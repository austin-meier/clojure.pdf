(ns file.trailer
  (:require
   [context.pdf :refer [catalog-ref]]
   [objects.pdf-serializable-protocol :refer [to-pdf]]))

(defn build-trailer-map
  "Builds the trailer map for a context"
  [ctx]
  {:size (count (:objects ctx))
   :root (catalog-ref ctx)})

(defn serialize-trailer
  "Appends the PDF trailer to the serialized bytes in the context.
   Uses :xref-offset for startxref and updates the serialization context."
  [serialization-ctx]
  (let [trailer-map (build-trailer-map (:ctx serialization-ctx))
        trailer-str (str "trailer\n"
                         (to-pdf trailer-map) "\n"
                         "startxref\n"
                         (:xref-offset serialization-ctx) "\n"
                         "%%EOF\n")
        trailer-bytes (.getBytes trailer-str "UTF-8")]
    (update serialization-ctx :serialized-bytes into trailer-bytes)))


