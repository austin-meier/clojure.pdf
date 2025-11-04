# Parsing PDFs

Back to the [docs index](../readme.md).

The parser reads the bytes of a clean, unencrypted PDF and hands you back
a normal document context, the same
plain map `new-pdf` gives you. So there's no separate read-only model to learn. What you parse,
you can edit and save straight back out.

See the example at: [`example/parse.clj`](example/parse.clj).

## The basics

Two entry points, both on `pdf.api`:

```clojure
(require '[pdf.api :as pdf])

(pdf/parse-file "my.pdf") ;; from a path (not supported on browser builds)
(pdf/parse some-byte-array) ;; from bytes you already have
```

You get back a context map:

```clojure
{:version 1.7
 :objects [{:id parsed-1-1234 :obj {:type :catalog :pages #Ref{...}}}
           {:id parsed-2-1235 :obj {:type :pages :kids [...] :count 1}}
           ...]
 :trailer {:info #Ref{...} :id [...]}   ;; /Info, /ID, and other trailer extras
 :warnings []}
```

Every `n g R` in the file becomes a symbolic `Ref` to a stable id, dictionaries become maps
(names run through the same keyword mapping as the writer, so `/MediaBox` is `:media-box` and
an unregistered name like `/Rotate` comes back as the symbol `'Rotate`), and streams become
`PdfStream` records with their payload bytes exactly as they were stored. Hand the whole thing
to `save` and you get a working PDF back.

## Edit and save

Because it's the same context the builders produce, editing a parsed document is just editing
a map. Here's the gist of the [example](example/parse.clj), rotating every page a quarter turn:

```clojure
(let [ctx (pdf/parse (pdf/serialize example-pdf))]
    ;; it's just a map, rotate every page a quarter turn
    (let [rotated (update ctx :objects
                          (fn [objs]
                            (mapv (fn [{:keys [obj] :as entry}]
                                    (if (= :page (:type obj))
                                      (assoc-in entry [:obj :rotate] 90)
                                      entry))
                                  objs)))]
      (pdf/save rotated "rotated-output.pdf")))
```

Object numbering, the xref table, the trailer: all of that gets rebuilt at save time, same as
always. You should not touch those. But heads up if you do, they get ignored

## What it handles

The parser reads clean, modern files, whatever cross-reference style they use:

- **Classic xref tables** (the pre-1.5 shape) with `/Prev` chains for incremental updates.
- **Xref streams** (1.5+), including PNG-predictor encoding, which is what most tools emit now.
- **Object streams**, where several objects are compressed together into one stream.
- **Hybrid-reference files**, the backwards-compatible files that carry both a classic table and
  an xref stream.

Stream payloads come through raw, exactly as stored, with their `/Filter` left in the dict. The
parser only decodes the streams it has to read itself (the xref and object streams), so it never
needs a pile of image or compression codecs just to round-trip a file. Your DCTDecode JPEG comes
back byte-for-byte identical to what went in.

## What it refuses

The bar for this whole library is to refuse loudly rather than quietly get it wrong, and the
parser holds that line. These throw a clear error instead of guessing:

- **Encrypted files** (`/Encrypt` in the trailer). No decryption support, and you'll get told
  that plainly instead of something cryptic downstream.
- **Damaged files**: a broken or missing xref, an object header that doesn't match the xref, a
  cyclic `/Prev` chain. Rebuilding a mangled file by scanning it is a real project for another
  day.
- **Non-Flate compression on the xref/object streams it has to decode** (LZW + friends).
  Passing an oddly-filtered *content* stream straight through is fine and automatic; the parser
  only trips when it needs to read the bytes itself. I might eventually add more default support

The one bit of recovery it does allow is a stream whose `/Length` is wrong: it just falls back to
scanning for `endstream`, and records a warning so you know it happened. Everything else in
`:warnings` is same idea, notes about what the parser had to paper over (like a reference to a
deleted object), not blocking errors.
