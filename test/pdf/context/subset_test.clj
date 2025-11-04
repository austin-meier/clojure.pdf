(ns pdf.context.subset-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.text.sfnt :as sfnt]
   [pdf.context.text.subset :as subset]
   [pdf.test-util :as tu]
   [pdf.bytes :as b]
   [pdf.bytes.memory :as mem]))

(def font-src (tu/ubuntu-src))
(def original (sfnt/parse-font font-src))
(def cps (subset/codepoints "Hello, World!"))
(def subset-src (subset/subset-font font-src cps))
(def parsed (sfnt/parse-font subset-src))

(def cmap (sfnt/parse-cmap font-src (get-in original [:tables "cmap" :offset])))
(def orig-glyf-off (get-in original [:tables "glyf" :offset]))
(def orig-loca (sfnt/parse-loca font-src (get-in original [:tables "loca" :offset])
                                (:index-to-loc-format original) (:num-glyphs original)))
(def new-glyf-off (get-in parsed [:tables "glyf" :offset]))
(def new-loca (sfnt/parse-loca subset-src (get-in parsed [:tables "loca" :offset])
                               1 (:num-glyphs parsed)))
(def kept (sfnt/glyph-closure font-src orig-glyf-off orig-loca
                              (into #{} (keep #(sfnt/char->gid cmap %)) cps)))

(deftest structure
  (testing "The subset is a standalone sfnt with the tables a font program needs"
    (is (every? (:tables parsed) ["head" "hhea" "maxp" "hmtx" "loca" "glyf"]))
    (is (not-any? (:tables parsed) ["name" "post" "cmap" "OS/2"])))
  (testing "Global metrics survive verbatim"
    (is (= (:units-per-em original) (:units-per-em parsed)))
    (is (= (:num-glyphs original) (:num-glyphs parsed)))
    (is (= (:bbox original) (:bbox parsed)))
    (is (= (:advance-widths original) (:advance-widths parsed))))
  (testing "loca is rewritten to long format"
    (is (= 1 (:index-to-loc-format parsed))))
  (testing "It is much smaller than the original"
    (is (< (b/length subset-src) (quot (b/length font-src) 2)))))

(deftest glyph-data
  (testing "A used glyph's outline bytes are copied verbatim (modulo pad)"
    (let [gid (sfnt/char->gid cmap (int \H))
          span (fn [src glyf-off loca]
                 (mapv #(b/u8 src (+ glyf-off %))
                       (range (nth loca gid) (nth loca (inc gid)))))
          original-bytes (span font-src orig-glyf-off orig-loca)
          subset-bytes (span subset-src new-glyf-off new-loca)]
      (is (= original-bytes (subvec subset-bytes 0 (count original-bytes))))
      (is (every? zero? (drop (count original-bytes) subset-bytes)))))
  (testing "Exactly the kept glyphs that had outlines still have them"
    (let [nonempty (fn [loca]
                     (set (filter #(< (nth loca %) (nth loca (inc %)))
                                  (range (:num-glyphs original)))))]
      (is (= (into #{} (filter (nonempty orig-loca)) kept)
             (nonempty new-loca))))))

(deftest checksums
  (testing "The whole-font checksum balances to the sfnt magic value"
    (is (= 0xB1B0AFBA (subset/table-checksum (b/unsigned-vec subset-src)))))
  (testing "Every directory checksum matches its table (head's is defined with
            a zeroed checkSumAdjustment)"
    (let [{:keys [records]} (mem/parse subset-src sfnt/directory-layout)
          zero-adjustment (fn [table] (apply assoc table (interleave (range 8 12) (repeat 0))))]
      (is (every? (fn [{:keys [tag checksum offset length]}]
                    (let [table (b/unsigned-vec (b/slice subset-src offset (+ offset length)))]
                      (= checksum (subset/table-checksum
                                   (if (= tag "head") (zero-adjustment table) table)))))
                  records)))))

(deftest rejects-unsupported-sources
  (is (thrown? Exception (subset/subset-font (b/from-unsigned (repeat 12 0)) [65]))))
