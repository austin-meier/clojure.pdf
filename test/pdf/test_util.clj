(ns pdf.test-util
  "Shared test fixtures: the Ubuntu guinea-pig font, parsed once and lazily,
   plus the solve helpers the layout tests repeat. Not a test namespace itself."
  (:require
   [pdf.context.text.font :as font]
   [pdf.layout.flex :as flex]
   [pdf.layout.text :as text]
   [pdf.layout.tree :as tree]
   [pdf.utils.io :refer [file->bytes]]))

(def font-path "test/resources/fonts/Ubuntu-Regular.ttf")

(def ^:private ubuntu-ctx (delay (font/new-font font-path)))
(def ^:private ubuntu-bytes (delay (file->bytes font-path)))

(defn ubuntu
  "The shared Ubuntu font context, parsed on first use."
  []
  @ubuntu-ctx)

(defn ubuntu-src
  "The raw bytes of the Ubuntu font program, read on first use."
  ^bytes []
  @ubuntu-bytes)

(defn solve
  "Solve `hiccup` against a w x h containing block with text measurement."
  [hiccup w h]
  (flex/solve (tree/normalize hiccup) {:width w :height h} {:measure text/measure}))

(defn solve-page
  "Mirror pdf.layout.core/pages: the document root fills the page content
   width and solves against an unbounded height."
  [hiccup w]
  (flex/solve (assoc-in (tree/normalize hiccup) [:style :width] w)
              {:width w :height nil} {:measure text/measure}))
