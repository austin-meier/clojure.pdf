(ns context.text.font-parser
  (:import [java.nio ByteBuffer ByteOrder]
           [java.nio.file Files Paths]))

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
  (.position buf (+ offset 36)) ;; skip to bbox
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
