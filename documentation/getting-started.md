# Getting started

Back to the [docs index](../readme.md).

## Install

There's no Clojars release yet, so pull it as a git dep in your `deps.edn`:

```clojure
{:deps {io.github.austin-meier/clojure.pdf
        {:git/url "https://github.com/austin-meier/clojure.pdf"
         :git/sha "<latest sha>"}}}
```

No transitive dependencies come with it. That's the whole point.

## Hello, PDF

Everything you need day to day hangs off `pdf.api`. You build a document context, put things on it, and save it.

```clojure
(require '[pdf.api :as pdf]
         '[pdf.utils.dimension :refer [inches->dim]])

(def ubuntu (pdf/new-font "fonts/Ubuntu-Regular.ttf"))

(-> (pdf/new-pdf)
    (pdf/with-page
      (-> (pdf/new-page (inches->dim 8.5) (inches->dim 11))
          (pdf/with-text (inches->dim 1) (inches->dim 9)
            (pdf/new-text "Hello, PDF" ubuntu))))
    (pdf/save "hello.pdf"))
```

## Run the examples

Every doc in this folder points at a runnable file in [`example/`](example/). They run from
the repo root and write a PDF next to you so you can immediately open the result:

```bash
clojure -M:examples -m example.hello
```

| Example | Shows |
|---|---|
| [`example/hello.clj`](example/hello.clj) | The minimal document: one page, embedded text |
| [`example/layout.clj`](example/layout.clj) | Flexbox layout, wrapping text, pagination, headers/footers |
| [`example/images.clj`](example/images.clj) | JPEG/PNG placement, aspect sizing, alpha |
| [`example/vector.clj`](example/vector.clj) | SVG conversion and hand-written vector forms |
| [`example/just_data.clj`](example/just_data.clj) | The escape hatch: raw ops and bare map surgery |
| [`example/svg_to_ops.cljs`](example/svg_to_ops.cljs) | The portable core running as ClojureScript via nbb |

## Where to go next

- Building documents by hand and the data model: [documents and pages](documents-and-pages.md)
- Text: [text and fonts](text-and-fonts.md)
- Don't want to position things manually? [the layout engine](layout-engine.md)
- Pictures: [images](images.md), [vector graphics and SVG](vector-graphics.md)
- Running in the browser or node: [ClojureScript](clojurescript.md)
