(ns pdf.layout.paginate-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.layout.paginate :as paginate]
   [pdf.layout.style :as style]
   [pdf.layout.text :as text]
   [pdf.test-util :as tu]))

(def font (tu/ubuntu))

(defn- pages [hiccup w content-h]
  (paginate/paginate (tu/solve-page hiccup w) content-h))

(defn- child-boxes [page]
  (map (fn [c] [(get-in c [:layout :y]) (get-in c [:layout :height])]) (:children page)))

(deftest single-page-when-it-fits
  (let [ps (pages [:div {}
                   [:div {:style {:width 50 :height 40 :background-color [0 0 0]}}]
                   [:div {:style {:width 50 :height 40 :background-color [0 0 0]}}]]
                  100 200)]
    (is (= 1 (count ps)))
    (is (= [[0 40] [40 40]] (child-boxes (first ps))))))

(deftest fixed-boxes-overflow-to-second-page
  (testing "three 40pt boxes on a 100pt page: two fit, the third pushes"
    (let [ps (pages [:div {}
                     [:div {:style {:width 50 :height 40 :background-color [0 0 0]}}]
                     [:div {:style {:width 50 :height 40 :background-color [0 0 0]}}]
                     [:div {:style {:width 50 :height 40 :background-color [0 0 0]}}]]
                    100 100)]
      (is (= 2 (count ps)))
      (is (= [[0 40] [40 40]] (child-boxes (first ps))))
      (is (= [[0 40]] (child-boxes (second ps))) "third box at the top of page 2"))))

(deftest break-inside-avoid-pushes-whole
  (testing "a box that would straddle the boundary is pushed whole"
    (let [ps (pages [:div {}
                     [:div {:style {:width 50 :height 70 :background-color [0 0 0]}}]
                     [:div {:style {:width 50 :height 50 :background-color [0 0 0]}}]]
                    100 100)]
      ;; first box 0..70; second would be 70..120, doesn't fit -> page 2
      (is (= 2 (count ps)))
      (is (= [[0 70]] (child-boxes (first ps))))
      (is (= [[0 50]] (child-boxes (second ps)))))))

(deftest oversized-node-overflows-its-own-page
  (testing "a node taller than a page sits at the top and overflows"
    (let [ps (pages [:div {}
                     [:div {:style {:width 50 :height 250 :background-color [0 0 0]}}]]
                    100 100)]
      (is (= 1 (count ps)))
      (is (= [[0 250]] (child-boxes (first ps))) "placed at top, overflows"))))

(deftest text-splits-between-lines
  (testing "a tall paragraph splits between lines across pages"
    (let [para (apply str (repeat 40 "word "))
          root-tree [:div {} [:text {:style {:font font :font-size 12}} para]]
          root (tu/solve-page root-tree 120)
          txt  (get-in root [:children 0])
          lh   (text/line-height font :normal 12)
          nlines (count (:lines (:measured txt)))
          ;; a page that holds ~3 lines
          content-h (* 3 lh)
          ps (paginate/paginate root content-h)
          lines-on (fn [p] (reduce + (map #(count (:lines (:measured %))) (:children p))))]
      (is (< 1 (count ps)) "paragraph spans multiple pages")
      (is (= nlines (reduce + (map lines-on ps))) "no lines lost across the split")
      (is (every? #(<= (lines-on %) 3) ps) "at most three lines per page")
      (is (= 0.0 (double (get-in (first (:children (first ps))) [:layout :y])))
          "first fragment starts at the page top"))))

(deftest split-container-paints-background-per-page
  (testing "a column view with a background that spans two pages emits a bg rect on each"
    (let [gray (style/resolve-color [128 128 128])
          ps (pages [:div {:style {:background-color [128 128 128]}}
                     [:div {:style {:width 50 :height 60 :background-color [0 0 0]}}]
                     [:div {:style {:width 50 :height 60 :background-color [0 0 0]}}]]
                    100 100)]
      (is (= 2 (count ps)))
      ;; each page has the outer container's bg fragment plus one inner box
      (is (every? (fn [p] (some #(= gray (:background-color (:style %)))
                                (:children p)))
                  ps)
          "outer background painted on both pages"))))
