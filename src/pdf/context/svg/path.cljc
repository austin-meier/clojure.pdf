(ns pdf.context.svg.path
  "SVG path data (the `d` attribute) -> PDF path-construction ops
   (:move-to/:line-to/:curve-to/:close-path), pure data in SVG user space —
   the y-flip to PDF space is the caller's transform (pdf.context.svg emits it).

   Follows the SVG 1.1 path grammar: implicit command repetition (a repeated M
   continues as L), relative variants, shorthand reflection (S/T), and arc
   flags packed against the next number (`a1 1 0 011.5.5`, as minifiers emit).
   Quadratics lift to cubics exactly; arcs convert via the spec's
   endpoint->center parameterization (F.6.5) into <=90-degree cubic segments.")

(defn parse-num
  "Parse an SVG number string. `parse-double` can't be trusted with the SVG
   forms `.5` and `1.` on cljs, so this goes to the host parser directly."
  [s]
  #?(:clj  (Double/parseDouble s)
     :cljs (js/parseFloat s)))

;; Numbers are matched longest-first (`1.5.5` -> 1.5, .5; `1-2` -> 1, -2);
;; separators fall out of re-seq. Arc flags packed into a following number
;; survive as one token and are split back apart at consumption during `take-args`.
(def ^:private token-re
  #"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")

(defn- command-token? [t]
  (boolean (re-matches #"[MmLlHhVvCcSsQqTtAaZz]" t)))

;; Relative (lowercase) command -> its absolute base. Chars on the JVM are
;; single-char strings on cljs, so case tests go through this map, not
;; Character/isLowerCase or str/lower-case (which returns a string, never
;; equal to a char).
(def ^:private base-command
  {\m \M \l \L \h \H \v \V \c \C \s \S \q \Q \t \T \a \A \z \Z})

(defn- base-of [c] (get base-command c c))

(def ^:private command-arg-kinds
  {\M [:num :num]
   \L [:num :num]
   \H [:num]
   \V [:num]
   \C [:num :num :num :num :num :num]
   \S [:num :num :num :num]
   \Q [:num :num :num :num]
   \T [:num :num]
   \A [:num :num :num :flag :flag :num :num]
   \Z []})

(defn- take-args
  "Consume one command's operands from the token seq, returning [args tokens].
   A :flag is a single 0/1 digit that may share a token with whatever follows
   (`011.5.5` = 0, 1, 1.5, .5), so only its first character is taken and the
   remainder goes back on the seq."
  [kinds tokens]
  (loop [kinds kinds, tokens tokens, args []]
    (if (empty? kinds)
      [args tokens]
      (let [t (first tokens)]
        (when (or (nil? t) (command-token? t))
          (throw (ex-info "Path command is missing operands" {:next-token t})))
        (case (first kinds)
          :num  (recur (rest kinds) (rest tokens) (conj args (parse-num t)))
          :flag (let [flag (first t)]
                  (when-not (#{\0 \1} flag)
                    (throw (ex-info "Arc flag must be 0 or 1" {:token t})))
                  (recur (rest kinds)
                         (if (> (count t) 1)
                           (cons (subs t 1) (rest tokens))
                           (rest tokens))
                         (conj args (if (= \1 flag) 1 0)))))))))

(defn- arc-segment
  "One cubic fitting the arc slice [t1 t2] (radians, <= pi/2) of the ellipse
   centered (cx, cy) with radii (rx, ry) rotated phi: control points sit along
   the endpoint tangents at distance k = 4/3 * tan((t2-t1)/4)."
  [cx cy rx ry cos-phi sin-phi t1 t2]
  (let [k  (* (/ 4.0 3.0) (Math/tan (/ (- t2 t1) 4)))
        at (fn [t]
             (let [ex (* rx (Math/cos t)), ey (* ry (Math/sin t))]
               [(+ cx (- (* cos-phi ex) (* sin-phi ey)))
                (+ cy (+ (* sin-phi ex) (* cos-phi ey)))]))
        d-at (fn [t]
               (let [ex (* (- rx) (Math/sin t)), ey (* ry (Math/cos t))]
                 [(- (* cos-phi ex) (* sin-phi ey))
                  (+ (* sin-phi ex) (* cos-phi ey))]))
        [x1 y1]   (at t1)
        [x2 y2]   (at t2)
        [dx1 dy1] (d-at t1)
        [dx2 dy2] (d-at t2)]
    [:curve-to
     (+ x1 (* k dx1)) (+ y1 (* k dy1))
     (- x2 (* k dx2)) (- y2 (* k dy2))
     x2 y2]))

(defn- arc->ops
  "Elliptical arc from (x1, y1) to (x2, y2) as cubic ops — SVG F.6.5/F.6.6:
   endpoint->center parameterization with radii scaled up when unreachable,
   then <=90-degree slices. Zero radii degrade to a line per the spec; a
   zero-length arc draws nothing."
  [x1 y1 rx ry rot-deg large? sweep? x2 y2]
  (cond
    (and (== x1 x2) (== y1 y2)) []
    (or (zero? rx) (zero? ry))  [[:line-to x2 y2]]
    :else
    (let [phi     (* rot-deg (/ Math/PI 180))
          cos-phi (Math/cos phi)
          sin-phi (Math/sin phi)
          hdx     (/ (- x1 x2) 2)
          hdy     (/ (- y1 y2) 2)
          x1p     (+ (* cos-phi hdx) (* sin-phi hdy))
          y1p     (+ (* (- sin-phi) hdx) (* cos-phi hdy))
          rx      (abs rx)
          ry      (abs ry)
          lambda  (+ (/ (* x1p x1p) (* rx rx)) (/ (* y1p y1p) (* ry ry)))
          scale   (if (> lambda 1) (Math/sqrt lambda) 1)
          rx      (* scale rx)
          ry      (* scale ry)
          num     (- (* rx rx ry ry) (* rx rx y1p y1p) (* ry ry x1p x1p))
          den     (+ (* rx rx y1p y1p) (* ry ry x1p x1p))
          co      (* (if (= large? sweep?) -1 1) (Math/sqrt (max 0.0 (/ num den))))
          cxp     (* co (/ (* rx y1p) ry))
          cyp     (* co (- (/ (* ry x1p) rx)))
          cx      (+ (- (* cos-phi cxp) (* sin-phi cyp)) (/ (+ x1 x2) 2))
          cy      (+ (* sin-phi cxp) (* cos-phi cyp) (/ (+ y1 y2) 2))
          t1      (Math/atan2 (/ (- y1p cyp) ry) (/ (- x1p cxp) rx))
          t2      (Math/atan2 (/ (- (- y1p) cyp) ry) (/ (- (- x1p) cxp) rx))
          dt      (- t2 t1)
          dt      (cond
                    (and sweep? (neg? dt))       (+ dt (* 2 Math/PI))
                    (and (not sweep?) (pos? dt)) (- dt (* 2 Math/PI))
                    :else                        dt)
          n       (int (Math/ceil (/ (abs dt) (/ Math/PI 2))))
          step    (/ dt n)
          curves  (mapv (fn [i]
                          (arc-segment cx cy rx ry cos-phi sin-phi
                                       (+ t1 (* i step)) (+ t1 (* (inc i) step))))
                        (range n))]
      ;; Pin the final endpoint to the exact target so numeric drift across
      ;; slices can't leave the path open.
      (update curves (dec n) #(-> % (assoc 5 x2) (assoc 6 y2))))))

(defn- quad->curve
  "Exact cubic lift of a quadratic: controls at 2/3 of the way from each
   endpoint to the quadratic control point."
  [cx cy qx qy x y]
  [:curve-to
   (+ cx (* (/ 2.0 3.0) (- qx cx))) (+ cy (* (/ 2.0 3.0) (- qy cy)))
   (+ x (* (/ 2.0 3.0) (- qx x)))   (+ y (* (/ 2.0 3.0) (- qy y)))
   x y])

(defn- reflect
  "The reflection of control point (px, py) about the current point, or the
   current point itself when the previous command wasn't in `preceding` —
   the S/T shorthand rule."
  [preceding prev-cmd [px py] cx cy]
  (if (and px (contains? preceding prev-cmd))
    [(- (* 2 cx) px) (- (* 2 cy) py)]
    [cx cy]))

(defn- apply-command
  "Apply one parsed command to the walk state, returning the state with ops
   appended. State: current point (:x :y), subpath start (:sx :sy), previous
   control points for shorthand reflection, and the previous base command."
  [{:keys [x y sx sy cubic-cp quad-cp cmd] :as st} c args]
  (let [rel?  (contains? base-command c)
        base  (base-of c)
        rx    (if rel? x 0)
        ry    (if rel? y 0)
        clear #(assoc % :cubic-cp nil :quad-cp nil :cmd base)]
    (case base
      \M (let [[mx my] args
               mx (+ rx mx), my (+ ry my)]
           (-> st clear
               (assoc :x mx :y my :sx mx :sy my)
               (update :ops conj [:move-to mx my])))
      \L (let [[lx ly] args
               lx (+ rx lx), ly (+ ry ly)]
           (-> st clear (assoc :x lx :y ly) (update :ops conj [:line-to lx ly])))
      \H (let [lx (+ rx (first args))]
           (-> st clear (assoc :x lx) (update :ops conj [:line-to lx y])))
      \V (let [ly (+ ry (first args))]
           (-> st clear (assoc :y ly) (update :ops conj [:line-to x ly])))
      \C (let [[x1 y1 x2 y2 ex ey] (map + [rx ry rx ry rx ry] args)]
           (-> st clear
               (assoc :x ex :y ey :cubic-cp [x2 y2])
               (update :ops conj [:curve-to x1 y1 x2 y2 ex ey])))
      \S (let [[x2 y2 ex ey] (map + [rx ry rx ry] args)
               [x1 y1] (reflect #{\C \S} cmd cubic-cp x y)]
           (-> st clear
               (assoc :x ex :y ey :cubic-cp [x2 y2])
               (update :ops conj [:curve-to x1 y1 x2 y2 ex ey])))
      \Q (let [[qx qy ex ey] (map + [rx ry rx ry] args)]
           (-> st clear
               (assoc :x ex :y ey :quad-cp [qx qy])
               (update :ops conj (quad->curve x y qx qy ex ey))))
      \T (let [[ex ey] (map + [rx ry] args)
               [qx qy] (reflect #{\Q \T} cmd quad-cp x y)]
           (-> st clear
               (assoc :x ex :y ey :quad-cp [qx qy])
               (update :ops conj (quad->curve x y qx qy ex ey))))
      \A (let [[arx ary rot laf swf ex ey] args
               ex (+ rx ex), ey (+ ry ey)]
           (-> st clear
               (assoc :x ex :y ey)
               (update :ops into (arc->ops x y arx ary rot (= 1 laf) (= 1 swf) ex ey))))
      \Z (-> st clear
             (assoc :x sx :y sy)
             (update :ops conj [:close-path])))))

(defn parse
  "Parse path data `d` into a vector of PDF path ops in SVG user space."
  [d]
  (loop [tokens (re-seq token-re (or d ""))
         st     {:x 0 :y 0 :sx 0 :sy 0 :cubic-cp nil :quad-cp nil :cmd nil :ops []}]
    (if (empty? tokens)
      (:ops st)
      (let [t (first tokens)
            [c tokens] (if (command-token? t)
                         [(first t) (rest tokens)]
                         ;; Bare operands repeat the previous command; extra
                         ;; coordinate pairs after M/m continue as lines.
                         (case (:cmd st)
                           nil (throw (ex-info "Path data must start with a moveto" {:token t}))
                           \Z  (throw (ex-info "Only a command may follow a closepath" {:token t}))
                           \M  [(if (= \m (:literal-cmd st)) \l \L) tokens]
                           [(:literal-cmd st) tokens]))
            _ (when (and (nil? (:cmd st)) (not= \M (base-of c)))
                (throw (ex-info "Path data must start with a moveto" {:command c})))
            [args tokens] (take-args (command-arg-kinds (base-of c)) tokens)]
        (recur tokens (assoc (apply-command st c args) :literal-cmd c))))))
