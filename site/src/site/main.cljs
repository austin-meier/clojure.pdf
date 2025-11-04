(ns site.main
  (:require
   [site.eval :as e]
   [site.editor :as editor]
   [site.examples :as examples]
   [site.preview :as preview]
   [site.split :as split]
   [pdf.context.text.font :as font]))

(defonce ^:private state (atom {:ctx nil :view nil :timer nil}))

(defn el [id] (js/document.getElementById id))

(defn render! []
  (let [err (el "error")]
    (try
      (preview/show-pdf! (el "preview") (e/eval->bytes (:ctx @state) (editor/current-doc (:view @state))))
      (set! (.-textContent err) "")
      (set! (.. err -style -display) "none")
      (catch :default ex
        (set! (.-textContent err) (str (ex-message ex)
                                       (when-let [d (ex-data ex)] (str "\n\n" (pr-str d)))))
        (set! (.. err -style -display) "block")))))

(defn schedule-render! []
  (js/clearTimeout (:timer @state))
  (swap! state assoc :timer (js/setTimeout render! 350)))

(defn populate-examples! [select]
  (doseq [[i {:keys [name]}] (map-indexed vector examples/all)]
    (let [opt (js/document.createElement "option")]
      (set! (.-value opt) (str i))
      (set! (.-textContent opt) name)
      (.appendChild select opt))))

(defn load-example! [i]
  (editor/set-doc! (:view @state) (:code (nth examples/all i)))
  (render!))

(defn start [font-bytes]
  (let [view   (editor/make-editor (el "editor") (:code (first examples/all)) schedule-render!)
        select (el "examples")]
    (swap! state assoc :ctx (e/make-ctx (font/new-font font-bytes)) :view view)
    (populate-examples! select)
    (.addEventListener select "change" #(load-example! (js/parseInt (.. % -target -value))))
    (.addEventListener (el "run") "click" render!)
    (render!)))

(defn init []
  (split/init (el "split") (el "gutter") (el "pane-editor"))
  (-> (js/fetch "Ubuntu-Regular.ttf")
      (.then #(.arrayBuffer %))
      (.then #(start (js/Uint8Array. %)))
      (.catch (fn [ex]
                (set! (.-textContent (el "error")) (str "Font load failed: " ex))
                (set! (.. (el "error") -style -display) "block")))))
