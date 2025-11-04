(ns pdf.parse.incremental-test
  "A hand-built incremental-update fixture: a base document followed by an
   update section that redefines the page object and chains back through
   /Prev. Exercises the classic-xref /Prev merge — newest definition wins. The
   file is pure ASCII, so byte offsets equal string lengths."
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.parse.core :as parse]
   [pdf.bytes :as b]))

(defn- src [s] (b/from-unsigned (b/ascii->bytes s)))

(defn- entry [off] (format "%010d 00000 n\r\n" off))

(defn- incremental-pdf
  "Base objects 1-3 (catalog, pages, page), a classic xref/trailer, then an
   incremental update that redefines object 3 with a /Rotate and points /Prev
   at the base xref."
  []
  (let [header "%PDF-1.7\n"
        obj1 "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
        obj2 "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
        obj3 "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n"
        pre (str header obj1 obj2 obj3)
        off1 (count header)
        off2 (+ off1 (count obj1))
        off3 (+ off2 (count obj2))
        xref1-off (count pre)
        xref1 (str "xref\n0 4\n"
                   (format "%010d 65535 f\r\n" 0)
                   (entry off1) (entry off2) (entry off3)
                   "trailer\n<< /Size 4 /Root 1 0 R >>\n"
                   "startxref\n" xref1-off "\n%%EOF\n")
        after-base (str pre xref1)
        new-off3 (count after-base)
        obj3b (str "3 0 obj\n"
                   "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Rotate 90 >>\n"
                   "endobj\n")
        xref2-off (+ new-off3 (count obj3b))
        xref2 (str "xref\n3 1\n" (entry new-off3)
                   "trailer\n<< /Size 4 /Root 1 0 R /Prev " xref1-off " >>\n"
                   "startxref\n" xref2-off "\n%%EOF\n")]
    (str after-base obj3b xref2)))

(deftest incremental-update-newest-wins
  (let [parsed (parse/parse (src (incremental-pdf)))
        page (some #(when (= :page (:type (:obj %))) (:obj %)) (:objects parsed))]
    (testing "the file parses cleanly"
      (is (= 1.7 (:version parsed)))
      (is (empty? (:warnings parsed)))
      (is (= 3 (count (:objects parsed)))))
    (testing "the /Prev merge takes the updated object 3"
      (is (some? page))
      (is (= 90 (get page :rotate))))))
