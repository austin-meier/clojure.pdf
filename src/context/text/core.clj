(ns context.text.core)

(defn with-text
  "Adds a text stream to the page context."
  [page-ctx txt-ctx]
  (-> page-ctx
      (update :contents conj (:text txt-ctx))
      (update :resources conj (:font txt-ctx))))

(defn new-text
  "Creates a new text context for use on a page."
  [font-ctx text]
  {:text {:text text}
   :font font-ctx})