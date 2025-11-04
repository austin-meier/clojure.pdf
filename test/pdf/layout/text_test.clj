(ns pdf.layout.text-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.text.sfnt :as sfnt]
   [pdf.layout.flex :as flex]
   [pdf.layout.text :as text]
   [pdf.layout.tree :as tree]
   [pdf.test-util :as tu]))

(def font (tu/ubuntu))

(defn- expected-width
  "Width of `s` computed straight from the font's advance widths, the ground
   truth run-width must match."
  [size s]
  (let [{:keys [advance-widths units-per-em]} (:sfnt font)]
    (* (/ size units-per-em)
       (reduce + (map #(get advance-widths (or (sfnt/char->gid (:cmap font) %) 0) 0)
                      (map int s))))))

(deftest run-width-matches-advance-widths
  (doseq [s ["A" "Hello" "The quick brown fox"]]
    (is (= (expected-width 12 s) (text/run-width font 12 s))
        (str "run-width of " (pr-str s)))))

(deftest run-width-scales-with-size
  (is (= (* 2 (text/run-width font 12 "Hello"))
         (text/run-width font 24 "Hello"))))

(deftest line-height-normal-and-multiplier
  (let [{:keys [ascent descent line-gap units-per-em]} (:sfnt font)]
    (is (= (* (/ (+ (- ascent descent) line-gap) units-per-em) 12.0)
           (text/line-height font :normal 12.0)))
    (is (= 18.0 (text/line-height font 1.5 12.0)))))

(deftest measure-single-line-no-wrap
  (let [node (tree/normalize [:text {:style {:font font :font-size 12}} "Hello"])
        m (text/measure node {:width nil})]
    (is (= 1 (count (:lines m))))
    (is (= "Hello" (:text (first (:lines m)))))
    (is (= (text/run-width font 12 "Hello") (:width m)))
    (is (= (:max-content m) (:width m)))))

(deftest measure-wraps-on-word-boundaries
  (let [node (tree/normalize [:text {:style {:font font :font-size 12}}
                              "aaa bbb ccc"])
        one-word (text/run-width font 12 "aaa")
        ;; a width that fits one word but not two
        m (text/measure node {:width (+ one-word 1)})]
    (is (= 3 (count (:lines m))) "each word wraps to its own line")
    (is (= ["aaa" "bbb" "ccc"] (map :text (:lines m))))
    (is (= (* 3 (text/line-height font :normal 12)) (:height m)))))

(deftest measure-min-max-content
  (let [node (tree/normalize [:text {:style {:font font :font-size 12}}
                              "short longestword"])
        m (text/measure node {:width nil})]
    (is (= (text/run-width font 12 "longestword") (:min-content m)))
    (is (= (text/run-width font 12 "short longestword") (:max-content m)))))

(deftest measure-no-font-is-zero
  (let [node (tree/normalize [:text "no font here"])]
    (is (= {:lines [] :width 0 :height 0 :ascent 0 :min-content 0 :max-content 0}
           (text/measure node {:width 100})))))

(deftest solver-uses-injected-measure
  (testing "a text leaf sizes to its measured box inside the flex solve"
    (let [tree (tree/normalize [:div {:style {:width 500 :height 100}}
                                [:text {:style {:font font :font-size 12}} "Hello"]])
          root (flex/solve tree {:width 500 :height 100} {:measure text/measure})
          txt  (get-in root [:children 0 :layout])]
      ;; column parent stretches the text width to the container; height is one line
      (is (= 500 (:width txt)))
      (is (= (text/line-height font :normal 12) (:height txt))))))
