(ns pdf.file.xref
  (:require
   [pdf.file.chunk :refer [append-chunks]]
   [pdf.serialize :refer [PdfSerializable]]
   [pdf.utils.string :as pstr]))

;; Symbolic reference used while authoring: points at an object by its stable id, independent of position
(defrecord Ref [id gen])

(defrecord IndirectRef [obj-num gen])

(defn ref? [obj]
  (instance? Ref obj))

(defn indirect-ref? [obj]
  (instance? IndirectRef obj))

(defn ->ref
  "A symbolic reference to the object with stable id `id` (default generation 0)."
  ([id] (->Ref id 0))
  ([id g] (->Ref id g)))

(extend-protocol PdfSerializable
  ;; A symbolic Ref must be concretized to an IndirectRef. hitting this means numbering was skipped.
  Ref
  (to-pdf [_]
    (throw (ex-info "Ref must be concretized to an IndirectRef before serialization." {})))

  IndirectRef
  (to-pdf [{:keys [obj-num gen]}]
    (str obj-num " " gen " R")))

(defn serialize-xref
  "Builds the PDF cross-reference table from :object-offsets,
   appends it as a chunk, and sets :xref-offset in the context."
  [serialization-ctx]
  (let [offsets (:object-offsets serialization-ctx)
        xref-entries (map (fn [off] (str (pstr/zero-pad off 10) " 00000 n\r\n")) offsets)
        xref-header (str "xref\n0 " (inc (count offsets)) "\n0000000000 65535 f\r\n")]
    (-> serialization-ctx
        (assoc :xref-offset (:length serialization-ctx))
        (append-chunks [(apply str xref-header xref-entries)]))))
