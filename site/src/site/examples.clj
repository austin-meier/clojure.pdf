(ns site.examples
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn display-name
  "Create menu label from a filename: strip the ordering prefix and extension, and
   turn separators into spaces (\"03-vector.clj\" -> \"vector\")."
  [filename]
  (-> filename
      (str/replace #"^\d+[-_]" "")
      (str/replace #"\.clj$" "")
      (str/replace #"[-_]" " ")))

(defmacro load-examples
  "Read site/examples/*.clj (sorted by filename) into a vector of
   {:name <label> :code <source string>}."
  []
  (->> (.listFiles (io/file "examples"))
       (filter #(str/ends-with? (.getName %) ".clj"))
       (sort-by #(.getName %))
       (mapv (fn [f] {:name (display-name (.getName f)) :code (slurp f)}))))
