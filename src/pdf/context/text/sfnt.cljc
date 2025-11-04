(ns pdf.context.text.sfnt
  "Portable SFNT (TrueType/OpenType) table parser, so it runs on both the JVM
   and ClojureScript. Each table's wire format is a declarative pdf.bytes.memory
   layout — the same specs the emit side will drive when subsetting rebuilds
   tables — and the functions below reshape parsed fields into the metrics
   embedding needs (head, maxp, hhea, post, hmtx, name). Glyph-level tables
   (cmap, loca, glyf) and subsetting build on top of the same directory.

   Replaces the JVM-only java.awt.Font name lookup and the ByteBuffer parser."
  (:require
   [pdf.bytes :as b]
   [pdf.bytes.memory :as mem]))

;; ---------------------------------------------------------------------------
;; Container: TTC detection + table directory
;; ---------------------------------------------------------------------------

(defn face-offset
  "Byte offset of the sfnt to parse. 0 for a plain TrueType/OpenType file; for a
   TrueType Collection ('ttcf') the offset of the first face."
  [src]
  (if (= "ttcf" (b/tag src 0))
    ;; ttcf(4) version(4) numFonts(4) offsets[numFonts]... first face offset at 12
    (b/u32 src 12)
    0))

(defn embed-bytes
  "The sfnt bytes to embed in the PDF. The whole file for a single-face font;
   for a TTC, the first face's byte range (a naive slice, superseded once
   subsetting rebuilds a standalone sfnt)."
  [src]
  (if (= "ttcf" (b/tag src 0))
    (let [num-fonts (b/u32 src 8)
          start (b/u32 src 12)
          end (if (> num-fonts 1) (b/u32 src 16) (b/length src))]
      (b/slice src start end))
    src))

(def directory-layout
  "The offset table and the table directory records that follow it."
  [{:section :header
    :fields  [[:sfnt-version   :u32]
              [:num-tables     :u16]
              [:search-range   :u16]
              [:entry-selector :u16]
              [:range-shift    :u16]]}
   {:section :records
    :repeat  [:header :num-tables]
    :fields  [[:tag      :tag]
              [:checksum :u32]
              [:offset   :u32]
              [:length   :u32]]}])

(defn table-directory
  "Map of tag -> {:offset o :length l} for the sfnt whose offset table starts at
   `base`. Table offsets are absolute from the start of `src`."
  [src base]
  (reduce (fn [dir {:keys [tag offset length]}]
            (assoc dir tag {:offset offset :length length}))
          {}
          (:records (mem/parse src directory-layout {:offset base}))))

;; ---------------------------------------------------------------------------
;; Individual tables, each described by its layout record
;; ---------------------------------------------------------------------------

(def head-fields
  [[:version             :fixed]
   [:font-revision       :fixed]
   [:checksum-adjustment :u32]
   [:magic-number        :u32]
   [:flags               :u16]
   [:units-per-em        :u16]
   [:created             :i64]
   [:modified            :i64]
   [:x-min               :i16]
   [:y-min               :i16]
   [:x-max               :i16]
   [:y-max               :i16]
   [:mac-style           :u16]
   [:lowest-rec-ppem     :u16]
   [:font-direction-hint :i16]
   [:index-to-loc-format :i16]
   [:glyph-data-format   :i16]])

(defn parse-head [src off]
  (let [{:keys [units-per-em x-min y-min x-max y-max index-to-loc-format]}
        (mem/parse-record src head-fields {:offset off})]
    {:units-per-em        units-per-em
     :bbox                [x-min y-min x-max y-max]
     :index-to-loc-format index-to-loc-format}))

(def maxp-fields
  "The version 0.5/1.0 common prefix; the 1.0 tail is TrueType limit fields
   subsetting recomputes anyway."
  [[:version    :fixed]
   [:num-glyphs :u16]])

(defn parse-maxp [src off]
  (select-keys (mem/parse-record src maxp-fields {:offset off}) [:num-glyphs]))

(def hhea-fields
  [[:version                :fixed]
   [:ascent                 :i16]
   [:descent                :i16]
   [:line-gap               :i16]
   [:advance-width-max      :u16]
   [:min-left-side-bearing  :i16]
   [:min-right-side-bearing :i16]
   [:x-max-extent           :i16]
   [:caret-slope-rise       :i16]
   [:caret-slope-run        :i16]
   [:caret-offset           :i16]
   [:reserved               [:i16 4]]
   [:metric-data-format     :i16]
   [:num-h-metrics          :u16]])

(defn parse-hhea [src off]
  (select-keys (mem/parse-record src hhea-fields {:offset off})
               [:ascent :descent :line-gap :num-h-metrics]))

(def post-fields
  "The fixed header; version 2.0's glyph-name arrays are not layout-static."
  [[:version             :fixed]
   [:italic-angle        :fixed]
   [:underline-position  :i16]
   [:underline-thickness :i16]
   [:is-fixed-pitch      :u32]
   [:min-mem-type42      :u32]
   [:max-mem-type42      :u32]
   [:min-mem-type1       :u32]
   [:max-mem-type1       :u32]])

