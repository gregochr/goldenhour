# Handoff: Heat map — Plan tab + Map tab

**Scope: v2 / Plan First UI only.** v1 is being disabled, so do not port any of this into v1
surfaces. Everything below assumes the window-first v2 Plan screen is the host.

**Date:** 2026-08-18
**Fidelity:** High. Colours, type, spacing, radii and copy are final and should be matched.

---

## Overview

The map is currently second-class because medallions stopped scaling. A medallion does two
jobs at once — the number is *how many* locations, the colour is their *average* score — and
both degrade when zoomed out, which is exactly when the strategic question is being asked.
Averaging a cluster of 45 hides the one 5★ gem inside it.

This work adds a **heat field**: every rated location paints its own score for one solar
window, blended where locations overlap. It lands in two places:

1. **Plan tab** — six thumbnails directly under the lens, one per solar window, in the same
   order as the window rows beneath them. The summary becomes a *visual index of the window
   list*, so the shape of the next four days is legible before a word is read. Opening a
   window row expands it into a full-width map, a ranked region rail, and the filtered
   location cards that already exist.
2. **Map tab** — the same field hosted on the existing Leaflet map. Heat is the default view;
   the current medallion clustering stays one tap away.

Division of labour, deliberately: **Plan is time-first** ("which of my six windows", with
space shown inside each). **Map is space-first** ("what about *here*" — pan to a headland, a
valley, a road you are driving). Same field, different question. This is a proposal rather
than a settled decision — see *Open questions*.

## About the design files

The files in this bundle are **design references created in HTML** — prototypes showing
intended look and behaviour, not production code to copy. The task is to recreate them in the
existing React + Vite codebase using its established patterns (`react-leaflet`,
`react-leaflet-cluster`, Tailwind v4 `plex-*` tokens, PropTypes).

The **one exception** is `heat-field.js`. It is deliberately framework-free — no React, no
Leaflet, no DOM assumptions beyond a canvas. Port it close to as-is into
`frontend/src/components/heatField.js` (or `utils/`). Its algorithm is load-bearing and was
tuned against real performance limits; rewriting it from scratch will reintroduce the stalls
described under *Performance*.

---

## Files in this bundle

| File | What it is |
| --- | --- |
| `Plan Tab with Heat.html` | Plan tab spec. Source of truth for the strip, window rows, region rail, drill-down band, origin chip and search. Three viewports via the toggle above the frame. |
| `Map Tab with Heat.html` | Map tab spec. Heat over tiles, view toggle, area toggle, dark-sky filter, zoom handover. |
| `heat-field.js` | **Port this.** The shared field kernel and geo helpers. |
| `plan-data.js` | Mock catalogue + the planning-area rule. Replace the data with API calls; **keep the rule** (see *Planning area*). |
| `plan-tab.js` | Plan tab behaviour. Reference, not for porting. |
| `map-tab.js` | Map tab behaviour. Reference, not for porting. |
| `screens/` | Reference screenshots — see below. |

### screens/

Captured from the live prototypes. **Note the widths:** these come from a preview pane, so the
"desktop" frames render around 750–920px rather than the 1180px design width. The layout rules
are in the CSS — `.wrap.pad` (≤834px: rail columns widen, cards get a bigger flex basis) and
`.wrap.mob` (390px: everything stacks). Above 834px desktop and iPad share the same rules, so
those two frames legitimately look alike.

