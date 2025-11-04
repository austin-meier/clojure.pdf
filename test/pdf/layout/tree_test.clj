(ns pdf.layout.tree-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.layout.style :as style]
   [pdf.layout.tree :as tree]
   [pdf.utils.dimension :as dim]))

(deftest style-defaults-and-metadata
  (testing "defaults expose the initial value of every property"
    (is (= :column (:flex-direction style/defaults)))
    (is (= :auto (:width style/defaults)))
    (is (= 12 (:font-size style/defaults)))
    (is (= [0.0 0.0 0.0] (:color style/defaults))))  ; [0 0 0] 0..255 -> 0..1 black
  (testing "the inherited set is exactly the text properties"
    (is (= #{:font :font-size :line-height :color} style/inherited))))

(deftest resolve-length-cases
  (is (= 50 (style/resolve-length 50)))
  (is (= :auto (style/resolve-length :auto)))
  (is (= :none (style/resolve-length :none)))
  (is (nil? (style/resolve-length nil)))
  (is (= {:% 30.0} (style/resolve-length "30%")))
  (is (= 72.0 (style/resolve-length (dim/inches->dim 1))))
  (is (thrown? clojure.lang.ExceptionInfo (style/resolve-length "banana"))))

(deftest resolve-box-shorthand
  (is (= [8 8 8 8] (style/resolve-box 8)))
  (is (= [1 2 1 2] (style/resolve-box [1 2])))
  (is (= [1 2 3 2] (style/resolve-box [1 2 3])))
  (is (= [1 2 3 4] (style/resolve-box [1 2 3 4]))))

(deftest normalize-shape
  (testing "a view carries a merged style and normalized children"
    (let [n (tree/normalize [:div {:style {:flex-direction :row :gap 8}}
                             [:div]])]
      (is (= :div (:node n)))
      (is (= :row (get-in n [:style :flex-direction])))
      (is (= 8 (get-in n [:style :gap])))
      (is (= 1 (count (:children n))))
      (is (= :column (get-in n [:children 0 :style :flex-direction])))))
  (testing "a text node concatenates its strings"
    (is (= "ab c" (:text (tree/normalize [:text "ab" " c"])))))
  (testing "the attrs map is optional"
    (is (= :div (:node (tree/normalize [:div [:text "x"]]))))
    (is (= "x" (get-in (tree/normalize [:div [:text "x"]]) [:children 0 :text]))))
  (testing "unknown nodes throw"
    (is (thrown? clojure.lang.ExceptionInfo (tree/normalize [:image {}])))))

(deftest normalize-unit-resolution
  (let [n (tree/normalize [:div {:style {:width "50%" :height (dim/points->dim 100)
                                          :padding [4 8]}}])]
    (is (= {:% 50.0} (get-in n [:style :width])))
    (is (= 100.0 (get-in n [:style :height])))
    (is (= [4 8 4 8] (get-in n [:style :padding])))))

(deftest normalize-inheritance
  (testing "text props inherit; box props reset to default"
    (let [n (tree/normalize [:div {:style {:font-size 20 :gap 5}}
                             [:text "child"]])
          child (get-in n [:children 0])]
      (is (= 20 (get-in child [:style :font-size])) "font-size inherits")
      (is (= 0 (get-in child [:style :gap])) "gap does not inherit")))
  (testing "a child can override an inherited property"
    (let [n (tree/normalize [:div {:style {:font-size 20}}
                             [:text {:style {:font-size 8}} "small"]])]
      (is (= 8 (get-in n [:children 0 :style :font-size]))))))
