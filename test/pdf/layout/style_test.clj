(ns pdf.layout.style-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.layout.style :as style]))

(deftest color-accepts-0-255-triples
  (testing "an [r g b] triple in 0..255 maps to PDF's 0..1"
    (is (= [1.0 0.0 0.0] (style/resolve-color [255 0 0])))
    (is (= [0.0 0.0 0.0] (style/resolve-color [0 0 0])))
    (is (= [1.0 1.0 1.0] (style/resolve-color [255 255 255])))))

(deftest color-accepts-hex-strings
  (testing "#rrggbb"
    (is (= [1.0 0.0 0.0] (style/resolve-color "#ff0000")))
    (is (= [0.0 0.0 0.0] (style/resolve-color "#000000"))))
  (testing "shorthand #rgb expands each digit"
    (is (= (style/resolve-color "#ff0000") (style/resolve-color "#f00"))))
  (testing "a leading # is optional"
    (is (= (style/resolve-color "#00ff00") (style/resolve-color "00ff00")))))

(deftest color-passes-nil-through
  (is (nil? (style/resolve-color nil))))

(deftest resolve-value-dispatches-through-the-registry
  (testing "each property coerces via the resolver it names under :resolver"
    (is (= [1.0 0.0 0.0] (style/resolve-value :background-color [255 0 0])))
    (is (= [0.0 0.0 1.0] (style/resolve-value :border-color "#0000ff")))
    (is (= {:% 50.0} (style/resolve-value :width "50%")))
    (is (= [4 4 4 4] (style/resolve-value :padding 4))))
  (testing "a property with no :resolver passes its value through verbatim"
    (is (= :center (style/resolve-value :justify-content :center)))
    (is (= 3 (style/resolve-value :flex-grow 3)))))

(deftest custom-resolver-extends-without-touching-resolve-value
  (testing "a resolver registered by keyword drives resolve-value"
    (with-redefs [style/resolvers  (assoc style/resolvers :shout (fn [v] (str v "!")))
                  style/properties (assoc style/properties :banner {:resolver :shout})]
      (is (= "hi!" (style/resolve-value :banner "hi"))))))
