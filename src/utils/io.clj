(ns utils.io
  (:require [clojure.java.io :as io]))


(defn file->bytes
  "Reads the contents of a file at `path` and returns it as a byte array."
  [path]
  (with-open [in (io/input-stream path)
              out (java.io.ByteArrayOutputStream.)]
    (io/copy in out)
    (.toByteArray out)))