# Handoff: geography on the heat field, and topic glyphs on Coming up

## Overview

Two changes, both about making an existing surface legible rather than adding a feature.

**1. Geography on the plan heat field.** The Plan tab's six window thumbnails paint a heat field over a real UK coastline, but nothing on them named a place. Every other reading on a card — drive time, `leave by`, the reach filter — is measured from one origin (`DH3 4NG`) that the picture never showed, so warmth 200 px north read the same as warmth twenty minutes up the A1. Thumbnails now carry a **home marker** and **area names at region centroids**; the drill-down popup field additionally carries **dashed reach rings at 45 min and 1h 30min**.

**2. Topic glyphs on Coming up.** The plan cards' topic chips already carry an emoji glyph per topic family. Coming up named its topics with no glyph. It now carries the same set, extended with two almanac families Plan does not have (sun, moon), on timeline rows, coincidence sub-lines, standing-condition rows and filter chips. Colour swatches were kept — the glyph sits beside the swatch, never in place of it.

## About the design files

The files in this bundle are **design references created in HTML** — prototypes that show the intended look and behaviour, not production code to copy. The task is to **recreate them in the PhotoCast frontend's existing environment** (React, `components/` + `frontend/src/components/`, e.g. `HeatmapGrid.jsx`) using its established patterns.

Two exceptions worth reading rather than reimplementing from scratch:

- `heat-field.js` is the existing shared heat kernel, unchanged in this handoff. It is included so the label geometry below can be understood in context. Do not fork it.
- `plan-tab-v4.js` contains the actual label placement algorithm (`placeLabels`, `labelThumb`, `kmPx`). The algorithm is the deliverable; the DOM plumbing around it is not. Port the algorithm faithfully — it is what stops labels from stacking.

## Fidelity

**High-fidelity.** Colours, type sizes, letter-spacing, marker dimensions and placement rules below are final and should be matched. The label placement is geometric, so it must be reimplemented as logic, not eyeballed.

---

## Screen 1 — Plan tab, window thumbnails

**File:** `Plan Tab with Heat v4.html` + `plan-tab-v4.js` (`labelThumb`, `drawThumbs`, `card`)
**Purpose:** six windows over four days; each thumbnail is the forecast field for that window.

### Markup change

The canvas gained a positioned wrapper and an overlay layer. Labels are HTML in an absolutely-positioned overlay, **not** canvas text — they must stay crisp at any DPR and (in the popup) clickable.

```html
<!-- was: <canvas></canvas> -->
<div class="tmap"><canvas></canvas><div class="tlab"></div></div>
```

`.tmap{position:relative;line-height:0}` · `.tlab{position:absolute;inset:0;pointer-events:none}`
The card's grid rule moved with it: `.wrap.sideways .hc>canvas{grid-area:cv}` → `.hc>.tmap{grid-area:cv}`.
Thumbnail width is now measured off the card, not the canvas's parent: `cv.closest('.hc').clientWidth - 12`.

### Home marker

```
.hm      position:absolute; display:flex; flex-direction:column; align-items:center; gap:2px; line-height:1
.hm .mk  9×9 px; border-radius:50%; border:1.6px solid #C9A24B (--home);
         background:rgba(20,15,11,.6);
         box-shadow:0 0 0 2.5px rgba(20,15,11,.5), 0 0 9px rgba(201,162,75,.5)
.hm .lb  IBM Plex Mono 7.5px / 600 / letter-spacing .14em; colour #EBD9A8;
         text-shadow:0 1px 3px rgba(0,0,0,.95); text "HOME"
```

Popup override: `.mlab>.hm .mk{width:12px;height:12px;border-width:2px}` · `.mlab>.hm .lb{font-size:9px}`.

Coordinates: `HOMEPT = [-1.573, 54.855]` (lng, lat — DH3 4NG, Chester-le-Street). In production this is the user's saved origin geocode, not a constant.

**Drawn only when planning from home** (`isHome(S.origin)`). An away origin frames a single region and home sits off-picture; no marker, no rings.

### Area names

```
.tlab>.rg2      position:absolute; IBM Plex Mono 8px / 600 / letter-spacing .09em;
                colour rgba(242,231,211,.62); line-height 1.2; white-space:nowrap;
                text-shadow:0 1px 3px rgba(0,0,0,.95), 0 0 8px rgba(0,0,0,.8)
.tlab>.rg2.hot  colour #F9F1E2   /* the region with the highest mean rating for this window */
```