(defn parse-post [src off]
  (select-keys (mem/parse-record src post-fields {:offset off}) [:italic-angle]))

(def h-metric-fields
  [[:advance-width :u16]
   [:lsb           :i16]])

(defn parse-hmtx
  "Advance widths for every glyph, in font design units. The hmtx table stores
   `num-h-metrics` (advanceWidth, lsb) pairs followed by left-side-bearings only;
   glyphs past the metrics share the final advance width (monospace tail)."
  [src off num-h-metrics num-glyphs]
  (let [metrics (:metrics (mem/parse src [{:section :metrics
                                           :repeat  num-h-metrics
                                           :fields  h-metric-fields}]
                                     {:offset off}))
        advances (mapv :advance-width metrics)]
    (into advances (repeat (- num-glyphs num-h-metrics) (peek advances)))))

;; ---------------------------------------------------------------------------
;; cmap -> codepoint-to-gid mapping as plain data
;; ---------------------------------------------------------------------------

(def cmap-layout
  "The cmap header and its encoding records; each record points at a
   format-dispatched subtable."
  [{:section :header
    :fields  [[:version    :u16]
              [:num-tables :u16]]}
   {:section :encodings
    :repeat  [:header :num-tables]
    :fields  [[:platform-id     :u16]
              [:encoding-id     :u16]
              [:subtable-offset :u32]]}])

(def ^:private unicode-encoding-preference
  "[platform-id encoding-id] pairs, full-repertoire subtables before BMP-only."
  [[3 10] [0 4] [0 6] [3 1] [0 3] [0 2] [0 1] [0 0]])

(defn- format4-segments
  "Format 4 segments materialized as data: {:start :end :delta} for delta
   segments, {:start :end :gids [...]} where the segment indexes glyphIdArray."
  [src off]
  (let [seg-count (quot (b/u16 src (+ off 6)) 2)
        arrays (mem/parse-record src
                                 [[:end-codes     [:u16 seg-count]]
                                  [:reserved-pad  :u16]
                                  [:start-codes   [:u16 seg-count]]
                                  [:deltas        [:i16 seg-count]]
                                  [:range-offsets [:u16 seg-count]]]
                                 {:offset (+ off 14)})
        range-base (+ off 14 2 (* 6 seg-count))]
    (mapv (fn [i]
            (let [start (nth (:start-codes arrays) i)
                  end   (nth (:end-codes arrays) i)
                  delta (nth (:deltas arrays) i)
                  range-offset (nth (:range-offsets arrays) i)]
              (if (zero? range-offset)
                {:start start :end end :delta delta}
                ;; The offset indexes glyphIdArray relative to the offset's own
                ;; position in the range-offset array; resolve the whole span
                ;; here so the segment is position-independent data.
                {:start start :end end
                 :gids (mapv (fn [k]
                               (let [gid (b/u16 src (+ range-base (* 2 i) range-offset (* 2 k)))]
                                 (if (zero? gid) 0 (mod (+ gid delta) 0x10000))))
                             (range (inc (- end start))))})))
          (range seg-count))))

(def cmap-format12-layout
  [{:section :header
    :fields  [[:format     :u16]
              [:reserved   :u16]
              [:length     :u32]
              [:language   :u32]
              [:num-groups :u32]]}
   {:section :groups
    :repeat  [:header :num-groups]
    :fields  [[:start-char :u32]
              [:end-char   :u32]
              [:start-gid  :u32]]}])

(defn parse-cmap
  "The best Unicode subtable as data: {:format 4 :segments [...]} or
   {:format 12 :groups [...]}. Throws when the font has no Unicode subtable or
   only one in a format outside 4/12 (the two that cover real fonts)."
  [src off]
  (let [{:keys [encodings]} (mem/parse src cmap-layout {:offset off})
        by-ids (into {} (map (fn [e] [[(:platform-id e) (:encoding-id e)] e])) encodings)
        chosen (some by-ids unicode-encoding-preference)]
    (when-not chosen
      (throw (ex-info "No Unicode cmap subtable" {:encodings encodings})))
    (let [soff (+ off (:subtable-offset chosen))
          format (b/u16 src soff)]
      (case format
        4  {:format 4 :segments (format4-segments src soff)}
        12 {:format 12 :groups (:groups (mem/parse src cmap-format12-layout {:offset soff}))}
        (throw (ex-info "Unsupported cmap subtable format" {:format format}))))))

(defn char->gid
  "Glyph id for a Unicode codepoint through parsed cmap data, or nil when
   unmapped (gid 0 is the missing glyph, never returned)."
  [{:keys [format segments groups]} cp]
  (case format
    4  (some (fn [{:keys [start end delta gids]}]
               (when (<= start cp end)
                 (let [gid (if gids
                             (nth gids (- cp start))
                             (mod (+ cp delta) 0x10000))]
                   (when (pos? gid) gid))))
             segments)
    12 (some (fn [{:keys [start-char end-char start-gid]}]
               (when (<= start-char cp end-char)
                 (let [gid (+ start-gid (- cp start-char))]
                   (when (pos? gid) gid))))
             groups)))

