(ns pdf.serialize-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.serialize :refer [to-pdf]]))


(deftest clojure-core-to-pdf
  (testing "Strings serialize correctly"
    (is (= "(Hello)" (to-pdf "Hello"))))

  (testing "Strings escape parens and backslashes"
    (is (= "(a\\(b\\)c\\\\)" (to-pdf "a(b)c\\"))))

  (testing "Strings escape CR and LF so bytes survive a round trip (7.3.4.2)"
    (is (= "(a\\rb)" (to-pdf "a\rb")))
    (is (= "(a\\nb)" (to-pdf "a\nb"))))

  (testing "Numbers serialize correctly"
    (is (= "42" (to-pdf 42))))

  (testing "Non-integers serialize as plain decimals — PDF has no ratio or
            exponent syntax"
    (is (= "0.3333" (to-pdf 1/3)))
    (is (= "1.5" (to-pdf 1.5)))
    (is (= "1" (to-pdf 1.0)))
    (is (= "0" (to-pdf 1.0E-9)))
    (is (thrown? clojure.lang.ExceptionInfo (to-pdf ##Inf))))

  (testing "Keywords serialize as PDF names"
    (is (= "/Type" (to-pdf :type))))

  (testing "Multi-word keywords keep interior capitals (no tail lower-casing)"
    (is (= "/MediaBox" (to-pdf :media-box)))
    (is (= "/FontBBox" (to-pdf :font-b-box))))

  (testing "Aliased keywords use their exact PDF name"
    (is (= "/ID" (to-pdf :id)))
    (is (= "/CIDToGIDMap" (to-pdf :cid-to-gid-map))))

  (testing "Symbols are the exact-name escape hatch"
    (is (= "/ca" (to-pdf 'ca)))
    (is (= "/CA" (to-pdf 'CA))))

  (testing "Symbol dict keys keep their exact name (not coerced to keywords)"
    (is (= "<< /ca 1 >>" (to-pdf {'ca 1})))
    (is (= "<< /Foo-Bar 1 >>" (to-pdf {'Foo-Bar 1}))))

  (testing "Vectors serialize as arrays"
    (is (= "[1 2 3]" (to-pdf [1 2 3]))))

  (testing "Maps serialize as dictionaries"
    (is (= "<< /Type /Page /Count 3 >>"
           (to-pdf {:type :page :count 3})))))