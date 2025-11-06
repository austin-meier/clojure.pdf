(ns context.text.core
  (:require
   [context.stream :refer [string->stream]]
   [utils.dimension :refer [dim->points]]))

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
   (count (get-in page-ctx [page-ctx :resources resource-type]))))

(defn resource-exists?
  [page-ctx resource-type resource]
  (some (fn [[k v]] (when (= v resource) k))
        (get-in page-ctx [page-ctx :resources resource-type])))

(defn add-resource
  "Adds the resource to the page and returns the key for usage."
  [page-ctx resource-type resource]
  (let [existing-resource (resource-exists? page-ctx resource-type resource)]
    (if existing-resource
      [page-ctx existing-resource]
      (let [res-key (next-resource-key page-ctx resource-type)]
        [(update page-ctx :resources assoc-in [resource-type res-key] resource) res-key]))))

(defn with-text
  "Adds a text stream to the page context."
  [page-ctx txt-ctx]
  (let [[page-ctx font-key] (add-resource page-ctx :font (:font txt-ctx))]
    (update page-ctx :contents conj (:text txt-ctx))))

(defn new-text
  "Creates a new text context for use on a page."
  [x y font-ctx text]
  {:text (string->stream (format "BT
/F0 24 Tf
%f %f Td
(%s) Tj
ET" (dim->points x) (dim->points y) text))
   :font font-ctx})