(ns file.xref)

(defrecord IndirectRef [obj-num gen])

;; Object handling
(defn ->ref
  "Takes an array index of the object and
   returns the serializable pdf reference"
  ([i] (->IndirectRef (inc i) 0))  ;; because PDF indices start at 1 and clojure's 0
  ([i g] (->IndirectRef (inc i) g)))


(defn serialize-xref
  "Builds the PDF cross-reference table from :object-offsets,
   appends it to :serialized-bytes, and sets :xref-offset in the context."
  [serialization-ctx]
  (let [offsets (:object-offsets serialization-ctx)
        xref-start (count (:serialized-bytes serialization-ctx))
        xref-entries
        (map (fn [off] (format "%010d 00000 n\n" off)) offsets)
        xref-header (format "xref\n0 %d\n0000000000 65535 f\n" (inc (count offsets)))
        xref-str (apply str xref-header xref-entries)
        xref-bytes (.getBytes xref-str "UTF-8")]
    (-> serialization-ctx
        (assoc :xref-offset xref-start)
        (update :serialized-bytes into xref-bytes))))