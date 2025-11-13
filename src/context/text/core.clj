(ns context.text.core
  (:require
   [context.page :refer [add-or-get-resource]]
   [context.stream :refer [string->stream]]
   [utils.dimension :refer [dim->points]]))

(defn text->stream
  "Converts the text context to a PDF content stream."
  [text-ctx font-key]
  (let [{:keys [text font-size x y]} text-ctx]
    (string->stream
     (format "BT\n/%s %d Tf\n%f %f Td\n(%s) Tj\nET\n"
             font-key font-size (dim->points x) (dim->points y) text))))

(defn with-text
  "Adds a text stream to the page context."
  [page-ctx x y txt-ctx]
  (let [[page-ctx font-key] (add-or-get-resource page-ctx :font (:font txt-ctx))]
    (update page-ctx :contents conj
      (text->stream (assoc txt-ctx :x x :y y) font-key))))

(defn new-text
  "Creates a new text context for use on a page."
  [text font-ctx]
  {:text text
   :font-size 12
   :font font-ctx})
