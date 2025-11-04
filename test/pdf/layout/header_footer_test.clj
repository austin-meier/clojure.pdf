(ns pdf.layout.header-footer-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [pdf.context.pdf :refer [new-pdf serialize]]
   [pdf.layout.core :as layout]
   [pdf.test-util :as tu]))

(def font (tu/ubuntu))

(defn- rects [contents]
  (->> (str/split-lines (String. ^bytes (:bytes contents) "ISO-8859-1"))
       (filter #(str/ends-with? % " re"))
       (map #(->> (str/split % #"\s+") (take 4) (map parse-double)))))

(deftest header-and-footer-repeat-on-every-page
  (testing "a header/footer block draws on each paginated page"
    (let [body (into [:div {:style {:gap 10}}]
                     (for [i (range 25)]
                       [:div {:style {:height 30 :background-color [0 0 0]}}]))
          ps (layout/pages body {:page-size [612 792] :margin 36
                                 :header [:div {:style {:height 20 :background-color [1 0 0]}}]
                                 :footer [:div {:style {:height 20 :background-color [0 0 1]}}]})]
      (is (< 1 (count ps)) "content spans multiple pages")
      (doseq [p ps]
        (let [rs (rects (:contents p))]
          ;; header rect: top-left y=36 (top margin) -> pdf-y = 792-36-20 = 736
          (is (some (fn [[x y w h]] (and (= y 736.0) (= h 20.0))) rs) "header on page")
          ;; footer rect: pdf-y = 36 (bottom margin), h=20
          (is (some (fn [[x y w h]] (and (= y 36.0) (= h 20.0))) rs) "footer on page"))))))

(deftest header-reserves-content-height
  (testing "the body starts below the header, not at the top margin"
    (let [ps (layout/pages [:div {} [:div {:style {:height 30 :background-color [0 0 0]}}]]
                           {:margin 36
                            :header [:div {:style {:height 50 :background-color [1 0 0]}}]})
          rs (rects (:contents (first ps)))
          ;; body box (h=30, black) top-left y = header(50) -> pdf-y = 792-36-50-30 = 676
          body-rect (first (filter (fn [[_ _ _ h]] (= h 30.0)) rs))]
      (is (= 676.0 (second body-rect)) "body pushed down by the 50pt header"))))

(deftest end-to-end-with-header-footer-serializes
  (let [bytes (-> (new-pdf)
                  (layout/with-layout
                    (into [:div {:style {:gap 8}}]
                          (for [i (range 40)]
                            [:text {:style {:font font :font-size 12}} (str "Line " i)]))
                    {:header [:text {:style {:font font :font-size 10}} "My Document"]
                     :footer [:text {:style {:font font :font-size 10}} "Page footer"]})
                  serialize)
        full (String. bytes "ISO-8859-1")]
    (is (str/includes? full "%PDF-2.0"))
    (is (str/includes? full "/FontFile2"))))
