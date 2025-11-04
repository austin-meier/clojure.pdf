(ns pdf.parse.fixtures-test
  "Phase 4 over committed fixtures (see test/resources/pdfs/generate.py): an
   xref-stream + object-stream file and a classic file with a binary /ID. The
   asserts are structural — object/page counts, extracted-catalog shape, /ID
   bytes, and that a parsed context re-serializes without error. The
   render/text-equality check lives in the python script beside the fixtures."
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.pdf :as pdfctx]
   [pdf.parse.core :as parse]))

(def ^:private objstm-path "test/resources/pdfs/object-streams.pdf")
(def ^:private binary-id-path "test/resources/pdfs/binary-id.pdf")

(defn- pages [ctx]
  (filter #(= :page (:type (:obj %))) (:objects ctx)))

(defn- catalog [ctx]
  (some #(when (= :catalog (:type (:obj %))) (:obj %)) (:objects ctx)))

(deftest object-streams-fixture
  (let [ctx (parse/parse-file objstm-path)]
    (testing "the modern xref-stream + object-stream file parses cleanly"
      (is (= 2.0 (:version ctx)))
      (is (empty? (:warnings ctx)))
      (is (= 1 (count (pages ctx))))
      (is (some? (catalog ctx)))
      (is (pos? (count (:objects ctx)))))
    (testing "an object pulled from an object stream is a real dict"
      ;; the catalog itself lives in an object stream in this fixture
      (is (contains? (catalog ctx) :pages)))
    (testing "the parsed context re-serializes without error"
      (is (bytes? (pdfctx/serialize ctx))))))

(deftest binary-id-fixture
  (let [ctx (parse/parse-file binary-id-path)]
    (testing "a classic file with a binary /ID parses cleanly"
      (is (empty? (:warnings ctx)))
      (is (= 1 (count (pages ctx)))))
    (testing "the binary /ID survives as two 16-byte trailer strings"
      (let [id (:id (:trailer ctx))]
        (is (vector? id))
        (is (= [16 16] (mapv count id)))))
    (testing "the parsed context re-serializes without error"
      (is (bytes? (pdfctx/serialize ctx))))
    (testing "the /ID round-trips through serialize -> parse (trailer extras)"
      (let [reparsed (parse/parse (pdfctx/serialize ctx))]
        (is (= (:id (:trailer ctx)) (:id (:trailer reparsed))))))))
