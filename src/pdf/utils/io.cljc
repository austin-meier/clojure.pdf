(ns pdf.utils.io
  "File I/O, the platform edge. JVM streams on Clojure; node's `fs` under
   ClojureScript (a node Buffer is already a Uint8Array). Browser builds have no
   filesystem, so they use `parse`/`serialize` over bytes instead of these."
  #?(:clj  (:require [clojure.java.io :as io])
     :cljs (:require ["node:fs" :as fs])))

(defn file->bytes
  "Reads the file at `path` and returns its bytes."
  [path]
  #?(:clj
     (with-open [in (io/input-stream path)
                 out (java.io.ByteArrayOutputStream.)]
       (io/copy in out)
       (.toByteArray out))
     :cljs
     (js/Uint8Array. (fs/readFileSync path))))

(defn bytes->file
  "Writes the byte source `bytes` to `path`."
  [path bytes]
  #?(:clj
     (with-open [out (io/output-stream path)]
       (.write out ^bytes bytes))
     :cljs
     (fs/writeFileSync path bytes)))
