(ns pdf.context.jpeg-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pdf.context.image.jpeg :as jpeg]
   [pdf.utils.io :refer [file->bytes]]))

;; Tiny checked-in fixtures (<1KB): 16x12 gradients in each color model.
(defn- fixture [name] (file->bytes (str "test/resources/images/" name)))

(deftest reads-frame-header
  (testing "The marker scan reads dimensions, precision and component count"
    (let [rgb (jpeg/parse (fixture "rgb.jpg"))]
      (is (= 16 (:width rgb)))
      (is (= 12 (:height rgb)))
      (is (= 8 (:bits rgb)))
      (is (= 3 (:components rgb)))
      (is (= :device-rgb (:color-space rgb))))))

(deftest component-count-picks-color-space
  (is (= :device-gray (:color-space (jpeg/parse (fixture "gray.jpg")))))
  (is (= :device-rgb  (:color-space (jpeg/parse (fixture "rgb.jpg")))))
  (is (= :device-cmyk (:color-space (jpeg/parse (fixture "cmyk.jpg"))))))

(deftest adobe-marker-flags-cmyk-inversion
  (testing "The APP14 Adobe marker is detected on the CMYK file"
    (is (:adobe? (jpeg/parse (fixture "cmyk.jpg"))))
    (is (not (:adobe? (jpeg/parse (fixture "rgb.jpg")))))))

(deftest progressive-passes-through-flagged
  (is (:progressive (jpeg/parse (fixture "rgb_progressive.jpg"))))
  (is (not (:progressive (jpeg/parse (fixture "rgb.jpg"))))))

(deftest rejects-non-jpeg
  (is (thrown? clojure.lang.ExceptionInfo (jpeg/parse (fixture "rgb.png")))))
