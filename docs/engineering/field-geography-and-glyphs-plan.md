# Field geography and topic glyphs — implementation plan

**Status: no phase started.** This line and the phase table below are the source of truth
between implementing sessions — update both in the same commit as each phase.

| phase | scope | state |
|---|---|---|
| G1 | `utils/labelPlacement.js` + `kmPerPx` (pure, tested) | not started |
| G2 | Plan thumbnails: home marker + area names | not started |
| G3 | Popup field: reach rings + home marker | not started |
| G4 | Coming up topic glyphs | not started |

**Source:** the design handoff is vendored verbatim at `docs/design/field-geography/` (from the
owner's `PhotoCast.zip`, received 2026-08-29). Do not edit the bundle; where its README disagrees
with this plan's §0 reconciliation, **this plan wins** — §0 records what the bundle does not know
about the real frontend. The plan is otherwise self-contained: an implementing session should be
able to work from this file alone and use the bundle for visual reference (`Plan Tab with Heat
v4.html` and `Coming Up.html` open directly in a browser; `Plan Thumbnail Geography.html` records
why this treatment won and is not for implementation).
**Design fidelity:** high — colours, type sizes, letter-spacing, marker dimensions and placement
rules below are final. The label placement is geometric and must be reimplemented as logic, not
eyeballed.

Two changes, both about making an existing surface legible rather than adding a feature:

1. **Geography on the Plan heat field.** The six window thumbnails paint a heat field over a real
   UK coastline, but nothing on them names a place. Every other reading on a card — drive time,
   `leave by`, the reach filter — is measured from one origin the picture never showed. Thumbnails
   gain a **home marker** and **area names at region centroids**; the drill-down popup field
   additionally gains **dashed reach rings** at the 45 min and 1h 30min tiers.
2. **Topic glyphs on Coming up.** Coming up names its topics with no glyph. It gains a per-family
   emoji glyph on timeline rows, standing-condition rows and filter chips — beside the existing
   colour swatch, never in place of it.

The work is four phases, each one PR, each following the repo's UI cadence (CLAUDE.md *UI Work —
Review Cadence*): **build → tests → adversarial review of the diff → fix what survives →
re-verify → commit.** Browser verification uses the local recipe:
`./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local` (port **8083**),
`npm run dev`, sign in `admin` / `golden2026`. A local DB with no evaluation run has no ratings, so
the rich states need the seed fixture (see the `heat-field-plan-handoff` memory note); state which
claims were seen in the browser and which were only tested.

---

## 0. Prototype → production reconciliation

The bundle's prototypes are vanilla-JS/DOM. These are the facts about the real frontend the
bundle does not know, established by exploration on 2026-08-29. **Do not re-derive these; verify
line numbers before editing (they will drift).**

