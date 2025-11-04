(ns example.just-data
  "Draw with raw content ops, set a PDF page key the
   high-level API has no builder for, and look at the document as the plain
   map it is.

   Generate from repo root with:
     clojure -M:examples -m example.just-data"
  (:require
   [clojure.pprint :refer [pprint]]
   [pdf.api :as pdf]
   [pdf.context.content :refer [ops->stream]]
   [pdf.utils.dimension :refer [inches->dim]]
   [pdf.validation.context :refer [validate-context]]))

(def crosshatch
  ;; content streams are vectors of [op & operands]; see pdf.context.operators
  (ops->stream
   (into [[:set-rgb-stroke 0.35 0.51 0.85] [:set-line-width 1]]
         (mapcat (fn [i]
                   [[:move-to (* i 20) 400] [:line-to (+ 200 (* i 20)) 600]
                    [:move-to (+ 200 (* i 20)) 400] [:line-to (* i 20) 600]
                    [:stroke]])
                 (range 11)))))

(defn -main []
  (let [page (-> (pdf/new-page (inches->dim 8.5) (inches->dim 11))
                 (pdf/with-stream crosshatch)
                 ;; no builder for /Rotate, but it's a map, so just put it there
                 (assoc :rotate 90))
        ctx  (pdf/with-page (pdf/new-pdf) page)]
    (println "the document context is a map:")
    (pprint (update ctx :objects (fn [objs] (mapv #(update % :obj select-keys [:type]) objs))))
    ;; can run the map through validations before rendering to a pdf
    (println "validate-context says:" (validate-context ctx))
    (pdf/save ctx "example-just-data.pdf")
    (println "wrote example-just-data.pdf (sideways on purpose)")))
