(ns utils.otf-parser
  (:require
   [utils.memory :refer [resolve-type-lengths parse-binary-file]]))



;; References:
;;   OpenType spec: https://learn.microsoft.com/en-us/typography/opentype/spec/
;;   TrueType spec: https://developer.apple.com/fonts/TrueType-Reference-Manual/
;;   CFF spec:      https://adobe-type-tools.github.io/font-tech-notes/pdfs/5176.CFF.pdf

;; OTF overview https://learn.microsoft.com/en-us/typography/opentype/spec/ttochap1


(def ttf-type-aliases
  {:uint8        :u8
   :int8         :i8
   :uint16       :u16
   :int16        :i16
   :uint24       :u24
   :uint32       :u32
   :int32        :i32
   :Fixed        :i32   ;; 16.16 fixed-point (interpretation step needed)
   :FWORD        :i16   ;; Design units (signed)
   :UFWORD       :u16   ;; Design units (unsigned)
   :F2DOT14      :i16   ;; 2.14 fixed (interpretation step needed)
   :LONGDATETIME :i64   ;; Seconds since 1904-01-01 UTC (interpretation step)
   :Tag          [:char :char :char :char]
   :Offset16     :uint16
   :Offset32     :uint32
   :Version16Dot16 :u32  ;; Packed major.minor (interpretation step needed)
   })

(resolve-type-lengths ttf-type-aliases) ;; side-effect resolution (legacy)


(def otf-layout
  [{:section :offset-table
    :fields  [[:sfntVersion    :u32]
              [:numTables      :u16]
              [:searchRange    :u16]
              [:entrySelector  :u16]
              [:rangeShift     :u16]]}

  {:section :table-directory
   :repeat  (fn [ctx] (get-in ctx [:data :offset-table :numTables]))
   :fields  [[:tag       :Tag]
             [:checkSum  :uint32]
             [:offset    :uint32]
             [:length    :uint32]]}])

(parse-binary-file "./Ubuntu-Regular.ttf" otf-layout ttf-type-aliases)



;; -----------------------------------------------------------------------------
;; High-level declarative specification
;; -----------------------------------------------------------------------------
;; The spec is a vector of section maps. Each section can describe:
;;   :section   - identifier keyword
;;   :fields    - vector of [field-key type-key]
;;   :repeat    - integer OR (fn [ctx] ...) returning count (for directories)
;;   :dispatch  - keyword of field whose value chooses a sub-layout map
;;   :layouts   - map from tag/string -> field vector for dispatched tables
;;   :offset    - keyword of field whose value is an absolute file offset
;;   :seek?     - boolean: parser should jump to :offset before reading :fields
;;   :version?  - predicate or key used to select conditional layout variants
;; The current `parse-binary` does NOT implement these — they are a contract.

