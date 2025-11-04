(ns pdf.context.svg
  (:require
   [clojure.string :as str]
   [pdf.context.svg.path :as path]))

(def ^:private number-re
  #"[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")

(defn- parse-nums [s]
  (mapv path/parse-num (re-seq number-re (str s))))

(defn- attr-num
  [v]
  (if (number? v)
    v
    (let [s (str/trim (str v))
          s (if (str/ends-with? s "px") (subs s 0 (- (count s) 2)) s)]
      (if (re-matches number-re s)
        (path/parse-num s)
        (throw (ex-info "Unsupported numeric attribute value" {:value v}))))))

(defn- normalize
  [node]
  (let [{:keys [tag attrs content]}
        (if (map? node)
          node
          (let [[tag maybe-attrs & children] node]
            (if (map? maybe-attrs)
              {:tag tag :attrs maybe-attrs :content children}
              {:tag tag :attrs {} :content (cond->> children
                                             (some? maybe-attrs) (cons maybe-attrs))})))]
    {:tag     (keyword (last (str/split (name tag) #":")))
     :attrs   (into {} (map (fn [[k v]] [(if (keyword? k) k (keyword k)) v])) attrs)
     :content (into []
                    (comp (filter #(or (map? %) (vector? %))) (map normalize))
                    content)}))

;; the standard svg named colors
(def ^:private named-colors
  {"black" "#000000" "silver" "#c0c0c0" "gray" "#808080" "grey" "#808080"
   "white" "#ffffff" "maroon" "#800000" "red" "#ff0000" "purple" "#800080"
   "fuchsia" "#ff00ff" "magenta" "#ff00ff" "green" "#008000" "lime" "#00ff00"
   "olive" "#808000" "yellow" "#ffff00" "navy" "#000080" "blue" "#0000ff"
   "teal" "#008080" "aqua" "#00ffff" "cyan" "#00ffff" "orange" "#ffa500"})

(defn- parse-hex [s]
  #?(:clj  (Integer/parseInt s 16)
     :cljs (js/parseInt s 16)))

(defn- hex->rgb
  "#rgb/#rgba/#rrggbb/#rrggbbaa (sans #) -> [r g b] in 0-1. A non-opaque
   alpha throws: honoring it needs ExtGState support."
  [h]
  (when-not (and (re-matches #"[0-9a-f]+" h) (#{3 4 6 8} (count h)))
    (throw (ex-info "Malformed hex color" {:color h})))
  (let [h (if (<= (count h) 4) (apply str (mapcat vector h h)) h)]
    (when (and (= 8 (count h)) (not= "ff" (subs h 6 8)))
      (throw (ex-info "Translucent colors are not supported (tier 1)" {:color h})))
    (mapv #(/ (parse-hex (subs h % (+ % 2))) 255.0) [0 2 4])))

(defn- rgb-component [s]
  (if (str/ends-with? s "%")
    (/ (path/parse-num (subs s 0 (dec (count s)))) 100.0)
    (/ (path/parse-num s) 255.0)))

(defn- rgb-fn->rgb [args]
  (let [parts (str/split (str/trim args) #"[\s,/]+")]
    (when-let [a (nth parts 3 nil)]
      ;; Alpha is 0-1 (or a percentage), unlike the 0-255 color channels.
      (let [alpha (if (str/ends-with? a "%")
                    (/ (path/parse-num (subs a 0 (dec (count a)))) 100.0)
                    (path/parse-num a))]
        (when (< alpha 1)
          (throw (ex-info "Translucent colors are not supported (tier 1)" {:alpha a})))))
    (mapv rgb-component (take 3 parts))))

(defn parse-color
  [v current]
  (let [s (str/lower-case (str/trim (if (keyword? v) (name v) (str v))))]
    (cond
      (#{"none" "transparent" ""} s) nil
      (= "currentcolor" s)           (parse-color (or current "black") "black")
      (str/starts-with? s "#")       (hex->rgb (subs s 1))
      (str/starts-with? s "url(")    (throw (ex-info "Gradient/pattern paint is not supported yet" {:paint s}))
      (named-colors s)               (hex->rgb (subs (named-colors s) 1))
      :else
      (if-let [[_ args] (re-matches #"rgba?\((.*)\)" s)]
        (rgb-fn->rgb args)
        (throw (ex-info "Unsupported color" {:color s}))))))

(defn- matrix*
  "Compose affine matrices [a b c d e f] in PDF/SVG operand order. The result
   applies m2 first, then m1"
  [[a1 b1 c1 d1 e1 f1] [a2 b2 c2 d2 e2 f2]]
  [(+ (* a1 a2) (* c1 b2))
   (+ (* b1 a2) (* d1 b2))
   (+ (* a1 c2) (* c1 d2))
   (+ (* b1 c2) (* d1 d2))
   (+ (* a1 e2) (* c1 f2) e1)
   (+ (* b1 e2) (* d1 f2) f1)])

(defn- deg->rad [a] (* a (/ Math/PI 180)))

(defn- transform-matrix [kind args]
  (case kind
    "matrix"    (vec args)
    "translate" (let [[tx ty] args] [1 0 0 1 tx (or ty 0)])
    "scale"     (let [[sx sy] args] [sx 0 0 (or sy sx) 0 0])
    "rotate"    (let [[a cx cy] args
                      r (deg->rad a)
                      m [(Math/cos r) (Math/sin r) (- (Math/sin r)) (Math/cos r) 0 0]]
                  (if cx
                    (-> [1 0 0 1 cx (or cy 0)]
                        (matrix* m)
                        (matrix* [1 0 0 1 (- cx) (- (or cy 0))]))
                    m))
    "skewX"     [1 0 (Math/tan (deg->rad (first args))) 1 0 0]
    "skewY"     [1 (Math/tan (deg->rad (first args))) 0 1 0 0]
    (throw (ex-info "Unsupported transform function" {:transform kind}))))

(defn parse-transform
  "Turns a SVG transform list into one composed [a b c d e f] matrix, or nil when
   there's nothing to apply."
  [s]
  (when s
    (when-let [ms (seq (re-seq #"([A-Za-z]+)\s*\(([^)]*)\)" (str s)))]
      (->> ms
           (map (fn [[_ kind args]] (transform-matrix kind (parse-nums args))))
           (reduce matrix*)))))

(def ^:private style-defaults
  {:fill "black" :stroke "none" :stroke-width 1 :fill-rule "nonzero"
   :stroke-linecap "butt" :stroke-linejoin "miter" :stroke-miterlimit 4
   :stroke-dasharray "none" :stroke-dashoffset 0 :color "black"
   :display "inline"})

(def ^:private opacity-keys [:opacity :fill-opacity :stroke-opacity])

(defn- style-decls
  [s]
  (into {}
        (keep (fn [decl]
                (let [[k v] (str/split decl #":" 2)]
                  (when v [(keyword (str/trim k)) (str/trim v)]))))
        (str/split (str s) #";")))

(defn- resolved-style
  "The element's effective style from the inherited `parent` under its presentation
   attributes and it's own `style` declarations."
  [parent attrs]
  (let [own (merge (select-keys attrs (into (keys style-defaults) opacity-keys))
                   (some-> (:style attrs) style-decls))]
    (when-let [[k v] (first (filter (fn [[_ v]] (< (attr-num v) 1))
                                    (select-keys own opacity-keys)))]
      (throw (ex-info "Opacity is not supported (needs ExtGState)" {k v})))
    (merge parent (select-keys own (keys style-defaults)))))

(def ^:private kappa 0.5522847498307936)

(defn- ellipse-ops [cx cy rx ry]
  (if (or (<= rx 0) (<= ry 0))
    []
    (let [kx (* kappa rx), ky (* kappa ry)]
      [[:move-to (+ cx rx) cy]
       [:curve-to (+ cx rx) (+ cy ky) (+ cx kx) (+ cy ry) cx (+ cy ry)]
       [:curve-to (- cx kx) (+ cy ry) (- cx rx) (+ cy ky) (- cx rx) cy]
       [:curve-to (- cx rx) (- cy ky) (- cx kx) (- cy ry) cx (- cy ry)]
       [:curve-to (+ cx kx) (- cy ry) (+ cx rx) (- cy ky) (+ cx rx) cy]
       [:close-path]])))

(defn- rounded-rect-ops [x y w h rx ry]
  (let [kx (* kappa rx), ky (* kappa ry)
        x2 (+ x w), y2 (+ y h)]
    [[:move-to (+ x rx) y]
     [:line-to (- x2 rx) y]
     [:curve-to (+ (- x2 rx) kx) y x2 (- (+ y ry) ky) x2 (+ y ry)]
     [:line-to x2 (- y2 ry)]
     [:curve-to x2 (+ (- y2 ry) ky) (+ (- x2 rx) kx) y2 (- x2 rx) y2]
     [:line-to (+ x rx) y2]
     [:curve-to (- (+ x rx) kx) y2 x (+ (- y2 ry) ky) x (- y2 ry)]
     [:line-to x (+ y ry)]
     [:curve-to x (- (+ y ry) ky) (- (+ x rx) kx) y (+ x rx) y]
     [:close-path]]))

(defn- corner-radius
  "One of a rect's rx/ry: nil and \"auto\" defer to the other axis, and the
   result clamps to half the side per the spec."
  [own other side]
  (let [v (first (remove #(or (nil? %) (= "auto" (str %))) [own other]))]
    (min (if v (attr-num v) 0) (/ side 2))))

(defn- rect-ops [attrs]
  (let [x (attr-num (:x attrs 0)),     y (attr-num (:y attrs 0))
        w (attr-num (:width attrs 0)), h (attr-num (:height attrs 0))
        rx (corner-radius (:rx attrs) (:ry attrs) w)
        ry (corner-radius (:ry attrs) (:rx attrs) h)]
    (cond
      (or (<= w 0) (<= h 0))    []
      (and (pos? rx) (pos? ry)) (rounded-rect-ops x y w h rx ry)
      :else                     [[:rectangle x y w h]])))

(defn- poly-ops [attrs close?]
  (let [pts (partition 2 (parse-nums (:points attrs "")))]
    (if (empty? pts)
      []
      (cond-> (into [(into [:move-to] (first pts))]
                    (map #(into [:line-to] %))
                    (rest pts))
        close? (conj [:close-path])))))

(defn- shape-ops [tag attrs]
  (case tag
    :path     (path/parse (:d attrs))
    :rect     (rect-ops attrs)
    :circle   (let [r (attr-num (:r attrs 0))]
                (ellipse-ops (attr-num (:cx attrs 0)) (attr-num (:cy attrs 0)) r r))
    :ellipse  (ellipse-ops (attr-num (:cx attrs 0)) (attr-num (:cy attrs 0))
                           (attr-num (:rx attrs 0)) (attr-num (:ry attrs 0)))
    :line     [[:move-to (attr-num (:x1 attrs 0)) (attr-num (:y1 attrs 0))]
               [:line-to (attr-num (:x2 attrs 0)) (attr-num (:y2 attrs 0))]]
    :polyline (poly-ops attrs false)
    :polygon  (poly-ops attrs true)))

(defn- enum-op [op table v]
  (if-let [n (get table (str v))]
    (when (pos? n) [op n])
    (throw (ex-info "Unsupported style value" {:op op :value v}))))

(defn- stroke-ops [style rgb]
  (let [dash (let [v (:stroke-dasharray style)]
               (when-not (= "none" (str v))
                 ;; SVG treats an all-zero dash list as no dashing
                 ;; PDF rejects it
                 (let [nums (parse-nums v)]
                   (when (some pos? nums) nums))))]
    (cond-> [(into [:set-rgb-stroke] rgb)
             [:set-line-width (attr-num (:stroke-width style))]]
      :always (into (keep identity
                          [(enum-op :set-line-cap {"butt" 0 "round" 1 "square" 2}
                                    (:stroke-linecap style))
                           (enum-op :set-line-join {"miter" 0 "round" 1 "bevel" 2}
                                    (:stroke-linejoin style))]))
      ;; PDFs default miter limit is 10, SVG's is 4, set it when mitering.
      (= "miter" (str (:stroke-linejoin style)))
      (conj [:set-miter-limit (attr-num (:stroke-miterlimit style))])
      dash (conj [:set-dash dash (attr-num (:stroke-dashoffset style))]))))

(defn- paint-op [fill? stroke? even-odd?]
  (cond
    (and fill? stroke?) (if even-odd? [:fill-stroke-even-odd] [:fill-stroke])
    fill?               (if even-odd? [:fill-even-odd] [:fill])
    :else               [:stroke]))

(defn- element-ops
  "Style + path + paint ops for one shape element, or nil when it paints nothing"
  [style tag attrs]
  (let [geometry   (shape-ops tag attrs)
        ;; A line has no interior; SVG never fills it.
        fill-rgb   (when-not (= :line tag)
                     (parse-color (:fill style) (:color style)))
        stroke-rgb (parse-color (:stroke style) (:color style))]
    (when (and (seq geometry) (or fill-rgb stroke-rgb))
      (-> []
          (cond-> fill-rgb   (conj (into [:set-rgb-fill] fill-rgb)))
          (cond-> stroke-rgb (into (stroke-ops style stroke-rgb)))
          (into geometry)
          (conj (paint-op fill-rgb stroke-rgb
                          (= "evenodd" (str (:fill-rule style)))))))))

(def ^:private skipped-tags
  #{:defs :title :desc :metadata :symbol :marker :mask :clipPath :pattern
    :linearGradient :radialGradient :filter :script})

(def ^:private unsupported-tags
  #{:text :tspan :textPath :use :image :style :switch :foreignObject})

(def ^:private shape-tags
  #{:path :rect :circle :ellipse :line :polyline :polygon})

(defn- node-ops [parent-style {:keys [tag attrs content]}]
  (let [style (resolved-style parent-style attrs)]
    (cond
      (= "none" (str (:display style))) []
      (contains? skipped-tags tag)      []
      (contains? unsupported-tags tag)  (throw (ex-info "Unsupported SVG element (tier-1 subset)" {:tag tag}))
      :else
      (let [tf   (parse-transform (:transform attrs))
            body (cond
                   (#{:g :svg :a} tag)        (into [] (mapcat #(node-ops style %)) content)
                   (contains? shape-tags tag) (element-ops style tag attrs)
                   :else                      [])]
        (cond
          (empty? body) []
          ;; Shapes always isolate their graphics state in q/Q so dash, cap and
          ;; friends can't leak to siblings; groups only need it for a transform.
          (contains? shape-tags tag)
          (-> [[:save-state]]
              (cond-> tf (conj (into [:concat-matrix] tf)))
              (into body)
              (conj [:restore-state]))

          tf    (-> [[:save-state] (into [:concat-matrix] tf)] (into body) (conj [:restore-state]))
          :else (vec body))))))

(defn- view-box [attrs]
  (if-let [vb (:viewBox attrs)]
    (parse-nums vb)
    (if-let [[w h] (and (:width attrs) (:height attrs)
                        [(attr-num (:width attrs)) (attr-num (:height attrs))])]
      [0 0 w h]
      (throw (ex-info "SVG needs a viewBox or width/height to size the form" {:attrs attrs})))))

(defn svg->form
  "Convert an SVG data tree into a form context ready for `with-form`"
  [tree]
  (let [{:keys [tag attrs content]} (normalize tree)]
    (when-not (= :svg tag)
      (throw (ex-info "Root element must be <svg>" {:tag tag})))
    (let [[min-x min-y w h] (view-box attrs)
          style (resolved-style style-defaults attrs)
          ;; The root runs through node-ops as a group so a transform
          ;; attribute on <svg> itself still lands inside the flip.
          body  (node-ops style {:tag   :g
                                 :attrs (select-keys attrs [:transform])
                                 :content content})]
      {:type :form-context
       :bbox [0 0 w h]
       :ops  (into [[:concat-matrix 1 0 0 -1 (- min-x) (+ min-y h)]] body)})))
