(ns pdf.layout.flex
  "The flex solver. Takes a normalized node (pdf.layout.tree) plus the available
   content space of its containing block and returns the same tree with a
   `:layout {:x :y :width :height}` border box on every node, in one top-left
   coordinate space (the caller y-flips at emit).

   Implements the CSS flexbox algorithm (css-flexbox-1 §9) for the library's
   subset: `:flex-direction` row/column, `:justify-content`, `:align-items`/
   `:align-self` (including `:baseline`), `:flex-grow`/`:flex-shrink`/
   `:flex-basis`, `:gap` (and split `:row-gap`/`:column-gap`), multi-line
   `:flex-wrap :wrap` with `:align-content`, borders in the box model,
   definite/`:auto`/`%` sizing with min/max clamping, and `:position :absolute`.
   Text leaves size through an injected `:measure` fn; with none, a `:text` node
   sizes to its explicit width/height or zero. Image leaves are CSS replaced
   elements: intrinsic size is the header's pixels scaled 96dpi->72pt, with an
   aspect-ratio fallback when exactly one dimension is authored.

   Generic over the main/cross axes via `axis-cfg` so row and column share one
   code path. Sizes forced by the parent (a grown/shrunk main size, a stretched
   cross size) arrive as `forces` {:width :height}; each nil entry falls back to
   the node's own style + content."
  (:require
   [pdf.layout.style :as style]))

(def ^:private axis-cfg
  {:row    {:main :width  :cross :height
            :m-start :left :m-end :right :c-start :top :c-end :bottom}
   :column {:main :height :cross :width
            :m-start :top  :m-end :bottom :c-start :left :c-end :right}})

(def ^:private margin-idx {:top 0 :right 1 :bottom 2 :left 3})

;; CSS defines 96px/in against PDF's 72pt/in, so a layout image pixel is 0.75pt.
(def ^:private px->pt 0.75)

(defn- resolve-len
  "Resolve a length against a definite available size: a number, or nil when
   indefinite (`:auto`/`:none`, or a percentage with unknown available)."
  [v avail]
  (cond
    (number? v)                    v
    (and (map? v) (number? avail)) (* 0.01 (:% v) avail)
    :else                          nil))

(defn- clamp [v lo hi]
  (cond-> v
    (number? lo) (max lo)
    (number? hi) (min hi)))

(defn- side-px
  "A resolved margin/padding side in points (indefinite -> 0)."
  [box side avail-w]
  (or (resolve-len (nth box (margin-idx side)) avail-w) 0))

