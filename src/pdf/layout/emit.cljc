(ns pdf.layout.emit
  "Turn one solved page tree into content ops in painter's order (document order,
   parents before children, so later siblings paint on top). The library's single
   y-flip lives here: layout solves in CSS top-left space, PDF draws bottom-up, so
   a border box at top-left (x, y) of height h paints at
   pdf-y = page-h - (oy + y) - h, and a text baseline drops the font ascent from
   the box top. `origin` [ox oy] is the page margin.

   Text and images compile through the same context-layer op builders
   `with-text`/`with-image` use (pdf.context.text.core/glyphs->ops,
   pdf.context.image/image->ops), and the gid->codepoint usage is returned per
   font key so the integration layer can record it; otherwise embed-fonts would
   subset away the glyphs the layout draws. Coordinates round to 3 decimals here
   (the solver keeps exact rationals; PDF wants finite reals)."
  (:require
   [pdf.context.image :as image]
   [pdf.context.text.cid :as cid]
   [pdf.context.text.core :as tcore]
   [pdf.layout.text :as text]
   [pdf.layout.tree :as tree]))

(defn- pt
  "Round a coordinate to 3 decimals as a double. The solver works in exact
   rationals, which don't serialize as PDF reals."
  [n]
  (double (/ (Math/round (* (double n) 1000.0)) 1000.0)))

(defn- contexts-used
  "Distinct non-nil results of `select` over the tree, in first-seen order."
  [page-tree select]
  (into [] (comp (keep select) (distinct)) (tree/preorder page-tree)))

(defn fonts-used
  "Distinct font contexts referenced by text nodes, in first-seen order."
  [page-tree]
  (contexts-used page-tree #(when (= :text (:node %)) (:font (:style %)))))

(defn images-used
  "Distinct image contexts referenced by image nodes, in first-seen order."
  [page-tree]
  (contexts-used page-tree #(when (= :image (:node %)) (:src %))))

(defn- fill-rect
  "A filled rect in `color` at top-left (x, y) size (w, h), y-flipped. nil when
   degenerate."
  [page-h [ox oy] [r g b] x y w h]
  (when (and (pos? w) (pos? h))
    [[:save-state]
     [:set-rgb-fill r g b]
     [:rectangle (pt (+ ox x)) (pt (- page-h (+ oy y) h)) (pt w) (pt h)]
     [:fill]
     [:restore-state]]))

(defn- bg-ops [page-h origin {:keys [x y width height]} color]
  (fill-rect page-h origin color x y width height))

(defn- as-num [v] (if (number? v) v 0))

(defn- border-ops
  "Paint a border as four filled side rects at the border-box edges (per-side
   widths, `[t r b l]`), so uneven borders and corners come out exact."
  [page-h origin {:keys [x y width height]} border-width color]
  (let [[bt br bb bl] (map as-num border-width)]
    (into [] cat
          (keep identity
                [(fill-rect page-h origin color x y width bt)                    ; top
                 (fill-rect page-h origin color x (+ y (- height bb)) width bb)  ; bottom
                 (fill-rect page-h origin color x y bl height)                   ; left
                 (fill-rect page-h origin color (+ x (- width br)) y br height)]))))  ; right

(defn- text-draw
  "Ops and glyph usage for one text node. One text-matrix set + show per line;
   the baseline of line i sits at box-top - i·line-height - ascent."
  [page-h [ox oy] node font-key]
  (let [{:keys [x y]} (:layout node)
        st   (:style node)
        font (:font st)
        size (:font-size st)
        [r g b] (:color st)
        lh   (text/line-height font (:line-height st) size)
        asc  (text/ascent-px font size)
        top  (- page-h (+ oy y))
        enc  (map #(cid/encode-text font (:text %)) (:lines (:measured node)))
        runs (map-indexed (fn [i e]
                            {:glyphs (cid/->GlyphString (:gids e))
                             :x      (pt (+ ox x))
                             :y      (pt (- top (* i lh) asc))})
                          enc)]
    {:ops   (tcore/glyphs->ops font-key size runs [r g b])
     :usage (reduce merge {} (map :gid->cp enc))}))

(defn- image-draw
  "The ops that draw an image node's XObject, y-flipped: the unit image square
   is scaled by the border box and dropped by its height, same convention as
   fill-rect."
  [page-h [ox oy] node image-key]
  (let [{:keys [x y width height]} (:layout node)]
    (image/image->ops (image-key (:src node))
                      (pt width) (pt height)
                      (pt (+ ox x)) (pt (- page-h (+ oy y) height)))))

(defn emit
  "Compile a solved `page-tree` to {:ops [...] :usage {font-key {gid cp}}}.
   `page-h` is the page height in points; `origin` [ox oy] the top-left content
   offset (margin); `font-key`/`image-key` map a font/image context to its page
   resource key."
  [page-tree page-h origin font-key image-key]
  (reduce
   (fn [acc node]
     (let [st  (:style node)
           bg  (:background-color st)
           bc  (:border-color st)
           bw  (:border-width st)
           acc (cond-> acc
                 bg (update :ops into (bg-ops page-h origin (:layout node) bg))
                 (and bc (some pos? (map as-num bw)))
                 (update :ops into (border-ops page-h origin (:layout node) bw bc)))]
       (cond
         (and (= :image (:node node)) (:src node))
         (update acc :ops into (image-draw page-h origin node image-key))

         (and (= :text (:node node)) (:font st) (seq (:lines (:measured node))))
         (let [k (font-key (:font st))
               {:keys [ops usage]} (text-draw page-h origin node k)]
           (-> acc
               (update :ops into ops)
               (update-in [:usage k] (fnil merge {}) usage)))

         :else acc)))
   {:ops [] :usage {}}
   (tree/preorder page-tree)))
