(ns context.pdf
  (:require
   [context.pdf :as pdf]
   [context.stream :refer [pdf-stream?]]
   [file.body :refer [serialize-objects]]
   [file.common :refer [bytes->string]]
   [file.header :refer [serialize-header]]
   [file.trailer :refer [serialize-trailer]]
   [file.xref :refer [->ref indirect-ref? serialize-xref]]
   [utils.collection :refer [first-index]]))


;; This contains the root PDF context structure and core functions for creating contexts
(defn new-pdf []
  {:version 1.7
   :objects
   [{:type :catalog
     :version 1.7
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

(defn- swap-with-ref
  "If the object is already in the context, returns [ctx obj-ref]. Otherwise adds the object and returns updated ctx + new ref."
  [ctx obj]
  (if-let [existing-idx (first-index #(= obj %1) (:objects ctx))]
    [ctx (->ref existing-idx)]
    (add-object ctx obj)))

(defn serializable-map? [m]
  (and (map? m)
       ;; Library records that will be serialized differently
       ;; should not be treated as serializable maps
       (not (pdf-stream? m))
       (not (indirect-ref? m))))

(defn- resolve-value
  [ctx v]
  ;;(println v)
  (cond
    (pdf-stream? v)
    (let [[ctx' ref] (swap-with-ref ctx v)]
      [ctx' ref])

    (serializable-map? v)
    (let [[ctx' resolved-map]
          (reduce (fn [[acc-ctx acc-map] [k val]]
                    (let [[new-ctx val'] (resolve-value acc-ctx val)]
                      [new-ctx (assoc acc-map k val')]))
                  [ctx {}]
                  v)]
      ;; Add the resolved map as an indirect object and return its ref
      ;; Eventually can add more fancy logic like if less than 4 keys inline it
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
  "Resolve and update the object at index `idx` in ctx's :objects vector.
   Resolving will also reduce a new context with any required values converted to indirect references."
  [ctx idx]
  (let [obj (nth (:objects ctx) idx)]
    (if (serializable-map? obj)
      ;; For top-level objects we want to keep the resolved map in-place
      ;; (don't add it as a new indirect object). Resolve nested values
      ;; inside the map but then store the resulting map back at `idx`.
      (let [[ctx' resolved-map]
            (reduce (fn [[acc-ctx acc-map] [k val]]
                      (let [[new-ctx val'] (resolve-value acc-ctx val)]
                        [new-ctx (assoc acc-map k val')]))
                    [ctx {}]
                    obj)
            objs (assoc (:objects ctx') idx resolved-map)]
        (assoc ctx' :objects objs))
      ;; Non-map objects: use the general resolver which may append indirect objects
      (let [[ctx' resolved] (resolve-value ctx obj)
            objs (assoc (:objects ctx') idx resolved)]
        (assoc ctx' :objects objs)))))

(defn resolve-context-objects
  "Walk the pdf context's :objects vector and resolve nested values so
   that maps, vectors and streams are converted into indirect objects
   appended to the context. Returns the updated pdf context."
  [ctx]
  (reduce (fn [acc-ctx idx]
            (resolve-object acc-ctx idx))
          ctx
          (range (count (:objects ctx)))))

(defn new-serialization-context [pdf-ctx]
  {:serialized-bytes [] ;; The actual byte array of the serialized PDF. Converts to UTF-8 string at the end.
   :object-offsets [] ;; list of byte offsets for each object. populated by serialize-objects
   :catalog-ref (catalog-ref pdf-ctx) ;; indirect reference to the catalog object
   :xref-offset 0 ;; byte offset for xref table. populated by serialize-xref
   :ctx pdf-ctx})

(defn serialize
  "Write the PDF context to a serialized string"
  [pdf-ctx]
  (println (resolve-context-objects pdf-ctx))
  (->
   pdf-ctx
   resolve-context-objects
   new-serialization-context
   serialize-header
   serialize-objects
   serialize-xref
   serialize-trailer
   :serialized-bytes
   (bytes->string "UTF-8")))