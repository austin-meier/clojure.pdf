(ns context.text.font-parser
  (:import [java.nio ByteBuffer ByteOrder]))

(defn read-uint16 [buf] (bit-and 0xFFFF (.getShort buf)))
(defn read-int16 [buf] (.getShort buf))
(defn read-uint32 [buf] (.getInt buf))
(defn read-fixed [buf]
  (let [raw (.getInt buf)
        int-part (bit-shift-right raw 16)
        frac-part (bit-and raw 0xFFFF)]
    (+ int-part (/ frac-part 65536.0))))

(defn read-tag [buf]
  (let [bytes (byte-array 4)]
    (.get buf bytes)
    (String. bytes "ISO-8859-1")))

(defn sfnt-bytes
  "Return the raw SFNT bytes to embed. If `bytes` is a TrueType Collection
  (TTC) this extracts the first font's sfnt bytes; otherwise returns the
  original bytes unchanged. This keeps embedding simple while handling
  TTC files where the file contains multiple faces."
  [bytes]
  (let [buf (doto (java.nio.ByteBuffer/wrap bytes)
              (.order java.nio.ByteOrder/BIG_ENDIAN))
        tag-bytes (byte-array 4)]
    (.get buf tag-bytes)
    (let [tag (String. tag-bytes "ISO-8859-1")]
      (if (= tag "ttcf")
        ;; TTC header: 'ttcf' (4) + version (4) + numFonts (4) + offsets[numFonts]
        (let [_ (.getInt buf) ;; version
              num-fonts (.getInt buf)
              offsets (vec (repeatedly num-fonts #(.getInt buf)))
              start (nth offsets 0)
              end (if (> num-fonts 1) (nth offsets 1) (alength bytes))]
          (java.util.Arrays/copyOfRange bytes start end))
        bytes))))

(defn table-directory [buf]
  (.position buf 4) ;; skip sfnt version
  (let [num-tables (read-uint16 buf)]
    (.position buf (+ (.position buf) 6)) ;; skip searchRange, entrySelector, rangeShift
    (loop [i 0 acc {}]
      (if (< i num-tables)
        (let [tag (read-tag buf)
              _ (read-uint32 buf) ;; checksum
              offset (read-uint32 buf)
              length (read-uint32 buf)]
          (recur (inc i) (assoc acc tag {:offset offset :length length})))
        acc))))

(defn parse-head [buf offset]
  (.position buf (+ offset 36))
  {:xMin (read-int16 buf)
   :yMin (read-int16 buf)
   :xMax (read-int16 buf)
   :yMax (read-int16 buf)})

(defn parse-hhea [buf offset]
  (.position buf (+ offset 4)) ;; skip version
  {:ascent (read-int16 buf)
   :descent (read-int16 buf)
   :lineGap (read-int16 buf)})

(defn parse-post [buf offset]
  (.position buf (+ offset 4))
  {:italic-angle (read-fixed buf)})

(defn parse-font-tables [bytes]
  (let [buf (doto (ByteBuffer/wrap bytes)
              (.order ByteOrder/BIG_ENDIAN))
        tables (table-directory buf)
        head (parse-head buf (:offset (tables "head")))
        hhea (parse-hhea buf (:offset (tables "hhea")))
        post (parse-post buf (:offset (tables "post")))]
    (merge head hhea post)))
