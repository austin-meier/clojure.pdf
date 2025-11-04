(ns pdf.context.pdf
  (:require
   [pdf.context.content :refer [ops->stream]]
   [pdf.context.form :refer [form-object]]
   [pdf.context.image.xobject :refer [image-object]]
   [pdf.context.predicates :refer [font-context? form-context? image-context?]]
   [pdf.context.stream :refer [pdf-stream?]]
   [pdf.context.text.cid :as cid]
   [pdf.file.body :refer [serialize-objects]]
   [pdf.file.chunk :refer [chunks->byte-array]]
   [pdf.file.header :refer [serialize-header]]
   [pdf.file.trailer :refer [serialize-trailer]]
   [pdf.file.xref :refer [->ref ->IndirectRef ref? indirect-ref? serialize-xref]]
   [pdf.utils.collection :refer [first-index]]
   [pdf.utils.io :as io]))


;; The context's :objects is a vector of entries {:id <stable-id> :obj <object>}.
;; Ids are stable symbolic identities minted here; object numbers are assigned
;; only at serialize time (see number-context), so objects can be added,
;; deduped or reordered without invalidating references.
(defn new-pdf []
  (let [catalog-id (gensym "catalog-")
        pages-id   (gensym "pages-")]
    {:version 2.0
     :objects
     [{:id catalog-id
       :obj {:type :catalog
             :version 2.0
             :pages (->ref pages-id)}}
      {:id pages-id
       :obj {:type :pages
             :kids []}}]}))

