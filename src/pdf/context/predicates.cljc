(ns pdf.context.predicates)

(defn font-context?
  [v]
  (and (map? v) (= :font-context (:type v))))

(defn image-context?
  [v]
  (and (map? v) (= :image-context (:type v))))

(defn form-context?
  [v]
  (and (map? v) (= :form-context (:type v))))
