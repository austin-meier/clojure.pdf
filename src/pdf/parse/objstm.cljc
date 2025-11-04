(ns pdf.parse.objstm
  "Object streams (7.5.7): a container stream holding several objects. Its
   header is `/N` integer pairs `obj-num offset`, and each object is a bare
   value (no `obj`/`endobj` wrapper, never a stream, gen 0) at `/First +
   offset`. The container itself is an ordinary type-1 object; `pdf.parse.core`
   loads it, then pulls compressed objects out of it."
  (:require
   [pdf.parse.lex :as lex]
   [pdf.parse.object :as object]
   [pdf.bytes :as b]
   [pdf.utils.flate :as flate]))

(defn- inflate-payload
  [dict raw]
  (when-not (= :flate-decode (:filter dict))
    (throw (ex-info "Object stream must be FlateDecode" {:filter (:filter dict)})))
  (b/from-unsigned (b/unsigned-vec (flate/inflate raw))))

(defn extract
  "Parse an object-stream container into `{:nums [obj-num ...] :values [v ...]}`
   in stream (index) order. The caller memoizes so one container inflates once."
  [stream]
  (let [dict (:dict stream)
        n (:n dict)
        first-off (:first dict)
        data (inflate-payload dict (:bytes stream))
        pairs (loop [i 0, p 0, acc []]
                (if (= i n)
                  acc
                  (let [t1 (lex/next-token data p)
                        t2 (lex/next-token data (:end t1))]
                    (when-not (and (= :number (:kind t1)) (:int? t1)
                                   (= :number (:kind t2)) (:int? t2))
                      (throw (ex-info "Malformed object stream header" {:index i})))
                    (recur (inc i) (:end t2) (conj acc [(:value t1) (:value t2)])))))]
    {:nums (mapv first pairs)
     :values (mapv (fn [[_ rel]] (first (object/parse-value data (+ first-off rel))))
                   pairs)}))
