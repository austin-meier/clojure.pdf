(ns pdf.layout.tree
  "Normalize a hiccup layout tree into node maps. Every later pass (measure,
   solve, paginate, emit) works on this one shape:

     {:node :div :style {..} :children [..normalized nodes..]}
     {:node :text :style {..} :text \"concatenated strings\"}
     {:node :image :style {..} :src <image context>}

   Styles arrive resolved: defaults merged under authored values, lengths coerced
   to numbers/`{:% n}`/`:auto` (see pdf.layout.style), and the text properties (font,
   font-size, line-height, color) inherited down the tree here so the solver and
   emit never walk back up for them.

   Grammar: `[:div attrs? & children]`, `[:text attrs? & strings]`,
   `[:image {:src <image context> :style {..}}]`. The attrs map is optional
   (Reagent-style); its `:style` is read everywhere and `:src` on an image
   (other keys like `:id` are reserved and ignored)."
  (:require
   [pdf.context.predicates :refer [image-context?]]
   [pdf.layout.style :as style]))

(defn preorder
  "Lazy preorder seq of a normalized node and all its descendants."
  [node]
  (cons node (mapcat preorder (:children node))))

(defn- parse-hiccup
  "Split a hiccup vector into [tag attrs children]. The attrs map is optional; a
   map in the first body position is attrs, anything else is a child."
  [[tag & body]]
  (if (map? (first body))
    [tag (first body) (rest body)]
    [tag nil body]))

(defn- resolve-style
  "The node's computed style: defaults, then inherited text props from the
   parent's computed style, then the node's own authored (unit-resolved) values."
  [authored parent-style]
  (let [inherited (select-keys parent-style style/inherited)
        resolved  (reduce-kv (fn [m k v] (assoc m k (style/resolve-value k v)))
                             {} authored)]
    (merge style/defaults inherited resolved)))

(defn normalize
  "Normalize a hiccup layout tree into node maps, inheriting text styles from
   `parent-style` (nil at the root). Pure."
  ([tree] (normalize tree nil))
  ([tree parent-style]
   (let [[tag attrs children] (parse-hiccup tree)
         node-style (resolve-style (:style attrs) parent-style)]
     (case tag
       :div {:node     :div
              :style    node-style
              :children (mapv #(normalize % node-style) children)}
       :text {:node  :text
              :style node-style
              :text  (apply str children)}
       :image (let [src (:src attrs)]
                (when-not (image-context? src)
                  (throw (ex-info "[:image] requires :src to be an image context"
                                  {:tree tree :src src})))
                {:node  :image
                 :style node-style
                 :src   src})
       (throw (ex-info "Unknown layout node" {:tag tag :tree tree}))))))
