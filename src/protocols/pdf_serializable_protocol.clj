(ns protocols.pdf-serializable-protocol
  (:require
   [clojure.string :as str]
   [context.stream :as stream]
   [file.xref :as xref]
   [utils.string :refer [kebab-to-pascal]])
  (:import
   [context.stream PdfStream]
   [file.xref IndirectRef]))

(defprotocol PdfSerializable
  ;; Serialize to a PDF text format definition
  (to-pdf [x]))

(defn pdf-name [s]
  (str "/" (clojure.string/replace s #"[^!-\)\+-.0-9:;=?@A-Z\[\]_a-z~]"
                                   #(format "#%02X" (int (first %))))))

;; Implement the protocol for core clojure data structures
(extend-protocol PdfSerializable
  nil
  (to-pdf [_] "null")

  Boolean
  (to-pdf [b] (if b "true" "false"))

  Number
  (to-pdf [n] (str n))

  String
  (to-pdf [s] (str "(" s ")"))

  clojure.lang.Keyword
  (to-pdf [k] (pdf-name (kebab-to-pascal (name k))))

  clojure.lang.Symbol
  (to-pdf [k] (pdf-name (name k)))

  clojure.lang.IPersistentList
  (to-pdf [l] (to-pdf (vec l)))

  clojure.lang.IPersistentVector
  (to-pdf [v] (str "[" (str/join " " (map to-pdf v)) "]"))

  clojure.lang.IPersistentMap
  (to-pdf [m]
    (str "<< " (str/join " "
                         (mapcat (fn [[k v]]
                                   [(to-pdf (if (keyword? k) k (keyword (str k))))
                                    (to-pdf v)])
                                 m))
         " >>")))

;; Core records
(extend-protocol PdfSerializable
  PdfStream
  (to-pdf [stream]
    (let [dict-str (to-pdf (assoc (:dict stream) :length (count (:bytes stream))))
          header-str (str dict-str
                          "\n"
                          "stream\n")
          footer-str "\nendstream"
          header-bytes (.getBytes header-str "UTF-8")
          footer-bytes (.getBytes footer-str "UTF-8")]
      (String. (byte-array
                (concat
                 (seq header-bytes)
                 (:bytes stream)
                 (seq footer-bytes))) "UTF-8")))

  IndirectRef
  (to-pdf [{:keys [obj-num gen]}]
    (format "%d %d R" obj-num gen)))

