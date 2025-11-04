(ns pdf.context.stream
  (:require
   [pdf.serialize :refer [PdfSerializable]]
   [pdf.bytes :as b]))

(defrecord PdfStream [dict bytes])

(defn pdf-stream? [obj]
  (instance? PdfStream obj))

(defn bytes->stream
  "Convert a byte source to a pdf stream object"
  ([bytes]
   (->PdfStream {} bytes))
  ([bytes dict]
   (->PdfStream dict bytes)))

(defn string->stream
  "Convert a string to a pdf stream object. Needs to be ASCII"
  ([s]
   (bytes->stream (b/from-unsigned (b/ascii->bytes s))))
  ([s dict]
   (bytes->stream (b/from-unsigned (b/ascii->bytes s)) dict)))

(extend-protocol PdfSerializable
  PdfStream
  (to-pdf [_]
    (throw (ex-info "PdfStream serializes as chunks, not syntax. Streams must be resolved to indirect objects first." {}))))
