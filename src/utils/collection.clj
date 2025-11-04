(ns utils.collection)

(defn first-index
  [f coll]
  (first (keep-indexed #(when (f %2) %1) coll)))