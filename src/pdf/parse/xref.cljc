(ns pdf.parse.xref
  "Cross-reference reading: `startxref` discovery, classic tables (7.5.4), xref
   streams (7.5.8), and the /Prev chain merge.

   The result is `[entries trailer]` where `entries` maps an object number to
   `{:type :in-use :offset n :gen g}`, `{:type :compressed :objstm c :index i}`,
   or `{:type :free}`, and `trailer` is the newest section's dictionary."
  (:require
   [pdf.context.image.unfilter :as unfilter]
   [pdf.parse.lex :as lex]
   [pdf.parse.object :as object]
   [pdf.bytes :as b]
   [pdf.utils.flate :as flate]))

(defn find-startxref
  "The byte offset named by the last `startxref` in the file, or throws. The
   keyword lives near the tail; the token after it is the offset."
  [src]
  (let [len (b/length src)
        sx (lex/find-last-keyword src "startxref" len)]
    (when-not sx
      (throw (ex-info "No startxref found" {})))
    (let [t (lex/next-token src (+ sx (count "startxref")))]
      (when-not (and (= :number (:kind t)) (:int? t))
        (throw (ex-info "startxref is not followed by an integer offset"
                        {:offset sx})))
      (:value t))))

(defn- parse-entries
  "The `cnt` entries of one subsection starting at `pos`, keyed from object
   number `start`. Returns [entries next-pos]. Each entry is `offset gen n|f`
   (three tokens); the lexer absorbs the entry EOL variants."
  [src pos start cnt]
  (loop [i 0, p pos, es {}]
    (if (= i cnt)
      [es p]
      (let [t1 (lex/next-token src p)
            t2 (lex/next-token src (:end t1))
            t3 (lex/next-token src (:end t2))]
        (when-not (and (= :number (:kind t1)) (:int? t1)
                       (= :number (:kind t2)) (:int? t2)
                       (= :word (:kind t3)) (#{"n" "f"} (:value t3)))
          (throw (ex-info "Malformed xref entry" {:offset p})))
        (let [entry (if (= "n" (:value t3))
                      {:type :in-use :offset (:value t1) :gen (:value t2)}
                      {:type :free})]
          (recur (inc i) (:end t3) (assoc es (+ start i) entry)))))))

(defn parse-classic-section
  "One classic xref section at `offset`: the word `xref`, subsections, then the
   word `trailer` and its dict. Returns [entries trailer]."
  [src offset]
  (let [xt (lex/next-token src offset)]
    (when-not (and (= :word (:kind xt)) (= "xref" (:value xt)))
      (throw (ex-info "Expected 'xref' at classic xref offset" {:offset offset})))
    (loop [p (:end xt), entries {}]
      (let [t (lex/next-token src p)]
        (cond
          (and (= :word (:kind t)) (= "trailer" (:value t)))
          [entries (first (object/parse-value src (:end t)))]

          (and (= :number (:kind t)) (:int? t))
          (let [t2 (lex/next-token src (:end t))]
            (when-not (and (= :number (:kind t2)) (:int? t2))
              (throw (ex-info "Malformed xref subsection header" {:offset p})))
            (let [[es np] (parse-entries src (:end t2) (:value t) (:value t2))]
              (recur np (merge entries es))))

          :else
          (throw (ex-info "Malformed xref section" {:offset (:start t)})))))))

(defn- read-be
  "Big-endian unsigned int of `width` bytes from the 0-255 vector `data` at
   `pos`. Width 0 reads nothing and yields 0 (a zero-width xref-stream field)."
  [data pos width]
  (reduce (fn [acc i] (+ (* acc 256) (nth data (+ pos i)))) 0 (range width)))

(defn- inflate-xref
  "Inflate an xref-stream payload and undo any PNG predictor, returning a
   0-255 vector of decoded bytes. Xref streams are always FlateDecode."
  [dict raw]
  (when-not (= :flate-decode (:filter dict))
    (throw (ex-info "Xref stream must be FlateDecode" {:filter (:filter dict)})))
  (let [inflated (b/unsigned-vec (flate/inflate raw))
        dp (:decode-parms dict)
        predictor (or (:predictor dp) 1)]
    (if (>= predictor 10)
      (let [columns (:columns dp)
            rows (quot (count inflated) (inc columns))]
        (unfilter/unfilter inflated columns rows 1 1))
      inflated)))

(defn- decode-xref-stream-entries
  "The entry map for an xref stream (7.5.8): `/W` byte widths, `/Index`
   subsections (default `[0 Size]`), and per-entry type/field decode. A
   `/W[0]` of 0 defaults the type to 1; other zero-width fields default to 0."
  [dict data]
  (let [[w1 w2 w3] (:w dict)
        width (+ w1 w2 w3)
        index (or (:index dict) [0 (:size dict)])]
    (loop [pairs (partition 2 index), pos 0, entries {}]
      (if (empty? pairs)
        entries
        (let [[start cnt] (first pairs)
              [entries' pos']
              (loop [i 0, p pos, es entries]
                (if (= i cnt)
                  [es p]
                  (let [f1 (if (zero? w1) 1 (read-be data p w1))
                        f2 (read-be data (+ p w1) w2)
                        f3 (read-be data (+ p w1 w2) w3)
                        entry (case f1
                                0 {:type :free}
                                1 {:type :in-use :offset f2 :gen f3}
                                2 {:type :compressed :objstm f2 :index f3}
                                (throw (ex-info "Unknown xref stream entry type"
                                                {:type f1})))]
                    (recur (inc i) (+ p width) (assoc es (+ start i) entry)))))]
          (recur (rest pairs) pos' entries'))))))

(defn parse-xref-stream
  "An xref stream at `offset` (`num gen obj << >> stream`). Returns
   [entries trailer], where the stream dict doubles as the trailer."
  [src offset]
  (let [[{:keys [obj]} _] (object/parse-indirect src offset {})
        dict (:dict obj)]
    [(decode-xref-stream-entries dict (inflate-xref dict (:bytes obj)))
     dict]))

(defn- parse-section
  "One xref section at `offset`, dispatching on the first token: the word
   `xref` is a classic table, an integer opens an xref stream."
  [src offset]
  (let [t (lex/next-token src offset)]
    (cond
      (and (= :word (:kind t)) (= "xref" (:value t)))
      (parse-classic-section src offset)
      (and (= :number (:kind t)) (:int? t))
      (parse-xref-stream src offset)
      :else
      (throw (ex-info "Expected an xref table or xref stream" {:offset offset})))))

(defn- section-entries
  "The entries for one update, folding a classic section's /XRefStm in with
   higher precedence than the classic table itself (7.5.8.4 / Annex H.7: a
   hybrid's classic table carries free placeholders for the compressed objects
   the stream really defines, so the stream must win for them)."
  [src [entries trailer]]
  (if-let [stm-off (:x-ref-stm trailer)]
    (merge entries (first (parse-section src stm-off)))
    entries))

(defn read-xref
  "Follow the /Prev chain from the newest section to the oldest, merging
   newest-first (first-seen object number wins). A classic section's /XRefStm
   is folded into that section (Annex H.7). Returns [entries trailer] with the
   newest trailer. Throws on a /Prev cycle."
  [src]
  (loop [offset (find-startxref src), visited #{}, merged {}, newest nil]
    (when (contains? visited offset)
      (throw (ex-info "Cyclic /Prev chain in xref" {:offset offset})))
    (let [[_ trailer :as section] (parse-section src offset)
          merged' (merge (section-entries src section) merged)  ;; existing (newer) wins
          newest' (or newest trailer)]
      (if-let [prev (:prev trailer)]
        (recur prev (conj visited offset) merged' newest')
        [merged' newest']))))
