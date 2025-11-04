(ns pdf.context.form-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.form :as form]
   [pdf.context.image :as img]
   [pdf.context.page :as page]
   [pdf.context.pdf :as pdf]
   [pdf.context.stream :refer [pdf-stream?]]
   [pdf.file.xref :refer [ref?]]
   [pdf.utils.dimension :refer [points->dim]]))

(def ^:private box-ops
  [[:set-rgb-fill 1 0 0] [:rectangle 0 0 10 10] [:fill]])

(defn- embedded-objects
  "The embedded XObject streams of `subtype` after the pipeline's embed passes."
  [ctx subtype]
  (->> (:objects (pdf/number-context
                  (pdf/resolve-context-objects
                   (pdf/embed-forms (pdf/embed-images ctx)))))
       (filter #(and (pdf-stream? %) (= subtype (get-in % [:dict :subtype]))))))

(defn- page-with
  [placements]
  (page/with-page (pdf/new-pdf)
    (reduce (fn [pg [f x y opts]] (form/with-form pg f x y (or opts {})))
            (page/new-page (points->dim 200) (points->dim 200))
            placements)))

(deftest form-object-shape
  (let [o (form/form-object (form/new-form [0 0 10 10] box-ops))]
    (is (pdf-stream? o))
    (is (= :xobject (get-in o [:dict :type])))
    (is (= :form (get-in o [:dict :subtype])))
    (is (= [0 0 10 10] (get-in o [:dict :bbox])))
    (testing "the ops compile into the stream payload"
      (is (= "1 0 0 rg\n0 0 10 10 re\nf"
             (String. ^bytes (:bytes o) "ISO-8859-1"))))))

(deftest with-form-places-through-the-ctm
  (testing "natural size is an unscaled translation"
    (let [pg (form/with-form (page/new-page (points->dim 200) (points->dim 200))
               (form/new-form [0 0 10 10] box-ops) 30 40)]
      (is (= [[:save-state]
              [:concat-matrix 1 0 0 1 30 40]
              [:draw-xobject 'X0]
              [:restore-state]]
             (first (:contents pg))))))
  (testing "a nonzero bbox origin translates so its corner lands at (x, y)"
    (let [pg (form/with-form (page/new-page (points->dim 200) (points->dim 200))
               (form/new-form [10 20 30 40] box-ops) 5 5 {:width 40})
          [_ cm] (first (:contents pg))]
      ;; bbox is 20x20, so :width 40 doubles both axes: tx = 5 - 2*10, ty = 5 - 2*20.
      (is (= [:concat-matrix 2 0 0 2 -15 -35] cm)))))

(deftest reused-form-embeds-once
  (let [f   (form/new-form [0 0 10 10] box-ops)
        ctx (page-with [[f 20 20] [f 100 20 {:width 50}]])]
    (is (= 1 (count (embedded-objects ctx :form))))))

(deftest distinct-forms-embed-separately
  (let [ctx (page-with [[(form/new-form [0 0 10 10] box-ops) 20 20]
                        [(form/new-form [0 0 20 20] box-ops) 100 20]])]
    (is (= 2 (count (embedded-objects ctx :form))))))

(deftest forms-and-images-share-the-xobject-pool
  (let [jpg (img/new-image "test/resources/images/rgb.jpg")
        pg  (-> (page/new-page (points->dim 200) (points->dim 200))
                (img/with-image jpg 20 100 {:width 100})
                (form/with-form (form/new-form [0 0 10 10] box-ops) 20 20))
        ctx (page/with-page (pdf/new-pdf) pg)]
    (is (= 1 (count (embedded-objects ctx :image))))
    (is (= 1 (count (embedded-objects ctx :form))))
    (testing "both embed passes swap the page resources over to refs"
      (let [resources (-> (pdf/embed-forms (pdf/embed-images ctx))
                          :objects
                          (->> (keep #(get-in % [:obj :resources :xobject])))
                          first)]
        (is (every? ref? (vals resources)))))))

(deftest form-documents-serialize
  (let [ctx (page-with [[(form/new-form [0 0 10 10] box-ops) 20 20]])]
    (is (pos? (alength (pdf/serialize ctx))))))
