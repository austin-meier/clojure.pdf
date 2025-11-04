(ns context.pdf
  (:require
   [file.xref :refer [->ref]]
   [utils.collection :refer [first-index]]))


;; This contains the root PDF context structure and core functions for creating contexts
(defn new-pdf []
  {:version 2.0
   :objects
   [{:type :catalog
     :version 2.0
     :pages (->ref 1)}
    {:type :pages
     :kids []}]})

(defn get-catalog
  "Returns the document catalog object from the context"
  [ctx]
  (first (filter #(= :catalog (:type %1)) (:objects ctx))))

(defn catalog-ref
  "Returns the indirect reference number of the document catalog"
  [ctx]
  (->ref (first-index #(= :catalog (:type %1)) (:objects ctx))))

(defn add-object
  "Adds an object to the context's :objects vector and returns [updated-ctx obj-ref] for reducabilty"
  [ctx obj]
  (let [obj-ref (->ref (count (:objects ctx)))]
    [(update ctx :objects conj obj) obj-ref]))