| Image | State |
| --- | --- |
| `01-plan-desktop-strip.png` | Default: masthead, origin chip, lens, six thumbnails, footer, change line, beyond line, collapsed rows. |
| `02-plan-order-best.png` | `Order · Best` — rows re-ranked and numbered (`1` on Thursday sunset), **strip still chronological**. Also shows the open row's full-width map with all seven region labels. |
| `03-plan-search-region.png` | Search open on `lakes`, showing the Region result row and `PLAN FROM HERE`. |
| `04-plan-origin-lake-district.png` | **The headline state.** Origin moved to the Lake District: chip and pin go blue, `⌂` appears, lens reads `DRIVE FROM KESWICK`, reach auto-drops to 1h 30, all six thumbnails re-frame to the Lakes, `BEST BET` moves to Wednesday sunrise, region lead narrative appears. |
| `05-plan-ipad.png` | iPad (754px) — six thumbnails still one row, plus the open Thursday sunset row and its map. |
| `06-plan-phone.png` | iPhone (390px) — status bar, two-row lens (`DRIVE` / `RATED` + When\|Best), 3×2 thumbnail grid with all six windows, wrapped change and beyond lines. |
| `07-map-heat-my-area.png` | Map tab default: heat, planning area, toolbar clear of the zoom control. |
| `08-map-window-switched.png` | A different solar window — the field repaints. |
| `09-map-whole-catalogue-scotland.png` | `Whole catalogue` — refits to include the Highlands. |
| `10-map-medallions.png` | Medallion view, the honest "before". |
| `11-map-zoom-handover.png` | Zoomed in — field faded to a wash, locations named and forward. |

The expanded window row's **region rail** and **drill-down band** are not isolated in any single
frame (they sit below the map, past one viewport height). Both are fully specified under
*Screens · 2*; open `Plan Tab with Heat.html`, click any `Open ▾`, and click a region.

The prototypes persist the demo viewport and the `Order` choice in `localStorage`
(`photocast.heat.viewport`, `photocast.heat.order`) so a reload comes back where you left it.

Source files live in the project at `design/`. Open both HTML files before writing code —
the explanatory prose above each frame and the notes beneath it carry the reasoning and are
**not** part of the UI. Strip: `.hd`, `.demobar`, `.notes`, `.cap`, `.try`, and the three
viewport buttons.

---

## Architecture — the part that matters most

The two surfaces share **one field kernel** and **one catalogue**. Before consolidating they
each had their own copy of both, and a performance fix landed on one and not the other. Keep
the seam:

```
heatField.js          ← kernel: screen-space points → blended field. Knows nothing about maps.
  .field(pts,w,h,o)   ← the accumulator (cull + bucket + ramp + coverage clamp + haze)
  .paint(ctx,...)     ← blur + composite into any 2d context
  .drawGeo(cv,...)    ← host A: d3-geo projection, clipped to real coastline  (Plan thumbnails)
  .drawTiles(cv,map,) ← host B: Leaflet projection, painted over tiles        (Map tab)
  .bbox/.latLngBounds/.aspect/.centroid/.radiusFor   ← framing helpers
```

`drawGeo` needs `d3-geo`, `d3-array` and `topojson-client` plus UK land geometry (currently
`world-atlas@2.0.2` `countries-50m`, filtered to id `826`). If you would rather not add d3,
the Plan thumbnails could instead be six small Leaflet maps — but that is six tile-map
instances on one screen, and the static canvas is much cheaper. Prefer d3.

**Never hand-draw a coastline.** The geometry comes from real topology.

---

## Performance — non-negotiable

The kernel went through two rounds of optimisation against real limits. Both are in the code
and both are required:

1. **Cull.** A location outside the frame plus the kernel's reach (`2.45 × radius`) cannot
   touch a single cell, so it is dropped before the loop.
2. **Spatial bucketing.** Points are bucketed by the cutoff distance; each grid cell sums only
   its 3×3 bucket neighbourhood. This turns `O(cells × locations)` into
   `O(cells × local density)`.

Without bucketing, **204 locations already stalled a pan on the Map tab** (~4.5M inner-loop
iterations per frame, `executeJavaScript` timing out at 7s). With it, the same view is
interactive. Coverage is growing to Scotland and the Peak District and beyond, so this is the
difference between "works today" and "works at a thousand locations".

Grid step is a quality/cost dial: `grid: 4` for thumbnails, `grid: 6` for the big map and the
tile map. Blur hides the coarser step; do not drop below 3 without measuring.

Also required: **throttle, do not debounce, on map move.** Use one rAF-guarded `render()` for
`move`/`zoom` and an un-throttled `renderNow()` on `moveend`/`zoomend`. An earlier build
debounced, so every frame cancelled the pending redraw and the overlay sat stale until the
gesture finished.

