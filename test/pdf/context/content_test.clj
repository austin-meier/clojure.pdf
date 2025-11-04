(ns pdf.context.content-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.content :refer [compile-content op->line ops->stream]]
   [pdf.context.stream :refer [pdf-stream?]]))

(deftest op->line-operands-then-mnemonic
  (testing "Operators with operands render operands first, mnemonic last"
    (is (= "/F0 12 Tf" (op->line [:set-font 'F0 12])))
    (is (= "100 500 Td" (op->line [:move-text-position 100 500])))
    (is (= "(hi) Tj" (op->line [:show-text "hi"]))))

  (testing "Operandless operators render as the bare mnemonic"
    (is (= "BT" (op->line [:begin-text])))
    (is (= "ET" (op->line [:end-text]))))

  (testing "Array operands render as PDF arrays"
    (is (= "[(A) -120 (B)] TJ" (op->line [:show-text-adjusted ["A" -120 "B"]]))))

  (testing "String operands are escaped"
    (is (= "(a\\(b\\)) Tj" (op->line [:show-text "a(b)"]))))

  (testing "Unknown operators throw"
    (is (thrown? clojure.lang.ExceptionInfo (op->line [:not-an-op 1 2])))))

(deftest compile-content-joins-lines
  (is (= "BT\n/F0 12 Tf\n(hi) Tj\nET"
         (compile-content [[:begin-text]
                           [:set-font 'F0 12]
                           [:show-text "hi"]
                           [:end-text]]))))

(deftest ops->stream-produces-a-stream
  (let [stream (ops->stream [[:begin-text] [:end-text]])]
    (is (pdf-stream? stream))
    (is (= "BT\nET" (String. ^bytes (:bytes stream) "UTF-8")))))
