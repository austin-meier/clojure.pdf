(ns file.pdf
  (:require
   [file.body :refer [serialize-objects]]
   [file.common :refer [bytes->string]]
   [file.header :refer [serialize-header]]
   [file.trailer :refer [serialize-trailer]]
   [file.xref :refer [serialize-xref]]))

(defn new-serialization-context [pdf-ctx]
  {:serialized-bytes [] ;; The actual byte array of the serialized PDF. Converts to UTF-8 string at the end.
   :object-offsets [] ;; list of byte offsets for each object. populated by serialize-objects
   :xref-offset 0 ;; byte offset for xref table. populated by serialize-xref
   :ctx pdf-ctx})

(defn serialize
  "Write the PDF to a serialized string"
  [pdf-ctx]
  (->
   pdf-ctx
   new-serialization-context
   serialize-header
   serialize-objects
   serialize-xref
   serialize-trailer
   :serialized-bytes
   (bytes->string "UTF-8")))

(def test-pdf-ctx
  {:version 2.0
   :objects
   [{:type :catalog}]})

(serialize test-pdf-ctx)