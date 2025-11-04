(ns site.examples
  (:require-macros [site.examples :refer [load-examples]]))

(def all
  "Vector of {:name :code}, in filename order."
  (load-examples))
