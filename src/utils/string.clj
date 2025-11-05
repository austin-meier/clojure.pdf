(ns utils.string
  (:require [clojure.string :as str]))

(defn kebab-to-pascal
  "Converts a kebab-case string to PascalCase"
  [s]
  (->> (str/split s #"-")
       (map clojure.string/capitalize)
       (apply str)))


(comment
  (kebab-to-pascal "example-string-conversion"))