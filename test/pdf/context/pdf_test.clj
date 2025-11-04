(ns pdf.context.pdf-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.page :refer [new-page with-page with-stream]]
   [pdf.context.pdf :refer [compile-content-ops new-pdf number-context serialize]]
   [pdf.context.stream :refer [bytes->stream pdf-stream?]]
   [pdf.file.xref :refer [->ref ->IndirectRef]]
   [pdf.utils.dimension :refer [inches->dim]]))

;; Every byte value exactly once, so any encoding round trip would corrupt it
(def binary-payload
  (byte-array (map unchecked-byte (range 256))))

(def pdf-bytes
  (serialize
   (-> (new-pdf)
       (with-page (-> (new-page (inches->dim 8.5) (inches->dim 11))
                      (with-stream (bytes->stream binary-payload)))))))

(defn- latin-1
  "Decode output bytes 1:1 so string indices equal byte offsets"
  [^bytes bs]
  (String. bs "ISO-8859-1"))

(deftest file-skeleton
  (let [s (latin-1 pdf-bytes)]
    (testing "Header and EOF are in place"
      (is (.startsWith s "%PDF-2.0\n"))
      (is (.endsWith s "%%EOF\n")))

    (testing "startxref points at the xref table"
      (let [[_ offset] (re-find #"startxref\n(\d+)\n" s)]
        (is (.startsWith (subs s (parse-long offset)) "xref\n"))))))

(deftest xref-offsets-are-byte-accurate
  (let [s (latin-1 pdf-bytes)
        offsets (map (comp parse-long second) (re-seq #"(\d{10}) 00000 n" s))]
    (is (pos? (count offsets)))
    (doseq [[idx offset] (map-indexed vector offsets)]
      (testing (str "xref entry " (inc idx) " points at its object header")
        (is (.startsWith (subs s offset) (str (inc idx) " 0 obj")))))))

(deftest binary-payload-survives-serialization
  (testing "The full 256-byte payload appears verbatim in the output"
    (is (some #(= (vec binary-payload) %)
              (partition (count binary-payload) 1 (vec pdf-bytes))))))

(deftest adjacent-op-batches-share-one-stream
  (let [ops   [[:save-state] [:restore-state]]
        page  (-> (new-page (inches->dim 4) (inches->dim 3))
                  (update :contents (fnil conj []) ops)
                  (update :contents (fnil conj []) ops)
                  (with-stream (bytes->stream binary-payload))
                  (update :contents conj ops))
        ctx   (compile-content-ops (with-page (new-pdf) page))
        contents (:contents (:obj (last (:objects ctx))))]
    (testing "Two adjacent batches merge; the raw stream keeps its position"
      (is (= 3 (count contents)))
      (is (every? pdf-stream? contents))
      (is (= "q\nQ\nq\nQ" (String. ^bytes (:bytes (first contents)) "ISO-8859-1")))
      (is (= "q\nQ" (String. ^bytes (:bytes (last contents)) "ISO-8859-1"))))))

(deftest bare-ref-contents-serializes
  ;; a parsed page can carry :contents as a single ref (from `/Contents 5 0 R`);
  ;; compile-content-ops must leave it intact, not seq the Ref record apart
  (let [ctx {:version 2.0
             :objects [{:id 'cat :obj {:type :catalog :pages (->ref 'pgs)}}
                       {:id 'pgs :obj {:type :pages :count 1 :kids [(->ref 'pg)]}}
                       {:id 'pg  :obj {:type :page :parent (->ref 'pgs)
                                       :media-box [0 0 200 200] :contents (->ref 'c)}}
                       {:id 'c   :obj (bytes->stream (.getBytes "q Q" "ISO-8859-1"))}]}
        out (latin-1 (serialize ctx))]
    (testing "the content ref survives, now as a single-element array"
      (is (re-find #"/Contents \[\d+ 0 R\]" out)))
    (is (re-find #"q Q" out) "the referenced stream is written")))

;; Object identity is symbolic; numbers are assigned only at serialize time, so
;; references survive any reordering of :objects.
(def two-object-ctx
  {:version 2.0
   :objects [{:id 'catalog :obj {:type :catalog :pages (->ref 'pages)}}
             {:id 'pages :obj {:type :pages :kids []}}]})

(deftest symbolic-refs-numbered-at-serialize-time
  (testing "Object numbers follow :objects order; refs resolve to them by id"
    (let [objs (:objects (number-context two-object-ctx))]
      (is (= (->IndirectRef 2 0) (:pages (first objs))))))

  (testing "Reordering objects keeps references valid (ids are stable)"
    (let [reordered (update two-object-ctx :objects (comp vec reverse))
          objs (:objects (number-context reordered))]
      (is (= :pages (:type (first objs))))
      ;; catalog is now object 2, but its :pages ref still points at pages (now 1)
      (is (= (->IndirectRef 1 0) (:pages (second objs)))))))