(def font-spec
  [;; 1. Offset Table (always first)
   {:section :offset-table
    :fields  [[:sfntVersion    :uint32]   ;; 0x00010000 for TrueType outlines or 'OTTO' for CFF
              [:numTables      :uint16]
              [:searchRange    :uint16]
              [:entrySelector  :uint16]
              [:rangeShift     :uint16]]}

   ;; 2. Table Directory (numTables records)
   {:section :table-directory
    :repeat  (fn [ctx] (get-in ctx [:data :offset-table :numTables]))
    :fields  [[:tag       :Tag]      ;; e.g. "head" "cmap" "name" etc.
              [:checkSum  :uint32]
              [:offset    :uint32]
              [:length    :uint32]]}

   ;; 3. Dispatched table parsers (selected by each directory entry :tag)
   {:section :tables
    :dispatch :tag
    :for-each (fn [ctx] (:table-directory ctx))
    :layouts
    {;; 'head' table (font header)
     "head" [[:version            :Fixed]
             [:fontRevision       :Fixed]
             [:checkSumAdjustment :uint32]
             [:magicNumber        :uint32]  ;; 0x5F0F3CF5
             [:flags              :uint16]
             [:unitsPerEm         :uint16]
             [:created            :LONGDATETIME]
             [:modified           :LONGDATETIME]
             [:xMin               :FWORD]
             [:yMin               :FWORD]
             [:xMax               :FWORD]
             [:yMax               :FWORD]
             [:macStyle           :uint16]
             [:lowestRecPPEM      :uint16]
             [:fontDirectionHint  :int16]
             [:indexToLocFormat   :int16]   ;; 0 = short offsets, 1 = long
             [:glyphDataFormat    :int16]]

     ;; 'maxp' table (maximum profile) — version-dependent
     "maxp" {:version? (fn [bytes] (take 4 bytes)) ;; placeholder extraction
             :variants
             {0x00005000 [[:version  :Fixed]
                          [:numGlyphs :uint16]]
              0x00010000 [[:version                :Fixed]
                          [:numGlyphs             :uint16]
                          [:maxPoints             :uint16]
                          [:maxContours           :uint16]
                          [:maxCompositePoints    :uint16]
                          [:maxCompositeContours  :uint16]
                          [:maxZones              :uint16]
                          [:maxTwilightPoints     :uint16]
                          [:maxStorage            :uint16]
                          [:maxFunctionDefs       :uint16]
                          [:maxInstructionDefs    :uint16]
                          [:maxStackElements      :uint16]
                          [:maxSizeOfInstructions :uint16]
                          [:maxComponentElements  :uint16]
                          [:maxComponentDepth     :uint16]]}}

     ;; 'hhea' Horizontal Header
     "hhea" [[:version            :Fixed]
             [:ascender           :FWORD]
             [:descender          :FWORD]
             [:lineGap            :FWORD]
             [:advanceWidthMax    :UFWORD]
             [:minLeftSideBearing :FWORD]
             [:minRightSideBearing :FWORD]
             [:xMaxExtent         :FWORD]
             [:caretSlopeRise     :int16]
             [:caretSlopeRun      :int16]
             [:caretOffset        :int16]
             [:reserved1          :int16]
             [:reserved2          :int16]
             [:reserved3          :int16]
             [:reserved4          :int16]
             [:metricDataFormat   :int16]
             [:numberOfHMetrics   :uint16]]

     ;; 'name' table (naming) — requires nested repeats based on counts
     "name" [[:format     :uint16]
             [:count      :uint16]  ;; number of name records
             [:stringOffset :uint16]
             ;; followed by `count` name records and then string storage at (start + stringOffset)
             ;; name record format: platformID, encodingID, languageID, nameID, length, offset
             {:repeat-field :count
              :fields [[:platformID :uint16]
                       [:encodingID :uint16]
                       [:languageID :uint16]
                       [:nameID     :uint16]
                       [:length     :uint16]
                       [:offset     :uint16]]
              :section :name-records}]

     ;; 'cmap' (character to glyph mapping) — simplified header here
     "cmap" [[:version :uint16]
             [:numTables :uint16]
             {:repeat-field :numTables
              :fields [[:platformID :uint16]
                       [:encodingID :uint16]
                       [:subtableOffset :uint32]]
              :section :cmap-encodings}]

     ;; 'OS/2' table (style metrics) — subset (full spec is longer)
     "OS/2" [[:version              :uint16]
             [:xAvgCharWidth        :int16]
             [:usWeightClass        :uint16]
             [:usWidthClass         :uint16]
             [:fsType               :uint16]
             [:ySubscriptXSize      :int16]
             [:ySubscriptYSize      :int16]
             [:ySubscriptXOffset    :int16]
             [:ySubscriptYOffset    :int16]
             [:ySuperscriptXSize    :int16]
             [:ySuperscriptYSize    :int16]
             [:ySuperscriptXOffset  :int16]
             [:ySuperscriptYOffset  :int16]
             [:yStrikeoutSize       :int16]
             [:yStrikeoutPosition   :int16]
             [:sFamilyClass         :int16]
             [:panose               [:uint8 :uint8 :uint8 :uint8 :uint8 :uint8 :uint8 :uint8 :uint8 :uint8]]
             [:ulUnicodeRange1      :uint32]
             [:ulUnicodeRange2      :uint32]
             [:ulUnicodeRange3      :uint32]
             [:ulUnicodeRange4      :uint32]
             [:achVendID            [:uint8 :uint8 :uint8 :uint8]]
             [:fsSelection          :uint16]
             [:usFirstCharIndex     :uint16]
             [:usLastCharIndex      :uint16]
             [:sTypoAscender        :int16]
             [:sTypoDescender       :int16]
             [:sTypoLineGap         :int16]
             [:usWinAscent          :uint16]
             [:usWinDescent         :uint16]]

     ;; 'post' table (PostScript info) — subset
     "post" [[:version        :Fixed]
             [:italicAngle    :Fixed]
             [:underlinePosition :FWORD]
             [:underlineThickness :FWORD]
             [:isFixedPitch   :uint32]
             [:minMemType42   :uint32]
             [:maxMemType42   :uint32]
             [:minMemType1    :uint32]
             [:maxMemType1    :uint32]]

     ;; 'loca' and 'glyf' need offsets + dependent parsing; placeholders
     "loca" [:DEPENDENT_ON_indexToLocFormat] ;; parse after 'head' & 'maxp'
     "glyf" [:OFFSET_DRIVEN_CONTENT]}}])