| prototype concept | production reality |
|---|---|
| `HeatField.centroid(spots, rid, proj)` | Already exists: `utils/heatField.js` exports `centroid` (~:290), used by `WindowRowFieldMap`. **Do not fork or duplicate it.** |
| `HeatField.bbox/aspect/drawGeo` | Already exist (`utils/heatGeometry.js` + `utils/heatField.js`). `drawGeo` **returns the projection** — that is the anchor source. |
| the six thumbnails | `WindowFirstHeatStrip.jsx` — a day-column × sunrise/sunset **matrix**, six cards typical, not constant. Canvas already wrapped: `<span data-testid="wf-heat-well" className="wf-hc-cv"><canvas …/></span>`. `.wf-hc-cv` is `display:block; overflow:hidden; border-radius:5px` (`index.css` ~:1426) — the `overflow:hidden` is load-bearing (390px horizontal-scroll defence). |
| thumbnail width | measured **per card** off the well: `canvas.parentElement.clientWidth` (solo phone cards span the full row); the hook's shared width is only the jsdom fallback. No `-12` constant — the well is unpadded by design. |
| `S.origin` / `isHome()` | `origin` prop: `{id, name, baseName}` when planning away, **`null` when home** (`utils/planOrigin.js`). |
| `HOMEPT = [-1.573, 54.855]` | **Must not be a constant.** `App.jsx` holds `homeCoords` (`{lat, lon}`, null when no postcode saved) from user settings. It currently reaches only the Map pane — plumbing it to the Plan surfaces is new work (§2, §3). |
| resize → redraw, debounce 140 ms | `hooks/useHeatCanvas.js` already owns this: ResizeObserver via ref callback + leading-edge throttle `RESIZE_THROTTLE_MS = 170`. **Reuse it — do not add a debounce.** Labels re-place whenever the paint effect re-runs. |
| popup `placeLabels` + hint box | `WindowRowFieldMap.jsx` already has a greedy placer: `fits(box, placed, w, h)` with `BOX_GAP=3`/`EDGE_GAP=2` + a 24px centre-separation test for `target:true` boxes (WCAG 2.5.8); pass order hint-corner → not-scored corner → region labels (seeded) → chips (flip-then-drop); two-pass measurement at `left:-9999px; visibility:hidden`. `HINT_BOX = {width:118, height:24}` is already reserved bottom-left. The projection and anchors live in **one** state object (`frame`) so a click is never answered against a projection the labels weren't drawn from — keep that invariant. |
| reach tiers `40 km ⇢ '45 min'`, `80 km ⇢ '1h 30min'` | Tier labels are **derived, never authored**: `utils/reachLens.js` `REACH_TIERS` builds labels with `formatDriveDuration(limitMinutes)`. Ring labels must reuse `formatDriveDuration(45)` / `formatDriveDuration(90)` so the strings can never drift from the lens. The km radii (40, 80) stay authored design constants. |
| region names `SHORT`/`TINY` tables | Production regions come from the DB. Use an authored abbreviation map keyed by region name for the known roster, **fail-soft to the uppercased full name** for any region not in the map — the collision pass drops what cannot fit, which is the designed failure mode. |
| `&nbsp;` in names | Use `white-space: nowrap` in CSS (the prototype sets it too; the nbsp was belt-and-braces for a DOM it built by `innerHTML`). |
| per-window "hot" region (highest mean rating) | **Not derivable on the strip today and must not be recomputed from heat spots** — per-region means are server-owned (`BriefingRegion.meanRating`; CLAUDE.md backend-heavy §). Thread it through `utils/windowFirstCards.js` at card-build time (§2.3). |
| Coming up families `tide/sky/sun/dust/air/moon` | Production wire families: `coastal, aurora, air, night-sky, sun-moon, dust, eclipse` (tokens `--color-topic-*`, `index.css` ~:106). Chips: `all, coastal, night-sky, sun-moon, air-dust` (`utils/comingUpFeed.js`). Wire `entry.type` values: `spring-tide, king-tide, meteor, supermoon, equinox, solstice, nlc-season, eclipse`. Mapping in §4.1. |
| coincidence sub-lines | **No renderer exists** — `entry.coincidence`/`joinNote` are on the wire but deferred to Coming-up P3b (`WindowComingUpEntry.jsx` ~:38–47). §4.5 records the glyph spec for P3b; build nothing for it now. |
| `world-atlas` CDN | Production uses the committed `src/assets/uk-land-50m.json` via `load()`. Nothing to do. |
| styling | New pixel-precise rules go in **`frontend/src/index.css`** beside their `.wf-*` siblings, heavily commented, with `var(--font-mono)` — not Tailwind arbitrary classes, not inline styles (inline `style` only for computed values: coordinates, ramp colours, custom properties). |

---

## 1. Phase G1 — `utils/labelPlacement.js` (pure utility) + `kmPerPx`

The deliverable of the whole handoff is the placement algorithm. It lands first, alone, as pure
functions with exhaustive unit tests, so every consumer phase reuses one proven implementation.

### 1.1 `placeWithNudges(anchor, size, placed, frameW, frameH)`

Direct port of the prototype's `placeLabels` inner loop, reshaped as a pure function (no DOM):

