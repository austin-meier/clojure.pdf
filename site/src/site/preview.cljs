(ns site.preview)

(defonce ^:private last-url (atom nil))

(defn show-pdf!
  [iframe bytes]
  (let [blob (js/Blob. #js [bytes] #js {:type "application/pdf"})
        url  (js/URL.createObjectURL blob)]
    (when-let [old @last-url] (js/URL.revokeObjectURL old))
    (reset! last-url url)
    (set! (.-src iframe) url)))
