(ns pdf.layout.core
  "The public layout API. `pages` lays a hiccup tree out and returns one render
   map per page; `with-layout` adds those pages to a pdf context. This is a
   lowering pass onto the existing ops layer: the low-level
   `with-text`/`with-stream`/raw-ops API stays untouched underneath, and users who
   want manual positioning drop to it or to `:position :absolute` in the tree."
  (:require
   [pdf.context.content :refer [ops->stream]]
   [pdf.context.page :refer [resource-keys with-page]]
   [pdf.layout.emit :as emit]
   [pdf.layout.flex :as flex]
   [pdf.layout.paginate :as paginate]
   [pdf.layout.text :as text]
   [pdf.layout.tree :as tree]))

(def ^:private default-page-size [612 792])   ; US Letter, points
(def ^:private default-margin 36)

(defn- solve-block
  "Solve a hiccup block filling `content-w`, returning its solved tree (nil in,
   nil out). Used for the document body and for header/footer blocks."
  [t content-w]
  (when t
    (flex/solve (assoc-in (tree/normalize t) [:style :width] content-w)
                {:width content-w :height nil}
                {:measure text/measure})))

(defn pages
  "Lay out hiccup `tree` and return one render map per page:

     {:media-box [0 0 pw ph]
      :contents  <content stream>
      :resources {:font {\"F0\" font-ctx ...} :xobject {\"X0\" image-ctx ...}}
      :font-usage {\"F0\" {gid cp ...} ...}}

   opts: `:page-size` [w h] in points (default US Letter 612×792), `:margin`
   (points on every side, default 36), and `:header`/`:footer` hiccup blocks
   repeated on every page (they reserve their measured height from the content
   area, so the body paginates into what's left). The tree is solved once against
   an unbounded height, then paginated into pages of the remaining content height;
   each page emits its header, body slice and footer with a shared font key map.
   Font resource keys and usage mirror `with-text` so pdf.context.pdf/embed-fonts
   subsets and embeds them the same way."
  ([tree] (pages tree {}))
  ([tree {:keys [page-size margin header footer]
          :or {page-size default-page-size margin default-margin}}]
   (let [[pw ph]   page-size
         content-w (- pw (* 2 margin))
         hroot     (solve-block header content-w)
         froot     (solve-block footer content-w)
         hh        (if hroot (get-in hroot [:layout :height]) 0)
         fh        (if froot (get-in froot [:layout :height]) 0)
         content-h (- ph (* 2 margin) hh fh)
         ;; the page content box is the root's containing block; a document root
         ;; fills it (width :auto would otherwise shrink-to-fit and never wrap).
         root      (solve-block tree content-w)
         ;; resource keys are minted once per document (over the unpaginated
         ;; body + header/footer), so a font keeps one key on every page.
         trees     (remove nil? [hroot root froot])
         key-of    (resource-keys :font (distinct (mapcat emit/fonts-used trees)))
         img-key   (resource-keys :xobject (distinct (mapcat emit/images-used trees)))
         resources (cond-> {:font (into {} (map (fn [[f k]] [k f])) key-of)}
                     (seq img-key) (assoc :xobject (into {} (map (fn [[im k]] [k im])) img-key)))]
     (mapv (fn [page-tree]
             (let [emit1  (fn [t origin] (emit/emit t ph origin key-of img-key))
                   parts  (cond-> [(emit1 page-tree [margin (+ margin hh)])]
                            hroot (conj (emit1 hroot [margin margin]))
                            froot (conj (emit1 froot [margin (- ph margin fh)])))
                   ops    (into [] (mapcat :ops) parts)
                   usage  (apply merge-with merge (map :usage parts))]
               {:media-box  [0 0 pw ph]
                :contents   (ops->stream ops)
                :resources  resources
                :font-usage (or usage {})}))
           (paginate/paginate root content-h)))))

(defn- render->page
  "A page context from a `pages` render map, shaped like the pages `with-text`
   builds (so embed-fonts and the serializer treat it identically)."
  [render]
  (-> {:type :page}
      (merge (select-keys render [:media-box :resources :font-usage]))
      (assoc :contents [(:contents render)])))

(defn with-layout
  "Add one page per `pages` result to the pdf context `ctx`, via with-page."
  ([ctx tree] (with-layout ctx tree {}))
  ([ctx tree opts]
   (reduce (fn [ctx render] (with-page ctx (render->page render)))
           ctx
           (pages tree opts))))
