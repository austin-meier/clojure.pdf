(ns pdf.context.svg-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.form :as form]
   [pdf.context.page :as page]
   [pdf.context.pdf :as pdf]
   [pdf.context.stream :refer [pdf-stream?]]
   [pdf.context.svg :as svg]
   [pdf.context.svg.xml :as svg-xml]
   [pdf.utils.dimension :refer [points->dim]]))

(defn- ops-of [tree] (:ops (svg/svg->form tree)))

(defn- has-op? [ops op] (boolean (some #{op} ops)))

(deftest form-shape-and-flip
  (let [f (svg/svg->form [:svg {:viewBox "0 0 24 24"}
                          [:rect {:x 2 :y 2 :width 20 :height 20}]])]
    (is (= :form-context (:type f)))
    (is (= [0 0 24.0 24.0] (:bbox f)))
    (testing "the first op flips SVG's y-down space into PDF's y-up"
      (let [[op & args] (first (:ops f))]
        (is (= :concat-matrix op))
        (is (every? true? (map == [1 0 0 -1 0 24] args)))))))

(deftest viewbox-offset-lands-in-the-flip
  (let [[_ & args] (first (ops-of [:svg {:viewBox "-5 10 20 30"}]))]
    (is (every? true? (map == [1 0 0 -1 5 40] args)))))

(deftest sizing-without-a-viewbox
  (is (= [0 0 100.0 50.0]
         (:bbox (svg/svg->form [:svg {:width "100px" :height "50"}]))))
  (is (thrown? clojure.lang.ExceptionInfo (svg/svg->form [:svg {}]))))

(deftest shapes-emit-geometry-and-default-black-fill
  (let [ops (ops-of [:svg {:viewBox "0 0 24 24"}
                     [:rect {:x 2 :y 2 :width 20 :height 20}]])]
    (is (has-op? ops [:rectangle 2 2 20 20]))
    (is (has-op? ops [:set-rgb-fill 0.0 0.0 0.0]))
    (is (has-op? ops [:fill]))
    (testing "shapes isolate their graphics state"
      (is (has-op? ops [:save-state]))
      (is (has-op? ops [:restore-state])))))

(deftest circle-becomes-four-cubics
  (let [ops (ops-of [:svg {:viewBox "0 0 10 10"} [:circle {:cx 5 :cy 5 :r 5}]])]
    (is (= 4 (count (filter #(= :curve-to (first %)) ops))))
    (is (has-op? ops [:move-to 10 5]))
    (is (has-op? ops [:close-path]))))

(deftest stroke-attributes-compile-to-graphics-state-ops
  (let [ops (ops-of [:svg {:viewBox "0 0 24 24"}
                     [:rect {:width 20 :height 20 :fill "none" :stroke "#ff0000"
                             :stroke-width 2 :stroke-linecap "round"
                             :stroke-dasharray "3 1"}]])]
    (is (has-op? ops [:set-rgb-stroke 1.0 0.0 0.0]))
    (is (has-op? ops [:set-line-width 2]))
    (is (has-op? ops [:set-line-cap 1]))
    (is (has-op? ops [:set-dash [3.0 1.0] 0]))
    (testing "SVG's miter limit default (4) overrides PDF's (10)"
      (is (has-op? ops [:set-miter-limit 4])))
    (is (has-op? ops [:stroke]))
    (is (not (has-op? ops [:fill])))))

(deftest paint-op-follows-fill-and-stroke
  (let [both (ops-of [:svg {:viewBox "0 0 9 9"}
                      [:rect {:width 9 :height 9 :fill "red" :stroke "blue"}]])
        even (ops-of [:svg {:viewBox "0 0 9 9"}
                      [:path {:d "M0 0h9v9h-9z" :fill-rule "evenodd"}]])]
    (is (has-op? both [:fill-stroke]))
    (is (has-op? even [:fill-even-odd]))))

(deftest invisible-elements-emit-nothing
  (testing "fill and stroke both none"
    (is (= 1 (count (ops-of [:svg {:viewBox "0 0 9 9"}
                             [:rect {:width 9 :height 9 :fill "none"}]])))))
  (testing "display:none"
    (is (= 1 (count (ops-of [:svg {:viewBox "0 0 9 9"}
                             [:g {:display "none"} [:rect {:width 9 :height 9}]]])))))
  (testing "defs, title and friends are skipped"
    (is (= 1 (count (ops-of [:svg {:viewBox "0 0 9 9"}
                             [:title "icon"]
                             [:defs [:linearGradient {:id "g"}]]]))))))

(deftest groups-inherit-style-and-carry-transforms
  (let [ops (ops-of [:svg {:viewBox "0 0 24 24"}
                     [:g {:fill "red" :transform "translate(5 5)"}
                      [:rect {:width 4 :height 4}]]])]
    (is (has-op? ops [:concat-matrix 1 0 0 1 5.0 5.0]))
    (is (has-op? ops [:set-rgb-fill 1.0 0.0 0.0]))))

(deftest style-attribute-wins-over-presentation-attributes
  (let [ops (ops-of [:svg {:viewBox "0 0 9 9"}
                     [:rect {:width 9 :height 9 :fill "blue" :style "fill:#00ff00"}]])]
    (is (has-op? ops [:set-rgb-fill 0.0 1.0 0.0]))))

(deftest current-color-resolves-through-the-color-property
  (let [ops (ops-of [:svg {:viewBox "0 0 9 9" :color "#0000ff"}
                     [:rect {:width 9 :height 9 :fill "currentColor"}]])]
    (is (has-op? ops [:set-rgb-fill 0.0 0.0 1.0]))))

(deftest color-syntaxes
  (is (= [1.0 0.0 0.0] (svg/parse-color "#f00" nil)))
  (is (= [1.0 0.0 0.0] (svg/parse-color "rgb(255, 0, 0)" nil)))
  (is (= [1.0 0.0 0.0] (svg/parse-color "rgb(100% 0% 0%)" nil)))
  (is (= [1.0 0.0 0.0] (svg/parse-color "RED" nil)))
  (is (nil? (svg/parse-color "none" nil)))
  (is (thrown? clojure.lang.ExceptionInfo (svg/parse-color "#ff000080" nil)))
  (is (thrown? clojure.lang.ExceptionInfo (svg/parse-color "rgba(0,0,0,0.5)" nil)))
  (is (thrown? clojure.lang.ExceptionInfo (svg/parse-color "url(#grad)" nil)))
  (is (thrown? clojure.lang.ExceptionInfo (svg/parse-color "peachpuff" nil))))

(deftest transform-lists-compose-left-to-right
  (is (= [2.0 0.0 0.0 2.0 2.0 3.0]
         (mapv double (svg/parse-transform "translate(2 3) scale(2)"))))
  (let [[a b c d] (svg/parse-transform "rotate(90)")]
    (is (every? true? (map #(< (abs (- %1 %2)) 1e-9) [a b c d] [0 1 -1 0])))))

(deftest unsupported-content-throws-instead-of-misrendering
  (is (thrown? clojure.lang.ExceptionInfo
               (svg/svg->form [:svg {:viewBox "0 0 9 9"} [:text {} "hi"]])))
  (is (thrown? clojure.lang.ExceptionInfo
               (svg/svg->form [:svg {:viewBox "0 0 9 9"}
                               [:rect {:width 9 :height 9 :opacity "0.5"}]])))
  (is (thrown? clojure.lang.ExceptionInfo
               (svg/svg->form [:svg {:viewBox "0 0 9 9"}
                               [:use {:href "#icon"}]]))))

(def ^:private doc-string
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
   <!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\"
     \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">
   <svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\">
     <g fill=\"#336699\">
       <circle cx=\"12\" cy=\"12\" r=\"10\"/>
       <path d=\"M6 12 L18 12\" stroke=\"white\" fill=\"none\"/>
     </g>
   </svg>")

(deftest xml-documents-parse-and-convert
  (let [f (svg/svg->form (svg-xml/string->tree doc-string))]
    (is (= [0 0 24.0 24.0] (:bbox f)))
    (is (has-op? (:ops f) [:set-rgb-stroke 1.0 1.0 1.0]))
    (is (= 4 (count (filter #(= :curve-to (first %)) (:ops f)))))))

(deftest svg-forms-serialize-and-dedupe
  (let [f    (svg/svg->form (svg-xml/string->tree doc-string))
        pg   (-> (page/new-page (points->dim 200) (points->dim 200))
                 (form/with-form f 10 10 {:width 48})
                 (form/with-form f 100 10 {:width 96}))
        ctx  (page/with-page (pdf/new-pdf) pg)
        objs (->> (:objects (pdf/number-context (pdf/resolve-context-objects (pdf/embed-forms ctx))))
                  (filter #(and (pdf-stream? %) (= :form (get-in % [:dict :subtype])))))]
    (testing "one form context placed twice embeds once"
      (is (= 1 (count objs))))
    (testing "the full pipeline serializes"
      (is (pos? (alength (pdf/serialize ctx)))))))