**Memoise derived scope.** `areaRids()` rescans the whole catalogue, and it sits beneath
`scopeSpots()`, `topAvg()` and `bestWin()`, which are called dozens of times while building the
strip and six rows. `plan-tab.js` caches `areaRids` / `scopeRids` / `scopeSpots` and per-region
window scores, invalidated once per `render()`. In React this falls out naturally from `useMemo`
on the origin and the catalogue — just do not recompute it inside a render loop.

One more trap, learned the hard way: **do not issue a heavy synchronous paint in the same tick
as an animated `fitBounds`** — it forces layout mid-transition and strands Leaflet at the old
view, so labels update while the map never moves. `setArea()` uses `{animate:false}` for
exactly this reason. A jump is honest; a silent no-op is not.

---

## The heat field — what it encodes

- **Intensity is the location's own score for one solar window.** One window at a time, as the
  app already works. All scores show — gems, duds and everything between — and the colours
  merge into a continuous field.
- **Not density.** Heat must never mean "how many locations are here", or it will light up
  wherever locations have been added and never reflect tonight.
- **Coverage is clamped.** `cov = 1 - exp(-Σw / 1.15)`, and cells below `Σw < 0.02` are fully
  transparent. Warmth appears only where locations actually are; empty moorland stays empty
  rather than being coloured in by interpolation from thirty miles away. This is the usual way
  a heat map lies — do not remove it.
- **Confidence is drawn, not just stated.** `conf` 0–1 desaturates toward grey (60% of the way
  at conf 0), thins alpha (−34%), and adds up to 2.6px of extra blur. A day-4 guess must not
  look as authoritative as tonight. The row header shows the same number as `◐ 88%`.
- **Focus** fades every region but one to 1e-4 weight, used when a region is drilled into.
- **The lens does not filter the field.** Drive time and rating floor filter the *cards*, never
  the heat. Repainting per driver would make the same night look different to two people and
  hide that the Lakes are on fire but two hours away. Both surfaces state this in a footer.

### Colour ramp — reconcile before building

The heat ramp is the app's existing **verdict palette**, which is a happy accident worth
keeping:

| Score | Hex | Token |
| --- | --- | --- |
| 1 | `#B03A2A` | (darker than standdown) |
| 2 | `#C8452F` | `--color-verdict-standdown` |
| 3 | `#E0A542` | `--color-verdict-marginal` |
| 4 | `#B0BE74` | (between) |
| 5 | `#8AAE72` | `--color-verdict-go` |

Linear interpolation between stops; scores clamp to 1–5.

**Conflict to resolve explicitly:** `frontend/src/components/markerUtils.js` `RATING_COLOURS`
is a monochrome grey→gold ramp (`#6B6B6B` → `#E5A00D`) that predates the Kodachrome skin. Heat
depends on *hue* to be readable at a glance — a monochrome field reads as one smear. Two
consistent options:

1. **Recommended.** Move markers to the verdict ramp so the map has one colour language. The
   tokens already exist; this is a small change to `markerUtils.js`.
2. Keep `RATING_COLOURS` for markers and accept that pins and field speak differently. Then
   say so in the legend.

Do not silently ship both ramps meaning the same thing.

---

## Planning area — the rule that lets coverage grow

`GLANCE = 180` minutes. A region is in your planning area if it is a configured home region
**or** its nearest location is within `GLANCE` of your base.

This exists because "show everything" and "show everything you could plausibly go to" were the
same sentence at northern-England scale and stop being the same once Scotland is in. The
Borders (1h52 to Kelso) and the Peak (2h26 to Ladybower) clear it; Highlands & Skye does not.
Without the rule, six thumbnails of Britain give you three green pixels and no glance at all.

- The undrilled field, the region rail and the Map tab's opening bounds all frame to the area.
- Regions **beyond** the area are named under the strip — "Beyond 3h and not in the field:
  Highlands & Skye — search to plan from one →" — and the link opens search pre-filled. Nothing
  is hidden; it is one search away.
- The Map tab has **My area / Whole catalogue** to switch, since panning is that tab's job.
- Both surfaces read the rule from one place so they cannot disagree.

`GLANCE` should become a user setting eventually, defaulting to the widest configured drive
time. Hard-coding 180 is fine for the first cut.

---

## Screens

### 1 · Plan tab — the summary strip

Sits directly under the lens, above the window rows, inside a `border-bottom` block.

