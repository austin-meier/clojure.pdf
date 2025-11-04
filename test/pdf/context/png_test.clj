(ns pdf.context.png-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.image.png :as png]
   [pdf.context.image.xobject :as xobject]
   [pdf.bytes :as b]
   [pdf.utils.io :refer [file->bytes]]
   [pdf.bytes.memory :as mem]))

(defn- fixture [name] (file->bytes (str "test/resources/images/" name)))

(deftest reads-ihdr
  (testing "The chunk walk reads IHDR dimensions, bit depth and color type"
    (let [rgb (png/parse (fixture "rgb.png"))]
      (is (= 16 (:width rgb)))
      (is (= 12 (:height rgb)))
      (is (= 8 (:bits rgb)))
      (is (= 2 (:color-type rgb)))
      (is (= 3 (:components rgb)))
      (is (= :device-rgb (:color-space rgb))))))

(deftest color-types-map-to-color-spaces
  (is (= :device-gray (:color-space (png/parse (fixture "gray.png")))))
  (is (= :device-rgb  (:color-space (png/parse (fixture "rgb.png")))))
  (is (= :indexed     (:color-space (png/parse (fixture "indexed.png"))))))

(deftest indexed-collects-palette
  (let [idx (png/parse (fixture "indexed.png"))]
    (is (some? (:palette idx)))
    (is (= 1 (:components idx)))
    ;; PLTE is 3 bytes per entry
    (is (zero? (mod (b/length (:palette idx)) 3)))))

(deftest idat-is-concatenated-bytes
  (let [rgb (png/parse (fixture "rgb.png"))]
    (is (pos? (b/length (:idat rgb))))))

(deftest ihdr-round-trips-through-memory
  (testing "The IHDR record emits back to the same 13 bytes it parsed"
    (let [src  (fixture "rgb.png")
          ;; IHDR data begins after the 8-byte signature + length(4) + type(4)
          ihdr (mem/parse-record src png/ihdr-fields {:offset 16})
          out  (mem/emit-record ihdr png/ihdr-fields)]
      (is (= (b/unsigned-vec (b/slice src 16 29)) (b/unsigned-vec out))))))

(deftest alpha-splits-into-color-and-smask
  (testing "An RGBA PNG builds a color image plus a grayscale /SMask"
    (let [{:keys [image smask]}
          (xobject/image-object (assoc (png/parse (fixture "rgba.png")) :format :png))]
      (is (= :device-rgb (get-in image [:dict :color-space])))
      (is (= :device-gray (get-in smask [:dict :color-space])))
      (is (= 1 (get-in smask [:dict :decode-parms :colors]))))))

(deftest interlaced-throws
  (testing "An interlaced PNG is refused (Adam7 can't pass through)"
    (let [src (b/unsigned-vec (fixture "rgb.png"))
          ;; interlace is the 13th IHDR data byte: sig(8)+len(4)+type(4)+12
          laced (b/from-unsigned (assoc (vec src) 28 1))]
      (is (thrown? clojure.lang.ExceptionInfo (png/parse laced))))))

(deftest rejects-non-png
  (is (thrown? clojure.lang.ExceptionInfo (png/parse (fixture "rgb.jpg")))))
