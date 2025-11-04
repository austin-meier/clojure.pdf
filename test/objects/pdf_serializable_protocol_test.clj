(ns objects.pdf-serializable-protocol-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [objects.pdf-serializable-protocol :refer [to-pdf]]))


(deftest clojure-core-to-pdf
  (testing "Strings serialize correctly"
    (is (= "(Hello)" (to-pdf "Hello"))))

  (testing "Numbers serialize correctly"
    (is (= "42" (to-pdf 42))))

  (testing "Keywords serialize as PDF names"
    (is (= "/Type" (to-pdf :type))))

  (testing "Vectors serialize as arrays"
    (is (= "[1 2 3]" (to-pdf [1 2 3]))))

  (testing "Maps serialize as dictionaries"
    (is (= "<< /Type /Page /Count 3 >>"
           (to-pdf {:type :page :count 3})))))