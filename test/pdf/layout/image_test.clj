(ns pdf.layout.image-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.image :as img]
   [pdf.layout.core :as core]
   [pdf.layout.emit :as emit]
   [pdf.layout.flex :as flex]
   [pdf.layout.tree :as tree]))

;; A minimal image context stands in for a decoded file: layout only reads
;; :type, :width and :height off :src.
(def ^:private img16x12 {:type :image-context :format :jpeg :width 16 :height 12})

(defn- solve [tree]
  (flex/solve (tree/normalize tree) {:width 500 :height nil}))

(deftest normalize-accepts-image
  (let [n (tree/normalize [:image {:src img16x12}])]
    (is (= :image (:node n)))
    (is (= img16x12 (:src n)))))

(deftest normalize-rejects-non-image-src
  (is (thrown? clojure.lang.ExceptionInfo (tree/normalize [:image {:src {:not :an-image}}])))
  (is (thrown? clojure.lang.ExceptionInfo (tree/normalize [:image {}]))))

(deftest intrinsic-size-is-pixels-times-0_75
  (testing "96dpi against 72pt: a 16x12px image is 12x9pt"
    (let [{:keys [width height]} (:layout (solve [:image {:src img16x12}]))]
      (is (= 12.0 (double width)))
      (is (= 9.0 (double height))))))

(deftest one-dimension-keeps-aspect-ratio
  (testing "width only -> height follows 4:3"
    (let [{:keys [width height]} (:layout (solve [:image {:src img16x12 :style {:width 100}}]))]
      (is (= 100.0 (double width)))
      (is (= 75.0 (double height)))))
  (testing "height only -> width follows 4:3"
    (let [{:keys [width height]} (:layout (solve [:image {:src img16x12 :style {:height 60}}]))]
      (is (= 80.0 (double width)))
      (is (= 60.0 (double height))))))

(deftest both-dimensions-stretch
  (let [{:keys [width height]} (:layout (solve [:image {:src img16x12 :style {:width 150 :height 60}}]))]
    (is (= 150.0 (double width)))
    (is (= 60.0 (double height)))))

(deftest replaced-element-ignores-align-stretch
  (testing "An auto-size image in a definite-width stretch container keeps its
            intrinsic size instead of ballooning to the container width"
    (let [r (flex/solve (tree/normalize [:div {:style {:width 400}} [:image {:src img16x12}]])
                        {:width 400 :height nil})
          {:keys [width height]} (get-in r [:children 0 :layout])]
      (is (= 12.0 (double width)))
      (is (= 9.0 (double height))))))

(deftest emit-draws-the-xobject
  (let [root (solve [:image {:src img16x12 :style {:width 100}}])
        {:keys [ops]} (emit/emit root 200 [0 0] (constantly "F0") (constantly "X0"))]
    (is (some #(= :draw-xobject (first %)) ops))
    (is (some #(= :concat-matrix (first %)) ops))))

(deftest images-used-collects-distinct
  (let [other (assoc img16x12 :width 5)
        root  (solve [:div {} [:image {:src img16x12}] [:image {:src img16x12}] [:image {:src other}]])]
    (is (= [img16x12 other] (emit/images-used root)))))

(deftest pages-assigns-xobject-keys-and-dedupes
  (testing "One image reused across nodes gets a single X key in resources"
    (let [renders (core/pages [:div {:style {:flex-direction :column :gap 5}}
                               [:image {:src img16x12 :style {:width 40}}]
                               [:image {:src img16x12 :style {:width 60}}]])
          xobject (get-in (first renders) [:resources :xobject])]
      (is (= 1 (count xobject)))
      (is (= {"X0" img16x12} xobject)))))

(deftest image-pushes-whole-across-page-break
  (testing "An image that doesn't fit the remaining space moves to the next page"
    (let [tall (assoc img16x12 :width 800 :height 800)  ; ~600pt tall
          renders (core/pages [:div {:style {:flex-direction :column :gap 5}}
                               [:image {:src img16x12 :style {:width 40 :height 600}}]
                               [:image {:src tall}]]
                              {:page-size [612 792] :margin 36})]
      ;; first image (~600pt) + tall image (~600pt) can't share a 720pt content
      ;; column, so pagination spills onto a second page
      (is (<= 2 (count renders))))))
