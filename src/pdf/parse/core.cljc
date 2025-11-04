(ns pdf.parse.core
  "The public parser entry: `parse` inverts `pdf.context.pdf/serialize`. Bytes
   of a clean, unencrypted PDF go in; a first-class library context comes out.
   Every `n g R` becomes a symbolic `Ref` to a minted stable id, so the result
   can be handed straight back to `serialize`.

   Assembly: read the xref, parse every in-use object, mint one stable id per
   object number, then rewrite every `IndirectRef` to a symbolic `Ref`."
  (:require
   [pdf.context.stream :refer [pdf-stream?]]
   [pdf.file.xref :as xref]
   [pdf.parse.lex :as lex]
   [pdf.parse.object :as object]
   [pdf.parse.objstm :as objstm]
   [pdf.parse.xref :as pxref]
   [pdf.bytes :as b]
   [pdf.utils.io :as io]))

;; Trailer keys that are recomputed or mechanical on re-serialize, so they
;; don't survive as trailer extras. Beyond the classic trio, the xref-stream
;; dict (which doubles as a trailer) carries stream mechanics too.
(def ^:private trailer-mechanics
  #{:size :root :prev :encrypt :type :index :w :filter :decode-parms :length :x-ref-stm})

(defn- parse-version
  "The `M.m` after `%PDF-` in the first 1024 bytes, as a double. No header
   throws."
  [src]
  (let [pat (b/ascii->bytes "%PDF-")
        limit (min (b/length src) 1024)]
    (loop [p 0]
      (cond
        (> (+ p 5) limit)
        (throw (ex-info "No %PDF- header found in the first 1024 bytes" {}))
        (every? (fn [i] (= (nth pat i) (b/ubyte src (+ p i)))) (range 5))
        (double (:value (lex/next-token src (+ p 5))))
        :else (recur (inc p))))))

(defn- object-loader
  "A memoized loader `obj-num -> {:obj-num :gen :obj :warnings}` (or nil for a
   free/absent number). Parses the object at its xref offset, resolving an
   indirect /Length through itself (a plain-integer target only)."
  [src entries]
  (let [cache (atom {})]
    (letfn [(resolve-ref [ref]
              (let [v (:obj (load (:obj-num ref)))]
                (when (integer? v) v)))
            (load [num]
              (if (contains? @cache num)
                (get @cache num)
                (let [entry (get entries num)
                      result (when (= :in-use (:type entry))
                               (first (object/parse-indirect
                                       src (:offset entry)
                                       {:resolve-ref resolve-ref})))]
                  (swap! cache assoc num result)
                  result)))]
      load)))

(defn- rewrite-refs
  "Walk `node`, converting each `IndirectRef` to a symbolic `Ref` via `ids`
   (object number -> stable id). A ref to a free or absent number resolves to
   nil (7.3.10) and records a `:dangling-ref` warning in `warns`."
  [ids warns node]
  (cond
    (xref/indirect-ref? node)
    (if-let [id (get ids (:obj-num node))]
      (xref/->ref id (:gen node))
      (do (swap! warns conj {:type :dangling-ref :obj-num (:obj-num node)}) nil))

    (pdf-stream? node) (update node :dict #(rewrite-refs ids warns %))
    (record? node)     node
    (map? node)        (reduce-kv (fn [m k v] (assoc m k (rewrite-refs ids warns v))) {} node)
    (sequential? node) (mapv #(rewrite-refs ids warns %) node)
    :else node))

(defn- catalog-version
  "The catalog's /Version override as a double when present and parseable, else
   nil (7.5.2). A spec /Version is a name, so it inverts to a symbol like `1.7`;
   only nameable values are considered (our own writer can emit a bare number
   there, which is skipped in favour of the header version)."
  [catalog]
  (let [v (:version catalog)]
    (when (or (string? v) (symbol? v) (keyword? v))
      (let [d #?(:clj  (parse-double (name v))
                 :cljs (js/parseFloat (name v)))]
        (when (and d #?(:clj true :cljs (not (js/isNaN d)))) d)))))

(defn parse
  "Parse a clean, unencrypted PDF byte source into a library context:
   `{:version v :objects [{:id .. :obj ..} ..] :trailer extras :warnings [..]}`.
   Throws on encryption, a broken xref, or a header/endobj mismatch."
  [src]
  (let [header-version (parse-version src)
        [entries trailer] (pxref/read-xref src)]
    (when (:encrypt trailer)
      (throw (ex-info "Encrypted PDFs are not supported (trailer has /Encrypt)" {})))
    (let [load (object-loader src entries)
          objstm-cache (atom {})
          get-objstm (fn [container]
                       (or (get @objstm-cache container)
                           (let [extracted (objstm/extract (:obj (load container)))]
                             (swap! objstm-cache assoc container extracted)
                             extracted)))
          ;; type-1 objects first (object-stream containers among them), then
          ;; type-2 objects pulled out of those containers
          records
          (into []
                (comp
                 (filter (fn [[_ e]] (not= :free (:type e))))
                 (map (fn [[num entry]]
                        (case (:type entry)
                          :in-use
                          (let [r (load num)]
                            (when-not (= num (:obj-num r))
                              (throw (ex-info "Object header number does not match xref"
                                              {:xref-number num :header-number (:obj-num r)})))
                            r)
                          :compressed
                          (let [{:keys [nums values]} (get-objstm (:objstm entry))
                                idx (:index entry)]
                            (when-not (= num (nth nums idx))
                              (throw (ex-info "Compressed object number does not match its object stream"
                                              {:xref-number num :objstm-number (nth nums idx)})))
                            {:obj-num num :gen 0 :obj (nth values idx)})))))
                (sort-by first entries))
          ids (into {} (map (fn [{:keys [obj-num]}]
                              [obj-num (gensym (str "parsed-" obj-num "-"))]))
                    records)
          warns (atom (into [] (mapcat :warnings) records))
          root-ref (:root trailer)
          _ (when-not (xref/indirect-ref? root-ref)
              (throw (ex-info "Trailer has no /Root reference" {})))
          root-record (some #(when (= (:obj-num root-ref) (:obj-num %)) %) records)
          _ (when-not (= :catalog (:type (:obj root-record)))
              (throw (ex-info "Trailer /Root does not point at a catalog" {})))]
      {:version (or (catalog-version (:obj root-record)) header-version)
       :objects (mapv (fn [{:keys [obj-num obj]}]
                        {:id (get ids obj-num)
                         :obj (rewrite-refs ids warns obj)})
                      records)
       :trailer (rewrite-refs ids warns (apply dissoc trailer trailer-mechanics))
       :warnings @warns})))

(defn parse-file
  "Parse the PDF at `path` (reads the file at the platform I/O edge, so JVM or
   node; browser builds have no filesystem and use `parse` over bytes)."
  [path]
  (parse (io/file->bytes path)))
