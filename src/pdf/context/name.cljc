(ns pdf.context.name
  "Mapping between authoring keywords and exact PDF name strings.

   PDF names are case-sensitive and not systematically PascalCase, so the
   kebab->Pascal derivation used for regular names can't express every one
   (`/ID`, `/URI`, `/CIDToGIDMap`, `/CA` vs `/ca`, ...). Well-known names are
   pinned in an explicit alias table; anything not in the table falls back to
   kebab->Pascal on emit and to a symbol on parse, so it still round-trips
   verbatim. Symbols and strings remain the exact-name escape hatch in
   `to-pdf` for names not worth pinning."
  (:require
   [pdf.utils.string :refer [kebab-to-pascal]]))

;; keyword -> exact PDF name string. Values must stay unique so the inverse is
;; well defined. Regular names (which kebab->Pascal would also produce) are
;; listed so the future parser can round-trip them back to friendly keywords
;; instead of symbols; irregular names are here because nothing else can
;; produce them.
(def aliases
  {;; document / page tree
   :type            "Type"
   :catalog         "Catalog"
   :pages           "Pages"
   :page            "Page"
   :kids            "Kids"
   :count           "Count"
   :parent          "Parent"
   :contents        "Contents"
   :resources       "Resources"
   :media-box       "MediaBox"
   :crop-box        "CropBox"
   :rotate          "Rotate"
   :size            "Size"
   :root            "Root"
   :version         "Version"

   ;; streams
   :length          "Length"
   :length1         "Length1"
   :filter          "Filter"
   :subtype         "Subtype"

   ;; fonts
   :font            "Font"
   :base-font       "BaseFont"
   :first-char      "FirstChar"
   :last-char       "LastChar"
   :widths          "Widths"
   :font-descriptor "FontDescriptor"
   :font-name       "FontName"
   :font-b-box      "FontBBox"
   :font-file       "FontFile"
   :font-file-2     "FontFile2"
   :font-file-3     "FontFile3"
   :flags           "Flags"
   :ascent          "Ascent"
   :descent         "Descent"
   :cap-height      "CapHeight"
   :italic-angle    "ItalicAngle"
   :stem-v          "StemV"
   :missing-width   "MissingWidth"
   :to-unicode      "ToUnicode"

   ;; composite (CID) fonts
   :type0            "Type0"
   :encoding         "Encoding"
   :identity-h       "Identity-H"
   :descendant-fonts "DescendantFonts"
   :registry         "Registry"
   :ordering         "Ordering"
   :supplement       "Supplement"
   :w                "W"

   ;; irregular initialisms / casing kebab->Pascal can't reach
   :id              "ID"
   :uri             "URI"
   :url             "URL"
   :dw              "DW"
   :device-gray     "DeviceGray"
   :device-rgb      "DeviceRGB"
   :device-cmyk     "DeviceCMYK"
   :device-n        "DeviceN"
   :cid-system-info "CIDSystemInfo"
   :cid-to-gid-map  "CIDToGIDMap"
   :cid-font-type0  "CIDFontType0"
   :cid-font-type2  "CIDFontType2"

   ;; images / XObjects — irregular casing kebab->Pascal can't reach
   :xobject         "XObject"
   :dct-decode      "DCTDecode"
   :smask           "SMask"
   :bbox            "BBox"

   ;; stream filters and decode parameters — names the parser meets constantly,
   ;; so they invert to friendly keywords instead of symbols
   :flate-decode        "FlateDecode"
   :ascii-hex-decode    "ASCIIHexDecode"
   :ascii85-decode      "ASCII85Decode"
   :lzw-decode          "LZWDecode"
   :run-length-decode   "RunLengthDecode"
   :ccitt-fax-decode    "CCITTFaxDecode"
   :jbig2-decode        "JBIG2Decode"
   :jpx-decode          "JPXDecode"
   :decode-parms        "DecodeParms"
   :predictor           "Predictor"
   :columns             "Columns"
   :colors              "Colors"
   :bits-per-component  "BitsPerComponent"

   ;; xref streams, object streams, and trailer keys the parser reads
   :xref            "XRef"
   :x-ref-stm       "XRefStm"
   :obj-stm         "ObjStm"
   :n               "N"
   :first           "First"
   :prev            "Prev"
   :info            "Info"
   :index           "Index"
   :encrypt         "Encrypt"})

(def ^:private names->key
  (into {} (map (fn [[k v]] [v k])) aliases))

;; A duplicate alias value would make pdf-name->key silently pick a winner.
(assert (= (count aliases) (count names->key))
        "pdf.context.name/aliases values must be unique for the inverse mapping")

(defn key->pdf-name
  "The exact PDF name string for a keyword: an explicit alias when one exists,
   otherwise kebab->Pascal."
  [k]
  (or (get aliases k)
      (kebab-to-pascal (name k))))

(defn pdf-name->key
  "Inverts a PDF name string back to its registered keyword, or to a symbol
   carrying the exact name when it isn't registered so it re-emits verbatim.
   The inverse of `key->pdf-name` for aliased names; the symbol fallback keeps
   unregistered names round-tripping through `to-pdf`'s exact-name escape hatch."
  [s]
  (or (get names->key s)
      (symbol s)))