(defn- translate
  "Shift a node's border box and every descendant by (dx, dy). Positions are
   local to the parent until the parent places the subtree with this."
  [node dx dy]
  (cond-> (update node :layout #(-> % (update :x + dx) (update :y + dy)))
    (:children node) (update :children (fn [cs] (mapv #(translate % dx dy) cs)))))

(declare flex-layout)

;; ---------------------------------------------------------------------------
;; §9.7 resolve flexible lengths
;; ---------------------------------------------------------------------------

(defn- resolve-flexible
  "Distribute `free` main space across `items` (maps with :base :hyp :grow
   :shrink :min :max) per §9.7, returning each item's target main size. Frozen
   (inflexible or violation-clamped) items settle at their clamped value; the
   rest share space proportionally, looping until no min/max violation remains."
  [items free]
  (let [grow? (> free 0)]
    (loop [targets (mapv :hyp items)
           frozen  (mapv (fn [it] (or (zero? (if grow? (:grow it) (:shrink it)))
                                       (if grow?
                                         (> (:base it) (:hyp it))
                                         (< (:base it) (:hyp it)))))
                         items)]
      (let [live (remove frozen (range (count items)))]
        (if (empty? live)
          targets
          (let [used   (reduce + (map (fn [i] (if (nth frozen i)
                                                (nth targets i)
                                                (:base (nth items i))))
                                      (range (count items))))
                remain (- free (- used (reduce + (map :base items))))
                scale  (if grow?
                         (reduce + (map #(:grow (nth items %)) live))
                         (reduce + (map #(* (:base (nth items %))
                                            (:shrink (nth items %))) live)))
                raw    (reduce
                        (fn [ts i]
                          (let [it (nth items i)
                                share (cond
                                        (zero? scale) 0
                                        grow? (* remain (/ (:grow it) scale))
                                        :else (* remain (/ (* (:base it) (:shrink it)) scale)))]
                            (assoc ts i (+ (:base it) share))))
                        targets live)
                clamped (reduce (fn [ts i]
                                  (let [it (nth items i)]
                                    (assoc ts i (clamp (nth raw i) (:min it) (:max it)))))
                                raw live)
                violation (reduce + (map #(- (nth clamped %) (nth raw %)) live))
                frozen' (reduce (fn [fr i]
                                  (let [d (- (nth clamped i) (nth raw i))]
                                    (if (or (zero? violation)
                                            (and (pos? violation) (pos? d))
                                            (and (neg? violation) (neg? d)))
                                      (assoc fr i true)
                                      fr)))
                                frozen live)]
            (recur clamped frozen')))))))

;; ---------------------------------------------------------------------------
;; alignment
;; ---------------------------------------------------------------------------

(defn- justify-offsets
  "Main-axis start position of each of `n` items given the leftover `free` space
   and per-item `gap`, per `:justify-content`. Returns [start step-extra] where
   an item's position is start + i*gap + cumulative(sizes) + i*step-extra."
  [justify n free gap]
  (let [free (max free 0)]
    (if (zero? n)
      [0 0]
      (case justify
      :flex-start    [0 0]
      :flex-end      [free 0]
      :center        [(/ free 2) 0]
      :space-between [0 (if (> n 1) (/ free (dec n)) 0)]
      :space-around  [(/ free (* 2 n)) (/ free n)]
      :space-evenly  [(/ free (inc n)) (/ free (inc n))]
      [0 0]))))

;; ---------------------------------------------------------------------------
;; the solver
;; ---------------------------------------------------------------------------

(defn- child-main
  "A child's flex base size along `main` (the border-box size): from
   `:flex-basis`, else the main-axis size property, else its content size. When
   the item's cross size is already known (`cross-force`, from a definite-cross
   stretch), the content is measured at that cross size: for a column that's what
   makes text height reflect the real wrap width."
  [child main main-content cross-force child-avail opts]
  (let [st     (:style child)
        basis  (:flex-basis st)
        forces (if (= main :width) {:width nil :height cross-force} {:width cross-force :height nil})]
    (or (when (not= :auto basis) (resolve-len basis main-content))
        (resolve-len (main st) main-content)
        (get-in (flex-layout child child-avail forces opts) [:layout main]))))

(defn- break-lines
  "Partition items into flex lines. A single line unless wrapping against a
   definite main size; then fill lines greedily up to `main-size` (an item always
   starts a line, even if it alone overflows)."
  [infos main-size main-gap wrap?]
  (if (or (not wrap?) (nil? main-size))
    [infos]
    (loop [remaining infos, line [], used 0, lines []]
      (if (empty? remaining)
        (cond-> lines (seq line) (conj line))
        (let [it    (first remaining)
              outer (+ (:hyp it) (:m-start it) (:m-end it))
              add   (if (empty? line) outer (+ main-gap outer))]
          (if (or (empty? line) (<= (+ used add) main-size))
            (recur (rest remaining) (conj line it) (+ used add) lines)
            (recur remaining [] 0 (conj lines line))))))))

(defn- resolved-align
  "The item's effective cross alignment: :align-self, falling back to the
   container's :align-items when :auto."
  [item-style align-items]
  (let [self (:align-self item-style)]
    (if (= :auto self) align-items self)))

(defn- item-baseline
  "Distance from an item's outer top (cross-start margin edge) to its first
   baseline: a text leaf's ascent, else its outer cross size (baseline at the
   bottom, per CSS for non-baseline-bearing items)."
  [laid info cross]
  (+ (:c-mstart info)
     (if (= :text (:node (:node info)))
       (or (:ascent (:measured laid)) (get-in laid [:layout cross]))
       (get-in laid [:layout cross]))))

(defn- place-children
  "Run the flex algorithm for a view's in-flow children within its content box
   (`content-main`/`content-cross` may be nil = content-sized). Supports
   multi-line wrap, split row/column gaps, per-line justify/align, baseline
   alignment, and align-content across lines. Returns {:children placed :main
   size :cross size}, children positioned relative to the content-box origin."
  [node dir content-main content-cross child-avail opts]
  (let [{:keys [main cross m-start m-end c-start c-end]} (axis-cfg dir)
        st          (:style node)
        gap-of      (fn [g content] (or (resolve-len (if (= :normal g) (:gap st) g) content) 0))
        main-gap    (gap-of (if (= dir :row) (:column-gap st) (:row-gap st)) content-main)
        cross-gap   (gap-of (if (= dir :row) (:row-gap st) (:column-gap st)) content-cross)
        wrap?       (= :wrap (:flex-wrap st))
        align-items (:align-items st)
        by-main     (fn [w h] (if (= main :width) [w h] [h w]))
        m-side      (fn [c s] (side-px (:margin (:style c)) s (:width child-avail)))
        min-of      (fn [ax cst content] (resolve-len ((keyword (str "min-" (name ax))) cst) content))
        max-of      (fn [ax cst content] (resolve-len ((keyword (str "max-" (name ax))) cst) content))
        ;; a nowrap container is single-line, so a stretch item with an auto cross
        ;; and a definite container cross is sized to that cross up front; the item
        ;; is then measured/laid at its real cross size (column: text wraps at the
        ;; right width, so its height comes out right).
        cross-force (fn [cst align cm-start cm-end]
                      (when (and (not wrap?) (number? content-cross)
                                 (= :stretch align) (= :auto (cross cst)))
                        (clamp (- content-cross cm-start cm-end)
                               (min-of cross cst content-cross) (max-of cross cst content-cross))))
        mk-info     (fn [c]
                      (let [cst   (:style c)
                            cms   (m-side c c-start)  cme (m-side c c-end)
                            align (resolved-align cst align-items)
                            ;; images are replaced elements: align stretch never
                            ;; overrides their intrinsic/aspect size.
                            cf    (when (not= :image (:node c)) (cross-force cst align cms cme))
                            base  (child-main c main content-main cf child-avail opts)]
                        {:node c :base base :hyp (clamp base (min-of main cst content-main) (max-of main cst content-main))
                         :grow (:flex-grow cst) :shrink (:flex-shrink cst) :cf cf
                         :min (min-of main cst content-main) :max (max-of main cst content-main)
                         :m-start (m-side c m-start) :m-end (m-side c m-end)
                         :c-mstart cms :c-mend cme}))
        infos       (mapv mk-info (:children node))
        lines       (break-lines infos content-main main-gap wrap?)
        lay         (fn [info t] (let [[w h] (by-main t (:cf info))]
                                   (flex-layout (:node info) child-avail {:width w :height h} opts)))
        ;; pass 1: flex each line's main sizes, lay items out, measure line cross
        laid-lines
        (mapv (fn [line]
                (let [n         (count line)
                      gap-total (* (max 0 (dec n)) main-gap)
                      sum-outer (reduce + 0 (map #(+ (:hyp %) (:m-start %) (:m-end %)) line))
                      line-main (or content-main (+ sum-outer gap-total))
                      free      (- line-main gap-total sum-outer)
                      targets   (if (and content-main (not (zero? free)))
                                  (resolve-flexible line free)
                                  (mapv :hyp line))
                      laid      (mapv lay line targets)
                      aligns    (mapv #(resolved-align (:style (:node %)) align-items) line)
                      outers    (mapv (fn [l info] (+ (get-in l [:layout cross]) (:c-mstart info) (:c-mend info))) laid line)
                      baselines (mapv #(item-baseline %1 %2 cross) laid line)
                      ascent    (reduce max 0 (keep-indexed #(when (= :baseline (nth aligns %1)) %2) baselines))
                      line-cross (reduce max 0 (map-indexed
                                                (fn [i o] (if (= :baseline (nth aligns i))
                                                            (+ ascent (- o (nth baselines i)))
                                                            o))
                                                outers))
                      main-used (+ gap-total (reduce + 0 (map #(+ (get-in %1 [:layout main]) (:m-start %2) (:m-end %2)) laid line)))]
                  {:line line :laid laid :aligns aligns :baselines baselines
                   :ascent ascent :line-cross line-cross :main-used main-used}))
              lines)
        ;; §9.4.8: a single line in a definite-cross container takes the whole
        ;; container cross size, so :stretch constrains an auto-cross child down
        ;; to it (and a nested wrap row then wraps) instead of overflowing.
        laid-lines  (if (and content-cross (= 1 (count laid-lines)))
                      (update laid-lines 0 assoc :line-cross content-cross)
                      laid-lines)
        n-lines     (count laid-lines)
        main-size   (or content-main (reduce max 0 (map :main-used laid-lines)))
        lines-cross (+ (reduce + 0 (map :line-cross laid-lines))
                       (* (max 0 (dec n-lines)) cross-gap))
        cross-size  (or content-cross lines-cross)
        ;; align-content: :stretch enlarges each line, others distribute leftover
        ac          (:align-content st)
        extra       (max 0 (- cross-size lines-cross))
        stretch-add (if (and (= :stretch ac) (pos? n-lines)) (/ extra n-lines) 0)
        [ac-start ac-step] (if (= :stretch ac) [0 0] (justify-offsets ac n-lines extra cross-gap))
        ;; pass 2: place lines along the cross axis, items within each line
        positioned
        (:out
         (reduce
          (fn [{:keys [cpos out]} ld]
            (let [line-cross (+ (:line-cross ld) stretch-add)
                  just-free  (- main-size (:main-used ld))
                  [ms mstep] (justify-offsets (:justify-content st) (count (:line ld)) just-free main-gap)
                  line-out
                  (:items
                   (reduce
                    (fn [{:keys [mp i items]} _]
                      (let [info  (nth (:line ld) i)
                            c     (:node info)
                            align (nth (:aligns ld) i)
                            l0    (nth (:laid ld) i)
                            l     (if (and (= :stretch align) (= :auto (cross (:style c)))
                                           (not= :image (:node c)))
                                    (let [tgt   (clamp (- line-cross (:c-mstart info) (:c-mend info))
                                                       (min-of cross (:style c) content-cross)
                                                       (max-of cross (:style c) content-cross))
                                          [w h] (by-main (get-in l0 [:layout main]) tgt)]
                                      (flex-layout c child-avail {:width w :height h} opts))
                                    l0)
                            outer (+ (get-in l [:layout cross]) (:c-mstart info) (:c-mend info))
                            cin   (case align
                                    :flex-end (- line-cross outer)
                                    :center   (/ (- line-cross outer) 2)
                                    :baseline (- (:ascent ld) (nth (:baselines ld) i))
                                    0)
                            cabs  (+ cpos cin (:c-mstart info))
                            mabs  (+ mp (:m-start info))
                            [x y] (if (= main :width) [mabs cabs] [cabs mabs])
                            adv   (+ (get-in l [:layout main]) (:m-start info) (:m-end info) main-gap mstep)]
                        {:mp (+ mp adv) :i (inc i) :items (conj items (translate l x y))}))
                    {:mp ms :i 0 :items []}
                    (:line ld)))]
              {:cpos (+ cpos line-cross cross-gap ac-step) :out (into out line-out)}))
          {:cpos ac-start :out []}
          laid-lines))]
    {:children positioned :main main-size :cross cross-size}))

(defn- abs-layout
  "Lay out an absolutely-positioned child against this node's border box
   (`bw`x`bh`). Uses width/height + top/left/right/bottom; a missing inset falls
   back to the static position 0 (a simplification: no full static-position
   tracking yet)."
  [child bw bh opts]
  (let [st   (:style child)
        left (resolve-len (:left st) bw)   right  (resolve-len (:right st) bw)
        top  (resolve-len (:top st) bh)    bottom (resolve-len (:bottom st) bh)
        w    (or (resolve-len (:width st) bw)  (when (and left right)  (- bw left right)))
        h    (or (resolve-len (:height st) bh) (when (and top bottom) (- bh top bottom)))
        l    (flex-layout child {:width bw :height bh} {:width w :height h} opts)
        x    (or left (when right (- bw right (get-in l [:layout :width]))) 0)
        y    (or top  (when bottom (- bh bottom (get-in l [:layout :height]))) 0)]
    (translate l x y)))

(defn- text-layout
  "Size a :text leaf via the injected :measure fn. With no measure it sizes to its
   explicit width/height or zero."
  [node avail forces opts]
  (let [st (:style node)
        measure (:measure opts)
        avail-w (or (:width forces) (resolve-len (:width st) (:width avail)) (:width avail))
        m (when measure (measure node {:width avail-w}))]
    (assoc node :layout
           {:x 0 :y 0
            :width  (or (:width forces)  (:width m)  (resolve-len (:width st) (:width avail))  0)
            :height (or (:height forces) (:height m) (resolve-len (:height st) (:height avail)) 0)}
           :measured m)))

(defn- image-layout
  "Size an :image leaf (a CSS replaced element). The intrinsic size is the
   header's pixels at 0.75pt each; a parent force or an authored width/height
   overrides. When exactly one dimension resolves, the other follows the aspect
   ratio; when neither does, the intrinsic size is used."
  [node forces]
  (let [st (:style node)
        iw (* (:width (:src node)) px->pt)
        ih (* (:height (:src node)) px->pt)
        w0 (or (:width forces)  (resolve-len (:width st) nil))
        h0 (or (:height forces) (resolve-len (:height st) nil))
        [w h] (cond
                (and w0 h0) [w0 h0]
                w0          [w0 (* w0 (/ ih iw))]
                h0          [(* h0 (/ iw ih)) h0]
                :else       [iw ih])]
    (assoc node :layout {:x 0 :y 0 :width w :height h})))

(defn flex-layout
  "Lay out one node given its containing block's content `avail` {:width :height}
   (each a number or nil = indefinite) and optional parent-`forces`
   {:width :height} pinning its border box. Returns the node with `:layout` on it
   and every descendant, positions local to the node's own border box."
  ([node avail] (flex-layout node avail nil {}))
  ([node avail forces opts]
   (cond
     (= :image (:node node)) (image-layout node forces)
     (= :text (:node node))  (text-layout node avail forces opts)
     :else
     (let [st   (:style node)
           dir  (:flex-direction st)
           {:keys [main cross]} (axis-cfg dir)
           pad  (:padding st)
           bd   (:border-width st)
           aw   (:width avail)
           pt   (side-px pad :top aw)    pb (side-px pad :bottom aw)
           pl   (side-px pad :left aw)   pr (side-px pad :right aw)
           bdt  (side-px bd :top aw)     bdb (side-px bd :bottom aw)
           bdl  (side-px bd :left aw)    bdr (side-px bd :right aw)
           ins-x (+ pl bdl)  ins-y (+ pt bdt)      ; content-box origin within border box
           edge-x (+ pl pr bdl bdr)  edge-y (+ pt pb bdt bdb)
           ;; own border box from forces / style (nil = content-sized)
           bw   (or (:width forces)  (resolve-len (:width st) aw))
           bh   (or (:height forces) (resolve-len (:height st) (:height avail)))
           content-w (when bw (- bw edge-x))
           content-h (when bh (- bh edge-y))
           child-avail {:width content-w :height content-h}
           [in-flow abs] ((juxt remove filter)
                          #(= :absolute (:position (:style %))) (:children node))
           flow (place-children (assoc node :children (vec in-flow))
                                dir
                                (if (= dir :row) content-w content-h)
                                (if (= dir :row) content-h content-w)
                                child-avail opts)
           final-w (or bw (+ (if (= dir :row) (:main flow) (:cross flow)) edge-x))
           final-h (or bh (+ (if (= dir :row) (:cross flow) (:main flow)) edge-y))
           final-w (if (:width forces) final-w
                       (clamp final-w (resolve-len (:min-width st) aw)  (resolve-len (:max-width st) aw)))
           final-h (if (:height forces) final-h
                       (clamp final-h (resolve-len (:min-height st) (:height avail)) (resolve-len (:max-height st) (:height avail))))
           ;; content-box origin (border box + border + padding); shift in-flow children in
           placed  (mapv #(translate % ins-x ins-y) (:children flow))
           abs-placed (mapv #(abs-layout % final-w final-h opts) abs)]
       (assoc node
              :layout {:x 0 :y 0 :width final-w :height final-h}
              :children (into placed abs-placed))))))

(defn solve
  "Solve a normalized tree to absolute boxes. `avail` is the page-content size
   {:width :height} (height may be nil = unbounded, what pagination hands you).
   `opts` may carry a `:measure` fn for text leaves. Returns the tree with
   `:layout` on every node in a top-left page-content coordinate space."
  ([node avail] (solve node avail {}))
  ([node avail opts] (flex-layout node avail nil opts)))