- **Header row:** `NEXT FOUR DAYS` (mono 10px, 0.11em, uppercase, `plex-text-muted`), a 1px
  rule filling the gap, then `204 rated locations · 51 named` right-aligned.
- **Six cards** in `grid-template-columns: repeat(6, 1fr); gap: 7px`. Desktop and iPad keep one
  row; **phone drops to `repeat(3,1fr)`** — a 3×2 grid, not a horizontal scroll, because
  hiding half the week defeats the glance.
- **Card** (`.hc`): `1px solid #3A2C23`, radius 9px, `background #1E1712`, padding `6px 6px 7px`,
  `display:flex; flex-direction:column; gap:5px`. Hover lightens border and background.
  Selected: border `rgba(201,162,75,.62)`, background `rgba(201,162,75,.09)`.
  - Top row: `TUE` (mono 9px 600, 0.09em, muted; gold `#C9A24B` when selected) · movement chip
    right-aligned · `↑`/`↓` arrow for sunrise/sunset.
  - Canvas: `width:100%; height:auto; border-radius:5px; background:#13100e`. Aspect comes from
    the frame's geography, clamped **0.85–1.22** — a true 1.5 made a 200px-tall strip.
  - Bottom row: time (mono 10.5px 600) · verdict word right-aligned, coloured
    go/marginal/standdown.
  - `BEST BET` flag: absolute `top:22px; right:6px`, mono 8px 600, `rgba(138,174,114,.94)` on
    `#15200f`. **Positioned below the top row on purpose** so it cannot cover the movement chip.
- **Movement chip** (`.dl`): `▲0.6` green `#A8C795`, `▼0.3` red `#E58C7A`, `—` muted for no
  change. Delta against the previous forecast run, for the region that sets that window's
  verdict.
- **Footer:** 60×6px ramp bar, `poor → worth it`, `later days render hazier — lower confidence`,
  then right-aligned `The field shows the forecast, not your reach — the cards below apply it`.
- **Change line:** `Since your last look 52m ago · Tomorrow sunrise ▲0.6 in The Lake District ·
  Thursday sunset ▲0.5 in Northumberland & Tyneside`. Names the two biggest movers.
- **Beyond line:** the planning-area note described above, 4px below, at 34% opacity.

Clicking a card opens that window's row and scrolls to it (`offsetTop - 96`, smooth).

**Order · Best re-ranks the rows and numbers them, but never the thumbnails.** Reordering the
strip would destroy its time axis, which is the only reason the week's shape is legible. The
strip is *when*; the rows are whatever you asked for.

### 2 · Plan tab — an open window row

Order inside `.wbody`, top to bottom:

1. **Full-width map.** `mapbox` wrapper, 1px border, radius 9px. Height `bw × aspect` clamped
   0.36–0.62 desktop, 0.5–0.95 phone. Region labels at cluster centroids, mono 9.5px 600, cream
   with a heavy text-shadow; the active one gets a `rgba(20,15,12,.85)` plate. Hint bottom-left.
   Clicking within 26% of frame width of a centroid selects that region; clicking it again or
   clicking empty space clears.
2. **Region rail** — `All N regions` first as a **peer cell**, then every region in the area
   ranked by score. `grid-template-columns: repeat(auto-fit, minmax(118px, 1fr))` so it survives
   a growing region list; phone is `1fr 1fr` with the All cell spanning full width at 46px min
   height. Each cell: name, verdict word, then `best 5★ · 12 in reach`. **Regions with nothing
   in reach show their distance instead** — `best 5★ · 2h 38min away` — so "worth it, and three
   hours away" reads in one line. The rail disappears when the origin is already a single region.
3. **Region band** (only once a region is selected) — name, `▲0.4 since 52m ago`, the active
   filter list, `Show all regions ×`, the narrative, three figures (`best in field`, `at 4★+`,
   `within 2h 30min`), and a six-dot window strip with `◎` on the region's own best window.
   Grid `1fr auto`, explicit row placement; phone collapses to one column.
4. **Tide row** — unchanged from the shipped design.
5. **Location cards** — unchanged, plus a **leave-by** line (see below).
6. **Footer** — `Ranked by rating, then drive time.` · an active-filter chip · `See all N →`.

