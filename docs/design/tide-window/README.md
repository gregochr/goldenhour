# Handoff: Map tab — tide on the window

## Overview

An **increment on the Map tab**, not a redesign. Everything in `design_handoff_map_tab/` and
`design_handoff_map_landing/` still stands: the field kernel, the coast mask, the basemap, the
one chronological event list, the verdict on the window control, the landing card, the filter
popover, the label density rules, the anchored callout and the shared location sheet. This
bundle adds the second axis the coast has always needed.

**The problem.** A strong sunrise at **mid water** is the commonest coastal disappointment
there is, and the map could not show it. Tide reached the screen as a single wave glyph on the
few locations where high or low water happened to land within 45 minutes of the light.
Everything else — including a 4★ headland that wants another metre of water — looked exactly
like an inland spot. And where the tide mismatch pulled the score down, the location left the
map altogether: **an empty coast reads as "nothing here" rather than "not this morning"**,
which is the opposite of true and the more expensive error, because it costs a trip the
photographer would have taken on a different date.

**The change, in three parts.**

| Part | What it does |
|---|---|
| The tide strip | Bottom-left of the map, always on where the view holds coast. The day's tide curve with **high / mid / low water ruled across it**, night shaded, sunrise and sunset marked, and the moment of the light marked **on the curve** with its height. Collapses to one line. |
| Dimmed, not dropped | A coastal location whose tide is wrong still draws, at reduced emphasis, with the shortfall on its chip: the wave glyph carries an arrow saying this spot wants the water **higher** or **lower** than the light gives. Rating, ramp colour and star are untouched. |
| Next fit | The callout and the strip footer both name the next window that gives that water, and jump to it. |

**What deliberately did not change.** The heat. Tide does not dim the field, because the field
is the *light* score and a wrong tide does not make the sky worse. Folding tide into the ramp
would make the map go cold on a genuinely clear morning and the two facts could never be
separated again. Same reason the star rating on a dimmed chip is untouched.

## About the design files

| File | What it is |
|---|---|
| `Map Tide Window.html` + `map-tide-v5.js` | **The design.** A working Leaflet prototype — design reference, **not production code**. Recreate in React with this repo's patterns (`react-leaflet`, `components/MapView.jsx`, `components/TideIndicator.jsx`, `api/tideApi.js`, `hooks/useIsMobile.js`). Do not port imperative DOM code. |
| `heat-field.js` | Unchanged from the Map tab v2 bundle. **Do not re-port or re-tune.** Listed only so the prototype runs. |
| `plan-data.js` | Mock catalogue. Replace with real API data; keep the shape. |

The notes column down the right of `Map Tide Window.html` is part of the specification. The
rejection in it is load-bearing — see §7.

## Fidelity

**High.** Every hex, threshold, rule and copy string below is the value in the prototype and is
intended to be matched. Items flagged `OPEN` need a decision or real data before ship.

---

## 1 · The model

### Water level at the moment of the light

The prototype models the curve as a **cosine between extrema**, which a real tide is very close
to, anchored on one stated extremum per window:

```
HALF = 372.5                        // minutes, high water to low water
phase        = (t - anchor) / HALF
level(t)     = anchor is HW ?  (1 + cos(pi * phase)) / 2
                            :  (1 - cos(pi * phase)) / 2      // 0 = low water, 1 = high water
height(t)    = lowHeightMetres + level(t) * rangeMetres
direction    = (anchorIsHW === (t >= anchor)) ? 'falling' : 'rising'
```

**In production you do not need the cosine anchor.** `GET /api/tides?locationName&date`
(`api/tideApi.js → fetchTidesForDate`) already returns the day's extremes with
`heightMetres` — interpolate between consecutive extremes instead (cosine between each pair is
still the right interpolation; a linear ramp visibly flattens the turn of the tide). Use
`GET /api/tides/stats` (`fetchTideStats`) for the `avgHighMetres` / `avgLowMetres` that produce
the header's *"0.5 m below average"* note.

### The three bands

```
band(level) = level >= 0.75 -> 'high'
              level <= 0.25 -> 'low'
              otherwise     -> 'mid'
```

These are **not thirds of the range**. They sit where the useful foreground changes character,
so `0.75` and `0.25` are tuning knobs, not arithmetic. `OPEN 2`.

### Fit, per location per window

Every coastal location carries the water it wants. Fit is then computed, not written:

