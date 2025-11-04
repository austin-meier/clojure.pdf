(ns example.images
  "An image example with manual placement on a page, as well as
   the layout engine with one in a repeating page header. The photo is used on
   every page and in the header, and still embeds in the PDF exactly once

   Generate from repo root with:
     clojure -M:examples -m example.images"
  (:require
   [pdf.api :as pdf]
   [pdf.utils.dimension :refer [inches->dim]]))

(defn manual-page
  [photo badge]
  (-> (pdf/new-page (inches->dim 8.5) (inches->dim 11))
      ;; give one dimension and the other follows the aspect ratio
      (pdf/with-image photo 72 560 {:width 220})
      (pdf/with-image photo 330 560 {:height 80})
      ;; RGBA PNG: the alpha channel becomes a /SMask
      (pdf/with-image badge 72 400 {:width 120})))

(defn layout-body
  [font photo badge]
  [:div {:style {:gap 12 :padding 20 :font font :font-size 12}}
   [:div {:style {:flex-direction :row :gap 10 :align-items :center
                  :background-color [240 240 250] :padding 8}}
    [:image {:src photo :style {:width 48}}]
    [:text {:style {:font-size 20 :color [30 30 90]}} "images in the layout engine"]]
   [:text {} "This banner image, the one in the page header, and the two on the
              manual page before this are all a single embedded image object."]
   [:image {:src badge :style {:width 100}}]])

(defn -main []
  (let [ubuntu (pdf/new-font "test/resources/fonts/Ubuntu-Regular.ttf")
        photo  (pdf/new-image "test/resources/images/rgb.jpg")
        badge  (pdf/new-image "test/resources/images/rgba.png")]
    (-> (pdf/new-pdf)
        (pdf/with-page (manual-page photo badge))
        (pdf/with-layout (layout-body ubuntu photo badge)
          {:header [:div {:style {:padding 6}}
                    [:image {:src photo :style {:width 30}}]]})
        (pdf/save "example-images.pdf"))
    (println "wrote example-images.pdf")))