- Inputs: `anchor = {x, y}` (centre point), `size = {w, h}` (measured element), `placed`
  (array of `{x, y, w, h}` boxes), frame dimensions.
- Try vertical nudges in exactly this order: `0, -13, 13, -24, 24, -36, 36` px.
- A candidate box is `{x: anchor.x - w/2, y: anchor.y - h/2 + dy, w, h}`.
- Reject a candidate that leaves the frame: `x < 1 || y < 1 || x + w > frameW - 1 || y + h > frameH - 1`.
- Reject a candidate that overlaps any placed box **inflated by 3 px horizontally and 2 px
  vertically**: overlap test `a.x < b.x + b.w + 3 && b.x < a.x + a.w + 3 && a.y < b.y + b.h + 2 && b.y < a.y + a.h + 2`.
- First accepting candidate wins; return its box (caller pushes it onto `placed`).
- **If no candidate fits, return `null` — the label is dropped.** Never stack, never shrink.
  A dropped name is better than an unreadable one.

Note this is the same *family* as `WindowRowFieldMap`'s `fits` but not the same function: `fits`
has no nudge ladder and carries the 24px target-separation test. Do not merge them — the popup's
`fits` keeps its invariants; the new util is for labels that may nudge. Export the nudge ladder
and paddings as named constants for boundary tests (the repo convention).

### 1.2 `kmPerPx(proj, refPoint)`

Add to `utils/heatField.js` beside `proj`/`centroid` (it is projection maths, and this is an
extension, not a fork):

```js
/* px per km at refPoint, measured off the projection rather than assumed, so a ring is real
   distance. 1° latitude ≈ 111.2 km. */
export function kmPerPx(proj, refPoint) {
  const a = proj(refPoint), b = proj([refPoint[0], refPoint[1] + 1]);
  return Math.abs(b[1] - a[1]) / 111.2;
}
```

(The prototype called it `kmPx` and hard-coded HOMEPT; production parameterises the reference
point.)

### 1.3 Tests (`src/test/labelPlacement.test.js`, extend `heatField.test.js`)

- Nudge order is tried exactly as specified (a collider at dy=0 lands the label at −13, etc.).
- First-fit wins; the returned box joins nothing (pure function — caller owns `placed`).
- Frame-edge rejection at all four edges, at the exact 1px inset.
- Inflation padding asymmetry: a box 3px away horizontally collides, 4px does not; 2px/3px
  vertically.
- Exhausted ladder returns null.
- `kmPerPx`: with a linear stub projection `([lng, lat]) => [lng * 10, lat * 10]`, one degree of
  latitude is 10px so `kmPerPx = 10 / 111.2`; assert to a tolerance.

---

## 2. Phase G2 — Plan thumbnails: home marker + area names

**Files:** `WindowFirstHeatStrip.jsx`, `utils/windowFirstCards.js` (hot region),
`WindowFirstShell.jsx` + `App.jsx` (homeCoords plumbing), `index.css`, tests.

### 2.1 Plumbing

`App.jsx` passes `homeCoords` (`{lat, lon} | null`) into `WindowFirstShell` (it already receives
`light`, `locations` etc.); the shell passes it to `WindowFirstHeatStrip` and into `openField`
(§3). Convert to the projection's `[lng, lat]` order at the point of use, never store both shapes.
PropTypes: `PropTypes.shape({ lat: PropTypes.number, lon: PropTypes.number })`.

### 2.2 Markup and CSS

The well already exists. Give it `position: relative; line-height: 0` and add the overlay as a
sibling of the canvas inside it:

```jsx
<span data-testid="wf-heat-well" className="wf-hc-cv">
  <canvas aria-hidden="true" … />
  {!geoFailed && <span className="wf-tlab" aria-hidden="true" data-testid="wf-thumb-labels">…</span>}
</span>
```

The whole overlay is `aria-hidden` — every name on it is decorative duplication of information the
card already states accessibly, and the existing popup labels (`.wf-mlab`) set the precedent.