```
want    : 'high' | 'mid' | 'low'          // one enum on the location record — see OPEN 1
target  : high -> 1.0   mid -> 0.5   low -> 0.0
fit     = 1 - |level(lightTime) - target[want]|
tier    = fit >= 0.80 -> 'match'
          fit >= 0.62 -> 'near'
          otherwise   -> 'miss'
```

Only **solar** events have a tide fit. Astro and aurora events return none — the strip hides
and no chip is dimmed.

`want` values in the prototype (`WANT` in `map-tide-v5.js`), as a sanity check on the enum:
high — St Mary's Lighthouse, Blyth Beach, Roker Pier & Lighthouse, Amble, Alnmouth, Saltburn
Pier, Redcar, St Abb's Head, Eyemouth, Eilean Donan · mid — Whitby Abbey, Ravenscar,
Coldingham Bay · low — Bamburgh Castle, Marsden Bay, Robin Hood's Bay, Sandsend Ness.
Unnamed scatter locations derive a want deterministically from their coordinates so the field
behaves like the shipped one; that derivation is prototype scaffolding, not a rule to port.

### Next fit

```
nextFitEv(want, fromIndex):
  scan the event list forward from fromIndex + 1
  return the first SOLAR event where 1 - |level(event) - target[want]| >= 0.80, else none
```

Scanning only the scored forecast horizon is a limitation, not a design choice — `OPEN 4`.

---

## 2 · The tide strip

### Visibility

Shown when **both**: the current event is solar, **and** the map viewport
(`bounds.pad(0.12)`) holds at least one coastal rated location. Pan inland or jump to the Lake
District and the strip leaves rather than sitting there empty — tide stops claiming to be the
question. It is **not a mode and has no toggle**: the morning you would have to remember to
turn it on is the morning you drive an hour to a covered foreground. The only control is
collapse.

Counts in the footer are counts of **named coastal locations in view**, which is what makes the
word "here" true, and they update live as you pan — the same behaviour the existing count line
already has.

### Geometry and chrome

