# Handoff: Map tab (v2)

## Overview

The Map tab is the screen that answers **where** — given a forecast window, which parts of the region are worth driving to tonight, and which named locations inside them. This bundle redesigns it.

Four problems in the shipped tab, and what replaced them:

| Problem | Fix |
|---|---|
| Heat blobs appeared to sit in the sea | The field is clipped to a real coastline mask, and the kernel radius was reduced. |
| Two overlapping event/window controls (a date strip + Sunrise/Sunset/Astro/Aurora pills, plus a third in-map window select) | **One** chronological event list. |
| The filter block consumed ~380px above a ~500px map | Filters moved into a popover with an active count on its chip; the map now owns the whole frame with no page scroll. |
| The map had none of the Plan tab's map affordances | Location naming, region naming, home marker, reach rings, confidence haze, and drill-in were all ported across. |

Two follow-on decisions made during the work, both documented below: **no text search field on this tab**, and **a new basemap**.

## About the design files

The files in this bundle are **design references created in HTML** — a working prototype of the intended look and behaviour, not production code to lift. The Leaflet-plus-canvas prototype exists so the interaction, density rules and colour can be judged honestly at real scale on real geography.

The task is to **recreate this design inside the PhotoCast React app**, using its existing environment and patterns: `react-leaflet` (`MapContainer`, `TileLayer`, `useMap`, `useMapEvents`), the existing `components/MapView.jsx`, `components/BottomSheet.jsx`, `hooks/useIsMobile.js`, and the existing API modules (`api/auroraApi.js`, `api/astroApi.js`, `api/settingsApi.js`). Do not port the prototype's imperative DOM code as-is.

**One exception, and it matters:** `heat-field.js` is *not* a mock. It is the real, tuned field kernel — bucketed gaussian accumulation, coverage clamp, colour ramp, confidence haze — and it is already shared with the Plan tab. Port it as a module more or less verbatim (`utils/heatField.js`), because the whole point of it is that the Plan thumbnails and the Map tab cannot disagree about what a colour means. Rewriting it will cause them to drift.

## Fidelity

**High fidelity.** Colours, typography, spacing, density rules, zoom thresholds and copy are all final and intended to be matched. Every hex value and threshold in this document is the value used in the prototype.

Three things are explicitly **not** final and need real data or a real decision — flagged as `OPEN` below:

- `OPEN 1` — the astro and aurora scoring models.
- `OPEN 2` — subject tags (Woodland / Waterfall / Wildlife are derived in the prototype).
- `OPEN 3` — whether hillshaded terrain should be an option or the default basemap.

---

## Screens / views

There is one screen. It fills the frame below the masthead and tab bar and **does not scroll**. Everything else is an overlay on the map.

### Layout

```
┌─ masthead (existing component) ─────────────────────────────┐
│  PhotoCast / Field guide to light      UP · cog · Sign out  │
│  ▬▬▬▬ light rule (existing) ▬▬▬▬                             │
│  📍 Home · DH3 4NG  drive times from here    05:30 blue …   │
├─ tab bar (existing) ────────────────────────────────────────┤
│  ◉ Plan   Coming up   ◍ Map   [Operations]                  │
├─────────────────────────────────────────────────────────────┤
│ ┌ ‹ │ SUNSET Tonight 19:58 ▾ │ › ┐      ┌ ◎ Regions ▾ ┐    │
│                                          ┌ Heat │ Pins ┐    │
│                                          ┌ Filters (2) ▾    │
│                                                              │
│                 M A P   +   H E A T   F I E L D              │
│                                                              │
│ ┌ ▤ Legend ▾ ┐        ┌ counts ┐              ┌ + − ⌂ ┐     │
└─────────────────────────────────────────────────────────────┘
```

Map container: `position:relative; flex:1; min-height:0`. Three stacked absolute layers inside it:

| Layer | z-index | Contents |
|---|---|---|
| Leaflet panes | (Leaflet default) | basemap tiles |
| `canvas#heat` | 410 | heat field, coastline stroke, reach rings — `pointer-events:none` |
| `.selmk` | 415 | selection ring on the selected location |
| `div#labs` | 420 | all HTML labels — container `pointer-events:none`, children `auto` |
| `#cal` | 1350 | selection callout |
| `#tip` | 1400 | hover tooltip |
| `.bar`, `.zoomg`, `.foot`, `#lchip` | 1100 | overlay chrome |
| `.menu` | 1500 | open popovers (must sit above the callout) |

### Masthead change

The Plan tab's postcode **search field** becomes a **statement** on the Map tab: a pin glyph, `Home · DH3 4NG`, and the caption `drive times from here` (`--ink-3`, 9.5px mono, uppercase, `letter-spacing:.13em`). It is not interactive.

