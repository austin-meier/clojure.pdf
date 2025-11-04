(ns pdf.parse.lex-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.parse.lex :as lex]
   [pdf.bytes :as b]))

(defn- src
  "A byte source from a Latin-1 string (1 char = 1 byte)."
  [s]
  (b/from-unsigned (b/ascii->bytes s)))

(defn- tok
  "The first token of `s`, with positional keys dropped for value asserts."
  [s]
  (dissoc (lex/next-token (src s) 0) :start :end))

(deftest numbers
  (testing "integers carry :int? true"
    (is (= {:kind :number :value 42 :int? true} (tok "42")))
    (is (= {:kind :number :value 17 :int? true} (tok "+17")))
    (is (= {:kind :number :value -5 :int? true} (tok "-5"))))

  (testing "reals and the odd decimal forms"
    (is (= {:kind :number :value 3.14 :int? false} (tok "3.14")))
    (is (= {:kind :number :value 4.0 :int? false} (tok "4.")))
    (is (= {:kind :number :value 0.5 :int? false} (tok ".5")))
    (is (= {:kind :number :value -0.002 :int? false} (tok "-.002"))))

  (testing "a token boundary ends the number"
    (is (= {:kind :number :value 42 :int? true}
           (dissoc (lex/next-token (src "42 obj") 0) :start :end)))))

(deftest names
  (is (= {:kind :name :value "Type"} (tok "/Type")))
  (testing "empty name"
    (is (= {:kind :name :value ""} (tok "/ "))))
  (testing "#XX hex escapes decode (7.3.5)"
    (is (= {:kind :name :value "A B"} (tok "/A#20B")))
    (is (= {:kind :name :value "Lime Green"} (tok "/Lime#20Green"))))
  (testing "a lone # with no hex pair stays literal"
    (is (= {:kind :name :value "a#b"} (tok "/a#b")))))

(deftest literal-strings
  (is (= {:kind :string :value "Hello"} (tok "(Hello)")))
  (testing "balanced nested parens are kept"
    (is (= {:kind :string :value "a(b)c"} (tok "(a(b)c)"))))
  (testing "named escapes"
    (is (= {:kind :string :value "\t"} (tok "(\\t)")))
    (is (= {:kind :string :value "()\\"} (tok "(\\(\\)\\\\)"))))
  (testing "octal escapes, value mod 256"
    (is (= {:kind :string :value "A"} (tok "(\\101)")))     ;; 0101 = 65
    (is (= {:kind :string :value "@A"} (tok "(\\100\\101)"))))
  (testing "backslash before EOL is a line continuation"
    (is (= {:kind :string :value "foobar"} (tok "(foo\\\nbar)"))))
  (testing "a raw EOL normalizes to a single LF regardless of form"
    (is (= {:kind :string :value "a\nb"} (tok "(a\rb)")))
    (is (= {:kind :string :value "a\nb"} (tok "(a\r\nb)")))
    (is (= {:kind :string :value "a\nb"} (tok "(a\nb)"))))
  (testing "unterminated throws"
    (is (thrown? clojure.lang.ExceptionInfo (tok "(oops")))))

(deftest hex-strings
  (is (= {:kind :hex-string :value "Hello"} (tok "<48656C6C6F>")))
  (testing "whitespace between digits is ignored"
    (is (= {:kind :hex-string :value "Hi"} (tok "<48 69>"))))
  (testing "an odd final digit pads with a trailing 0"
    (is (= {:kind :hex-string :value "@"} (tok "<4>"))))   ;; 0x40
  (testing "empty hex string"
    (is (= {:kind :hex-string :value ""} (tok "<>")))))

(deftest delimiters-and-words
  (is (= {:kind :array-open} (tok "[")))
  (is (= {:kind :array-close} (tok "]")))
  (is (= {:kind :dict-open} (tok "<<")))
  (is (= {:kind :dict-close} (tok ">>")))
  (testing "<< is disambiguated from a hex string by peeking"
    (is (= :dict-open (:kind (tok "<<"))))
    (is (= :hex-string (:kind (tok "<4869>")))))
  (testing "words pass through unvalidated"
    (is (= {:kind :word :value "obj"} (tok "obj")))
    (is (= {:kind :word :value "R"} (tok "R")))
    (is (= {:kind :word :value "true"} (tok "true"))))
  (testing "a lone > is an error"
    (is (thrown? clojure.lang.ExceptionInfo (tok ">")))))

(deftest whitespace-and-comments
  (testing "leading whitespace and % comments are skipped"
    (is (= {:kind :number :value 42 :int? true} (tok "  \n42")))
    (is (= {:kind :number :value 42 :int? true} (tok "% a comment\n42")))))

(deftest eof
  (is (= :eof (:kind (tok ""))))
  (is (= :eof (:kind (tok "   ")))))

(deftest end-positions
  (testing ":end is where the next scan begins"
    (let [s (src "42 /Type")
          t1 (lex/next-token s 0)
          t2 (lex/next-token s (:end t1))]
      (is (= 2 (:end t1)))
      (is (= {:kind :name :value "Type"} (dissoc t2 :start :end))))))

(deftest skip-eol
  (is (= 1 (lex/skip-eol (src "\n42") 0)))
  (is (= 2 (lex/skip-eol (src "\r\n42") 0)))
  (is (= 1 (lex/skip-eol (src "\r42") 0)))
  (testing "no EOL leaves pos unchanged"
    (is (= 0 (lex/skip-eol (src "42") 0)))))

(deftest find-last-keyword
  (let [s (src "junk\nstartxref\n123\n%%EOF\n")]
    (is (= 5 (lex/find-last-keyword s "startxref" (b/length s)))))
  (testing "returns the last occurrence"
    (let [s (src "startxref 1 startxref 2")]
      (is (= 12 (lex/find-last-keyword s "startxref" (b/length s))))))
  (testing "requires a word boundary — no substring match"
    (let [s (src "nostartxrefx")]
      (is (nil? (lex/find-last-keyword s "startxref" (b/length s))))))
  (testing "absent word is nil"
    (is (nil? (lex/find-last-keyword (src "nothing here") "startxref" 12)))))
