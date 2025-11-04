(ns example.layout
  "The layout engine. flex rows, wrapped text, pagination, headers+footers.

   Generate from repo root with:
     clojure -M:examples -m example.layout"
  (:require
   [pdf.api :as pdf]))

(def row-content
  "Each of these rows is a flex row.
   break-inside :avoid keeps a row from being sliced across a page break.")

(defn row [i]
  [:div {:style {:flex-direction :row :gap 10 :padding [6 8]
                 :break-inside :avoid
                 :background-color (if (even? i) [244 246 252] [255 255 255])}}
   [:div {:style {:width 50}} [:text {} (str "#" i)]]
   [:div {:style {:flex-grow 1}} [:text {} row-content]]
   [:div {:style {:width 70}} [:text {} (str (* i 3) " pts")]]])

(defn report [font]
  [:div {:style {:font font :font-size 10 :gap 4}}
   [:text {:style {:font-size 24 :color [24 23 66]}} "Layout demo"]
   [:div {:style {:flex-direction :row :gap 12 :height 40}}
    [:div {:style {:flex-grow 1 :background-color [12 74 110]}}]
    [:div {:style {:width 120 :background-color [253 186 116]}}]]
   (into [:div {:style {:gap 2}}] (map row (range 1 25)))])

(defn -main []
  (let [ubuntu (pdf/new-font "test/resources/fonts/Ubuntu-Regular.ttf")]
    (-> (pdf/new-pdf)
        (pdf/with-layout (report ubuntu)
          {:header [:text {:style {:font ubuntu :font-size 9 :color [120 120 130]}}
                    "I am a header!"]
           :footer [:text {:style {:font ubuntu :font-size 9 :color [120 120 130]}}
                    "I am a footer!"]})
        (pdf/save "example-layout.pdf"))
    (println "wrote example-layout.pdf")))
