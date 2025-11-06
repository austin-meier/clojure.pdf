(ns context.page
  (:require
   [context.pdf :refer [catalog-idx]]
   [utils.dimension :refer [dim->points]])
  (:import
   [utils.dimension Dimension]))


;; This contains functions for creating and manipulating PDF page contexts

;; Eventually maybe this is pure data as well without a reference.
;; would need updates within the context resolver
(defn with-page
  [ctx page-ctx]
  (let [catalog-idx (catalog-idx ctx)]
  (println "Adding page to catalog idx:" catalog-idx)
    (-> ctx
        (update :objects conj page-ctx)
        (update-in [:objects catalog-idx :pages :kids] conj page-ctx)
        (update-in [:objects catalog-idx :pages :count] (fnil inc 0)))))

(defn new-page
  "Creates a new page context for use within the pdf context."
  [^Dimension width ^Dimension height]
  {:type :page
   :parent nil ;; to be set by resolver
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
