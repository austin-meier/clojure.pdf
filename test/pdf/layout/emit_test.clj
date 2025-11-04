(ns pdf.layout.emit-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.layout.emit :as emit]
   [pdf.test-util :as tu :refer [solve]]))

(def font (tu/ubuntu))

(deftest background-rect-y-flip
  (testing "a top-left box paints at pdf-y = page-h - y - height"
    (let [root (solve [:div {:style {:width 100 :height 200}}
                       [:div {:style {:width 100 :height 20
                                       :background-color [255 0 0]}}]]
                      100 200)
          {:keys [ops]} (emit/emit root 200 [0 0] (constantly "F0") (constantly "X0"))]
      (is (some #{[:rectangle 0.0 180.0 100.0 20.0]} ops)
          "child at y=0 h=20 on a 200pt page flips to pdf-y 180")
      (is (= [:save-state] (first ops)))
      (is (some #{[:set-rgb-fill 1.0 0.0 0.0]} ops)
          "[255 0 0] resolves to PDF's 0..1 red"))))

(deftest origin-offsets-coordinates
  (testing "the margin origin shifts x and the y-flip"
    (let [root (solve [:div {:style {:width 100 :height 20
                                      :background-color [0 0 0]}}]
                      100 20)
          {:keys [ops]} (emit/emit root 200 [36 36] (constantly "F0") (constantly "X0"))]
      ;; x = 36 + 0; pdf-y = 200 - (36 + 0) - 20 = 144
      (is (some #{[:rectangle 36.0 144.0 100.0 20.0]} ops)))))

(deftest text-ops-and-usage
  (let [root (solve [:div {:style {:width 500 :height 100}}
                     [:text {:style {:font font :font-size 12}} "Hi"]]
                    500 100)
        {:keys [ops usage]} (emit/emit root 100 [0 0] (constantly "F0") (constantly "X0"))]
    (testing "the op shape is BT / set-font / Tm+Tj per line / ET"
      (is (some #{[:begin-text]} ops))
      (is (some #{[:end-text]} ops))
      (is (some #{[:set-font 'F0 12]} ops))
      (is (some #(and (vector? %) (= :show-text (first %))) ops)))
    (testing "glyph usage is recorded under the font key for embed-fonts"
      (is (contains? usage "F0"))
      (is (seq (get usage "F0"))))))

(deftest fonts-used-distinct
  (let [root (solve [:div {}
                     [:text {:style {:font font :font-size 12}} "a"]
                     [:text {:style {:font font :font-size 20}} "b"]]
                    500 nil)]
    (is (= [font] (emit/fonts-used root)) "one distinct context reused")))