There is deliberately **no permanent map or narrative panel**. At most one big map is on screen,
inside the window it describes, and closing the row takes it away — so the layout never carries
an empty half waiting to be filled.

### 3 · Plan tab — leave-by on every card

`↰ leave 03:50` — window time minus drive time minus `SETUP = 20` minutes to park and set up.
Mono 10.5px, `rgba(235,217,168,.72)` with the time in `#EBD9A8` 600.

A rating is an opinion; a departure time is a plan. This is the single highest-value small
addition in the set — it turns the card lane from a ranking into a shortlist of alarms. It also
recomputes from the local base when the origin moves, so an away plan is actionable.

### 4 · Map tab

Existing `MapView.jsx` gains a heat canvas between the tile layer and the marker layer.

- **Opening bounds:** `HeatField.latLngBounds(areaSpots(), 0.12)` with `padding:[28,28]`.
  **Not** all locations — that opens on the whole of Britain.
- **Toolbar** top-left, offset `left: 60px` and `z-index: 1100`. Both are required: Leaflet's
  zoom control sits at z-index 800 inside a 1000 container and will otherwise paint over your
  controls and steal their clicks. Corners are all taken — panel bottom-left, count bottom-right,
  view toggle top-right — so offsetting is safer than repositioning the zoom control.
  - `◎ My area` / `Whole catalogue` segmented control.
  - `🔭 Dark sky only` toggle.
  - `Heat` / `◍ Medallions` segmented control, right-aligned. **Heat is the default.**
- **Zoom handover.** Heat is the base layer; from z10.6 to z12.2 it fades to a faint 17% wash
  while locations come forward, picking up names past 55% blend. The field stays rather than
  vanishing, so regional context survives the zoom in. A panel bottom-left states
  `Field` → `Handing over` → `Locations` with a mix bar, so the state is never ambiguous.
- **Radius** is set in real distance: `radiusFor(map, 8500, 34, 240)` — 8.5km, clamped 34–240px.
- **Medallion view** keeps the existing 64px-cell clustering with count and average score. It is
  the honest "before" and at anything wider than a county it shows why: a cluster of forty
  averages its own gems away.

---

## Interactions

| Trigger | Behaviour |
| --- | --- |
| Click a thumbnail | Opens that window's row, clears region, smooth-scrolls to it |
| Click a window header | Toggles the row, clears region |
| Click a region on the map | Filters that window's cards; focuses the field |
| Click the same region again | Clears |
| Click `All N regions` | Clears |
| Click a rail cell | Selects that region |
| Click a six-dot | Jumps to that window |
| Click a location card | Opens the four-day location sheet |
| Origin chip / `/` | Opens search |
| Search a region | Re-points pool, drive times, lens label **and the heat** |
| `⌂` | Back to home origin, reach resets to 2h 30 |
| `My area` / `Whole catalogue` | Refits bounds, `{animate:false}` |
| `Dark sky only` | Filters locations, repaints the field |

Search matches three kinds in one box, grouped in the dropdown: **Windows** (`thursday sunset`,
`tonight`, `tomorrow`), **Regions**, **Locations**. Region rows are deliberately *absent from
the resting list* — the map is the region picker now — but still match when typed. Keyboard:
`↑↓` move, `enter` open, `esc` close.

## State

```js
{ open: 'w5',        // open window id, or null
  reg: null,         // drilled region id, or null — per-window, not global
  reach: '150',      // '45' | '90' | '150' | 'any'
  rate: '4',         // 'any' | '3' | '4'
  order: 'when',     // 'when' | 'best'  — rows only, never the strip
  origin: HOME,      // frame of reference: HOME (all area regions) or one region
  spot: null,        // open location sheet
  searching: false, q: '', sel: 0 }
```

Map tab: `{ view:'heat'|'med', win:0..5, dark:bool, area:bool }`.

Two scopes, one gesture: the **origin chip** sets the global frame; the **rail** filters one
window. When origin is already a single region the rail drops away — nothing left to choose.

Cards are the intersection of region × rating floor × travel time, and every window footer
names the three in force, so a short list reads as "I asked for this" rather than "the forecast
is bad".

---

