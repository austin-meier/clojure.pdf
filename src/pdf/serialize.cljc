(ns pdf.serialize
  "The `PdfSerializable` / `to-pdf` protocol: Clojure data to PDF syntax strings.
   This namespace stays a leaf. The core types (nil, booleans, numbers, strings,
   keywords, symbols) and the collection catch-all live here; each library record
   (`PdfStream`, `Ref`, `IndirectRef`, `Dimension`, `GlyphString`) extends the
   protocol in its own namespace, which keeps this portable and avoids a
   cross-namespace record reference in a reader-conditional `extend-protocol`."
  (:require
   [clojure.string :as str]
   [pdf.context.name :refer [key->pdf-name]]
   [pdf.bytes :as b]
   [pdf.utils.string :as pstr]))

(defprotocol PdfSerializable
  ;; Serialize to a PDF text format definition
  (to-pdf [x]))

(defn pdf-number
  "Render a number as a PDF numeric literal — the one formatter at the PDF
   boundary. Integers print as themselves; every other number (double, ratio)
   prints as a plain decimal rounded to 4 places with trailing zeros trimmed,
   because PDF reals have no exponent or ratio syntax. Throws on non-finite
   values."
  [n]
  (if (integer? n)
    (str n)
    (let [d (double n)]
      (when-not #?(:clj (Double/isFinite d) :cljs (js/isFinite d))
        (throw (ex-info "PDF reals must be finite" {:value n})))
      (let [r (/ (Math/round (* d 10000.0)) 10000.0)
            s (str r)
            s (if (str/ends-with? s ".0") (subs s 0 (- (count s) 2)) s)]
        (if (or (= s "0") (= s "-0")) "0" s)))))

(defn pdf-name [s]
  (str "/" (str/replace s #"[^!-\)\+-.0-9:;=?@A-Z\[\]_a-z~]"
                        (fn [m] (str "#" (pstr/hex-upper (first (b/ascii->bytes m)) 2))))))

(defn escape-pdf-string
  "Escapes the characters that would terminate or unbalance a PDF literal
   string, plus CR/LF. A raw CR (0x0D) inside a literal string is read back as
   LF by a conforming reader (7.3.4.2), so it must be escaped to survive a
   round trip; LF is escaped too for tidiness. Other bytes are fine raw in a
   Latin-1 chunk."
  [s]
  (str/replace s #"[\\()\r\n]"
               (fn [m]
                 (case m
                   "\r" "\\r"
                   "\n" "\\n"
                   (str "\\" m)))))

(defn- map->pdf [m]
  (str "<< " (str/join " "
                       (mapcat (fn [[k v]]
                                 ;; Keywords and symbols already carry an exact
                                 ;; name (symbols are the escape hatch, e.g.
                                 ;; 'ca); only coerce other key types.
                                 [(to-pdf (if (or (keyword? k) (symbol? k))
                                            k
                                            (keyword (str k))))
                                  (to-pdf v)])
                               m))
       " >>"))

(defn- seq->pdf [v]
  (str "[" (str/join " " (map to-pdf v)) "]"))

(extend-protocol PdfSerializable
  nil
  (to-pdf [_] "null")

  #?(:clj Boolean :cljs boolean)
  (to-pdf [b] (if b "true" "false"))

  #?(:clj Number :cljs number)
  (to-pdf [n] (pdf-number n))

  #?(:clj String :cljs string)
  (to-pdf [s] (str "(" (escape-pdf-string s) ")"))

  #?(:clj clojure.lang.Keyword :cljs cljs.core/Keyword)
  (to-pdf [k] (pdf-name (key->pdf-name k)))

  #?(:clj clojure.lang.Symbol :cljs cljs.core/Symbol)
  (to-pdf [s] (pdf-name (name s)))

  ;; Collections (and anything not otherwise extended) branch here. Records with
  ;; their own impls beat this; a stray record would fall through map? and
  ;; serialize as a dict, same trap the explicit record impls guard against.
  #?(:clj Object :cljs default)
  (to-pdf [x]
    (cond
      (map? x)        (map->pdf x)
      (sequential? x) (seq->pdf x)
      :else (throw (ex-info "Cannot serialize value to PDF" {:value x})))))
