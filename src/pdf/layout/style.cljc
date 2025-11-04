(ns pdf.layout.style
  "The CSS-flexbox-subset style vocabulary as data: each property's default,
   whether it inherits to children, and how a written value coerces to what the
   solver understands. `pdf.layout.tree/normalize` leans on this so every later pass
   sees only resolved numbers, `{:% n}`, `:auto`, or `:none`, never a \"50%\"
   string or a Dimension. Names, values and semantics are CSS verbatim (see the
   MDN flexbox docs); nothing here is renamed or invented.

   A property that needs coercion names a resolver by keyword under `:resolver`
   (see the `resolvers` registry); without one its authored value passes through
   verbatim. Dispatching on a keyword keeps `properties` pure, printable data and
   lets a new value type register a resolver instead of editing `resolve-value`."
  (:require
   [clojure.string :as str]
   [pdf.utils.dimension :refer [dimension?]]))

;; Property metadata. :default is the initial value; :inherited marks the text
;; properties that flow to children (resolved once, in normalize); :resolver
;; names how an authored value coerces (a :length, a :box padding/margin
;; shorthand, or a :color); absent means pass through verbatim.
(def properties
  {:flex-direction   {:default :column}
   :justify-content  {:default :flex-start}
   :align-items      {:default :stretch}
   :align-self       {:default :auto}            ; :auto -> use the parent's :align-items
   :flex-grow        {:default 0}
   :flex-shrink      {:default 1}
   :flex-basis       {:default :auto :resolver :length}
   :gap              {:default 0 :resolver :length}
   :row-gap          {:default :normal :resolver :length}    ; :normal -> fall back to :gap
   :column-gap       {:default :normal :resolver :length}
   :align-content    {:default :stretch}                ; cross-axis distribution of wrap lines
   :width            {:default :auto :resolver :length}
   :height           {:default :auto :resolver :length}
   :min-width        {:default 0 :resolver :length}
   :min-height       {:default 0 :resolver :length}
   :max-width        {:default :none :resolver :length}
   :max-height       {:default :none :resolver :length}
   :padding          {:default 0 :resolver :box}
   :margin           {:default 0 :resolver :box}
   :border-width     {:default 0 :resolver :box}             ; participates in the box model
   :border-color     {:default nil :resolver :color}    ; painted as filled side rects
   :top              {:default :auto :resolver :length}
   :right            {:default :auto :resolver :length}
   :bottom           {:default :auto :resolver :length}
   :left             {:default :auto :resolver :length}
   :position         {:default :relative}
   :flex-wrap        {:default :nowrap}
   :background-color {:default nil :resolver :color}
   :font             {:default nil :inherited true}
   :font-size        {:default 12 :inherited true}
   :line-height      {:default :normal :inherited true}
   :color            {:default [0 0 0] :inherited true :resolver :color}
   :break-inside     {:default :auto}})

(defn- percentage? [v]
  (and (string? v) (str/ends-with? v "%")))

(defn resolve-length
  "Coerce a length value to the solver's vocabulary: a number of points, `{:% n}`
   for a percentage, or the passthrough keywords `:auto`/`:none`. A Dimension
   resolves to its points; a \"50%\" string to `{:% 50}`. nil passes through."
  [v]
  (cond
    (or (nil? v) (= :auto v) (= :none v) (= :normal v)) v
    (number? v)     v
    (dimension? v)  (:points v)
    (percentage? v) {:% (parse-double (subs v 0 (dec (count v))))}
    :else (throw (ex-info "Not a valid length value" {:value v}))))

(defn resolve-box
  "Expand a padding/margin value to `[top right bottom left]` of resolved
   lengths, following CSS shorthand ordering (1, 2, 3 or 4 values)."
  [v]
  (let [vs (if (sequential? v) (mapv resolve-length v) [(resolve-length v)])]
    (case (count vs)
      1 (let [[a] vs]     [a a a a])
      2 (let [[v h] vs]   [v h v h])
      3 (let [[t h b] vs] [t h b h])
      (subvec vs 0 4))))

(defn- hex-pair
  "The byte value (0..255) of the two hex digits of `s` at index `i`."
  [s i]
  #?(:clj  (Integer/parseInt (subs s i (+ i 2)) 16)
     :cljs (js/parseInt (subs s i (+ i 2)) 16)))

(defn- hex->rgb
  "A `#rrggbb` or shorthand `#rgb` hex string to [r g b] in 0..1."
  [s]
  (let [h (cond-> s (str/starts-with? s "#") (subs 1))
        h (if (= 3 (count h))
            (apply str (mapcat (fn [c] [c c]) h))   ; #abc -> #aabbcc
            h)]
    (mapv #(/ (hex-pair h %) 255.0) [0 2 4])))

(defn resolve-color
  "Coerce an authored color to PDF's [r g b] in 0..1: a `#rrggbb`/`#rgb` hex
   string or an [r g b] triple in 0..255 (the conventional way to write colors;
   PDF's native 0..1 is unusual). nil (no color) passes through."
  [v]
  (cond
    (nil? v)    v
    (string? v) (hex->rgb v)
    (coll? v)   (mapv #(/ % 255.0) v)
    :else       v))

;; Value resolvers keyed by the name a property carries under :resolver. Add a
;; resolver here to give a property custom coercion without touching
;; resolve-value; a property with no :resolver passes its value through.
(def resolvers
  {:length resolve-length
   :box    resolve-box
   :color  resolve-color})

(defn resolve-value
  "Coerce one authored style value through the resolver its property names under
   :resolver (see `resolvers`); properties without one pass through verbatim
   (keywords, font contexts, plain numbers)."
  [prop v]
  (if-let [resolver (get resolvers (:resolver (get properties prop)))]
    (resolver v)
    v))

(def defaults
  "The initial value of every property in solver vocabulary (defaults resolved
   the same as authored values), merged under authored styles in normalize."
  (into {} (map (fn [[k m]] [k (resolve-value k (:default m))])) properties))

(def inherited
  "The set of properties that inherit down the tree (the text properties)."
  (into #{} (comp (filter (comp :inherited val)) (map key)) properties))
