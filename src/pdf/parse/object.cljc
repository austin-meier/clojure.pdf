(ns pdf.parse.object
  "Recursive descent over the lexer. Everything returns `[value next-pos]`,
   matching the `[ctx ref]` convention elsewhere in the codebase.

   Values parse to the authoring shape directly: dicts to maps keyed through
   `pdf-name->key`, arrays to vectors, `n g R` to an `IndirectRef` (rewritten to
   a symbolic `Ref` by `pdf.parse.core` once the id table exists), and streams
   to `PdfStream` records holding their payload bytes exactly as stored."
  (:require
   [pdf.context.name :refer [pdf-name->key]]
   [pdf.context.stream :as stream]
   [pdf.file.xref :as xref]
   [pdf.parse.lex :as lex]
   [pdf.bytes :as b]))

(declare parse-value)

(defn- parse-number-or-ref
  "An integer may open an `n g R` reference: look ahead two tokens and fold the
   three into an `IndirectRef` when they're a non-negative int then the word
   `R`. Otherwise the number stands alone and the lookahead is discarded."
  [src {:keys [value int? end]}]
  (if (and int? (>= value 0))
    (let [t2 (lex/next-token src end)
          t3 (when (and (= :number (:kind t2)) (:int? t2) (>= (:value t2) 0))
               (lex/next-token src (:end t2)))]
      (if (and t3 (= :word (:kind t3)) (= "R" (:value t3)))
        [(xref/->IndirectRef value (:value t2)) (:end t3)]
        [value end]))
    [value end]))

(defn- parse-array
  "Values until `]`, into a vector. `pos` is just past the `[`. Nulls stay;
   only dict entries drop on null."
  [src pos]
  (loop [p pos, acc []]
    (let [t (lex/next-token src p)]
      (case (:kind t)
        :array-close [acc (:end t)]
        :eof (throw (ex-info "Unterminated array" {:offset pos}))
        (let [[v np] (parse-value src p)]
          (recur np (conj acc v)))))))

(defn- parse-dict
  "Name/value pairs until `>>`, into a map. `pos` is just past the `<<`. Keys go
   through `pdf-name->key`; a non-name key is an error. Entries whose value is
   null are dropped (7.3.7)."
  [src pos]
  (loop [p pos, m {}]
    (let [t (lex/next-token src p)]
      (case (:kind t)
        :dict-close [m (:end t)]
        :name (let [[v np] (parse-value src (:end t))]
                (recur np (if (nil? v) m (assoc m (pdf-name->key (:value t)) v))))
        (throw (ex-info "Expected a name key or >> in dictionary"
                        {:offset (:start t) :kind (:kind t)}))))))

(defn parse-value
  "Any single PDF object starting at `pos`; returns `[value next-pos]`."
  [src pos]
  (let [t (lex/next-token src pos)]
    (case (:kind t)
      :number     (parse-number-or-ref src t)
      :string     [(:value t) (:end t)]
      :hex-string [(:value t) (:end t)]
      :name       [(pdf-name->key (:value t)) (:end t)]
      :array-open (parse-array src (:end t))
      :dict-open  (parse-dict src (:end t))
      :word (case (:value t)
              "true"  [true (:end t)]
              "false" [false (:end t)]
              "null"  [nil (:end t)]
              (throw (ex-info "Unexpected keyword in value position"
                              {:offset (:start t) :word (:value t)})))
      (throw (ex-info "Unexpected token in value position"
                      {:offset (:start t) :kind (:kind t)})))))

(defn- resolve-length
  "The stream length: a plain int in the dict, or the target of an indirect
   `/Length` via `(:resolve-ref opts)` (a plain integer only), or nil."
  [dict opts]
  (let [l (:length dict)]
    (cond
      (and (integer? l) (>= l 0)) l
      (and (xref/indirect-ref? l) (:resolve-ref opts))
      (let [r ((:resolve-ref opts) l)]
        (when (and (integer? r) (>= r 0)) r))
      :else nil)))

(defn- trim-trailing-eol
  "Exclusive end after trimming one EOL (CRLF, LF, or lone CR) immediately
   before `end`. The spec puts an EOL between the payload and `endstream`."
  [src start end]
  (cond
    (<= end start) end
    (= (b/ubyte src (dec end)) 10)
    (if (and (> (dec end) start) (= (b/ubyte src (- end 2)) 13)) (- end 2) (dec end))
    (= (b/ubyte src (dec end)) 13) (dec end)
    :else end))

(defn- scan-payload
  "The sanctioned /Length-mismatch fallback: scan forward for the first
   `endstream` at a delimiter boundary, trim one trailing EOL off the payload,
   and record a warning. Returns [payload endstream-start warning]."
  [src after obj-num]
  (let [es (lex/find-next-keyword src "endstream" after)]
    (when-not es
      (throw (ex-info "No endstream found for stream" {:obj-num obj-num})))
    [(b/slice src after (trim-trailing-eol src after es))
     es
     {:type :length-mismatch :obj-num obj-num}]))

(defn- parse-stream
  "A stream object (7.3.8): `stream` EOL, payload, `endstream`, `endobj`. Uses
   /Length when it verifies (payloads can legally contain the bytes
   `endstream`), else the fallback scan. Strips only /Length from the dict;
   the emitter recomputes it."
  [src dict obj-num gen stream-tok opts]
  (let [after (lex/skip-eol src (:end stream-tok))
        len (resolve-length dict opts)
        [payload es-start warning]
        (or (when len
              (let [pend (+ after len)
                    et (lex/next-token src (lex/skip-eol src pend))]
                (when (and (= :word (:kind et)) (= "endstream" (:value et)))
                  [(b/slice src after pend) (:start et) nil])))
            (scan-payload src after obj-num))
        nt (lex/next-token src (+ es-start (count "endstream")))]
    (when-not (and (= :word (:kind nt)) (= "endobj" (:value nt)))
      (throw (ex-info "Expected 'endobj' after stream"
                      {:obj-num obj-num :offset (:start nt)})))
    [(cond-> {:obj-num obj-num :gen gen
              :obj (stream/->PdfStream (dissoc dict :length) payload)}
       warning (assoc :warnings [warning]))
     (:end nt)]))

(defn parse-indirect
  "The indirect object at `pos`: `num gen obj <value> [stream ...] endobj`.
   Returns `[{:obj-num n :gen g :obj value :warnings [...]} next-pos]`; only
   streams carry warnings. A missing/mismatched header or endobj throws, with no
   repair beyond the stream fallback scan. `opts` may carry `:resolve-ref` for
   indirect /Length."
  [src pos opts]
  (let [t1 (lex/next-token src pos)
        t2 (lex/next-token src (:end t1))
        t3 (lex/next-token src (:end t2))]
    (when-not (and (= :number (:kind t1)) (:int? t1)
                   (= :number (:kind t2)) (:int? t2)
                   (= :word (:kind t3)) (= "obj" (:value t3)))
      (throw (ex-info "Expected 'num gen obj' object header" {:offset pos})))
    (let [obj-num (:value t1)
          gen (:value t2)
          [value vpos] (parse-value src (:end t3))
          nt (lex/next-token src vpos)]
      (if (and (= :word (:kind nt)) (= "stream" (:value nt))
               (map? value) (not (record? value)))
        (parse-stream src value obj-num gen nt opts)
        (do
          (when-not (and (= :word (:kind nt)) (= "endobj" (:value nt)))
            (throw (ex-info "Expected 'endobj'"
                            {:offset (:start nt) :obj-num obj-num})))
          [{:obj-num obj-num :gen gen :obj value} (:end nt)])))))
