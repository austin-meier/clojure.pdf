(ns pdf.context.image.jpeg
  "Portable JPEG header readeer.
   A JPEG file is a valid /DCTDecode stream payload verbatim, nothing here
   decodes the entropy stream. The scan walks the marker segments from the SOI
   only far enough to read the frame header (SOF): image dimensions, precision,
   and component count. It also notes an Adobe APP14 marker, which flags the
   inverted-CMYK convention 4-component files that Adobe loves to create

   JPEG markers are big-endian: 0xFF, a marker code, then (for most) a u16
   length that includes its own two bytes. RSTn/SOI/EOI/TEM carry no length."
  (:require
   [pdf.bytes :as b]))

(def ^:private soi 0xD8)
(def ^:private sos 0xDA)
(def ^:private app14 0xEE)

(defn- sof?
  "SOF markers carry the frame header. 0xC0-0xCF are frame markers except DHT
   (C4), JPG (C8) and DAC (CC), which are not."
  [m]
  (and (<= 0xC0 m 0xCF) (not (#{0xC4 0xC8 0xCC} m))))

(defn- standalone?
  "Markers with no length field: TEM and the restart markers RST0-RST7."
  [m]
  (or (= m 0x01) (<= 0xD0 m 0xD7)))

(defn- components->color-space
  [n]
  (case n
    1 :device-gray
    3 :device-rgb
    4 :device-cmyk
    (throw (ex-info "Unsupported JPEG component count" {:components n}))))

(defn parse
  "Scan a JPEG byte source to an image header map: :width :height :bits
   :components :color-space :progressive, plus :adobe? when an APP14 marker is
   present (so you know to invert CMYK). Throws if the SOI magic or an SOF is missing."
  [src]
  (when-not (and (= 0xFF (b/u8 src 0)) (= soi (b/u8 src 1)))
    (throw (ex-info "Not a JPEG (missing SOI marker)" {})))
  (loop [p 2 adobe? false]
    (when (>= (inc p) (b/length src))
      (throw (ex-info "JPEG ended before a frame header (SOF)" {})))
    (if (not= 0xFF (b/u8 src p))
      ;; Skip stray fill bytes between segments.
      (recur (inc p) adobe?)
      (let [m (b/u8 src (inc p))]
        (cond
          (standalone? m) (recur (+ p 2) adobe?)
          (= m sos)       (throw (ex-info "JPEG scan reached before a frame header (SOF)" {}))
          (sof? m)        (let [components (b/u8 src (+ p 9))]
                            {:width       (b/u16 src (+ p 7))
                             :height      (b/u16 src (+ p 5))
                             :bits        (b/u8 src (+ p 4))
                             :components  components
                             :color-space (components->color-space components)
                             :progressive (= m 0xC2)
                             :adobe?      adobe?})
          :else           (let [len (b/u16 src (+ p 2))]
                            (recur (+ p 2 len) (or adobe? (= m app14)))))))))
