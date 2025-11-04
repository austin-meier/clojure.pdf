(ns context.stream)

(defrecord PdfStream [dict bytes])

(defn bytes->stream
  "Convert a byte array to a pdf stream object"
  [bytes]
  (->PdfStream {} bytes))

(defn string->stream
  "Convert a string to a pdf stream object"
  [s]
  (bytes->stream (.getBytes s "UTF-8")))
