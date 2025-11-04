(ns pdf.context.svg-path-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.svg.path :as path]))

(defn- approx= [a b] (< (abs (- a b)) 1e-9))

(deftest absolute-and-relative-basics
  (is (= [[:move-to 10.0 20.0] [:line-to 30.0 40.0]]
         (path/parse "M10 20 L30 40")))
  (testing "relative commands offset from the current point"
    (is (= [[:move-to 10.0 20.0] [:line-to 15.0 25.0]]
           (path/parse "m10 20 l5 5")))))

(deftest implicit-command-repetition
  (testing "extra pairs after a moveto continue as lines"
    (is (= [[:move-to 0.0 0.0] [:line-to 10.0 10.0] [:line-to 20.0 20.0]]
           (path/parse "M0 0 10 10 20 20")))
    (is (= [[:move-to 0.0 0.0] [:line-to 10.0 10.0]]
           (path/parse "m0 0 10 10")))))

(deftest horizontal-and-vertical-lines
  (is (= [[:move-to 10.0 10.0] [:line-to 20.0 10.0] [:line-to 20.0 30.0]
          [:line-to 25.0 30.0] [:line-to 25.0 35.0]]
         (path/parse "M10 10 H20 V30 h5 v5"))))

(deftest smooth-cubic-reflects-the-previous-control
  (is (= [[:move-to 0.0 0.0]
          [:curve-to 0.0 10.0 10.0 10.0 10.0 0.0]
          [:curve-to 10.0 -10.0 20.0 -10.0 20.0 0.0]]
         (path/parse "M0 0 C0 10 10 10 10 0 S20 -10 20 0"))))

(deftest quadratics-lift-to-cubics-exactly
  (is (= [[:move-to 0.0 0.0] [:curve-to 2.0 4.0 4.0 4.0 6.0 0.0]]
         (path/parse "M0 0 Q3 6 6 0")))
  (testing "T reflects the previous quadratic control before lifting"
    (is (= [[:move-to 0.0 0.0]
            [:curve-to 2.0 4.0 4.0 4.0 6.0 0.0]
            [:curve-to 8.0 -4.0 10.0 -4.0 12.0 0.0]]
           (path/parse "M0 0 Q3 6 6 0 T12 0")))))

(deftest tokenizer-handles-packed-numbers
  (testing "a decimal terminates the previous number; a sign starts a new one"
    (is (= [[:move-to 1.5 0.5] [:line-to -2.0 -3.0]]
           (path/parse "M1.5.5-2-3")))))

(deftest closepath-returns-to-the-subpath-start
  (is (= [[:move-to 10.0 10.0] [:line-to 20.0 10.0] [:close-path]
          [:line-to 15.0 15.0]]
         (path/parse "M10 10 L20 10 Z l5 5"))))

(deftest arcs-convert-to-cubics
  (let [ops (path/parse "M0 0 A10 10 0 0 1 20 0")]
    (testing "a semicircle splits into two <=90-degree cubic segments"
      (is (= 3 (count ops)))
      (is (every? #(= :curve-to (first %)) (rest ops))))
    (testing "the final endpoint is pinned exactly to the target"
      (is (= [20.0 0.0] (subvec (peek ops) 5))))
    (testing "intermediate segment endpoints lie on the circle"
      (let [[_ _ _ _ _ x y] (second ops)]
        (is (approx= 100.0 (+ (* (- x 10) (- x 10)) (* y y))))))))

(deftest arc-flags-packed-against-the-next-number
  (testing "minifier output like `1150 0` reads as flags 1, 1 then x 50"
    (let [ops (path/parse "M0 0 a25 25 0 1150 0")]
      (is (= [50.0 0.0] (subvec (peek ops) 5))))))

(deftest degenerate-arcs
  (testing "a zero radius degrades to a line per the spec"
    (is (= [[:move-to 0.0 0.0] [:line-to 20.0 0.0]]
           (path/parse "M0 0 A0 10 0 0 1 20 0"))))
  (testing "a zero-length arc draws nothing"
    (is (= [[:move-to 5.0 5.0]]
           (path/parse "M5 5 A10 10 0 0 1 5 5")))))

(deftest malformed-path-data-throws
  (is (thrown? clojure.lang.ExceptionInfo (path/parse "L10 10")))
  (is (thrown? clojure.lang.ExceptionInfo (path/parse "M0 0 A10 10 0 5 1 20 0")))
  (is (thrown? clojure.lang.ExceptionInfo (path/parse "M0 0 L10"))))
