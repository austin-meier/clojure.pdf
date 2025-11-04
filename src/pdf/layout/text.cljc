(ns pdf.layout.text
  "Text measurement over font contexts. A font context already carries the parsed
   `:advance-widths`, `:units-per-em` and hhea metrics, and
   `pdf.context.text.sfnt/char->gid` maps codepoints to glyphs, so measurement is
   exact (no `measureText` guesswork). `measure` is the fn the solver injects as
   `:measure`: it greedily wraps words to an available width.

   Per-glyph advances only, no kerning or shaping (that needs GPOS/kern parsing),
   so a word wider than the available width overflows instead of breaking
   mid-word."
  (:require
   [clojure.string :as str]
   [pdf.context.text.sfnt :as sfnt]
   [pdf.context.text.subset :as subset]))

(defn- glyph-advances
  "Advance widths (font units) of the glyphs `text` maps to in `font-ctx`,
   .notdef (gid 0) for unmapped codepoints."
  [font-ctx text]
  (let [widths (:advance-widths (:sfnt font-ctx))
        cmap   (:cmap font-ctx)]
    (map (fn [cp] (get widths (or (sfnt/char->gid cmap cp) 0) 0))
         (subset/codepoints text))))

(defn run-width
  "Width in points of `text` set in `font-ctx` at `size` pt: the sum of glyph
   advances scaled by size / units-per-em. No kerning or shaping."
  [font-ctx size text]
  (let [upem (:units-per-em (:sfnt font-ctx))]
    (* (/ size upem) (reduce + 0 (glyph-advances font-ctx text)))))

(defn line-height
  "The line-box height in points: the `:line-height` multiplier × size, or the
   hhea-derived `:normal` = (ascent - descent + line-gap) / units-per-em × size
   (descent is negative in hhea, so this sums the three)."
  [font-ctx line-height-style size]
  (let [{:keys [ascent descent line-gap units-per-em]} (:sfnt font-ctx)]
    (if (= :normal line-height-style)
      (* (/ (+ (- ascent descent) line-gap) units-per-em) size)
      (* line-height-style size))))

(defn ascent-px
  "The font ascent in points at `size`: the baseline offset from the top of the
   line box, which emit needs to place glyphs."
  [font-ctx size]
  (* (/ (:ascent (:sfnt font-ctx)) (:units-per-em (:sfnt font-ctx))) size))

(defn- words
  "Split into words on whitespace runs, collapsing them to single spaces
   (CSS white-space:normal). Leading/trailing whitespace drops out."
  [text]
  (remove str/blank? (str/split text #"\s+")))

(defn- wrap-lines
  "Greedily pack `ws` into lines no wider than `avail` (nil = single line). A word
   wider than `avail` takes its own line and overflows. Returns [{:text :width}]."
  [font-ctx size avail ws]
  (let [space (run-width font-ctx size " ")]
    (loop [remaining ws, cur [], cur-w 0, lines []]
      (if (empty? remaining)
        (mapv (fn [{:keys [words width]}] {:text (str/join " " words) :width width})
              (cond-> lines (seq cur) (conj {:words cur :width cur-w})))
        (let [w  (first remaining)
              ww (run-width font-ctx size w)]
          (cond
            (empty? cur)
            (recur (rest remaining) [w] ww lines)

            (or (nil? avail) (<= (+ cur-w space ww) avail))
            (recur (rest remaining) (conj cur w) (+ cur-w space ww) lines)

            :else
            (recur (rest remaining) [w] ww (conj lines {:words cur :width cur-w}))))))))

(defn measure
  "Measure a `:text` node (font/size/line-height read from its style) wrapped to
   the available `:width` (nil = no wrap). Returns
   {:lines [{:text :width}] :width :height :min-content :max-content}: the box's
   used width (widest line) and height (line count × line height), plus the
   intrinsic min-content (widest word) and max-content (unbroken run) widths the
   flex automatic-minimum-size rule wants. A node with no `:font` measures to
   zero."
  [node {:keys [width]}]
  (let [st   (:style node)
        font (:font st)
        size (:font-size st)
        text (:text node)]
    (if (nil? font)
      {:lines [] :width 0 :height 0 :ascent 0 :min-content 0 :max-content 0}
      (let [ws    (words text)
            lines (wrap-lines font size width ws)
            lh    (line-height font (:line-height st) size)]
        {:lines       lines
         :width       (reduce max 0 (map :width lines))
         :height      (* (count lines) lh)
         :ascent      (ascent-px font size)   ; baseline offset of the first line, for baseline align
         :min-content (reduce max 0 (map #(run-width font size %) ws))
         :max-content (run-width font size (str/join " " ws))}))))
