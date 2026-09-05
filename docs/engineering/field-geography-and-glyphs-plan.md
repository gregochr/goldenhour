# Field geography and topic glyphs — implementation plan

**Status: G1–G4 complete — the plan is finished.** This line and the phase table below are the
source of truth between implementing sessions — update both in the same commit as each phase.

| phase | scope | state |
|---|---|---|
| G1 | `utils/labelPlacement.js` + `kmPerPx` (pure, tested) | done — merged (#694, `bd1ebe29`) |
| G2 | Plan thumbnails: home marker + area names | done — merged (#698) |
| G3 | Popup field: reach rings + home marker | done — merged (#701, `768f8af3`) |
| G4 | Coming up topic glyphs | done — merged (#704, `1a6fd986`) |

**Source:** the design handoff is vendored verbatim at `docs/design/field-geography/` (from the
owner's `PhotoCast.zip`, received 2026-08-29). Do not edit the bundle; where its README disagrees
with this plan's §0 reconciliation, **this plan wins** — §0 records what the bundle does not know
about the real frontend. The plan is otherwise self-contained: an implementing session should be
able to work from this file alone and use the bundle for visual reference (`Plan Tab with Heat
v4.html` and `Coming Up.html` open in a browser **with network access** — they load d3/topojson
from CDNs and the coastline from world-atlas, so in a sandbox that blocks outbound they render a
blank map; that is the prototype's limitation, not a bug to debug. `Plan Thumbnail Geography.html`
records why this treatment won and is not for implementation).

**This plan was adversarially reviewed before landing** (six prosecutor lenses — codebase
accuracy, design fidelity, architecture conformance, implementability, test quality, future
hazards — 2026-08-29); the surviving findings are folded in below. Where a section carries a
worked stub scale or an unusually specific instruction, that is usually a review finding — do not
"simplify" it away.
**Design fidelity:** high — colours, type sizes, letter-spacing, marker dimensions and placement
rules below are final. The label placement is geometric and must be reimplemented as logic, not
eyeballed.

Two changes, both about making an existing surface legible rather than adding a feature:

1. **Geography on the Plan heat field.** The six window thumbnails paint a heat field over a real
   UK coastline, but nothing on them names a place. Every other reading on a card — drive time,
   `leave by`, the reach filter — is measured from one origin the picture never showed. Thumbnails
   gain a **home marker** and **area names at region centroids**; the drill-down popup field
   additionally gains **dashed reach rings** at two reach-distance tiers (25 mi / 50 mi, labelled
   by distance by default and by drive duration only once a real drive time is measured — §5.2).
2. **Topic glyphs on Coming up.** Coming up names its topics with no glyph. It gains a per-family
   emoji glyph on timeline rows, standing-condition rows and filter chips — where a colour swatch
   element exists (condition rows, chips), the glyph sits beside it, never in place of it; the
   timeline card has **no swatch element** (its colour is a `data-family` border accent), and
   there the glyph is the title's first child.

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
| reach tiers `40 km ⇢ '45 min'`, `80 km ⇢ '1h 30min'` | Tier labels are **derived, never authored**: `utils/reachLens.js` `REACH_TIERS` builds labels with `formatDriveDuration(limitMinutes)`. Ring labels must reuse `formatDriveDuration(45)` / `formatDriveDuration(90)` so the strings can never drift from the lens. The km radii (40, 80) stay authored design constants. ⚠️ **§5.2 later re-authored both halves of this row**: the radii are now 25 mi / 50 mi (40.2336 / 80.4672 km), and the ring label only reuses `formatDriveDuration` when `reachMeasured` is true — otherwise it is the authored miles string. This row is kept as the historical record of the G3 reconciliation; §3.2/§5.2 are the current behaviour. |
| region names `SHORT`/`TINY` tables | Production regions come from the DB. Use an authored abbreviation map keyed by region name for the known roster, **fail-soft to the uppercased full name** for any region not in the map — the collision pass drops what cannot fit, which is the designed failure mode. |
| `&nbsp;` in names | Use `white-space: nowrap` in CSS (the prototype sets it too; the nbsp was belt-and-braces for a DOM it built by `innerHTML`). |
| per-window "hot" region (highest mean rating) | The argmax **already exists**: `topRegion(es)` in `utils/windowFirstCards.js` (~:192), with a deliberate name tie-break and the `eligibleRegions` canopy filter. Reuse it (§2.3) — never a fresh argmax, never recomputed from heat spots. ⚠️ The strip's `cards` prop comes from `buildHeatStripCards` in **`utils/windowFirstStrip.js`**, a field-by-field whitelist fold — a field added in `windowFirstCards.js` alone is silently dropped before it reaches the strip. |
| Coming up families `tide/sky/sun/dust/air/moon` | Production wire families: `coastal, aurora, air, night-sky, sun-moon, dust, eclipse` (tokens `--color-topic-*`, `index.css` ~:106). Of these, the assembler currently *emits* only `coastal`/`night-sky`/`sun-moon`/`eclipse` — `air`/`dust`/`aurora` are legal-but-unreachable today, and the glyph map still covers them. Chips: `all, coastal, night-sky, sun-moon, air-dust` (`utils/comingUpFeed.js`). Wire `entry.type` values: `spring-tide, king-tide, meteor, supermoon, equinox, solstice, nlc-season, eclipse`. Mapping in §4.1. |
| coincidence sub-lines | The renderer is landing in **Coming-up P3b, in flight as PR #690** (adds `.wf-cu-coin-line` with a per-line `.wf-cu-coin-swatch`, no glyph). §4.5 makes the sub-line glyph a *conditional* G4 scope item: in scope if #690 has merged when G4 starts, pointer-comment-only if not. |
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

- **The nudge ladder is pinned by a walk, not by asserting the constant against itself**
  (`expect(NUDGES).toEqual([...])` proves nothing — the repo's own suites record that
  anti-pattern). Build fixtures blocking each successive prefix: a collider at dy=0 → label lands
  at −13; colliders at {0, −13} → +13; at {0, ±13} → −24; and so on through the whole ladder to
  the all-blocked → null case. `placed` takes arbitrary rects, so a blocking box can straddle two
  rungs while leaving the next clear.
- First-fit wins; purity: the input `placed` array is **not mutated** (assert it), and the
  returned box is the caller's to push.
- Frame-edge rejection at all four edges, both sides of the boundary: `x = 1` accepted, `x = 0`
  rejected (the inequality is strict `< 1`), same for y and the far edges.
- Inflation padding, both sides of each band edge, where "gap" means edge-to-edge distance:
  horizontally a **2px gap collides, a 3px gap is clear** (the test is strict `<`, so the 3px
  inflation excludes gaps *below* 3); vertically a **1px gap collides, a 2px gap is clear**. Do
  not "fix" a red test here by widening the inflation — the strict inequality is the prototype's
  and the popup `fits`' shared semantics.
- Exhausted ladder returns null.
- `kmPerPx`: with a linear stub projection `([lng, lat]) => [lng * 10, lat * 10]`, one degree of
  latitude is 10px so `kmPerPx = 10 / 111.2`; assert to a tolerance. A linear stub is blind to
  the refPoint parameterisation §1.2 exists for, so add one fixture with a latitude-dependent
  scale (e.g. `([lng, lat]) => [lng * 10, lat * lat]`) asserting two reference points give two
  different answers — this kills the mutant that ignores `refPoint`.

---

## 2. Phase G2 — Plan thumbnails: home marker + area names

**Files:** `WindowFirstHeatStrip.jsx`, `utils/windowFirstCards.js` (hot region),
**`utils/windowFirstStrip.js`** (the whitelist fold the field must survive — §2.3),
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
  <span className="wf-tlab" aria-hidden="true" data-testid="wf-heat-labels">…</span>
</span>
```

(No `!geoFailed` gate on the overlay — the existing gate already unmounts the whole well on that
path; an inner gate would be dead code.)

The whole overlay is `aria-hidden` — every name on it is decorative duplication of information the
card already states accessibly, and the existing popup labels (`.wf-mlab`) set the precedent.
(Note the `!geoFailed` gate already unmounts the entire **well** today — the overlay needs no gate
of its own for that path; see the two distinct absence mechanisms in §2.7.)

`index.css` (next to the `.wf-hc-*` block, commented):

```css
.wf-tlab{position:absolute;inset:0;pointer-events:none}

/* home marker: dot + word, stacked */
.wf-hm{position:absolute;display:flex;flex-direction:column;align-items:center;gap:2px;line-height:1}
.wf-hm-mk{width:9px;height:9px;border-radius:50%;border:1.6px solid var(--color-home);
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

Define `--color-home: #C9A24B` once, **in the `@theme static` block, not the plain `@theme`** —
a plain-block token whose only consumers are handwritten `var()` rules prunes to the empty string
(the `--color-plex-panel` incident recorded in `index.css`'s own comments and in the review-cadence
section of CLAUDE.md). Do **not** give the `var()` a same-value fallback: `var(--color-home,
#C9A24B)` would render identically with the token pruned, masking exactly the defect the block
choice prevents. Comment the token with its consumers (home marker border, ring stroke at 40%
alpha, §3) and note that `#C9A24B` deliberately aliases the value of `--color-topic-sun-moon` /
`--color-close-to-home` — same hex, distinct semantic channels, the repo's established practice.

Positioning convention for everything the overlay places: `placeWithNudges` returns a **top-left**
box; set `left`/`top` from it directly. Nothing in `.wf-tlab` inherits a centre-translate (unlike
the popup's `.wf-mlab span` — see the §3.3 layering note). Known and accepted: the well's
`overflow:hidden` (load-bearing, 390px scroll defence) will clip a label's text-shadow and the
marker's 9px glow at the frame edge — the 1px placement inset keeps the text itself inside; do
not "fix" the clip.

### 2.3 The hot region (reuse `topRegion`, and survive the strip fold)

The design brightens the region with the **highest mean rating for that window**. That argmax
**already exists**: `topRegion(es)` in `utils/windowFirstCards.js` (~:192) — it runs over
`eligibleRegions(es)` (the canopy filter, whose absence is a recorded production defect: an
all-woodland region rated 4.8 on a misty dawn must not brighten a sky-gated field) and ties break
on the region **name** via `localeCompare`, under a load-bearing comment ("Keep them identical,
or reconverge both on one helper") that keeps the thumbnail, the movement chip and the popup
rail's rank 1 naming the same region. So:

- `card.hotRegionName = topRegion(es)?.regionName ?? null` at the same site that builds
  `movement` — **never a fresh argmax, and never the prototype's served-order tiebreak** (that is
  the exact divergence the comment forbids). Under an away origin, follow the same
  `scopedRegion` re-pointing every sibling field takes; nothing rated → `null`, no label
  brightens (a deliberate deviation from the prototype's `reduce`, which seeds with the first
  region and would brighten it on an all-null window — recorded in §5.4).
- ⚠️ **The strip never sees `windowFirstCards` output directly.** Its `cards` prop comes from
  `buildHeatStripCards` in `utils/windowFirstStrip.js`, an explicit field-by-field whitelist fold
  — carry `hotRegionName: card?.hotRegionName ?? null` there too, and pin the fold with a
  `windowFirstStrip` test. The component tests hand fixture cards straight in and **cannot** see
  a dropped fold field; without the fold test a session can go fully green while `data-hot` never
  fires in production.

This is a *read* of served data, inside the licensed client-derivation class. **Do not** compute
means from the heat-spot catalogue.

### 2.4 Area-name text

> ⚠️ **AMENDED 2026-09-05 — this section's key scheme shipped and was wrong.** The tables below
> are the design bundle's `SHORT` map (`docs/design/field-geography/Plan Thumbnail Geography.html`
> line 108), which is keyed by opaque region **id** (`ntw`, `nymc`, `lakes`, …), re-keyed here by
> **display name**. That re-keying is where the defect entered, and the verification step this
> section prescribes two paragraphs down — "verify the keys against the seeded roster's actual
> region names at implementation time" — is the step that was skipped. Production serves
> `The Lake District`, `The Yorkshire Dales`, `North York Moors & Coast` and
> `Northumberland & Tyneside`; every key below misses all four, so every lookup fell to the tiny
> fallback and both Lakes and Dales rendered as the single word `THE` on phone-width cards. See
> §5 decision 12 for the fix and what it deliberately did not change. The tables and the fallback
> rule below are kept verbatim as the shipped design, not as current behaviour.

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

Verify the keys against the seeded roster's actual region names at implementation time — and note
that check is *local-only*: production regions are DB-managed via the Admin UI on a separate host,
and `RegionService.setName` makes renames routine (V137 exists to clean up after one), so the map
is deliberately non-authoritative and will drift. Three guards, all cheap:

- Fallback for an unmapped region: `name.toUpperCase()` in the **full** set. For the **tiny** set,
  a full-name-uppercase fallback is the wrong degrade — a long name at `< 215px` fails placement
  and the region silently loses its label entirely — so derive the tiny fallback instead: drop
  leading directionals (`NORTH/SOUTH/EAST/WEST`), keep the first remaining word, uppercase.
- A dev-mode warning when a scoped region misses the map
  (`import.meta.env.DEV && console.warn(...)`) so a rename or roster growth surfaces at the next
  local session rather than as an unlabeled blob.
- A comment on the table citing the rename precedent, so a later session knows the map is a
  styling nicety over a derivation rule, not a registry.

Tiny set is chosen per card when the **drawn width** — the well's `clientWidth`, the same
measurement the paint uses — is `< 215px` (strict; 215 exactly takes the full set).

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
2. Each through `placeWithNudges` against the accumulating box list. The home marker is placed as
   **one element** (dot + word), so a nudge moves the dot with it — up to ±36px off the exact
   geographic point, and vertical-only nudging means an anchor within half its width of the left
   or right frame edge fails every rung and drops. Both are the prototype's own behaviour
   (`labelThumb` places the whole `.hm` through `placeLabels`), accepted, not bugs to fix.
3. Null result → the label is not rendered this pass.

Placement re-runs whenever the anchors state changes — which the existing throttle/observer
machinery already drives; add nothing. One bookkeeping duty: the strip carries a **measured**
long-task figure in a doc comment near the paint pass (~:415–421); G2 adds a measurement pass and
a second commit to that work, so re-measure in the browser and update the figure in the same
commit — a stale measured claim is the citation-rot this repo documents. (Optional, not required:
label sizes are fixed monospace strings, so a `(text, class)`-keyed size cache would skip the
hidden-measure pass after the first paint.)

### 2.6 data-testids

`wf-heat-labels` (overlay), `wf-heat-home`, `wf-heat-area` (+ `data-region`, `data-hot`) — the
strip's existing testids are all `wf-heat-*`; stay in that namespace.

### 2.7 Tests (`WindowFirstHeatStrip.test.jsx` + placement already covered by G1)

Follow the suite's existing canvas conventions, with three mechanics the review established:

- **Stub upgrade without leakage.** The suite's module mock is `drawGeo: vi.fn(() => null)` and
  its `afterEach` is `vi.clearAllMocks()`, which clears *calls* but does **not** restore an
  implementation set by `mockImplementation` — and after this phase the strip *reads* `drawGeo`'s
  return, so a leaked stub silently changes sibling tests. Use `mockImplementationOnce` /
  `mockReturnValueOnce` per test (or reset to `() => null` in `afterEach` explicitly).
- **Fixture coordinates must project inside the frame.** Under the linear ×10 stub, real-UK
  coordinates (lat ~55, lng ~−1.7) land at (−17, 556) — outside any plausible frame, so
  `placeWithNudges` edge-rejects everything. Use synthetic coordinates chosen so the anchors land
  where the test needs them.
- **Element sizes for the measurement pass** come from `HTMLElement.prototype`
  `offsetWidth`/`offsetHeight` stubs; where a test needs two *different* sizes, use the suite's
  data-driven getter pattern (`withWellWidths`-style, keyed off the element) rather than a second
  uniform stub.

Tests:

- Home marker rendered at the projected point when `origin` is null and `homeCoords` set.
- No home marker when `origin` is an away region; none when `homeCoords` is null.
- **Home wins its space:** a region centroid coincident with the home anchor loses its label (or
  is nudged) while home stays — this pins the placement *order*, which nothing else does.
- One area label per scoped region with a computable centroid; tiny table at stubbed drawn width
  **214**, full table at **215** (the rule is strict `<`).
- Unmapped region name falls back per §2.4 (full: uppercased self; tiny: derived abbreviation).
- `data-hot="true"` on exactly the card's `hotRegionName` label; no hot when null.
- Collision drop: two regions with coincident centroids **and a stubbed label height ≥ 35px** →
  second label absent. The arithmetic matters: at the dialog suite's uniform 14px height, rung
  −24 clears (24 ≥ 14 + 2) and the second label is *nudged*, not dropped — so also keep a
  short-stub fixture asserting the nudge outcome (second label present at dy −24). Both are
  correct behaviours at different geometries; pin each.
- **Two absence mechanisms, two tests** (they are different code paths): `load()` rejection sets
  `geoFailed` and unmounts the whole well (existing behaviour — assert no well); `drawGeo`
  returning null leaves the well mounted and clears that card's anchors (assert the well present,
  zero labels inside the overlay).
- **Re-placement on change:** rerender with changed anchors (origin flip or a spots change) and
  assert label positions moved — the layout effect's dependency list is new code and a dropped
  dependency means stale labels over a repainted coastline.
- Overlay is `aria-hidden`.
- `windowFirstCards`: `hotRegionName` = `topRegion` reuse (tie on name, canopy filter inherited),
  null when nothing rated, away re-point. **`windowFirstStrip`: the fold carries the field** —
  this is the test that the component suite structurally cannot replace (§2.3).

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
driver would make the same night look different to two people). The *rings themselves* are gated
on a saved postcode only, not on `reachMeasured`: they claim a distance from home, not that a
drive-time filter ran. The *ring label*, since §5.2's decision, is gated on `reachMeasured` too —
see §3.2 and §5.2 for the resolved distance-vs-duration split. One consequence to have in writing
before the first user report, unchanged by that decision: even for a `reachMeasured` reader the
ring is straight-line km wearing a duration label while the lens tier is measured road minutes, so
a spot can sit inside the "45 min" ring yet outside the lens's 45-min tier (and vice versa) —
**designed behaviour, triage it as such, not as a bug**. §5.2 records this residual explicitly.

### 3.2 Rings

An SVG layer rendered as the first child of the new `.wf-mgeo` layer (§3.3), which itself sits
**after the canvas and before `.wf-mlab`/`.wf-mchips`** in DOM order — the overlay siblings are
z-ordered by DOM order, so the rings paint over the field but under every label. (The canvas is
the map box's true first child; "first child of the map box" would put the rings *under the
paint*, invisible.)

```css
.wf-rings{position:absolute;inset:0;width:100%;height:100%;pointer-events:none}
.wf-rings circle{fill:none;stroke:rgba(201,162,75,.4);stroke-width:1;stroke-dasharray:3 3}
```

Two circles centred on `proj(homePoint)`:

```js
const MI_TO_KM = 1.609344;   // 1 mi in km — exact, not an approximation
const RING_TIERS = [[25, 45], [50, 90]].map(([mi, minutes]) => ({ mi, km: mi * MI_TO_KM, minutes }));
// 25 mi / 50 mi ≈ 40.2336 / 80.4672 km — re-authored from km to miles at §5.2 (owner decision,
// 2026-08-30); visually indistinguishable circles from the original 40/80 km, since those km
// values were always authored design constants rather than a measurement of anything. The km
// radius is derived from the mile constant HERE, at the definition site, so the unit intent is
// explicit; kmPerPx and every projection calculation downstream stay in km, untouched.
//
// label: by default `${mi} mi` (a distance claim, true for every account); upgrades to
// formatDriveDuration(minutes) — the SAME string the reach lens shows for that tier — only when
// `reachMeasured` is true (§5.2).
```

Radius in px: `km * kmPerPx(proj, homePoint)`. Skip a ring when `r < 18` (illegible) or
`r > Math.max(w, h) * 1.15` (entirely off-frame). If both skip, render no SVG element.

### 3.3 Ring labels and home marker, in the existing placement pass

⚠️ **Layering: the new elements get their own overlay layer, NOT `.wf-mlab`.** `index.css` has a
universal `.wf-mlab span` rule (absolute positioning, `translate(-50%,-50%)`, its own dark plate,
9.5px type — the translate/line-height pair is documented as load-bearing there). Mounting the
home marker or ring labels inside it would centre-shift every `placeWithNudges` top-left box by
half its own size and put plates on the marker's inner spans. Render instead a sibling layer
`.wf-mgeo` (`position:absolute; inset:0; pointer-events:none`) holding the rings SVG, the ring
labels and the home marker — placed in DOM order **after the canvas, before `.wf-mlab` and
`.wf-mchips`**, so it paints under both label layers. Top-left positioning throughout, no
inherited transform.

The placement pass in `WindowRowFieldMap`'s layout effect changes order to:

1. hint corner box (unchanged) and not-scored corner box (unchanged);
2. **ring labels** — anchor at the ring's top (`x: hx, y: hy - r`), placed via G1's
   `placeWithNudges` against the shared box list;
3. **home marker** — anchored at `proj(homePoint)`, via `placeWithNudges`. Rings + home outrank
   region labels (they are placed earlier); note home is *not* first overall — the two corner
   reservations and the ring labels precede it, so a home point projecting into the hint corner
   is nudged (up to 36px) or dropped, and a home within half its width of a side edge drops
   (vertical-only nudges). All prototype-accepted; pin the popup home position in a test (§3.5).
4. region labels — **droppable, but only when `homePoint` produced boxes**: run them through the
   component's existing `fits` (they are not `target: true`, so the 24px separation test is inert
   for them; a dropped label's box is not seeded). With no `homePoint` the pass must stay
   byte-identical to today's never-dropped behaviour — that is what makes §3.5's "no homePoint →
   field unchanged" promise true, and it scopes the behaviour change to exactly what §5.5
   records. Making labels droppable also forces them onto the chips' **two-pass**
   measure-then-commit treatment (today they render at their centroid immediately); survivors
   ride the same single state object as the chips (`placed` grows a `labels` member — the
   one-piece-of-state invariant holds).
5. chips — unchanged (flip-then-drop, target separation, caps).

Mechanical traps the review caught: the component's box vocabulary is `{x, y, width, height}`
while G1's `placeWithNudges` contract is `{x, y, w, h}` — **normalise to one shape before
mixing** (unconverted, `b.w` is `undefined`, every comparison is false, and no collision is ever
detected); ring/home boxes never carry `target: true`. And the standing comment at the pass
(~:498, "region labels … are never dropped … the new layer yields to the old one") states the
**opposite** of this change — rewrite it in the same commit, and note the reversal beside §5.5.

All new elements are `pointer-events: none` (the overlay already is; only `.wf-mchip` buttons opt
back in), so the field's nearest-centroid click is never intercepted. The centroid-click handler
itself is untouched.

```css
.wf-mgeo{position:absolute;inset:0;pointer-events:none}
.wf-ringlb{position:absolute;font-family:var(--font-mono);font-size:8.5px;letter-spacing:.08em;
  color:rgba(235,217,168,.72);background:rgba(14,11,9,.74);padding:1px 4px;border-radius:3px;
  white-space:nowrap;line-height:1.35}
/* popup home marker: same structure as the thumbnail's, one size up */
.wf-mgeo .wf-hm-mk{width:12px;height:12px;border-width:2px}
.wf-mgeo .wf-hm-lb{font-size:9px}
```

### 3.4 data-testids

`wf-row-map-rings`, `wf-row-map-ring` (+ `data-mi` — re-authored from `data-km` at §5.2, since the
new km values are non-round decimals and 25/50 mi are the clean identifiers now), `wf-row-map-ring-label`,
`wf-row-map-home`.

### 3.5 Tests (`WindowRowFieldMap.test.jsx`, `WindowSheetDialog.test.jsx`)

⚠️ **The suite's default ×10 stub cannot exercise ring geometry.** Work the arithmetic before
writing anything: under `([lng, lat]) => [lng*10, lat*10]`, `kmPerPx = 10/111.2 ≈ 0.09 px/km`, so
r₂₅ᵐⁱ ≈ 3.6px and r₅₀ᵐⁱ ≈ 7.2px — **both under the 18px skip floor, so no ring ever renders** and
every positive ring assertion is unreachable. And the mocked projection ignores the frame, so
"shrink the frame until r < 18" is a false mechanism (that intuition is a browser fact about
`drawGeo`'s bbox fit, which the mock discards). Reach every case **by choosing the projection
scale per test** (`mockImplementationOnce` with `([lng, lat]) => [lng*S, lat*S]`), keeping the
geometry honest through the real `kmPerPx`:

⚠️ **These S values were reworked from the 40/80 km arithmetic, not copied from it** — 25 mi /
50 mi are 40.2336 / 80.4672 km (`MI_TO_KM = 1.609344`), close to but not identical to the old
round 40/80, so every boundary scale below moves too.

- **Visible rings:** S = 100 → `kmPerPx = 100/111.2 ≈ 0.899281`, r₂₅ᵐⁱ ≈ 36.18px, r₅₀ᵐⁱ ≈ 72.36px
  on a 600px frame (1.15 × 600 = 690, neither off-frame). Divide fixture lat/lng by 10 relative to
  the ×10 fixtures and every existing pixel expectation is preserved, so ring tests can share
  fixtures with label/chip tests. Assert both radii to tolerance, centred on the projected home
  point — **and assert the home marker's own committed position** (nothing else pins it).
- **Skip floor, both sides of the edge:** S = 30 → r₂₅ᵐⁱ ≈ 10.85 (skipped), r₅₀ᵐⁱ ≈ 21.71 (drawn).
  Band edge: `S = 18 × 111.2 / 40.2336 ≈ 49.74946313528991` puts r₂₅ᵐⁱ at exactly 18.00 (verified
  in double precision, not just algebraically) — drawn, the rule is strict `<`; S = 49.6 →
  ≈ 17.95, skipped. As with the old 50.04/49.9 pair, use `homePoint` latitude 0 (not 3) for the
  exact-boundary case — `kmPerPx` subtracts two projected points, and only at lat 0 does
  `1×S − 0×S` avoid the float error `4×S − 3×S` (lat 3) introduces; that error happens to land on
  the opposite side of 18.0 for the new constant than it did for the old one, so do not assume the
  old fixture's direction carries over unchecked.
- **Off-frame rule:** scale *plus* frame: S = 100 at width 60 (above the component's 57px paint
  floor; height ≈ 53) → 1.15 × 60 = 69 < r₅₀ᵐⁱ ≈ 72.36 (skipped) while r₂₅ᵐⁱ ≈ 36.18 stays drawn.
- **Both skipped → no rings element at all:** the default ×10 stub, unmodified.

Remaining tests:

- **Both label states, both files.** `WindowRowFieldMap.test.jsx` pins the ring label TEXT against
  its own `reachMeasured` prop directly: absent/`false` → `"25 mi"` / `"50 mi"` (via the local
  `formatMiles`, never a literal); `true` → `formatDriveDuration(45)` / `formatDriveDuration(90)`
  (imported, never a literal, so the strings cannot drift from the lens); undefined explicitly
  (prop omitted, not passed as `false`) → the miles labels too — §5.2's fail-soft direction.
  `WindowSheetDialog.test.jsx` pins the ONE thing only that file can be wrong about: that the
  dialog passes `card.reachMeasured` through unchanged rather than re-deriving it — same two label
  states, driven by `card({ reachMeasured: true/false })` rather than a raw prop.
- Ring labels and home join the shared box list: a chip whose anchor sits on a ring label is
  flipped or dropped (extend the existing collision fixtures).
- Home marker outranks a region label: a region centroid coincident with the home anchor loses
  its label (the behaviour change in §3.3 step 4, pinned deliberately — this also pins the pass
  *order*).
- Away origin (`origin` set): no rings, no home marker, even with `homePoint` supplied.
- No `homePoint`: neither renders, **and region labels keep today's never-dropped behaviour** —
  the §3.3 step-4 scoping made this "field unchanged" promise literal; pin it.
- The hint box still wins its corner against a ring label anchored there (fixture arithmetic:
  the ring's top `(hx, hy − r)` must land in the bottom-left, i.e. home projects below the
  frame's bottom edge with r reaching back in — choose S and the home fixture together).
- DOM order: canvas → `.wf-mgeo` (rings SVG first within it) → `.wf-mlab` → `.wf-mchips`; assert
  the relative order of the four siblings, **not** `mapbox.firstChild` (that is the canvas — an
  assertion pinning the SVG first would pin the rings under the paint).
- **Phone fixture:** the popup is where box space is scarcest on the phone (aspect band 0.5–0.95,
  chip cap 6), and rings + home are placed *before* chips — add a phone-aspect fixture with rings
  present asserting the surviving chip count, and put a 390px full-screen popup pass in G3's
  browser checklist.
- The pointer pass-through is a CSS claim jsdom cannot test (no hit-testing): assert `.wf-mgeo`
  carries no click handler, re-run the existing centroid-click boundary tests with rings + home
  in the fixture, and name the actual pass-through as a **browser-verified** claim in the PR.

---

## 4. Phase G4 — Coming up topic glyphs

**Files:** new `utils/comingUpGlyphs.js`, `WindowComingUpEntry.jsx`,
`WindowComingUpConditions.jsx`, `WindowFirstComingUp.jsx` (chips), `index.css`, tests.

### 4.1 The glyph module

```js
/* One glyph per topic family, beside (never instead of) the colour swatch where a swatch element
   exists — condition rows and chips; the timeline card's colour is a border accent, no swatch.
   The swatch carries the topic colour system, the glyph carries recognition. */
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
  unknown family/type → null. **Completeness pins, honestly scoped** (the review killed the
  claim that a literal copy catches new wire families — families arrive as served strings, so a
  copy of `FAMILY_GLYPHS`' own keys is circular and a brand-new served family fails nothing):
  (a) derive the client's family universe from **live exports** and assert glyph coverage over it
  — every family in `FILTER_CHIPS[].families` (`comingUpFeed.js`) ∪ the `comingUpConditions.js`
  family map's values ∪ `{aurora}` has a `FAMILY_GLYPHS` entry, and every non-`all`
  `FILTER_CHIPS` id has a `CHIP_GLYPHS` entry — so registering a family or chip anywhere
  client-side without a glyph fails; (b) keep one literal copy of the seven-token family list
  *naming its source* (the `--color-topic-*` token block) as mutation coverage for deletions and
  typos, the `windowFirstTopics` pattern done properly. A served family registered nowhere
  client-side renders glyph-less **and** swatch-less by design — state that in the test file.
- Entry: glyph rendered before the title, correct per family; absent (no empty span) for an
  unknown family; `aria-hidden`; the accessible name of the card does **not** contain the emoji.
- Featured entry gets the 14px class by virtue of the existing `wf-cu-card-feat` (jsdom asserts
  class presence, not computed size).
- Conditions: `.wf-cond-fam` wraps swatch+glyph; each of the three conditions carries its family's
  glyph; column count of the row unchanged (assert the row's direct-child count if that is how the
  layout is pinned).
- Chips: coastal/night-sky/sun-moon/air-dust carry their glyphs; `all` carries none.

### 4.5 Coincidence sub-lines — conditional G4 scope (P3b is in flight as PR #690)

Coming-up P3b — **open as PR #690 at the time this plan was reviewed** — builds the coincidence
renderer: `.wf-cu-coin-line` rows each carrying a `.wf-cu-coin-swatch` in the line's own family
colour, no glyph. When G4 starts, check whether #690 has merged:

- **Merged (the likely order):** the sub-line glyph is **in G4's scope** — a fourth insertion
  point: `<span className="wf-cu-gi wf-cu-gi-sm" aria-hidden="true">` at `font-size:11px`,
  directly **after** `.wf-cu-coin-swatch` in each line, resolved by the line's served family
  (prefer that over the design's name-regex `glyphOf`; fall back to the regex — moon → 🌙,
  tide/water → 🌊, else nothing — only if a line carries no family). Add the corresponding CSS
  (`.wf-cu-gi-sm{font-size:11px}`) and a test per line.
- **Not merged:** build nothing for it; leave a one-line comment at the renderer site in
  `WindowComingUpEntry.jsx` pointing here, and say so in the G4 PR description so the retrofit
  is scheduled rather than forgotten.

Either way, expect textual merge conflicts with #690 in `WindowComingUpEntry.jsx` and
`WindowFirstComingUp.jsx` — mechanical, but budget for them.

---

## 5. Decisions this plan has already made — do not re-litigate

1. **Home is the user's saved geocode, never a constant.** No marker or rings without a saved
   postcode; no fallback point. (The bundle says so itself.) The recovery route for a no-postcode
   account is the masthead's existing empty-state nudge to the postcode field — do not invent an
   on-field "add your postcode" affordance.
2. **DECIDED 2026-08-30 (owner call, restated on #701 — do not re-litigate).** The open question
   from G3 was whether a straight-line ring may carry a duration label ("45 min") for every
   account, including the LITE readers who see the same string greyed behind a Pro pill in the
   lens bar on the same screen. The owner's resolution, implemented in the ring-miles follow-up:
   - **Radii re-authored from km to miles**: 40/80 km → **25 mi / 50 mi** (40.2336 / 80.4672 km at
     `1 mi = 1.609344 km`) — visually indistinguishable circles, since the km values were always
     authored design constants rather than a measurement of anything. `kmPerPx` and every
     projection calculation stay in km; the km radius is derived from the mile constant at the
     `RING_TIERS` definition site so the unit intent is explicit.
   - **Labels default to the distance itself — "25 mi" / "50 mi" — and upgrade to the duration
     strings (`formatDriveDuration(45)` / `formatDriveDuration(90)`, imported, never literals)
     only when `reachMeasured` is true.** `reachMeasured` is the existing single producer
     (`card.reachMeasured`, from `BriefingWindow` — CLAUDE.md's reach-vocabulary rule), read
     as a plumbed prop and never re-derived from drive times or role; absent/undefined renders
     the miles labels (the fail-soft direction). Rationale: a distance ring stating a distance
     makes no drive-time claim, so it is honest for every account whether or not a drive time was
     ever measured; the duration label is a claim the reach-vocabulary rule reserves for a surface
     a real drive time actually gated, and unconditionally printing it here — as the G3 draft
     did — is exactly the "45 min hiding a longer drive" hazard `reachLens.js` itself warns
     against. Gating the *label* on `reachMeasured` resolves that without gating the *rings*
     themselves on anything (they still render for every saved postcode, per decision 1 above)
     and without touching role at all — a LITE reader with a measured drive time sees the
     duration string exactly like a PRO reader would; the axis that decides is measurement, not
     the pill.
   - **Residual, accepted with eyes open**: even for a `reachMeasured` reader, the ring is still a
     straight-line circle wearing a *label* built from the tier's road-minute figure — the ring
     itself never routes a road, so a spot can sit inside the "45 min" ring's circle yet outside
     the lens's actual 45-min tier (and vice versa), exactly as §3.1's own note already recorded
     before this decision. That divergence is unchanged by the miles re-authoring and is not what
     this decision resolves; it is designed behaviour, to be triaged as such rather than as a bug
     if a user ever reports it.
3. **SUPERSEDED by decision 2's `reachMeasured` gate, above — kept as history.** This line
   originally read "Ring labels are the reach lens's own strings (`formatDriveDuration`), never
   authored text," full stop. That is now true only once `reachMeasured` is true; the default
   label is the authored miles string (`formatMiles`, a deliberate, documented exception to §5.3
   — a distance claim is not the duration claim §5.3's rule exists to protect). When
   `reachMeasured` **is** true the duration half of this rule still holds exactly as before:
   `formatDriveDuration`, imported, never a literal.
4. **The hot region is `topRegion(es)?.regionName`** — the existing helper, its name tie-break
   and canopy filter inherited by construction; never a fresh argmax, never recomputed from the
   heat catalogue (backend-heavy rule). Unlike the prototype's seeded `reduce`, an unrated window
   brightens **nothing** — a deliberate deviation, pinned by test.
5. **Region labels on the popup become droppable — only when `homePoint` produced boxes** (rings
   and home are placed first and outrank them; with no homePoint the pass is byte-identical to
   today). Behaviour change from "seeded, never dropped", pinned by tests on both sides, matching
   the prototype's `drawBig`. The standing comment at the pass (~:498) records the old rule and
   must be rewritten in the same commit.
6. **Glyph spans are `aria-hidden`** — production convention beats prototype silence on this.
7. **Eclipse → `◐`, aurora → `🌌`, air-dust chip → `🏜️`** — authored extensions where the design
   was silent, chosen for cross-surface consistency with `HotTopicStrip` and the design's own
   chip table. (`HotTopicStrip` is slated for deletion in Coming-up P6 — the `◐` character is a
   literal here and outlives it; only this rationale sentence goes stale.)
8. **Coincidence-line glyphs are conditional G4 scope** (P3b's renderer is in flight as PR #690)
   — §4.5 has the branch.
9. **The prototype's served-`ic` per-event glyph override is not carried** — no `ic` field exists
   on the almanac wire; if one is ever added, prepend `entry?.ic ??` to `entryGlyph`'s chain.
10. **`ComingUpConditionOccurrence.label` (P3b's unrendered field) is out of G4's scope.** P3b's
    phase log leaves it "for whichever phase next touches the occurrence row"; G4 touches the
    condition row's *family cell*, not the occurrence row — decided here so G4's review doesn't
    charge the omission.
11. **Rejected by the design bundle, worth not re-proposing:** best-place-per-region names on the
    thumbnail (four place names is a legend; the card's Best row already names one); fixed town
    landmarks (reads as a basemap the app does not have); rings on the thumbnails (too small; the
    small field's job is the shape of the night). No animation on any label — they appear with
    their field.
12. **DECIDED 2026-09-05 — the area-name lookup normalises; the tables are not re-keyed.** §2.4's
    tables miss every region production actually serves (see that section's amendment note), so
    `areaLabel` now reduces a served name to the tables' own key form — drop a leading `the`, drop
    a trailing `& …`/`and …` conjunct — and looks up the served spelling first, the reduction
    second. Re-keying the tables to today's four spellings was the obvious alternative and is
    **rejected**: the tables are non-authoritative by this section's own reasoning, regions are
    DB-managed and renamed routinely, and that fix re-breaks on the next rename. Three parts of
    the shape are load-bearing and should not be "tidied":
    - **The reduction is a TINY-table key only.** Every `AREA_FULL` value is exactly its own key
      uppercased — the bundle's `SHORT` needed no full-width curation because its keys were opaque
      ids — so a plain-form lookup there can only ever return something *shorter* than the
      fallback, never something better. The first cut of this fix wired it to both tables and
      silently retitled `Northumberland & Tyneside` as `NORTHUMBERLAND` on full-width cards: half
      the name dropped where there was room, and a fresh disagreement with the Map tab, which
      renders the served name verbatim. Caught in adversarial review before merge.
    - **The tiny fallback's directional drop loops** and always leaves one word standing, so
      `North West Highlands` reaches `HIGHLANDS` rather than the meaningless `WEST`, and a region
      named only `North` keeps it.
    - **`plainName` trims before stripping the conjunct.** The other order lets a leading ` & …`
      match from index 0 and reduce the whole name to `''`, and an empty label is not a short one:
      the placement pass skips a zero-measured candidate, so the region loses its name — the very
      outcome §2.4's third guard exists to prevent, arriving through the guard itself.

    **Known and accepted, not oversights.** An abbreviation-headed rename (`N. York Moors & Coast`,
    the spelling every `docs/design/*/plan-data.js` uses) still yields the two-character `N.`,
    because the reduction reaches no table key and `N.` is not a directional — the fallback has no
    minimum-length floor. And the reductions are case-insensitive while the table match is
    byte-exact, so `the lake district` reaches `LAKE` rather than `LAKES`; exact region-name
    matching is the project rule (`utils/planOrigin.js`), so this is an inconsistency inside one
    function rather than a violation of it. Both are recorded here rather than fixed, to keep the
    change to the defect that was reported.
13. **Two live answers to "what do we call this region when there is no room", and D-11 must
    displace both.** The Map tab shortens by CSS — the served name, `max-width: 90px` with an
    ellipsis below `REGION_TINY_FRAME_WIDTH` (`utils/mapLabels.js`, which says in terms that it
    "only decides WHEN to shorten, not what to shorten it TO"). The Plan matrix curates *and*, as
    of decision 12, normalises. So a thumbnail labelled `LAKES` opens a popup whose own field
    prints `The Lake District` verbatim, on the same screen and one click apart. This is not new —
    before decision 12 the Plan answer was `THE` — but it is now worth stating, because
    `map-tab-v2-plan.md`'s **O-4** (curated `regions.short_name`, served) is a port of this logic
    and would otherwise be written against the Map tab's CSS-only answer alone. A served short name
    should retire the client table, the reduction and the CSS clamp together.

## 5b. Interaction with the Coming-up series (no ordering assumed)

The Coming-up train (P3b in flight as PR #690; P5, P6 planned) shares files with G4 and, at the
shell, with G2/G3. Nothing here assumes an order — expect and budget for merge conflicts rather
than trying to sequence around them:

- `WindowComingUpEntry.jsx`: G4's title glyph vs P3b's coincidence renderer + action switch
  (#690) vs P5's NEW-flag slot ("between the name and the kind tag" — G4's glyph goes *before*
  the name, so the two slots don't collide semantically, only textually).
- `WindowFirstComingUp.jsx`: G4's chip glyphs vs #690's shell/handoff edits vs P5's badge work.
- `WindowFirstShell.jsx`: G2/G3's homeCoords plumbing vs P6's layout work — mechanical.
- `CHANGELOG.md`: conflicts guaranteed regardless (every PR appends to `[Unreleased]`).

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
| ring geometry | 25 mi / 50 mi (40.2336 / 80.4672 km); 1px dashed `3 3` | reach rings — re-authored to miles at §5.2 |
| ring skip rules | `r < 18px` or `r > max(w,h) × 1.15` | legibility / off-frame |
| nudge ladder | 0, ±13, ±24, ±36 px | collision resolution |
| collision padding | 3px x, 2px y | collision resolution |
| tiny-name threshold | drawn width `< 215px` | thumbnail label set |
