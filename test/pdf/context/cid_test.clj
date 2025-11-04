(ns pdf.context.cid-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [pdf.context.page :refer [new-page with-page]]
   [pdf.context.pdf :refer [new-pdf serialize]]
   [pdf.context.stream :refer [pdf-stream?]]
   [pdf.context.text.cid :as cid]
   [pdf.context.text.core :refer [new-text with-text]]
   [pdf.context.text.font :refer [new-font]]
   [pdf.context.text.sfnt :as sfnt]
   [pdf.serialize :refer [to-pdf]]
   [pdf.test-util :as tu]
   [pdf.utils.dimension :refer [inches->dim]]))

(def font (tu/ubuntu))

(deftest text-encoding
  (testing "Text encodes to one gid per codepoint with the ToUnicode pairs"
    (let [{:keys [gids gid->cp]} (cid/encode-text font "Hello")]
      (is (= 5 (count gids)))
      (is (every? pos-int? gids))
      (is (= (set gids) (set (keys gid->cp))))
      (is (= (set (map int "Helo")) (set (vals gid->cp))))))
  (testing "Unmapped codepoints fall back to notdef and carry no mapping"
    ;; U+0378 is an unassigned Unicode codepoint
    (let [{:keys [gids gid->cp]} (cid/encode-text font (str (char 0x0378)))]
      (is (= [0] gids))
      (is (= {} gid->cp)))))

(deftest glyph-strings
  (testing "A glyph string renders as 2-byte hex CIDs"
    (is (= "<00480065>" (to-pdf (cid/->GlyphString [0x48 0x65]))))
    (is (= "<>" (to-pdf (cid/->GlyphString []))))))

