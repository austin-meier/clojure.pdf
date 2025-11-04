# clojure-pdf

The ESM build of [clojure.pdf](https://github.com/austin-meier/clojure.pdf): create, lay out, and
parse PDFs from JavaScript. Works in Node and the browser.

```bash
npm install clojure-pdf
```

The package ships two builds and picks the right one for you through conditional exports:

- **Node** (`import "clojure-pdf"`): the full authoring API plus `parseFile`/`save`, since it has
  `node:zlib`/`node:fs`. Build documents from scratch, or read and write PDFs on disk.
- **Browser** (bundlers resolve the `browser` condition): the same authoring API, self-contained
  with no filesystem, so it's bytes in and bytes (or a `Blob`) out. `parseFile`/`save` are the
  only things dropped, since there's nowhere to read or write.

## Node

Node has the whole API: build documents from scratch with the authoring functions (identical to
the browser example below), and because it has a filesystem you also get `parseFile` and `save`.

```javascript
import { parseFile, serialize, save, newPdf } from "clojure-pdf";

const ctx = parseFile("in.pdf");   // -> an opaque context handle
save(ctx, "out.pdf");              // write it back via node:fs
const bytes = serialize(ctx);      // ...or get a Uint8Array
```

## Browser

Everything loads from `Uint8Array` bytes (fetch them, don't read a path), and you get the same
JS-native authoring API Node exposes: objects with camelCase keys become styles, arrays become
hiccup.

```javascript
import {
  newPdf, newPage, withPage, newFont,
  withText, withLayout, toBlob,
} from "clojure-pdf";

const font = newFont(fontBytes);   // Uint8Array of a .ttf
const page = withText(newPage(612, 792), "Hello from the browser",
                      { x: 72, y: 720, font, size: 18 });

const tree =
  ["div", { style: { font, fontSize: 12, gap: 12, padding: 24 } },
    ["text", { style: { fontSize: 24 } }, "Layout from JS"],
    ["text", {}, "The quick brown fox jumps over the lazy dog."]];

const doc = withLayout(withPage(newPdf(), page), tree, { pageSize: [612, 792], margin: 48 });
const url = URL.createObjectURL(toBlob(doc));   // hand it to a download link
```

## Documentation

The full docs live in the repo, one focused page per subsystem. The
[ClojureScript and ESM](https://github.com/austin-meier/clojure.pdf/blob/main/documentation/clojurescript.md)
page is the one aimed at this package (the whole JS API, both builds, the design), but the rest
apply just as well since the pipeline is the same underneath.

| Doc | What's in it |
|---|---|
| [Getting started](https://github.com/austin-meier/clojure.pdf/blob/main/documentation/getting-started.md) | Install, hello world, running the examples |
| [Documents and pages](https://github.com/austin-meier/clojure.pdf/blob/main/documentation/documents-and-pages.md) | The data model, content ops, the escape hatch |
| [Text and fonts](https://github.com/austin-meier/clojure.pdf/blob/main/documentation/text-and-fonts.md) | TrueType embedding, subsetting, what save time does |
| [The layout engine](https://github.com/austin-meier/clojure.pdf/blob/main/documentation/layout-engine.md) | Flexbox over hiccup: styles, wrapping, pagination, headers/footers |
| [Images](https://github.com/austin-meier/clojure.pdf/blob/main/documentation/images.md) | JPEG/PNG pass-through, alpha, sizing, dedupe |
| [Vector graphics and SVG](https://github.com/austin-meier/clojure.pdf/blob/main/documentation/vector-graphics.md) | SVG conversion, hand-written forms, what's supported |
| [Parsing PDFs](https://github.com/austin-meier/clojure.pdf/blob/main/documentation/parsing.md) | Reading files back into editable contexts, what's handled and what's refused |
| [ClojureScript and ESM](https://github.com/austin-meier/clojure.pdf/blob/main/documentation/clojurescript.md) | The portable core, the Node and browser ESM builds, using it from JavaScript |

## License

MIT. See [the repo](https://github.com/austin-meier/clojure.pdf) for the full source and design notes.