## Data requirements

Per location: `id, name, lat, lng, regionId, driveMinutesFromBase, miles, localDriveMinutes,
scores[6], darkness`.

Per window: `id, dayLabel, dow, dayOfMonth, eventType, time, confidence, tide?, lead?`.

Per region: `id, name, aliases[], isHome, baseName, leadNarrative?`.

Also needed: **per-region-per-window narrative** (already `region.summary` in the existing
briefing payload) and **per-region-per-window delta against the previous run** — that last one
is new and is what the movement chips need.

Notes on the mock in `plan-data.js` you must replace, not port:
- Scores are generated from a regional base plus seeded jitter. Real scores come from the API.
- **Darkness** is a mock Bortle-style 1–5 from a regional base plus distance from the light
  dome, with `dark = bortle >= 3.8`. Replace with a real per-location value. An earlier version
  derived it from *region* alone and 92% of locations qualified, which made the filter a visible
  no-op — region is far too coarse a proxy, since Roker Pier and Kielder are both
  "Northumberland".
- Scatter locations (unnamed) exist only to make the field legible at mock scale. Real data has
  its own density.
- `SETUP = 20` minutes should become a user setting.

## Design tokens

All from `frontend/src/index.css` `@theme` — no new colours were invented.

| Purpose | Token | Hex |
| --- | --- | --- |
| Page | `plex-bg` | `#181210` |
| Surface | `plex-surface` | `#221A15` |
| Panel | — | `#1E1712` |
| Border | `plex-border` | `#3A2C23` |
| Border light | `plex-border-light` | `#4A3A2E` |
| Ink | `plex-text` | `#F2E7D3` |
| Ink 2 | `plex-text-secondary` | `rgba(242,231,211,.66)` |
| Ink 3 | `plex-text-muted` | `rgba(242,231,211,.42)` |
| Go | `verdict-go` | `#8AAE72` |
| Marginal | `verdict-marginal` | `#E0A542` |
| Stand down | `verdict-standdown` | `#C8452F` |
| Tide / links | — | `#6FA8B0` |
| Home / selected | — | `#C9A24B` |
| Sea (canvas) | — | `#13100e` |
| Land plate (canvas) | — | `#241d18` |

Type: `IBM Plex Sans` UI, `IBM Plex Mono` figures and labels, `Newsreader` narrative prose.
Radii: 5px canvases, 8px controls, 9px cards, 11–12px panels. Gaps: 3, 6, 7, 9, 14px.

`--mastH` is set at runtime from the masthead height so the lens can pin beneath it.

One CSS gotcha worth knowing: `.mapbox` carries `line-height:0` to kill the inline gap under the
canvas, and the absolutely-positioned region labels inside it inherit it. `.mlab span` therefore
sets `line-height:1.35` explicitly — without it the selected region's dark chip paints 6px tall
behind 9px of text and reads as a strike-through rather than a plate.

## Assets

None. No images, no icon files. Glyphs are text (`◎ ◍ ★ ▲ ▼ ↰ ◐ ↑ ↓ ≈`), matching v2's
text-glyph convention. Map geometry is `world-atlas` topojson; tiles are the existing CARTO
`dark_all`.

---

## Open questions for the team

1. **Plan/Map division of labour.** Built as time-first / space-first. The alternative worth
   considering is Map as the *catalogue* — what exists, browsable at 400+ scale, forecast-
   agnostic — which is the one job Plan structurally cannot do. Not a code change of any size,
   but decide it before building more onto either tab.
2. **Density on a Plan row.** Quality is now stated four times before you reach the rail:
   thumbnail verdict word, `best 5★`, `◐ 88%`, verdict pill. Each arrived for a good reason,
   which is exactly how medallion overload happened. Recommend cutting two.
3. **Should the field respect drive time?** Currently no, deliberately. Revisit only if it
   starts promising light you cannot reach.
4. **"Somewhere is good, just not near you."** The mock shows Thursday sunrise as Poor across
   the whole planning area but 3.9 on the west coast — the front that flattens Durham lights
   Glencoe. There is a real feature in surfacing that, but it is a new idea and was left alone.
5. **`GLANCE` as a setting** rather than a hard-coded 180 minutes.
