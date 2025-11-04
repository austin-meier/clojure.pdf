# The layout engine

Back to the [docs index](../readme.md).

Manual positioning gets old fast, so there's a flexbox layout engine that takes
hiccup. I made the style names and semantics CSS verbatim to keep things as easy to use as possible,
it handles measuring, wrapping, pagination, and repeating headers/footers for you.

Runnable version: [`example/layout.clj`](example/layout.clj). It builds a two-page striped
report with the layout engine.

## The shape of it

```clojure
(pdf/with-layout (pdf/new-pdf)
  [:div {:style {:font ubuntu :font-size 10 :gap 4}}
   [:text {:style {:font-size 24 :color [24 23 66]}} "Layout demo"]
   [:div {:style {:flex-direction :row :gap 12 :height 40}}
    [:div {:style {:flex-grow 1 :background-color [12 74 110]}}]
    [:div {:style {:width 120 :background-color [253 186 116]}}]]
   [:image {:src logo :style {:width 120}}]]
  {:header [:text {:style {:font ubuntu :font-size 9}} "my report"]
   :footer [:text {:style {:font ubuntu :font-size 9}} "page footer"]})
```

Three element types: `:div` (a flex container), `:text`, and `:image` (with an image context
as `:src`). Text measurement uses the real font metrics, so wrapping is exact, not
approximated.

## Options

`with-layout` (and `pages`, if you want the render maps without a document) takes:

| Option | Default | What it does |
|---|---|---|
| `:page-size` | `[612 792]` (US Letter) | page width/height in points |
| `:margin` | `36` | points on every side |
| `:header` | none | hiccup block repeated at the top of every page |
| `:footer` | none | hiccup block repeated at the bottom |

Headers and footers reserve their measured height, and the body paginates into what's left.

## The style vocabulary

It's a CSS flexbox subset. Values are numbers (points), `"50%"` strings, `Dimension`s, or the
CSS keywords you'd expect. The full property table with defaults lives in
`pdf.layout.style/properties`; the working set:

| Group | Properties |
|---|---|
| Flex | `flex-direction` `justify-content` `align-items` `align-self` `flex-grow` `flex-shrink` `flex-basis` `flex-wrap` `align-content` `gap` `row-gap` `column-gap` |
| Box | `width` `height` `min-/max-width` `min-/max-height` `padding` `margin` `border-width` `border-color` |
| Position | `position` (`:relative`/`:absolute`) with `top` `right` `bottom` `left` |
| Text | `font` `font-size` `line-height` `color` (all inherited) |
| Paint | `background-color` (colors are `[r g b]` 0-255 or a string hex "#fefefe") |
| Pagination | `break-inside` (`:avoid` keeps a block on one page) |

`padding`, `margin`, and `border-width` take CSS shorthand: one value, `[vertical horizontal]`, or
`[top right bottom left]`.

## Pagination

You don't do anything. The tree is solved once at unbounded height, then sliced into pages of
the available content height. Text splits between lines, columns split between children, and
`:break-inside :avoid` pushes a whole block to the next page instead of slicing it:

```clojure
[:div {:style {:break-inside :avoid}}
 [:text {} "this row never gets cut in half"]]
```

## When to drop down

The layout engine is a lowering pass onto the same ops layer as everything else, so it
composes with manual work instead of fighting it. If you need one precisely-placed element on an
otherwise laid-out document, just use `:position :absolute` in the tree, or build that page with
[`with-text`/`with-stream`](documents-and-pages.md) directly.
