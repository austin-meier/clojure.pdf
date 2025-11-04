(ns pdf.context.form
  "The form XObject authoring API, mirroring pdf.context.image: `new-form`
   wraps a vector of content ops and a bounding box into a form *context*
   (plain data), and `with-form` places it on a page. Pages reference the
   context by value in :resources :xobject; the real Form XObject is built at
   serialize time by pdf.context.pdf/embed-forms. Form contexts are pure data,
   so reusing one value across pages (or placing an equal one twice) embeds
   once. pdf.context.svg builds these from SVG trees."
  (:require
   [pdf.context.content :refer [compile-content]]
   [pdf.context.image :refer [image-size]]
   [pdf.context.page :refer [add-or-get-resource]]
   [pdf.context.stream :refer [string->stream]]
   [pdf.utils.dimension :refer [->points]]))

(defn new-form
  "A form context: `ops` drawn in the coordinate space bounded by `bbox`
   ([x0 y0 x1 y1] — the form's clip region and natural size). :resources, when
   given, is carried into the XObject dict for ops that name resources."
  ([bbox ops] (new-form bbox ops {}))
  ([bbox ops {:keys [resources]}]
   (cond-> {:type :form-context :bbox bbox :ops ops}
     resources (assoc :resources resources))))

(defn form-object
  "Build the embedded Form XObject for a form context: the ops compiled to a
   content stream under the /Form dict. A :resources map stays inline in the
   dict (legal PDF; the resolver leaves stream dicts alone)."
  [{:keys [bbox ops resources]}]
  (string->stream (compile-content ops)
                  (cond-> {:type :xobject :subtype :form :bbox bbox}
                    resources (assoc :resources resources))))

(defn form->ops
  "The four ops that draw the XObject `res-key` scaled by (sx, sy) with its
   space translated by (tx, ty). Unlike images, form space is the bbox, not
   the unit square, so the CTM carries scale factors rather than the size."
  [res-key sx sy tx ty]
  [[:save-state]
   [:concat-matrix sx 0 0 sy tx ty]
   [:draw-xobject (symbol res-key)]
   [:restore-state]])

(defn with-form
  "Place `form` on the page with its bbox's bottom-left corner at (x, y).
   x/y and the :width/:height opts take numbers (points) or Dimensions; one of
   width/height scales the other by the bbox aspect ratio, neither draws at
   the bbox's natural size."
  ([page-ctx form x y] (with-form page-ctx form x y {}))
  ([page-ctx form x y {:keys [width height]}]
   (let [[x0 y0 x1 y1] (:bbox form)
         [w h] (image-size {:width (- x1 x0) :height (- y1 y0)} width height)
         sx    (/ w (- x1 x0))
         sy    (/ h (- y1 y0))
         [page-ctx res-key] (add-or-get-resource page-ctx :xobject form)]
     (update page-ctx :contents (fnil conj [])
             (form->ops res-key sx sy
                        (- (->points x) (* sx x0))
                        (- (->points y) (* sy y0)))))))
