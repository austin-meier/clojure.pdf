(ns pdf.layout.border-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.layout.emit :as emit]
   [pdf.layout.flex :as flex]
   [pdf.layout.tree :as tree]))

(defn- solve [hiccup w h]
  (flex/solve (tree/normalize hiccup) {:width w :height h}))

(defn- box [node & path]
  (:layout (get-in node (interleave (repeat :children) path))))

(deftest border-participates-in-box-model
  (testing "border and padding both inset the content box"
    (let [root (solve [:div {:style {:width 100 :height 100
                                      :border-width 5 :padding 10}}
                       [:div {:style {:width 20 :height 20}}]]
                      100 100)]
      ;; content origin = border(5) + padding(10) = 15
      (is (= [15 15 20 20]
             ((juxt :x :y :width :height) (box root 0)))))))

(deftest border-grows-content-sized-box
  (testing "an auto-size box includes its border in the border box"
    (let [root (solve [:div {:style {:border-width [2 4 2 4]}}
                       [:div {:style {:width 30 :height 10}}]]
                      500 nil)]
      ;; width = content 30 + left/right border 4+4 = 38; height = 10 + 2+2 = 14
      (is (= 38 (:width (box root))))
      (is (= 14 (:height (box root)))))))

(deftest border-paints-four-side-rects
  (testing "emit paints a filled rect per non-zero border side, y-flipped"
    (let [root (solve [:div {:style {:width 100 :height 50
                                      :border-width 4 :border-color [1 0 0]}}]
                      100 50)
          {:keys [ops]} (emit/emit root 50 [0 0] (constantly "F0") (constantly "X0"))
          rects (filter #(= :rectangle (first %)) ops)]
      (is (= 4 (count rects)) "top, bottom, left, right")
      ;; top border: x=0 y=0 w=100 h=4 -> pdf-y = 50 - 0 - 4 = 46
      (is (some #{[:rectangle 0.0 46.0 100.0 4.0]} rects) "top")
      ;; bottom border: top-left y = 50-4 = 46 -> pdf-y = 50 - 46 - 4 = 0
      (is (some #{[:rectangle 0.0 0.0 100.0 4.0]} rects) "bottom")
      ;; left border: x=0 y=0 w=4 h=50 -> pdf-y = 50 - 0 - 50 = 0
      (is (some #{[:rectangle 0.0 0.0 4.0 50.0]} rects) "left")
      ;; right border: x=96 -> pdf same y=0
      (is (some #{[:rectangle 96.0 0.0 4.0 50.0]} rects) "right"))))

(deftest zero-width-sides-not-painted
  (testing "only non-zero border sides emit a rect"
    (let [root (solve [:div {:style {:width 100 :height 50
                                      :border-width [4 0 0 0] :border-color [0 0 1]}}]
                      100 50)
          {:keys [ops]} (emit/emit root 50 [0 0] (constantly "F0") (constantly "X0"))]
      (is (= 1 (count (filter #(= :rectangle (first %)) ops))) "only the top side"))))

(deftest no-border-color-paints-nothing
  (let [root (solve [:div {:style {:width 100 :height 50 :border-width 4}}] 100 50)
        {:keys [ops]} (emit/emit root 50 [0 0] (constantly "F0") (constantly "X0"))]
    (is (empty? (filter #(= :rectangle (first %)) ops)))))
