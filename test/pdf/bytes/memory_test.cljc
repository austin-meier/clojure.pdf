(ns pdf.bytes.memory-test
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])
   [pdf.bytes :as b]
   [pdf.bytes.memory :as mem]))

(defn bs
  "A platform byte source from a seq of 0-255 values."
  [coll]
  #?(:clj  (byte-array (map unchecked-byte coll))
     :cljs (js/Uint8Array. (clj->js coll))))

(def point-fields
  [[:tag :tag]
   [:x   :i16]
   [:y   :i16]])

(def point-bytes [0x70 0x6F 0x69 0x6E 0x00 0x2A 0xFF 0xFF])

(deftest record-parsing
  (testing "A record reads its fields in order from the offset"
    (is (= {:tag "poin" :x 42 :y -1}
           (mem/parse-record (bs point-bytes) point-fields)))
    (is (= {:x 42 :y -1}
           (mem/parse-record (bs (into [0 0] point-bytes))
                             [[:x :i16] [:y :i16]]
                             {:offset 6}))))

  (testing "Array types read as vectors, aliases resolve through :types"
    (is (= {:pad [1 2 3] :w -2}
           (mem/parse-record (bs [1 2 3 0xFF 0xFE])
                             [[:pad [:u8 3]] [:w :fword]]
                             {:types {:fword :i16}}))))

  (testing "An unknown type throws"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (mem/parse-record (bs [0]) [[:x :nope]])))))

(def counted-layout
  [{:section :header
    :fields  [[:count :u8]]}
   {:section :rows
    :repeat  [:header :count]
    :fields  [[:v :u16]]}])

(deftest layout-parsing
  (testing "A :repeat count can come from an earlier section"
    (is (= {:header {:count 2} :rows [{:v 1} {:v 512}]}
           (mem/parse (bs [2 0x00 0x01 0x02 0x00]) counted-layout))))

  (testing ":repeat also takes a number or a function of the data"
    (let [src (bs [0x00 0x01 0x00 0x02])]
      (is (= [{:v 1} {:v 2}]
             (:rows (mem/parse src [{:section :rows :repeat 2 :fields [[:v :u16]]}]))
             (:rows (mem/parse src [{:section :rows
                                     :repeat (fn [_] 2)
                                     :fields [[:v :u16]]}]))))))

  (testing ":at seeks to an absolute offset before a section"
    (is (= {:a {:v 1} :b {:v 4}}
           (mem/parse (bs [0 1 9 9 4]) [{:section :a :fields [[:v :u16]]}
                                        {:section :b :at 4 :fields [[:v :u8]]}])))))

(deftest emitting
  (testing "emit-record is the inverse of parse-record"
    (is (= point-bytes
           (b/unsigned-vec (mem/emit-record {:tag "poin" :x 42 :y -1} point-fields)))))

  (testing "emit is the inverse of parse for a counted layout"
    (let [data {:header {:count 3} :rows [{:v 7} {:v 8} {:v 9}]}]
      (is (= data (mem/parse (mem/emit data counted-layout) counted-layout)))))

  (testing ":at pads forward with zeros and refuses to seek backward"
    (is (= [5 0 0 0 6]
           (b/unsigned-vec (mem/emit {:a {:v 5} :b {:v 6}}
                                   [{:section :a :fields [[:v :u8]]}
                                    {:section :b :at 4 :fields [[:v :u8]]}]))))
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (mem/emit {:a {:v 5} :b {:v 6}}
                           [{:section :a :fields [[:v :u16]]}
                            {:section :b :at 1 :fields [[:v :u8]]}]))))

  (testing "A tag that is not exactly 4 bytes throws instead of desyncing"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (mem/emit-record {:tag "abc"} [[:tag :tag]]))))

  (testing "An array value with the wrong count throws instead of desyncing"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (mem/emit-record {:pad [1 2]} [[:pad [:u8 3]]])))))

(deftest type-sizes
  (is (= 8 (mem/type-size {} :i64)))
  (is (= 6 (mem/type-size {} [:i16 3])))
  (is (= 4 (mem/type-size {:fixed-alias :fixed} :fixed-alias))))
