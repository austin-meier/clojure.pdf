# Documents and pages

Back to the [docs index](../readme.md).

This is the data model everything else sits on. If you only remember one thing: a document is
a plain map, and the builders are conveniences over data you could write yourself. You never
hit a wall where the API runs out and you're stuck.

Runnable version: [`example/just_data.clj`](example/just_data.clj).

## The document context

`new-pdf` gives you a map with a `:version` and a vector of `:objects` (a catalog and a page
tree to start). `with-page` adds a page. `save` serializes. That's the whole lifecycle:

```clojure
(require '[pdf.api :as pdf]
         '[pdf.utils.dimension :refer [inches->dim]])

(-> (pdf/new-pdf)
    (pdf/with-page (pdf/new-page (inches->dim 8.5) (inches->dim 11)))
    (pdf/save "blank.pdf"))
```

Page sizes take `Dimension` values from `pdf.utils.dimension` (`inches->dim`, `cm->dim`,
`mm->dim`, `points->dim`), so you don't have to remember that US Letter is 612x792 points.

## How Clojure data becomes PDF syntax

The mapping is mechanical, and it's the same everywhere:

| Clojure | PDF |
|---|---|
| `:font-name` keyword | `/FontName` name (kebab-case to PascalCase, irregular names like `/CIDToGIDMap` are aliased) |
| `'SubType` symbol | `/SubType` name, exactly as written (the escape hatch) |
| vector | array |
| map | dictionary |
| string | `(literal string)` |
| byte array | stream payload |

Object references are symbolic until serialize time, so you can add, dedupe, and reorder
objects freely and the numbering sorts itself out at the end. You'll never hand-manage an
object number.

## Content streams are data too

What actually draws on a page is a content stream, and here that's a vector of ops, each
`[op & operands]`. The op names live in `pdf.context.operators`:

```clojure
(require '[pdf.context.content :refer [ops->stream]])

(def box
  (ops->stream
   [[:set-rgb-fill 0.35 0.51 0.85]
    [:rectangle 100 500 200 100]
    [:fill]]))

(pdf/with-stream page box)
```

Every high-level feature (text, images, the layout engine, the SVG converter) compiles down
to these same ops, so anything they can draw, you can draw by hand.

## The escape hatch

The builders don't cover every key in the PDF spec, and they don't need to. A page is a map.
If you want `/Rotate` and there's no builder for it, put it there:

```clojure
(assoc page :rotate 90)
```

The keyword-to-name mapping handles the rest. If you need a name the kebab->Pascal rule can't
produce, use a symbol (`'ca` emits `/ca` exactly as written).

## Validating

Because a document is a map, you can sanity-check it before it's ever a file:

```clojure
(require '[pdf.validation.context :refer [validate-context]])

(validate-context ctx)
;; => {:valid true, :errors []}
```

The checks are structural and basic today; richer warnings are on the roadmap.

## Deeper reading

The serialization pipeline (how maps become byte-accurate PDF files, symbolic ref numbering,
the chunk layer) is documented in [context.md](../context.md). You don't need it to use the
library, but it's there when you're curious.
