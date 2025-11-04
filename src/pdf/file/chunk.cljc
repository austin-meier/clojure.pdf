(ns pdf.file.chunk
  (:require [pdf.bytes :as b]))

;; A chunk is either a String of PDF syntax or a byte source of raw payload.
;; binary payloads stay byte sources end to end

(defn- byte-source? [x]
  #?(:clj  (bytes? x)
     :cljs (instance? js/Uint8Array x)))

(defn chunk->bytes
  "Returns the chunk's bytes as a platform byte source."
  [chunk]
  (cond
    (byte-source? chunk) chunk
    (string? chunk)      (b/from-unsigned (b/ascii->bytes chunk))
    :else (throw (ex-info "Chunks must be strings or byte sources" {:chunk chunk}))))

(defn chunk-length
  "Returns the byte length of the chunk."
  [chunk]
  (cond
    (byte-source? chunk) (b/length chunk)
    (string? chunk)      (count chunk)
    :else (throw (ex-info "Chunks must be strings or byte sources" {:chunk chunk}))))

(defn append-chunks
  "Appends chunks to the serialization context's :chunks, advancing :length
   by their total byte length."
  [serialization-ctx chunks]
  (-> serialization-ctx
      (update :chunks into chunks)
      (update :length + (reduce + 0 (map chunk-length chunks)))))

(defn chunks->byte-array
  "Concatenates chunks into a single platform byte source."
  [chunks]
  #?(:clj
     (let [out (java.io.ByteArrayOutputStream.)]
       (doseq [chunk chunks]
         (.write out ^bytes (chunk->bytes chunk)))
       (.toByteArray out))
     :cljs
     (let [arrays (mapv chunk->bytes chunks)
           total (reduce + 0 (map #(.-length %) arrays))
           result (js/Uint8Array. total)]
       (reduce (fn [offset arr]
                 (.set result arr offset)
                 (+ offset (.-length arr)))
               0 arrays)
       result)))
