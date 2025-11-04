# Images

Back to the [docs index](../readme.md).

JPEG and PNG, embedded pass-through: the compressed bytes go into the PDF verbatim, nothing
gets decoded and re-encoded, so there's no quality loss and no surprise bloat.

See Example: [`example/images.clj`](example/images.clj).

## Loading and placing

```clojure
(def photo (pdf/new-image "photo.jpg"))

(-> (pdf/new-page (inches->dim 8.5) (inches->dim 11))
    (pdf/with-image photo 72 560 {:width 220}))
```

`new-image` sniffs the format from the magic bytes (I have been bit too many times from file extensions), parses
the header for dimensions and color info, and hands you back a map. `with-image` places it
with its bottom-left corner at x/y, in points or `Dimension`s.

## Sizing rules

| You give | You get |
|---|---|
| `:width` and `:height` | exactly that, aspect ratio be damned |
| just one of them | the other follows the image's aspect ratio |
| neither | the intrinsic pixel size as points |

```clojure
(pdf/with-image page photo 72 560 {:width 220})    ; height scales
(pdf/with-image page photo 330 560 {:height 80})   ; width scales
```

## Alpha PNGs

RGBA and gray+alpha PNGs just work: the alpha channel is split out into a proper `/SMask`, so
transparency renders correctly in every viewer. Palette PNGs work too (`/Indexed` color
space, the palette gets carried along).

## Dedupe

Placing the same image five times embeds it once. Dedupe is by content hash, so even loading
the same file into two separate `new-image` calls still only embeds a single copy in the resulting pdf.

## In the layout engine

Inside a [layout](layout-engine.md) tree, images are an `:image` element with the image
context as `:src`:

```clojure
[:image {:src photo :style {:width 120}}]
```