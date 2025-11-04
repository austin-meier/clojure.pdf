(ns pdf.bytes
  "Portable big-endian reading and writing over a raw byte source: a JVM
   `byte[]` or a ClojureScript `js/Uint8Array`. Both are indexable, but JVM
   bytes are signed (-128..127) and Uint8Array entries are unsigned (0..255),
   so `ubyte` normalizes them.

   Multi-byte reads compose via multiplication rather than `bit-shift-left` so
   32-bit (and wider) values stay exact under ClojureScript, whose bitwise ops
   are signed 32-bit. SFNT is always big-endian, so that is the only order here.

   The write side mirrors the readers: each `*->bytes` renders a value to a
   vector of 0-255 ints, and `from-unsigned` packs such a vector into a
   platform byte source in one step at the boundary.

   File/network reading is platform I/O and stays at the edges (see pdf.utils.io on
   the JVM); everything below operates on an already-loaded byte source."
  #?(:clj (:import [java.util Arrays]
                   [java.security MessageDigest])))

(defn ubyte
  "Unsigned 0-255 value of the byte at index `i`."
  [src i]
  #?(:clj  (bit-and (aget ^bytes src i) 0xFF)
     :cljs (aget src i)))

(defn length
  "Number of bytes in the source."
  [src]
  #?(:clj  (alength ^bytes src)
     :cljs (.-length src)))

(defn u8  [src p] (ubyte src p))
(defn u16 [src p] (+ (* (ubyte src p) 0x100) (ubyte src (+ p 1))))
(defn u24 [src p] (+ (* (u16 src p) 0x100) (ubyte src (+ p 2))))
(defn u32 [src p] (+ (* (u16 src p) 0x10000) (u16 src (+ p 2))))

(defn i8  [src p] (let [n (u8 src p)]  (if (>= n 0x80) (- n 0x100) n)))
(defn i16 [src p] (let [n (u16 src p)] (if (>= n 0x8000) (- n 0x10000) n)))
(defn i32 [src p] (let [n (u32 src p)] (if (>= n 0x80000000) (- n 0x100000000) n)))

;; 64-bit values are exact under ClojureScript only below 2^53 (the JS float
;; ceiling). SFNT's only 64-bit fields (LONGDATETIME seconds since 1904) stay
;; far under it. The signed read composes from a signed high word so the JVM
;; side needs no >64-bit intermediate.
(defn u64 [src p] (+ (* (u32 src p) 0x100000000) (u32 src (+ p 4))))
(defn i64 [src p] (+ (* (i32 src p) 0x100000000) (u32 src (+ p 4))))

(defn fixed
  "16.16 signed fixed-point at `p` as a double (0x00010000 -> 1.0)."
  [src p]
  (/ (i32 src p) 65536.0))

(defn f2dot14
  "2.14 signed fixed-point at `p` as a double."
  [src p]
  (/ (i16 src p) 16384.0))

(defn tag
  "The 4-byte SFNT tag at `p` as a string (e.g. \"glyf\")."
  [src p]
  (apply str (map #(char (ubyte src (+ p %))) (range 4))))

(defn ascii
  "`n` bytes at `p` decoded as Latin-1/ASCII (1 byte = 1 char)."
  [src p n]
  (apply str (map #(char (ubyte src (+ p %))) (range n))))

(defn utf16-be
  "`n` bytes at `p` decoded as UTF-16BE (`n` must be even)."
  [src p n]
  (apply str (map #(char (u16 src (+ p (* 2 %)))) (range (quot n 2)))))

(defn slice
  "A copy of bytes [start, end) as a new byte source of the same platform type."
  [src start end]
  #?(:clj  (Arrays/copyOfRange ^bytes src (int start) (int end))
     :cljs (.slice src start end)))

(defn concat-bytes
  "One byte source from a sequence of byte sources, copied end to end. Copies
   through the platform primitive (no per-byte boxing), so it stays cheap for
   large payloads."
  [sources]
  (let [total (reduce + (map length sources))]
    #?(:clj  (let [out (byte-array total)]
               (reduce (fn [pos ^bytes src]
                         (System/arraycopy src 0 out pos (alength src))
                         (+ pos (alength src)))
                       0 sources)
               out)
       :cljs (let [out (js/Uint8Array. total)]
               (reduce (fn [pos src]
                         (.set out src pos)
                         (+ pos (length src)))
                       0 sources)
               out))))

;; ---------------------------------------------------------------------------
;; Writing: value -> vector of 0-255 ints, packed once via from-unsigned
;; ---------------------------------------------------------------------------

(defn from-unsigned
  "A platform byte source from a seq of 0-255 values."
  [coll]
  #?(:clj  (byte-array (map unchecked-byte coll))
     :cljs (js/Uint8Array. (into-array coll))))

(defn unsigned-vec
  "All bytes of a source as a vector of 0-255 values (inverse of
   `from-unsigned`), for building or comparing byte runs as plain data."
  [src]
  (mapv #(ubyte src %) (range (length src))))

(defn u8->bytes  [n] [(mod n 0x100)])
(defn u16->bytes [n] (into (u8->bytes (quot n 0x100)) (u8->bytes n)))
(defn u24->bytes [n] (into (u16->bytes (quot n 0x100)) (u8->bytes n)))
(defn u32->bytes [n] (into (u16->bytes (quot n 0x10000)) (u16->bytes n)))
(defn u64->bytes [n] (into (u32->bytes (quot n 0x100000000)) (u32->bytes n)))

(defn i8->bytes  [n] (u8->bytes  (if (neg? n) (+ n 0x100) n)))
(defn i16->bytes [n] (u16->bytes (if (neg? n) (+ n 0x10000) n)))
(defn i32->bytes [n] (u32->bytes (if (neg? n) (+ n 0x100000000) n)))

(defn i64->bytes
  "Two's-complement 64-bit bytes of `n`. Splits into a signed high word and an
   unsigned low word (the inverse of `i64`) so no >64-bit value is needed."
  [n]
  (let [lo (mod n 0x100000000)]
    (into (i32->bytes (quot (- n lo) 0x100000000)) (u32->bytes lo))))

(defn fixed->bytes
  "16.16 signed fixed-point bytes of a double (1.0 -> 0x00010000)."
  [x]
  (i32->bytes (Math/round (* x 65536.0))))

(defn f2dot14->bytes
  "2.14 signed fixed-point bytes of a double."
  [x]
  (i16->bytes (Math/round (* x 16384.0))))

(defn ascii->bytes
  "The chars of `s` as single bytes (Latin-1/ASCII; inverse of `ascii`/`tag`)."
  [s]
  (mapv (fn [i] #?(:clj  (int (.charAt ^String s i))
                   :cljs (.charCodeAt s i)))
        (range (count s))))

(defn content-hash
  "A stable content key for deduping identical byte payloads. JVM: SHA-1 hex;
   cljs: a deterministic hash of the bytes (dedupe needs equality within one
   document, not a cryptographic digest)."
  [bs]
  #?(:clj  (let [d (.digest (MessageDigest/getInstance "SHA-1") ^bytes bs)]
             (apply str (map #(format "%02x" %) d)))
     :cljs (str (hash (unsigned-vec bs)))))