Uppercase, spaces rendered as `&nbsp;` so a name never wraps. Positioned at the region's **centroid of its rated locations** in screen space (`HeatField.centroid(spots, rid, s => proj([s.lng, s.lat]))`) — not at a polygon centre; PhotoCast draws no region boundaries and must not start.

Two name sets, chosen by thumbnail width (`w < 215 px` → tiny):

| region id | full | tiny |
|---|---|---|
| `ntw` | NORTHUMBERLAND | NORTHUMB. |
| `penn` | NORTH PENNINES | PENNINES |
| `nymc` | NORTH YORK MOORS | N Y MOORS |
| `lakes` | LAKE DISTRICT | LAKES |
| `dales` | YORKSHIRE DALES | DALES |
| `borders` | BORDERS | BORDERS |
| `peak` | PEAK DISTRICT | PEAK |

Area names carry **no rating**. The card's spread histogram and the popup's region rail already state it.

### Label placement (`placeLabels`)

One greedy pass in priority order. Home is first, so it always wins its space.

1. For each item, measure the element by appending it at `left:-9999px`.
2. Try vertical nudges in this order: `0, -13, 13, -24, 24, -36, 36` px.
3. A candidate box is `{x: cx - w/2, y: cy - h/2 + dy, w, h}`. Reject if it leaves the frame (`x<1 || y<1 || x+w>W-1 || y+h>H-1`).
4. Reject if it overlaps any already-placed box, inflated by 3 px horizontally and 2 px vertically.
5. First accepting candidate wins; the box joins the placed set.
6. **If no candidate fits, remove the element.** A dropped name is better than an unreadable one. Never stack, never shrink to fit.

Placement runs on every redraw (window resize is debounced 140 ms, and viewport changes recompute) because the projection changes with the frame.

## Screen 2 — Plan tab, drill-down popup field

**File:** `plan-tab-v4.js`, `drawBig()`
**Purpose:** the same field at ~2× size, where the decision to drive is actually made.

It already carried region names (`.rg`) and clickable location chips (`.loc`) placed by the same collision idea. Added, in this order:

1. **Reach rings** — an SVG layer inserted as the overlay's first child so it sits under every label.
   ```
   .rings{position:absolute;inset:0;width:100%;height:100%}
   .rings circle{fill:none;stroke:rgba(201,162,75,.4);stroke-width:1;stroke-dasharray:3 3}
   ```
   Two circles centred on the projected home point: **40 km labelled `45 min`** and **80 km labelled `1h 30min`**. Radius in px comes from a measured projection scale, never a guess:
   ```js
   const kmPx = proj => {
     const a = proj(HOMEPT), b = proj([HOMEPT[0], HOMEPT[1] + 1]);
     return Math.abs(b[1] - a[1]) / 111.2;      // 1° latitude ≈ 111.2 km
   };
   ```
   A ring is skipped if `r < 18 px` (illegible) or `r > max(w,h) * 1.15` (entirely off-frame).

2. **Ring labels** — `.ringlb`: Mono 8.5px, letter-spacing .08em, colour rgba(235,217,168,.72), background rgba(14,11,9,.74), padding 1px 4px, radius 3px. Placed at the ring's top (`x: hx, y: hy - r`) through the same `placeLabels` pass, sharing the popup's existing box list so a ring label can never sit on a location chip.

3. **Home marker** — placed last through `placeLabels` against the same box list, at the larger `.mlab>.hm` sizing.

The popup's pre-existing box list already reserves the bottom-left tap hint (`{x:0, y:h-24, w:118, h:24}`); rings and home join that list, so region names and location chips continue to avoid all of it.

Rings state the reach thresholds in real distance, deliberately **not** a filtered view of the heat. The field still paints every rated location regardless of reach — filtering the field per driver would make the same night look different to two people.

## Screen 3 — Coming up, topic glyphs

**File:** `Coming Up.html`

```js
const G = {tide:'🌊', sky:'🌌', sun:'☀️', dust:'🏜️', air:'☁️', moon:'🌙'};
const glyphOf = n => /moon/i.test(n) ? G.moon : /tide|water/i.test(n) ? G.tide : null;
```

`tide / sky / dust / air` are the plan cards' existing topic emoji (`plan-data.js` → `TOPICS[*].ic`); `sun` and `moon` are new, for the two almanac families Plan does not name. An event may override with its own `ic`.