Rationale: on a map, panning *is* the search. A text field in the masthead invites you to type the name of a place you can already see, and the origin postcode is a Plan-tab concern (choosing where you are starting from). What the map needs instead is a jump list — see **Regions** below. The caption stays because every drive time and leave-by on the screen is measured from that postcode.

---

## Components

### 1. The window control — one control, all light types

Replaces the date strip, the event pills, and the in-map window select.

`EV` is a single chronological array. For each forecast day: the solar window(s), then that night's **Astro**, then **Aurora** *only if* the night's topics include an aurora flag. Night events sort after that day's sunset because that is when they happen.

> An always-present Aurora tab that is empty six nights in seven teaches users to ignore it. Aurora appears only when the forecast flags it.

Current pill (`#wnow`, min-height 36px, 40px on mobile):

- Kind chip — 8.5px mono, 700, `letter-spacing:.11em`, uppercase, `padding:3px 5px`, `radius:4px`, tinted per kind (below)
- Label — 12.5px sans, 600, `--ink`, `letter-spacing:-.01em` (`Tonight`, `Tomorrow`, `Tuesday night`)
- Time — 11.5px mono, `--ink-2`, tabular-nums
- `▾` — 9px, `--ink-3`

Kind colours:

| Kind | Text | Background | Ring |
|---|---|---|---|
| Sunrise (`am`) | `--dawn` `#8FA8C4` | `dawn @ 16%` | `dawn @ 30%` |
| Sunset (`pm`) | `#EE8064` | `--coral #E8593F @ 16%` | `coral @ 30%` |
| Astro / Aurora | `#A79FE4` | `--astro #8E86D6 @ 18%` | `astro @ 34%` |

Steppers `‹ ›` (`.step`, 32×32, 36×40 mobile) flank it and are disabled at the ends. `ArrowLeft` / `ArrowRight` do the same.

**Dropdown** (`#wmenu`, 334px, max-height `calc(100% - 88px)`, scrolls): grouped under day headings (`.wday`, 8.5px mono, 600, `.13em`, uppercase, `--ink-3`, top border). Each row is a 4-column grid — kind chip · label + time · `N★ best` with a ramp-coloured 8px swatch · topic icons. Active row: `background:rgba(201,162,75,.13)`, `border-left:2px solid --home`.

Stating each event's best achievable score in the menu is the point: choosing a window is then an informed act rather than a guess.

### 2. Regions jump list — search, without a text field

`◎ Regions ▾` opens `#jmenu` (300px, right-aligned on desktop; bottom sheet on mobile). One row per region, **sorted by nearest drive time**:

- Region name — 12.5px sans, 600
- Drive time to its nearest named location — 9.5px mono, `--ink-3`; suffixed `· beyond your area` when over the 3h glance threshold
- Best score this window — 11px mono, 600, with ramp swatch

Selecting a row fits the map to that region's bounds. **If the region lies outside "My area", it switches scope to Whole catalogue automatically** rather than refusing or showing nothing.

### 3. Heat / Pins segmented control

`Heat` (default) shows the field; `Pins` shows one dot per location and no field. Pins is retained deliberately as the honest comparison — at anything wider than a county, clusters average their own gems away, which is *why* heat is the default. In Pins mode the Legend chip hides.

Pins: named locations 26px with `N★` inside; unnamed 13px; fill = ramp colour; text `#1a130d` when luminance > 150 else `#fff`; drawn weakest-first so the best sit on top.

### 4. Filters popover

Chip `Filters (N) ▾`; `N` = count of active filters; active chip gets `--home` border and `rgba(201,162,75,.16)` background. Panel `#fpanel` 318px on desktop, bottom sheet on mobile. Rows, each `padding:8px 12px` with a `--border` bottom rule and an 8.5px mono uppercase key:

| Row | Control | Values |
|---|---|---|
| Minimum rating | segmented | Any · 2★+ · 3★+ · 4★+ · 5★ |
| Subject | chips (multi) | 🌊 Seascape · 🏔️ Landscape · 🌳 Woodland · 💦 Waterfall · 🐾 Wildlife |
| Drive from DH3 4NG | segmented | Any · 45 min · 1h 30 · 2h 30 |
| Sky | chip (toggle) | 🔭 Dark sky only |
| Scope | segmented | ◎ My area · Whole catalogue |

Footer: `<b>N</b> of M shown` and a `Clear all` link (`--tide`). Segment buttons min-height 32px, chips 30px.

`OPEN 2` — the prototype's catalogue carries `coast` and `lake` honestly; Woodland/Waterfall/Wildlife are derived from those plus a name regex plus a per-location jitter, purely so the chip row behaves realistically. In the port these must come off the location record. Filter logic is unchanged.

### 5. The heat field

Same kernel as the Plan tab thumbnails (`heat-field.js`, `drawTiles` host).

