(ns context.page
  (:require
   [file.xref :refer [->ref]]
   [utils.collection :refer [first-index]]
   [utils.dimension :refer [dim->points]])
  (:import
   [utils.dimension Dimension]))


;; This contains functions for creating and manipulating PDF page contexts
(defn root-pages-idx
  "Returns the index of the page root (Pages object) in the context's :objects vector"
  [ctx]
  (first-index #(= :pages (:type %1)) (:objects ctx)))

(defn root-pages-ref
  "Returns the indirect reference number of the page root (Pages object)"
  [ctx]
  (->ref (root-pages-idx ctx)))

;; Eventually maybe this is pure data as well without a reference.
;; would need updates within the context resolver
(defn with-page
  [ctx page-ctx]
  (let [pages-idx (root-pages-idx ctx)
        page-ctx' (assoc page-ctx :parent (->ref pages-idx))
        page-ref (->ref (count (:objects ctx)))]
    (-> ctx
        (update :objects conj page-ctx')
        (update-in [:objects pages-idx :kids] conj page-ref)
        (update-in [:objects pages-idx :count] (fnil inc 0)))))

(defn new-page
  "Creates a new page context for use within the pdf context."
  [^Dimension width ^Dimension height]
  {:type :page
   :mediaBox [0 0 (dim->points width) (dim->points height)]})

(defn with-stream
  "Adds a content stream to the page context."
  [page-ctx stream]
  (update page-ctx :contents conj stream))

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
  "Checks if a resource exists in the page resource context."
  [page-ctx resource-type resource]
  (some (fn [[k v]] (when (= v resource) k))
        (get-in page-ctx [page-ctx :resources resource-type])))

(defn add-or-get-resource
  "Adds the resource to the page and returns the key for usage."
  [page-ctx resource-type resource]
  (let [existing-resource (resource-exists? page-ctx resource-type resource)]
    (if existing-resource
      [page-ctx existing-resource]
      (let [res-key (next-resource-key page-ctx resource-type)]
        [(update page-ctx :resources assoc-in [resource-type res-key] resource) res-key]))))

