(ns pdf.layout.baseline-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.layout.flex :as flex]
   [pdf.layout.text :as text]
   [pdf.layout.tree :as tree]
   [pdf.test-util :as tu]))

(def font (tu/ubuntu))

(defn- box [node & path]
  (:layout (get-in node (interleave (repeat :children) path))))

(deftest baseline-aligns-box-bottoms
  (testing "non-text items expose their bottom as baseline, so baseline align bottoms them"
    (let [root (flex/solve
                (tree/normalize [:div {:style {:flex-direction :row :align-items :baseline
                                                :width 100 :height 100}}
                                 [:div {:style {:width 10 :height 20}}]
                                 [:div {:style {:width 10 :height 40}}]])
                {:width 100 :height 100})]
      ;; common baseline at max height 40: shorter box drops by 20
      (is (= 20 (:y (box root 0))))
      (is (= 0  (:y (box root 1)))))))

(deftest baseline-aligns-text-of-different-sizes
  (testing "two text runs align on their shared baseline (the larger ascent)"
    (let [root (flex/solve
                (tree/normalize [:div {:style {:flex-direction :row :align-items :baseline
                                                :width 500 :height 100}}
                                 [:text {:style {:font font :font-size 12}} "small"]
                                 [:text {:style {:font font :font-size 24}} "BIG"]])
                {:width 500 :height 100} {:measure text/measure})
          a12 (text/ascent-px font 12)
          a24 (text/ascent-px font 24)]
      ;; baselines coincide at the larger ascent; the 12pt run drops by the difference
      (is (= (- a24 a12) (:y (box root 0))))
      (is (= 0 (:y (box root 1))))
      ;; the two baselines land at the same absolute y
      (is (= (+ (:y (box root 0)) a12)
             (+ (:y (box root 1)) a24))))))