- Radius: `radiusFor(map, 7200, 30, 190)` — 7.2km in real distance, clamped to 30–190px. **Reduced from 8500m/240px**, which was part of the "heat is in the sea" problem.
- `grid:6`, `blur:4`
- `conf` = the event's forecast confidence: lower confidence desaturates and thins the field, so a day-4 guess cannot look as authoritative as tonight.
- Coverage clamp (`1 - exp(-Σw/1.15)`) keeps warmth only where rated locations actually are — empty ground stays empty instead of being coloured in by interpolation from thirty miles away.

**The land clip — the actual fix for point 1.** The kernel is a gaussian centred on each location, so a coastal spot spreads inland and out to sea equally. With no land mask, the visible half was the half over dark water, which reads as *the data is offshore*. The Plan thumbnails never had this because they clip to real coastline.

Implementation: build a `Path2D` of UK land **once per zoom level** in absolute Leaflet pixel coordinates, then slide it by the current pixel origin (`clipDx/clipDy = -pixelBounds.min`). Panning costs a `translate`, not a re-projection of the coastline. Invalidate the cache on zoom change and on container resize.

- Geometry: `world-atlas@2.0.2/countries-50m.json`, TopoJSON, filtered to country id `826`.
- **Grow the mask seaward by ~4km before using it** (`clipGrow`, implemented by stroking the
  same path with `lineWidth = grow*2`, round join/cap). Without this, clipping to a 1:50m
  coastline **erases coastal locations entirely** — measured: 7 of 51 at alpha 0, including
  Marsden Bay at 5★, every one of them coastal, zero inland affected. A rated 5★ painting as
  empty ground is worse than the bug the clip fixed. Do not instead union a disc per location:
  the error is geographic, so the disc grows with zoom and becomes visible circles offshore.
- **Apply it as a blurred alpha mask, not `ctx.clip()`** (`clipSoft`, ~4px). A hard clip puts a
  crisp edge through a blurred field, which reads as an artifact wherever the eye knows the
  line should not be sharp. Compose the mask (dilation stroke + fill) on its **own** surface
  and apply it in a single `destination-in`: with `destination-in` already set, successive
  draws *intersect* rather than union, so a stroke-then-fill leaves only the dilation band.
- Verify after any change: sample the heat canvas alpha at every location's own lat/lng and
  assert none is near zero. Ours: min 173 at the regional glance, 154 at county zoom.
- **The clip is dropped above zoom 11.5.** A 1:50m coastline is accurate enough at the scales where the field is the subject, but at street scale its error shows as a visibly false coast. By then the field is at its floor opacity, so an unclipped wash is less wrong than a hard edge in the wrong place.
- `OPEN` for production: swap in a higher-resolution UK coastline (Ordnance Survey or Natural Earth 1:10m) and the threshold can move up.

**Coastline stroke.** Stroked from the *same* `Path2D` the clip uses, so the two can never disagree: `rgba(242,231,211, a)`, `lineWidth:0.8`, where `a = clamp((11 - zoom)/1.6, 0, 1) * 0.5`. This exists because the new quieter basemap doesn't assert the coast strongly enough on its own at county scale.

**Field / label handover.** `FADE = {a:10.4, b:12.0}`, `FLOOR = 0.12`. Field opacity multiplier goes `1 → 0.12` across that zoom range. Past a county the question stops being *where* and becomes *which*, and a smear cannot answer *which* — but the field never disappears completely, because it is still the reason you are looking here.

**Reach rings.** Dashed `[3,4]`, `rgba(201,162,75,.42)`, at 36km and 72km from home (≈45 min and 1h 30 at 0.8 km/min). Shown below zoom 10.6, toggleable in the Legend. These are what turn "a warm blob" into "an hour north of me".

### 6. Labels — placement and density

All labels are absolutely-positioned HTML in `#labs`, placed by **one greedy pass in priority order**. A label that cannot find clear air is **dropped, never stacked** — an unreadable name is worse than a missing one.

Placement: for each candidate, try offsets `dy ∈ [0,-14,14,-26,26,-38,38] × dx ∈ [0, -w/2-9, +w/2+9]`; reject any box outside the frame or colliding (3px x-pad, 2px y-pad) with an already-placed box; first fit wins; no fit means the element is removed.

**The obstacle list is seeded with the overlay chrome** — the window bar, Regions/Heat/Pins/Filters bar, Legend chip, count footer, zoom group, the open callout and any open menu, each padded 5px. A name hidden under the window pill has been dropped anyway; it just took the pixels with it.

Priority order:

