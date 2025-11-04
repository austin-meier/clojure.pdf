(ns site.eval
   "The library's public surface is registered into an SCI context under the same
   `pdf.api`. A preloaded font is bound as `font` so snippets
   can draw text without a full font loading pipeline"
  (:require
   [sci.core :as sci]
   [pdf.context.pdf :as pdf]
   [pdf.context.page :as page]
   [pdf.context.content :as content]
   [pdf.context.text.core :as text]
   [pdf.context.text.font :as font]
   [pdf.context.image :as image]
   [pdf.context.form :as form]
   [pdf.context.svg :as svg]
   [pdf.layout.core :as layout]
   [pdf.parse.core :as parse]))

(def ^:private api-ns
  {'new-pdf pdf/new-pdf
   'serialize pdf/serialize
   'parse parse/parse
   'new-page page/new-page
   'with-page page/with-page
   'with-stream page/with-stream
   'ops->stream content/ops->stream
   'new-font font/new-font
   'new-text text/new-text
   'with-text text/with-text
   'new-image image/new-image
   'with-image image/with-image
   'new-form form/new-form
   'with-form form/with-form
   'svg->form svg/svg->form
   'with-layout layout/with-layout})

(defn make-ctx
  "An SCI context exposing `pdf.api` and a `font` binding (the preloaded font context)
   for snippets to draw with."
  [preloaded-font]
  (sci/init {:namespaces {'pdf.api api-ns}
             :bindings   {'font preloaded-font}}))

(defn eval->bytes
  "Evaluate `code` in `ctx`. Serializes to a Uint8Array of PDF bytes."
  [ctx code]
  (pdf/serialize (sci/eval-string* ctx code)))
