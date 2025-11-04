# Text and fonts

Back to the [docs index](../readme.md).

Text here means real embedded fonts, not just the 14 built-in PDF fonts from 1993. You load a
TrueType file, draw with it, and at save time the library embeds a subset containing only the
glyphs you actually used.

See the Example: [`example/hello.clj`](example/hello.clj).

## Loading a font

```clojure
(def ubuntu (pdf/new-font "fonts/Ubuntu-Regular.ttf"))
```

`new-font` parses the font into a font *context*: the raw bytes plus the parsed tables. No
PDF objects exist yet, because what gets embedded depends on what text your document ends up
showing.

TrueType (`.ttf`) and TypeType-style OTF (`.otf`) work for now. CFF + CFF-style OTF are not supported current but do throw a clear error message. They are uncommon in the modern era but I guess if you really need them open an issue.

## Drawing text

```clojure
(-> (pdf/new-page (inches->dim 8.5) (inches->dim 11))
    (pdf/with-text (inches->dim 1) (inches->dim 9.5)
      (assoc (pdf/new-text "Hello, PDF" ubuntu) :font-size 28))
    (pdf/with-text (inches->dim 1) (inches->dim 9)
      (pdf/new-text "Body text at the default 12pt." ubuntu)))
```

- `with-text` takes the position as x/y `Dimension`s from the page's bottom-left (PDF
  coordinates go up, not down).
- `new-text` returns a map. Font size is just a key on it, so `assoc` it (default is 12).

If you're setting more than a couple of lines by hand, you probably want the
[layout engine](layout-engine.md) instead. It measures, wraps, and paginates for you using
the same font machinery.

## What happens at save time

This is the part you get for free, but it's worth knowing what you're getting:

- The font embeds as a proper Type0/CIDFontType2 with the subset font program, so any Unicode
  your font covers just works.
- Font Subsetting (if the font allows it): can drop a 300KB font to a few KB for a short string.
- A `/ToUnicode` map is included, so copy/paste and text extraction give back the exact source
  text. So you don't have copy and paste hell from PDF text.
- Font + glyph usage de-dupes by content hash, so loading the same file into two separate `new-font` calls still embeds one shared, merged copy.
