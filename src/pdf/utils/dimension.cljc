(ns pdf.utils.dimension
  "The Dimension unit record and point coercions. Portable (.cljc); its
   PdfSerializable impl is at the bottom (pdf.serialize is a leaf now, so the
   record extends the protocol here rather than there)."
  (:require
   [pdf.serialize :refer [PdfSerializable pdf-number]]))

(defrecord Dimension [points]
  #?@(:clj [Object
            (toString [this]
              (format "Dimension{points=%.4f}" (:points this)))]))

(defn dimension?
  "Is `v` a Dimension?"
  [v]
  (instance? Dimension v))

(defn ->points
  "Points from a number (as-is) or a Dimension; nil passes through."
  [v]
  (cond
    (nil? v)       nil
    (dimension? v) (:points v)
    :else          v))

;; Conversion constants
(def ^:const points-per-inch 72.0)
(def ^:const points-per-cm (* points-per-inch (/ 1.0 2.54)))
(def ^:const points-per-mm (/ points-per-cm 10.0))

(defn dimension
  "Create a Dimension from a points"
  [points]
  (->Dimension (double points)))
(def points->dim dimension)

(defn inches->dim
  "Create a Dimension from inches" [inches]
  (dimension (* (double inches) points-per-inch)))

(defn cm->dim
  "Create a Dimension from centimeters" [cm]
  (dimension (* (double cm) points-per-cm)))

(defn mm->dim
  "Create a Dimension from millimeters" [mm]
  (dimension (* (double mm) points-per-mm)))

(defn dim->points
  "Get a dimension as points"
  [^Dimension d]
  (:points d))

(defn dim->inches
  "Get a dimension as inches"
  [^Dimension d]
  (/ (:points d) points-per-inch))

(defn dim->cm
  "Get a dimension as centimeters"
  [^Dimension d]
  (* (dim->inches d) 2.54))

(defn dim->mm
  "Get a dimension as millimeters"
  [^Dimension d]
  (* (dim->cm d) 10.0))


(defn dim+
  "Add two dimensions"
  [^Dimension a ^Dimension b]
  (dimension (+ (:points a) (:points b))))

(defn dim-
  "Subtract two dimensions"
  [^Dimension a ^Dimension b]
  (dimension (- (:points a) (:points b))))

(defn dim*
  "Multiply two dimensions"
  [^Dimension a ^Dimension b]
  (dimension (* (:points a) (:points b))))

(defn dimdiv
  "Divide two dimensions"
  [^Dimension a ^Dimension b]
  (dimension (/ (:points a) (:points b))))

(extend-protocol PdfSerializable
  Dimension
  (to-pdf [d] (pdf-number (dim->points d))))
