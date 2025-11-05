(ns context.text.core)

(defn with-text
  "Adds a text stream to the page context."
  [page-ctx txt-ctx]
  (update page-ctx :contents conj txt-ctx))

(defn new-text
  "Creates a new text context for use on a page."
  []
  {:type :text
   :content []})