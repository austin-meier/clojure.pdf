(ns pdf.file.chunk-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.file.chunk :refer [append-chunks chunk-length chunks->byte-array]]))

(deftest chunk-lengths
  (testing "String chunks count one byte per char"
    (is (= 7 (chunk-length "1 0 obj"))))

  (testing "Byte array chunks count their raw length"
    (is (= 3 (chunk-length (byte-array [0 -1 127]))))))

(deftest binary-passthrough
  (testing "Byte array chunks survive concatenation untouched"
    (let [payload (byte-array (range -128 128))
          out (chunks->byte-array ["a" payload "b"])]
      (is (= (vec payload)
             (vec (java.util.Arrays/copyOfRange out 1 257))))))

  (testing "High-bit chars in syntax strings encode as single Latin-1 bytes"
    (is (= [37 -128 37]
           (vec (chunks->byte-array [(str "%" (char 128) "%")]))))))

(deftest append-tracks-byte-length
  (let [sctx (append-chunks {:chunks [] :length 0} ["abc" (byte-array 4)])]
    (testing "Length advances by the total byte length of appended chunks"
      (is (= 7 (:length sctx))))
    (testing "Chunks accumulate in order"
      (is (= 2 (count (:chunks sctx)))))))
