(ns pdf.context.text.core
  (:require
   [pdf.context.page :refer [add-or-get-resource]]
   [pdf.context.text.cid :as cid]
   [pdf.utils.dimension :refer [->points]]))

(defn glyphs->ops
  "The BT..ET ops that draw positioned glyph runs in `font-key` at `font-size`.
   Each run is {:glyphs <GlyphString> :x <pts> :y <pts>}, positioned absolutely
   via the text matrix; an optional `color` [r g b] sets the fill. Both the
   manual API and pdf.layout.emit compile text through this one builder, so the
   two paths can't drift."
  ([font-key font-size runs] (glyphs->ops font-key font-size runs nil))
  ([font-key font-size runs color]
   (-> [[:begin-text]]
       (cond-> color (conj (into [:set-rgb-fill] color)))
       (conj [:set-font (symbol font-key) font-size])
       (into (mapcat (fn [{:keys [glyphs x y]}]
                       [[:set-text-matrix 1 0 0 1 x y]
                        [:show-text glyphs]])
                     runs))
       (conj [:end-text]))))

(defn text->ops
  "The content-stream operations that draw the text context with `font-key`.
   The font key is a symbol so it serializes as a PDF name (`/F0`); the text is
   already encoded as a GlyphString of gids (Identity-H)."
  [text-ctx font-key]
  (let [{:keys [glyphs font-size x y]} text-ctx]
    (glyphs->ops font-key font-size
                 [{:glyphs glyphs :x (->points x) :y (->points y)}])))

(defn with-text
  "Adds the text's draw ops to the page context: the text is encoded to gids
   through the font's cmap, and the gid->codepoint usage is recorded on the
   page under :font-usage for the serialize-time font embedding pass. The ops
   compile into a content stream at serialize time (adjacent draws share one
   stream — see pdf.context.pdf/compile-content-ops)."
  [page-ctx x y txt-ctx]
  (let [[page-ctx font-key] (add-or-get-resource page-ctx :font (:font txt-ctx))
        {:keys [gids gid->cp]} (cid/encode-text (:font txt-ctx) (:text txt-ctx))]
    (-> page-ctx
        (update-in [:font-usage font-key] (fnil merge {}) gid->cp)
        (update :contents (fnil conj [])
                (text->ops (assoc txt-ctx :x x :y y :glyphs (cid/->GlyphString gids))
                           font-key)))))

(defn new-text
  "Creates a new text context for use on a page."
  [text font-ctx]
  {:text text
   :font-size 12
   :font font-ctx})
