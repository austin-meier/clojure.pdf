(ns test
  (:require
   [context.page :refer [new-page with-page with-stream]]
   [context.pdf :refer [new-pdf serialize]]
   [context.stream :refer [string->stream]]
   [context.text.core :refer [new-text with-text]]
   [context.text.font :refer [new-font]]
   [utils.dimension :refer [inches->dim]]
   [validation.context :refer [validate-context]]))

(def draw-stream
  (str
   "0 1 1 0 k % Set fill color cmyk red\n"
   "0 0 0 1 K % Set stroke color cmyk black\n"
   "100 500 200 100 re  % draw rectangle\n"
   "B % fill+stroke path zero-wind"))

(def pdf-ctx
  (->
   (new-pdf)
   (with-page (-> (new-page (inches->dim 8.5) (inches->dim 11))
                  (with-stream (string->stream draw-stream))))))

(let 2)

(def pdf-ctx-2
     (->
      (new-pdf)
      (with-page (->
                  (new-page (inches->dim 8.5) (inches->dim 11))
                  (with-text
                   (inches->dim 3) (inches->dim 10)
                   (new-text
                    "I just be some text"
                    (new-font "./Ubuntu-Regular.ttf")
                    ))))))

(defn -main []
  (println (validate-context pdf-ctx))
  (println pdf-ctx-2)
  (spit "./test.pdf" (serialize pdf-ctx-2)))
