(ns pdf.file.header
  (:require
   [pdf.file.chunk :refer [append-chunks]]
   [pdf.bytes :as b]))

(defn pdf-binary-comment
  "Returns a byte source for a PDF binary comment line with bytes >= 128"
  []
  (b/from-unsigned [37 128 129 254 255 10]))

(defn serialize-header
  "Appends the PDF header line and binary comment to the serialization context"
  [serialization-ctx]
  (let [pdf-version (get-in serialization-ctx [:ctx :version] "1.7")]
    (append-chunks serialization-ctx
                   [(str "%PDF-" pdf-version "\n")
                    (pdf-binary-comment)])))
