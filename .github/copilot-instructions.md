## Purpose
This repo implements a small Clojure-based PDF construction/serialization library. These instructions give focused context for an AI coding agent to be immediately productive working here.

## High-level architecture
- Data model: the runtime PDF context is a map (see `src/pdf.clj`) with keys like `:version` and `:objects` (a vector of object maps). Objects are plain maps with a `:type` keyword (`:catalog`, `:pages`, `:page`, ...).
- Serialization pipeline: `src/file/pdf.clj` drives a fixed pipeline of steps: header -> objects -> xref -> trailer. See `serialize` which calls `[serialize-header serialize-objects serialize-xref serialize-trailer]` in that order.
- Serializers: `src/objects/pdf_serializable_protocol.clj` defines the `PdfSerializable` protocol and `to-pdf` implementations for core types (nil, Boolean, Number, String, Keyword, Vector, Map). Keywords are converted to PDF names via `(str "/" (str/capitalize (name k)))` (e.g. `:catalog` -> `/Catalog`).

## Important files and where to look first
- `deps.edn` — lightweight deps; `:test` alias is used for running tests.
- `readme.md` — project conventions: keywords => PDF names, vectors => arrays.
- `src/pdf.clj` — top-level PDF context and small example (`test-pdf-ctx`).
- `src/context/*` — helpers for building logical PDF objects (e.g. `src/context/page.clj`).
- `src/file/*` — the serialization steps (header, body, xref, trailer, helpers). Modify these when changing byte layout or ordering.
- `src/objects/pdf_serializable_protocol.clj` — canonical place to change text serialization rules for types.
- `test/` — unit tests; examples of expected output/behaviour (use to drive changes and expectations).

## Conventions & patterns (concrete examples)
- PDF names are Clojure keywords. Example: `{:type :catalog}` becomes `/Catalog` in `to-pdf` (see `src/objects/pdf_serializable_protocol.clj`).
- PDF arrays are Clojure vectors; maps become PDF dictionaries via `to-pdf` map implementation.
- Indirect references: constructed via `file.xref/->ref` and the object ordering/offsets are managed in `src/file/*` during serialization; changing object order requires careful updates to `:object-offsets` logic.
 - Indirect references: constructed via `file.xref/->ref`. `->ref` returns an `IndirectRef` record with fields `:obj-num` and `:gen` where `:obj-num` is `(inc i)` (PDF object numbers are 1-based) and `:gen` defaults to `0`. When validating or indexing into the `:objects` vector, convert back to a zero-based index with `(dec obj-num)`.
- Serialization state: `new-serialization-context` (in `src/file/pdf.clj`) keeps `:serialized-bytes`, `:object-offsets`, and `:xref-offset`. Follow this structure when adding new serialization steps.

## How to run and test (concrete commands)
- Run unit tests (uses the `:test` alias in `deps.edn`):

```powershell
clojure -M:test
```

- Quick REPL check to exercise the small example from `src/pdf.clj`:

```powershell
clojure
;; then in the REPL
(require 'pdf)
(pdf/serialize pdf/test-pdf-ctx)
```

## Integration points & change impact
- Changes to `to-pdf` (in `src/objects/pdf_serializable_protocol.clj`) affect every serialized object — update tests accordingly.
- Changes to `serialize-objects` or object indexing affect xref offsets and trailer generation (`src/file/xref.clj`, `src/file/trailer.clj`). Adjust tests that assert byte offsets.

## Tests & expectations
- Tests use Cognitect's test-runner via the `:test` alias. Test files live under `test/` and mirror important behaviours (e.g., `test/file/trailer_test.clj`, `test/objects/pdf_serializable_protocol_test.clj`).

## Quick tips for edits
- For formatting/name changes, update `to-pdf` implementations and add/adjust tests under `test/objects`.
- For byte-level or ordering changes, update `src/file/*` pipeline and the test fixtures that assert offsets.
- When in doubt, run `clojure -M:test` and use the REPL `(pdf/serialize ...)` to inspect output.

If any of the above is unclear or you'd like more detail (examples of object shapes, common failing tests, or a sample serialized output to match), tell me which area to expand and I will update this file.
