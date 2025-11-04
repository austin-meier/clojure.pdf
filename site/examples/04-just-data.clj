(require '[pdf.api :as pdf])

;; You can draw with raw content ops, and therefore add any operation
;; the API has no builder for. Content streams are just vectors of [op & operands].
;; so they are easy to compose
(def lines
  (pdf/ops->stream
   (into [[:set-rgb-stroke 0.35 0.51 0.85] [:set-line-width 1]]
         (mapcat (fn [i]
                   [[:move-to (* i 20) 400] [:line-to (+ 200 (* i 20)) 600]
                    [:move-to (+ 200 (* i 20)) 400] [:line-to (* i 20) 600]
                    [:stroke]])
                 (range 11)))))

(-> (pdf/new-pdf)
    (pdf/with-page
      (-> (pdf/new-page 612 792)
          (pdf/with-stream lines)
          ;; no builder for /Rotate... but it's a map, so you can just put it there
          (assoc :rotate 90))))
