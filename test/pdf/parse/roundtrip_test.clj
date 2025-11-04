(ns pdf.parse.roundtrip-test
  "The parser's milestone guard: serialize an authored context, parse the
   bytes, serialize the parsed context, and assert the second byte output
   equals the first — byte for byte. Our serializer is deterministic, so a
   clean file must survive the round trip verbatim. Keep this green forever."
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.api :as pdf]
   [pdf.context.stream :as stream]
   [pdf.parse.core :as parse]
   [pdf.test-util :as tu]
   [pdf.bytes :as b]
   [pdf.utils.dimension :refer [inches->dim]]
   [pdf.utils.io :as io])
  (:import [java.util Arrays]))

(defn- text-doc []
  (let [font (tu/ubuntu)
        title (assoc (pdf/new-text "Hello, PDF" font) :font-size 28)
        body (pdf/new-text "A page is a map and this file is data until save." font)]
    (-> (pdf/new-pdf)
        (pdf/with-page
          (-> (pdf/new-page (inches->dim 8.5) (inches->dim 11))
              (pdf/with-text (inches->dim 1) (inches->dim 9.5) title)
              (pdf/with-text (inches->dim 1) (inches->dim 9) body))))))

(defn- raw-stream-doc []
  (let [ops (pdf/string->stream "0.35 0.51 0.85 RG 1 w 20 400 m 220 600 l S")
        page (-> (pdf/new-page (inches->dim 8.5) (inches->dim 11))
                 (pdf/with-stream ops)
                 ;; a name with no alias, to exercise the exact-name escape hatch
                 (assoc 'VendorRotate 90))]
    (pdf/with-page (pdf/new-pdf) page)))

(defn- image-doc []
  (let [img (pdf/new-image "test/resources/images/rgb.jpg")
        page (-> (pdf/new-page (inches->dim 8.5) (inches->dim 11))
                 (pdf/with-image img 72 360 {:width 220}))]
    (pdf/with-page (pdf/new-pdf) page)))

(defn- roundtrips-byte-exact? [ctx]
  (let [bytes-a (pdf/serialize ctx)
        parsed (parse/parse bytes-a)
        bytes-b (pdf/serialize parsed)]
    {:parsed parsed
     :equal? (Arrays/equals ^bytes bytes-a ^bytes bytes-b)}))

(deftest text-doc-roundtrip
  (let [{:keys [parsed equal?]} (roundtrips-byte-exact? (text-doc))]
    (is equal? "text document round-trips byte-for-byte")
    (testing "parsed context is well-formed"
      (is (= 2.0 (:version parsed)))
      (is (empty? (:warnings parsed)))
      (is (= 1 (count (filter #(= :page (:type (:obj %))) (:objects parsed)))))
      (is (some #(= :catalog (:type (:obj %))) (:objects parsed))))))

(deftest raw-stream-doc-roundtrip
  (let [{:keys [parsed equal?]} (roundtrips-byte-exact? (raw-stream-doc))]
    (is equal? "raw-stream document round-trips byte-for-byte")
    (testing "an unregistered name survives on the page as an exact-name symbol"
      (is (= 90 (some #(when (= :page (:type (:obj %))) (get (:obj %) 'VendorRotate))
                      (:objects parsed)))))))

(deftest image-doc-roundtrip
  (let [ctx (image-doc)
        bytes-a (pdf/serialize ctx)
        parsed (parse/parse bytes-a)
        bytes-b (pdf/serialize parsed)]
    (is (Arrays/equals ^bytes bytes-a ^bytes bytes-b)
        "image document round-trips byte-for-byte")
    (testing "the JPEG XObject payload survives parse byte-identical to the source"
      ;; rgb.jpg embeds as a DCTDecode pass-through, so the parsed stream bytes
      ;; must equal the original file bytes exactly.
      (let [file-bytes (io/file->bytes "test/resources/images/rgb.jpg")
            jpeg-stream (->> (:objects parsed)
                             (map :obj)
                             (filter stream/pdf-stream?)
                             (some (fn [s] (when (= :dct-decode (:filter (:dict s))) s))))]
        (is (some? jpeg-stream) "a DCTDecode image stream is present")
        (is (= (b/unsigned-vec file-bytes) (b/unsigned-vec (:bytes jpeg-stream))))))))
