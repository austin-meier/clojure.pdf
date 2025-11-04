(ns pdf.utils.collection)

(defn first-index
  "Index of the first item in `coll` for which `(f item)` is truthy, or nil."
  [f coll]
  (first (keep-indexed #(when (f %2) %1) coll)))