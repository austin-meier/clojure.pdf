(ns pdf.api
  "Public entry points, re-exported from their home namespaces so consumers can
   require one namespace for the whole authoring surface. Portable: the facade
   is a thin binding layer that works on both Clojure and ClojureScript."
  (:require
   [pdf.context.form :as form]
   [pdf.context.image :as image]
   [pdf.context.page :as page]
   [pdf.context.pdf :as pdf]
   [pdf.context.stream :as stream]
   [pdf.context.svg :as svg]
   [pdf.context.text.core :as text]
   [pdf.context.text.font :as font]
   [pdf.layout.core :as layout]
   [pdf.parse.core :as parse])
  ;; cljs macros aren't visible to their own .cljc file without a self-require
  #?(:cljs (:require-macros [pdf.api :refer [defalias]])))

(defmacro ^:private defalias
  "Defs `alias` to the value of `target`, copying :doc/:arglists so the
   re-exported var documents itself like the original.

   A cljs macro expands on the JVM at compile time, so `&env` — not a reader
   conditional — tells us the target platform. Compiling Clojure, we read the
   target var's metadata directly; compiling ClojureScript we emit a plain
   value alias (pdf.api isn't the cljs authoring surface — pdf.api.js is — so
   copying facade metadata into the analyzer there isn't worth the reach-in)."
  {:clj-kondo/lint-as 'clojure.core/def}
  [alias target]
  (let [m (when-not (:ns &env)
            (let [tm (meta (resolve target))]
              ;; :arglists must be quoted — def evaluates symbol metadata, and
              ;; a bare ([a b]) would try to invoke the vector (like defn does).
              (cond-> {}
                (:doc tm)      (assoc :doc (:doc tm))
                (:arglists tm) (assoc :arglists (list 'quote (:arglists tm))))))]
    `(def ~(vary-meta alias merge m) ~target)))

(defalias new-pdf pdf/new-pdf)
(defalias serialize pdf/serialize)
(defalias save pdf/save)

(defalias parse parse/parse)
(defalias parse-file parse/parse-file)

(defalias new-page page/new-page)
(defalias with-page page/with-page)
(defalias with-stream page/with-stream)
(defalias string->stream stream/string->stream)

(defalias new-font font/new-font)
(defalias new-text text/new-text)
(defalias with-text text/with-text)

(defalias new-image image/new-image)
(defalias with-image image/with-image)

(defalias new-form form/new-form)
(defalias with-form form/with-form)
(defalias svg->form svg/svg->form)

(defalias with-layout layout/with-layout)
