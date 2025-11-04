(ns pdf.context.page
  (:require
   [pdf.file.xref :refer [->ref]]
   [pdf.utils.collection :refer [first-index]]
   [pdf.utils.dimension :refer [->points]]))


;; This contains functions for creating and manipulating PDF page contexts
(defn root-pages-entry-idx
  "Returns the :objects index of the entry holding the root Pages object"
  [ctx]
  (first-index #(= :pages (:type (:obj %1))) (:objects ctx)))

(defn root-pages-ref
  "Returns a symbolic reference to the root Pages object"
  [ctx]
  (->ref (:id (nth (:objects ctx) (root-pages-entry-idx ctx)))))

(defn with-page
  [ctx page-ctx]
  (let [pages-idx (root-pages-entry-idx ctx)
        pages-id (:id (nth (:objects ctx) pages-idx))
        page-id (gensym "page-")
        page-ctx' (assoc page-ctx :parent (->ref pages-id))
        page-ref (->ref page-id)]
    (-> ctx
        (update :objects conj {:id page-id :obj page-ctx'})
        (update-in [:objects pages-idx :obj :kids] conj page-ref)
        (update-in [:objects pages-idx :obj :count] (fnil inc 0)))))

(defn new-page
  "Creates a new page context for use within the pdf context. `width`/`height`
   take numbers (points) or Dimensions."
  [width height]
  {:type :page
   :media-box [0 0 (->points width) (->points height)]})

(defn with-stream
  "Adds a content stream to the page context. Streams paint in the order added,
   so :contents grows as a vector (conj onto a nil seq would reverse them)."
  [page-ctx stream]
  (update page-ctx :contents (fnil conj []) stream))

(defn resource-type-prefix
  [resource-type]
  (case resource-type
    :font "F"
    :xobject "X"
    :color-space "CS"
    :pattern "P"
    :ext-gstate "GS"
    (throw (ex-info (str "Unknown resource type: " resource-type) {}))))

(defn next-resource-key
  [page-ctx resource-type]
  (str
   (resource-type-prefix resource-type)
   (count (get-in page-ctx [:resources resource-type]))))

(defn resource-keys
  "Map of context -> stable resource key (F0.., X0..) for `contexts`, minted
   with the same prefix scheme as next-resource-key."
  [resource-type contexts]
  (into {}
        (map-indexed (fn [i c] [c (str (resource-type-prefix resource-type) i)]))
        contexts))

(defn resource-key
  "The key `resource` is registered under in the page's resources, or nil."
  [page-ctx resource-type resource]
  (some (fn [[k v]] (when (= v resource) k))
        (get-in page-ctx [:resources resource-type])))

(defn add-or-get-resource
  "Adds the resource to the page and returns the key for usage."
  [page-ctx resource-type resource]
  (if-let [existing-key (resource-key page-ctx resource-type resource)]
    [page-ctx existing-key]
    (let [res-key (next-resource-key page-ctx resource-type)]
      [(update page-ctx :resources assoc-in [resource-type res-key] resource) res-key])))

