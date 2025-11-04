(ns pdf.context.unfilter-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.image.unfilter :as uf]
   [pdf.bytes :as b]))

(deftest paeth-predictor-vectors
  (testing "Ties break toward a, then b (spec examples)"
    (is (= 10 (uf/paeth 10 20 30)))   ; p=0: pa=10 < pb=20,pc=30 -> a
    (is (= 10 (uf/paeth 0 10 0)))     ; p=10: pb=0 wins -> b
    (is (= 30 (uf/paeth 40 20 30)))   ; p=30: pc=0 wins -> c
    (is (= 200 (uf/paeth 200 5 5)))))

;; Forward PNG filter: the encoder inverse of unfilter. Predicts each byte from
;; the raw (reconstructed) neighbours, so unfilter must recover the raw samples.
(defn- encode
  [raw width height channels bps filter-types]
  (let [bpp    (max 1 (* channels bps))
        stride (* width channels bps)]
    (vec
     (mapcat
      (fn [r]
        (let [ft (nth filter-types r)]
          (cons ft
                (map (fn [i]
                       (let [oi (+ (* r stride) i)
                             a  (if (>= i bpp) (nth raw (- oi bpp)) 0)
                             bb (if (pos? r) (nth raw (- oi stride)) 0)
                             c  (if (and (pos? r) (>= i bpp)) (nth raw (- oi stride bpp)) 0)
                             pred (case ft 0 0 1 a 2 bb 3 (quot (+ a bb) 2) 4 (uf/paeth a bb c))]
                         (bit-and (- (nth raw oi) pred) 0xFF)))
                     (range stride)))))
      (range height)))))

(deftest fixed-vector-sub-filter
  (testing "A Sub-filtered (type 1) grayscale row reconstructs to a ramp"
    ;; raw ramp [10 20 30 40], filtered as deltas [10 10 10 10] with filter 1
    (let [encoded [1 10 10 10 10]]
      (is (= [10 20 30 40] (uf/unfilter encoded 4 1 1 1))))))

(deftest fixed-vector-up-filter
  (testing "An Up-filtered (type 2) second row adds the row above"
    (let [encoded [0 5 5   ; row 0 raw: 5 5
                   2 3 4]] ; row 1: 3+5=8, 4+5=9
      (is (= [5 5 8 9] (uf/unfilter encoded 2 2 1 1))))))

(deftest round-trips-every-filter-type
  (testing "encode then unfilter is identity for each filter type, RGBA 8-bit"
    (let [width 5, height 4, channels 4, bps 1
          n (* width height channels bps)
          raw (mapv #(mod (* 37 (inc %)) 256) (range n))]
      (doseq [ft [0 1 2 3 4]]
        (let [encoded (encode raw width height channels bps (repeat height ft))]
          (is (= raw (uf/unfilter encoded width height channels bps))
              (str "filter type " ft))))
      (testing "mixed per-row filter types"
        (let [encoded (encode raw width height channels bps [0 1 2 4])]
          (is (= raw (uf/unfilter encoded width height channels bps))))))))

(deftest split-peels-the-alpha-channel
  (testing "The last channel becomes the alpha plane; a 0 filter byte leads each row"
    ;; 2x1 RGBA: pixel0 = R1 G2 B3 A4, pixel1 = R5 G6 B7 A8
    (let [samples [1 2 3 4 5 6 7 8]
          {:keys [color alpha]} (uf/split-color-alpha samples 2 1 4 1)]
      (is (= [0 1 2 3 5 6 7] (b/unsigned-vec color)))   ; filter byte + RGB per pixel
      (is (= [0 4 8] (b/unsigned-vec alpha))))))         ; filter byte + alpha per pixel
