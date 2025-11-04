(ns pdf.bytes-test
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [pdf.bytes :as b]))

(defn bs
  "A platform byte source from a seq of 0-255 values."
  [coll]
  #?(:clj  (byte-array (map unchecked-byte coll))
     :cljs (js/Uint8Array. (clj->js coll))))

(deftest unsigned-reads
  (testing "u8/u16/u24/u32 read big-endian unsigned"
    (is (= 42 (b/u8 (bs [0x2A]) 0)))
    (is (= 256 (b/u16 (bs [0x01 0x00]) 0)))
    (is (= 65535 (b/u16 (bs [0xFF 0xFF]) 0)))
    (is (= 65536 (b/u24 (bs [0x01 0x00 0x00]) 0)))
    (is (= 65536 (b/u32 (bs [0x00 0x01 0x00 0x00]) 0))))

  (testing "u32 stays exact at the 32-bit boundary (the ClojureScript hazard)"
    (is (= 4294967295 (b/u32 (bs [0xFF 0xFF 0xFF 0xFF]) 0)))
    (is (= 2147483648 (b/u32 (bs [0x80 0x00 0x00 0x00]) 0)))))

(deftest signed-reads
  (testing "i8/i16/i32 sign-extend"
    (is (= -1 (b/i8 (bs [0xFF]) 0)))
    (is (= -1 (b/i16 (bs [0xFF 0xFF]) 0)))
    (is (= -32768 (b/i16 (bs [0x80 0x00]) 0)))
    (is (= -1 (b/i32 (bs [0xFF 0xFF 0xFF 0xFF]) 0)))
    (is (= 1 (b/i16 (bs [0x00 0x01]) 0)))))

(deftest fixed-point
  (testing "16.16 fixed reads as a double"
    (is (= 1.0 (b/fixed (bs [0x00 0x01 0x00 0x00]) 0)))
    (is (= 1.5 (b/fixed (bs [0x00 0x01 0x80 0x00]) 0)))))

(deftest reads-at-offset
  (is (= 0x0203 (b/u16 (bs [0x00 0x01 0x02 0x03]) 2))))

(deftest sixty-four-bit-reads
  (testing "u64/i64 compose exactly below the 2^53 JS float ceiling"
    (is (= 4294967296 (b/u64 (bs [0 0 0 1 0 0 0 0]) 0)))
    (is (= -1 (b/i64 (bs [0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF 0xFF]) 0)))
    ;; 2021-01-01 as LONGDATETIME-ish seconds since 1904
    (is (= 3692217600 (b/i64 (bs [0x00 0x00 0x00 0x00 0xDC 0x12 0xC5 0x00]) 0)))))

(deftest writes-invert-reads
  (testing "each *->bytes round-trips through its reader"
    (is (= 42 (b/u8 (b/from-unsigned (b/u8->bytes 42)) 0)))
    (is (= 65535 (b/u16 (b/from-unsigned (b/u16->bytes 65535)) 0)))
    (is (= 65536 (b/u24 (b/from-unsigned (b/u24->bytes 65536)) 0)))
    (is (= 4294967295 (b/u32 (b/from-unsigned (b/u32->bytes 4294967295)) 0)))
    (is (= -1 (b/i8 (b/from-unsigned (b/i8->bytes -1)) 0)))
    (is (= -32768 (b/i16 (b/from-unsigned (b/i16->bytes -32768)) 0)))
    (is (= -189 (b/i16 (b/from-unsigned (b/i16->bytes -189)) 0)))
    (is (= -2147483648 (b/i32 (b/from-unsigned (b/i32->bytes -2147483648)) 0)))
    (is (= 3692217600 (b/i64 (b/from-unsigned (b/i64->bytes 3692217600)) 0)))
    (is (= -4294967296 (b/i64 (b/from-unsigned (b/i64->bytes -4294967296)) 0)))
    (is (= 1.5 (b/fixed (b/from-unsigned (b/fixed->bytes 1.5)) 0)))
    (is (= -0.5 (b/f2dot14 (b/from-unsigned (b/f2dot14->bytes -0.5)) 0)))
    (is (= "glyf" (b/tag (b/from-unsigned (b/ascii->bytes "glyf")) 0)))))

(deftest text-and-slice
  (testing "tag and ascii decode single bytes to chars"
    (is (= "glyf" (b/tag (bs [0x67 0x6C 0x79 0x66]) 0)))
    (is (= "AB" (b/ascii (bs [0x41 0x42]) 0 2))))

  (testing "utf16-be decodes two bytes per char"
    (is (= "Hi" (b/utf16-be (bs [0x00 0x48 0x00 0x69]) 0 4))))

  (testing "slice copies a byte range"
    (let [src (bs [0x00 0x01 0x02 0x03 0x04])
          out (b/slice src 1 4)]
      (is (= 3 (b/length out)))
      (is (= [1 2 3] [(b/u8 out 0) (b/u8 out 1) (b/u8 out 2)])))))

(deftest concat-joins-sources
  (testing "concat-bytes copies sources end to end"
    (let [out (b/concat-bytes [(bs [0x00 0x01]) (bs [0x02]) (bs [0x03 0x04])])]
      (is (= 5 (b/length out)))
      (is (= [0 1 2 3 4] (b/unsigned-vec out)))))
  (testing "empty and single-source edges"
    (is (= 0 (b/length (b/concat-bytes []))))
    (is (= [7 8] (b/unsigned-vec (b/concat-bytes [(bs [7 8])]))))))
