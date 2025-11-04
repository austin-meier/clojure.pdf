(require '[pdf.api :as pdf])

;; `font` is preloaded to be the bytes of the Ubuntu TTF font.
(let [title (assoc (pdf/new-text "Hello, PDF" font) :font-size 28)
      body  (pdf/new-text
              "A page is a map, a font is also a map"
              font)]
  (-> (pdf/new-pdf)
      (pdf/with-page
        (-> (pdf/new-page 612 792)
            (pdf/with-text 72 684 title)
            (pdf/with-text 72 648 body)))))