`index.css` (next to the `.wf-hc-*` block, commented):

```css
.wf-tlab{position:absolute;inset:0;pointer-events:none}

/* home marker: dot + word, stacked */
.wf-hm{position:absolute;display:flex;flex-direction:column;align-items:center;gap:2px;line-height:1}
.wf-hm-mk{width:9px;height:9px;border-radius:50%;border:1.6px solid var(--color-home, #C9A24B);
  background:rgba(20,15,11,.6);
  box-shadow:0 0 0 2.5px rgba(20,15,11,.5), 0 0 9px rgba(201,162,75,.5)}
.wf-hm-lb{font-family:var(--font-mono);font-size:7.5px;font-weight:600;letter-spacing:.14em;
  color:#EBD9A8;text-shadow:0 1px 3px rgba(0,0,0,.95)}

/* area names at region centroids */
.wf-tlab-rg{position:absolute;font-family:var(--font-mono);font-size:8px;font-weight:600;
  letter-spacing:.09em;color:rgba(242,231,211,.62);line-height:1.2;white-space:nowrap;
  text-transform:uppercase;
  text-shadow:0 1px 3px rgba(0,0,0,.95), 0 0 8px rgba(0,0,0,.8)}
.wf-tlab-rg[data-hot="true"]{color:#F9F1E2}
```

Define `--color-home: #C9A24B` once in the `@theme`/token block beside the other `--color-*`
tokens, with a comment naming its consumers (home marker border, ring stroke at 40% alpha, §3).

### 2.3 The hot region (server-owned means, threaded, never recomputed)

The design brightens the region with the **highest mean rating for that window**. Per-region means
are server-owned (`BriefingRegion.meanRating`) and the strip does not receive them today. Add
`card.hotRegionName` in `utils/windowFirstCards.js` where each card is built from the served
briefing day/event: the argmax over that window's served region means (ties → first in served
order; nothing rated → `null`, and no label brightens). This is a *read* of served data — an
argmax like the popup's region rail's sort — not a recomputation, so it stays inside the licensed
client-derivation class. **Do not** compute means from the heat-spot catalogue.

### 2.4 Area-name text

New module-level tables in `WindowFirstHeatStrip.jsx` (exported for tests), keyed by **region
name** as served:

```js
const AREA_FULL = { 'Northumberland':'NORTHUMBERLAND', 'North Pennines':'NORTH PENNINES',
  'North York Moors':'NORTH YORK MOORS', 'Lake District':'LAKE DISTRICT',
  'Yorkshire Dales':'YORKSHIRE DALES', 'Borders':'BORDERS', 'Peak District':'PEAK DISTRICT' };
const AREA_TINY = { 'Northumberland':'NORTHUMB.', 'North Pennines':'PENNINES',
  'North York Moors':'N Y MOORS', 'Lake District':'LAKES', 'Yorkshire Dales':'DALES',
  'Borders':'BORDERS', 'Peak District':'PEAK' };
```

Verify the keys against the seeded roster's actual region names at implementation time (they come
from the DB; adjust keys, not the pattern). Fallback for an unmapped region: `name.toUpperCase()`
in both sets — CSS uppercases anyway; the map exists only for the authored tiny forms. Tiny set is
chosen per card when the drawn width `< 215px`.

Area names carry **no rating** — the card's spread histogram and the popup's region rail already
state it.

### 2.5 Anchors, state, and placement

Extend the existing per-card paint pass (the effect that calls `drawGeo` per canvas): after
`drawGeo` returns `proj`, compute for that card:

- `home`: `proj([lon→lng order])` — **only when `origin == null` and `homeCoords != null`**. An
  away origin frames a single region and home sits off-picture; no marker (and in §3, no rings).
- one anchor per scoped region: `centroid(framedSpots, rid, s => proj([s.lng, s.lat]))` (use
  whatever rid/name keying the existing `WindowRowFieldMap` centroid call uses — match it
  exactly), skipping null centroids;
