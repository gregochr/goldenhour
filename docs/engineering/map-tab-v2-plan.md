# Map tab v2 + the heat bloom — implementation plan

**Source**: the design handoff vendored at `docs/design/map-tab-v2/` (README, `Map Tab v2.html`,
`map-tab-v2.js`, `heat-field.js`, `plan-tab-v5.js`, the light/dark and six-way basemap comparisons,
`plan-data.js`). Read the bundle README before any phase — it is the spec; this document is the
*port* plan: what maps onto code that already exists, what is genuinely new, where the design and
the codebase disagree on purpose, and how the work cuts into single-session phases.

**Two deliverables, one bundle.** (1) A colour change shared by both tabs: the **heat bloom** (an
emissive layer that fixes the temperature ramp's luminance inversion on dark grounds) plus the
"no ramp colour as text" ink rule. (2) A redesign of the **Map tab**: one chronological window
control, filters in a popover, a land-clipped heat field, greedy-placed labels, an anchored callout
instead of a popup, a Regions jump list, and a full-frame map.

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (~6 prosecutor lenses + refutation agents, read-only) → fix survivors → browser
verification (backend `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local`, port
**8083**; `npm run dev`; `admin`/`golden2026`) → commit. Frontend gate before any push-request:
`npm run lint && npm test && npm audit --audit-level=high && npm run build`. Backend phases use the
no-Docker verify from CLAUDE.md. Never push; never tag. Every phase adds a
`changelog.d/YYYYMMDD-<slug>.md` entry (never a direct `CHANGELOG.md` edit — the convention
changed under this plan; see `changelog.d/README.md`). Paste
the relevant section of THIS plan (and the bundle README section) into any review agent's prompt —
review agents cannot see untracked context and a compliance lens with no spec returns zero findings.

**A fresh local DB has no ratings, so no field paints and none of the measured checks can run.**
The working seeding recipe is `docs/engineering/heat-field-plan.md` §7.3 +
`scripts/dev-seed-locations.sh`: seed `cached_evaluation` by SQL **then restart the backend**
(startup rehydration is the only DB read), and `frontend/.env.local` must carry
`VITE_API_TARGET=http://localhost:8083` or every request 502s — both traps have each cost sessions
before.

---

## §1 Corrections to the bundle — where the codebase has already moved

The prototype was built against an older mental model of the app. Every phase brief must know
these, or it will re-implement things that exist:

1. **The Esri dark basemap is already shipped.** `MapView.jsx:1937–1945` mounts
   `World_Dark_Gray_Base` + `World_Dark_Gray_Reference`, keyless, `maxZoom={16}`, with the Esri
   attribution. The bundle's "swap the basemap" phase collapses to: the warm CSS filter, gating the
   reference layer to zoom ≥ 11.8 (today it is **always on**), and the attribution restyle.
2. **The heat field is already the Map tab's default view.** `MapHeatLayer.jsx` paints
   `drawTiles` over the tiles (radius 8500 m / 34–240 px, grid 6, blur 4, opacity 0.9, fade band
   10.6 → 12.2, floor 0.17). The design *re-tunes* this (7200 m / 30–190 px, fade 10.4 → 12.0,
   floor 0.12) and adds the land clip and bloom — it does not introduce the field.
3. **An in-map window select already exists** (`wf-map-window`, `MapView.jsx:2185–2205`), fed by
   `heat.windows` — briefing-derived `{key, date, targetType, label, time, bestRating, conf}`.
   That is the seed of the design's single window control, not a competitor to delete.
4. **The ink rule is already enforced everywhere.** The adversarial sweep found **zero**
   ramp-as-text violations: every star numeral goes through `spotBadgeStyle`/`readableInkOn`
   (`utils/windowFirstSpots.js:103–107`, same #FFFFFF/#0F172A pair, same WCAG math as the bundle's
   `HeatField.ink`). `ScoreBar`'s numeral tint was already removed (heat-scale Stage 7). The
   bundle's four ink-rule diffs vs the matrix-axis vendored v5 are all already satisfied. **Port
   no `ink()` into `heatField.js`** — `readableInkOn` is the app's single implementation.
5. **The temperature stops match exactly.** Bundle `STOPS` ≡ `STOPS_TEMP`
   (`utils/scoreRamp.js:66–75`), all eight stops verified hex↔triple. No ramp change anywhere.
6. **A greedy label placer already exists**: `utils/labelPlacement.js` (`placeWithNudges`, nudge
   ladder `[0,−13,13,−24,24,−36,36]`, drop-don't-shrink), used by the Plan thumbnails and the
   popup field map. The map's placement pass extends this module; it does not add a second one.
7. **Woodland already exists.** `LocationType` = LANDSCAPE, WILDLIFE, SEASCAPE, WATERFALL,
   BLUEBELL, **WOODLAND** (`entity/LocationType.java`). The bundle's `OPEN 2` ("subject tags must
   come off the location record") is **already discharged on the backend**; `utils/locationTypes.js`
   models the set client-side. There is no lake flag and no need for one (the prototype's
   `lake → Woodland` derivation was jitter-mock, not spec).
8. **The v5 Plan-tab layout is already ported.** The bundle's `plan-tab-v5.js` differs from the
   matrix-axis vendored copy by exactly six hunks: two bloom call sites and four ink sites
   (verified by diff). The named rails / sticky day tiles are matrix-axis work, already merged.
9. **The map is TWO mounts of one component.** The Map *tab*
   (`WindowFirstMapPane` → `DateStrip` + `MapView` with the `heat` opt-in) and the Plan-tab
   *overlay* (`MapOverlay` → `MapView` with `overlayMode`, deliberately no `heat` — "the omission
   IS the mechanism", `App.jsx:527–537`). The redesign targets **the tab only**; §3 sets the
   strategy.
10. **`useHeatCanvas`, vendored UK topology, and the land loader exist.**
    `utils/heatField.js` `load()` dynamically imports `src/assets/uk-land-50m.json` (CSP allows no
    CDN; asset must stay in `src/assets/` — hashing + dynamic import + PWA CacheFirst). The coast
    mask reuses this, never the bundle's `d3.json` CDN fetch.
11. **Home, reach and drive-times are per-user contracts.** `GET /api/user/settings` (home
    coords), `/reach` (driveMinutes + straight-line distanceMiles, null = unknown), `/drive-times`;
    the shared region-base matrix is `GET /api/regions/drive-times`. None of this may ride a
    shared payload; "from home/origin" joins stay client-side (the licensed per-user class,
    CLAUDE.md "Backend-heavy"). The prototype's hardcoded `HOMEPT` maps to the settings read.
12. **The `reachMeasured` discipline extends to every new surface.** No surface may say "within
    reach", print a drive duration, or gate on distance unless a measured drive exists
    (`card.reachMeasured` is the single producer). New consumers in this plan: the callout facts
    row, the Regions jump list rows, ring labels.

## §2 Strategy — evolve the tab in place, freeze the overlay

**The tab evolves inside the existing mount; the Plan overlay does not change at all.** New UI
lands as new components under `frontend/src/components/map/` (WindowControl, FiltersPopover,
MapLabels, MapCallout, RegionsJump, MapLegendPanel, PinsLayer), mounted by `MapView` only when the
`heat` opt-in is present — the exact gating mechanism the tab already uses. Rationale:

- No parity cliff and no feature flag: every phase merges live, matching how every UI series here
  has shipped (plan-matrix M1–M5, coming-up P1–P7). The v1-retirement history is the argument
  against a parallel `MapTabV2` + flag.
- The overlay is the shared-component blast-radius case: gate every shared change behind a caller
  opt-in, and treat any overlay diff in any phase as a review finding.
- End state: `MapView` hosts two thin modes (tab = new chrome; overlay = markers + popup as
  today). A later owner decision (recorded in §6) may converge the overlay onto the callout; this
  plan does not.

**Data strategy: solar-first.** The briefing already serves the ordered ≤6 solar event list
(`DailyBriefingResponse.renderedEvents`), per-window roster best (`BriefingWindow.bestRating`),
confidence, badges/topics, and per-location per-window ratings + summaries ride
`GET /api/briefing/evaluate/scores` (the exact feed `LocationFourDaySheet` uses). Astro and aurora
nights have real gaps (no served night time, no confidence, no topics; aurora is PRO-only and
manually triggered — `POST /api/aurora/forecast/run` is ADMIN or PRO, with no scheduled producer)
— P5 closes the minimum (night times) and the EV list ships with honest night rows; the rest is
recorded in §6 rather than invented.

---

## §3 Phases

Sizes: S ≈ half a session, M ≈ one, L ≈ one heavy session. Dependencies noted; P3/P4 can run
before or after P5–P6. Every frontend phase inherits the cadence block at the top of this file.

### P1 — Kernel: bloom, soft mask, score callback (inert) — S/M

Port the bundle's kernel delta into `utils/heatField.js`, changing **no surface's rendering** (no
caller passes the new options yet).

- `field()`: optional emissive layer — when `opts.bloom`, build a second `ImageData`; gate
  `g = opts.bloomFrom ?? 3`, `t = pow(clamp((s0−g)/(5−g),0,1), 1.2)`, ember RGB **[255,138,66]**,
  alpha `t × cov × (opts.bloomA ?? 190) × conf` (confidence multiplies — a day-4 guess must not
  glow like tonight). Return shape gains `bloom` (and a `bloomImg` alongside `img` for tests).
  Carry the bundle's gate comment: **the gate stays at 3.0 on every surface** — a higher gate
  reintroduces the inversion; to tame a small surface cut `bloomBlur`, never raise the gate.
  (The bundle disagrees with itself on the dead-band measurement — its kernel comment says a 3.7
  gate put 3★ and 5★ **0.2** apart, its README says **0.9**. Carry the README's 0.9 and note the
  kernel comment's variance in the ported comment, so a reviewer doesn't flag the port as a
  misquote.)
- `paint()`: after the field draw, when `f.bloom`: `globalCompositeOperation='lighter'`,
  `filter = blur((opts.blur||3) × (opts.bloomBlur ?? 2.4) + f.unc*3)`, second `drawImage` —
  **inside the same save/clip**, before `restore()`. (Extracting the bundle's `_blit` split is
  optional; the soft-mask route below needs the shared draw, so extract it.)
- `paint()`: the soft-mask route — `opts.clipPath` (a `Path2D` in absolute pixel space) +
  `clipSoft` (blur px) + `clipGrow` (dilation px) + `clipDx/clipDy`. Render field+bloom to a temp
  surface; compose the mask on its **own** surface (blurred stroke-then-fill of the same path —
  with `destination-in` already set, successive draws intersect, which erases the land; the bundle
  comments record the trap); apply in a single `destination-in`; draw to the target. The hard
  `clipPath` translate route comes too. `drawGeo`'s hard clip and `hatchPlate` are untouched
  (hatched windows have empty point sets → `field()` returns null → no bloom; assert this).
- `drawTiles()`: `opts.score` callback (default `s => s.r[win]`) so a host can score night events
  not present in `r[]`.
- Preserve every repo deviation enumerated in the file header: ES modules, `scoreRamp` import
  (keep `rampRgb` module-state, do **not** port `opts.ramp`), `heatGeometry` split, `land()`/
  shared `loading` latch, vendored topology, the `fit()` realloc guard, `img` in the return,
  `kmPerPx`, NaN-safe ramp.
- **Tests** (`test/heatField.test.js`, stubbed-2d-context pattern — no canvas polyfill): bloom
  `ImageData` ember channels and score-gated alpha via the `cell()` idiom (0 below the gate at
  every coverage; rises with score; × conf); call-order extension of the `paint` suite (field
  draw → `lighter` → bloom draw → restore, and blur formula); soft-mask route order (temp surface,
  own-mask surface, single `destination-in`); `drawTiles` score-callback; hatch+bloom
  non-interaction; return-shape. Four stub facts an implementing session hits mid-test (the
  count was two until P1 was built — the last two were discovered by the build and its review):
  the recorder snapshots only `fillStyle`/`strokeStyle`/`lineWidth` per call — extend it with
  `globalCompositeOperation` and `filter` (today's filter assertions are final-state only);
  **jsdom 30 defines no `Path2D` at all**, so tests pass an opaque sentinel as `clipPath` and the
  kernel must only ever hand it to `ctx.fill/stroke/clip`, never construct or introspect it; the
  stub context needs a `.canvas` back-reference (the soft-mask route sizes its temp surface from
  `ctx.canvas.width`, and a real browser context always has one); and `translate` must join the
  recorder's intercepted-method list (both clip routes call it). Two more lessons from the build,
  recorded so later phases stop rediscovering them: the soft-mask suite MUST carry a case with
  the backing store at 2× the CSS size (every test at the degenerate sx = 1 leaves the whole
  device-px machinery — temp-surface sizing, both `setTransform`s, the identity reset, the final
  downscale — deletable with a green suite, and DPR 2 is the mainstream production case); and
  expect float-exactness traps in blur/transform assertions (`3 × 2.4 ≠ 7.2` in binary float;
  `-0` from a negated zero offset fails a naive `toEqual`).

### P2 — Bloom on: the whole "Plan screen colour change" — S/M

Flip the three surfaces, gated on the **temperature** mode:

| Surface | Call site | Options to add |
|---|---|---|
| Plan thumbnails | `WindowFirstHeatStrip.jsx:561–575` | `bloom:1, bloomFrom:3, bloomA:155, bloomBlur:0.9` |
| Plan popup field map | `WindowRowFieldMap.jsx:476–490` | `bloom:1, bloomFrom:3, bloomA:170, bloomBlur:2` |
| Map tab field | `MapHeatLayer.jsx:277–286` | `bloom:1` (kernel defaults are the README's 190/2.4) |

- **Bloom only when `getMode() === 'temp'`** (decision D-1, §5). The bloom exists to fix the
  temperature ramp's inversion (luminance peaks at gold 3★, 5★ is its darkest colour); the verdict
  ramp is brightest at its good end, has no inversion, and an ember glow over green would be a
  false signal. The strip and `MapHeatLayer` already carry `colourMode` as a repaint key — but
  **`WindowRowFieldMap` does not** (its paint deps have no mode key, and `WindowSheetDialog`
  passes none): thread a `colourMode` prop in and add it to the paint callback's dependency array,
  the strip's exact pattern, or this is the "module-global mode + memoised consumer" staleness the
  heat-scale series hit three times.
- **Browser verification is the acceptance test** and it is measured, not judged by eye
  (bundle README "Verify-before-you-ship" #2): sample composited luminance at a blob core for
  3★/3.5★/4★/4.5★/5★ on all three surfaces; **must climb monotonically from 3★ up**; record the
  table in the PR description next to the bundle's reference table (thumbnails 83→100, popup
  82→105, map 88→117). Also check a cluster of adjacent 4★ locations does not accumulate a false
  5★, and that verdict mode renders byte-identically to today. The measurement needs a seeded
  fixture (cadence block): five locations far enough apart to give separated blob cores at
  3/3.5/4/4.5/5★, plus one adjacent-4★ cluster for the false-5★ check.
- Component tests: assert the options object passed to `drawGeo`/`drawTiles` carries the bloom
  keys in temp mode and not in verdict mode (the existing `WindowFirstHeatStrip`/
  `WindowRowFieldMap`/`MapHeatLayer` test files stub the canvas; extend their option assertions).

### P3 — Basemap dress: warm filter, gated reference layer — S

`MapView.jsx` tab+overlay both benefit (pure tile styling; the overlay diff here is deliberate and
tiny — call it out in the PR):

- Warm the base: CSS class on the base `TileLayer` → `filter: saturate(.5) sepia(.32)
  brightness(.9) contrast(1.08)`; reference layer class → `saturate(.35) sepia(.3)
  brightness(1.02)`, `opacity: .6`. Tailwind-only rule: these land as classes in `index.css`
  beside the heat tokens, with a comment naming the bundle.
- **Gate the reference layer to zoom ≥ 11.8** (today it is always on — dropping the town labels at
  a glance-scale is "the biggest single legibility win"). Zoom-keyed mount using the existing
  `ZoomTracker`.
- `zoomSnap: 0` on the tab's `MapContainer` (fractional zoom makes every later threshold a
  gradient, not a step). Regression-check fitBounds framing, cluster behaviour, and the
  handover fade with fractional zooms. Overlay keeps its current snap (blast radius).
- Attribution restyle per the bundle (small, `!important`-free if possible).
- **maxZoom stays 16** — decision D-6 (§5): the repo's recorded rationale (nothing in the app
  zooms deeper; native tiles stop at 16) stands; the bundle's `maxNativeZoom:16 / maxZoom:19` is
  declined for now.

### P4 — The land clip, coastline stroke, and field re-tune — L

The fix for the founding complaint ("heat sits in the sea"), in `MapHeatLayer.jsx` + a small util:

- **Path2D-per-zoom land mask** (`utils/landMask.js`, new): stream the loaded UK FeatureCollection
  (`heatField.load()` — vendored topology, shared latch) through `map.project` at the current zoom
  into one `Path2D` in absolute pixel coordinates; cache keyed on zoom; slide by
  `clipDx/clipDy = −pixelBounds.min`. Invalidate on zoom change, container resize, and topology
  arrival. With `zoomSnap:0` the cache rebuilds per frame during pinch below the clip threshold —
  accepted (the bundle measured it acceptable); if profiling disagrees, quantise the cache key,
  never the visual zoom.
- Pass to `drawTiles`: `clipPath` when zoom < **11.5** (above it the 1:50m error shows; the field
  is at floor opacity by then), `clipSoft: 4`, `clipGrow: radiusFor(map, 4200, 3, 120)` (the ~4 km
  seaward dilation — without it a 1:50m clip **erased 7 of 51 coastal locations including a 5★**),
  `clipDx/clipDy`.
- **Coastline stroke from the same Path2D** (never a second geometry): `rgba(242,231,211,a)`,
  `lineWidth 0.8`, `a = clamp((11 − zoom)/1.6, 0, 1) × 0.5`. Drawn even when the field is at
  floor, and later in Pins mode too (P10).
- Re-tune the field constants: radius **7200 m / 30–190 px** (from 8500/34–240), fade band
  **10.4 → 12.0**, floor **0.12** (from 10.6→12.2/0.17). One commit, because radius and clip were
  co-tuned — the old radius is part of why the field swam offshore.
- **Verification #1 (measured, not by eye)**: sample the heat canvas alpha at every location's own
  lat/lng at the regional glance and at county zoom; **none near zero** (bundle's floor: min 173 /
  154). Script it in the browser console via the exposed canvas; paste the min into the PR. This
  is the check that proves the mask did not overcorrect. Re-run the P2 luminance table once —
  the clip must not dim cores.
- Unit tests: mask-cache invalidation matrix (zoom/resize/load), clip-threshold gating, option
  plumbing. **jsdom has no `Path2D` at all** — `landMask` tests stub the constructor by
  injection; the mask build itself is browser-verified.
- **Performance note from P1's review**: the kernel's soft-mask route allocates two fresh
  viewport-size canvases (the temp surface and the mask surface, each at the full device-pixel
  backing size) on **every clipped paint** — which, once this phase passes `clipPath`+`clipSoft`,
  is per-frame during pan/zoom below the clip threshold. Profile on the real map; if it shows,
  the fix is surface reuse across frames inside the kernel (a P4 kernel change with its own
  tests), not dropping the soft mask.

### P5 — Night events become servable (backend) — S/M

The minimum backend for honest astro/aurora rows in the EV list (gap analysis: **no served night
time exists anywhere**):

- `AstroConditionsDto` gains the night window it was scored over — `nightStart`/`nightEnd`,
  **mapped from the entity's stored `nauticalDuskUtc`/`nauticalDawnUtc`** (V64 columns, written by
  `evaluateAndPersist` at the fixed reference point — they have been persisted all along, so no
  migration and no serve-time computation). Serve the STORED instants: a recompute can diverge
  from the window the score was actually computed over after any solar-calculation change.
  Recompute via `solarService.nauticalDuskUtc`/`nauticalDawnUtc` only as an explicit fallback for
  legacy rows whose columns are null. (An earlier revision of this plan claimed the instants
  existed only on the write path — wrong, caught by cross-vendor review on #723; the entity has
  stored them since V64.) DTO + service + controller tests.
- `AuroraForecastResultDto`: serve the same two fields derived **per result date** via
  `AuroraForecastRunService.computeWindowForDate(date)` — the date-aware calculation the run was
  scored with. Never `AuroraPollingJob.calculateTonightWindow()`: it takes no date and reads the
  clock, so it would pin TONIGHT's window on a T+1 or historical row — the night-vs-date trap
  `docs/engineering/aurora-night-selection.md` records, and `computeWindowForDate`'s own javadoc
  warns against exactly this reuse. (Also a cross-vendor review catch on #723.)
- **Do not** invent night confidence or night topics (owner items, §6). The EV rows will render
  confidence via the client's existing capped-inference rule (`MAX_INFERRED_TIER` precedent —
  absent field ⇒ capped at medium, never high).
- Java verify per CLAUDE.md (no Docker; integration classes excluded locally; CI proves the rest).

### P6 — One window control — L

The design's centrepiece: **one chronological event list** replacing the date strip, the event-type
pills, and the in-map select — on the tab only.

- `utils/mapEvents.js` (new, pure, heavily tested): build `EV` from (a) briefing
  `renderedEvents`/days (solar: kind am/pm, label, time, confidence, bestRating, badges), (b)
  astro available-dates + conditions (night rows, time from P5, roster = bortle-enriched subset),
  (c) aurora available-dates/results (night rows **only when results exist for that night** —
  "an always-present Aurora tab that is empty six nights in seven teaches users to ignore it"),
  and (d) **the remaining forecast dates beyond the briefing's rendered horizon** (decision
  D-13): the briefing renders ≤6 events (~3 days) while the map's own domain is the forecast
  endpoint's T..T+5 — the pane's DateStrip javadoc defends the strip on exactly this ground, and
  the EV list must not silently shrink the browsable horizon. Later-day solar rows render
  unscored/dim (no roster best, field in its no-data state) rather than not existing.
  Night rows sort after their day's sunset. Per-event best: **solar rows read the served
  `bestRating` — never a client max** (client aggregation is not licensed; the served figure
  exists and a client max risks disagreeing with it). Only astro/aurora night rows, where no
  roster best is served, take a client max over served stars — a named member of the licensed
  aggregation class this plan's §4 #15 records (CLAUDE.md's Backend-heavy fifth class, added at
  P13). The swatch samples the ramp
  **at whole stars** of the labelled best (labelled-fill rule; a deliberate change from the
  prototype's interpolated pool-mean swatch — §4.13).
- `components/map/WindowControl.jsx`: pill (kind chip, label, time, ▾) + `‹ ›` steppers
  (disabled at ends) + the grouped dropdown (day headings, kind chip · label+time · `N★ best`
  with swatch · topic icons; active row `--home` tint). Kind colours per the bundle token table
  (dawn `#8FA8C4` / coral `#E8593F` / astro `#8E86D6` — join them to existing theme tokens where
  they exist rather than minting duplicates). 334px desktop; mobile treatment lands in P12.
- Keyboard: `←`/`→` step, `Esc` closes — **scoped to the map pane** (focus-within or pane-level
  key handling), never a document listener; the tab shares a page with dialogs and inputs.
- **The EV selection's owner is the pane** (a pane-local EV key), not App's `selectedDate`. Three
  consumers hang off the old date state and each is handled explicitly: App's `effectiveDate`
  guard rejects any date not in `allDates` (`App.jsx:235–237`) — so a night row whose date has no
  forecast rows **selects locally and does not forward**; `onSelectDate` is still forwarded
  whenever the selected row's date IS in `allDates`, keeping the Plan overlay's
  `date ?? effectiveDate` fallback coherent; the aurora auto-jump keeps its semantics through the
  same forwarding rule.
- Replace: `DateStrip` unmounts from `WindowFirstMapPane` (delete the component if orphaned);
  `ForecastTypeSelector` unmounts from the **tab** mount only (the overlay keeps it); the old
  `wf-map-window` select is absorbed. Rewire what those controls fed: astro mode = selecting an
  astro row (`drawTiles` with the P1 `score` callback over astro stars; bortle-only roster note
  when thin — the bundle's OPEN 1 caveat "if the real astro score only exists for dark-sky
  locations, the event row should say so". ⚠️ **The score callback has no exclusion seam** —
  P1's review established that a callback returning `null`/`undefined` for an unrated location
  yields a NaN weight that poisons every field cell that spot touches. FILTER the spot list to
  scored locations before calling `drawTiles`; never signal "unscored" through the callback's
  return value); aurora mode = selecting an aurora row (stored results,
  viewline overlay gates move from `date === auroraNight` to "the selected EV row is that
  night's aurora row"; keep the auto-jump latch semantics). **LITE**: aurora rows are ABSENT,
  not greyed — the freemium greyed-row treatment is unimplementable today, because
  `AuroraForecastController` is role-gated at class level and `getAuroraForecastAvailableDates()`
  folds the 403 to `[]`, so a LITE client cannot even learn an aurora night exists to grey out.
  (Caught by cross-vendor review on #723 — an earlier revision promised the greyed row.)
  Rendering it needs LITE-safe presence metadata first, which is O-9's second half; P6 does not
  block on that decision.
- Tests: `mapEvents` unit suite (ordering incl. night-after-sunset, aurora presence rule,
  beyond-briefing solar rows, best/swatch stats, empty briefing, LITE shape); control interaction
  tests; the existing `MapViewAstro`/`MapViewAuroraNight`/`MapViewSunsetToggle`/
  `MapViewDarkSkyHandoff` suites are rewritten onto the new control — each rewritten test must
  state which old pin it replaces.
- **Split point if this runs long**: P6a = `mapEvents` + `WindowControl` built and mounted beside
  the old controls (inert or additive); P6b = the three-control replacement + mode rewiring +
  suite rewrite.

### P7 — Filters popover, full-frame map, counts footer — M/L

- `components/map/FiltersPopover.jsx`: chip `Filters (N) ▾` (N = active count, scope not
  counted; active chip `--home` border + `rgba(201,162,75,.16)`), panel 318px. Rows port the
  existing drawer's contents — min rating (keep the persisted `mapFilterMinStars` default),
  subject chips (repo's six types incl. Pro-gated seasonal BLUEBELL, canopy exclusion rules
  unchanged), drive-from-origin segmented (reach-measured only), 🔭 dark-sky toggle
  (`DARK_SKY_THRESHOLD` single source), scope segmented (My area / Whole catalogue). **A scope
  change refits bounds with `animate:false`** — the bundle's recorded Leaflet-strand trap lives
  on exactly this control (a heavy field paint in the same frame as an animated fit strands
  Leaflet at the old view). Footer `N of M shown` + `Clear all` (clears everything but scope).
  Admin stand-down/unknown toggles ride along in an admin-only row (decision D-8). Popover
  semantics: opening one popover closes the others, and a click on the map background closes
  whatever is open — both tested. **The inbound handoff channels re-target the popover**: the
  Plan/Coming-up `handoffFilterAction`/`handoffDarkSky` effects today write the old drawer's
  state and call `setAdvancedOpen(true)` (`MapView.jsx:967–1010`) — they now write the popover's
  state and open it.
- **Full frame** — a change with **four separate owners, two outside the shell**; enumerate them
  or this phase is understated: (1) App's `<main className="px-4 py-6">` padding — and App cannot
  currently know the active tab (`effectiveTab` is shell-internal), so either a new `onTabChange`
  callback from the shell or an in-shell width restructure; (2) the shell root's inline
  `WRAP_MAX_WIDTH = 1080px` with the **masthead rendered inside it** — releasing it per-tab
  changes the masthead's width on tab switch, which needs an explicit decision (recommend: the
  wrap stays on the masthead + tab bar, only the panel region releases). ⚠️ **Superseded — the
  panel region's release is reversed, 2026-09-03.** Bundle rev 2 argued the opposite of this
  recommendation; §4 item 29 records the reversal (O-17) and its rationale. (3) the `wf-body`
  padding; (4) `MapView`'s `MAP_HEIGHT_PX = 500` tab constant. "No page scroll" needs a viewport
  height chain (`100dvh` minus measured masthead + tab bar — the sticky `--wf-mast-h` machinery
  is the precedent) that nothing currently provides. The pane becomes a flex column
  (`flex:1; min-height:0`). The never-unmount/hidden-pane lifecycle and `invalidateSize`
  discipline stay exactly as documented (`WindowFirstMapPane.jsx:183–203` — the zero-box skip is
  load-bearing). Overlay chrome moves onto the map: window control top-left; Regions/Heat-Pins/
  Filters top-right; legend chip bottom-left; counts footer bottom-centre; zoom + ⌂ bottom-right.
  Adopt the bundle's z-ladder for the new chrome (heat 410 / selection ring 415 / labels 420 /
  chrome 1100 / callout 1350 / tooltip 1400 / menus 1500 — menus must beat the callout); P12
  asserts the relation.
- **Split point if this runs long**: P7a = full frame (the four owners); P7b = popover + footer +
  re-homed chips.
- Counts footer: `N named · M rated of K` + `filtered` flag; second line "Beyond 3h: …" (My area)
  or the whole-catalogue sentence. The 3h glance threshold joins `planningArea`'s existing
  constant — one source.
- The colour-scale one-time notice, LITE viewline upsell chip, and scored-locations chip survive
  and are re-homed in the new chrome (they also seed P8's obstacle list).
- Tests: popover count/clear/scope semantics; full-frame layout (jsdom class assertions + the
  sliced-stylesheet cascade technique where cascade matters); footer counts against fixture pools.

### P8 — Labels, density ramp, reach rings — L

- Extend `utils/labelPlacement.js`: horizontal offsets (`dx ∈ [0, −w/2−9, +w/2+9]` × the existing
  dy ladder — adopt the bundle's `[0,−14,14,−26,26,−38,38]` for the map call while keeping the
  Plan callers' ladder unchanged via a parameter), obstacle-box seeding, frame-edge rejection.
  One module, two ladders, both tested.
- `components/map/MapLabels.jsx` (absolutely-positioned HTML layer, container
  `pointer-events:none`, chips `auto`): one greedy pass in priority order — home marker (< z13) →
  ring labels ("45 min"/"1h 30", pre-filtered to the frame) → region names (< z11.2, centroid of
  the *filtered* pool via `heatField.centroid`, hottest region emphasised, short forms < 430px:
  **CSS truncation of the served name for now** — curated short names are decision D-11) →
  location chips (best-first then nearest, budget `clamp(6 + (z−8.6)×11, 6, 60)`, best-per-region
  always candidates, **the selected location always gets its chip**). Chips = 5px ramp square ·
  name · `N★` with the star in `--ink`, never ramp ink (the bundle HTML is the authority here —
  its README §6 contradicts its own ink rule; follow the HTML). Obstacle list seeds from the live
  chrome rects incl. open callout/menu, each padded 5px. Labels re-place through the same
  rAF-guarded paint as the field (`move` coalesced, `moveend` immediate — never both).
- **The desktop hover tooltip** (z1400): on chip hover — name, event label, `N★ verdict`,
  region · drive · `sky {bortle}`; mouse-only, positioned off the cursor with right/top clamps.
  P10's pins get parity with this, so it is built here, not there.
- **Reach rings on the heat canvas**: dashed `[3,4]`, `rgba(201,162,75,.42)`, below z10.6,
  toggleable. **Tiers are the shipped 25 mi / 50 mi** (≈45/90 min) shared with
  `WindowRowFieldMap.RING_TIERS` — extract the tiers to one util; the bundle's 36/72 km is
  declined (decision D-4). Rings and their labels only when home exists; ring labels only when
  `reachMeasured`-style honesty holds (the tier legend states duration only when a measured drive
  gated it — same rule as the field map's rings).
- Tests: placement unit tests (ladders, obstacles, drop-not-stack, selected-chip guarantee,
  budget edges at z8.6/z13); density snapshot over a fixture catalogue at three zooms; chip ink
  assertion. Browser: the mid-zoom "hole" check — county scale must not drop to 2 labels
  (the bundle's recorded wrong turn).

### P9 — Selection: ring + anchored callout — L

Replaces the Leaflet popup / mobile BottomSheet **on the tab** ("a popup covers exactly the ground
you just asked about").

- Selection ring `.selmk` (34px, `--home` ring + halo + centre dot) and
  `components/map/MapCallout.jsx` (286px desktop / 266px phone, dark card, 11px tail). ⚠️ **The
  tail was removed on 2026-09-05 at the owner's request — §4.30 below**, taking the next line's
  "tail clamped into the card" clause with it (struck below rather than deleted, so the phase spec
  still reads as it shipped); every other clause in this bullet still stands.
  Anchoring recomputed **every paint** so it travels with its point: prefer below (22px gap),
  flip above on band overflow, clamp horizontally to 8px, ~~tail clamped into the card~~; the
  vertical band is derived from chrome bars spanning ≥ 50% of the frame width (the rule that lets
  it sit beside the desktop pill but never under the phone's bottom bar). On open:
  `map.panInside(latlng, {padding:[70,150]})`.
- Contents: name + region · subject tags; verdict block (kind chip, label · time, `N★
  Worth-it/Maybe/Poor` — **verdict words come from served enums where the surface has one**; the
  ≥3.7/≥2.8 client thresholds are only for surfaces with no served verdict, and the map's
  per-location star has none — record the choice in-code); reason prose = that location's
  per-window `summary` from the evaluate/scores feed (fallback: region gloss) — Newsreader,
  3-line clamp; facts row: Drive (measured only; miles are straight-line `distanceMiles`, home
  origin only — away origins show override minutes and no miles), **Leave by** = event time −
  drive − `SETUP_MINUTES` via `utils/leaveBy.js` (guard the midnight wrap — the prototype
  silently wraps; ours must not print yesterday's clock unmarked), Dark sky (Bortle, `· dark`
  when flagged); topic tags filtered to the location (tide topics only where `coastalTidal` —
  reuse `windowFirstTopics`' type-map idiom); **"This location, every window ▾"** — collapsed
  grid of every EV entry with this location's score (the per-location index across windows
  already exists for `LocationFourDaySheet`; reuse `buildScoreIndex`), selecting one switches
  the window; actions: *Zoom to it* (`flyTo`, floor z12.6) and *Open in Plan* — a real handoff:
  switch to the Plan tab and open that location's `LocationFourDaySheet` as the **only** dialog
  layer (the supported stack is two deep; a sheet alone is one — do not route through the popup).
  The shell already supports both halves: the sheet mounts independently of the popup
  (`WindowFirstShell.jsx:1652–1654`; search's `onPickLocation` opens sheet-alone the same way),
  and `selectTab` clears every shell dialog on any tab switch (`:464–475`) — route the handoff
  through `selectTab`, not a bare `setActiveTab`.
- **Inbound handoffs re-target the callout**: `handoffLocationName` today drives
  `HandoffPopupController`, which opens a Leaflet marker popup by name — on the tab it now
  selects the location and opens the callout instead. A click on the map background closes the
  callout (after closing any open popover).
- The tab stops mounting Leaflet `Popup`/`BottomSheet` for markers; `MarkerPopupContent` remains
  the overlay's renderer untouched. Callout is not a modal: no focus trap, no `aria-modal`,
  `Esc` closes (after menus), consistent with `useDialogFocus`'s app-wide containment refusal.
- **Split point if this runs long**: P9a = ring + anchoring engine + core contents; P9b =
  every-window strip + the Open-in-Plan shell hook + popup/BottomSheet removal + inbound handoff
  rewire.
- Tests: anchoring math (band, flip, clamp) as pure functions — the tail arm of this became, on
  2026-09-05, a pin that NO tail is placed (§4.30); content gating
  (reachMeasured, missing summary, LITE); every-window strip switching; the Open-in-Plan handoff
  (new shell hook) incl. the hidden-pane rule (a hidden map must not act on stale handoffs).

### P10 — Pins mode + Legend panel — M

- `components/map/PinsLayer.jsx`: the honest comparison — one dot per location, no field, drawn
  weakest-first; named 26px with `N★` inside (ink via `readableInkOn`), unnamed 13px (production
  note: every catalogue location is named — the small-dot class exists only if unnamed rows ever
  appear); fill = ramp at whole stars. Coastline stroke still draws; rings/region names/legend
  chip do not. Replaces the medallion+cluster view **on the tab** (medallions, clustering,
  azimuth lines remain overlay-only — azimuth in the tab's pins mode is decision D-9). Hover
  tooltip parity with chips.
- `components/map/MapLegendPanel.jsx` (desktop, bottom-left): ramp bar via `rampGradientCss()`
  (**never** the bundle HTML's stale RAG gradient — its legend inverts the temperature field's
  meaning; the README documents the stale spec as if final), whole-star labels `1★ poor / 3★ /
  5★ go`, the Field→Handing over→Locations indicator from the fade `t`, rings toggle, the
  confidence note. Hidden in pins mode.
- Tests: paint order (weakest-first), ink pair, legend gradient source equality
  (the `heatTokens`/`mastheadColours` cross-file-equality idiom), mode switching chrome.

### P11 — Regions jump list, masthead statement, ⌂ — M

- `components/map/RegionsJump.jsx`: `◎ Regions ▾` → one row per region, **sorted by nearest
  measured drive from the active origin** (per-user reach for home; `regionApi` matrix via the
  pane's `driveOverrideById` for away origins — never mix the two, and never borrow
  `distanceMiles` for away), suffix `· beyond your area` past the 3h glance threshold, best score
  this window (served `BriefingRegion.bestRating`, name-keyed join as `heatSpots` already does —
  region-id on the briefing rollup is owner item O-8). Selecting fits that region's bounds;
  **jumping outside My area flips scope to Whole catalogue** ("a jump is honest; a no-op is
  not") — `animate:false` on the refit (the recorded Leaflet-strand trap). Unmeasured-drive rows
  sort last and show no duration (reachMeasured).
- Masthead: on the map tab the tick line's origin control renders as a **statement** (pin glyph,
  `Home · DH3 4NG`, caption "drive times from here") and the ⌕ search button is absent — a
  per-tab state of `MastheadTickLine`, not a fork; `PlanSearch`'s `.wf-tick` anchoring and the
  `searchOpen → tabIndex=-1` machinery must survive on the other tabs (extend
  `MastheadTickLine.test.jsx` for the per-tab variant, and mind WCAG 2.5.3 accname rules already
  enforced there).
- `⌂` resets scope to My area and refits (does not clear filters — matches prototype).
- Tests: sort/threshold/scope-flip; masthead variant incl. accname; ⌂ semantics.

### P12 — Responsive + accessibility pass — M/L

Per the bundle's viewport table: iPhone 390 gets the window control full-width (40px targets),
the Regions/Heat-Pins/Filters row as a **bottom bar** (thumb-reachable), Filters and Regions as
`BottomSheet`s, legend and zoom buttons hidden (pinch), short region names, 266px callout with
the every-window strip collapsed; iPad ≈ desktop. Verify the callout never overlaps the bottom
bar (measured — bundle check #4) at several locations, collapsed and expanded. A11y sweep:
popovers are disclosure widgets (`aria-expanded`/`aria-controls`, no focus trap), the callout has
a real accessible name, the canvas layers are `aria-hidden` with text equivalents (counts footer,
legend text), touch targets ≥ the bundle's minima, keyboard reachability of every control that
exists on desktop. The double-BottomSheet hazard (`App.jsx:289–303`) is re-checked now the tab
itself opens sheets, and the z-ladder relations from P7 (menus > tooltip > callout > chrome >
labels > ring > heat) are asserted.

### P13 — Sweep: verify checklist, cleanup, docs — M

- Run all four bundle verify checks end-to-end and record numbers: (1) alpha at every location;
  (2) luminance monotone 3★→5★ per surface; (3) grep + measure every star label ≥ 4.5:1 (target
  5.56:1); (4) callout-vs-controls overlap on the phone.
- Delete orphans: `DateStrip` (if no consumer), the tab-only branches of the old drawer/window
  select, dead test files — each deletion named in the PR (an orphan kept is an owner question,
  the v1-retirement idiom).
- Update CLAUDE.md (Map tab bullet), this plan's §4 ledger with anything new, and the phase's
  changelog.d entry.
- Re-run the P4 alpha check and P2 luminance table one final time (they are cheap and they are
  the two that regress silently).

**Measured verify results — all four pass.** Measured 2026-09-03 against the P13 worktree build
(seeded local stack, admin, temperature mode):

1. **Alpha at every location** (1280×900, zoom 8.24, land clip active): all 21 enabled locations
   paint — α 87–165, except Wallington Woods α=3, which is the WOODLAND canopy-exclusion working
   as designed (neighbour spill only, its own rating excluded from the sky field). Sea α=0 exactly
   at all three in-view offshore points (North Sea E, NE, off Tyne).
2. **Luminance monotone 3★→5★** (synthetic bundle method — real kernel via Vite module graph,
   single point per score over each surface's ground, centre-sampled): strictly monotone on all
   three surfaces. Thumbnails (bloom 3/155/0.9): .0826→.1067→.1420→.1722→.2037. Popup field
   (3/170/2): .0826→.1061→.1405→.1662→.1960. ⚠️ **Map field row SUPERSEDED by §4 item 16
   (`HEAT_OPACITY` 0.90 → 0.92, 2026-09-03).** Old measurement, kept for the record: Map field
   (bloom 1, blur 4, opacity 0.9, ground #3a332b): .1250→.1518→.1857→.2121→.2443. **Re-measured
   2026-09-03 at opacity 0.92** (same synthetic method, ground #3a332b, conf 1, bloom 1, blur 4):
   .1257→.1525→.1913→.2199→.2481 — strictly monotone.
3. **Star-label ink contrast** (rampHex × readableInkOn, WCAG): temperature min 5.03:1 (1★ 7.13,
   2★ 6.04, 3★ 6.51, 4★ 5.03, 5★ 5.56 — the bundle's 5.56 target exactly), verdict min 4.83:1.
   Every whole-star label ≥4.5:1 in both modes; ink flips dark/light correctly per fill.
4. **Phone callout clearance** (390×780, three locations, collapsed + expanded): Bamburgh Beach
   113/21px, Ashness Bridge 108/21px, Robin Hood's Bay 108/21px above the bottom bar. Zero overlap
   in all six states.

---

## §4 Disagreements with the bundle, on purpose

Recorded so a later reader sees decisions, not accidents (the plan-matrix §4 idiom):

1. **Bloom is temperature-mode-only.** The bundle has one ramp; the app has two plus a per-user
   switch. The bloom's entire measured rationale is the temp ramp's inversion; verdict mode never
   blooms. (§3 P2, decision D-1.)
2. **`ink()` is not ported.** `readableInkOn` already is that function, measured and tested; one
   implementation, in `windowFirstSpots.js`/`scoreRamp` territory, not `heatField.js`. The
   pins-mode ink rule (`#1a130d` when luminance > 150 else `#fff` — a different pair and a
   different test from `ink()`) is also normalised onto `readableInkOn`: one ink implementation
   everywhere.
3. **Reach rings stay 25 mi / 50 mi**, sharing the field map's tiers — an explicit owner decision
   (2026-08-30, field-geography §5.2) the bundle didn't know about. 36/72 km would silently
   re-open it. Flagged as O-3 if the owner prefers the metric pair.
4. **maxZoom stays 16** (repo rationale recorded in-code; bundle wanted 19-with-upscale).
5. **The bundle's legend/segment/mix-bar gradients are stale** (RAG ramp) and are **not** copied;
   the legend paints from `rampGradientCss()`. Likewise the README §6 chip spec ("`N★` in ramp
   colour") loses to the bundle's own HTML and ink rule: star text is `--ink`.
6. **The chronological EV list is solar-first with honest night rows**, not the prototype's fully
   populated week: aurora rows exist only where stored results exist (also the design's own
   intent), astro rows carry served night times (P5) and capped inferred confidence, and neither
   invents topics or narratives. OPEN 1's mock scoring formulas are discarded entirely — real
   feeds only.
7. **No text search on the map, and also no new "named" flag**: every production location is
   named; the prototype's `named:false` filler class has no counterpart, so the 13px unnamed pin
   is dormant until the catalogue ever grows such rows.
8. **Subject tags come from `LocationType`** (six values incl. WOODLAND + seasonal BLUEBELL), not
   the prototype's five-chip derivation; no lake flag; canopy polarity rules (woodland ratings
   never blend into the sky field) are inherited invariants, not new work.
9. **The Plan-tab overlay keeps the old map wholesale** — popup, medallions,
   ForecastTypeSelector. The bundle redesigns "the Map tab"; the overlay is a different surface
   with a different job. Convergence is O-6. ⚠️ **Clustering was struck from this list on
   2026-09-04 (item 20) — the one place the overlay's freeze has been deliberately broken.**
10. **Keyboard shortcuts are pane-scoped**, not document-global — the prototype had no other
    focusable surfaces; the app does.
11. **The callout carries a reduced fact set by design**; the full forecast detail (tide
    indicator, comfort rows, score bars, inversion/dust badges) stays one step away in the Plan
    popup/location sheet and the overlay popup. Anything later judged missing is added to the
    callout deliberately, not by porting `MarkerPopupContent` across.
12. **`m2hm`'s silent midnight wrap is not ported** — a leave-by that crosses midnight is either
    marked or suppressed.
13. **Menu/jump swatches sample at whole stars of the labelled best**, replacing the prototype's
    interpolated pool-mean swatch (the prototype's number and colour answered different
    statistics — `d3.max` text over a `ramp(d3.mean)` swatch; the labelled-fill rule wins).
14. **The window menu's `N★ best` follows the active filters' scope honestly** where the
    prototype computed it over `basePool()` (ignoring every filter but scope) — whichever way
    the implementing session lands this, it states the population in a code comment; the
    prototype's split is not silently copied. ⚠️ **Settled by what shipped, the opposite of this
    entry's hedge — see #15.** `N★ best` is the served figure verbatim (solar: `BriefingWindow`/
    `BriefingRegion.bestRating`; night: `bestOfNight` over the FULL served roster), held to one
    unfiltered rule on every row rather than following scope or any other active filter. This
    entry is kept for the history — the population had to be settled in code, and now is.
15. **A night window's best score is a licensed client max, adjudicated at P11 and ledgered
    here at P13.** `mapEvents.bestOfNight` (P6) is the window control's `N★ best` for an
    astro/aurora night row — the max over that night's FULL served roster, held to the same
    unfiltered rule as a solar row's served `BriefingWindow.bestRating` rather than independently
    scope-narrowed. `regionsJump.buildNightRegionBest` (P11) groups the identical served rows by
    region (via the location→region join `heat.spots` already carries) and calls `bestOfNight`
    once per group — the per-region counterpart of a solar row's served `BriefingRegion.bestRating`
    — so the window dropdown and the Regions jump list can never disagree about a night's best per
    region. Licensed because astro/aurora carries no *rated* per-window or per-region rollup
    comparable to those two solar figures: a star-free per-region aurora summary IS served
    (`AuroraRegionSummary`'s GO/STANDDOWN `verdict`), but nothing on the wire aggregates stars at
    all, per window or per region, for a night. Recorded as a fifth class in CLAUDE.md's
    Backend-heavy licensed-member list (members: those two functions, nothing else); its exit is
    **O-16**.
16. **Map field opacity conformed 0.90 → 0.92 (2026-09-03), superseding heat-field-plan.md §4.5's
    own decision.** 0.9 was not an unledgered accident to begin with — `heat-field-plan.md` §4.5
    specified `opacity 0.9 × heatAlpha(zoom)` outright, and it matched that (earlier) heat-field
    design bundle. The map-tab-v2 bundle that superseded it moved the map surface to
    `opacity:0.92*alpha` (`docs/design/map-tab-v2/map-tab-v2.js:173`), but this plan's §1 item 2
    (inventorying what P4's re-tune changes — radius, fade band, floor) never named opacity as one
    of them, so heat-field-plan's 0.9 survived the port unnoticed rather than by decision. A
    post-P13 spec-vs-shipped audit (2026-09-03) caught the 0.02 gap as the pipeline's one
    unledgered drift. `HEAT_OPACITY` in `MapHeatLayer.jsx` is now 0.92 — conformance to the newer
    bundle superseding the older one's figure, not a fresh re-tune. See heat-field-plan.md §4.5 for
    the superseded decision, annotated there.
17. **Plan-screen confidence medium tier re-tuned 0.72 → 0.82 (2026-09-03) — an owner move AWAY
    from the shipped decision, unlike #16.** `CONFIDENCE_TREATMENT.medium.fillScale`
    (`frontend/src/utils/confidenceUtils.js`) belongs to the Plan-screen confidence channel; its
    decision of record is `docs/engineering/heat-field-plan.md` D3 (the "◐ 88% rejected" decision,
    cross-referenced by `plan-matrix-plan.md` A2) — not this plan's own kernel, and not
    plan-verdict-consolidation-plan.md's own D3 (a different decision entirely, about the Best/Also
    pick pool). It is ledgered here only because it landed in the same owner-directed commit as #16
    and moves a colour dial the same audit examined. The design bundle's confidence ladder carries
    one value per window across six windows (`[0.95, 0.88, 0.82, 0.72, 0.65, 0.57]`); production
    quantises to three tiers and most live cards resolve MEDIUM, so 0.72 rendered the typical card
    at the bundle's *window-4* value — one step hazier than intended, and the audit found the Plan
    tab reading muted for it. The owner moved the tier to the bundle's *window-3* value (0.82)
    instead. Measured live: medium-tier thumbnail cores render ~15–16% brighter at 4–5★ than at the
    old 0.72 (5★ centre-sampled luminance .1341 → .1559), tier ordering (low < medium < high)
    intact. This is a deliberate departure from D3's original number, not a conformance fix: LOW
    (0.5) and HIGH (1.0) are unchanged.
18. **The tab's medallions are hidden unconditionally while `MapHeatLayer` is mounted (2026-09-03)
    — a conformance fix that REMOVES an unledgered disagreement, the opposite direction to #16.**
    Two conditions were tried on that hide and an adversarial review killed both; they are recorded
    together because the second was introduced *by the fix for the first*.
    **(a) The zoom condition.** `fadeAt`'s `markers` half faded the pre-v2 cluster medallions and
    per-location discs back in across the 10.4→12.0 band, so past zoom 12 a Heat-mode map carried a
    cluster bubble and a coloured disc under every chip that already named the same location and its
    own star. The bundle's Heat view has no markers **at any zoom** — `map-tab-v2.js`'s `paint()`
    draws the field and the labels and nothing else, and `handover()`'s `t` reaches only the
    Legend's own indicator (`setHand`; the field reads the separate `heat` component). §3 P10 says
    the same in its own words ("Replaces the medallion+cluster view **on the tab**"), and
    `MapLabels.jsx`'s `LABEL_PANE_Z = 650` comment already recorded the mismatch as a fact. The fade
    predates P8 by two weeks (#564 → #733): the medallions genuinely were the only vocabulary the
    tab had past the band when it was written, and it was never revisited once the chips arrived.
    **(b) The point-count condition.** `points.length > 0` — inherited from that same pre-P8 code
    and kept in the first cut of this change — was defended as "an unscored window has nothing but
    the markers to say where the locations are". Every clause was false. `MapLabels` mounts on
    `heatOn` with no scoring gate and renders an unrated spot as a grey-swatch chip, so the map is
    never blank; the restored pane is frequently *emptier* than the chips, because
    `visibleLocations` drops unrated non-wildlife locations unless `showUnrated` (default false);
    and an empty `points` array is not "nothing is scored" at all. It is also a **D-13 filler row**
    (`heatWindow` null — one click away in the window control, and `windowUnscored` is false there,
    so nothing on screen says why), the **dark-sky filter** narrowing a well-rated window to
    nothing, and a **served window with an empty `pointsByKey` entry** (a real production join gap
    already pinned in `MapViewHeat.test.jsx`). All three restored the full medallion set under the
    chips — the reported bug, on triggers (a) never touched. Re-keying to `windowUnscored` (which
    `MapView` derives correctly and whose javadoc already says "the served `bestRating`, **never a
    point count**") was considered and rejected: the chips carry those same stars from the same
    accessor, so it would restore a duplicate vocabulary to no one's benefit.
    **What ships**: `applyMarkerFade(map, 0)` on every paint while the layer is mounted;
    `restoreMarkerPanes` is reached only by the unmount cleanup, which is what keeps aurora (where
    `heatOffered` is false and the layer never mounts), an unscored *catalogue*, a tab change and a
    logout honest. `fadeAt().markers` survives as the Legend indicator's progress fraction, painting
    nothing. `markersLocked` is gone — its premise was the overlay's Leaflet popup leader tip, and
    the tab's selection is `MapCallout`, which anchors on the location's own coordinates and draws
    its own ring. Four test pins were **inverted** rather than deleted and each says so in its own
    comment. `MARKER_INTERACTIVE_ALPHA` and `markersAreInteractive` are now vestigial (the only
    argument is `0`) and are deliberately **kept**: they are the regression net for any future
    middle ground, and deleting them as dead code is what would make this hard to reverse.
    ⚠️ **Two pre-existing defects this exposed, BOTH now fixed** — item 19 (the colour ramp key
    rendering above a field that paints nothing on a D-13 filler row, which the medallions were
    masking) and item 21 (Leaflet's focusable no-op markers). Neither was smuggled into this change;
    each got its own phase, which is why they are separately ledgered.
19. **The colour key no longer renders above an empty field (2026-09-04) — the first of item 18's
    two exposed defects, closed.** `windowUnscored` required a non-null `heatWindow`, excused in its
    own javadoc by "the selector already says 'No forecast window' for that". That excuse was false:
    `WindowControl` says it only when NO EV row matches, and a **D-13 filler row** matches perfectly
    well — it is an ordinary enabled row the `›` stepper walks into. So on a filler row the reader
    got no "not scored yet" line AND a colour key to a gradient that was not there. The same held
    for any date the EV list has no row for, where the key also rendered.
    `heatWindow?.bestRating == null` now covers both "no served window" and "served, nothing rated",
    which are one answer to the only question the key asks. The camera-vs-forecast distinction the
    old comment reached for is kept, but moved onto the MESSAGE (`unscoredLineShown`), which stays
    quiet on a date with no row so the selector is not answered twice. ⚠️ **ASTRO is exempt from
    that gate** — it carries no EV row when nothing is scored, but its unscored state is derived
    from `astroHeatPoints` directly, i.e. a statement about the forecast, not the camera; gating it
    silenced the one mode whose message is always earned, and a test caught it. Both new pins fail
    against the old logic (verified by reverting the predicate).
20. **The tide-alignment gate is `TideFactDeriver`'s dynamic half-width, never bundle rev 2's fixed
    ±45 minutes.** `map-tab-v2.js`'s `tideOf`/`tideFit` demo (`TIDE_TIGHT=45`) parses the window out
    of already-formatted tide copy; the port instead serves the fact structurally.
    `BriefingSlotBuilder.calculateTideData` computes four new `BriefingSlot.TideInfo` fields
    (`nearestSolarOffsetMinutes`, `nearestExtremeKind`, `tideOnTheLight`,
    `nearestSolarOffsetPhrase`) from the nearest tide extreme of *either* kind against
    `TideFactDeriver.tightAlignmentWindowMinutes` — the same per-location, per-date, per-event
    half-width the Plan tab's own tide row already gates on, so the map's glyph and the Plan tab's
    tide row can never disagree about what counts as "on the light"
    (`WindowTideRollupBuilder.java:308-314`'s own warning against a second alignment rule). CLAUDE.md's
    backend-heavy rule applies unchanged: the offset, kind and gate are structured fields on the
    wire, and the one phrase string is built once server-side from the shared `TideWording`
    vocabulary — no client parses formatted tide copy the way the bundle's demo code does. ⚠️ The
    gate matches the Plan tab's tide row exactly, but the two can still name different *water* in a
    fringe case: the row's water is that day's chosen *representative* extreme
    (`TideRunBuilder`'s own selection for a multi-day run), while the chip's is the nearest extreme
    of *either* kind to THIS event, full stop. That is deliberate, not an inconsistency to fix — the
    chip is answering "does the water land on the light here", not "which extreme is this run's
    representative one", and the two questions can disagree on which tide they are about while
    agreeing on the answer that matters (whether it is tight).
21. **`dayLabel` ships as a sibling field, not a rewrite of `label`.** The bundle's `dayOnly()`
    mutates the string a window is keyed and displayed by; the port instead has
    `utils/mapEvents.js` emit `dayLabel` alongside the untouched `label` on every EV row (solar:
    `label` with its trailing sunrise/sunset word stripped; night: identical to `label`, since a
    night row carries no event word to strip). Only the four chip-adjacent consumers that sit
    beside a kind chip switch readers — `WindowControl`'s collapsed pill and menu rows,
    `MapCallout`'s verdict line and every-window strip cells — because the chip already states
    SUNRISE/SUNSET and repeating it in the label text beside it would say the same fact twice. The
    pin tooltip (`MapView`) and the callout strip cell's own `title` attribute keep reading `label`
    unchanged, since neither sits beside a kind chip that would make the event word redundant.
22. **§4 of the increment — the full-bleed width question — is NOT implemented, and that is the
    increment's own answer.** It argues the map should keep the masthead's content column and then
    says outright: "Product decision, not settled here." The shipped tab releases that constraint on
    the Map tab alone (P7's recorded decision, `WindowFirstShell`'s own comment: "the wrap stays on
    masthead + tab bar, only the panel region releases"), so implementing §4 would reverse a merged,
    ledgered phase decision on a hint rather than a call. Left as an owner decision; the toolbar
    toggle the bundle ships for comparing the two at real width lives in `Map Tab v2.html`, not here.
23. **The increment's amber `outside your plan` badge is NOT adopted — this arm already fixed that
    bug differently, and better-argued.** §2 of the increment prescribes
    `border-color:rgba(224,165,66,.45); background:rgba(224,165,66,.11); color:#EFC377`, because in
    the parallel panel it built the badge inherited no rule at all and "rendered as a third neutral
    meta fact instead of a warning". Here the badge is `.wf-loc-out`, which has always had its own
    rule, and that rule's own comment refuses amber on purpose: `--color-verdict-marginal` "would
    read as a judgement on the forecast, which is the one thing this badge says nothing about". It
    takes the arm's away/elsewhere ink instead (`#6FA8B0`, measured 5.26:1 on the composited head).
    Verified live on a Scottish Borders location: it renders as a bordered, tinted pill reading
    `outside your 3h area`, not a neutral fact. The increment's *defect* does not exist here; its
    *fix* would reintroduce a semantic this arm deliberately rejected.
24. **§2's "reused verbatim from the spec" list is 2 of 7 here, and the other five are refusals
    this arm made before the increment existed (2026-09-04).** The increment lists seven things its
    sheet takes from `design_handoff_plan_matrix` §3. Two hold ({@code ◎ BEST} tagged in place and
    expanded by default; the 52px row grid). The rest were each decided against, in code, with
    reasons: the kicker's **denominator** (`5 of 10 windows at 4★+`) is refused outright by
    `locationSheet.leadLine` — plan-matrix M4's rule is `2 windows at 4★+` / `none at 4★+`, never
    `1 OF 6`; the **lead paragraph as the largest type** is inverted here (`.wf-loc-lead` is a 9.5px
    mono kicker under a 15.5px title) because the design's second lead line is the best window's
    prose and the best row arrives expanded eight pixels below it; **≤2★ rows** are at `.8` not
    `.62`, with four documented exclusions; the **card sizing** is a centred `Modal` at 880px rather
    than `min(680px, 100% − 36px)` at `top: 20px`, so the two dialogs on this tab read as one shape;
    and the **class vocabulary** is `.wf-sheet-*`/`.wf-loc-*`. None is a defect and none was
    introduced by this increment — but the increment asks for them by name, so the divergence is
    recorded here rather than left to be rediscovered.
25. **The route LEAVES the Map tab, where the increment's z-index note implies a layer over it
    (2026-09-04).** §2's one recorded adaptation is `z-index: 1700` "over everything, **without
    closing what is underneath**". Here `Four days here ›` reuses `Open in Plan`, which requests a
    tab change, so the map goes. Kept, for two reasons. The sheet's own footer carries
    `◍ Show on map → …`, an action that only makes sense from somewhere the map is not — the sheet
    was designed off-map. And mounting it over the map would give one dialog two hosts and force the
    two-deep dialog-stack invariant (plan-matrix §6 M5) onto a third route. Revisit as **O-18** if
    the owner wants the map kept underneath.
26. **The reason button's accessible name is the whole narrative, and that is the spec's structure
    (2026-09-04).** §1 is explicit that "the clamped prose *is* a button", with an HTML sketch
    wrapping both prose and caption. Measured on the increment's own long fixture the resulting
    accessible name is 399 characters / 72 words. An accessibility lens confirmed **2.5.3 passes**
    (the visible caption is contained, in order) but flagged the ergonomics: VoiceOver treats a
    button as atomic, so the prose can no longer be read in chunks; NVDA's element list carries a
    400-character entry; Dragon cannot match it by label. The alternative — caption-as-button, prose
    as a sibling `<p>` — has a four-word name and keeps the clamp, but abandons the spec's large
    click target. Kept as specified, `aria-haspopup="dialog"` added (this tab's convention for a
    control that opens one), and recorded as **O-19**.
27. **The meta row's topics are not scope-intersected, where `windowFirstTopics.windowTopics` is
    (2026-09-04).** A8's rule intersects a topic's served `regions` with the origin scope "because
    `regions` means something different per strategy". `sheetMetaFacts` applies only the coastal
    day-scoped filter, so the row can list a topic whose `regions` never contain this location's.
    The map callout has always done the same, so the two agree — but the callout states topics
    beside a window where this row sits under the place's name and reads as a property of it.
    Low severity, no live report; recorded rather than fixed because the honest fix (intersecting)
    would make the callout and the sheet disagree, which §2 forbids more strongly.

28. **One increment string is reconciled to #748's house style rather than shipped verbatim
    (2026-09-04).** §2's meta row specifies `Coastal · tide applies`. The plain-English copy pass
    (#748) landed while this branch was open and its entire thesis is deleting that register from
    customer-facing surfaces — *held back* → *not listed*, *drive from origin* → *drive time*,
    *Field*/*Locations* → *Regions*/*Places*. "Applies" is the legalistic form that pass was written
    to remove, so the row reads **`Coastal · the tide matters here`**. The increment's other two
    strings are kept verbatim: `Dark sky N · dark` already matches `calloutFacts`' own wording (which
    the pass did not touch), and `Four days here ›` is plain already. Easily reverted if the owner
    prefers the literal spec.
29. **P7's width release is reversed (O-17, bundle rev 2, owner decision 2026-09-03) — the Map
    tab keeps the masthead's 1080px column instead of going full-bleed.** Bundle rev 2's own case
    is structural, not a taste call: full-bleed made the tab strip look like it floated above an
    unrelated surface, since the strip stops at the content column while a full-width panel
    carries on to the window edge, and at wide viewports it added sea and empty moor rather than
    information. P7's HEIGHT half — the `100dvh` flex recast, zero-padding `.wf-body.wf-body--map`,
    no page scroll — is untouched; only the panel region's own width constraint changed, from
    releasing `WRAP_MAX_WIDTH` on the Map tab to sharing it with every other tab off the same
    constant. O-17 is registered in §6 and bundle rev 2 vendored by the tide/label PR of the same
    date, merge-ordered ahead of this change. The 1080px figure is the app's OWN `WRAP_MAX_WIDTH`
    (the masthead's column, which is what bundle rev 2's note names) and deliberately not the
    design bundle's own `.wrap{max-width:1240px}` — that figure belongs to the demo harness's
    comparison rig (it also drives `.wrap.pad`'s 834px and `.wrap.mob`'s 390px device frames) and
    was never the app's own width; and the no-border/no-radius call on the map panel was judged
    the same way — the harness's `.frame` wraps masthead+tabs+map together with bezel-style box
    shadows that scale up per device width, which reads as the comparison rig's own device chrome,
    not a treatment the shipped panel is meant to carry.

20. **Marker clustering deleted outright, on both surfaces (2026-09-04) — an owner decision, and
    the only deliberate break of the frozen-overlay rule to date.** Asked whether
    `MarkerClusterGroup` should stay mounted on the tab, the owner's answer was to delete it rather
    than gate it, and the reasoning is the record: **clustering is the precise defect the heat field
    exists to fix.** A bubble reading "12" cannot tell you whether tonight is worth driving for —
    it averages the gems away, which is this bundle's own argument for why heat became the default.
    A Pins mode that clusters stops being the honest comparison and becomes a third representation,
    at which point the case for heat is unfalsifiable because the reader never sees the pile it is
    solving. Pins already answers density deliberately (weakest-first paint order so the best draws
    on top, 26px named over 13px unnamed); clustering destroys that z-order and swaps the
    best-visible pin for a count. ⚠️ **Scale does not reopen it**: at ~51 locations it is
    unnecessary, and if the catalogue ever reaches thousands the answer is canvas rendering
    (`preferCanvas` is already set), because clustering re-introduces the averaging problem at any
    size.
    **The overlay.** `MarkerClusterGroup` was ungated — one component, two mounts — so this reaches
    the deliberately frozen Plan-tab overlay, which §4.9 above listed clustering among its keeps.
    Confirmed with the owner before executing rather than inferred: the overlay keeps its medallions
    and popup (its actual distinctives) and now renders them unclustered. It opens focused on one
    spot from a card, and O-6 convergence is its stated destination, so keeping a dependency alive
    solely for it was the debt this deletion clears.
    **What went with it**, all verified dead rather than assumed: `react-leaflet-cluster` off
    `package.json` (`leaflet.markercluster` was transitive; `npm ci` re-verified, and the lockfile
    diff is pure deletion — no metadata churn), `markerUtils.createClusterIcon`, `markerUtils`'
    now-unused `leaflet` import, `clusterGroupRef`, and the chip click's `zoomToShowLayer` — whose
    real effect was jumping the camera on EVERY chip click below zoom 13 to reveal a marker the tab
    does not paint, and whose ring-anchoring justification had been false since P9. Also
    `excludeFromCluster` / `excludeFromSkyCluster`, write-only once their sole reader went; the
    subject rule they encoded survives in `heatSpots.js`, which is what the field reads. 21 test
    files dropped a `react-leaflet-cluster` mock and 22 a `MarkerCluster.css` mock; 20 tests were
    deleted as testing absent behaviour, each noted where it stood. Measured: the lazy `leaflet`
    chunk builds at 165 kB where `vite.config.js` recorded ~196 kB, and that comment is corrected.

21. **The hidden markers leave the tab order and the accessibility tree, via `inert` (2026-09-04)
    — item 18's second exposed defect, closed.** Leaflet gives every marker icon
    `tabIndex=0`/`role="button"` (`keyboard: true` is its default), and `applyMarkerFade` hid the
    panes with opacity plus a `pointer-events` class only, on a recorded rule that neither should
    "touch the tree" — justified by "the markers are the only route to a location's popup". That
    justification died with P9 (#734), which stopped mounting a Leaflet `Popup` on the tab. What was
    left was a catalogue of invisible controls that do nothing, reached BEFORE `MapLabels`' chips,
    which are the route that works.
    **`inert` on the pane**, not `visibility: hidden` and not a `tabindex` sweep. `visibility`
    fights the opacity fade the same function exists to apply; a sweep is one-shot, and Leaflet
    creates and destroys marker icons as filters, windows and the roster change, so anything added
    afterwards would be focusable again. `inert` is set on the pane, covers whatever it later
    contains, and removes the subtree from the tab order and the a11y tree together — the pair the
    old rule wanted kept apart and now wants joined. `wf-markers-inert` stays as defence in depth
    (`inert` blocks pointer events too, so the class is redundant where the attribute is honoured,
    but it is the older and better-supported half).
    ⚠️ **Scoped by construction, twice over, with no mode test of its own.** The layer only mounts
    on the tab, so the Plan-tab overlay — where markers DO carry popups and ARE a real keyboard
    route — never sees `inert`. And an AURORA window unmounts the layer, at which point
    `restoreMarkerPanes` clears the attribute: aurora has no chips and no pins, so the medallions
    are the whole location vocabulary there and must stay reachable. Both fall out of "set while
    hiding, clear while restoring".
    ⚠️ **jsdom implements no `inert` behaviour**, so the tests assert the ATTRIBUTE; a test there
    trying to prove non-focusability would pass against a no-op. Both new pins were verified to fail
    without the fix. The `visibility`-vs-`display` half of the original decision is still pinned, and
    the review that surfaced this measured it as overwhelmingly pre-existing rather than caused by
    item 18 — `fadeAt` already returned 0 at every zoom the tab opens at.

30. **The callout's 11px tail is gone (2026-09-05) — an owner decision, taken on the rendered
    card.** ⚠️ **Numbered 30, not 22**: the two entries above it are a second run of 20 and 21
    (added 2026-09-04 by #755 and #757), so this list already carries two items numbered 20 and two
    numbered 21, and a third 22 would have made every "§4.22" in this repo ambiguous. Those two are
    left as they stand because CLAUDE.md cites "item 20" for the clustering deletion; cite the end
    of this list by CONTENT, not by number.
    P9 above specifies "286px desktop / 266px phone, dark card, 11px tail", and the tail was
    built: a rotated square held outside the card's own box by CSS alone — a plain child `<span>`
    of `.wf-callout` at `top`/`bottom: -6px`, NOT a second `createPortal` (only the ring + card as a
    unit are portalled, to the chrome wrapper), which is exactly why that box's `overflow: visible`
    was load-bearing for it. It flipped `above`/`below` with the anchoring and slid along the card's
    edge by `anchorCallout`'s `tailLeft`. The owner asked for it removed.
    ⚠️ **AND IT DESERVED TO GO ON ITS OWN MERITS — do not read the paragraph below as a reluctant
    trade.** That framing was written first and is too generous to the tail; the owner corrected it
    from the screen grab that prompted the removal, where the arrow sat directly under a chip
    reading *Gosforth Nature Reserve* above a card titled *Newcastle and Gateshead Quayside*.
    **The tail was accurate to a POINT, and what is drawn at that point is a LABEL — which is not
    at its own point.** `placeLabelPass` nudges every chip off its anchor to keep the layer
    readable: up to ±38px vertically (`MAP_NUDGES`) and up to half the chip's own width plus 9px
    horizontally (`mapDxOffsets`), so a ~297px name legitimately sits ~158px from the place it
    names. An arrow fired into that layer therefore lands on a neighbour's name routinely, and the
    reader cannot tell the difference: an arrow is a strong claim — *that* one — so pointing at the
    wrong name costs more than pointing at nothing. **And in one case it aimed at a pixel the
    location was definitely not at**: `tailLeft` was clamped to `[13, cardWidth − 24]`, so a card
    pushed against a frame edge got a tail that stopped at the card's edge instead of reaching its
    point. That clamp was deliberate — keeping the tail ON THE CARD was chosen over keeping it on
    the location — which is the trade a pointer cannot win once the card is edge-clamped.
    ⚠️ **The ring inherits the CONGESTION half of this, and that was left alone deliberately
    (owner's call, same day).** `MapLabels`' greedy pass seeds only *chrome* as obstacles — the
    eleven `OBSTACLE_SELECTOR` testids plus Leaflet's own corner — so neither `.wf-selmk` (z1200)
    nor the card (`map-callout`, z1350) is one, and nothing keeps another location's chip off the
    selected point; both simply paint over the label pane (z650). The ring is *less* wrong than the
    arrow was — it encircles a spot and puts a 7px dot on the exact point rather than aiming across
    a 22px gap at it — but in a crowded area it can be drawn around a neighbour's name. Seeding the
    ring's 34px box as an obstacle before the pass is the fix if this is ever worth taking;
    considered on 2026-09-05 and not taken, because it changes label placement on the tab and the
    arrow was the part that actually misread.
    **What removal costs, stated rather than waved past** — read now in the light of the two
    paragraphs above, which is a smaller cost than it first looked: the tail was the only part of
    the card that pointed AT anything, so the card's tie to its location now rests entirely on
    the `.wf-selmk` selection ring (P9 above calls it `.selmk`, the design bundle's own name) and on
    `map.panInside` bringing both into view together — which is what the ring was always for, and
    the card also names the location in its own title. A pointer whose target could not be relied
    on is not a capability lost. Measured in Chromium at 1280×900 rather than
    asserted: with the card flipped ABOVE its point the ring sits 5.3px under the card's bottom edge
    and centred on it, and with the card BELOW its point 5.0px over its top edge — the same 22px
    gap the anchoring always used, minus the ring's own 34px box. The card carries two children,
    both `position: static`, and no node in the document matches `[class*="tail"]`. The
    anchoring itself is untouched: prefer-below, the band flip, the 8px horizontal clamp and the
    22px gap all still run, and `below` still chooses `top` — it merely no longer chooses which way
    a pointer faced. `tailLeft` was deleted with it (nothing else read it) rather than left as a
    computed value with no renderer, which is the shape the clustering deletion's own sweep took
    (the SECOND item numbered 20, above).
    ⚠️ **`below` is deliberately not treated the same way, and the difference is not a hedge.** The
    tail span was its only production reader too — `MapCallout` now takes `left`/`top` alone — so a
    mechanical application of the rule above would delete it. It stays because it is not a derived
    coordinate for a deleted element: it is the function's own flip DECISION, computed regardless in
    order to choose `top`, and returning it is what lets a test say "it flipped" rather than compare
    two pixel values that can coincide. `mapCallout.js`'s own doc carries the same distinction.
    `.wf-callout`'s `overflow: visible` **stays**: the tail was one of two reasons for it, and the
    other — the inline `max-height` whose squeeze must land on `.wf-callout-body` alone — is still
    pinned by `mapCalloutClampCascade`, which now also pins that the tail rule has not come back.
31. **The window pill is a FIXED 262px and ellipses; the bundle's hugs its content and never
    truncates (2026-09-05).** The prototype gives this control no width at all —
    `#wnow{gap:9px;padding:6px 10px;min-height:36px}` over a `.pill` with `white-space:nowrap`, no
    cap, no `overflow` and no ellipsis (`Map Tab v2.html`) — so the pill grows and shrinks with
    whatever the event says. The port already diverged once at P6, which added a `max-width: 260px`
    app-side with no counterpart in the bundle and no entry here; this item records both halves,
    because the second one is the reason the first stopped being invisible.
    **Why.** Content-sizing puts the `‹ ›` steppers somewhere new on every step, and stepping is
    the entire use those buttons have. Measured in Chromium against the app's own loaded fonts
    across every combination the component can emit, the pill ran 115.41px ("No forecast") to
    227.52px ("Aurora · Wednesday night · 21:04"), moving `›` by up to 112px between one click and
    the next. An owner report, not a theory.
    **Why 262 and not the measured maximum.** The one width the design does declare for this
    control is the dropdown's — `#wmenu`, 334px (README "The window control", §3 P6) — so the
    control is sized to the menu it opens and the two share both edges; 262px is simply what is
    left of 334 after two 32px steppers and two 4px gaps. Deriving it from the bundle's own figure
    is the point: the retired 260px had no provenance to appeal to, and at 260px the menu overhung
    its trigger by exactly 2px at every viewport, which reads as a misalignment on a control that
    is otherwise pixel-constant.
    ⚠️ **Truncation is not newly introduced.** `overflow: hidden`, `text-overflow: ellipsis` and
    the 260px cap all pre-date this change, so the clip threshold moved by 2px in the safe
    direction. The widest REACHABLE content is that 227.52px case (~14% headroom);
    `WindowControl.jsx`'s `dayLabel ?? label` fallback would reach 247px but is unreachable from
    `mapEvents.js`, which sets `dayLabel` on all three row shapes it builds. The headroom is
    measured at 100% text scale with the bundled face — a minimum-font-size setting, Firefox's
    text-only zoom or a webfont failure can still eat it, and the clipped text stays in the pill's
    accessible name (computed from its subtree; `title` carries `rosterNote`, never the label).
    ⚠️ **`.wf-map-chrome-tl` is a label-placement obstacle** (`MapLabels.jsx`/`PinsLayer.jsx`
    `OBSTACLE_SELECTOR`, padded 5px, and a label with no non-colliding candidate is **dropped**,
    not nudged), so the control's width is not purely its own business. The obstacle goes from a
    variable 187–300px to a constant 334px. Measured at two views on the live tab, the rendered
    label set and every label's position were **identical** before and after — the band is only
    36px tall, so widening it rarely exhausts a location's candidates. The stability cuts the other
    way too, and in the same direction as the fix: a variable obstacle made labels appear and
    disappear as the reader stepped, which is the flicker the steppers were doing.
    Adversarial review also removed a `min-width: 0` from `.wf-win-label` that the first cut added
    with a stated mechanism that was not the one at work — `overflow: hidden` already makes the
    label a scroll container, so its automatic minimum was zero before the change (CSS Sizing 3
    §5.1). Verified identical both ways in Chromium and at 320px.

## §5 Decisions taken in this plan (challenge in review, not in code)

- **D-1** Bloom gated on temp mode (§4.1). Alternative — retiring verdict mode — is O-1.
- **D-2** In-place tab evolution behind the `heat` opt-in; no flag, no parallel component (§2).
- **D-3** Solar-first EV; P5 is the only backend phase strictly required.
- **D-4** Ring tiers 25/50 mi shared with the field map (§4.3).
- **D-6** maxZoom 16 (§4.4).
- **D-8** Admin drawer toggles ride into the filters popover as an admin-only row; the popup's
  admin "Run Forecast" does **not** move to the callout (Ops + the overlay cover it) — revisit as
  O-7 if the owner misses it.
- **D-9** Azimuth lines: overlay keeps them; the tab's pins mode drops them initially (they were
  marker-layer furniture; the design's chip/pin vocabulary has no host). Restore later on the
  selection ring if wanted — O-5.
- **D-11** Region short names by CSS truncation for now; a curated nullable `regions.short_name`
  (+ admin field, + migration proven only in CI) is O-4.
- **D-13** The EV list keeps the map's full T..T+5 browsable horizon: forecast dates beyond the
  briefing's rendered ~3 days appear as unscored/dim solar rows (§3 P6). The alternative —
  deliberately retiring T+3..T+5 browsing — is the owner's to take (O-14), and would need the
  pane javadoc's horizon rationale rebutted here.

## §6 Owner decisions / OPEN items (nothing below blocks P1–P4)

- **O-1** Verdict colour mode: keep (bloom stays gated) or retire (bloom unconditional, switch
  removed). The design implicitly assumes temperature-only.
- **O-2** `OPEN 3` hillshade basemap (`Elevation/World_Hillshade_Dark`, filter `saturate(.4)
  sepia(.22) brightness(.86)`): option, default, or dropped. The six-way comparison is vendored.
- **O-3** Ring distances: confirm 25/50 mi stands over the bundle's 36/72 km.
- **O-4** Curated region short names (migration + admin UI).
- **O-5** Azimuth lines on the redesigned tab.
- **O-6** Overlay convergence onto the callout (and `MarkerPopupContent`'s long-term home).
- **O-7** Admin single-slot "Run Forecast" from the map surface.
- **O-8** `regionId` on briefing region rollups (kills the name-keyed join class).
- **O-9** Aurora nightly scheduling (today: manually triggered — `POST /api/aurora/forecast/run`,
  ADMIN or PRO, no scheduled producer — so most nights have no aurora row) — prerequisite for
  aurora being a *dependable* EV column; also whether LITE should see greyed aurora rows or none
  (greyed requires LITE-safe presence metadata the role-gated aurora API cannot serve today —
  until that backend decision, P6 ships LITE with no aurora rows).
- **O-10** Night confidence + night topics as served channels (until then: capped inference, no
  topics on night rows).
- **O-11** The LITE/PRO split on `GET /api/briefing/evaluate/scores` (pre-existing leak the Plan
  tab already has; the map adopting the same feed widens exposure of enhanced-tier ratings to
  LITE. Known-open per project memory; not this plan's to fix, but P6/P9 increase its surface).
- **O-12** Higher-resolution coastline (would let the clip threshold rise above 11.5;
  `frontend/scripts/generate-uk-land.mjs` is the generation path).
- **O-13** Esri terms/rate-limit confirmation for production (provider unchanged; usage grows).
- **O-14** Whether to retire T+3..T+5 map browsing instead of D-13's unscored later-day rows.
- **O-15** Two legend surfaces coexist on the tab's Heat view: the plain always-visible
  `wf-map-heat-legend` ramp key (top-right, beside the Heat/Pins segment — pre-dates this plan,
  from the original heat-field work, #564) and `MapLegendPanel` (P10, bottom-left, `▤ Legend ▾`),
  which contains the same ramp plus whole-star labels, the handover indicator, the rings toggle
  and the confidence note. **Deliberate, not an oversight** — P10's squash-merge commit message
  (`195ed993`, #736) states it explicitly: "A new MapLegendPanel is added ALONGSIDE the existing
  in-map heat legend key (not a replacement)." P13 leaves this alone; **retiring the plain key is
  NOT a free mechanical dedup either** — `MapLegendPanel` is desktop-only (`!isMobile`,
  `MapView.jsx:3574`), and `index.css:2959–2961` records, as a P12 review finding, that the plain
  key deliberately STAYS on the phone because the panel is also hidden there: removing the plain
  key without first giving the phone its own legend surface would strand it with none at all. If
  the owner wants one surface, the exit is either (a) retire the plain key on desktop only, where
  the panel is a strict superset, and leave the phone's shrunk key as its sole legend, or (b) give
  the phone a legend surface first (a `BottomSheet` variant of the panel, matching P12's Filters/
  Regions treatment) and only then retire the plain key everywhere.
- **O-17** ✅ **CLOSED by #749 (2026-09-04).** It asked for a served per-location tide answer, so
  the glyph would stop being one representative coastline's geometry asserted on every coastal chip.
  #749 ships exactly that: `BriefingSlot.TideInfo` gains `tideOnTheLight` (plus the signed offset,
  the extreme's kind and a formatted phrase), computed per slot at that location's own solar time
  against `TideFactDeriver`'s dynamic half-width. The limitation this item existed to name no longer
  exists.
- **O-18** Whether `Four days here ›` should open the sheet OVER the map rather than switching to
  the Plan tab (§4 #25).
- **O-19** Whether the reason button should keep the spec's whole-prose target (a 399-character
  accessible name) or move to caption-as-button with a four-word one (§4 #26).
- **O-16** The exit for §4 #15 / CLAUDE.md's Backend-heavy fifth class: a served, RATED
  per-window or per-region night rollup for astro/aurora (comparable to a solar row's
  `BriefingWindow.bestRating`/`BriefingRegion.bestRating`) would retire `mapEvents.bestOfNight`
  and `regionsJump.buildNightRegionBest`'s licensed client aggregation outright. No such rollup
  exists today — `AuroraRegionSummary` carries a GO/STANDDOWN `verdict` but no stars, and astro
  has no per-region rollup at all — so until one ships, this pair is the licensed stand-in.
- **O-17** — bundle rev 2's width note (the map keeps the masthead's 1080px column rather than
  full-bleed): **DECIDED 2026-09-03**, owner chose the column; implemented as its own change (see
  the width PR), not in this PR.

## §7 Phase → session map

| Phase | Size | Depends on | Ships user-visible |
|---|---|---|---|
| P1 kernel | S/M | — | nothing (inert) |
| P2 bloom on | S/M | P1 | **the whole Plan-screen colour change** + map bloom |
| P3 basemap dress | S | — | warm tiles, quiet glance |
| P4 land clip + re-tune | L | P1 (soft mask) | the "heat in the sea" fix |
| P5 night times (backend) | S/M | — | nothing directly |
| P6 window control | L | P5 | one control, date strip + pills gone |
| P7 filters popover + full frame | M/L | P6 | map owns the whole frame |
| P8 labels + rings | L | P7 | names, regions, rings |
| P9 callout | L | P6 (P8 helps) | selection on the map |
| P10 pins + legend | M | P8 | honest comparison + legend |
| P11 regions jump + masthead | M | P7 | jump list, masthead statement |
| P12 responsive + a11y | M/L | P7–P11 | the phone layout |
| P13 sweep | M | all | verified checklist, cleanup |
