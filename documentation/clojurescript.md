# ClojureScript and the JavaScript build

Back to the [docs index](../readme.md).

The library runs on both the JVM, ClojureScript, and can be compile to a node or browser ESM module with shadow-cljs for direct
usage from JavaScript/TypeScript.

## What's not portable

One file stays JVM-only on purpose, and it isn't on the serialize/parse path:
`pdf.context.svg.xml` (parsing an SVG *string* needs a real XML parser; the SVG core works on
hiccup, which is portable). Everything the ESM build exports avoids it.

## Two builds: Node and browser

Only two things need the platform: flate (zlib) and file I/O. Everything else is the same
portable core, so the two targets just differ in how they fill those two holes.

- **Node** (`dist/pdf.js`): flate and file I/O resolve to the built-in `node:zlib` and
  `node:fs`, left as native imports. This is the target that carries `parseFile` and `save`,
  the two entry points that actually touch disk.
- **Browser** (`dist/browser/pdf.js`): same core, bundled self-contained. There's no
  filesystem and no *synchronous* zlib in a browser (only the async `CompressionStream`, which
  doesn't fit the pipeline), so the build swaps both at resolve time:
  [pako](https://github.com/nodeca/pako) stands in for `node:zlib`, and `node:fs` becomes a
  stub that throws a pointed error if you hand it a path. The disk-bound entry points
  (`parseFile`, `save`) are left out, because there's nowhere to read or write. You pass bytes
  in and get bytes (or a `Blob`) out.

## Building the ESM module

The whole build pipeline lives in [`esm/`](../esm/): `shadow-cljs.edn`, the npm bits, the
browser shims, and the smoke tests. Run it from there:

```bash
cd esm
npm install
npm run build # dist/pdf.js (Node) + dist/browser/pdf.js (browser)
npm test
```

`shadow-cljs.edn` points the `:esm` target at Node and exports a handful of functions:

```clojure
{:target :esm
 :runtime :node
 :js-options {:js-provider :import}   ;; leave node:zlib / node:fs as native imports
 :modules {:pdf {:exports {parse      pdf.parse.core/parse
                           parseFile  pdf.parse.core/parse-file
                           serialize  pdf.context.pdf/serialize
                           save       pdf.context.pdf/save
                           newPdf     pdf.context.pdf/new-pdf
                           newPage    pdf.context.page/new-page
                           withPage   pdf.context.page/with-page
                           newFont    pdf.context.text.font/new-font
                           newImage   pdf.context.image/new-image
                           withText   pdf.api.js/with-text
                           withImage  pdf.api.js/with-image
                           withLayout pdf.api.js/with-layout
                           toBlob     pdf.api.js/to-blob}}}}
```

Both targets export the same JS authoring surface (the table below) through `pdf.api.js`. The
only difference is disk: the Node build adds `parseFile`/`save`, and the browser build drops
exactly those two, because there's no filesystem out there.

## Using it from Node

The Node build is the one with a filesystem, so it's the round-trip target: read a PDF off
disk, do whatever, write it back.

```javascript
import { parseFile, serialize, save } from "./dist/pdf.js";
const ctx = parseFile("in.pdf");
const bytes = serialize(ctx);
save(ctx, "out.pdf");
```

You get the JS authoring API. It converts plain JS
input at the boundary (`pdf.api.js`): objects with **camelCase** keys become the library's
keywordized style maps, and **arrays** become hiccup, so building a document reads the way
you'd write it in JS (see table below)

## Using it from the browser

The browser build can't touch disk, so everything comes in as `Uint8Array` bytes: fonts,
images, PDFs to parse. You get the same JS API that Node utilizes above.

```javascript
import {
  newPdf, newPage, withPage, newFont, newImage,
  withText, withImage, withLayout, serialize, toBlob, parse,
} from "./dist/browser/pdf.js";

/* Use a fetch().bytes() to get Uint8Array's of resources you wish to use */
const font  = newFont(fontBytes);
const image = newImage(pngBytes);

/* An example of absolute positioning */
const page = withText(newPage(612, 792), "Hello from the browser",
                      { x: 72, y: 720, font, size: 18 });

/* You can also use the CSS inspired layout engine*/
const layout =
  ["div", { style: { font, fontSize: 12, gap: 12, padding: 24 } },
    ["text", { style: { fontSize: 24 } }, "Layout from JS"],
    ["image", { src: image, style: { width: 96 } }],
    ["text", {}, "The quick brown fox jumps over the lazy dog."]];

const doc = withLayout(
  withPage(newPdf(), page),
  layout,
  { pageSize: [612, 792], margin: 48 }
);

/* Serialize the doc to bytes, and then create a downloadable url file from it */
const blob = toBlob(doc);
const url = URL.createObjectURL(blob);
```

The JS surface, all camelCase and JS-value-friendly:

| Function | What it does |
|---|---|
| `newPdf()` | A fresh empty document context |
| `newPage(width, height)` | A page context at that point size |
| `withPage(pdf, page)` | Add a page to the document |
| `newFont(bytes)` | Embed a TrueType font from `Uint8Array` bytes |
| `newImage(bytes)` | Load a PNG or JPEG from `Uint8Array` bytes |
| `withText(page, text, {x, y, font, size})` | Draw text at a point |
| `withImage(page, img, x, y, {width, height})` | Draw an image (one of width/height keeps aspect) |
| `withLayout(pdf, tree, {pageSize, margin, header, footer})` | Run the flex layout engine over array-hiccup |
| `serialize(ctx)` | The document as a `Uint8Array` |
| `toBlob(ctx)` | The document as an `application/pdf` `Blob` |
| `parse(bytes)` | Parse a PDF from `Uint8Array` bytes back into a context |

If you provide `newFont`/`newImage`/`parse` a *path* instead of bytes and the fs shim throws a pointed
error telling you to pass bytes: there's no filesystem out here.

## Experimentation without a build

You technically don't even need the build tool to try the portable core.
[nbb](https://github.com/babashka/nbb) can interpret it straight off the classpath:

```bash
npx nbb --classpath src documentation/example/svg_to_ops.cljs
```

## The rules that keep it portable

If you're contributing, this is the contract:

- New shared code is `.cljc` over `pdf.bytes`. No `java.*` in shared namespaces.
- Byte data is `byte[]`/`js/Uint8Array`, never boxed vectors, never `ByteBuffer`.
- Platform edges (flate, file I/O) live behind `#?(:clj ... :cljs ...)` in one place each
  (`pdf.utils.flate`, `pdf.utils.io`), and cljs uses `node:`-prefixed built-ins so shadow
  leaves them as native imports.
- Records extend `PdfSerializable` in their own namespace, so `pdf.serialize` stays a leaf and
  no reader conditional has to name a cross-namespace record type.