Four insertion points, glyph **after** the colour swatch in each:

| surface | markup |
|---|---|
| timeline row title | `<span class="gi">{e.ic \|\| G[e.g]}</span>` before `.nm` |
| coincidence sub-line | `<span class="sw"></span><span class="gi sm">{glyphOf(l[1])}</span>` |
| standing-condition row | `<span class="fam"><span class="sw2"></span><span class="gi c2">{G[c.g]}</span></span>` |
| filter chip | `<span class="dt2"></span><span class="gi ch">{G[f[0]]}</span>` |

```
.gi        font-size:12.5px; line-height:1; flex:none; align-self:center; filter:saturate(.9)
.feat .gi  font-size:14px
.gi.sm     font-size:11px
.gi.c2     font-size:12px; width:14px; text-align:center
.gi.ch     font-size:11px; margin-right:1px
.fam       display:flex; align-items:center; gap:7px
```

`.fam` exists because `.cond` is a four-column grid — swatch and glyph must occupy one cell together or the columns shift.

Glyph and swatch are **redundant on purpose**: the swatch carries the topic colour system (teal coastal, gold sun and moon), the glyph carries recognition. Removing either loses something.

---

## Interactions & behaviour

- **Thumbnail click** — unchanged; opens that window's popup.
- **Popup field click** — unchanged; picks the nearest region centroid within 26% of the field width, else clears. The new labels are `pointer-events:none` (except the existing `.loc` chips) so they never intercept it.
- **Resize** — thumbnails and popup field redraw and re-place labels; debounce 140 ms. Viewport switches (desk / pad / mob) redraw on `transitionend`.
- **Origin change** — an away origin refits the projection to that region, drops the home marker and the rings, and relabels the field with that region's own name only.
- No animation on any label. They appear with their field.

## State

No new state. Everything derives from existing state: `S.origin` (home vs away), `S.win`, `S.reg`, `S.reach`, `S.rate`, and the window index. Ring radii derive from the projection, which derives from the fitted bbox of the spots in scope.

## Design tokens used

| token | value | use |
|---|---|---|
| `--home` | `#C9A24B` | home marker border, ring stroke (at 40% alpha) |
| home label ink | `#EBD9A8` | HOME text, ring labels (at 72% alpha) |
| area name ink | `rgba(242,231,211,.62)` | region labels |
| area name ink, hot | `#F9F1E2` | strongest region for the window |
| marker halo | `rgba(20,15,11,.5)` | 2.5px spread ring behind the home dot |
| label shadow | `0 1px 3px rgba(0,0,0,.95)`, `0 0 8px rgba(0,0,0,.8)` | all labels on the field |
| mono | IBM Plex Mono | every label |
| label sizes | 7.5 / 8 / 8.5 / 9 px | HOME, area name, ring label, popup HOME |
| glyph sizes | 11 / 12 / 12.5 / 14 px | chip, condition, row, featured row |
| ring geometry | 40 km, 80 km; 1px dashed `3 3` | reach |
| nudge ladder | 0, ±13, ±24, ±36 px | collision resolution |
| collision padding | 3 px x, 2 px y | collision resolution |

## Assets

None. Topic glyphs are system emoji; the coastline is the existing `world-atlas@2.0.2` topojson already loaded by `heat-field.js`. No new fonts, no new images.

## Files in this bundle

| file | what it is |
|---|---|
| `Plan Tab with Heat v4.html` | the plan tab, hifi. Open this first. Structure, CSS, and the design notes at the top. |
| `plan-tab-v4.js` | its logic. `labelThumb`, `placeLabels`, `kmPx`, `drawThumbs`, `drawBig`. |
| `plan-data.js` | mock data: regions, locations, windows, topics. Reference only — production reads the real API. |
| `heat-field.js` | the existing shared heat kernel, **unchanged**. Included for context. |
| `Coming Up.html` | Coming up with the topic glyphs, hifi. |
| `Plan Thumbnail Geography.html` | the exploration behind the decision: five label treatments side by side (none / home / home+areas / home+places / home+areas+rings / home+towns). Not for implementation — it records why option B won and what was rejected. |

Rejected, and worth not re-litigating: best-place-per-region names on the thumbnail (four place names is a legend, and the card's `Best` row already names one); fixed town landmarks (survives coverage growth better, but reads as a basemap the app does not have); rings on the thumbnails (too small to carry them, and the small field's job is the shape of the night).