1. **Home marker** — 11px ring, 2px `--home` border, glow; caption `HOME` 8px mono 600 `.14em` `#EBD9A8`. Below zoom 13.
2. **Ring labels** — `45 min`, `1h 30`; 8.5px mono, `rgba(235,217,168,.7)` on `rgba(14,11,9,.72)`. These go through the same pass, so a ring label never stacks on a region name.
3. **Region names** (below zoom 11.2) — 9.5px mono, 600, `.09em`, `rgba(242,231,211,.6)`; the highest-average region gets `#F9F1E2`. Placed at the pixel centroid of that region's visible locations. Short names below 430px width (`NORTHUMB.`, `PENNINES`, `N Y MOORS`).
4. **Location chips** — the Plan tab's component: 5px ramp-coloured square · name (9.5px mono 600 `--ink`) · `N★` in ramp colour behind a 1px divider. Background `rgba(14,11,9,.84)`, `inset 0 0 0 1px --border-light`, radius 5px. Hover brightens and switches the ring to `--home`; selected gets a 1.5px `--home` ring.

**Density ramps with zoom over what is in view:**

```js
const inView = named.filter(s => bounds.contains([s.lat, s.lng]));
const budget = clamp(6 + (zoom - 8.6) * 11, 6, 60);
// always a candidate: the best location in each region
const shown = unique([...bestPerRegion, ...inView.slice(0, budget)]);
```

Candidates are sorted **best score first, then nearest**, so when space runs out it is always the weakest names that go. The best location in every region is always a candidate, so a named region always contains a named destination.

Measured result: ~11 names at the regional glance → the local set at county scale → everything in view past ~z11. An earlier build stepped straight from "one name per region" to "all of them", which left a hole in the middle: 13 named spots in view at county scale and only 2 labelled. Don't reintroduce a step function.

### 7. Selection — on the map, not in a popup

**This is a deliberate rejection of `leaflet-popup`.** A popup covers exactly the ground you just asked about. Instead:

- **Selection ring** (`.selmk`) — 34px, `0 0 0 1.5px rgba(201,162,75,.85)` plus a `0 0 0 7px rgba(201,162,75,.14)` halo, with a 7px `--home` dot at its centre.
- **Callout** (`#cal`) — 286px (266px mobile), `rgba(20,15,12,.965)`, 1px `--border-light`, radius 11px, `blur(7px)` backdrop, `0 22px 50px rgba(0,0,0,.72)`. An 11px rotated square tail points into the marker.

Anchoring is recomputed **every paint**, so the callout travels with its point through pan and zoom: prefer below the marker (22px gap), flip above when it would overflow, clamp horizontally to an 8px margin, and clamp the tail to stay within the card.

On open, `map.panInside(latlng, {padding:[70,150]})` — enough to bring both the point and the callout into view without recentring the whole map.

Callout contents, in order:

1. Name (13.5px, 700) + region · subject tags (9px mono, `--ink-3`), close `✕`
2. Verdict block — kind chip, `label · time`, and `N★ Worth it|Maybe|Poor`; border and background derived from the ramp colour at 50% / 10% alpha
3. Reason — 12.5px Newsreader, `--ink-2`, clamped to 3 lines
4. Facts row — `Drive` (`Xh Ymin · N mi`) · `Leave by` (event time − drive − setup) · `Dark sky` (Bortle, `· dark` when flagged)
5. Topic tags for this window, filtered to those that apply to this location (e.g. a tide topic only on a coastal spot)
6. **`This location, every window ▾`** — collapsed by default; expands to a 3-column grid of every event with this location's score, and selecting one switches the current window. This is how you compare Bamburgh across the week without leaving the map. Collapsed the card is ~250px; expanded ~427px, which is why it collapses on a phone.
7. Actions — `Zoom to it` · `Open in Plan`

### 8. Legend

`▤ Legend ▾` (bottom left, hidden on mobile) opens a 262px panel: the ramp bar (`#b03a2a → #C8452F → #E0A542 → #a9be78 → #8AAE72`) with `1★ poor / 3★ / 5★ go`; a handover indicator that reads `Field` → `Handing over` → `Locations` as you zoom; the reach-rings toggle; and the note *"Warmth only where rated locations are, clipped to land. Later windows render hazier — lower confidence."*

### 9. Count footer

`<b>N</b> named · M rated of K` plus `filtered` in `#EBD9A8` when filters are active. Second line: `Beyond 3h: Peak District · Highlands & Skye` when scope is My area, or `Whole catalogue — including regions you would not drive to tonight` when not.

---

## The basemap

**Re-decided after the scale change — read the ramp section above first.** The original
light-vs-dark comparison was argued on the RAG ramp, whose good end is a light green; that
argument does not survive the temperature scale. Dark still wins, but only with the bloom.
`Light or Dark Map.html` carries the measurements and a light option for comparison.

Changed after building a six-way comparison on identical data — see `Map Basemap Options.html`, which syncs six maps so they can be judged together at the glance, at county scale and at a headland.

**Chosen: Esri Dark Gray Canvas, warmed into the palette.**