| Property | Value |
|---|---|
| Position | `absolute; left:12px; bottom:12px; z-index:1120` |
| Width | `474px`, `max-width:calc(100% - 24px)` |
| Surface | `background:rgba(20,15,12,.94)`, `border:1px solid var(--border)` (#3A2C23), `border-radius:10px`, `box-shadow:0 14px 34px rgba(0,0,0,.5)`, `backdrop-filter:blur(7px)` |
| Font | `var(--mono)` (IBM Plex Mono) throughout |
| Body grid | `grid-template-columns:31px minmax(0,1fr); gap:0 8px; padding:2px 11px 0` |
| Chart height | `48px` (band gutter matches) |

**The strip publishes its own height.** After each render the component sets
`--tsh: <offsetHeight>px` on the map wrapper, and the chrome above it clears that value:

```css
#mapwrap.tw #lchip, #mapwrap.tw .foot { bottom: calc(var(--tsh,120px) + 22px) }
#mapwrap.tw #lpanel                   { bottom: calc(var(--tsh,120px) + 66px) }
```

Open and collapsed are roughly 3× apart in height, so a fixed offset is wrong for one of them.
Measured: strip `474 × 166` open, legend chip and count line clear it with 22px.

### Header row

| Element | Spec | Copy |
|---|---|---|
| `.ts-k` | 8.5px / 600 / `.13em` / uppercase / `var(--ink-3)` | `Tide at this light` |
| `.ts-st` | 11px / 600 / `.05em` / uppercase / `#8FC0C7` | `mid tide, rising` · `high water, falling` · `low water, rising` |
| `.ts-m` | 9.5px / `var(--ink-3)`, `nowrap` + ellipsis | `2.6 m · about average` |
| `.ts-x` | 10px, 1px `var(--border)`, radius 6, `min-height:22px`, right-aligned | `▾` (open) · `Open ▴` (collapsed) |

The state word and the direction are one phrase, because "mid" alone does not tell you whether
to hurry.

### The chart

`svg viewBox="0 0 1000 32" preserveAspectRatio="none"`, so the curve stretches horizontally
with the panel. Vertical mapping, shared by the SVG and every HTML overlay on top of it:

```
level 1 (high water) -> y  6        level 0 (low water) -> y 26
TY(level) = (26 - level * 20) / 32 * 100      // percent down the 48px box
```

| Layer | Spec |
|---|---|
| Night | two rects, `fill:rgba(0,0,0,.32)`, from 0 to sunrise and from sunset to 24:00 |
| Water | area under the curve, `fill:rgba(111,168,176,.17)` — level is an **area**, not a line |
| Curve | `stroke:#6FA8B0`, `stroke-width:1.5`, `vector-effect:non-scaling-stroke` (mandatory: the SVG is non-uniformly scaled) |
| High / mid / low lines | three `1px dashed rgba(242,231,211,.17)` rules at `TY(1)`, `TY(0.5)`, `TY(0)` — the guide and its label share the level, so the dot is read against real water lines rather than invented band boundaries |
| Band labels | gutter column, 8px `.09em` `var(--ink-3)`, `translateY(-50%)`; the band the light falls in is `#8FC0C7` / 600 |
| Sunrise / sunset | `1px rgba(224,165,66,.5)` verticals, labels 8px `var(--marginal)` (#E0A542), `↑ 05:42` left-aligned, `↓ 20:25` right-aligned |
| Extrema | `HW 08:47` / `LW 02:34` below the curve, 8px `var(--ink-3)` with the time in `var(--ink-2)`, `translateX(-50%)`, clamped to 5–95% so edge labels stay inside |
| The light | 7px dot `#8FC0C7`, ring `0 0 0 2px rgba(20,15,12,.95)`, glow `0 0 10px rgba(111,168,176,.85)`, at `left:X(lightTime)`, `top:TY(level)`; height label above it, 9px / 600 / `#8FC0C7`, clamped 9–91% |
| Hour axis | `00 06 12 18 24`, 8px `var(--ink-3)`, `space-between` under the chart column only |

The whole chart is `aria-hidden`. The meaning is carried by the header phrase, the height and
the footer sentence — do not let the chart become the only statement of it.

### Footer row

`9.5px var(--ink-3)`, counts in `var(--ink)` / 600, `border-top:1px solid var(--border)`,
`background:rgba(0,0,0,.24)`.

| Case | Copy |
|---|---|
| Some miss | `13 of 16 coastal spots are dimmed — 9 of them want high water` (drop `9 of them` when every miss shares one want: `they want high water`) |
| None miss | `6 of 16 coastal spots have the water they want` |
| No fit either way | `No coastal spot here has its water on this light` |
| Next fit (right-aligned button, `var(--tide)`) | `Next high water on the light · Thursday sunset ›` |
| No next fit in the horizon | `Next high water on a sunrise is beyond these four days` |

The next-fit target is the **dominant unmet want** among the dimmed locations in view, so the
jump serves the majority of what just went quiet.

### Collapsed row

One line, 9.5px: `Tide` · state · `2.6 m at 05:42` · `· 13 dimmed` · `Open ▴`. Collapse state
persists for the session; it does not reset on window change.

---

## 3 · The chip

```
match -> class .td    + wave glyph, var(--tide)       (existing v2 treatment, unchanged)
near  -> no class     + no glyph                      (silent: not worth a mark either way)
miss  -> class .tmiss + wave-with-arrow glyph, muted
```

| Property | Value |
|---|---|
| `.loc.tmiss` | `opacity:.72` |
| `.loc.tmiss:hover`, `.loc.tmiss.on` | `opacity:1` — inspecting or selecting restores it in full |
| `.loc.tmiss .tw` | `color:rgba(242,231,211,.5)` |
| Glyphs | match `viewBox="0 0 14 8"`, 13×7 rendered; miss `viewBox="0 0 21 8"`, 19×8 rendered |

The miss glyph is the **same wave** with an arrow: `↑` when the spot wants the water higher
than the light gives, `↓` when lower. A crossed-out wave was rejected — it says "no tide here",
which is false and the wrong direction of information.

Nothing else about the chip changes: same ramp swatch, same name, same `N★`. The light is as
good as it was; only emphasis moves. Never re-colour the star or the swatch for tide —
`design_temp_scale` Change 5a still holds (ramp colour is never ink).

**Label budget.** The existing tiebreaker stays: score, then tide match, then drive time. A
`miss` does **not** lose its label to an inland location of equal score — the sort only promotes
matches; it never demotes misses below the rest. That is the whole point of dimming rather than
dropping.

---

## 4 · The callout and the location sheet

One block renders all three tiers, used by both the anchored map callout and the shared
location sheet (`.ctide`, already in the Map tab v2 bundle):

| Tier | Border / background | Copy |
|---|---|---|
| match | `rgba(111,168,176,.32)` / `rgba(111,168,176,.09)` | **Tide lands on the light** · `mid tide, rising · HW 20:46 · 17m after sunset · 3.9 m` |
| near | `rgba(111,168,176,.22)` / `rgba(111,168,176,.05)` | **Close on the tide** · `Wants high water · mid tide, rising at 05:42, 3.2 m of 4.3 m` |
| miss | `rgba(242,231,211,.2)` / `rgba(242,231,211,.05)` | **Wrong water, not wrong light** · `Wants low water. At 05:42 it is mid tide, rising — 2.6 m of 4.3 m.` |

The miss block then carries the jump, as a text button on its own line
(`var(--tide)`, 9.5px):

```
Next low water on the light · Tomorrow sunset 20:25 ›
```

…or, when nothing in the horizon fits, the same line as plain `var(--ink-3)` italic-weight text:
`Nothing in these four days puts low water on the light here.`

Tooltip on hover gets one extra line: `Wants high water — mid tide, rising at 05:42, 2.6 m`, in
`rgba(242,231,211,.72)`.

Heading choice matters here. **"Wrong water, not wrong light"** is doing the work the old
absence could not: it tells the reader the forecast is good and the reason for the demotion is
something they may choose to overrule.

---

## 5 · State

| State | Where | Notes |
|---|---|---|
| `tideCollapsed` | strip | Session-persistent; independent of the selected window |
| `--tsh` | map wrapper | Published by the strip after render; consumed by legend chip, count line, legend panel |
| Derived per (window, location) | memoise | `level`, `band`, `direction`, `height`, `fit`, `tier` — recompute on window change, not on pan |
| Derived per (window, viewport) | recompute on pan | in-view coastal counts, dominant unmet want, next-fit index |

No new fetch pattern: tide extremes are already fetched per location per date
(`TideIndicator.jsx` does exactly this). What the strip needs is **one representative day
curve for the view** rather than one per location — see `OPEN 3`.

---

## 6 · Responsive

| Width | Behaviour |
|---|---|
| Desktop / iPad | As specified. Strip `474px` bottom-left; legend chip and count line clear `--tsh`. |
| Phone (`.wrap.mob`) | Strip goes full width: `left:10px; right:10px; bottom:60px` (above `#gnav`). The **count line is hidden** while the strip is up — the tide sentence is the more useful line at that width, and stacking both pushes the map to nothing. Measured clearance: strip bottom 741 vs `#gnav` top 747. |

---

## 7 · Rejected: shading the sea

The first instinct — and the one that prompted the work — is a semi-transparent blue wash over
the water, keyed to the tide, so the map itself shows the level. It was rejected, and the
reason is worth keeping because it will come up again:

- Tide phase along this coast drifts **well under an hour** end to end. A wash keyed to it would
  be near-uniform, so it would carry almost no information while covering a great deal of map.
- Where it *did* vary it would imply a **spatial** story about something **temporal** — that the
  tide is different *there* in a way that matters to where you stand, which it is not.
- It would sit exactly on top of the coastal locations it describes, which are the pins whose
  legibility this whole change exists to protect.

The coast is where the answer is **needed**; it is not where the answer can be **drawn**.

**Worth testing instead, cheaply:** stroke the existing coastline path thicker at high water and
thinner at low. Same fact, rendered on a line that is already there, covering nothing. The coast
geometry is already stroked from the field's mask (`drawCoast`), so this is a width function,
not a new layer.

---

## 8 · Design tokens

All from the existing palette. **No new colour values.**

| Token | Value | Use here |
|---|---|---|
| `--tide` | `#6FA8B0` | curve stroke, next-fit buttons, match border |
| — | `#8FC0C7` | tide state word, band hit label, the light dot and its height |
| — | `rgba(111,168,176,.17)` | water area fill |
| `--marginal` | `#E0A542` | sunrise / sunset rules and labels |
| `--ink` | `#F2E7D3` | footer counts |
| `--ink-2` | `rgba(242,231,211,.66)` | extrema times |
| `--ink-3` | `.42` page-wide, **`.66` inside `#mapwrap`** | every small label in the strip. The map wrapper's opt-in override is why these pass AA — do not hard-code `.42` in the strip |
| `--border` | `#3A2C23` | strip border, footer rule |
| `--mono` | IBM Plex Mono | the entire strip |
| Dash rules | `rgba(242,231,211,.17)` | high / mid / low water lines |
| Night | `rgba(0,0,0,.32)` | before sunrise, after sunset |

Contrast measured: every text label in the strip ≥ 7:1 on the panel. The hour axis was the one
regression found in review (`.34` alpha, 2.73:1) and is now `var(--ink-3)`.

---

## 9 · Open items

**OPEN 1 · the one new field.** `want: 'high' | 'mid' | 'low'` (nullable) on the location
record, meaningful only where `coast` is true. Needs a column, an API field, and an editor in
`components/LocationManagementView.jsx` (or `TideManagementView.jsx`, which already owns the
tide surface). Everything else in this design derives from data the app already has. Decide
whether `null` on a coastal location means *no preference* (never dim) or *unknown* (never dim,
but flag for the user to fill in). The prototype assumes the first.

**OPEN 2 · the band thresholds.** `0.75` / `0.25` are editorial. They decide how much of the
coast dims on a mid-water morning, so validate them against a real user's own spots before
ship — ideally by asking two or three photographers where their foreground stops working.

**OPEN 3 · one curve for many locations.** The strip states a single day curve for the whole
view, and tide times vary along the coast (~40–60 min across the Northumberland/Yorkshire
span). Decide between: the nearest station to the viewport centroid, the station serving the
highest-rated coastal location in view, or the selected location when one is open. Whichever is
chosen, **the strip should say which** — an unattributed curve invites a 20-minute error.

**OPEN 4 · next fit beyond the forecast.** The prototype only scans the scored windows, so it
can only answer within four days. Tide is deterministic years out, and the light times are
computable, so the genuinely useful version of this answer — *"the next sunrise with high water
at Bamburgh is Tue 22"* — needs no forecast at all. It is a tide-plus-ephemeris query, not a
forecast query, and it is the strongest follow-on this design opens up.

**OPEN 5 · dimming and the score.** This design deliberately leaves scoring alone. If the
shipped model already penalises a tide mismatch inside the star rating, the user now sees the
penalty **twice** (a lower star and a dimmed chip) and the strip's counts will disagree with the
ratings. Check `api/forecastApi.js` scoring before build: tide should be represented in the
chip's emphasis or in its score, **not both**.

---

## 10 · Verify by measurement

Report numbers, not screenshots.

1. **Tide never moves the heat.** Field alpha at a coastal location's core is identical with
   `want` set to each of high / mid / low on the same window.
2. **Nothing is dropped.** Count named coastal chips placed on a mid-water window; it equals the
   count on a high-water window at the same viewport and zoom (dimming changes opacity, not
   membership).
3. **Strip visibility.** Solar + coast in view → visible. Night event → hidden. Viewport with no
   coastal rated location → hidden, and `#lchip` / `.foot` return to `bottom:12px`.
4. **`--tsh` clearance.** Open and collapsed: legend chip bottom and count line bottom both sit
   at least 22px above the strip's top edge.
5. **Chart mapping.** The light dot's `top` equals `TY(level)` within 1px, and the dot sits on
   the curve at the event time for all six windows.
6. **Next fit is real.** For each window and each `want`, the returned event's level satisfies
   `fit >= 0.80`; where none exists the copy says "beyond", never a false jump.
7. **Contrast.** Every text node in the strip ≥ 4.5:1 against `rgba(20,15,12,.94)`.
8. **Phone.** Strip does not overlap `#gnav`; the count line is hidden while the strip is up.

---

## 11 · Files

| File | Notes |
|---|---|
| `Map Tide Window.html` | The design. Notes column is part of the spec. |
| `map-tide-v5.js` | Behaviour. Tide model at the top (`TIDEX`, `LVL`, `bandOf`, `tideFitOf`, `nextFitEv`, `tideBlock`); strip at the bottom (`tideChart`, `renderTide`). |
| `heat-field.js` | Unchanged. Do not re-tune. |
| `plan-data.js` | Mock catalogue. The prototype's per-window tide facts live in `TIDEX` in `map-tide-v5.js`, not here. |

### Screenshots

| File | State |
|---|---|
| `screenshots/01-mid-water-sunrise.png` | Wednesday sunrise, 4★ light at mid water — the case this design exists for. Strip open; coastal chips dimmed with the arrow glyph. |
| `screenshots/02-miss-callout-next-fit.png` | Bamburgh Castle selected: dimmed chip, the *Wrong water, not wrong light* block, and the next-fit jump. Footer shows `13 of 16 coastal spots are dimmed — 9 of them want high water`. |
| `screenshots/03-match-thursday-sunset.png` | Thursday sunset, high water 17m after the light. Same chips at full strength with the plain wave glyph; matching callout block. |
| `screenshots/04-strip-collapsed.png` | Collapsed strip, and the legend chip / count line re-seated against the smaller `--tsh`. |

Existing code this touches: `components/MapView.jsx` (chips, callout, chrome),
`components/TideIndicator.jsx` (already fetches the extremes this needs),
`api/tideApi.js` (`fetchTidesForDate`, `fetchTideStats`), `components/MarkerPopupContent.jsx`.