(defn- entry-of-type
  "The first :objects entry whose object has the given :type."
  [ctx t]
  (first (filter #(= t (:type (:obj %1))) (:objects ctx))))

(defn get-catalog
  "Returns the document catalog object from the context"
  [ctx]
  (:obj (entry-of-type ctx :catalog)))

(defn add-object
  "Adds an object to the context's :objects vector under a fresh stable id and
   returns [updated-ctx symbolic-ref] for reducibility"
  [ctx obj]
  (let [id (gensym "obj-")]
    [(update ctx :objects conj {:id id :obj obj}) (->ref id)]))

(defn- object-index
  "Map of object value -> stable id for every current :objects entry, keeps
   the first id when equal objects occur twice."
  [ctx]
  (reduce (fn [m {:keys [id obj]}] (if (contains? m obj) m (assoc m obj id)))
          {}
          (:objects ctx)))

(defn- swap-with-ref
  "If an equal object is already indexed, returns [ctx ref] to it. Otherwise
   adds and indexes the object and returns updated ctx + new ref."
  [ctx obj]
  (if-let [id (get-in ctx [::obj-index obj])]
    [ctx (->ref id)]
    (let [[ctx' ref] (add-object ctx obj)]
      [(assoc-in ctx' [::obj-index obj] (:id ref)) ref])))

(defn serializable-map? [m]
  (and (map? m)
       (not (pdf-stream? m))
       (not (ref? m))
       (not (indirect-ref? m))))

(declare resolve-value)

(defn- resolve-map-values
  "Resolve every value of map `m`, threading the context. Returns [ctx m']."
  [ctx m]
  (reduce (fn [[acc-ctx acc-map] [k val]]
            (let [[new-ctx val'] (resolve-value acc-ctx val)]
              [new-ctx (assoc acc-map k val')]))
          [ctx {}]
          m))

(defn- resolve-value
  [ctx v]
  (cond
    (pdf-stream? v)
    (swap-with-ref ctx v)

    (serializable-map? v)
    ;; Nested maps become indirect objects: resolve the values, then add the
    ;; map and return its ref. Eventually could add magic to inline small dicts instead.
    (let [[ctx' resolved-map] (resolve-map-values ctx v)]
      (add-object ctx' resolved-map))

    (sequential? v)
    (reduce (fn [[acc-ctx acc-vec] elem]
              (let [[new-ctx e'] (resolve-value acc-ctx elem)]
                [new-ctx (conj acc-vec e')]))
            [ctx []]
            v)

    :else
    [ctx v]))

(defn- resolve-object
  "Resolve and update the object in the entry at index `idx` of ctx's :objects.
   Resolving threads a new context with any nested values hoisted to indirect
   references (and their objects appended as new entries)."
  [ctx idx]
  (let [obj (:obj (nth (:objects ctx) idx))]
    (cond
      ;; A top-level stream dict holds only inline values and refs.
      ;; resolving it would swap-with-ref it against itself.
      (pdf-stream? obj)
      ctx

      ;; Top-level maps don't become indirect objects themselves, only their
      ;; nested values are hoisted.
      (serializable-map? obj)
      (let [[ctx' resolved-map] (resolve-map-values ctx obj)]
        (assoc-in ctx' [:objects idx :obj] resolved-map))

      :else
      ;; Resolve non map values
      (let [[ctx' resolved] (resolve-value ctx obj)]
        (assoc-in ctx' [:objects idx :obj] resolved)))))

(defn- update-page-objects
  "Apply `f` to every page object in the context's :objects vector."
  [ctx f]
  (update ctx :objects
          (fn [entries]
            (mapv #(cond-> % (= :page (:type (:obj %))) (update :obj f)) entries))))

(defn- page-resource-contexts
  "Values under [:resources resource-type] matching `pred` across every page,
   distinct by `key-fn` in stable first-seen order, so one context reused on
   several pages embeds once."
  [ctx resource-type pred key-fn]
  (second
   (reduce (fn [[seen out :as acc] res]
             (let [k (key-fn res)]
               (if (contains? seen k) acc [(conj seen k) (conj out res)])))
           [#{} []]
           (into []
                 (comp (filter #(= :page (:type (:obj %))))
                       (mapcat #(vals (get-in (:obj %) [:resources resource-type])))
                       (filter pred))
                 (:objects ctx)))))

(defn- embed-page-resources
  "Add one embedded object per distinct `resource-type`
   resource matching `pred` on the pages, then swap those page resource values over to
   the refs."
  ([ctx resource-type pred add-embedded]
   (embed-page-resources ctx resource-type pred identity add-embedded))
  ([ctx resource-type pred key-fn add-embedded]
   (let [[ctx refs] (reduce (fn [[ctx refs] res]
                              (let [[ctx ref] (add-embedded ctx res)]
                                [ctx (assoc refs (key-fn res) ref)]))
                            [ctx {}]
                            (page-resource-contexts ctx resource-type pred key-fn))]
     (update-page-objects
      ctx
      (fn [page]
        (cond-> page
          (get-in page [:resources resource-type])
          (update-in [:resources resource-type]
                     (fn [m]
                       (into {} (map (fn [[k v]] [k (get refs (key-fn v) v)])) m)))))))))

(defn compile-content-ops
  "compile the raw op batches on each page's :contents into
   content streams, merging adjacent batches into one stream."
  [ctx]
  (update-page-objects
   ctx
   (fn [page]
     (let [contents (:contents page)]
       (cond-> page
         (some? contents)
         (assoc :contents
                (into []
                      (comp (partition-by vector?)
                            (mapcat (fn [run]
                                      (if (vector? (first run))
                                        [(ops->stream (into [] cat run))]
                                        run))))
                      (if (sequential? contents) contents [contents]))))))))

(defn- content-key
  "Dedupe key for an embeddable resource context: its content hash when it
   carries one or else the value itself."
  [ctx]
  (or (:sha1 ctx) ctx))

(defn- page-font-usage
  "Merged glyph usage ({gid -> codepoint}) per distinct font"
  [ctx]
  (reduce
   (fn [usage {:keys [obj]}]
     (if (= :page (:type obj))
       (reduce-kv (fn [usage res-key font]
                    (if (font-context? font)
                      (update usage (content-key font) (fnil merge {})
                              (get-in obj [:font-usage res-key]))
                      usage))
                  usage
                  (get-in obj [:resources :font]))
       usage))
   {}
   (:objects ctx)))

(defn embed-fonts
  "Build one embedded CID font object (Type0 over a subset font
   program) per distinct font used on the pages, then swap the page font
   resources over to refs."
  [ctx]
  (let [usage (page-font-usage ctx)]
    (-> ctx
        (embed-page-resources :font font-context? content-key
                              (fn [ctx font]
                                (add-object ctx (cid/font-object font (usage (content-key font))))))
        (update-page-objects #(dissoc % :font-usage)))))

(defn- add-image-object
  "Add the embedded objects for one image context and return [ctx ref]"
  [ctx img]
  (let [built (image-object img)]
    (if (pdf-stream? built)
      (add-object ctx built)
      (let [{:keys [image smask]} built
            [ctx smask-ref] (add-object ctx smask)]
        (add-object ctx (assoc-in image [:dict :smask] smask-ref))))))

(defn embed-images
  "Build one Image XObject per distinct image context used on
   the pages, then swap the page xobject resources over to refs."
  [ctx]
  (embed-page-resources ctx :xobject image-context?
                        content-key
                        add-image-object))

(defn embed-forms
  "Build one Form XObject per distinct form context used on the
   pages, then swap the page xobject resources over to refs."
  [ctx]
  (embed-page-resources ctx :xobject form-context?
                        (fn [ctx form] (add-object ctx (form-object form)))))

(defn resolve-context-objects
  "Walk the pdf context's :objects vector and resolve nested values so
   that maps, vectors and streams are converted into indirect objects
   appended to the context. Returns the updated pdf context."
  [ctx]
  (-> (reduce resolve-object
              (assoc ctx ::obj-index (object-index ctx))
              (range (count (:objects ctx))))
      (dissoc ::obj-index)))

(defn- object-numbers
  "Map of stable object id -> PDF object number, assigned by :objects order."
  [ctx]
  (into {} (map-indexed (fn [i entry] [(:id entry) (inc i)]) (:objects ctx))))

(defn- concretize
  "Rewrite every symbolic Ref in `node` to a numbered IndirectRef using the
   id->number map"
  [numbers node]
  (cond
    (ref? node)        (->IndirectRef (get numbers (:id node)) (:gen node))
    (pdf-stream? node) (assoc node :dict (concretize numbers (:dict node)))
    (record? node)     node
    (map? node)        (reduce-kv (fn [m k v] (assoc m k (concretize numbers v))) {} node)
    (sequential? node) (mapv #(concretize numbers %) node)
    :else              node))

(defn number-context
  "Assign object numbers by order, rewrite symbolic refs to numbered indirect
   refs, and flatten :objects to a plain vector of numbered objects."
  [ctx]
  (let [numbers (object-numbers ctx)]
    (cond-> (assoc ctx :objects (mapv #(concretize numbers (:obj %)) (:objects ctx)))
      (:trailer ctx) (update :trailer #(concretize numbers %)))))

(defn catalog-ref
  "Numbered indirect reference to the catalog, over a flattened (numbered) ctx."
  [ctx]
  (->IndirectRef (inc (first-index #(= :catalog (:type %1)) (:objects ctx))) 0))

(defn new-serialization-context [pdf-ctx]
  {:chunks [] ;; file content in order: strings of PDF syntax and byte arrays of raw payloads
   :length 0 ;; total byte length of :chunks. object offsets and startxref read this
   :object-offsets [] ;; list of byte offsets for each object. populated by serialize-objects
   :catalog-ref (catalog-ref pdf-ctx) ;; indirect reference to the catalog object
   :xref-offset 0 ;; byte offset for xref table. populated by serialize-xref
   :ctx pdf-ctx})

(defn serialization-chunks
  "Runs the full serialization pipeline and returns the PDF file as chunks"
  [pdf-ctx]
  (->
   pdf-ctx
   compile-content-ops
   embed-fonts
   embed-images
   embed-forms
   resolve-context-objects
   number-context
   new-serialization-context
   serialize-header
   serialize-objects
   serialize-xref
   serialize-trailer
   :chunks))

(defn serialize
  "Serialize the PDF context to a platform byte source"
  [pdf-ctx]
  (chunks->byte-array (serialization-chunks pdf-ctx)))

(defn save
  "Serialize the PDF context and write it to `path`"
  [pdf-ctx path]
  (io/bytes->file path (serialize pdf-ctx)))