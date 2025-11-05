(ns test
  (:require
   [context.page :refer [new-page with-page with-stream]]
   [context.pdf :refer [new-pdf]]
   [context.stream :refer [string->stream]]
   [file.pdf :refer [serialize]]
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

(defn -main []
  (println (validate-context pdf-ctx))
  (spit "./test.pdf" (serialize pdf-ctx)))
