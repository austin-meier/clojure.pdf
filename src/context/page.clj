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

(defn with-page
  [ctx page-ctx]
  (let [pages-idx (root-pages-idx ctx)
        page-ctx' (assoc page-ctx :parent (->ref pages-idx))
        page-ref (->ref (count (:objects ctx)))]
    (-> ctx
        (update :objects conj page-ctx')
        (update-in [:objects pages-idx :kids] conj page-ref))))

(defn new-page
  "Creates a new page context for use within the pdf context."
  [^Dimension width ^Dimension height]
  {:type :page
   :mediaBox [0 0 (dim->points width) (dim->points height)]})

(defn with-stream
  "Adds a content stream to the page context."
  [page-ctx stream]
  (update page-ctx :contents conj stream))

(defn add-resource
  "Adds resource to the page context."
  [page-ctx resource]
  (if (:resources page-ctx)
    (update page-ctx :resources merge resource)
    (assoc page-ctx :resources resource)))
