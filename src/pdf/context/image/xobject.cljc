(ns pdf.context.image.xobject
  "Image context -> PDF Image XObject, as plain data through the existing
   stream/name machinery."
  (:require
   [pdf.context.image.unfilter :as unfilter]
   [pdf.context.stream :refer [->PdfStream]]
   [pdf.bytes :as b]
   [pdf.utils.flate :as flate]))

(defn- indexed-color-space
  "The /Indexed color space array for a palette-based PNG: base space, hival,
   and the raw PLTE bytes as a Latin-1 literal string (chars 0-255 -> bytes)."
  [palette]
  (let [s (b/ascii palette 0 (b/length palette))]
    [:indexed :device-rgb (dec (quot (b/length palette) 3)) s]))

(defn- jpeg-object
  [{:keys [bytes width height bits color-space components adobe?]}]
  (->PdfStream
   (cond-> {:type :xobject :subtype :image
            :width width :height height
            :color-space color-space
            :bits-per-component bits
            :filter :dct-decode}
     ;; Adobe 4-component JPEGs store CMYK inverted; the APP14 marker signals it.
     (and adobe? (= 4 components)) (assoc :decode [1 0 1 0 1 0 1 0]))
   bytes))

(defn- flate-image
  "A FlateDecode Image XObject over PNG-predictor scanlines."
  [width height color-space bits colors payload]
  (->PdfStream
   {:type :xobject :subtype :image
    :width width :height height
    :color-space color-space
    :bits-per-component bits
    :filter :flate-decode
    :decode-parms {:predictor 15
                   :colors colors
                   :bits-per-component bits
                   :columns width}}
   payload))

(defn- png-passthrough-object
  "Non-alpha PNG: the IDAT is a valid FlateDecode payload verbatim."
  [{:keys [idat width height bits color-space components palette]}]
  (flate-image width height
               (if (= :indexed color-space) (indexed-color-space palette) color-space)
               bits components idat))

(defn- png-alpha-object
  "Alpha PNG (color types 4/6): inflate the IDAT, unfilter the scanlines, split
   the alpha channel into a grayscale /SMask, and deflate both planes. Returns
   {:image <color stream> :smask <alpha stream>}; embed-images wires the ref."
  [{:keys [idat width height bits color-type color-space]}]
  (let [channels (if (= 4 color-type) 2 4)
        bps      (quot bits 8)
        samples  (unfilter/unfilter (b/unsigned-vec (flate/inflate idat))
                                    width height channels bps)
        {:keys [color alpha]} (unfilter/split-color-alpha samples width height channels bps)]
    {:image (flate-image width height color-space bits (dec channels) (flate/deflate color))
     :smask (flate-image width height :device-gray bits 1 (flate/deflate alpha))}))

(defn- png-object
  [{:keys [color-type] :as img}]
  (if (#{4 6} color-type)
    (png-alpha-object img)
    (png-passthrough-object img)))

(defn image-object
  "Build the embedded object(s) for an image context. For plain images that's a
   single PdfStream; alpha PNGs (I3) return {:image <stream> :smask <stream>}."
  [{:keys [format] :as img}]
  (case format
    :jpeg (jpeg-object img)
    :png  (png-object img)
    (throw (ex-info "Unknown image format" {:format format}))))