;; ---------------------------------------------------------------------------
;; loca + glyf -> glyph spans and composite closure
;; ---------------------------------------------------------------------------

(defn parse-loca
  "Byte offsets of each glyph within glyf: num-glyphs+1 monotonic values, so
   glyph `gid` spans [loca[gid], loca[gid+1]). Short format (0) stores
   offset/2 as u16; long format (1) stores u32."
  [src off index-to-loc-format num-glyphs]
  (let [short? (zero? index-to-loc-format)
        {:keys [offsets]} (mem/parse-record src
                                            [[:offsets [(if short? :u16 :u32) (inc num-glyphs)]]]
                                            {:offset off})]
    (if short? (mapv #(* 2 %) offsets) offsets)))

(defn glyph-components
  "Gids referenced by the glyph whose glyf bytes span [start, end): its
   component records when composite (negative contour count), else empty."
  [src glyf-off start end]
  (if (or (= start end)
          (>= (b/i16 src (+ glyf-off start)) 0))
    []
    ;; Component records follow the 10-byte glyph header: flags, glyphIndex,
    ;; two args (bytes, or words when flag 0x0001), then an optional transform
    ;; (2/4/8 bytes for scale / x-y scale / 2x2). Flag 0x0020 chains records.
    (loop [p (+ glyf-off start 10)
           gids []]
      (let [flags (b/u16 src p)
            gids (conj gids (b/u16 src (+ p 2)))
            next-p (+ p 4
                      (if (pos? (bit-and flags 0x0001)) 4 2)
                      (cond
                        (pos? (bit-and flags 0x0008)) 2
                        (pos? (bit-and flags 0x0040)) 4
                        (pos? (bit-and flags 0x0080)) 8
                        :else 0))]
        (if (pos? (bit-and flags 0x0020))
          (recur next-p gids)
          gids)))))

(defn glyph-closure
  "`gids` closed over composite component references, always including gid 0
   (the missing glyph)."
  [src glyf-off loca gids]
  (loop [pending (conj (set gids) 0)
         closed #{}]
    (if-let [gid (first pending)]
      (let [components (glyph-components src glyf-off (nth loca gid) (nth loca (inc gid)))]
        (recur (into (disj pending gid) (remove (conj closed gid)) components)
               (conj closed gid)))
      closed)))

;; ---------------------------------------------------------------------------
;; name table -> PostScript name for /BaseFont
;; ---------------------------------------------------------------------------

(def name-layout
  "The name table header and its records; the string storage they point into is
   offset-driven and decoded separately."
  [{:section :header
    :fields  [[:format        :u16]
              [:count         :u16]
              [:string-offset :u16]]}
   {:section :records
    :repeat  [:header :count]
    :fields  [[:platform-id :u16]
              [:encoding-id :u16]
              [:language-id :u16]
              [:name-id     :u16]
              [:length      :u16]
              [:offset      :u16]]}])

(defn- name-records [src off]
  (let [{:keys [header records]} (mem/parse src name-layout {:offset off})
        storage (+ off (:string-offset header))]
    (mapv #(assoc % :string-off (+ storage (:offset %))) records)))

(defn- decode-name [src {:keys [platform-id length string-off]}]
  ;; Windows (platform 3) strings are UTF-16BE; Mac (platform 1) are single-byte.
  (if (= platform-id 3)
    (b/utf16-be src string-off length)
    (b/ascii src string-off length)))

(defn parse-name
  "The PostScript name (nameID 6), preferring the Windows record, for /BaseFont."
  [src off]
  (let [sixes (filter #(= 6 (:name-id %)) (name-records src off))
        record (or (first (filter #(= 3 (:platform-id %)) sixes))
                   (first sixes))]
    (when record (decode-name src record))))

;; ---------------------------------------------------------------------------
;; Top-level parse
;; ---------------------------------------------------------------------------

(defn parse-font
  "Parse the tables needed for embedding + metrics from a font byte source.
   Returns the table directory plus flattened metrics and the base font name."
  [src]
  (let [base (face-offset src)
        dir  (table-directory src base)
        off  (fn [tag] (get-in dir [tag :offset]))
        head (parse-head src (off "head"))
        maxp (parse-maxp src (off "maxp"))
        hhea (parse-hhea src (off "hhea"))]
    (merge head maxp hhea
           {:tables dir
            :base-font (some->> (off "name") (parse-name src))
            :italic-angle (if-let [o (off "post")] (:italic-angle (parse-post src o)) 0.0)
            :advance-widths (parse-hmtx src (off "hmtx")
                                        (:num-h-metrics hhea)
                                        (:num-glyphs maxp))})))
