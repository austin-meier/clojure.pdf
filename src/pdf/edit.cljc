(ns pdf.edit
  "Document-level page-tree editing. A parsed (or authored) context can nest
   :pages nodes to any depth, and a leaf :page inherits attributes (Resources,
   MediaBox, CropBox, Rotate) from its ancestors (7.7.3.4). That structure is
   awkward for editing: to add, drop, reorder or merge pages you'd have to know
   where every leaf lives and what it inherits.

   `normalize` collapses all of that. It materializes each leaf page's inherited
   attributes onto the page itself, re-parents every leaf directly under the
   catalog's root Pages node in reading order, and drops the intermediate nodes.
   The result is a flat, self-contained page list that the higher-level edit ops
   (merge, insert, delete, reorder) can treat as a plain vector."
  (:require
   [pdf.context.pdf :refer [get-catalog]]
   [pdf.context.stream :refer [pdf-stream?]]
   [pdf.file.xref :refer [->ref ref?]]))

(def inheritable
  "Page attributes a leaf :page inherits from ancestor :pages nodes (7.7.3.4)."
  #{:resources :media-box :crop-box :rotate})

(defn- object-index
  "Map of stable id -> object for every :objects entry, for ref resolution."
  [ctx]
  (into {} (map (juxt :id :obj)) (:objects ctx)))

(defn- root-pages-ref
  "The catalog's /Pages reference. Throws if the context has no root page tree."
  [ctx]
  (let [pages-ref (:pages (get-catalog ctx))]
    (when-not (ref? pages-ref)
      (throw (ex-info "Context has no root page tree (catalog /Pages is not a reference)"
                      {:pages pages-ref})))
    pages-ref))

(defn- collect-leaves
  "Ordered vector of [leaf-page-id inherited-attrs] for every leaf :page
   reachable from `ref`, threading the inheritable attributes down the tree. A
   nil kid (a dangling ref the parser already nilled, 7.3.10) is skipped; a node
   that resolves to neither a page nor a pages node is a corrupt tree and throws."
  [idx ref inherited]
  (if (nil? ref)
    []
    (let [obj (get idx (:id ref))]
      (case (:type obj)
        :page  [[(:id ref) inherited]]
        :pages (let [inherited' (merge inherited (select-keys obj inheritable))]
                 (into [] (mapcat #(collect-leaves idx % inherited')) (:kids obj)))
        (throw (ex-info "Page tree node is neither a page nor a pages node"
                        {:id (:id ref) :type (:type obj)}))))))

(defn pages
  "Ordered vector of the document's leaf page objects with inherited attributes
   materialized (a page's own value always wins). Read-only: it resolves the
   page tree but does not modify the context."
  [ctx]
  (let [idx (object-index ctx)]
    (mapv (fn [[id inherited]] (merge inherited (get idx id)))
          (collect-leaves idx (root-pages-ref ctx) {}))))

(defn normalize
  "Collapse the page tree to a single flat level. Postconditions: exactly one
   :pages node (the catalog's root); its :kids reference every leaf page in
   reading order and :count matches; every leaf page carries its inherited
   attributes explicitly and points :parent at the root; the intermediate :pages
   nodes are gone. Idempotent on an already-flat tree."
  [ctx]
  (let [idx       (object-index ctx)
        root-ref  (root-pages-ref ctx)
        root-id   (:id root-ref)
        leaves    (collect-leaves idx root-ref {})
        inh-by-id (into {} leaves)
        leaf-ids  (into #{} (map first) leaves)
        kid-refs  (mapv (comp ->ref first) leaves)
        ;; every :pages node except the root is an intermediate node to drop
        drop-ids  (into #{}
                        (comp (filter #(= :pages (:type (:obj %))))
                              (map :id)
                              (remove #(= root-id %)))
                        (:objects ctx))]
    (update ctx :objects
            (fn [entries]
              (into []
                    (comp
                     (remove #(contains? drop-ids (:id %)))
                     (map (fn [{:keys [id obj] :as entry}]
                            (cond
                              ;; root: inheritable attrs now live on each leaf, so
                              ;; strip them here to keep a single source of truth
                              (= id root-id)
                              (assoc entry :obj (-> (apply dissoc obj inheritable)
                                                    (assoc :kids kid-refs
                                                           :count (count kid-refs))))

                              (contains? leaf-ids id)
                              (assoc entry :obj (-> (merge (inh-by-id id) obj)
                                                    (assoc :parent (->ref root-id))))

                              :else entry))))
                    entries)))))

(defn- ref-ids
  "Every stable id referenced by a `Ref` anywhere inside `node` (descending maps,
   vectors, and a stream's dict, but not other records)."
  [node]
  (cond
    (ref? node)        [(:id node)]
    (pdf-stream? node) (ref-ids (:dict node))
    (record? node)     []
    (map? node)        (into [] (mapcat ref-ids) (vals node))
    (sequential? node) (into [] (mapcat ref-ids) node)
    :else              []))

(defn- reachable-ids
  "The set of object ids reachable from `seeds` by following refs through `idx`
   (id -> object). Used to carry only the objects a page actually needs, so
   catalog-level things (outlines, form fields, orphan metadata) don't tag along."
  [idx seeds]
  (loop [seen #{}, stack (vec seeds)]
    (if-let [id (peek stack)]
      (if (contains? seen id)
        (recur seen (pop stack))
        (recur (conj seen id) (into (pop stack) (ref-ids (get idx id)))))
      seen)))

(defn- update-obj-by-id
  "Apply `f` to the object of the :objects entry whose id is `id`."
  [ctx id f]
  (update ctx :objects
          (fn [entries]
            (mapv #(cond-> % (= id (:id %)) (update :obj f)) entries))))

(defn- fresh-ids
  "Remap every stable id in `ctx` to a fresh one, rewriting all refs to match, so
   its ids can't collide with the document it's merged into. Parsed docs already
   have unique ids, but authored ones (or a self-merge) might not."
  [ctx]
  (let [remap   (into {} (map (fn [{:keys [id]}] [id (gensym "merged-")])) (:objects ctx))
        rewrite (fn rewrite [node]
                  (cond
                    (ref? node)        (->ref (get remap (:id node) (:id node)) (:gen node))
                    (pdf-stream? node) (update node :dict rewrite)
                    (record? node)     node
                    (map? node)        (reduce-kv (fn [m k v] (assoc m k (rewrite v))) {} node)
                    (sequential? node) (mapv rewrite node)
                    :else              node))]
    (update ctx :objects
            (fn [entries]
              (mapv (fn [{:keys [id obj]}] {:id (get remap id) :obj (rewrite obj)}) entries)))))

(defn- append-pages
  "Append every page of `b` onto `a`'s page tree. Both are normalized first, so
   each is a single flat level of self-contained leaves. b's leaves are
   re-parented onto a's root and carried over with the objects they reach; b's
   catalog, root pages node, and anything only the catalog referenced are left
   behind. Refs stay valid across the join because object numbers aren't assigned
   until serialize time."
  [a b]
  (let [a         (normalize a)
        b         (fresh-ids (normalize b))
        a-root-id (:id (root-pages-ref a))
        b-root    (get (object-index b) (:id (root-pages-ref b)))
        leaf-ids  (mapv :id (:kids b-root))
        leaf-set  (set leaf-ids)
        ;; re-parent b's leaves before walking, so the reach can't wander back up
        ;; into b's now-defunct root pages node
        b-objs    (mapv (fn [{:keys [id] :as entry}]
                          (cond-> entry
                            (leaf-set id) (assoc-in [:obj :parent] (->ref a-root-id))))
                        (:objects b))
        keep      (reachable-ids (into {} (map (juxt :id :obj)) b-objs) leaf-set)
        carried   (filterv #(keep (:id %)) b-objs)]
    (-> a
        (update-obj-by-id a-root-id
                          (fn [root]
                            (-> root
                                (update :kids into (mapv ->ref leaf-ids))
                                (update :count + (count leaf-ids)))))
        (update :objects into carried))))

(defn merge-pdfs
  "Merge documents into one, concatenating their pages in argument order. Each is
   normalized, so nested page trees and inherited attributes are handled; only
   the pages (and the objects they reference) carry over — catalog-level features
   like outlines and form fields are dropped. Returns the first document when
   called with one."
  [a & more]
  (reduce append-pages a more))