- `tiny = cardWidth < 215`, `hot = card.hotRegionName`, plus `width`/`height`.

Store all of it in **one state object per card key** (a Map in one `useState`, replaced
wholesale per paint — the `WindowRowFieldMap` one-piece-of-state rule; anchors must never outlive
the projection that produced them). `drawGeo` returning null (geo failed / too small) clears that
card's entry.

Then a `useLayoutEffect` places labels per card using the two-pass measurement pattern already in
`WindowRowFieldMap` (render candidates at `left:-9999px; visibility:hidden`, read
`offsetWidth`/`offsetHeight`, commit survivors):

1. Priority order: **home first** (it always wins its space), then regions in scope order.
2. Each through `placeWithNudges` against the accumulating box list.
3. Null result → the label is not rendered this pass.

Placement re-runs whenever the anchors state changes — which the existing throttle/observer
machinery already drives; add nothing.

### 2.6 data-testids

`wf-thumb-labels` (overlay), `wf-thumb-home`, `wf-thumb-area` (+ `data-region`, `data-hot`).

### 2.7 Tests (`WindowFirstHeatStrip.test.jsx` + placement already covered by G1)

Follow the suite's existing canvas conventions, but this phase needs the `drawGeo` mock upgraded
from `null` to the linear stub projection (`([lng, lat]) => [lng*10, lat*10]`) **for the new
tests** so anchors are hand-checkable — keep existing tests on whichever stub they assert against.
Stub `offsetWidth`/`offsetHeight` on `HTMLElement.prototype` for the measurement pass (the dialog
suite's pattern).

- Home marker rendered at the projected point when `origin` is null and `homeCoords` set.
- No home marker when `origin` is an away region; none when `homeCoords` is null.
- One area label per scoped region with a computable centroid; text from the tiny table under a
  stubbed `clientWidth < 215`, full table above.
- Unmapped region name falls back to its uppercased self.
- `data-hot="true"` on exactly the card's `hotRegionName` label; no hot when null.
- Collision: two regions with coincident centroids → second label absent (dropped, not stacked).
- Overlay absent when `drawGeo` returns null (geoFailed path).
- Overlay is `aria-hidden`.
- `windowFirstCards`: `hotRegionName` argmax, tie-break, null when nothing rated.

---

## 3. Phase G3 — popup field: reach rings + home marker

**Files:** `WindowRowFieldMap.jsx`, `WindowSheetDialog.jsx`, `WindowFirstShell.jsx` (openField),
`index.css`, tests.

### 3.1 Plumbing and gating

`openField` gains `homeCoords`; `WindowSheetDialog` passes it to `WindowRowFieldMap` as
`homePoint` (convert to `[lng, lat]` once). Rings and marker render only when the popup is a
home-origin view (`origin == null` — the shell already forces `selectedRegion` null when away and
carries `origin` in `openField`) **and** `homePoint` is non-null.

Rings state the reach thresholds in real distance, deliberately **not** a filtered view of the
heat — the field still paints every rated location regardless of reach (filtering the field per
driver would make the same night look different to two people). They are gated on a saved
postcode, not on `reachMeasured`: they claim a distance from home, not that a drive-time filter
ran. (Recorded as a decision in §5; the six `reachMeasured` surfaces are untouched.)

### 3.2 Rings

An SVG layer rendered as the **first child** of the map box's overlay stack (before `.wf-mlab`
and `.wf-mchips` in DOM order, so it paints under every label — the stack is z-ordered by DOM
order):

```css
.wf-rings{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}
.wf-rings circle{fill:none;stroke:rgba(201,162,75,.4);stroke-width:1;stroke-dasharray:3 3}
```

Two circles centred on `proj(homePoint)`:

```js
const RING_TIERS = [[40, 45], [80, 90]];   // [km, tier minutes] — km are authored design constants
// label: formatDriveDuration(minutes) — the SAME string the reach lens shows for that tier
```

