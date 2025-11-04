(ns pdf.file.body
  (:require
   [pdf.context.stream :refer [pdf-stream?]]
   [pdf.file.chunk :refer [append-chunks]]
   [pdf.serialize :refer [to-pdf]]
   [pdf.bytes :as b]))

(defn- byte-source? [x]
  #?(:clj  (bytes? x)
     :cljs (instance? js/Uint8Array x)))

(defn- payload-bytes [payload]
  (if (byte-source? payload) payload (b/from-unsigned payload)))

(defn stream-chunks
  "Chunks for a PDF stream object: the dict with a computed :length, then the
   raw payload framed by stream/endstream. The payload stays a byte source."
  [stream]
  (let [payload (payload-bytes (:bytes stream))
        dict (assoc (:dict stream) :length (b/length payload))]
    [(to-pdf dict) "\nstream\n" payload "\nendstream"]))

(defn object-chunks
  "Chunks for the numbered indirect object at context index `idx`"
  [idx obj]
  (concat
   [(str (inc idx) " 0 obj\n")]
   (if (pdf-stream? obj)
     (stream-chunks obj)
     [(to-pdf obj)])
   ["\nendobj\n"]))

(defn serialize-objects
  "Appends every object from the :ctx to the serialization context as chunks.
   Records each object's absolute byte offset in :object-offsets."
  [serialization-ctx]
  (reduce
   (fn [sctx [idx obj]]
     (-> sctx
         (update :object-offsets conj (:length sctx))
         (append-chunks (object-chunks idx obj))))
   serialization-ctx
   (map-indexed vector (get-in serialization-ctx [:ctx :objects]))))
