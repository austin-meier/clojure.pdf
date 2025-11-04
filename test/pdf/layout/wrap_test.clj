(ns pdf.layout.wrap-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.layout.flex :as flex]
   [pdf.layout.tree :as tree]))

(defn- solve [hiccup w h]
  (flex/solve (tree/normalize hiccup) {:width w :height h}))

(defn- box [node & path]
  (:layout (get-in node (interleave (repeat :children) path))))

(defn- xywh [l] [(:x l) (:y l) (:width l) (:height l)])

(deftest nowrap-keeps-one-line-overflowing
  (testing "without wrap (and no shrink), three 40-wide items stay on one row and overflow"
    (let [root (solve [:div {:style {:flex-direction :row :width 100 :height 50}}
                       [:div {:style {:width 40 :height 20 :flex-shrink 0}}]
                       [:div {:style {:width 40 :height 20 :flex-shrink 0}}]
                       [:div {:style {:width 40 :height 20 :flex-shrink 0}}]]
                      100 50)]
      (is (= [0 40 80] (map (comp :x #(box root %)) [0 1 2]))))))

(deftest wrap-flows-items-onto-multiple-lines
  (testing "with wrap, items that overflow the main size move to the next line"
    (let [root (solve [:div {:style {:flex-direction :row :flex-wrap :wrap
                                      :align-content :flex-start :width 100 :height 100}}
                       [:div {:style {:width 40 :height 20}}]
                       [:div {:style {:width 40 :height 20}}]
                       [:div {:style {:width 40 :height 20}}]]
                      100 100)]
      ;; 40+40=80 fits, third wraps: line1 = items 0,1 (y=0); line2 = item 2 (y=20)
      (is (= [0 0 40 20] (xywh (box root 0))))
      (is (= [40 0 40 20] (xywh (box root 1))))
      (is (= [0 20 40 20] (xywh (box root 2))) "third item on the second line"))))

(deftest column-gap-and-row-gap-split
  (testing "row flow: column-gap spaces items, row-gap spaces wrapped lines"
    (let [root (solve [:div {:style {:flex-direction :row :flex-wrap :wrap
                                      :align-content :flex-start
                                      :column-gap 10 :row-gap 30
                                      :width 100 :height 100}}
                       [:div {:style {:width 40 :height 20}}]
                       [:div {:style {:width 40 :height 20}}]
                       [:div {:style {:width 40 :height 20}}]]
                      100 100)]
      ;; line 1: item0 x=0, item1 x=40+10=50 (column-gap). 50+40=90 fits; third wraps.
      (is (= 0 (:x (box root 0))))
      (is (= 50 (:x (box root 1))) "column-gap between items")
      (is (= 50 (:y (box root 2))) "row-gap (30) after line1 height (20)"))))

(deftest gap-shorthand-feeds-both
  (testing ":gap sets both row and column gaps when they are :normal"
    (let [root (solve [:div {:style {:flex-direction :row :flex-wrap :wrap :gap 10
                                      :align-content :flex-start :width 100 :height 100}}
                       [:div {:style {:width 40 :height 20}}]
                       [:div {:style {:width 40 :height 20}}]
                       [:div {:style {:width 40 :height 20}}]]
                      100 100)]
      (is (= 50 (:x (box root 1))) "gap as column-gap")
      (is (= 30 (:y (box root 2))) "gap as row-gap (20 line height + 10 gap)"))))

(deftest align-content-distributes-lines
  (testing "align-content flex-end pushes wrapped lines to the cross end"
    (let [tree [:div {:style {:flex-direction :row :flex-wrap :wrap
                               :align-content :__ :width 100 :height 100}}
                [:div {:style {:width 60 :height 20}}]
                [:div {:style {:width 60 :height 20}}]]  ; each wraps: 2 lines of 20
        at (fn [ac] (let [r (solve (assoc-in tree [1 :style :align-content] ac) 100 100)]
                      [(:y (box r 0)) (:y (box r 1))]))]
      ;; two lines total 40 tall in a 100 cross; 60 leftover
      (is (= [0 20] (at :flex-start)))
      (is (= [60 80] (at :flex-end)))
      (is (= [30 50] (at :center))))))

(deftest column-child-fills-width-so-nested-wrap-wraps
  (testing "an auto-width child in a definite-width column fills that width, so a
            nested wrap row wraps and its height reflects the wrapped lines (the
            demo regression: without this the row stays content-wide and 1 line)"
    (let [root (flex/solve
                (tree/normalize
                 [:div {:style {:width 100}}
                  [:div {:style {:flex-direction :row :flex-wrap :wrap
                                 :align-content :flex-start}}
                   [:div {:style {:width 60 :height 20}}]
                   [:div {:style {:width 60 :height 20}}]]
                  [:div {:style {:width 10 :height 5}}]])       ; sibling below the wrap row
                {:width 100 :height nil})]
      (is (= 100 (:width (box root 0))) "inner row stretched to the column width")
      (is (= 40 (:height (box root 0))) "two 60-wide items wrapped to two 20pt lines")
      ;; the sibling sits below the *wrapped* row, not overlapping it
      (is (= 40 (:y (box root 1))) "sibling positioned after the full wrapped height")
      (is (= 45 (:height (box root))) "column height = 40 (row) + 5 (sibling)"))))

(deftest wrap-content-sized-cross-sums-lines
  (testing "a wrapping row with auto height sizes to the sum of its line heights"
    (let [root (solve [:div {:style {:flex-direction :row :flex-wrap :wrap
                                      :row-gap 5 :width 100}}
                       [:div {:style {:width 60 :height 20}}]
                       [:div {:style {:width 60 :height 30}}]]
                      100 nil)]
      (is (= 55 (:height (box root))) "20 + gap 5 + 30"))))
