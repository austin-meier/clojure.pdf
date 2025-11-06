(ns file.trailer
  (:require
   [protocols.pdf-serializable-protocol :refer [to-pdf]]))

(defn build-trailer-map
  "Builds the trailer map for a serialization context"
  [serialization-ctx]
  {:size (count (:objects (:ctx serialization-ctx)))
   :root (:catalog-ref serialization-ctx)})

(defn serialize-trailer
  "Appends the PDF trailer to the serialized bytes in the context.
   Uses :xref-offset for startxref and updates the serialization context."
  [serialization-ctx]
  (let [trailer-map (build-trailer-map serialization-ctx)
        trailer-str (str "trailer\n"
                         (to-pdf trailer-map) "\n"
                         "startxref\n"
                         (:xref-offset serialization-ctx) "\n"
                         "%%EOF\n")
        trailer-bytes (.getBytes trailer-str "UTF-8")]
    (update serialization-ctx :serialized-bytes into trailer-bytes)))