It is **not label-free** — verify this before designing around it. The base tile still sets
country and water-body names (SCOTLAND, Solway Firth, Firth of Clyde). What it does not set is
**town** names, which is the class that was competing with our location chips. Materially
quieter than CARTO's town labels, but do not expect a clean base.

```
https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}
maxNativeZoom: 16   (service stops there; upscaling beats blank tiles)
attribution:  Tiles © Esri — Esri, DeLorme, HERE
filter:       saturate(.5) sepia(.32) brightness(.9) contrast(1.08)
```

Reference (place names) layer, added only at **zoom ≥ 11.8**, `opacity:.6`, `filter: saturate(.35) sepia(.3) brightness(1.02)`:

```
.../Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}
```

Reasoning:

- **Stay dark.** The field is a translucent warm ramp; on a light ground its low end — *poor* — nearly vanishes, which inverts the meaning. Poor should be quiet, not invisible. A dark ground also lets a warm blob read as *light* rather than as a shape.
- **Drop the basemap's town labels.** This was the biggest single legibility win, and the real problem — not the darkness. Morpeth and Ashington were competing with our location chips for the same pixels, and ours carry a rating theirs cannot. Country and water-body names remain in the tile; they sit at a scale that does not collide with location chips.
- **Bring names back late, under our control.** Past zoom 11.8 our own chips have thinned out and the village you are driving through becomes useful context. That is a layer we toggle, not something baked into the tile at every scale.
- **Warm the tiles.** The stock dark tiles are neutral blue-grey inside a warm brown UI, so the map read as an embedded third-party component. A CSS filter fixes it at zero tile cost.

**Provider note.** CARTO now serves its no-label styles with `API KEY REQUIRED` drawn into the tile, so the change with the clearest benefit isn't available there without a key. Esri serves the whole set clean — and the shipped app already attributes `Tiles © Esri`, so the prototype was the odd one out. Confirm Esri's terms and rate limits for production use.

`OPEN 3` — hillshaded terrain (`Elevation/World_Hillshade_Dark`, filter `saturate(.4) sepia(.22) brightness(.86)`) is the one genuinely open call. It shows aspect, valleys and the shape of the coast, which is arguably what a landscape photographer reads a map for. It costs contrast against the field and carries no roads, so it is a real trade. It's in the comparison file; decide with the product owner.

---

## Interactions & behaviour

| Trigger | Behaviour |
|---|---|
| Click location chip / pin | Open callout, `panInside` to fit it |
| Hover chip / pin (desktop) | Tooltip: name, event, `N★ verdict`, region · drive · Bortle |
| Click map background | Close menus; close callout |
| `←` / `→` | Previous / next event; closes menus |
| `Esc` | Close menus, then close callout |
| Chip click | Toggle its popover; opening one closes the others |
| Zoom | Field fades toward floor, labels re-place, reference layer appears at 11.8, land clip drops at 11.5 |
| Pan | Field repaints (rAF-throttled), labels re-place |
| Filter change | Immediate repaint; scope change refits bounds |
| Region jump | Fit to region bounds; auto-switch scope if outside area |
| `⌂` | Reset to My area and refit |

**Rendering discipline.** Paint through a single `requestAnimationFrame` guard on `move`/`zoom`/`resize`; paint immediately on `moveend`/`zoomend`. Never both.

**Sizing.** Do *not* fit bounds at mount. A `fitBounds` against a container that hasn't been laid out yet clamps to max zoom and no later event corrects it. Set an unconditional valid view first (`[54.9,-2.0]`, z8), then let a `ResizeObserver` on the map container be the **single** sizing trigger: the first callback with a non-zero size calls `invalidateSize`, clears the land-mask cache and fits the real frame; later callbacks keep it honest through layout changes. In React this belongs in one effect keyed to the container ref — not a chain of `setTimeout`s, and not `whenReady` plus a fallback timer.

### Responsive

The prototype demonstrates all three viewports in one document (Desktop / iPad / iPhone switcher).

| | Desktop ≥1024 | iPad 834 | iPhone 390 |
|---|---|---|---|
| Window control | top left | top left | top, full width, 40px targets |
| Regions / Heat-Pins / Filters | top right | top right | **bottom bar**, thumb-reachable |
| Filters panel | 318px popover | 318px popover | bottom sheet, `left/right:10px` |
| Regions menu | 300px popover | 300px popover | bottom sheet |
| Legend | bottom left | bottom left | hidden |
| Zoom buttons | bottom right | bottom right | hidden (pinch instead) |
| Region names | full | full | short forms |
| Callout | 286px | 286px | 266px, strip collapsed |
| Status bar | — | — | shown |

Existing `useIsMobile` and `BottomSheet` should carry the mobile variants.

---

## State

