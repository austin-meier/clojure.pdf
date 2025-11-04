(ns file.trailer-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [file.trailer :refer [build-trailer-map serialize-trailer]]))

(defonce test-pdf-ctx
  {:version 2.0
   :objects
     [{:type :catalog}]})

(deftest pdf-trailer-serialization
  (testing "PDF trailer serialization"
    (is (= "" (serialize-trailer test-pdf-ctx)))))