(ns example.parse
  "build a PDF, read it back into a context, edit the plain map,
   and save it again. The parser simply inverts the serializer.

   Generate from repo root with:
     clojure -M:examples -m example.parse"
  (:require
   [pdf.api :as pdf]
   [pdf.utils.dimension :refer [inches->dim]]))

(defn example-pdf []
  (let [ubuntu (pdf/new-font "test/resources/fonts/Ubuntu-Regular.ttf")]
    (-> (pdf/new-pdf)
        (pdf/with-page
          (-> (pdf/new-page (inches->dim 8.5) (inches->dim 11))
              (pdf/with-text (inches->dim 1) (inches->dim 9)
                (pdf/new-text "Written, then read back." ubuntu)))))))

(defn -main []
  ;; produce and then read a pdf back to rotate it
  (let [ctx (pdf/parse (pdf/serialize example-pdf))]
    (println "parsed" (count (:objects ctx)) "objects, version" (:version ctx))
    (println "warnings:" (:warnings ctx))
    ;; it's just a map, rotate every page a quarter turn
    (let [rotated (update ctx :objects
                          (fn [objs]
                            (mapv (fn [{:keys [obj] :as entry}]
                                    (if (= :page (:type obj))
                                      (assoc-in entry [:obj :rotate] 90)
                                      entry))
                                  objs)))]
      (pdf/save rotated "example-parse-output.pdf")
      (println "wrote example-parse-output.pdf (same document, turned sideways)"))))
