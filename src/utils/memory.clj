(ns utils.memory
  (:require
   [clojure.java.io :as io]))

;; Java bindings
(defn file->bytes [file-path]
  (-> file-path
      io/file
      .toPath
      java.nio.file.Files/readAllBytes
      vec))


(def byte-count
  {:char 1
   :u8 1
   :u16 2
   :u24 3
   :u32 4
   :u64 8
   :u128 16
   :i8 1
   :i16 2
   :i24 3
   :i32 4
   :i64 8
   :i128 16
   :f32 4
   :f64 8})

(defn parse-type
  [type bytes]
  (println "Parsing type:" type "from bytes:" bytes)
  (case type
    :char (char (nth bytes 0))
    :u8   (nth bytes 0)
    :i8   (let [b (nth bytes 0)] (if (>= b 128) (- b 256) b))
    :u16  (reduce (fn [acc b] (+ (bit-shift-left acc 8) b)) 0 bytes)
    :i16  (let [val (reduce (fn [acc b] (+ (bit-shift-left acc 8) b)) 0 bytes)]
            (if (>= val 32768) (- val 65536) val))
    :u24  (reduce (fn [acc b] (+ (bit-shift-left acc 8) b)) 0 bytes)
    :u32  (reduce (fn [acc b] (+ (bit-shift-left acc 8) b)) 0 bytes)
    :i32  (let [val (reduce (fn [acc b] (+ (bit-shift-left acc 8) b)) 0 bytes)]
            (if (>= val 2147483648) (- val 4294967296) val))
    :i64  (let [val (reduce (fn [acc b] (+ (bit-shift-left acc 8) b)) 0 bytes)]
            (if (>= val 9223372036854775808) (- val 18446744073709551616) val))
    :f32  (let [int-val (reduce (fn [acc b] (+ (bit-shift-left acc 8) b)) 0 bytes)]
            (Float/intBitsToFloat int-val))
    :f64  (let [long-val (reduce (fn [acc b] (+ (bit-shift-left acc 8) b)) 0 bytes)]
            (Double/longBitsToDouble long-val))
    nil))

(defn resolve-type
  [type lookup]
  (cond
    (number? type) type
    (keyword? type) (resolve-type (lookup type) lookup)
    (vector? type) (mapv #(resolve-type % lookup) type)))

(defn resolve-type-lengths
  [type-map]
  (let [merged (merge type-map byte-count)]
    (reduce-kv
     (fn [m k v]
       (assoc m k (resolve-type v merged)))
     {}
     merged)))


(defn parse-section
  [ctx section]
  (reduce
   (fn [ctx [field-key type-key]]
     (let [bytes (subvec (get-in ctx [:ctx :bytes])
                         (get-in ctx [:ctx :offset])
                         (+ (get-in ctx [:ctx :offset])
                            (get-in ctx [:ctx :types type-key])))]
       (-> ctx
           (assoc-in [:data field-key]
                     (if (:raw-bytes? ctx)
                       bytes
                       (parse-type type-key bytes))))))
   {:ctx ctx
    :taken []
    :data {}}
   (:fields section)))

(defn repeatedly-parse-section
  [ctx section]
  (reduce
   (fn [ctx _]
     (let [parsed (parse-section ctx section)
           taken-bytes (reduce + (map #(get-in ctx [:ctx :types (second %)]) (:fields section)))]
       (-> ctx
           (update :data conj (:data parsed))
           (update-in [:ctx :offset] + taken-bytes))))
   {:ctx ctx
    :data []}
   (range (if (fn? (:repeat section))
            ((:repeat section) ctx)
            (:repeat section)))))

(defn parse-fn-for-section
  [section ctx]
  (cond
    (:repeat section) repeatedly-parse-section
    :else parse-section))

(defn new-parse-ctx
  [byte-array types]
  {:bytes byte-array
   :types types
   :offset 0
   :data {}})

(defn parse-binary
  [byte-array layout type-aliases]
  (let
   [ctx (new-parse-ctx byte-array (resolve-type-lengths type-aliases))]
    (reduce
     (fn [ctx section]
       (assoc-in ctx [:data (:section section)]
                 (:data ((parse-fn-for-section section ctx) ctx section))))
     ctx layout)))

(defn parse-binary-file
  [input layout type-aliases]
  (parse-binary (file->bytes input) layout type-aliases))