(deftest widths
  (let [metrics {:advance-widths [500 100 200 300 400 250] :units-per-em 1000}]
    (testing "Consecutive gids group into runs"
      (is (= [1 [100 200] 5 [250]] (cid/widths-array metrics #{5 1 2})))
      (is (= [] (cid/widths-array metrics #{})))))
  (testing "Widths scale to 1000 units per em"
    (is (= [1 [1000]] (cid/widths-array {:advance-widths [0 2048] :units-per-em 2048} #{1})))))

(deftest subset-tags
  (let [tag (cid/subset-tag #{1 5 9})]
    (testing "Six uppercase letters, deterministic in the gid set"
      (is (re-matches #"[A-Z]{6}" tag))
      (is (= tag (cid/subset-tag #{9 5 1}))))))

(deftest to-unicode
  (testing "bfchar entries map gid hex to UTF-16BE hex"
    (let [cmap (String. ^bytes (:bytes (cid/to-unicode-stream {1 65, 2 0x1F600})) "UTF-8")]
      (is (str/includes? cmap "2 beginbfchar"))
      (is (str/includes? cmap "<0001> <0041>"))
      (is (str/includes? cmap "<0002> <D83DDE00>"))
      (is (str/includes? cmap "endcmap"))))
  (testing "Entries chunk into blocks of at most 100"
    (let [cmap (String. ^bytes (:bytes (cid/to-unicode-stream
                                        (zipmap (range 1 151) (range 65 215))))
                        "UTF-8")]
      (is (str/includes? cmap "100 beginbfchar"))
      (is (str/includes? cmap "50 beginbfchar")))))

(deftest font-objects
  (let [{:keys [gid->cp]} (cid/encode-text font "Hello, World!")
        obj (cid/font-object font gid->cp)
        descendant (first (:descendant-fonts obj))
        descriptor (:font-descriptor descendant)]
    (testing "Type0 over CIDFontType2 with Identity mappings"
      (is (= :type0 (:subtype obj)))
      (is (= :identity-h (:encoding obj)))
      (is (= :cid-font-type2 (:subtype descendant)))
      (is (= :identity (:cid-to-gid-map descendant)))
      (is (= {:registry "Adobe" :ordering "Identity" :supplement 0}
             (:cid-system-info descendant))))
    (testing "The subset name tags the original PostScript name"
      (is (re-matches #"[A-Z]{6}\+Ubuntu-Regular" (name (:base-font obj))))
      (is (= (:base-font obj) (:base-font descendant) (:font-name descriptor))))
    (testing "Every used gid has a width"
      (let [covered (set (mapcat (fn [[start ws]] (range start (+ start (count ws))))
                                 (partition 2 (:w descendant))))]
        (is (every? covered (keys gid->cp)))))
    (testing "FontFile2 holds a parseable subset with gids intact"
      (let [program (:font-file-2 descriptor)
            parsed (sfnt/parse-font (:bytes program))]
        (is (pdf-stream? program))
        (is (= (get-in program [:dict :length1])
               (alength ^bytes (:bytes program))))
        (is (= (get-in font [:sfnt :num-glyphs]) (:num-glyphs parsed)))
        (is (= (get-in font [:sfnt :units-per-em]) (:units-per-em parsed)))))
    (testing "ToUnicode rides along"
      (is (pdf-stream? (:to-unicode obj))))))

(deftest resource-keys
  (let [page (new-page (inches->dim 8.5) (inches->dim 11))
        at (fn [p txt f] (with-text p (inches->dim 1) (inches->dim 5) (new-text txt f)))]
    (testing "Distinct fonts get distinct keys (the F0-collision bug)"
      (let [other (new-font tu/font-path)
            page (-> page (at "one" font) (at "two" other))]
        (is (= #{"F0" "F1"} (set (keys (get-in page [:resources :font])))))))
    (testing "Reusing the same font value dedupes onto one key and merges usage"
      (let [page (-> page (at "ab" font) (at "bc" font))]
        (is (= ["F0"] (keys (get-in page [:resources :font]))))
        (is (= (set (map int "abc"))
               (set (vals (get-in page [:font-usage "F0"])))))))))

(deftest end-to-end-serialization
  (let [ctx (-> (new-pdf)
                (with-page (-> (new-page (inches->dim 8.5) (inches->dim 11))
                               (with-text (inches->dim 1) (inches->dim 5)
                                 (new-text "Hello, World!" font)))))
        out (String. (serialize ctx) "ISO-8859-1")]
    (testing "The file carries the whole CID structure"
      (is (str/includes? out "/Encoding /Identity-H"))
      (is (str/includes? out "/Subtype /CIDFontType2"))
      (is (str/includes? out "/CIDToGIDMap /Identity"))
      (is (str/includes? out "/DescendantFonts"))
      (is (str/includes? out "/ToUnicode"))
      (is (str/includes? out "beginbfchar"))
      (is (re-find #"/BaseFont /[A-Z]{6}\+Ubuntu-Regular" out)))
    (testing "Text shows as a hex glyph string"
      (is (re-find #"<[0-9A-F]+> Tj" out)))
    (testing "No authoring-only keys leak into the file"
      (is (not (str/includes? out "FontUsage")))
      (is (not (str/includes? out "FontContext"))))))

(deftest font-dedup
  (testing "the content hash is stable across independent loads of one file"
    (is (= (:sha1 (new-font tu/font-path)) (:sha1 (new-font tu/font-path)))))
  (testing "two independent loads of the same file embed a single merged font"
    (let [page (fn [txt f] (-> (new-page (inches->dim 8.5) (inches->dim 11))
                               (with-text (inches->dim 1) (inches->dim 5)
                                 (new-text txt f))))
          ctx (-> (new-pdf)
                  (with-page (page "abc" (new-font tu/font-path)))
                  (with-page (page "xyz" (new-font tu/font-path))))
          out (String. (serialize ctx) "ISO-8859-1")]
      (is (= 1 (count (re-seq #"/FontFile2" out))) "the font program embeds once")
      ;; the one subset carries glyphs from both loads: their codepoints both
      ;; survive in the shared /ToUnicode (UTF-16BE hex, 'a'=0061, 'x'=0078)
      (is (str/includes? out "0061"))
      (is (str/includes? out "0078")))))
