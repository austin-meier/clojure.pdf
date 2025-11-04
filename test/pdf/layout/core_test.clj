(ns pdf.layout.core-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [pdf.context.pdf :refer [new-pdf serialize]]
   [pdf.layout.core :as layout]
   [pdf.test-util :as tu]))

(def font (tu/ubuntu))

(deftest pages-render-map-shape
  (let [tree [:div {:style {:padding 20}}
              [:text {:style {:font font :font-size 14}} "Hello, layout"]]
        [page] (layout/pages tree {:page-size [612 792] :margin 36})]
    (is (= [0 0 612 792] (:media-box page)))
    (is (= {"F0" font} (get-in page [:resources :font])))
    (is (contains? (:font-usage page) "F0"))
    (is (seq (get-in page [:font-usage "F0"])))))

(deftest pages-defaults
  (testing "default page size and margin apply"
    (let [[page] (layout/pages [:div {}])]
      (is (= [0 0 612 792] (:media-box page))))))

(deftest end-to-end-serializes-to-pdf
  (testing "a layout tree lays out, embeds its font, and serializes to valid PDF"
    (let [tree [:div {:style {:padding 40 :gap 8}}
                [:text {:style {:font font :font-size 24 :color [0.1 0.1 0.6]}}
                 "Title text"]
                [:div {:style {:flex-direction :row :gap 10 :height 30}}
                 [:div {:style {:flex-grow 1 :background-color [0.9 0.9 0.9]}}]
                 [:div {:style {:width 100 :background-color [0.7 0.7 1.0]}}]]
                [:text {:style {:font font :font-size 12}}
                 "A longer paragraph of body text that should wrap across several lines once it exceeds the content width of the page it is laid out on."]]
          bytes (-> (new-pdf)
                    (layout/with-layout tree)
                    serialize)
          header (String. (java.util.Arrays/copyOfRange bytes 0 8) "ISO-8859-1")
          full   (String. bytes "ISO-8859-1")]
      (is (< 1000 (count bytes)) "produced a non-trivial PDF")
      (is (= "%PDF-2.0" header))
      (is (str/includes? full "/Page"))
      (is (str/includes? full "/Font"))
      (is (str/includes? full "/FontFile2") "the subset font program is embedded"))))
