(ns pdf.context.image.png
  "Portable PNG chunk walker.
   A PNG's concatenated IDAT payload is a valid /FlateDecode stream payload, and
   PDF's FlateDecode understands PNG's own scanline predictors via /DecodeParms
   {/Predictor 15 ...} so non-alpha PNGs pass through with no decompression:
   parse the IHDR, concatenate the IDAT chunks, collect PLTE/tRNS

   The 8-byte signature is followed by chunks of `length u32, type tag, data,
   crc u32`."
  (:require
   [pdf.bytes :as b]
   [pdf.bytes.memory :as mem]))

(def ^:private signature [137 80 78 71 13 10 26 10])

(def ihdr-fields
  [[:width       :u32]
   [:height      :u32]
   [:bit-depth   :u8]
   [:color-type  :u8]
   [:compression :u8]
   [:filter      :u8]
   [:interlace   :u8]])

(def ^:private color-type->color
  {0 {:color-space :device-gray :components 1}
   2 {:color-space :device-rgb  :components 3}
   3 {:color-space :indexed     :components 1}
   4 {:color-space :device-gray :components 1}
   6 {:color-space :device-rgb  :components 3}})

(defn- signature?
  [src]
  (every? (fn [i] (= (get signature i) (b/u8 src i)))
          (range (count signature))))

(defn- walk-chunks
  "Fold every chunk after the signature into an accumulator: IHDR fields, the
   concatenated IDAT payloads, and PLTE/tRNS byte ranges when present."
  [src]
  (loop [p 8 acc {:idat []}]
    (if (>= p (b/length src))
      acc
      (let [len  (b/u32 src p)
            tag  (b/tag src (+ p 4))
            data (+ p 8)
            next (+ data len 4)]
        (case tag
          "IHDR" (recur next (assoc acc :ihdr (mem/parse-record src ihdr-fields {:offset data})))
          "IDAT" (recur next (update acc :idat conj (b/slice src data (+ data len))))
          "PLTE" (recur next (assoc acc :palette (b/slice src data (+ data len))))
          "tRNS" (recur next (assoc acc :trns (b/slice src data (+ data len))))
          "IEND" acc
          (recur next acc))))))

(defn parse
  "Parse a PNG byte source to an image header map: :width :height :bits
   :color-type :components :color-space, plus :idat (concatenated IDAT bytes)
   and :palette / :trns when present. Interlaced PNGs throw because PDF can't express
   Adam7 without decoding, so re-export without interlacing until I add decoding support here."
  [src]
  (when-not (signature? src)
    (throw (ex-info "Not a PNG (bad signature)" {})))
  (let [{:keys [idat] :as walked} (walk-chunks src)
        {:keys [width height bit-depth color-type interlace]} (:ihdr walked)]
    (when (pos? interlace)
      (throw (ex-info "Interlaced (Adam7) PNG is not supported; re-export without interlacing"
                      {:interlace interlace})))
    (let [{:keys [color-space components]} (color-type->color color-type)]
      (when-not color-space
        (throw (ex-info "Unsupported PNG color type" {:color-type color-type})))
      (cond-> {:width       width
               :height      height
               :bits        bit-depth
               :color-type  color-type
               :components  components
               :color-space color-space
               :idat        (b/concat-bytes idat)}
        (:palette walked) (assoc :palette (:palette walked))
        (:trns walked)    (assoc :trns (:trns walked))))))
