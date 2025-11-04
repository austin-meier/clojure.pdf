(ns pdf.file.trailer
  (:require
   [pdf.file.chunk :refer [append-chunks]]
   [pdf.serialize :refer [to-pdf]]))

(defn build-trailer-map
  "Builds the trailer map for a serialization context. Merges any :trailer
   extras carried on the context (the parser puts /Info, /ID and other
   surviving trailer keys there, already ref-rewritten) under the recomputed
   :size and :root, so a round-tripped document keeps them."
  [serialization-ctx]
  (merge (get-in serialization-ctx [:ctx :trailer])
         {:size (count (:objects (:ctx serialization-ctx)))
          :root (:catalog-ref serialization-ctx)}))

(defn serialize-trailer
  "Appends the PDF trailer to the serialization context.
   Uses :xref-offset for startxref."
  [serialization-ctx]
  (append-chunks serialization-ctx
                 [(str "trailer\n"
                       (to-pdf (build-trailer-map serialization-ctx)) "\n"
                       "startxref\n"
                       (:xref-offset serialization-ctx) "\n"
                       "%%EOF\n")]))
