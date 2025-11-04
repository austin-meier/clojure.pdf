(ns pdf.parse.hybrid-test
  "A hand-built hybrid-reference file (7.5.8.4 / Annex H.7): a classic xref
   table whose trailer carries an /XRefStm, and one object (obj 4) that lives
   only in an object stream. The classic table marks obj 4 as a free
   placeholder; the /XRefStm holds its real compressed entry. Parsing must let
   the stream win, recover obj 4, and resolve the catalog ref to it.

   Building the file (deflate payloads, a binary xref stream) beside the test
   keeps it reproducible; `write-hybrid!` also emits the committed
   test/resources/pdfs/hybrid.pdf that verify.py checks."
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.file.xref :as xref]
   [pdf.parse.core :as parse]
   [pdf.bytes :as b]
   [pdf.utils.flate :as flate]))

(defn- ascii [s] (b/ascii->bytes s))

(defn- deflate-vec [byte-vec]
  (b/unsigned-vec (flate/deflate (b/from-unsigned byte-vec))))

(defn hybrid-bytes
  "The hybrid fixture as a byte source. Objects: 1 catalog (referencing the
   hidden obj 4), 2 pages, 3 page, 5 object-stream container holding obj 4, 6
   the /XRefStm. A classic table at the tail lists obj 4 as free; the stream
   overrides it."
  []
  (let [header (ascii "%PDF-1.5\n")
        obj1 (ascii "1 0 obj\n<< /Type /Catalog /Pages 2 0 R /Hidden 4 0 R >>\nendobj\n")
        obj2 (ascii "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
        obj3 (ascii "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n")
        ;; object stream (obj 5): header "4 0 " then the value at /First
        objstm-header "4 0 "
        objstm-value "<< /Type /ExampleHidden /Value 42 >>"
        first-off (count objstm-header)
        objstm-payload (deflate-vec (ascii (str objstm-header objstm-value)))
        obj5 (vec (concat (ascii (str "5 0 obj\n<< /Type /ObjStm /N 1 /First " first-off
                                      " /Length " (count objstm-payload)
                                      " /Filter /FlateDecode >>\nstream\n"))
                          objstm-payload
                          (ascii "\nendstream\nendobj\n")))
        ;; xref stream (obj 6): one type-2 entry for obj 4 -> container 5, index 0
        xref-payload (deflate-vec [2 5 0])
        obj6 (vec (concat (ascii (str "6 0 obj\n"
                                      "<< /Type /XRef /Size 7 /W [1 1 1] /Index [4 1]"
                                      " /Root 1 0 R /Filter /FlateDecode /Length "
                                      (count xref-payload) " >>\nstream\n"))
                          xref-payload
                          (ascii "\nendstream\nendobj\n")))
        pre (vec (concat header obj1 obj2 obj3 obj5 obj6))
        off1 (count header)
        off2 (+ off1 (count obj1))
        off3 (+ off2 (count obj2))
        off5 (+ off3 (count obj3))
        off6 (+ off5 (count obj5))
        classic-off (count pre)
        classic (ascii (str "xref\n0 7\n"
                            (format "%010d 65535 f\r\n" 0)
                            (format "%010d 00000 n\r\n" off1)
                            (format "%010d 00000 n\r\n" off2)
                            (format "%010d 00000 n\r\n" off3)
                            (format "%010d 00000 f\r\n" 0)   ;; obj 4 free placeholder
                            (format "%010d 00000 n\r\n" off5)
                            (format "%010d 00000 n\r\n" off6)
                            "trailer\n<< /Size 7 /Root 1 0 R /XRefStm " off6 " >>\n"
                            "startxref\n" classic-off "\n%%EOF\n"))]
    (b/from-unsigned (concat pre classic))))

(defn write-hybrid!
  "Emit the committed fixture. Run from a REPL to regenerate:
   (write-hybrid! \"test/resources/pdfs/hybrid.pdf\")."
  [path]
  (with-open [out (java.io.FileOutputStream. ^String path)]
    (.write out ^bytes (hybrid-bytes))))

(defn- catalog [ctx]
  (some #(when (= :catalog (:type (:obj %))) (:obj %)) (:objects ctx)))

(deftest hybrid-xrefstm-wins
  (let [parsed (parse/parse (hybrid-bytes))]
    (testing "the hybrid file parses cleanly"
      (is (= 1.5 (:version parsed)))
      (is (empty? (:warnings parsed)))
      (is (= 1 (count (filter #(= :page (:type (:obj %))) (:objects parsed))))))
    (testing "the catalog references the hidden object by symbolic ref"
      ;; /Hidden is unregistered, so it inverts to an exact-name symbol key
      (is (xref/ref? (get (catalog parsed) 'Hidden))))
    (testing "the compressed object is recovered despite the classic free entry"
      (let [hidden (some #(when (= 42 (get (:obj %) 'Value)) (:obj %)) (:objects parsed))]
        (is (some? hidden))
        (is (= 'ExampleHidden (:type hidden)))))))