```js
{
  ei: 0,                  // index into the EV event list
  view: 'heat' | 'pins',
  area: true,             // My area vs Whole catalogue
  rate: 'any'|'2'|'3'|'4'|'5',
  reach: 'any'|'45'|'90'|'150',
  subj: Set<'sea'|'land'|'wood'|'fall'|'wild'>,
  dark: false,            // dark-sky only
  rings: true,            // reach rings
  spot: string|null,      // selected location name
  menu: 'win'|'jump'|'filt'|'leg'|null,
  strip: false            // callout's every-window strip expanded
}
```

Derived, memoised: `pool()` (filtered locations for the current event), `nFilters()`, `handover()` (zoom→opacity), the per-zoom land `Path2D`.

Data needed per location: `name, lat, lng, rid (region), r[] (per-window ratings), min (drive minutes), mi (miles), bortle, dark, named, coast, lake, tags[]`. Per event: `kind, day, time, confidence, lead/narrative, topics[]`.

`OPEN 1` — the prototype's night scores are my own plausible models, not the shipped ones:

```js
astro  = clamp(round(bortle*0.72 + clarity*0.5 - 1.0 + jitter), 1, 5)
aurora = clamp(round(1.4 + (lat-53)*1.1 + (bortle-2)*0.45 + (kp-4)*0.6 + jitter), 1, 5)
```

Aurora is latitude-led on purpose: at Kp 5 the southern edge of the band is the whole story, and a dark sky in Derbyshire cannot fix being too far south. Replace both with the real models from `astroApi` / `auroraApi`. **If the real astro score only exists for dark-sky locations, the event row should say so** rather than silently thinning the map.

---

## Design tokens

```css
--bg:#181210;  --surface:#221A15;  --surface-light:#2A2019;  --panel:#1E1712;
--border:#3A2C23;  --border-light:#4A3A2E;
--ink:#F2E7D3;  --ink-2:rgba(242,231,211,.66);  --ink-3:rgba(242,231,211,.42);
--go:#8AAE72;  --marginal:#E0A542;  --poor:#C8452F;
--tide:#6FA8B0;  --home:#C9A24B;  --coral:#E8593F;  --dawn:#8FA8C4;  --astro:#8E86D6;
--sans:'IBM Plex Sans';  --serif:'Newsreader';  --mono:'IBM Plex Mono';
```

Score ramp — the shipped **temperature** scale, cold to hot. Stops are deliberately **uneven**
and the hot leg **descends in luminance**; both properties are load-bearing (see
`design_temp_scale`). Do not even them out and do not lighten the top.

```
[[1,  [58, 92,112]], [2.2,[80,104,120]], [2.8,[146,140,128]], [3,  [196,148, 64]],
 [3.2,[201,146, 48]], [3.9,[223,107, 42]], [4.3,[222, 72, 38]], [5,  [200, 40, 32]]]
```

Sampled at whole stars: `#3A5C70 · #4C6677 · #C49440 · #DF6229 · #C82820`.
**Any fill that carries a label samples at whole stars; only label-free surfaces interpolate.**

### No ramp colour as text, anywhere

`design_temp_scale` Change 5a, and it is not optional. As a **fill**, the ramp colour has ink
placed on top of it and the ink is chosen per fill, so it passes. As **text**, the ramp colour
*is* the ink and cannot be fixed — and a lightness floor will not save it either, because the
ramp's mid-peaked luminance makes **1★ and 5★ fail from opposite ends**:

| ramp-as-text on the chip ground | 1★ | 2★ | 3★ | 4★ | 5★ |
|---|---|---|---|---|---|
| contrast ratio | **2.37** | **2.80** | 6.17 | 4.76 | **3.04** |

Note the shape: 5★ is the *least* readable numeral. It is the same inversion the bloom fixes in
the field, reproduced in a layer no bloom can reach.

So: the swatch or the badge fill carries the colour, and the numeral takes a fixed ink picked
per fill — `#FFFFFF` or `#0F172A` via `HeatField.ink(rgb)`. Same colours, compliant: 5★ on
white 5.56, 3★ on `#0F172A` 6.51. Measured across both deliverables, every star label now
sits at or above **5.56:1** (24 labels on the Plan tab, 15 on the Map tab, 0 failing).

### Panel text uses `--ink-2`; only map-overlay labels use `--ink-3`

The split is by **background**, not by importance:

- **Solid panels** — the callout, the drilldown, menus — use `--ink-2` for captions, day labels
  and chevrons. `--ink-3` measures ~3.5:1 on these grounds and fails AA. Both panels show the
  same content, so they must not differ: all 38 text nodes in the callout measure ≥ 5.56:1, the
  drilldown's small text 6.7–7.1:1.
- **Over map tiles** — `.rg2`, `.loc`, `.foot`, `.ringlb` — keep `--ink-3` with text-shadows.
  Recessive weight is deliberate there: those labels sit over variable imagery and must not
  compete with the field, which is the data.

