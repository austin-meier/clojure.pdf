(ns context.text.font
  (:require
   [context.stream :refer [bytes->stream]]
   [context.text.font-parser :refer [parse-font-tables sfnt-bytes]]
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
  (let [file-bytes (file->bytes path)
        sfnt (sfnt-bytes file-bytes)
        font-format (detect-font-format path)
        font-tables (parse-font-tables sfnt)]
    (try
      (with-open [stream (FileInputStream. path)]
        (let [font (Font/createFont font-format stream)]
          {:type :font
           :subtype (if (= font-format Font/TRUETYPE_FONT) :open-type :type1-c)
           :base-font (symbol (.getName font))
           :font-descriptor
           {:type :font-descriptor
            :flags 32
            :italic-angle (:italic-angle font-tables)
            :ascent (:ascent font-tables)
            :descent (:descent font-tables)
            :cap-height (:ascent font-tables)
            :stem-v 80
            :font-b-box [(:xMin font-tables)
                        (:yMin font-tables)
                        (:xMax font-tables)
                        (:yMax font-tables)]
            :font-name (symbol (.getName font))
            :font-file-3 (bytes->stream sfnt {:length1 (count sfnt) :sub-type (get-font-subtype font-format)})}}))

      (catch Exception e
        (println "Font load failed:" (.getMessage e))
        nil))))


(comment
  (new-font "/System/Library/Fonts/Courier.ttc"))