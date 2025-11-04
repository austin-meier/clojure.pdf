(ns pdf.edit-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.pdf :refer [save serialize]]
   [pdf.context.stream :refer [bytes->stream]]
   [pdf.edit :refer [merge-pdfs normalize pages]]
   [pdf.file.xref :refer [->ref]]
   [pdf.parse.core :refer [parse parse-file]]))

;; A deliberately nested tree: root -> [mid -> [p1 p2], p3]. Inheritable
;; attributes sit on both interior nodes; p2 overrides the inherited media-box
;; with its own. Ids are literal symbols so the structure reads at a glance
;; (object numbers are assigned only at serialize time, so ids can be anything).
(def nested
  {:version 2.0
   :objects
   [{:id 'cat  :obj {:type :catalog :pages (->ref 'root)}}
    {:id 'root :obj {:type :pages :count 3 :kids [(->ref 'mid) (->ref 'p3)]
                     :media-box [0 0 612 792]}}
    {:id 'mid  :obj {:type :pages :count 2 :kids [(->ref 'p1) (->ref 'p2)]
                     :resources {:font {"F0" (->ref 'font)}}}}
    {:id 'p1   :obj {:type :page :parent (->ref 'mid)}}
    {:id 'p2   :obj {:type :page :parent (->ref 'mid) :media-box [0 0 200 200]}}
    {:id 'p3   :obj {:type :page :parent (->ref 'root)}}
    {:id 'font :obj {:type :font}}]})

(defn- obj-by-id [ctx id]
  (some #(when (= id (:id %)) (:obj %)) (:objects ctx)))

(defn- entries-of-type [ctx t]
  (filter #(= t (:type (:obj %))) (:objects ctx)))

(deftest normalize-flattens-the-tree
  (let [flat (normalize nested)
        root (obj-by-id flat 'root)]
    (testing "exactly one pages node remains, and it's the root"
      (is (= 1 (count (entries-of-type flat :pages))))
      (is (nil? (obj-by-id flat 'mid))))

    (testing "root kids list every leaf in reading order, with a matching count"
      (is (= [(->ref 'p1) (->ref 'p2) (->ref 'p3)] (:kids root)))
      (is (= 3 (:count root))))

    (testing "every leaf is re-parented directly under the root"
      (is (every? #(= (->ref 'root) (:parent (:obj %)))
                  (entries-of-type flat :page))))

    (testing "the root no longer carries the now-materialized inherited attrs"
      (is (not (contains? root :media-box))))))

(deftest normalize-materializes-inherited-attributes
  (let [flat (normalize nested)]
    (testing "a leaf inherits the nearest ancestor's attributes"
      ;; p1 inherits media-box from root and resources from mid
      (is (= [0 0 612 792] (:media-box (obj-by-id flat 'p1))))
      (is (= {:font {"F0" (->ref 'font)}} (:resources (obj-by-id flat 'p1)))))

    (testing "a leaf's own attribute overrides the inherited one"
      (is (= [0 0 200 200] (:media-box (obj-by-id flat 'p2)))))

    (testing "inheritance follows the tree, not object order"
      ;; p3 is under root only, so it gets root's media-box but no resources
      (is (= [0 0 612 792] (:media-box (obj-by-id flat 'p3))))
      (is (not (contains? (obj-by-id flat 'p3) :resources))))))

(deftest pages-query-returns-materialized-leaves-in-order
  (let [ps (pages nested)]
    (is (= 3 (count ps)))
    (is (every? #(= :page (:type %)) ps))
    (is (= [[0 0 612 792] [0 0 200 200] [0 0 612 792]] (mapv :media-box ps)))
    (testing "the query does not mutate the context"
      (is (some #(= 'mid (:id %)) (:objects nested))))))

(deftest normalize-is-idempotent-on-a-flat-tree
  (let [once  (normalize nested)
        twice (normalize once)]
    (is (= (mapv :media-box (pages once))
           (mapv :media-box (pages twice))))
    (is (= (:kids (obj-by-id once 'root)) (:kids (obj-by-id twice 'root))))))

;; A one-page document whose catalog also points at an outline object that no
;; page references — so merge should drop the outline (and the catalog).
(def doc-b
  {:version 2.0
   :objects
   [{:id 'b-cat     :obj {:type :catalog :pages (->ref 'b-root)
                          :outlines (->ref 'b-outline)}}
    {:id 'b-root    :obj {:type :pages :count 1 :kids [(->ref 'b-p1)]}}
    {:id 'b-p1      :obj {:type :page :parent (->ref 'b-root)
                          :media-box [0 0 100 100] :contents (->ref 'b-c1)}}
    {:id 'b-c1      :obj (bytes->stream (.getBytes "q Q" "ISO-8859-1"))}
    {:id 'b-outline :obj {:type :outlines :count 0}}]})

(deftest merge-concatenates-pages-in-order
  (let [merged (merge-pdfs nested doc-b)]
    (testing "all pages from both documents, in argument order"
      (is (= 4 (count (pages merged))))
      (is (= [[0 0 612 792] [0 0 200 200] [0 0 612 792] [0 0 100 100]]
             (mapv :media-box (pages merged)))))

    (testing "one catalog and one pages node survive the join"
      (is (= 1 (count (entries-of-type merged :catalog))))
      (is (= 1 (count (entries-of-type merged :pages)))))

    (testing "every page hangs off the one surviving root"
      ;; a keeps its ids, so a's root is still 'root; b's leaf re-parents onto it
      (is (every? #(= (->ref 'root) (:parent (:obj %)))
                  (entries-of-type merged :page))))

    (testing "catalog-only orphans are dropped"
      (is (empty? (entries-of-type merged :outlines)) "b's orphan outline is gone"))

    (testing "the join is valid end to end (b's content stream came along)"
      (let [re (parse (serialize merged))]
        (is (empty? (:warnings re)) "no dangling refs")
        (is (= 4 (count (pages re))))))))

(deftest merge-is-variadic
  (testing "pages accumulate across every argument (b's ids are freshened, so a
            self-repeat can't collide)"
    (is (= 7 (count (pages (merge-pdfs nested doc-b nested))))))
  (testing "a single argument just yields that document's pages"
    (is (= 3 (count (pages (merge-pdfs nested)))))))

;; End-to-end with real files: merging two parsed PDFs produces a valid document
;; whose page count is the sum, and it round-trips through serialize/re-parse.
(deftest merge-real-documents-round-trips
  (let [a       (parse-file "test/resources/pdfs/object-streams.pdf")
        b       (parse-file "test/resources/pdfs/binary-id.pdf")
        merged  (merge-pdfs a b)
        n       (+ (count (pages a)) (count (pages b)))]
    (is (= n (count (pages merged))) "merged page count is the sum")
    (let [re (parse (serialize merged))]
      (is (empty? (:warnings re)) "the merged file re-parses cleanly")
      (is (= n (count (pages re))) "page count survives a round trip"))))

;; End-to-end: a real multi-page file survives parse -> normalize -> serialize
;; -> re-parse with its page count intact.
(deftest normalize-preserves-a-real-document
  (let [ctx  (parse-file "test/resources/pdfs/object-streams.pdf")
        flat (normalize ctx)
        out  (java.io.File/createTempFile "normalized" ".pdf")]
    (is (= (count (pages ctx)) (count (pages flat)))
        "normalize keeps every page")
    (is (= 1 (count (entries-of-type flat :pages)))
        "the round-tripped tree is flat")
    (save flat (.getPath out))
    (let [re (parse-file (.getPath out))]
      (is (= (count (pages ctx)) (count (pages re)))
          "page count survives a serialize/re-parse round trip"))
    (.delete out)))
