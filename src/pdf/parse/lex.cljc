(ns pdf.parse.lex
  (:require
   [clojure.string :as str]
   [pdf.bytes :as b]))

;; spec 7.2.3: whitespace and delimiter byte values. Everything else is a
;; regular character (the body of names, numbers, and words).
(def ^:private whitespace #{0 9 10 12 13 32})
(def ^:private delimiter #{40 41 60 62 91 93 123 125 47 37})

(defn- regular? [c]
  (not (or (whitespace c) (delimiter c))))

(defn- parse-int* [s]
  #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10)))

(defn- parse-float* [s]
  #?(:clj (Double/parseDouble s) :cljs (js/parseFloat s)))

(defn- bytes->latin1
  "A Latin-1 string from a seq of 0-255 byte values (1 byte = 1 char)."
  [byte-coll]
  (apply str (map char byte-coll)))

(defn- hex-val
  "0-15 for an ASCII hex-digit byte, or nil."
  [c]
  (cond
    (<= 48 c 57)  (- c 48)        ;; 0-9
    (<= 65 c 70)  (- c 55)        ;; A-F
    (<= 97 c 102) (- c 87)        ;; a-f
    :else nil))

(defn- to-eol
  "First offset at or after `pos` that is a CR/LF or end of input."
  [src pos]
  (let [len (b/length src)]
    (loop [p pos]
      (if (or (>= p len) (#{10 13} (b/ubyte src p)))
        p
        (recur (inc p))))))

(defn- skip-ws
  "Advance past whitespace and `%` comments; returns the next content offset."
  [src pos]
  (let [len (b/length src)]
    (loop [p pos]
      (if (>= p len)
        p
        (let [c (b/ubyte src p)]
          (cond
            (whitespace c) (recur (inc p))
            (= c 37)       (recur (to-eol src (inc p)))  ;; % comment
            :else          p))))))

(defn- read-regular
  "First offset at or after `pos` that is not a regular character."
  [src pos]
  (let [len (b/length src)]
    (loop [p pos]
      (if (and (< p len) (regular? (b/ubyte src p)))
        (recur (inc p))
        p))))

;; Number grammar (7.2.4/7.3.3): optional sign, then digits with an optional
;; dot, or a leading-dot form. Accepts 4., .5, -.002, +17; rejects words and
;; PDF-illegal exponents.
(def ^:private number-re #"[+-]?(?:\d+\.?\d*|\.\d+)")

(defn- parse-number
  "[value int?] for a numeric token string, or nil if it isn't a number."
  [s]
  (when (re-matches number-re s)
    (let [int? (not (str/includes? s "."))]
      [(if int? (parse-int* s) (parse-float* s)) int?])))

(defn- lex-regular
  "A run of regular characters: a number when it parses as one, else a word."
  [src pos]
  (let [end (read-regular src pos)
        s (b/ascii src pos (- end pos))]
    (if-let [[v int?] (parse-number s)]
      {:kind :number :value v :int? int? :start pos :end end}
      {:kind :word :value s :start pos :end end})))

(defn- lex-name
  "A name (7.3.5) starting at the `/`. `#XX` hex escapes are decoded; `/` alone
   is the empty name."
  [src slash]
  (let [end (read-regular src (inc slash))
        decoded (loop [p (inc slash), acc []]
                  (if (>= p end)
                    acc
                    (let [c (b/ubyte src p)
                          h1 (when (and (= c 35) (< (+ p 2) end))
                               (hex-val (b/ubyte src (inc p))))
                          h2 (when h1 (hex-val (b/ubyte src (+ p 2))))]
                      (if h2
                        (recur (+ p 3) (conj acc (+ (* h1 16) h2)))
                        (recur (inc p) (conj acc c))))))]
    {:kind :name :value (bytes->latin1 decoded) :start slash :end end}))

(defn- read-escape
  "At `p` (the byte after a backslash inside a literal string), returns
   [next-pos byte-or-nil]. A nil byte means the escape produced no character
   (a line continuation)."
  [src p len]
  (let [c (b/ubyte src p)]
    (cond
      (= c 110) [(inc p) 10]   ;; \n -> LF
      (= c 114) [(inc p) 13]   ;; \r -> CR
      (= c 116) [(inc p) 9]    ;; \t
      (= c 98)  [(inc p) 8]    ;; \b
      (= c 102) [(inc p) 12]   ;; \f
      (<= 48 c 55)             ;; octal \d \dd \ddd, value mod 256
      (loop [q p, n 0, k 0]
        (if (and (< k 3) (< q len) (<= 48 (b/ubyte src q) 55))
          (recur (inc q) (+ (* n 8) (- (b/ubyte src q) 48)) (inc k))
          [q (mod n 256)]))
      (or (= c 10) (= c 13))   ;; backslash before EOL: both vanish (CRLF as one)
      [(if (and (= c 13) (< (inc p) len) (= (b/ubyte src (inc p)) 10))
         (+ p 2) (inc p))
       nil]
      :else [(inc p) c])))     ;; backslash before anything else: drop backslash

(defn- lex-literal-string
  "A literal string (7.3.4.2) starting at `(`. Nested parens balance, escapes
   and octals decode, a backslash-EOL continues the line, and a raw EOL
   normalizes to a single LF."
  [src start]
  (let [len (b/length src)]
    (loop [p (inc start), depth 1, acc []]
      (if (>= p len)
        (throw (ex-info "Unterminated literal string" {:offset start}))
        (let [c (b/ubyte src p)]
          (cond
            (= c 92)                                  ;; backslash escape
            (if (>= (inc p) len)
              (throw (ex-info "Unterminated literal string" {:offset start}))
              (let [[np bt] (read-escape src (inc p) len)]
                (recur np depth (if bt (conj acc bt) acc))))

            (= c 40)                                  ;; nested (
            (recur (inc p) (inc depth) (conj acc c))

            (= c 41)                                  ;; )
            (if (= depth 1)
              {:kind :string :value (bytes->latin1 acc) :start start :end (inc p)}
              (recur (inc p) (dec depth) (conj acc c)))

            (or (= c 10) (= c 13))                    ;; raw EOL -> single LF
            (let [np (if (and (= c 13) (< (inc p) len) (= (b/ubyte src (inc p)) 10))
                       (+ p 2) (inc p))]
              (recur np depth (conj acc 10)))

            :else (recur (inc p) depth (conj acc c))))))))

(defn- lex-hex-string
  "A hex string (7.3.4.3) starting at `<`. Whitespace is ignored between
   digits; an odd final digit is padded with a trailing 0."
  [src start]
  (let [len (b/length src)]
    (loop [p (inc start), hi nil, acc []]
      (if (>= p len)
        (throw (ex-info "Unterminated hex string" {:offset start}))
        (let [c (b/ubyte src p)]
          (cond
            (= c 62)                                  ;; >
            {:kind :hex-string
             :value (bytes->latin1 (if hi (conj acc (* hi 16)) acc))
             :start start :end (inc p)}

            (whitespace c) (recur (inc p) hi acc)

            :else
            (let [h (hex-val c)]
              (when (nil? h)
                (throw (ex-info "Invalid byte in hex string" {:offset p :byte c})))
              (if hi
                (recur (inc p) nil (conj acc (+ (* hi 16) h)))
                (recur (inc p) h acc)))))))))

(defn next-token
  "The token starting at or after `pos`, as
   `{:kind ... :value ... :start s :end e}` where `:end` is where the next scan
   begins. Whitespace and `%` comments are skipped first. Returns `:eof` at end
   of input. The lexer classifies but does not validate words (`obj`, `R`, ...);
   the parser interprets them."
  [src pos]
  (let [p (skip-ws src pos)
        len (b/length src)]
    (if (>= p len)
      {:kind :eof :start p :end p}
      (let [c (b/ubyte src p)]
        (cond
          (= c 40) (lex-literal-string src p)         ;; (
          (= c 47) (lex-name src p)                   ;; /
          (= c 91) {:kind :array-open  :start p :end (inc p)}   ;; [
          (= c 93) {:kind :array-close :start p :end (inc p)}   ;; ]
          (= c 60)                                    ;; < : dict-open or hex
          (if (and (< (inc p) len) (= (b/ubyte src (inc p)) 60))
            {:kind :dict-open :start p :end (+ p 2)}
            (lex-hex-string src p))
          (= c 62)                                    ;; > : must be >>
          (if (and (< (inc p) len) (= (b/ubyte src (inc p)) 62))
            {:kind :dict-close :start p :end (+ p 2)}
            (throw (ex-info "Unexpected '>' (not '>>')" {:offset p})))
          (regular? c) (lex-regular src p)
          :else (throw (ex-info "Unexpected delimiter"
                                {:offset p :byte c :char (char c)})))))))

(defn skip-eol
  "Consume one end-of-line at `pos` (CRLF, LF, or a lone CR), returning the
   next offset. Used after the `stream` keyword (7.3.8.1); the spec allows only
   CRLF/LF there, this is lenient and also accepts a bare CR."
  [src pos]
  (let [len (b/length src)]
    (if (>= pos len)
      pos
      (let [c (b/ubyte src pos)]
        (cond
          (= c 10) (inc pos)                          ;; LF
          (and (= c 13) (< (inc pos) len) (= (b/ubyte src (inc pos)) 10)) (+ pos 2)  ;; CRLF
          (= c 13) (inc pos)                          ;; lone CR
          :else pos)))))

(defn- non-regular-edge?
  "True when offset `p` is off either end of the source or holds a
   non-regular byte, i.e. a word boundary."
  [src p len]
  (or (< p 0) (>= p len) (not (regular? (b/ubyte src p)))))

(defn- keyword-at?
  "True when the bytes of `word` sit at offset `p` bounded by whitespace,
   delimiters, or the file edges."
  [src wbytes wlen len p]
  (and (non-regular-edge? src (dec p) len)
       (non-regular-edge? src (+ p wlen) len)
       (every? (fn [i] (= (nth wbytes i) (b/ubyte src (+ p i))))
               (range wlen))))

(defn find-last-keyword
  "The start offset of the last occurrence of the bare word `word` within
   src[0, from), delimited by whitespace/delimiters or the file edges, or nil.
   Used for `startxref` discovery near the file tail."
  [src word from]
  (let [wbytes (b/ascii->bytes word)
        wlen (count word)
        len (b/length src)]
    (loop [p (- (min from len) wlen)]
      (cond
        (< p 0) nil
        (keyword-at? src wbytes wlen len p) p
        :else (recur (dec p))))))

(defn find-next-keyword
  "The start offset of the first occurrence of the bare word `word` at or after
   `from`, delimited by whitespace/delimiters or the file edges, or nil. Used to
   scan for `endstream` when a stream's /Length can't be trusted."
  [src word from]
  (let [wbytes (b/ascii->bytes word)
        wlen (count word)
        len (b/length src)]
    (loop [p (max 0 from)]
      (cond
        (> (+ p wlen) len) nil
        (keyword-at? src wbytes wlen len p) p
        :else (recur (inc p))))))
