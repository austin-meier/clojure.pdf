(ns pdf.layout.paginate
  "Pagination. CSS fragmentation is the one place we're on our own, so it's a
   separate pass over a tree solved against an unbounded height (one tall virtual
   page). It reflows that tree into pages of `content-h`, threading a per-page
   cursor and applying a few rules:

   - a node that fits in the remaining space stays;
   - `:break-inside :avoid` or an unsplittable leaf that doesn't fit is pushed
     whole to the next page (and, if taller than a page, overflows its own page);
   - a column-flow `:div` splits its children across the boundary;
   - a `:text` node splits between its already-computed lines, never mid-line.

   Splitting shifts `y` by the consumed height; `x` never changes. The output is
   a vector of page trees with boxes in each page's own top-left coordinate
   space. Each page tree is a synthetic root `:div` whose children are the
   drawable fragments on that page (background boxes and text) in painter's order,
   flattened, since emit only walks nodes to paint rects and text and a split
   container's background is painted per fragment anyway.

   Limitations: only column-flow views split (a row is atomic), and an
   absolutely-positioned child rides on its container's first page."
  (:require
   [pdf.layout.style :as style]
   [pdf.layout.tree :as tree]))

(defn- lx [n] (:x (:layout n)))
(defn- ly [n] (:y (:layout n)))
(defn- lw [n] (:width (:layout n)))
(defn- lh [n] (:height (:layout n)))

(defn- drawable? [node]
  (or (= :text (:node node))
      (= :image (:node node))
      (some? (:background-color (:style node)))))

(defn- in-flow? [node] (not= :absolute (:position (:style node))))

(defn- splittable-column?
  "A view we may break between its children: column flow, not break-inside:avoid,
   with at least one in-flow child."
  [node]
  (and (= :div (:node node))
       (= :column (:flex-direction (:style node)))
       (not= :avoid (:break-inside (:style node)))
       (some in-flow? (:children node))))

(defn- paint-node
  "A drawable node reduced to a page-local leaf fragment: `y` shifted by `delta`,
   children dropped (its descendants are flattened as their own fragments)."
  [node delta]
  (-> node
      (assoc-in [:layout :y] (+ (ly node) delta))
      (dissoc :children)))

(defn- drawable-atoms
  "Preorder drawable fragments of an atomic subtree, all on `page`, `y` shifted by
   `delta`. Atomic means unsplit, so text renders whole."
  [node delta page]
  (into []
        (comp (filter drawable?)
              (map (fn [n] {:page page :node (paint-node n delta)})))
        (tree/preorder node)))

(declare place)

(defn- place-atomic
  "Place an unsplittable subtree of height h. Fits -> current page; else push to
   the next page top; already at a page top -> stay and overflow."
  [node cursor page ph]
  (let [h (lh node)
        [pg y] (cond
                 (<= (+ cursor h) ph) [page cursor]
                 (zero? cursor)       [page 0]
                 :else                [(inc page) 0])]
    {:atoms (drawable-atoms node (- y (ly node)) pg)
     :page pg :cursor (+ y h)}))

(defn- place-text
  "Split a text node between its lines. Each line drops to the next page when it
   would overflow the remaining space; a single line taller than a page overflows
   at the page top. Consecutive lines on one page become one text fragment."
  [node cursor page ph]
  (let [m     (:measured node)
        lines (vec (:lines m))
        step  (if (seq lines) (/ (:height m) (count lines)) 0)
        frag  (fn [pg y ls]
                {:page pg
                 :node (-> node
                           (assoc :measured (assoc m :lines ls :height (* (count ls) step)))
                           (assoc-in [:layout :y] y)
                           (assoc-in [:layout :height] (* (count ls) step)))})]
    (loop [i 0, cur cursor, pg page, gy cursor, acc [], groups []]
      (if (= i (count lines))
        {:atoms  (mapv #(apply frag %) (cond-> groups (seq acc) (conj [pg gy acc])))
         :page   pg
         :cursor cur}
        (if (or (<= (+ cur step) ph) (zero? cur))   ; fits, or overflow at page top
          (recur (inc i) (+ cur step) pg (if (empty? acc) cur gy)
                 (conj acc (nth lines i)) groups)
          (recur i 0 (inc pg) 0 []                  ; break: retry line i on next page
                 (cond-> groups (seq acc) (conj [pg gy acc]))))))))

(defn- place-column
  "Split a column view between its children. Vertical gaps (padding-top, inter-
   child gap/margins) come from the solved positions so the reflow preserves
   spacing; page breaks add the extra whitespace. Emits the container's own
   background per page fragment it spans."
  [node cursor page ph]
  (let [children (filterv in-flow? (:children node))
        abs      (filterv (complement in-flow?) (:children node))
        {:keys [pg cur atoms]}
        (reduce
         (fn [{:keys [pg cur atoms prev-bottom]} child]
           (let [gap  (if (nil? prev-bottom)
                        (- (ly child) (ly node))          ; leading inset before first child
                        (- (ly child) prev-bottom))       ; gap between siblings
                 res  (place child (+ cur gap) pg ph)]
             {:pg (:page res) :cur (:cursor res)
              :atoms (into atoms (:atoms res))
              :prev-bottom (+ (ly child) (lh child))}))
         {:pg page :cur cursor :atoms [] :prev-bottom nil}
         children)
        last-child (peek children)
        tail       (- (+ (ly node) (lh node)) (+ (ly last-child) (lh last-child)))
        bottom-end (+ cur tail)
        bg (when (:background-color (:style node))
             (for [p (range page (inc pg))]
               (let [top    (if (= p page) cursor 0)
                     bottom (if (= p pg) bottom-end ph)]
                 {:page p
                  :node (-> node
                            (dissoc :children)
                            (assoc :layout {:x (lx node) :y top
                                            :width (lw node) :height (- bottom top)}))})))
        abs-atoms (mapcat #(drawable-atoms % (- cursor (ly node)) page) abs)]
    {:atoms  (vec (concat bg atoms abs-atoms))
     :page   pg
     :cursor bottom-end}))

(defn- place
  "Place one node starting at page-local `cursor` on `page`, page content height
   `ph`. Returns {:atoms [{:page :node}...] :page :cursor}."
  [node cursor page ph]
  (cond
    (= :text (:node node))    (place-text node cursor page ph)
    (splittable-column? node) (place-column node cursor page ph)
    :else                     (place-atomic node cursor page ph)))

(defn paginate
  "Slice a solved tree (solved with an unbounded height) into page trees of
   `content-h`. Returns a vector of page trees, each a synthetic root `:div`
   whose children are the drawable fragments on that page in painter's order,
   with page-local top-left coordinates."
  [root content-h]
  (let [{:keys [atoms]} (place root 0 0 content-h)
        by-page (group-by :page atoms)
        n       (if (seq atoms) (inc (reduce max (map :page atoms))) 1)]
    (mapv (fn [p]
            {:node     :div
             :style    style/defaults
             :layout   {:x 0 :y 0 :width (lw root) :height content-h}
             :children (mapv :node (get by-page p []))})
          (range n))))
