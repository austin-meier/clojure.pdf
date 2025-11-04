(ns pdf.context.image
  "The image authoring API, mirroring pdf.context.text.font / pdf.context.text.core:
   `new-image` loads a file into an image *context* (raw bytes + parsed header,
   plain data) at the I/O edge, and `with-image` places it on a page. Pages
   reference the context by value in :resources :xobject; the real Image XObject
   is built at serialize time by pdf.context.pdf/embed-images, exactly as fonts
   embed from usage. Reusing one `new-image` value across pages embeds once."
  (:require
   [pdf.context.image.jpeg :as jpeg]
   [pdf.context.image.png :as png]
   [pdf.context.page :refer [add-or-get-resource]]
   [pdf.bytes :as b]
   [pdf.utils.dimension :refer [->points]]
   [pdf.utils.io :refer [file->bytes]]))

(defn- detect-format
  "Image format from the magic bytes (never the extension): JPEG starts FFD8,
   PNG with the 0x89 'P' 'N' 'G' signature."
  [src]
  (cond
    (and (= 0xFF (b/u8 src 0)) (= 0xD8 (b/u8 src 1))) :jpeg
    (and (= 0x89 (b/u8 src 0)) (= 0x50 (b/u8 src 1))) :png
    :else (throw (ex-info "Unrecognized image format (expected JPEG or PNG)" {}))))

(defn new-image
  "Load an image into an image context: the raw bytes plus the parsed header
   (dimensions, bit depth, components, color space). `src` is a file path or
   the image bytes (the bytes form is the browser path, where there is no
   filesystem). JPEG and PNG only; throws on anything else. :sha1 hashes the
   content at the loading edge so serialize-time dedupe works across
   separately loaded copies — `=` on the context can't, because byte arrays
   only compare by identity."
  [src]
  (let [src    (if (string? src) (file->bytes src) src)
        format (detect-format src)]
    (assoc (case format
             :jpeg (jpeg/parse src)
             :png  (png/parse src))
           :type   :image-context
           :format format
           :bytes  src
           :sha1   (b/content-hash src))))

(defn image-size
  "Draw size in points for `img` given optional :width/:height (points or
   Dimensions). Both given uses them; one given scales the other by the aspect
   ratio; neither uses intrinsic pixels (points-in, points-out — the 96dpi rule
   is the layout engine's, not manual placement's)."
  [{:keys [width height]} req-width req-height]
  (let [w (->points req-width)
        h (->points req-height)]
    (cond
      (and w h) [w h]
      w         [w (* w (/ height width))]
      h         [(* h (/ width height)) h]
      :else     [width height])))

(defn image->ops
  "The four ops that draw the XObject `res-key` at (x, y) scaled to w x h. Image
   space is the unit square, so the CTM carries the size; x, y is the
   bottom-left in PDF space."
  [res-key w h x y]
  [[:save-state]
   [:concat-matrix w 0 0 h x y]
   [:draw-xobject (symbol res-key)]
   [:restore-state]])

(defn with-image
  "Place `img` on the page with its bottom-left at (x, y). x/y and the :width/
   :height opts take numbers (points) or Dimensions. The image is added to the
   page's :xobject resources and referenced by value; embed-images builds the
   XObject and compile-content-ops the content stream at serialize time."
  ([page-ctx img x y] (with-image page-ctx img x y {}))
  ([page-ctx img x y {:keys [width height]}]
   (let [[w h] (image-size img width height)
         [page-ctx res-key] (add-or-get-resource page-ctx :xobject img)]
     (update page-ctx :contents (fnil conj [])
             (image->ops res-key w h (->points x) (->points y))))))
