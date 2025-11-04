(ns site.split)

(def ^:private breakpoint "(max-width: 720px)")

(defn vertical? []
  (.-matches (js/window.matchMedia breakpoint)))

(defn clamp [lo hi v] (max lo (min hi v)))

(defn set-fraction! [pane f]
  (set! (.. pane -style -flex) (str "0 0 " (* 100 f) "%")))

(defn fraction-at [container e]
  (let [rect (.getBoundingClientRect container)]
    (clamp 0.15 0.85
           (if (vertical?)
             (/ (- (.-clientY e) (.-top rect)) (.-height rect))
             (/ (- (.-clientX e) (.-left rect)) (.-width rect))))))

(defn init
  [container gutter pane]
  (let [move (fn [e] (.preventDefault e) (set-fraction! pane (fraction-at container e)))
        stop (fn stop [_]
               (js/window.removeEventListener "pointermove" move)
               (js/window.removeEventListener "pointerup" stop)
               (set! (.. js/document -body -style -userSelect) ""))]
    (.addEventListener gutter "pointerdown"
                       (fn [e]
                         (.preventDefault e)
                         (set! (.. js/document -body -style -userSelect) "none")
                         (js/window.addEventListener "pointermove" move)
                         (js/window.addEventListener "pointerup" stop)))))
