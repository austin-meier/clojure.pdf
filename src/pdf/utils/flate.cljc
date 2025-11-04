(ns pdf.utils.flate
  "zlib inflate/deflate, bytes in, bytes out. The one codec seam: a PNG's IDAT
   is zlib-compressed, and the parser inflates xref/object streams. On the JVM
   it's java.util.zip; under ClojureScript it's node's built-in zlib (a node
   Buffer is already a Uint8Array, so it flows straight through pdf.bytes).
   That makes cljs a Node target; the browser has only the async
   CompressionStream, which doesn't fit the synchronous pipeline."
  #?(:clj  (:import [java.io ByteArrayOutputStream]
                    [java.util.zip Deflater Inflater])
     :cljs (:require ["node:zlib" :as zlib])))

(defn inflate
  "Decompress a zlib stream to its raw bytes."
  [data]
  #?(:clj
     (let [inf (doto (Inflater.) (.setInput ^bytes data))
           out (ByteArrayOutputStream. (alength ^bytes data))
           buf (byte-array 8192)]
       (loop []
         (when-not (.finished inf)
           (let [n (.inflate inf buf)]
             (when (pos? n) (.write out buf 0 n))
             (when (or (pos? n) (not (.needsInput inf)))
               (recur)))))
       (.end inf)
       (.toByteArray out))
     :cljs
     (zlib/inflateSync data)))

(defn deflate
  "Compress raw bytes to a zlib stream (the shape /FlateDecode expects)."
  [data]
  #?(:clj
     (let [def (doto (Deflater.) (.setInput ^bytes data) (.finish))
           out (ByteArrayOutputStream. (alength ^bytes data))
           buf (byte-array 8192)]
       (loop []
         (when-not (.finished def)
           (let [n (.deflate def buf)]
             (when (pos? n) (.write out buf 0 n))
             (recur))))
       (.end def)
       (.toByteArray out))
     :cljs
     (zlib/deflateSync data)))
