(ns pdf.layout.flex-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.layout.flex :as flex]
   [pdf.layout.tree :as tree]))

(defn- solve [hiccup w h]
  (flex/solve (tree/normalize hiccup) {:width w :height h}))

(defn- box [node & path]
  (:layout (get-in node (interleave (repeat :children) path))))

(defn- xywh [l] [(:x l) (:y l) (:width l) (:height l)])

(deftest column-flow-stacks-children
  (let [root (solve [:div {:style {:width 200 :height 200}}
                     [:div {:style {:width 100 :height 20}}]
                     [:div {:style {:width 100 :height 30}}]]
                    200 200)]
    (is (= [0 0 200 200] (xywh (box root))))
    (is (= [0 0 100 20] (xywh (box root 0))))
    (is (= [0 20 100 30] (xywh (box root 1))))))

(deftest row-flow-with-gap
  (let [root (solve [:div {:style {:flex-direction :row :gap 8 :width 200 :height 50}}
                     [:div {:style {:width 40 :height 10}}]
                     [:div {:style {:width 60 :height 10}}]]
                    200 50)]
    (is (= [0 0 40 10] (xywh (box root 0))))
    (is (= [48 0 60 10] (xywh (box root 1))) "second starts after first + gap")))

(deftest padding-offsets-content
  (let [root (solve [:div {:style {:width 100 :height 100 :padding 10}}
                     [:div {:style {:width 20 :height 20}}]]
                    100 100)]
    (is (= [10 10 20 20] (xywh (box root 0))))))

(deftest justify-content-variants
  (let [tree [:div {:style {:flex-direction :row :justify-content :__ :width 100 :height 10}}
              [:div {:style {:width 20 :height 10}}]
              [:div {:style {:width 20 :height 10}}]]
        at (fn [j] (let [t (assoc-in tree [1 :style :justify-content] j)
                         r (solve t 100 10)]
                     [(:x (box r 0)) (:x (box r 1))]))]
    (is (= [0 20] (at :flex-start)))
    (is (= [60 80] (at :flex-end)))
    (is (= [30 50] (at :center)))
    (is (= [0 80] (at :space-between)))
    (is (= [15 65] (at :space-around)))     ; 60 free / (2*2)=15 edge, 30 between
    (is (= [20 60] (at :space-evenly)))))   ; 60 free / 3 = 20

(deftest align-items-variants
  (let [tree [:div {:style {:flex-direction :row :align-items :__ :width 100 :height 40}}
              [:div {:style {:width 10 :height 10}}]]
        at (fn [a] (:y (box (solve (assoc-in tree [1 :style :align-items] a) 100 40) 0)))]
    (is (= 0 (at :flex-start)))
    (is (= 30 (at :flex-end)))
    (is (= 15 (at :center)))))

(deftest align-items-stretch
  (testing "an auto-cross item stretches to the line's cross size"
    (let [root (solve [:div {:style {:flex-direction :row :align-items :stretch
                                      :width 100 :height 40}}
                       [:div {:style {:width 10}}]]
                      100 40)]
      (is (= 40 (:height (box root 0)))))))

(deftest flex-grow-distributes-free-space
  (let [root (solve [:div {:style {:flex-direction :row :width 100 :height 10}}
                     [:div {:style {:flex-grow 1 :height 10}}]
                     [:div {:style {:flex-grow 2 :height 10}}]]
                    100 10)]
    (is (= [0 0 100 10] (xywh (box root))))
    (is (= 100/3 (:width (box root 0))) "1/3 of free space")
    (is (= 200/3 (:width (box root 1))) "2/3 of free space")))

(deftest flex-shrink-distributes-overflow
  (let [root (solve [:div {:style {:flex-direction :row :width 100 :height 10}}
                     [:div {:style {:width 80 :flex-shrink 1 :height 10}}]
                     [:div {:style {:width 80 :flex-shrink 1 :height 10}}]]
                    100 10)]
    ;; 160 wanted, 100 available: shrink 60 weighted equally by base*shrink
    (is (= 50 (:width (box root 0))))
    (is (= 50 (:width (box root 1))))))

(deftest min-max-clamping
  (testing "flex-grow respects max-width"
    (let [root (solve [:div {:style {:flex-direction :row :width 100 :height 10}}
                       [:div {:style {:flex-grow 1 :max-width 30 :height 10}}]
                       [:div {:style {:flex-grow 1 :height 10}}]]
                      100 10)]
      (is (= 30 (:width (box root 0))))
      (is (= 70 (:width (box root 1))) "the other item absorbs the rest"))))

(deftest percentage-width
  (let [root (solve [:div {:style {:flex-direction :row :width 200 :height 10}}
                     [:div {:style {:width "25%" :height 10}}]]
                    200 10)]
    (is (= 50.0 (:width (box root 0))))))

(deftest nesting
  (let [root (solve [:div {:style {:width 100 :height 100 :padding 10}}
                     [:div {:style {:flex-direction :row :gap 5 :width 80 :height 20}}
                      [:div {:style {:width 20 :height 20}}]
                      [:div {:style {:width 20 :height 20}}]]]
                    100 100)]
    (is (= [10 10 80 20] (xywh (box root 0))))
    (is (= [10 10 20 20] (xywh (box root 0 0))))
    (is (= [35 10 20 20] (xywh (box root 0 1))) "nested child abs position folds in padding + gap")))

(deftest content-sized-container
  (testing "an auto-size column sizes to its children"
    (let [root (solve [:div {}
                       [:div {:style {:width 40 :height 10}}]
                       [:div {:style {:width 60 :height 15}}]]
                      500 nil)]
      (is (= 60 (:width (box root))) "widest child")
      (is (= 25 (:height (box root))) "sum of heights"))))

(deftest absolute-positioning
  (let [root (solve [:div {:style {:width 100 :height 100}}
                     [:div {:style {:position :absolute :top 5 :left 8
                                     :width 20 :height 20}}]]
                    100 100)]
    (is (= [8 5 20 20] (xywh (box root 0)))))
  (testing "left+right with auto width sizes to fit"
    (let [root (solve [:div {:style {:width 100 :height 100}}
                       [:div {:style {:position :absolute :left 10 :right 30 :height 20}}]]
                      100 100)]
      (is (= [10 0 60 20] (xywh (box root 0)))))))