Implement it by making the recessive token **opt-in inside the map frame**, so a panel cannot
inherit a failing value:

```css
#mapwrap { --ink-3: rgba(242,231,211,.66) }   /* = --ink-2: the default for anything in the frame */
.rg2, .ringlb { color: /* explicit recessive rgba + text-shadow */ }
```

Enumerating the panels that need fixing does not converge — `#cal, #drill`, then the four
`.menu` panels, then `#tip`; each pass missed the next one (`.dday i` sat at 3.54:1 beside a
6.72:1 label in the same row; the window picker's event times at 3.42:1). Defaulting the frame
to the passing value covers every panel including ones added later, and **recessiveness becomes
a declared exception** on the two bare overlay labels that genuinely want it.

The dividing line is *panel or not*, not *important or not*. `.foot` looks like an overlay label
but has its own background, so it follows the panel rule (7.15:1; its secondary line 5.26:1).
Only `.rg2` and `.ringlb` — bare text over tiles, with text-shadows, which must not compete
with the field — stay recessive.

Measured after the change: window picker 6.19:1 min, regions 7.09, filters 6.87, legend 7.09,
callout 5.56, drilldown 5.03. Zero failing across every panel in the frame.

Two verification notes, both learned the hard way:

- **Sweep every text node** in the panel, not a list of selectors — a selector list can only
  find what you already thought of. Ours: 38 nodes in the callout (min 5.56:1), 93 in the
  drilldown (min 5.03:1), zero failing.
- **Composite the text's own alpha** before measuring. A `.66`-alpha token measures as if it
  were opaque otherwise, and a failing row reads as passing — which is how the first pass
  reported 14:1 for rows that were actually at 6.7:1.
- Expand a drilldown row before sweeping: open rows composite against `--surface`, which is
  lighter than the closed row's `--panel`.

### The heat bloom (required on a dark ground)

The temperature ramp peaks in luminance at the gold 3★ (152) and its hot end is its darkest
colour (5★ = 73). On a dark basemap (#2A2724 = 39) that inverts the ordering: 5★ separates by
34, 3★ by 113 — the map is loudest where the night is average.

A blend mode does **not** fix this. `screen` and `lighter` are monotonic in source luminance,
so they lift the whole field and leave the ranking untouched; a dark red cannot outrank a light
gold under either. (Also: the field is drawn on an overlay canvas that is a DOM sibling of the
tile pane and is cleared each frame, so a canvas-level blend has nothing to composite against.)

The fix is a second emissive layer whose alpha follows the **score**:

```js
const g = opts.bloomFrom ?? 3;                       // gate — nothing below it blooms
const t = Math.pow(clamp((score - g)/(5 - g),0,1), 1.2);
bloom.rgb   = [255,138,66];                          // ember
bloom.alpha = t * coverage * (opts.bloomA ?? 190) * confidence;
```

Drawn after the main field, inside the same clip, with `globalCompositeOperation:'lighter'` and
a blur of `blur * (bloomBlur ?? 2.4)`. Multiplying by confidence keeps a day-4 guess from
glowing as hard as tonight.

**`bloomFrom` must stay at 3.0 on every surface.** 3★ is where the ramp's own luminance peaks;
any higher gate leaves a **dead band** between 3★ and the gate in which the ramp is already
darkening and the bloom contributes nothing — which reintroduces the exact inversion the bloom
exists to remove. Measured: a 3.7 gate on the thumbnails put 3★ and 5★ **0.9 luminance apart**,
i.e. indistinguishable, with 4★ *darker* than both. To stop a small surface washing out, cut
`bloomBlur` (which keeps the glow on the hot cores) — never raise the gate.

| Surface | bloomFrom | bloomA | bloomBlur |
|---|---|---|---|
| Map tab field | 3.0 | 190 | 2.4 |
| Plan tab popup map | 3.0 | 170 | 2.0 |
| Plan tab thumbnails | 3.0 | 155 | 0.9 |

**Measured composited luminance at the blob core** (single point per score, over each surface's
real ground; method: run `paint()` on a filled canvas and sample the centre pixel):

| Surface | 1★ | 2★ | 3★ | 3.5★ | 4★ | 4.5★ | 5★ |
|---|---|---|---|---|---|---|---|
| Thumbnail, **no bloom** | 55 | 60 | 83 | 78 | 69 | 58 | **50** |
| Thumbnail, bloom | 55 | 60 | 83 | 87 | 91 | 94 | **100** |
| Plan popup map | 54 | 59 | 82 | 88 | 93 | 96 | **105** |
| Map tab field | 60 | 65 | 88 | 95 | 101 | 107 | **117** |

The first row is the defect: without the bloom, brightness *falls* from 3★ to 5★ by 33. With it,
all three surfaces climb monotonically from 3★ up. **Re-measure this table if any bloom
parameter changes** — the ordering is the whole point, and it is not safe to tune by eye.

Check on the real screen that a cluster of adjacent 4★ locations does not accumulate into a
false 5★.

Verdict thresholds: `≥3.7 Worth it`, `≥2.8 Maybe`, else `Poor`.

Radii: 4 (kind chip) · 5 (location chip) · 6–8 (buttons, rows) · 9 (overlay pills) · 11 (menus, callout) · 999 (subject chips).
Type scale: 8 / 8.5 / 9 / 9.5 / 10 / 10.5 / 11.5 / 12.5 / 13.5px.
Min touch targets: 30px (chips) · 32px (segments) · 36px desktop / 40px mobile (overlay pills).
Zoom thresholds: 10.4 fade start · 10.6 rings off · 11.2 region names off · 11.5 land clip off · 11.8 reference labels on · 12.0 fade end · 13 home marker off.

---

## Assets

No image assets. The home pin is inline SVG; subject chips use emoji (as the shipped filter does); all other glyphs are Unicode (`‹ › ▾ ◎ ◍ ▤ ✕ ★ ⌂ + −`).

External: Leaflet 1.9.4, d3 7.9.0, topojson-client 3.1.0 (the app already has Leaflet and react-leaflet). `world-atlas@2.0.2/countries-50m.json` for the coastline — **bundle this rather than fetching from CDN in production**, and prefer a higher-resolution UK coastline if available.

Fonts: IBM Plex Sans, IBM Plex Mono, Newsreader — already in use.

---

## Files in this bundle

| File | What it is |
|---|---|
| `Map Tab v2.html` | **The deliverable.** Markup, all CSS, three viewports, plus design notes at the bottom explaining each decision and each wrong turn. |
| `map-tab-v2.js` | Behaviour: event list, filters, label placement, callout anchoring, coast mask, basemap layers. Heavily commented with the reasoning. |
| `heat-field.js` | **Port this nearly as-is.** The shared field kernel — ramp, bloom, soft mask, `ink()`. Used by both tabs; rewriting it makes them drift. |
| `plan-data.js` | Mock catalogue: regions, locations, windows, ratings, narratives, topics. Replace with real API data. |
| `Plan Tab with Heat v5.html` + `plan-tab-v5.js` | **Also changed this turn** — the bloom and the ink rule were applied to the Plan thumbnails and popup map so the two tabs keep one colour language. Not a redesign of the Plan tab; only those two changes. |
| `Light or Dark Map.html` + `ramp-basemap.js` | Why the map stays dark **and** why it needs the bloom, with the luminance measurements. Read before touching the basemap or the ramp. |
| `Map Basemap Options.html` + `basemap-options.js` | The six-way basemap comparison, synced. Keep for the terrain decision (`OPEN 3`). Note its field still uses the plain paint path, so it is a basemap reference only. |

Existing code to work with: `components/MapView.jsx` (current implementation), `components/MarkerPopupContent.jsx`, `components/ForecastTypeSelector.jsx` (superseded by the single window control), `components/BottomSheet.jsx`, `components/markerUtils.js`, `hooks/useIsMobile.js`, `api/auroraApi.js`, `api/astroApi.js`, `api/settingsApi.js`.

## Verify-before-you-ship checklist

These four are all measurable, and all four were got wrong at least once during the design.
Do not judge any of them by eye.

1. **Every rated location paints.** Sample the heat canvas alpha at each location's own lat/lng; none should be near zero. Coastal spots are the ones that fail.
2. **Brightness climbs with score.** Sample composited luminance at a blob core for 3★/4★/5★ on each surface; it must be monotone. Re-run after any bloom parameter change.
3. **No star numeral is ramp-coloured.** Grep for ramp colour assigned to `color:`; then measure contrast on every star label. Minimum 4.5:1, ours is 5.56:1.
4. **The callout never covers a control.** On the phone layout, open it at several locations, collapsed and expanded, and assert no overlap with the bottom bar.

## Suggested build order

1. Port `heat-field.js` to `utils/heatField.js` unchanged, and verify the Plan thumbnails still render identically. Do this first — it is the shared contract.
2. Swap the basemap and add the gated reference layer. Smallest change, most visible improvement.
3. Add the coast mask — dilated, soft, applied as an alpha mask. This is the fix for the original complaint, and check (1) above is what proves it did not overcorrect.
4. Build the event list and the single window control; delete the date strip and `ForecastTypeSelector` from this tab.
5. Move filters into the popover; give the map the full frame.
6. Add label placement and the density ramp.
7. Replace the Leaflet popup with the anchored callout.
8. Add the Regions jump list; remove the search field from the Map tab masthead.
9. Responsive passes for iPad and iPhone.
10. Resolve `OPEN 1`–`OPEN 3` with real data and a product decision.
