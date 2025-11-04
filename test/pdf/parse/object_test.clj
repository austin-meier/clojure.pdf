(ns pdf.parse.object-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.stream :as stream]
   [pdf.file.xref :as xref]
   [pdf.parse.object :as object]
   [pdf.bytes :as b]))

(defn- src [s] (b/from-unsigned (b/ascii->bytes s)))

(defn- pv
  "The parsed value of `s` (position dropped)."
  [s]
  (first (object/parse-value (src s) 0)))

(deftest scalars
  (is (= 42 (pv "42")))
  (is (= 3.14 (pv "3.14")))
  (is (= true (pv "true")))
  (is (= false (pv "false")))
  (is (= nil (pv "null")))
  (is (= "hi" (pv "(hi)")))
  (is (= "Hi" (pv "<4869>"))))

(deftest names
  (testing "registered names invert to keywords"
    (is (= :type (pv "/Type"))))
  (testing "unregistered names invert to exact-name symbols"
    (is (= 'Foo (pv "/Foo")))))

(deftest arrays
  (is (= [1 2 3] (pv "[1 2 3]")))
  (is (= [[1] [2]] (pv "[[1] [2]]")))
  (testing "nulls are kept in arrays"
    (is (= [1 nil 2] (pv "[1 null 2]")))))

(deftest ref-lookahead
  (testing "n g R folds into one IndirectRef"
    (is (= [(xref/->IndirectRef 1 2)] (pv "[1 2 R]"))))
  (testing "three integers stay three integers"
    (is (= [1 2 3] (pv "[1 2 3]"))))
  (testing "two integers stay two integers"
    (is (= [1 2] (pv "[1 2]"))))
  (testing "a bare ref parses at value level"
    (is (= (xref/->IndirectRef 5 0) (pv "5 0 R")))))

(deftest dicts
  (is (= {:type :page :count 3} (pv "<< /Type /Page /Count 3 >>")))
  (testing "null-valued entries are dropped (7.3.7)"
    (is (= {:count 3} (pv "<< /Count 3 /Size null >>"))))
  (testing "refs as values"
    (is (= {:root (xref/->IndirectRef 1 0)} (pv "<< /Root 1 0 R >>")))))

(defn- payload-vec [pdf-stream]
  (b/unsigned-vec (:bytes pdf-stream)))

(deftest streams-inline-length
  (let [[{:keys [obj-num gen obj warnings]} _]
        (object/parse-indirect
         (src "5 0 obj\n<< /Length 5 >>\nstream\nHELLO\nendstream\nendobj\n") 0 {})]
    (is (= 5 obj-num))
    (is (= 0 gen))
    (is (stream/pdf-stream? obj))
    (testing "/Length is stripped from the parsed dict"
      (is (= {} (:dict obj))))
    (is (= (b/ascii->bytes "HELLO") (payload-vec obj)))
    (testing "a verified length records no warning"
      (is (nil? warnings)))))

(deftest streams-indirect-length
  (let [resolver (fn [ref] (when (= 7 (:obj-num ref)) 5))
        [{:keys [obj]} _]
        (object/parse-indirect
         (src "5 0 obj\n<< /Length 7 0 R >>\nstream\nHELLO\nendstream\nendobj\n")
         0 {:resolve-ref resolver})]
    (is (= (b/ascii->bytes "HELLO") (payload-vec obj)))))

(deftest streams-length-mismatch-fallback
  (let [[{:keys [obj warnings]} _]
        (object/parse-indirect
         (src "5 0 obj\n<< /Length 2 >>\nstream\nHELLO\nendstream\nendobj\n") 0 {})]
    (testing "the fallback scan recovers the full payload"
      (is (= (b/ascii->bytes "HELLO") (payload-vec obj))))
    (testing "and records a length-mismatch warning"
      (is (= [{:type :length-mismatch :obj-num 5}] warnings)))))

(deftest streams-payload-with-embedded-endstream-word
  (testing "a correct /Length wins even when the payload contains 'endstream'"
    (let [payload "x endstream y"
          doc (str "5 0 obj\n<< /Length " (count payload) " >>\nstream\n"
                   payload "\nendstream\nendobj\n")
          [{:keys [obj]} _] (object/parse-indirect (src doc) 0 {})]
      (is (= (b/ascii->bytes payload) (payload-vec obj))))))

(deftest bad-header-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (object/parse-indirect (src "5 0 xyz << >> endobj") 0 {}))))

(deftest missing-endobj-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (object/parse-indirect (src "5 0 obj << /A 1 >> ") 0 {}))))
