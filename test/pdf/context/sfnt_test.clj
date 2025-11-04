(ns pdf.context.sfnt-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.text.sfnt :as sfnt]
   [pdf.test-util :as tu]
   [pdf.bytes :as b]
   [pdf.bytes.memory :as mem]))

;; Parses the repo's embedding guinea-pig font. Values are stable properties of
;; that specific file, so this doubles as a regression guard for the parser.
(def font-src (tu/ubuntu-src))
(def parsed (sfnt/parse-font font-src))

(deftest table-directory-found
  (testing "The directory exposes the tables subsetting needs"
    (is (every? (:tables parsed) ["head" "maxp" "hhea" "hmtx" "cmap" "loca" "glyf" "name"]))))

(deftest metrics-and-name
  (testing "Head/maxp/hhea metrics match the font"
    (is (= "Ubuntu-Regular" (:base-font parsed)))
    (is (= 1000 (:units-per-em parsed)))
    (is (= 1262 (:num-glyphs parsed)))
    (is (= [-167 -189 3480 962] (:bbox parsed)))
    (is (= 932 (:ascent parsed)))
    (is (= -189 (:descent parsed)))
    (is (= 1 (:index-to-loc-format parsed)))))

(deftest advance-widths
  (testing "One advance width per glyph"
    (is (= (:num-glyphs parsed) (count (:advance-widths parsed))))
    (is (every? nat-int? (:advance-widths parsed)))))

(defn- unsigned-vec [src start end]
  (mapv #(b/u8 src %) (range start end)))

(def loca (sfnt/parse-loca font-src (get-in parsed [:tables "loca" :offset])
                           (:index-to-loc-format parsed) (:num-glyphs parsed)))
(def glyf-off (get-in parsed [:tables "glyf" :offset]))

(deftest cmap-lookup
  (let [cmap (sfnt/parse-cmap font-src (get-in parsed [:tables "cmap" :offset]))]
    (testing "Known characters map to distinct nonzero gids"
      (let [gids (map #(sfnt/char->gid cmap (int %)) "Hello")]
        (is (every? pos-int? gids))
        (is (= 4 (count (distinct gids))))))
    (testing "Unmapped codepoints return nil"
      (is (nil? (sfnt/char->gid cmap 0x10FFFF))))))

(deftest loca-offsets
  (testing "One span per glyph, monotonic, within the glyf table"
    (is (= (inc (:num-glyphs parsed)) (count loca)))
    (is (apply <= loca))
    (is (<= (peek loca) (get-in parsed [:tables "glyf" :length])))))

(deftest composite-closure
  (let [composite? (fn [gid]
                     (let [start (nth loca gid) end (nth loca (inc gid))]
                       (and (< start end) (neg? (b/i16 font-src (+ glyf-off start))))))
        gid (first (filter composite? (range (:num-glyphs parsed))))]
    (is (some? gid) "the font has at least one composite glyph")
    (testing "Composite components are found and closed over"
      (let [components (sfnt/glyph-components font-src glyf-off (nth loca gid) (nth loca (inc gid)))
            closure (sfnt/glyph-closure font-src glyf-off loca #{gid})]
        (is (seq components))
        (is (every? closure (cons gid components)))
        (is (contains? closure 0))))
    (testing "Simple glyphs have no components"
      (is (= [] (sfnt/glyph-components font-src glyf-off (nth loca 0) (nth loca 1)))))))

;; The layouts are bi-directional: emitting what was parsed must reproduce the
;; font's bytes exactly. This is the emit-side guard subsetting will lean on.
(deftest layouts-round-trip
  (testing "The table directory survives parse -> emit byte-exact"
    (let [dir (mem/parse font-src sfnt/directory-layout)
          end (+ 12 (* 16 (get-in dir [:header :num-tables])))]
      (is (= (unsigned-vec font-src 0 end)
             (unsigned-vec (mem/emit dir sfnt/directory-layout) 0 end)))))

  (testing "The head table survives parse -> emit byte-exact"
    (let [off (get-in parsed [:tables "head" :offset])
          head (mem/parse-record font-src sfnt/head-fields {:offset off})
          emitted (mem/emit-record head sfnt/head-fields)]
      (is (= (unsigned-vec font-src off (+ off (b/length emitted)))
             (unsigned-vec emitted 0 (b/length emitted)))))))

(deftest embed-bytes-passthrough
  (testing "A single-face TTF embeds its whole byte source"
    (is (= (b/length font-src) (b/length (sfnt/embed-bytes font-src))))))
