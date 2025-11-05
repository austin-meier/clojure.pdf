(ns context.page
  (:require
   [context.pdf :refer [add-object]]
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


(defn- reduce-contents
  [ctx page-ctx]
  (let [contents (or (:contents page-ctx) [])
        init [ctx (assoc page-ctx :contents [])]
        step (fn [[acc-ctx acc-page] content]
               (let [[new-ctx obj-ref] (add-object acc-ctx content)
                     acc-page' (update acc-page :contents conj obj-ref)]
                 [new-ctx acc-page']))]
    (reduce step init contents)))

(defn with-page
  [ctx page-ctx]
  (let [pages-idx (root-pages-idx ctx)
        [ctx-after-contents page-ctx'] (reduce-contents ctx (assoc page-ctx :parent (->ref pages-idx)))
        page-ref (->ref (count (:objects ctx-after-contents)))]
    (-> ctx-after-contents
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
