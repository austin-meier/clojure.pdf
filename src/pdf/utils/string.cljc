(ns pdf.utils.string
  (:require [clojure.string :as str]))

(defn kebab-to-pascal
  "Converts a kebab-case string to PascalCase, upper-casing the first letter of
   each hyphen-separated segment while leaving the rest untouched. Unlike
   `capitalize`, it never lower-cases the tail, so existing case is preserved
   (`\"font-BBox\"` -> `\"FontBBox\"`, not `\"FontBbox\"`)."
  [s]
  (->> (str/split s #"-")
       (map (fn [seg]
              (if (empty? seg)
                seg
                (str (str/upper-case (subs seg 0 1)) (subs seg 1)))))
       (apply str)))

;; Portable string formatting: cljs has no clojure.core/format, and the format
;; specs used across the serializer are all simple zero-padded decimal or hex.

(defn- pad-left [s width]
  (str (apply str (repeat (max 0 (- width (count s))) "0")) s))

(defn- hex [n]
  #?(:clj  (Integer/toString (int n) 16)
     :cljs (.toString n 16)))

(defn hex-upper
  "`n` as uppercase hex, left-padded with zeros to `width` (`%0<width>X`)."
  [n width]
  (pad-left (str/upper-case (hex n)) width))

(defn hex-lower
  "`n` as lowercase hex, left-padded with zeros to `width` (`%0<width>x`)."
  [n width]
  (pad-left (hex n) width))

(defn zero-pad
  "`n` as a decimal string, left-padded with zeros to `width` (`%0<width>d`)."
  [n width]
  (pad-left (str n) width))

(comment
  (kebab-to-pascal "example-string-conversion"))
