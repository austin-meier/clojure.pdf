(ns pdf.context.text.font
  (:require
   [pdf.context.text.sfnt :as sfnt]
   [pdf.bytes :as b]
   [pdf.utils.io :refer [file->bytes]]))

(defn detect-font-format
  "Font program format from the file extension: :truetype, :cff, or :type1."
  [path]
  (let [ext (str (second (re-find #"\.([^.]+)$" path)))]
    (cond
      (#{"ttf" "ttc"} ext) :truetype
      (#{"otf"} ext)       :cff
      (#{"pfb"} ext)       :type1
      :else                :truetype)))

(defn sniff-font-format
  "Font program format from the sfnt magic: OTTO is CFF; everything else
   (0x00010000, 'true', 'ttcf') falls to :truetype and lets the sfnt parser
   reject what it can't read."
  [src]
  (if (= "OTTO" (b/tag src 0)) :cff :truetype))

(defn new-font
  "Load a TrueType font into a font context: the raw program bytes plus the
   parsed metrics and cmap. `src` is a file path or the program bytes (the
   bytes form is the browser path, where there is no filesystem). Pages record
   which glyphs get used with it, and the real PDF objects (Type0/CIDFontType2
   over a subset program) are built from that usage at serialize time by
   pdf.context.pdf/embed-fonts."
  [src]
  (let [path?  (string? src)
        format (if path? (detect-font-format src) (sniff-font-format src))]
    (when-not (= :truetype format)
      (throw (ex-info "Only TrueType fonts are supported so far"
                      {:src (if path? src "<bytes>") :format format})))
    (let [src    (if path? (file->bytes src) src)
          parsed (sfnt/parse-font src)]
      {:type :font-context
       :src src
       ;; :sha1 hashes the program so serialize-time dedupe works across
       ;; separately loaded copies — `=` on the context can't, because byte
       ;; arrays only compare by identity (mirrors new-image).
       :sha1 (b/content-hash src)
       :sfnt parsed
       :cmap (sfnt/parse-cmap src (get-in parsed [:tables "cmap" :offset]))})))
