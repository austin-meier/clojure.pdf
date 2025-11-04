# Vector graphics and SVG

Back to the [docs index](../readme.md).

There are currently two ways to get vector art onto a page: convert an SVG, or write the drawing ops yourself.
Both produce a *form* (a reusable vector graphic with its _own_ coordinate space because PDF is cool like that) that you
place like an image.

See example: [`example/vector.clj`](example/vector.clj).

## SVG in, PDF out

PDF's drawing operators are essentially a superset of SVG's, so this is just transcription, not a
rasterization.

```clojure
(require '[pdf.context.svg.xml :as svg-xml])

(def icon (pdf/svg->form (svg-xml/file->tree "icon.svg")))

(-> page
    (pdf/with-form icon 72 500 {:width 200})
    (pdf/with-form icon 300 500 {:width 40}))
```

Sizing follows the same rules as images (one dimension given, the other keeps the aspect
ratio). One converted SVG placed at ten sizes only embeds a single object.

`svg->form` takes a data tree, so you can also skip XML entirely and write SVG as hiccup.

```clojure
(pdf/svg->form
 [:svg {:viewBox "0 0 24 24"}
  [:circle {:cx 12 :cy 12 :r 10 :fill "#5881d8"}]
  [:path {:d "M7.5 12l3 3 5.5-6" :stroke "white" :stroke-width 2.5
          :fill "none" :stroke-linecap "round"}]])
```

## What the converter currently supports

| Works | Notes |
|---|---|
| `path` | the full `d` grammar: relative commands, shorthands, arcs, minified data |
| `rect` `circle` `ellipse` `line` `polyline` `polygon` | including rounded rects |
| solid fills and strokes | hex, `rgb()`, common named colors, `currentColor`, `fill-rule` |
| stroke styling | width, caps, joins, miter limit, dash arrays |
| `transform` | `translate` `scale` `rotate` `skewX` `skewY` `matrix`, full lists |
| `g` groups | with style inheritance, plus the `style` attribute |
| `viewBox` | the y-flip between SVG and PDF space is handled for you |

Any unsupported ops that would *misrender* throw instead.
Non-rendering elements (`defs`, `title`, `metadata`) are skipped quietly.
SVG user units are read as points 1:1; percentage and em units throw.

## Hand-written forms

A form is a bounding box plus a vector of content ops, the same op vocabulary everything else
compiles to (see [documents and pages](documents-and-pages.md) for the ops story):

```clojure
(def wave
  (pdf/new-form [0 0 100 60]
                [[:set-rgb-stroke 0.49 0.18 0.07]
                 [:set-line-width 2]
                 [:set-dash [6 3] 0]
                 [:move-to 10 30]
                 [:curve-to 35 55 65 5 90 30]
                 [:stroke]]))

(pdf/with-form page wave 72 380)
```

This is also exactly what `svg->form` returns, so a converted SVG is just
data you can post-process before placing if you desire.

## Not there yet
Vector forms aren't a layout-engine element yet (no `[:form ...]` in the hiccup tree), so
placement is manual for now.
