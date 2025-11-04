(ns example.hello
  "The smallest useful document: one page, two lines of real embedded text.
   Generate from repo root with:
     clojure -M:examples -m example.hello"
  (:require
   [pdf.api :as pdf]
   [pdf.utils.dimension :refer [inches->dim]]))

(defn -main []
  (let [ubuntu (pdf/new-font "test/resources/fonts/Ubuntu-Regular.ttf")
        title  (assoc (pdf/new-text "Hello, PDF" ubuntu) :font-size 28)
        body   (pdf/new-text "A page is a map, a font is a map, and this file is data until save." ubuntu)]
    (-> (pdf/new-pdf)
        (pdf/with-page
          (-> (pdf/new-page (inches->dim 8.5) (inches->dim 11))
              (pdf/with-text (inches->dim 1) (inches->dim 9.5) title)
              (pdf/with-text (inches->dim 1) (inches->dim 9) body)))
        (pdf/save "example-hello.pdf"))
    (println "wrote example-hello.pdf")))
