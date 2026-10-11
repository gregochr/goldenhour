# Map tab — aurora as a heat field, the card's dismiss, and the viewline: implementation plan

**Source**: the design bundle vendored at `docs/design/aurora-heat/` — `README.md` (the spec, three
tasks), `Map Aurora Heat.html` + `aurora-map.js` (the prototype), `heat-field.js` (the kernel with
the bundle's one two-line change). `docs/design/aurora-heat/VENDORING.md` records what was copied
and what was not. Read the spec before any phase; **this document is the *port* plan**: what the
codebase already has, what is genuinely new, where the spec and the code disagree on purpose, and
how the work cuts into single-session phases for **Sonnet** sessions. The viewline findings are
their own document, `docs/engineering/aurora-viewline-investigation.md`, because the bundle asks
for them to be reviewed by the owner before any viewline code moves.

**The one-sentence version.** An aurora window on the Map tab still draws one Leaflet star
medallion per location while every other window draws the heat field; this increment paints the
aurora night through the **same kernel** with its **own ramp** (night slate → teal → aurora green),
gives the best-location card a way to close, and — separately, owner-gated — corrects the southern
viewline, which on 10 Oct 2026 drew at 54°N through a G3 storm seen from the south coast.

**The one-paragraph correction.** The spec's picture of the code is a year old in places, and the
work is both smaller and differently shaped than it says. **Astro mode already paints the heat
field** (`MapView.jsx`'s `astroHeatPoints` + `astroConfidenceScalar`, map-tab-v2-plan.md §3 P6),
so "aurora as a heat field" is *astro's own path with a different score source and a different
ramp*, not a new mode: the kernel's `opts.score` hook the spec leans on is never needed, because
this codebase filters unscored spots **before** construction and carries the star at `r[0]`
(the P1 review finding: a `null` score poisons every cell it touches). What is genuinely new is the
**ramp injection** — the in-repo kernel has no `opts.ramp` at all; every colour on the tab
(`heatField.js`, `PinsLayer`, `MapLabels`, `MapLegendPanel`, `MapPeekSheet`, the toolbar's ramp
key and the star-floor swatches) reads `utils/scoreRamp.js`'s **module state** (`temp`/`verdict`,
a persisted user preference) — and aurora must be an *event-type* override on top of that
preference, never a third persisted mode. The medallions are not clustered (clustering is gone,
owner decision — never back), there is no "heat/medallion toggle" (it is Heat|Pins, hidden in
aurora mode because `heatOffered` is false there), and the viewline's render gate is the night in
progress, not the calendar date.

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (~6 prosecutor lenses + one refuter per charge, all read-only) → fix survivors → browser
verification (§9) → commit. Frontend gate before any push request: `npm run lint && npm test &&
npm audit --audit-level=high && npm run build`, gated on **exit codes, never on a grep of the
output**. Never push; never tag. Every phase adds a `changelog.d/YYYYMMDD-<slug>.md` entry (never a
direct `CHANGELOG.md` edit). Paste the relevant section of THIS plan and the spec into every review
agent's prompt — review agents cannot see untracked context.

**Check open PRs first.** `gh pr list --state open` and grep the titles for *aurora*, *heat*,
*viewline*, *ramp*, *legend*. Checked 2026-10-11 at plan time: **no overlap** (#843 is a CLAUDE.md
facts PR; #815 is a measurement doc).

---

## §0 Status

**Status: A1 merged (#1097); A2 merged (#1100); A3 BUILT 2026-10-11 (PR pending); A4 merged (#1095); A5–A6 not started.** Phase log (every phase appends its own row in
the same commit as its code; the `PR` column names the PR, not a SHA, because the repo
squash-merges):

| phase | what | PR | notes |
|---|---|---|---|
| A0 | vendoring, this plan, the prompts, the viewline findings | — | this document |
| A1 | ramp + bloom injection in the kernel; `utils/auroraRamp.js` | #1097 | built 2026-10-11 on `feature/aurora-heat-a1`; `drawTiles → paint → field` already passed `opts` whole, so no threading was needed; byte-equality and both default-removal mutants verified |
| A2 | aurora mode paints the field (points, confidence, pins, chips) | #1100 | built 2026-10-11 on `feature/aurora-heat-a2`; field points follow D1; the aurora `<Marker>` loop and `getRatingForLocation` untouched (Q1 still open) |
| A3 | legend, toolbar key, peek-sheet swatch in aurora mode | pending | built 2026-10-11 on `feature/aurora-heat-a3`; `MapLegendPanel` `variant`/`viewlineShown` (one shared `viewlineShown` const in `MapView` for overlay and note); the toolbar ramp key was already done in A2; peek swatch keyed on `row.kind === EVENT_KIND.AURORA`; solar legend HTML pinned byte-identical |
| A4 | best-location card dismiss | #1095 | independent — may run beside A1; `bestAuroraDismissedKey` = `nightDate\|name`; focus to the window pill, else the first other pane control |
| A5 | viewline: 10 Oct reconstruction + owner answers | | **owner-gated** (§5) |
| A6 | viewline fix | | after A5 |

---

## §1 Corrections to the spec (what the code actually does)

Each of these was read off the code on 2026-10-11 at `7b2358df`. The *design* stands; these
change only what a session builds against.

1. **"In aurora mode the Map tab still draws medallion clusters"** — medallions yes, clusters no.
   Every `visibleLocations` entry gets a Leaflet `<Marker>` whose icon is `makeMarkerIcon` →
   `markerUtils.buildMarkerSvg`, a solid disc with "N★", coloured `rampHex(rating)`
   (`MapView.jsx:5926-6031`; arcs off, `markerDetail` false at `:5952`). Clustering was deleted
   2026-09-04 and is **never** coming back at any scale (CLAUDE.md *Locations*).
2. **"The heat/medallion toggle"** — the control is **Heat|Pins** (`wf-map-view-heat` /
   `wf-map-view-pins`, `MapView.jsx:6374-6398`) and in aurora mode it is **unmounted**, because
   `heatOffered = Boolean(heat?.enabled) && !isAuroraMode` (`:2830`). `'medallions'` survives
   only as a dead literal in the overlay branch (`:2835`).
3. **"`getRatingForLocation(loc)` for AURORA returns `stored ?? live ?? null`"** — the live
   fallback is gated on the night in progress: `storedAuroraResults[name]?.stars ??
   (liveAuroraOnScreen ? auroraScores[name]?.stars : null) ?? null` (`:3171-3201`). ⚠️ And the
   **medallions do not use that accessor**: they apply the *opposite* precedence — live-only on
   the night in progress, stored-only otherwise (`:5944-5948`) — and so does `bestAuroraLocation`
   (`:4004-4012`, live source only). §5 D1 picks one rule for the field.
4. **"`data-testid="aurora-best-location-card"` with `Centre map`"** — true (`:5544-5568`), but
   the card is a **page-level block above the map**, outside `MapContainer`, shown only when
   `isAuroraMode && bestAuroraLocation` (live night, best > 1★). No dismiss, no state.
5. **"The overlay only renders when `date === today` (`en-CA`)"** — false. The gate is
   `viewlineEnabled && eventType === 'AURORA' && liveAuroraOnScreen` where `liveAuroraOnScreen =
   nightDate === auroraNight` (`:1974`, `:5885`), the backend's own dusk→dawn night
   (`docs/engineering/aurora-night-selection.md`). The calendar-date gate was deliberately
   replaced; do not reintroduce it.
6. **"`useAuroraViewline` fetches `/viewline/forecast` once and never polls"** — it retries on the
   5-minute cadence **until a first answer lands**, then stops, and re-fetches whenever the key
   `forecast:${forecastKp}` changes (`hooks/useAuroraViewline.js:80-95`). The live key polls
   forever.
7. **The kernel.** The spec says "add `bloomRGB`; `opts.ramp` and `opts.score` already exist".
   In `frontend/src/utils/heatField.js`: `opts.score` exists (`:605`) but **no production caller
   uses it**; `opts.ramp` **does not exist** — colour is `rampRgb(s0)` from `scoreRamp.js` module
   state (`:35`, `:170`); the bloom colour is three literal assignments (`:202-204`,
   `bl.data[k]=255; …=138; …=66`), so grep for `255,138,66` finds nothing; the gate is
   `opts.bloomFrom ?? 3`, the strength `opts.bloomA ?? 190`. A1 adds both hooks.
8. **Heat constants differ from the bundle's.** `MapHeatLayer.jsx:74-79`: `HEAT_OPACITY` 0.92,
   `RADIUS_METRES` 7200, `RADIUS_MIN_PX` 30, `RADIUS_MAX_PX` 400, `GRID` 6, `BLUR` 4; handover
   `FADE_FROM` 10.4 / `FADE_TO` 12.0 / `HEAT_FLOOR` 0.35 (`utils/heatHandover.js:46-48`). The
   spec's `8500, 34, 240` and `10.6→12.2` are the prototype's. **Keep the repo's** (§4 #3).
9. **Bloom is only on in temperature mode** today: `...(getMode() === 'temp' ? { bloom:
   FIELD_BLOOM } : null)` (`MapHeatLayer.jsx` `paint`). The verdict mode has no bloom. Aurora
   needs bloom regardless of the user's colour-scale preference (§5 D3).
10. **Astro is the template, not solar.** `astroHeatPoints` (`MapView.jsx:2915-2926`) joins
    `heat.spots` to a name-keyed score object and skips non-finite scores;
    `astroConfidenceScalar` (`:2937-2940`) is `confidenceScalar(resolveConfidence(null,
    daysOut(nightDate, ukDateStr())))`; `MapHeatLayer` gets `conf={isAstroMode ?
    astroConfidenceScalar : heatWindow?.conf}` (`:5703`); `windowUnscored` for astro is derived
    from the point set (`:3007`). Aurora is a third arm of each of those, not a new path.
11. **The medallions already vanish under the field.** `MapHeatLayer.paint` calls
    `applyMarkerFade(map, 0)` on every paint and `restoreMarkerPanes` only on unmount
    (`MapHeatLayer.jsx:310-319`, `:497`, `:705`) — the doc there names aurora as the mode whose
    *unmount* restores them. Once the layer mounts in aurora mode, the markers are hidden by the
    existing mechanism; nothing about the marker loop needs to change for the field to win. (The
    `MapHeatLayer.test.jsx:613-623, 700-712` pins that say "keeps AURORA honest" must be
    re-worded, not deleted — they still pin the unmount restore, which the Plan overlay relies on.)
12. **`confidence` for a night row already exists on the EV list.** `mapEvents.nightRow`
    (`utils/mapEvents.js:292-333`) gives the aurora row `confidence: resolveConfidence(null,
    daysOut(date, todayStr))` — a capped-inference tier, never served. The spec's "use the night's
    forecast confidence if the API exposes one" — it does not; A2 re-derives the same scalar the
    astro arm does (§1 #10), not a read off the EV row (`findEvIndex` may not match).

## §2 What the codebase already has (do not rebuild)

- The kernel, its cull/bucketing/coverage clamp/confidence haze, `radiusFor`, the zoom handover,
  `MapHeatLayer`'s rAF-throttled paint and un-throttled `renderNow`, the coastline stroke, the
  land clip below z11.5, the reach rings — **all untouched** by this increment.
- `PinsLayer` (pane `wf-pins` z650; dots weakest-first; 26px named / 13px unnamed), `MapLabels`
  (chips), `MapLegendPanel` (ramp bar from `rampGradientCss()`, handover phase, note), the
  toolbar's ramp key `wf-map-heat-legend-ramp`, the phone `MapPeekSheet` Layers row.
- The aurora data: `storedAuroraResults` (per `nightDate`, `GET /api/aurora/forecast/results`,
  keyed `locationName`), `auroraScores` (`GET /api/aurora/locations`, keyed `location.name`,
  only while the level is MODERATE/STRONG), `liveAuroraOnScreen`, `auroraNight`, the auto-jump
  latch, the viewline hook and overlay. None change in A1–A4.
- `AuroraViewlineOverlay` draws in Leaflet's default overlayPane (z400) — **already above** the
  heat pane `wf-heat` (z350) and below markers (600) / pins (650). The spec's "z-index above 410"
  is satisfied by construction. No change.

## §3 Phases

### A1 — ramp and bloom injection in the kernel; the aurora ramp module

Pure utilities, no UI. Lands first because A2 and A3 both import it.

- `frontend/src/utils/auroraRamp.js` (new): `AURORA_STOPS = [[1,[56,66,98]], [2,[44,84,112]],
  [3,[34,134,126]], [4,[58,200,132]], [5,[118,255,160]]]` — port **exactly**; `auroraRampRgb(s)`
  (linear between stops, clamp 1–5), `auroraRampHex(s)`, `auroraRampGradientCss()` returning
  `linear-gradient(90deg,#384262,#2C5470 25%,#22867E 50%,#3AC884 75%,#76FFA0)`,
  `AURORA_BLOOM = { rgb: [120,255,170], from: 3.4, a: 150 }`, `AURORA_TOGGLE_SWATCH =
  'linear-gradient(90deg,#22867E,#76FFA0)'`. Export a `AURORA_RAMP` object `{ rgb, hex,
  gradientCss }` so consumers take one prop. ⚠️ Never a ramp colour as text (map-tab-v2 rule) —
  pick ink per fill for contrast with `windowFirstSpots.readableInkOn(fillHex)` (there is no `ink()` in `heatField.js`; that is the bundle's `HF.ink`).
- `frontend/src/utils/heatField.js`: `field()` takes `opts.ramp` (a `(s)=>[r,g,b]`, default the
  module's `rampRgb`) and `opts.bloomRGB` (default `[255,138,66]`), written through a local
  `bc`. **Nothing else in the kernel moves.** Threading: `drawTiles` → `paint` → `field` already
  pass `opts` whole.
- Tests (`frontend/src/test/`): `auroraRamp.test.js` — the five stops return their exact RGB,
  2.5 interpolates, 0 and 7 clamp, the gradient string is byte-exact, hex of each stop matches the
  spec table. `heatField.test.js` (extend) — **byte-equality**: a fixed point set rendered through
  `field()` with no `ramp`/`bloomRGB` and again with `ramp: rampRgb, bloomRGB: [255,138,66]`
  produce identical `ImageData` for both the field and the bloom canvases (jsdom canvas: use the
  existing test's mocking approach — see `MapHeatLayer.test.jsx`'s canvas stub — or assert on the
  `img.data`/`bl.data` arrays the function fills if the test already reaches them). A mutant that
  drops the `|| [255,138,66]` default must fail it.
- Changelog: `### Added — the heat kernel takes a ramp and a bloom colour (aurora heat, A1)`.

### A2 — aurora mode paints the field

The bulk of Task 1. Everything here is an **aurora arm beside the astro arm**.

- `MapView.jsx`:
  - `heatOffered = Boolean(heat?.enabled)` — drop `&& !isAuroraMode`; rewrite the javadoc
    (`:2817-2829`) — the "latitude-led smear" argument it makes is the one the spec answers
    (each location's *rating* already folds in latitude, cloud and darkness; the field paints
    ratings, and the viewline carries the latitude signal on top).
  - `auroraHeatPoints` (beside `astroHeatPoints`): join `heat.spots` by name to the aurora stars
    under **D1's rule**; skip non-finite; shape `{id, name, lat, lng, rid, r: [stars]}`. The point
    set is what `heatPoints` returns when `isAuroraMode` (`:2954` gets a third arm), what
    `windowUnscored` reads (`:3007` arm), and what the counts footer counts.
  - `auroraConfidenceScalar` — identical formula to `astroConfidenceScalar`, on `nightDate`.
    `conf={isAstroMode ? … : isAuroraMode ? auroraConfidenceScalar : heatWindow?.conf}`.
  - Hand `MapHeatLayer` `ramp={isAuroraMode ? AURORA_RAMP.rgb : null}` and `bloom={isAuroraMode ?
    AURORA_BLOOM : null}`; hand `PinsLayer` and `MapLabels` `rampHex={isAuroraMode ?
    AURORA_RAMP.hex : rampHex}`.
  - The existing aurora `<Marker>` loop stays as is (§1 #11 hides it under the field). ⚠️ The
    `MapCallout` for a selected location is unchanged; it does not draw ramp colour.
- `MapHeatLayer.jsx`: new optional props `ramp` (fn) and `bloom` (`{rgb, from, a}`); in `paint`,
  `...(bloom ? { bloom: FIELD_BLOOM, bloomFrom: bloom.from, bloomA: bloom.a, bloomRGB: bloom.rgb }
  : getMode() === 'temp' ? { bloom: FIELD_BLOOM } : null)` and `ramp` into the `drawTiles` opts.
  The repaint key already includes the ramp mode; add `ramp`/`bloom` identity to the deps.
  **PropTypes** for both.
- `PinsLayer.jsx` / `MapLabels.jsx`: optional `rampHex` prop defaulting to the module import (so
  every non-aurora caller is byte-identical); in `PinsLayer`, when the prop is the aurora one and
  `rating >= 4`, add `box-shadow: 0 0 14px rgba(r,g,b,.7)` (the glow) — pass a `glow` boolean
  rather than sniffing the function. Named pins keep **26px/13px** (§4 #3).
- Tests: rewrite `MapViewHeat.test.jsx:1100` ("paints no field and offers no toolbar in aurora
  mode") into the astro triplet's shape (`:1113-1160`): offers the toolbar; paints from that
  night's aurora stars (live night → `auroraScores`, stored night → `storedAuroraResults`, per
  D1); a location with no aurora rating is **absent** from the points (not 1★); hands the field
  its own capped-inference confidence, never null. Re-word (not delete) the two
  `MapHeatLayer.test.jsx` "keeps AURORA honest" comments. `MapViewAuroraLiveNight.test.jsx:178-205`
  ("medallion stars live vs stored") keeps passing because the markers still mount. A PinsLayer
  test: aurora `rampHex` + glow on 4★, none on 3★; default prop → unchanged output.
- Docs: CLAUDE.md's *Ask PhotoCast — the client* bullet says "An aurora window draws no chips (the
  heat field is not offered there) though the camera still fits", and
  `docs/engineering/map-tab-v2-plan.md` §3 item 18 says "an AURORA window unmounts the layer …
  aurora has no chips and no pins, so the medallions are the whole location vocabulary there" —
  both become false here; update both in the same commit, the plan item with a dated ⚠️ rather than
  a rewrite (the Ask pick chips in aurora mode follow §5 D4).
- Changelog: `### Changed — the Map tab paints an aurora night as a heat field, aurora green
  (aurora heat, A2)`.

### A3 — legend, toolbar key, peek sheet

- `MapLegendPanel.jsx`: optional `variant` prop (`'solar' | 'aurora'`, default solar). Aurora:
  label `COLOUR = AURORA RATING`; bar `background: auroraRampGradientCss()`, 9px, radius 999px,
  `box-shadow: 0 0 10px rgba(118,255,160,.25)`; ends `1★ dark sky, no show` · `3★` · `5★ aurora
  green`; note **"Rating, not Kp.** Each location's score already folds in latitude, cloud and
  sky darkness. The dashed line is where the aurora clears the northern horizon." (the "dashed
  line" clause is conditional on the viewline being mounted — read `viewlineShown` as a prop and
  drop the sentence when it is not, rather than describing a line that is not there). Handover
  phase text unchanged. ⚠️ The panel is `!isMobile`; the phone has no legend (map-mobile-sheet
  M1), so nothing to do there.
- Toolbar ramp key (`wf-map-heat-legend-ramp`, `MapView.jsx:6417-6421`): aurora →
  `AURORA_TOGGLE_SWATCH` and ends `1★` / `5★` (the "Poor"/"Worth it" words are solar verdict
  words and do not apply). The Heat button's swatch, if one is added, is the same gradient.
- `MapPeekSheet.jsx:346`: the Windows list swatch `rampHex(row.bestRating)` — for an **aurora**
  row use `auroraRampHex`; astro rows keep the shared ramp (they paint through it).
- The star-floor filter swatches (`MapView.jsx:5383-5386`) stay on the shared ramp: the filter is
  the reader's floor, the same control on every window; recolouring it per mode would make the
  same star look like two things. §4 #5.
- Tests: `MapLegendPanel.test.jsx` — aurora variant strings and bar style; default variant
  byte-identical to today (snapshot the rendered HTML before the change, assert it after).
  `MapPeekSheet` swatch per row kind.
- Changelog: `### Changed — the heat legend and ramp key describe the aurora ramp in aurora mode
  (aurora heat, A3)`.

### A4 — the best-location card's dismiss (Task 2)

Independent of A1–A3; a short phase, may run in parallel with A1 in its own worktree.

- `MapView.jsx:5544-5568`: a `✕` button after `Centre map`, `aria-label="Dismiss best aurora
  location"`, `data-testid="aurora-best-location-dismiss"`, 44×44 hit target, `text-indigo-300
  hover:text-indigo-100 hover:bg-indigo-800/40`. State `bestAuroraDismissedKey`
  (`useState(null)`), set to `` `${nightDate}|${bestAuroraLocation.name}` `` on click; the card
  renders only when the key differs. ⚠️ Key on **`nightDate`**, not `date` — the card is live-night
  only (`:4004-4012`) and `nightDate` is the night it speaks for (§1 #5). Session-only.
- Tests: `MapViewAuroraLiveNight.test.jsx` — dismiss hides it; a different best location shows it
  again; a different night shows it again; a re-render with the same best keeps it hidden.
  `MapViewAuroraLiveFetch.test.jsx` has eight references to the card; run it.
- Changelog: `### Added — the aurora best-location card can be dismissed (aurora heat, A4)`.

### A5 — viewline: reconstruct 10 Oct, answer the owner's questions (owner-gated)

The findings are written: `docs/engineering/aurora-viewline-investigation.md` §0–§4. What this
phase does is §5 (the owner runs the host grep; the session reads the output they paste and fills
§5 in with the reconstructed trigger/Kp/line per NOTIFY) and §6 (the session puts the nine
questions to the owner with `AskUserQuestion` and records the answers in §6 as decisions). **It
changes no code.** It ends with the investigation doc updated and §7 rewritten as the chosen
fix, committed.

### A6 — viewline fix (after A5)

Scope is whatever §6 decided. The shape the findings point at, for sizing only:

- Backend `NoaaSwpcClient`: replace `KP_LATITUDE_CAP`'s system (geomagnetic figures as
  geographic) with a per-longitude geomagnetic→geographic conversion, or a re-rowed table;
  interpolate on fractional Kp; a horizon allowance; `applyKpCap` either removed from the live
  path or using the corrected table; the forecast line derived from `maxKpRestOfTonight` per
  request rather than `lastTriggerKp`; an INFO line per served viewline. Tests named in §7 of the
  findings. `docs/pipeline-reference.html:1033-1050` ("Kp Latitude Hard Caps") updated.
- Frontend: `AuroraViewlineOverlay` label with source and age; recolour if D5 says the amber
  clashes with the green field (candidate: keep amber for forecast — it reads as *warning*, which
  "forecast" is — and change live to the ink colour `#F2E7D3` at 0.85 rather than green).
- `AuroraHotTopicStrategy.java:224` reads the **same table** via `getGlowLatitudeCap` for the Plan
  card's "visible as far south as…" line — it must move with it, or the Plan and the Map disagree.

## §4 Deliberate disagreements with the spec

1. **Ramp injection is a prop, not kernel-only, and not a mode.** The spec says "pass `opts.ramp`".
   Here every colour consumer reads `scoreRamp.js` module state (a persisted user preference,
   `map_colour_scale`), so an aurora ramp has to reach six consumers, and it must be an
   *event-type override* that leaves the preference alone: `MapView` chooses `AURORA_RAMP` when
   `isAuroraMode` and hands it down as props with defaults equal to today's imports. A third
   `setMode('aurora')` was rejected: it would persist, it would leak into the Plan overlay through
   module state, and `MapHeatLayer`'s own notes record why module-state colour already needs a
   repaint key.
2. **`opts.score` is not used.** The repo filters unscored spots before construction and carries
   the star at `r[0]` (P1 review finding; §1 #7, #10). Same outcome as the spec's "null omitted",
   stronger guarantee.
3. **Radius, grid, blur, handover and pin sizes are the repo's**, not the prototype's (§1 #8).
   They were tuned on this basemap and roster in map-tab-v2 P1–P10 and the spec itself says
   "everything else is unchanged".
4. **The bloom gate is 3.4 for aurora, as the spec asks — but the *shared* gate stays 3.** The
   kernel comment records why 3 is load-bearing for the temperature ramp (its luminance peaks at
   3★); the aurora ramp rises monotonically so a 3.4 gate has no dead band. Two gates, both
   justified, passed as opts; the kernel default is unchanged.
5. **The star-floor filter swatches stay on the shared ramp** (A3).
6. **The investigation file is `docs/engineering/aurora-viewline-investigation.md`**, not the
   spec's `docs/aurora-viewline-investigation.md` — every engineering doc lives under
   `docs/engineering/`.
7. **No `localStorage` for the dismiss** (spec: "only if the user asks"). Session-only.
8. **Unrated spots draw no pin and no chip in aurora mode** (2026-10-11, A2 review). The spec draws
   pins "same as other modes", where an unrated spot is a no-data grey dot. In aurora the 1★ fill
   (#384262) is 1.10:1 against that grey (`NO_DATA_COLOUR` #3A3D45), so a poor night and no data
   would be the same pixels — which the spec's own "no data is not 1★" rule forbids. `PinsLayer` and
   `MapLabels` take an `omitUnrated` prop (default false, byte-identical elsewhere) that `MapView`
   sets in aurora mode. Also in A2: the dark-sky toggle narrows the aurora field like a solar one;
   Ask pick pins keep the SHARED ramp (a pick belongs to its own solar window); the three colour
   keys read 1★ to 5★ in aurora, and the legend PANEL was withheld in aurora until A3 (since mounted with
   the aurora variant).

## §5 Decisions

Taken at plan time (2026-10-11) so sessions build rather than reopen:

- **D1 — which aurora star the field paints.** The **medallion rule**: on the night in progress
  (`liveAuroraOnScreen`) the live `auroraScores`, otherwise `storedAuroraResults` for `nightDate`
  — because the field *replaces* the medallions and `MapViewAuroraLiveNight.test.jsx:178-205`
  pins that rule as the tab's behaviour. `getRatingForLocation`'s stored-first rule is left alone
  (it feeds the callout and overlay popup) and the divergence is recorded here as **Q1** for the
  owner, not silently unified.
- **D2 — confidence.** Re-derived as the astro arm does (§1 #10, #12), never read off the EV row.
- **D3 — bloom in aurora mode regardless of the colour-scale preference** (§1 #9): the aurora
  ramp needs no bloom to rank, but the spec's glow is part of the design, and the preference is
  about the *solar* scale.
- **D4 — Ask pick chips in aurora mode.** With the field offered, `MapLabels` mounts and Ask's
  numbered picks would draw. Picks are solar windows only (a pick's window must be in the served
  `renderedEvents`, which are solar only), so an aurora window carries none; nothing to decide, but the CLAUDE.md sentence changes
  (A2).
- **D5 — the viewline's colour is an A5 question** (§6 #5 of the findings), not pre-decided.

Open for the owner (do not block A1–A4):

- **Q1** Should `getRatingForLocation`'s AURORA precedence (stored first) be made the medallion
  rule (live first on the live night), so the callout and the field agree on one night? A2 review
  widened this: the chips, pins, rating floor and counts also read the stored-first accessor, so on
  the live night with BOTH sources present the field (live first) and a chip's swatch (stored first)
  can differ. A2 refined D1 in one way only: on the live night a spot the live cache does not hold
  (it is empty below MODERATE) falls back to its stored result, so the field is never blank under
  rated chips.
- **Q2** Should the aurora field be offered to **LITE**? Today `auroraAvailable` is PRO/ADMIN
  (aurora rows are absent for LITE — map-tab-v2 O-9), so the question is moot until that changes;
  recorded so A2 does not accidentally widen it.
- **Q3** The aurora 3★ stop (#22867E) gives 4.39:1 against the best ink (white): just under the AA
  4.5:1 for the 11px bold pin numeral; every other stop and interpolated value passes. The spec says
  "port exactly"; a one-stop darkening (e.g. #1F7F78) would pass. Not blocking A2 — the owner
  decides (measured with the repo's `readableInkOn`).

## §6 Risks

- **Blast radius of the ramp props** — six consumers, all with defaults. The A1/A3
  byte-equality and snapshot tests are what make "no non-aurora pixel moved" a claim rather than
  a hope. A reviewer lens on *every* phase: diff the rendered output in a solar window before and
  after.
- **`MapHeatLayer`'s repaint key** reads `getMode()`; adding `ramp`/`bloom` to the deps without
  the key leaves a stale canvas when switching Aurora → Sunset in the window control. A2's test
  must switch windows and assert a repaint.
- **The medallions still mount under the field.** That is today's mechanism for every solar
  window too (§1 #11), so it is not new cost — but a session that "tidies" the marker loop out
  of aurora mode breaks the Plan overlay, which has no field.
- **Phone.** The field paints on phone (`MapHeatLayer` is not `!isMobile`-gated); the legend is.
  The peek sheet's Windows row swatch is the one phone surface A3 touches. Check at 390px.

## §7 Follow-ups named here, not built here

- **`POST /api/aurora/forecast/run` outlasts the Cloudflare tunnel's 100 s** on a storm night
  (one Claude call for 200+ locations, up to ~49k output tokens) — the modal reports "failed"
  while the backend completes minutes later. Needs the async 202-and-poll shape the
  cloud-verification backfill already uses. Found 2026-10-10; out of this increment's scope.
- The aurora poller's `lastTriggerKp` freezing within a level affects the **Plan card's** "visible
  as far south as" line (`AuroraHotTopicStrategy`) as well as the viewline — A6 moves both.
- O-16 (a served per-region night rollup) is unchanged by this: `mapEvents.bestOfNight` stays the
  licensed client max.

## §8 Vendoring log

`docs/design/aurora-heat/` — see its `VENDORING.md`. Copied 2026-10-11: `README.md`, `Map Aurora
Heat.html`, `aurora-map.js`, `heat-field.js`. Not copied: `plan-data.js` (byte-identical to
`docs/design/map-tab-v2/plan-data.js`).

## §9 Browser verification recipe

Backend `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local` (port 8083; use
`--server.port=8093` if 8083 is held), `npm run dev`, the **owner** signs in (a session cannot).
A local DB has no aurora results: use **Operations → Aurora → Simulate** (`POST
/api/aurora/admin/simulate`, STRONG) to put the alert ACTIVE and `POST /api/aurora/forecast/run`
for tonight to get stored rows (the simulation marks them `simulated=true`, filtered from every
read — so the field will paint from the *live* `auroraScores` the simulation's NOTIFY produces;
that is D1's live-night arm, which is the one that matters). Then: Map tab → window control →
the Aurora row; expect the green field, no medallions, the Legend reading *aurora rating*, the
viewline above the field; zoom past 12 for pins with the 4★+ glow; switch to Sunset and back and
confirm the ramp swaps both ways; 390px for the peek sheet's swatch. State which claims were
seen and which were tested.