Radius in px: `km * kmPerPx(proj, homePoint)`. Skip a ring when `r < 18` (illegible) or
`r > Math.max(w, h) * 1.15` (entirely off-frame). If both skip, render no SVG element.

### 3.3 Ring labels and home marker, in the existing placement pass

The pass in `WindowRowFieldMap`'s layout effect changes order to:

1. hint corner box (unchanged) and not-scored corner box (unchanged);
2. **ring labels** — anchor at the ring's top (`x: hx, y: hy - r`), placed via G1's
   `placeWithNudges` against the shared box list;
3. **home marker** — anchored at `proj(homePoint)`, via `placeWithNudges`;
4. region labels — as today, **but now dropped if they collide with an already-placed box**
   (previously they were seeded first and could collide with nothing; the prototype's `drawBig`
   drops a region label that cannot fit, and home/rings outrank regions by design — "home is
   first, so it always wins its space"). No nudge ladder for region labels (prototype parity);
5. chips — unchanged (flip-then-drop, target separation, caps).

All new elements are `pointer-events: none` (the overlay already is; only `.wf-mchip` buttons opt
back in), so the field's nearest-centroid click is never intercepted. The centroid-click handler
itself is untouched.

```css
.wf-ringlb{position:absolute;font-family:var(--font-mono);font-size:8.5px;letter-spacing:.08em;
  color:rgba(235,217,168,.72);background:rgba(14,11,9,.74);padding:1px 4px;border-radius:3px}
/* popup home marker: same structure as the thumbnail's, one size up */
.wf-mlab .wf-hm-mk{width:12px;height:12px;border-width:2px}
.wf-mlab .wf-hm-lb{font-size:9px}
```

### 3.4 data-testids

`wf-row-map-rings`, `wf-row-map-ring` (+ `data-km`), `wf-row-map-ring-label`, `wf-row-map-home`.

### 3.5 Tests (`WindowRowFieldMap.test.jsx`, `WindowSheetDialog.test.jsx`)

The suite already stubs a linear projection, so every expected pixel is hand-checkable:

- Ring radii: with the linear stub, `kmPerPx = 10/111.2`; assert both circles' `r` to tolerance,
  centred on the projected home point.
- Skip rules at both boundaries: a frame small enough that `r < 18` drops the 40km ring; a frame
  small enough that `r > 1.15 × max(w,h)` drops the 80km ring; both skipped → no `wf-row-map-rings`
  element at all.
- Ring labels carry `formatDriveDuration(45)` / `formatDriveDuration(90)` output exactly (import
  the function in the test — never a literal, so the strings cannot drift from the lens).
- Ring labels and home join the shared box list: a chip whose anchor sits on a ring label is
  flipped or dropped (extend the existing collision fixtures).
- Home marker outranks a region label: a region centroid coincident with the home anchor loses
  its label (the behaviour change in §3.3 step 4, pinned deliberately).
- Away origin (`origin` set): no rings, no home marker, even with `homePoint` supplied.
- No `homePoint`: neither renders; the rest of the field is unchanged.
- The hint box still wins its corner against a ring label anchored there.
- SVG layer is the overlay stack's first child (DOM-order assertion).

---

## 4. Phase G4 — Coming up topic glyphs

**Files:** new `utils/comingUpGlyphs.js`, `WindowComingUpEntry.jsx`,
`WindowComingUpConditions.jsx`, `WindowFirstComingUp.jsx` (chips), `index.css`, tests.

### 4.1 The glyph module

```js
/* One glyph per topic family, beside (never instead of) the colour swatch: the swatch carries
   the topic colour system, the glyph carries recognition. Removing either loses something. */
export const FAMILY_GLYPHS = {
  coastal: '🌊', 'night-sky': '🌌', aurora: '🌌', 'sun-moon': '☀️',
  dust: '🏜️', air: '☁️', eclipse: '◐',
};
/* wire-type overrides within a family — the moon events read as moon, not sun */
const TYPE_GLYPHS = { supermoon: '🌙' };
export const entryGlyph = (entry) =>
  TYPE_GLYPHS[entry?.type] ?? FAMILY_GLYPHS[entry?.family] ?? null;
/* filter chips (utils/comingUpFeed.js chip ids); 'all' deliberately carries none */
export const CHIP_GLYPHS = { coastal: '🌊', 'night-sky': '🌌', 'sun-moon': '☀️', 'air-dust': '🏜️' };
```

Decisions recorded (§5): `aurora` shares 🌊-style family logic with `night-sky`'s 🌌 (the design's
single `sky` family covered both; aurora has no chip in v1); `eclipse` → `◐`, borrowed from
`HotTopicStrip`'s `ECLIPSE` glyph for cross-surface consistency (the design left eclipse to the
per-event override it never authored); the combined `air-dust` chip takes 🏜️ (the design's own
`Air & dust` chip used its `dust` glyph). A null glyph renders nothing — never a placeholder.

