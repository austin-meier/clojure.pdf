(ns pdf.context.text.cid
  "CID font machinery: everything between a font context and the PDF objects
   that embed it. Text shows through a Type0 font with Identity-H encoding over
   a CIDFontType2 descendant, so a shown string is the glyph ids themselves
   (CID = GID, /CIDToGIDMap /Identity) — exactly what the gid-stable subsetter
   produces. `encode-text` runs at authoring time (the content stream needs the
   gids); `font-object` runs at serialize time over the usage pages recorded
   (see pdf.context.pdf/embed-fonts)."
  (:require
   [pdf.context.stream :refer [bytes->stream string->stream]]
   [pdf.context.text.sfnt :as sfnt]
   [pdf.context.text.subset :as subset]
   [pdf.serialize :refer [PdfSerializable]]
   [pdf.bytes :as b]
   [pdf.utils.string :as pstr]))

;; Under Identity-H a shown string is big-endian 2-byte CIDs, which only a hex
;; string can carry safely through the syntax layer.
(defrecord GlyphString [gids])

(extend-protocol PdfSerializable
  GlyphString
  (to-pdf [{:keys [gids]}]
    (str "<" (apply str (map #(pstr/hex-upper % 4) gids)) ">")))

(defn encode-text
  "Encode `text` for Identity-H showing with a font context: the gid per
   codepoint (0/notdef when unmapped) plus the gid->codepoint pairs ToUnicode
   needs (notdef carries no mapping)."
  [font-ctx text]
  (let [cps (subset/codepoints text)
        gids (mapv #(or (sfnt/char->gid (:cmap font-ctx) %) 0) cps)]
    {:gids gids
     :gid->cp (into {}
                    (filter (fn [[gid _]] (pos? gid)))
                    (map vector gids cps))}))

;; Glyph-space values scale to PDF text space as if every font had 1000 units
;; per em (the unitsPerEm normalization the roadmap called out).
(defn- scale-to-em [units-per-em v]
  (Math/round (* v (/ 1000.0 units-per-em))))

(defn widths-array
  "The CIDFontType2 /W array for the used gids: runs of consecutive gids with
   their advance widths in 1000-unit text space, `[start [w0 w1 ...] ...]`."
  [{:keys [advance-widths units-per-em]} gids]
  (let [runs (reduce (fn [runs gid]
                       (if (and (seq runs) (= gid (inc (peek (peek runs)))))
                         (update runs (dec (count runs)) conj gid)
                         (conj runs [gid])))
                     []
                     (sort gids))]
    (into []
          (mapcat (fn [run]
                    [(first run)
                     (mapv #(scale-to-em units-per-em (nth advance-widths %)) run)]))
          runs)))

(defn subset-tag
  "Deterministic six-uppercase-letter subset prefix (spec 9.6.4) derived from
   the used gid set, so the same subset names the same across runs."
  [gids]
  (let [n (bit-and (hash (vec (sort gids))) 0x7fffffff)]
    (apply str (map #(char (+ 65 (mod % 26)))
                    (take 6 (iterate #(quot % 26) n))))))

(defn- utf16be-hex [cp]
  (if (<= cp 0xFFFF)
    (pstr/hex-upper cp 4)
    (let [u (- cp 0x10000)]
      (str (pstr/hex-upper (+ 0xD800 (quot u 0x400)) 4)
           (pstr/hex-upper (+ 0xDC00 (mod u 0x400)) 4)))))

(def ^:private to-unicode-header
  (str "/CIDInit /ProcSet findresource begin\n"
       "12 dict begin\n"
       "begincmap\n"
       "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
       "/CMapName /Adobe-Identity-UCS def\n"
       "/CMapType 2 def\n"
       "1 begincodespacerange\n"
       "<0000> <FFFF>\n"
       "endcodespacerange\n"))

(def ^:private to-unicode-footer
  (str "endcmap\n"
       "CMapName currentdict /CMap defineresource pop\n"
       "end\n"
       "end"))

(defn to-unicode-stream
  "The /ToUnicode CMap stream mapping each used gid (the CID under Identity-H)
   back to its source codepoint, in bfchar blocks of at most 100 as the spec
   requires."
  [gid->cp]
  (string->stream
   (str to-unicode-header
        (apply str
               (map (fn [block]
                      (str (count block) " beginbfchar\n"
                           (apply str (map (fn [[gid cp]]
                                             (str "<" (pstr/hex-upper gid 4) "> <"
                                                  (utf16be-hex cp) ">\n"))
                                           block))
                           "endbfchar\n"))
                    (partition-all 100 (sort gid->cp))))
        to-unicode-footer)))

(defn font-object
  "The complete Type0 font object for a font context and its used glyphs
   ({gid -> codepoint}): Identity-H over a CIDFontType2 descendant with a
   subset font program, /W widths and /ToUnicode. Everything nested is plain
   data; the resolver hoists the dicts and streams into indirect objects."
  [font-ctx gid->cp]
  (let [{:keys [src sfnt]} font-ctx
        {:keys [units-per-em ascent descent bbox italic-angle base-font]} sfnt
        scale #(scale-to-em units-per-em %)
        subset-src (subset/subset-font src (vals gid->cp))
        font-name (symbol (str (subset-tag (keys gid->cp)) "+" (or base-font "Font")))]
    {:type :font
     :subtype :type0
     :base-font font-name
     :encoding :identity-h
     :descendant-fonts
     [{:type :font
       :subtype :cid-font-type2
       :base-font font-name
       :cid-system-info {:registry "Adobe" :ordering "Identity" :supplement 0}
       :font-descriptor {:type :font-descriptor
                         :font-name font-name
                         :flags 4
                         :font-b-box (mapv scale bbox)
                         :italic-angle italic-angle
                         :ascent (scale ascent)
                         :descent (scale descent)
                         :cap-height (scale ascent)
                         :stem-v 80
                         :font-file-2 (bytes->stream subset-src
                                                     {:length1 (b/length subset-src)})}
       :dw 1000
       :w (widths-array sfnt (keys gid->cp))
       :cid-to-gid-map :identity}]
     :to-unicode (to-unicode-stream gid->cp)}))
