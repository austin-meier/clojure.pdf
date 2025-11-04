(ns pdf.api.js
  "The JavaScript-facing API, exported by the ESM builds (see shadow-cljs.edn).
   Every function here converts plain JS input at the boundary — objects with
   camelCase keys become keywordized style maps, arrays become hiccup vectors —
   so authoring from JS feels native while the library underneath only ever
   sees its own data conventions. The pdf context itself stays an opaque handle
   threaded between calls; reading or editing parsed object graphs from JS is
   deliberately out of scope.

   Pass-through functions that already take JS-friendly input (newPdf, newPage,
   serialize, parse, ...) are exported straight from their home namespaces."
  (:require
   [clojure.string :as str]
   [pdf.context.image :as image]
   [pdf.context.pdf :as pdf]
   [pdf.context.text.core :as text]
   [pdf.layout.core :as layout]))

(defn- camel->kebab
  "\"flexDirection\" -> :flex-direction, matching the CSS property names the
   style vocabulary uses (pdf.layout.style)."
  [s]
  (keyword (str/replace s #"[A-Z]" #(str "-" (str/lower-case %)))))

(defn- style-value
  "A style value from JS. Strings become the keywords the style vocabulary
   expects (\"row\" -> :row), except the two string-native value types the
   resolvers take verbatim: \"#rrggbb\" colors and \"50%\" lengths. JS arrays
   (box shorthands, [r g b] colors) become vectors; numbers and font contexts
   pass through."
  [v]
  (cond
    (string? v) (if (or (str/starts-with? v "#") (str/ends-with? v "%"))
                  v
                  (keyword v))
    (array? v)  (mapv style-value v)
    :else       v))

(defn- js-style
  [o]
  (into {}
        (map (fn [k] [(camel->kebab k) (style-value (unchecked-get o k))]))
        (js-keys o)))

(defn- js-attrs
  "A hiccup attrs map from a JS props object: :style converts through the
   style vocabulary, :src (image contexts) passes through untouched."
  [o]
  (let [style (unchecked-get o "style")
        src   (unchecked-get o "src")]
    (cond-> {}
      (some? style) (assoc :style (js-style style))
      (some? src)   (assoc :src src))))

(defn- hiccup
  "A hiccup vector from a JS layout tree: arrays with a string tag head and an
   optional props object, e.g. [\"div\", {style: {...}}, ...children]. Nested
   arrays recurse; strings stay text children."
  [node]
  (if-not (array? node)
    node
    (let [[head & body] (array-seq node)
          attrs         (when (object? (first body)) (js-attrs (first body)))
          children      (map hiccup (if attrs (rest body) body))]
      (into (cond-> [(keyword head)] attrs (conj attrs)) children))))

(defn- layout-opts
  [o]
  (if (some? o)
    (cond-> {}
      (unchecked-get o "pageSize") (assoc :page-size (vec (unchecked-get o "pageSize")))
      (unchecked-get o "margin")   (assoc :margin (unchecked-get o "margin"))
      (unchecked-get o "header")   (assoc :header (hiccup (unchecked-get o "header")))
      (unchecked-get o "footer")   (assoc :footer (hiccup (unchecked-get o "footer"))))
    {}))

(defn with-text
  "withText(page, \"Hello\", {x: 72, y: 720, font, size: 12})"
  [page-ctx txt opts]
  (text/with-text page-ctx
    (unchecked-get opts "x")
    (unchecked-get opts "y")
    {:text      txt
     :font      (unchecked-get opts "font")
     :font-size (or (unchecked-get opts "size") 12)}))

(defn with-image
  "withImage(page, img, x, y, {width, height}) — width/height optional, one
   alone keeps the aspect ratio."
  ([page-ctx img x y] (image/with-image page-ctx img x y {}))
  ([page-ctx img x y opts]
   (image/with-image page-ctx img x y
     {:width  (unchecked-get opts "width")
      :height (unchecked-get opts "height")})))

(defn with-layout
  "withLayout(ctx, tree, {pageSize: [612, 792], margin: 36, header, footer}) —
   `tree` is JS-array hiccup with camelCase CSS style objects (see `hiccup`)."
  ([ctx tree] (with-layout ctx tree nil))
  ([ctx tree opts] (layout/with-layout ctx (hiccup tree) (layout-opts opts))))

(defn to-blob
  "The serialized document as an application/pdf Blob, ready for
   URL.createObjectURL or a download link."
  [ctx]
  (js/Blob. #js [(pdf/serialize ctx)] #js {:type "application/pdf"}))