### 4.2 Insertion points (three now; the fourth is §4.5)

All glyph spans are `aria-hidden="true"` — an emoji in the accessible name is noise, and the
adjacent text already names the topic (production's swatches set the precedent). This deviates
from the prototype only in accessibility, not appearance.

| surface | change |
|---|---|
| timeline row title (`WindowComingUpEntry.jsx`, `.wf-cu-ttl`) | `<span className="wf-cu-gi" aria-hidden="true" data-testid="coming-up-glyph">{entryGlyph(entry)}</span>` as the first child, **before** `.wf-cu-nm`, followed by the file's mandatory `{' '}` separator (see the accessible-name comment in that file — every top-level section is separated by a bare text node). |
| standing-condition row (`WindowComingUpConditions.jsx`) | Wrap the existing swatch and the new glyph in one container: `<span className="wf-cond-fam"><span className="wf-cond-sw" … /><span className="wf-cu-gi wf-cu-gi-cond" aria-hidden="true" data-testid="condition-glyph">{FAMILY_GLYPHS[family]}</span></span>`. The wrapper exists because the row is a multi-column layout — swatch and glyph must occupy **one** cell together or the columns shift (verify the row's grid/flex reality and keep its column count unchanged). |
| filter chip (`WindowFirstComingUp.jsx`) | After the existing `.wf-cu-chip-dot`: `<span className="wf-cu-gi wf-cu-gi-chip" aria-hidden="true" data-testid="coming-up-chip-glyph">{CHIP_GLYPHS[chip.id]}</span>` — only when the map yields one (`all` has no dot and gets no glyph). |

### 4.3 CSS (`index.css`, in the `.wf-cu-*` block)

```css
.wf-cu-gi{font-size:12.5px;line-height:1;flex:none;align-self:center;filter:saturate(.9)}
.wf-cu-card-feat .wf-cu-gi{font-size:14px}
.wf-cu-gi-cond{font-size:12px;width:14px;text-align:center}
.wf-cu-gi-chip{font-size:11px;margin-right:1px}
.wf-cond-fam{display:flex;align-items:center;gap:7px}
```

(`.wf-cu-card-feat` is production's existing featured-card modifier — the design's `.feat`.)

### 4.4 Tests (`WindowComingUpEntry.test.jsx`, `WindowComingUpConditions.test.jsx`, `WindowFirstComingUp.test.jsx`, new `comingUpGlyphs.test.js`)

- `comingUpGlyphs.test.js`: every wire family yields a glyph; `supermoon` overrides its family;
  unknown family/type → null; **completeness pin**: the test holds its own literal copy of the
  family list (the `windowFirstTopics` scope-set pattern) so a new wire family fails the test
  rather than silently rendering glyph-less.
- Entry: glyph rendered before the title, correct per family; absent (no empty span) for an
  unknown family; `aria-hidden`; the accessible name of the card does **not** contain the emoji.
- Featured entry gets the 14px class by virtue of the existing `wf-cu-card-feat` (jsdom asserts
  class presence, not computed size).
- Conditions: `.wf-cond-fam` wraps swatch+glyph; each of the three conditions carries its family's
  glyph; column count of the row unchanged (assert the row's direct-child count if that is how the
  layout is pinned).
- Chips: coastal/night-sky/sun-moon/air-dust carry their glyphs; `all` carries none.

### 4.5 Deferred: coincidence sub-lines (hand to Coming-up P3b — build nothing now)

When P3b builds the coincidence renderer, each sub-line carries a small glyph after its swatch:
`<span className="wf-cu-gi wf-cu-gi-sm">` at `font-size:11px`, resolved per sub-line by topic
(the design's `glyphOf`: a line naming a moon → 🌙, a tide/water → 🌊, else none — prefer keying
off served type over the design's name-regex if the wire carries one per line). Add this note to
the P3b work item; leave a one-line comment at the deferred-renderer site in
`WindowComingUpEntry.jsx` pointing here.

---

## 5. Decisions this plan has already made — do not re-litigate

1. **Home is the user's saved geocode, never a constant.** No marker or rings without a saved
   postcode; no fallback point. (The bundle says so itself.)
2. **Rings are ungated by role** for now: they derive from the reader's own postcode plus fixed
   distance constants, not from per-user drive-time data, and the design deliberately decoupled
   them from the reach filter. CLAUDE.md's freemium note makes "the local radius" a Pro feature on
   the Map tab — if the owner wants the rings behind the same gate it is one condition at the §3.1
   gate; flag it in the PR description as an owner call, default open.
3. **Ring labels are the reach lens's own strings** (`formatDriveDuration`), never authored text.
4. **The hot region rides `windowFirstCards`**, from served region means — never recomputed from
   the heat catalogue (backend-heavy rule).
5. **Region labels on the popup become droppable** (only against home/ring boxes, which are placed
   first). Behaviour change from "seeded, never dropped", pinned by a test, matching the
   prototype's `drawBig` and its priority rationale.
6. **Glyph spans are `aria-hidden`** — production convention beats prototype silence on this.
7. **Eclipse → `◐`, aurora → `🌌`, air-dust chip → `🏜️`** — authored extensions where the design
   was silent, chosen for cross-surface consistency with `HotTopicStrip` and the design's own
   chip table.
8. **Coincidence-line glyphs are P3b's**, specified in §4.5.
9. **Rejected by the design bundle, worth not re-proposing:** best-place-per-region names on the
   thumbnail (four place names is a legend; the card's Best row already names one); fixed town
   landmarks (reads as a basemap the app does not have); rings on the thumbnails (too small; the
   small field's job is the shape of the night). No animation on any label — they appear with
   their field.

## 6. Design token appendix (final values)

| token | value | use |
|---|---|---|
| `--color-home` | `#C9A24B` | home marker border; ring stroke at 40% alpha |
| home label ink | `#EBD9A8` | HOME text; ring labels at 72% alpha |
| area name ink | `rgba(242,231,211,.62)` | region labels on thumbnails |
| area name ink, hot | `#F9F1E2` | the window's strongest region |
| marker halo | `rgba(20,15,11,.5)` | 2.5px spread ring behind the home dot |
| label shadow | `0 1px 3px rgba(0,0,0,.95)`, `0 0 8px rgba(0,0,0,.8)` | all labels on the field |
| mono | IBM Plex Mono (`var(--font-mono)`, 600 already bundled) | every label |
| label sizes | 7.5 / 8 / 8.5 / 9 px | HOME (thumb), area name, ring label, HOME (popup) |
| glyph sizes | 11 / 12 / 12.5 / 14 px | chip, condition, row, featured row |
| ring geometry | 40 km, 80 km; 1px dashed `3 3` | reach rings |
| ring skip rules | `r < 18px` or `r > max(w,h) × 1.15` | legibility / off-frame |
| nudge ladder | 0, ±13, ±24, ±36 px | collision resolution |
| collision padding | 3px x, 2px y | collision resolution |
| tiny-name threshold | drawn width `< 215px` | thumbnail label set |
