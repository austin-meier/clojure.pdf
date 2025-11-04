(require '[pdf.api :as pdf])

;; svg->form converts an SVG hiccup tree to a Form XObject (svg xml string parsing is
;; JVM only for now due to deps). Even though the single parsed form placed at three
;; sizes the engine only embeds single Form XObject in the rendered PDF.
(def art
  [:svg {:width 200 :height 200}
   [:rect {:x 8 :y 8 :width 184 :height 184 :rx 20 :fill "#f3f4f6"}]
   [:circle {:cx 74 :cy 82 :r 44 :fill "#2f6fe0"}]
   [:path {:d "M104 150 L164 54 L188 150 Z" :fill "#e0492f"}]
   [:circle {:cx 140 :cy 96 :r 26 :fill "#2fae4a"}]])

(let [icon (pdf/svg->form art)]
  (-> (pdf/new-pdf)
      (pdf/with-page
        (-> (pdf/new-page 612 792)
            (pdf/with-form icon 96 460 {:width 240})
            (pdf/with-form icon 380 560 {:width 120})
            (pdf/with-form icon 380 460 {:width 60})))))
