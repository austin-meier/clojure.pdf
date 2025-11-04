(ns pdf.context.text.subset
  "TrueType subsetting: rebuild a standalone sfnt containing only the glyphs a
   document uses. Glyph ids keep their original values — unused glyphs become
   zero-length — so the CID side (Identity-H, CIDToGIDMap /Identity) can treat
   CID = GID with no renumbering, and composite glyphs need no component
   rewriting. glyf and loca are rebuilt (loca always long format), head is
   re-emitted through its layout with the new loca format and a recomputed
   checkSumAdjustment, and the other required tables (hhea maxp hmtx cvt fpgm
   prep) are copied verbatim. name/post/OS-2/cmap are dropped: the PDF layer
   carries naming, widths, and Unicode mapping (phase C)."
  (:require
   [pdf.context.text.sfnt :as sfnt]
   [pdf.bytes :as b]
   [pdf.bytes.memory :as mem]))

(defn codepoints
  "Unicode codepoints of a string, surrogate pairs combined."
  [s]
  #?(:clj  (let [n (.length ^String s)]
             (loop [i 0 cps []]
               (if (< i n)
                 (let [cp (.codePointAt ^String s i)]
                   (recur (+ i (Character/charCount cp)) (conj cps cp)))
                 cps)))
     :cljs (vec (js/Array.from s (fn [ch] (.codePointAt ch 0))))))

(defn- pad-even [v] (if (odd? (count v)) (conj v 0) v))

(defn- align4 [v] (into v (repeat (mod (- 4 (mod (count v) 4)) 4) 0)))

(defn table-checksum
  "SFNT checksum of an unsigned byte vector: the sum of its big-endian u32s
   (zero-padded to a 4-byte multiple), mod 2^32."
  [table]
  (reduce (fn [sum quad]
            (mod (+ sum (reduce (fn [word byte] (+ (* word 0x100) byte)) 0 quad))
                 0x100000000))
          0
          (partition 4 4 [0 0 0] table)))

(defn- search-params
  "The binary-search fields of the offset table for `n` directory records."
  [n]
  (let [pows (take-while #(<= % n) (iterate #(* 2 %) 1))
        search-range (* 16 (last pows))]
    {:search-range   search-range
     :entry-selector (dec (count pows))
     :range-shift    (- (* 16 n) search-range)}))

(defn build-sfnt
  "Assemble a standalone TrueType sfnt from a map of tag -> unsigned byte
   vector: sorted directory, 4-byte-aligned tables, per-table checksums, and
   head's checkSumAdjustment balanced over the whole font. Callers pass head
   with its adjustment field zeroed (its directory checksum is defined over
   that state)."
  [tables]
  (let [tags (sort (keys tables))
        dir-size (+ 12 (* 16 (count tags)))
        placed (reduce (fn [acc tag]
                         (let [table (get tables tag)
                               aligned (align4 table)]
                           {:offset  (+ (:offset acc) (count aligned))
                            :records (conj (:records acc)
                                           {:tag tag
                                            :checksum (table-checksum table)
                                            :offset (:offset acc)
                                            :length (count table)})
                            :bytes   (into (:bytes acc) aligned)}))
                       {:offset dir-size :records [] :bytes []}
                       tags)
        directory {:header (merge {:sfnt-version 0x00010000
                                   :num-tables   (count tags)}
                                  (search-params (count tags)))
                   :records (:records placed)}
        font (into (b/unsigned-vec (mem/emit directory sfnt/directory-layout))
                   (:bytes placed))
        head-off (some #(when (= "head" (:tag %)) (:offset %)) (:records placed))
        adjustment (mod (- 0xB1B0AFBA (table-checksum font)) 0x100000000)]
    (b/from-unsigned
     (if head-off
       ;; checkSumAdjustment sits 8 bytes into head; patching it makes the
       ;; whole-font checksum come out to the magic 0xB1B0AFBA.
       (reduce (fn [font [i v]] (assoc font (+ head-off 8 i) v))
               font
               (map-indexed vector (b/u32->bytes adjustment)))
       font))))

(defn- rebuild-glyf
  "New glyf bytes (unsigned vector) and long-format loca offsets keeping only
   the `kept` gids; every other glyph becomes zero-length. Glyph data pads to
   even length so offsets stay word-aligned."
  [src glyf-off loca kept]
  (reduce (fn [acc gid]
            (let [start (nth loca gid)
                  end (nth loca (inc gid))
                  data (if (and (kept gid) (< start end))
                         (pad-even (b/unsigned-vec (b/slice src (+ glyf-off start) (+ glyf-off end))))
                         [])]
              {:glyf (into (:glyf acc) data)
               :loca (conj (:loca acc) (+ (peek (:loca acc)) (count data)))}))
          {:glyf [] :loca [0]}
          (range (dec (count loca)))))

(defn- copy-table [src dir tag]
  (when-let [{:keys [offset length]} (get dir tag)]
    (b/unsigned-vec (b/slice src offset (+ offset length)))))

(def ^:private copied-tags ["hhea" "maxp" "hmtx" "cvt " "fpgm" "prep"])

(defn subset-font
  "A standalone TrueType sfnt keeping only the glyphs for `cps` (a seq of
   Unicode codepoints), plus gid 0 and any composite components. Returns a
   platform byte source ready to embed."
  [src cps]
  (let [base (sfnt/face-offset src)
        dir (sfnt/table-directory src base)
        off (fn [tag] (get-in dir [tag :offset]))]
    (when-not (and (get dir "glyf") (get dir "cmap"))
      (throw (ex-info "Subsetting needs TrueType outlines and a cmap"
                      {:tables (sort (keys dir))})))
    (let [head (mem/parse-record src sfnt/head-fields {:offset (off "head")})
          num-glyphs (:num-glyphs (sfnt/parse-maxp src (off "maxp")))
          cmap (sfnt/parse-cmap src (off "cmap"))
          loca (sfnt/parse-loca src (off "loca") (:index-to-loc-format head) num-glyphs)
          gids (into #{} (keep #(sfnt/char->gid cmap %)) cps)
          kept (sfnt/glyph-closure src (off "glyf") loca gids)
          {new-glyf :glyf new-loca :loca} (rebuild-glyf src (off "glyf") loca kept)
          tables (into {"head" (b/unsigned-vec
                                (mem/emit-record (assoc head
                                                        :index-to-loc-format 1
                                                        :checksum-adjustment 0)
                                                 sfnt/head-fields))
                        "loca" (b/unsigned-vec
                                (mem/emit-record {:offsets new-loca}
                                                 [[:offsets [:u32 (count new-loca)]]]))
                        "glyf" new-glyf}
                       (keep (fn [tag]
                               (when-let [table (copy-table src dir tag)]
                                 [tag table])))
                       copied-tags)]
      (build-sfnt tables))))
