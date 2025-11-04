(ns pdf.context.image-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.image :as img]
   [pdf.context.image.xobject :as xobject]
   [pdf.context.page :as page]
   [pdf.context.pdf :as pdf]
   [pdf.context.stream :refer [pdf-stream?]]
   [pdf.file.xref :refer [indirect-ref?]]
   [pdf.bytes :as b]
   [pdf.utils.dimension :refer [inches->dim]]))

(def ^:private dir "test/resources/images/")

(deftest new-image-dispatches-on-magic-bytes
  (testing "Format comes from the magic bytes, not the extension"
    (is (= :jpeg (:format (img/new-image (str dir "rgb.jpg")))))
    (is (= :png  (:format (img/new-image (str dir "rgb.png")))))))

(deftest image-object-shapes
  (testing "JPEG becomes a DCTDecode Image XObject carrying the file bytes"
    (let [o (xobject/image-object (img/new-image (str dir "rgb.jpg")))]
      (is (pdf-stream? o))
      (is (= :image (get-in o [:dict :subtype])))
      (is (= :dct-decode (get-in o [:dict :filter])))))
  (testing "Non-alpha PNG becomes a FlateDecode stream with PNG predictors"
    (let [o (xobject/image-object (img/new-image (str dir "rgb.png")))]
      (is (= :flate-decode (get-in o [:dict :filter])))
      (is (= 15 (get-in o [:dict :decode-parms :predictor])))
      (is (= 3 (get-in o [:dict :decode-parms :colors])))))
  (testing "Adobe CMYK JPEG carries the inversion /Decode"
    (let [o (xobject/image-object (img/new-image (str dir "cmyk.jpg")))]
      (is (= [1 0 1 0 1 0 1 0] (get-in o [:dict :decode]))))))

(defn- image-objects
  "The embedded Image XObject streams after the pipeline's pre-serialize passes."
  [ctx]
  (->> (:objects (pdf/number-context (pdf/resolve-context-objects (pdf/embed-images ctx))))
       (filter #(and (pdf-stream? %) (= :image (get-in % [:dict :subtype]))))))

(defn- page-with
  [placements]
  (let [pg (reduce (fn [pg [im x y opts]] (img/with-image pg im x y opts))
                   (page/new-page (inches->dim 4) (inches->dim 3))
                   placements)]
    (page/with-page (pdf/new-pdf) pg)))

(deftest reused-context-embeds-once
  (testing "One new-image value placed twice yields a single embedded object"
    (let [jpg (img/new-image (str dir "rgb.jpg"))
          ctx (page-with [[jpg 20 20 {:width 100}] [jpg 150 20 {:width 60}]])]
      (is (= 1 (count (image-objects ctx)))))))

(deftest separately-loaded-copies-embed-once
  (testing "Two new-image loads of the same file dedupe by content hash"
    (let [a   (img/new-image (str dir "rgb.jpg"))
          b   (img/new-image (str dir "rgb.jpg"))
          ctx (page-with [[a 20 20 {:width 100}] [b 150 20 {:width 60}]])]
      (is (= 1 (count (image-objects ctx)))))))

(deftest distinct-contexts-embed-separately
  (let [jpg  (img/new-image (str dir "rgb.jpg"))
        png  (img/new-image (str dir "rgb.png"))
        ctx  (page-with [[jpg 20 20 {:width 100}] [png 150 20 {:width 100}]])]
    (is (= 2 (count (image-objects ctx))))))

;; The pass-through guard: the embedded stream bytes must equal the source
;; verbatim. This is the milestone's equivalent of the xref corruption guard —
;; it catches any "helpful" transformation sneaking into the pipeline.
(deftest jpeg-bytes-pass-through-verbatim
  (let [jpg (img/new-image (str dir "rgb.jpg"))
        ctx (page-with [[jpg 20 20 {:width 100}]])
        obj (first (image-objects ctx))]
    (is (= (b/unsigned-vec (:bytes jpg)) (b/unsigned-vec (:bytes obj))))))

(deftest png-idat-passes-through-verbatim
  (let [png (img/new-image (str dir "rgb.png"))
        ctx (page-with [[png 20 20 {:width 100}]])
        obj (first (image-objects ctx))]
    (is (= (b/unsigned-vec (:idat png)) (b/unsigned-vec (:bytes obj))))))

(deftest alpha-embeds-color-and-smask-as-indirect-objects
  (testing "An RGBA PNG embeds two image streams; the color image's /SMask is a
            top-level indirect ref, never a nested stream"
    (let [rgba (img/new-image (str dir "rgba.png"))
          ctx  (page-with [[rgba 20 20 {:width 100}]])
          objs (image-objects ctx)]
      (is (= 2 (count objs)))
      (is (some #(indirect-ref? (get-in % [:dict :smask])) objs)))))

(deftest sizing-follows-aspect-ratio
  (let [img4x3 {:width 16 :height 12}]
    (testing "both dimensions use them as given"
      (is (= [100.0 50.0] (mapv double (img/image-size img4x3 100 50)))))
    (testing "one dimension scales the other by the aspect ratio"
      (is (= [100.0 75.0] (mapv double (img/image-size img4x3 100 nil))))
      (is (= [40.0 30.0]  (mapv double (img/image-size img4x3 nil 30)))))
    (testing "neither uses intrinsic pixels"
      (is (= [16 12] (img/image-size img4x3 nil nil))))))

(deftest rejects-unknown-format
  (testing "A non-image file is refused by magic-byte sniffing"
    (is (thrown? clojure.lang.ExceptionInfo (img/new-image "deps.edn")))))
