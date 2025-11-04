(ns file.common)

(defn byte->char [b] (char (bit-and 0xFF b)))
(defn bytes->string [bs encoding] (String. (byte-array bs) encoding))
(defn string->bytes [s encoding] (.getBytes s encoding))

(defonce markers
  {:EOL (String. (byte-array [(byte 13) (byte 10)]) "UTF-8")})