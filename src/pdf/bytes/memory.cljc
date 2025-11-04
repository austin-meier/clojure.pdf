(ns pdf.bytes.memory
  "Declarative, bi-directional binary layouts (parse + emit)

   A *record* is a vector of [field-key type] pairs read/written in order. A
   *layout* is a vector of section maps composing records into a whole
   structure:

     {:section :header                 ; key the section's data lives under
      :fields  [[:num-tables :u16] ...]
      :repeat  [:header :num-tables]   ; optional: parse as a vector of rows
      :at      1024}                   ; optional: absolute seek before reading

   :repeat and :at take a number, a path into the data parsed so far (a
   keyword or a get-in vector), or a function of that data so layouts whose
   counts and offsets live in earlier sections stay plain printable data. A
   field type is a primitive keyword, a fixed-size array [type n], or an alias
   resolved through the :types map in opts (e.g. {:fword :i16}).

   Emitting walks the same layout in reverse: repeated sections write every
   row present in the data (count fields are ordinary fields, so keeping them
   consistent is the caller's job) and :at pads with zeros up to the target
   offset."
  (:require
   [pdf.bytes :as b]))

(def primitives
  {:u8      {:size 1 :read b/u8      :write b/u8->bytes}
   :i8      {:size 1 :read b/i8      :write b/i8->bytes}
   :u16     {:size 2 :read b/u16     :write b/u16->bytes}
   :i16     {:size 2 :read b/i16     :write b/i16->bytes}
   :u24     {:size 3 :read b/u24     :write b/u24->bytes}
   :u32     {:size 4 :read b/u32     :write b/u32->bytes}
   :i32     {:size 4 :read b/i32     :write b/i32->bytes}
   :u64     {:size 8 :read b/u64     :write b/u64->bytes}
   :i64     {:size 8 :read b/i64     :write b/i64->bytes}
   :fixed   {:size 4 :read b/fixed   :write b/fixed->bytes}
   :f2dot14 {:size 2 :read b/f2dot14 :write b/f2dot14->bytes}
   :tag     {:size 4 :read b/tag     :write b/ascii->bytes}})

(defn- resolve-type
  [types t]
  (cond
    (contains? primitives t) t
    (contains? types t)      (recur types (get types t))
    :else (throw (ex-info "Unknown layout type" {:type t}))))

(defn type-size
  "Byte size of a field type (primitive, alias, or [type n] array)."
  [types t]
  (if (vector? t)
    (let [[et n] t] (* n (type-size types et)))
    (:size (primitives (resolve-type types t)))))

(defn- read-value
  [src p types t]
  (if (vector? t)
    (let [[et n] t
          size (type-size types et)]
      (mapv #(read-value src (+ p (* % size)) types et) (range n)))
    ((:read (primitives (resolve-type types t))) src p)))

(defn- write-value
  [types t v]
  (if (vector? t)
    (let [[et n] t]
      (when (not= n (count v))
        (throw (ex-info "Value does not fit its type" {:type t :value v})))
      (into [] (mapcat #(write-value types et %)) v))
    (let [{:keys [size write]} (primitives (resolve-type types t))
          out (write v)]
      ;; Only variable-width values (a :tag string of the wrong length) can
      ;; mismatch, but a silent mismatch desyncs every later offset.
      (when (not= size (count out))
        (throw (ex-info "Value does not fit its type" {:type t :value v})))
      out)))

(defn- resolve-ref
  "A :repeat/:at spec against the data so far: a number stands for itself, a
   keyword or vector is a get-in path, a fn is called with the data."
  [spec data]
  (cond
    (number? spec)  spec
    (keyword? spec) (get data spec)
    (vector? spec)  (get-in data spec)
    (fn? spec)      (spec data)))

(defn- parse-fields
  [src p types fields]
  (reduce (fn [[row p] [field-key t]]
            [(assoc row field-key (read-value src p types t))
             (+ p (type-size types t))])
          [{} p]
          fields))

(defn parse-record
  "Parse one record (a vector of [field-key type] pairs) at `:offset`,
   returning the field map."
  ([src fields] (parse-record src fields {}))
  ([src fields {:keys [offset types] :or {offset 0 types {}}}]
   (first (parse-fields src offset types fields))))

(defn parse
  "Parse a layout map against a byte source, returning a map of section key ->
   field map (or vector of field maps for :repeat sections). Sections read
   sequentially from `:offset` unless they carry :at."
  ([src layout] (parse src layout {}))
  ([src layout {:keys [offset types] :or {offset 0 types {}}}]
   (first
    (reduce
     (fn [[data p] {:keys [section fields at] rep :repeat}]
       (let [p (if at (resolve-ref at data) p)]
         (if rep
           (reduce (fn [[data p] _]
                     (let [[row p'] (parse-fields src p types fields)]
                       [(update data section conj row) p']))
                   [(assoc data section []) p]
                   (range (resolve-ref rep data)))
           (let [[row p'] (parse-fields src p types fields)]
             [(assoc data section row) p']))))
     [{} offset]
     layout))))

(defn- emit-fields
  [types fields row]
  (into [] (mapcat (fn [[field-key t]] (write-value types t (get row field-key)))) fields))

(defn emit-record
  "Emit one record's fields from `row` as a platform byte source."
  ([row fields] (emit-record row fields {}))
  ([row fields {:keys [types] :or {types {}}}]
   (b/from-unsigned (emit-fields types fields row))))

(defn- pad-to
  [out target]
  (when (< target (count out))
    (throw (ex-info "Layout :at seeks backward while emitting"
                    {:at target :written (count out)})))
  (into out (repeat (- target (count out)) 0)))

(defn emit
  "Emit `data` back through a layout as a platform byte source: the inverse of
   `parse`. Repeated sections write every row present in the data; :at pads
   with zeros up to the target offset (seeking backward throws)."
  ([data layout] (emit data layout {}))
  ([data layout {:keys [types] :or {types {}}}]
   (b/from-unsigned
    (reduce
     (fn [out {:keys [section fields at] rep :repeat}]
       (let [out (if at (pad-to out (resolve-ref at data)) out)
             rows (if rep (get data section) [(get data section)])]
         (into out (mapcat #(emit-fields types fields %)) rows)))
     []
     layout))))
