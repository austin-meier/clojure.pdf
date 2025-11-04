# clojure.pdf

A pure Clojure PDF library with the goal of being elegant. No wrapped libraries, no runtime dependencies, just data.

Based on the [PDF 2.0 specification](https://www.pdfa-inc.org/product/iso-32000-2-pdf-2-0-bundle-sponsored-access/).

## Hello, PDF

Everything hangs off `pdf.api`. You build a document context (a plain map), put things on it,
and save it.

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

## Documentation

The docs live in [`documentation/`](documentation/), Most are accompanied with examples in [`documentation/example/`](documentation/example/).

You can run an individual example by namespace to see what it produces with
```bash
clojure -M:examples -m example.hello
```

| Doc | What's in it | Example |
|---|---|---|
| [Getting started](documentation/getting-started.md) | Installation, hello world, running the examples | [`hello.clj`](documentation/example/hello.clj) |
| [Documents and pages](documentation/documents-and-pages.md) | The data model, content ops, the escape hatch | [`just_data.clj`](documentation/example/just_data.clj) |
| [Text and fonts](documentation/text-and-fonts.md) | TrueType embedding, font subsetting | [`hello.clj`](documentation/example/hello.clj) |
| [The layout engine](documentation/layout-engine.md) | Flexbox via hiccup: styles, wrapping, pagination, headers/footers | [`layout.clj`](documentation/example/layout.clj) |
| [Images](documentation/images.md) | JPEG/PNG pass-through, alpha, sizing, dedupe | [`images.clj`](documentation/example/images.clj) |
| [Vector graphics and SVG](documentation/vector-graphics.md) | SVG conversion, hand-written forms, what's supported | [`vector.clj`](documentation/example/vector.clj) |
| [Parsing PDFs](documentation/parsing.md) | Reading files back into editable contexts, what's handled and what's refused | [`parse.clj`](documentation/example/parse.clj) |
| [ClojureScript and ESM build](documentation/clojurescript.md) | The portable core, the Node and browser ESM builds + using it from JavaScript

## It's just data

The design goal is that you never hit a wall. The builders cover the common paths, but every
context they produce is a plain map you can `assoc` your way through when the high-level API
doesn't have what you need. Keywords become PDF names, maps become PDF dictionaries, vectors/collections
become PDF arrays, and object references stay symbolic until serialization time so the numbering
never becomes your problem. For more information see [documents and pages](documentation/documents-and-pages.md).

## Where things live

| Namespace | What it is |
|---|---|
| `pdf.api` | The public entry points, re-exported in one place |
| `pdf.context.*` | Authoring contexts: document, page, content ops, text/fonts (`text.*`), images (`image.*`), vector forms and SVG (`form`, `svg.*`) |
| `pdf.layout.*` | The layout engine: hiccup tree -> styles -> flex solver -> pagination -> draw ops |
| `pdf.serialize` | The `to-pdf` protocol, number formatter, string escaping |
| `pdf.file.*` | The file skeleton: header, body, xref table, trailer |
| `pdf.utils.*` | string helpers, io helpers, dimension helpers, etc |
| `pdf.bytes.*` | A portable declative binary codec
| `pdf.validation.context` | Basic sanity checks on a document context |
| `documentation/example/` | Runnable examples |

## Development

The JVM side runs through `deps.edn`; the ESM side runs through npm in [`esm/`](esm/).

```bash
# JVM
clojure -X:test                        # run the tests
clojure -M:examples -m example.hello   # build a documentation example PDF
clojure -T:build jar                   # build a jar

# ESM (Node + browser builds), from esm/
cd esm
npm install                            # first time only
npm run build                          # -> dist/pdf.js + dist/browser/pdf.js
npm test                               # round-trips a PDF through both builds
```

## Roadmap

The PDF format is both incredibly impressive and incredibly wide. I work with PDFs professionally,
so I do intend to add some of the more intricate parts of the format. The focus started with
being elegant at *producing* PDFs, and now the parser reads them back into the same contexts,
so the round trip is closed.

Done so far:

- Pages, text, and TrueType font embedding with subsetting (Type0/CID, Identity-H)
- JPEG and PNG images, including alpha
- A flexbox layout engine with pagination, headers, and footers
- Vector graphics: form XObjects and an SVG-to-PDF converter
- The full serializer (symbolic refs, byte-accurate xref)
- parser: classic xref, xref streams, object streams, and hybrid files back into editable
  contexts (see [parsing](documentation/parsing.md))
- ClojureScript builds: the whole parse/serialize/layout pipeline compiles to ES modules for
  both Node and the browser (bundled flate, a JS-friendly authoring API), see
  [ClojureScript and ESM](documentation/clojurescript.md)
- Basic context validation

Still to come:

- CFF + extend OTF font support
- Encryption + Decryption + more compression algo support
- Embedded PDFs, (finalizing) document merging, incremental update writing
- Gradients and opacity for the SVG converter, vector forms as a layout element
- Annotations and links
- Context verification system with warnings and errors
