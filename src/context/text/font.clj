(ns context.text.font
  (:require
   [context.stream :refer [bytes->stream]]
   [context.text.font-parser :refer [parse-font-tables]]
   [utils.io :refer [file->bytes]])
  (:import
   [java.awt Font]
   [java.io FileInputStream]))


(defn detect-font-format [path]
  (let [ext (str (second (re-find #"\.([^.]+)$" path)))]
    (cond
      (#{"ttf" "ttc" "otf"} ext) Font/TRUETYPE_FONT
      (#{"pfb"} ext) Font/TYPE1_FONT
      :else Font/TRUETYPE_FONT)))

(defn get-font-subtype [font-format]
  (case font-format
    Font/TYPE1_FONT :Type1C
    Font/TRUETYPE_FONT :OpenType
    :OpenType))

(defn new-font [path]
  (let [bytes (file->bytes path)
        font-format (detect-font-format path)
        font-tables (parse-font-tables bytes)]
    (try
      (with-open [stream (FileInputStream. path)]
        (let [font (Font/createFont font-format stream)]
          {:type :font
           :subtype (if (= font-format Font/TRUETYPE_FONT) :TrueType :Type1)
           :base-font (symbol (.getName font))
           :font-descriptor
           {:type :font-descriptor
            :flags 32
            :italic-angle (:italic-angle font-tables)
            :ascent (:ascent font-tables)
            :descent (:descent font-tables)
            :cap-height (:ascent font-tables)
            :stem-v 80
            :font-bbox [(:xMin font-tables)
                        (:yMin font-tables)
                        (:xMax font-tables)
                        (:yMax font-tables)]
            :font-name (symbol (.getName font))
            :font-file-3 (bytes->stream bytes {:subtype (get-font-subtype font-format)})}}))

      (catch Exception e
        (println "Font load failed:" (.getMessage e))
        nil))))


(comment
  (new-font "/System/Library/Fonts/Courier.ttc"))