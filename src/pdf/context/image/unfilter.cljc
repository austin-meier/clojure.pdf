(ns pdf.context.image.unfilter
  "PNG scanline unfiltering and channel splitting,
   needed because PDF has no pass-through for soft masks:
   an alpha PNG interleaves alpha with color per pixel. PDF wants color in
   the image and alpha in a separate grayscale /SMask.

   PNG prefixes every scanline with a filter-type byte (0-4) that predicts each
   sample from its left (a), up (b) and up-left (c) neighbors. `unfilter`
   reconstructs the raw samples; `split-color-alpha` peels the alpha channel off
   and refilters both planes with filter 0."
  (:require
   [pdf.bytes :as b]))

(defn- mag [x] (if (neg? x) (- x) x))

(defn paeth
  "The PNG Paeth predictor whichever of a, b, c is closest to p = a + b - c,
   with ties broken toward a then b."
  [a b c]
  (let [p  (- (+ a b) c)
        pa (mag (- p a))
        pb (mag (- p b))
        pc (mag (- p c))]
    (cond
      (and (<= pa pb) (<= pa pc)) a
      (<= pb pc)                  b
      :else                       c)))

(defn- predict
  "The value the filter type `ft` adds back to a filtered byte."
  [ft a b c]
  (case (int ft)
    0 0
    1 a
    2 b
    3 (quot (+ a b) 2)
    4 (paeth a b c)
    (throw (ex-info "Unknown PNG filter type" {:filter ft}))))

(defn unfilter
  "Reconstruct raw scanline samples from PNG-filtered inflated `data` (a vector
   0-255). Returns height*stride samples with the per-row filter bytes
   removed. `bpp` (bytes per pixel, min 1) sets the left/up-left neighbor
   offset; `stride` is bytes per scanline."
  [data width height channels bytes-per-sample]
  (let [bpp    (max 1 (* channels bytes-per-sample))
        stride (* width channels bytes-per-sample)]
    (loop [r 0, in 0, out (transient [])]
      (if (= r height)
        (persistent! out)
        (let [ft (nth data in)]
          (recur
           (inc r)
           (+ in 1 stride)
           (loop [i 0, out out]
             (if (= i stride)
               out
               (let [oi (+ (* r stride) i)
                     a  (if (>= i bpp) (nth out (- oi bpp)) 0)
                     b  (if (pos? r) (nth out (- oi stride)) 0)
                     c  (if (and (pos? r) (>= i bpp)) (nth out (- oi stride bpp)) 0)
                     recon (bit-and (+ (nth data (+ in 1 i)) (predict ft a b c)) 0xFF)]
                 (recur (inc i) (conj! out recon)))))))))))

(defn split-color-alpha
  "Peel the alpha channel (the last of `channels`) off reconstructed `samples`
   into two planes, each refiltered with filter 0 (a leading 0 byte per row).
   Returns {:color <byte source> :alpha <byte source>}."
  [samples width height channels bytes-per-sample]
  (let [color-ch (dec channels)
        stride   (* width channels bytes-per-sample)]
    (loop [r 0, color (transient []), alpha (transient [])]
      (if (= r height)
        {:color (b/from-unsigned (persistent! color))
         :alpha (b/from-unsigned (persistent! alpha))}
        (let [base (* r stride)
              [color alpha]
              (loop [x 0, color (conj! color 0), alpha (conj! alpha 0)]
                (if (= x width)
                  [color alpha]
                  (let [pbase (+ base (* x channels bytes-per-sample))
                        alpha-at (+ pbase (* color-ch bytes-per-sample))
                        color (reduce (fn [c k] (conj! c (nth samples (+ pbase k))))
                                      color (range (* color-ch bytes-per-sample)))
                        alpha (reduce (fn [a k] (conj! a (nth samples (+ alpha-at k))))
                                      alpha (range bytes-per-sample))]
                    (recur (inc x) color alpha))))]
          (recur (inc r) color alpha))))))
