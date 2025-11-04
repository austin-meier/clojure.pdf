(ns file.header 
  (:require
   [clojure.string :as str]))

(defn pdf-binary-comment
  "Returns a byte array for a PDF binary comment line with bytes ≥128"
  []
  (byte-array
     [(byte 37) (byte -128) (byte -127) (byte -2) (byte -1) (byte 10)]))

(defn serialize-header
  "Appends the PDF header to the serialized byte stream and returns the updated context"
  [serialization-ctx]
  (let [pdf-version (get-in serialization-ctx [:ctx :version] "1.7")
        header-bytes (.getBytes (str "%PDF-" pdf-version "\n") "UTF-8")
        ;; binary comment bytes (4 bytes >= 128 + newline)
        binary-bytes (pdf-binary-comment)]
    (-> serialization-ctx
        (update :serialized-bytes into (seq header-bytes))
        (update :serialized-bytes into (seq binary-bytes)))))

;; page 59
(defn pdf-has-binary-data? [line]
  (let [trimmed (str/trim line)]
    (and
    ;; Must be a pdf comment
    (str/starts-with? trimmed "%")
    ;; Must have at least 4 following characters
    (>= (count (subs trimmed 1)) 4)
    ;; All following characters > 128
    (every? #(> (int %) 128)
            (subs trimmed 1)))))
