(ns pdf.file.trailer-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.file.trailer :refer [build-trailer-map serialize-trailer]]
   [pdf.file.xref :refer [->IndirectRef]]))

;; A serialize-time context: :objects is the flattened, numbered vector and
;; :catalog-ref is already a numbered IndirectRef (object 1).
(def serialization-ctx
  {:chunks []
   :length 0
   :object-offsets [15 60]
   :catalog-ref (->IndirectRef 1 0)
   :xref-offset 123
   :ctx {:objects [{:type :catalog} {:type :pages}]}})

(deftest trailer-map
  (testing "Trailer map carries the object count and catalog ref"
    (is (= {:size 2 :root (->IndirectRef 1 0)} (build-trailer-map serialization-ctx)))))

(deftest pdf-trailer-serialization
  (testing "Trailer chunk includes the dict, startxref offset and EOF marker"
    (is (= "trailer\n<< /Size 2 /Root 1 0 R >>\nstartxref\n123\n%%EOF\n"
           (first (:chunks (serialize-trailer serialization-ctx)))))))
