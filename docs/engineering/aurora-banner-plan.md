# Aurora alert banner — field thumbnail on desktop, glow strip on phone: implementation plan

**Source**: the design bundle vendored at `docs/design/aurora-banner/` — `README.md` (the spec) and
`Aurora Alert Banner.html` (the design reference). `docs/design/aurora-banner/VENDORING.md` records
what was copied and what was not. Read the spec before any phase; **this document is the *port*
plan**: what the codebase already has, what is genuinely new, where the spec and the code disagree
on purpose, and how the work cuts into single-session phases for **Sonnet** sessions. It follows
`docs/engineering/aurora-heat-plan.md` in shape, and depends on that series (A1–A6, all merged
2026-10-11) for the aurora ramp, the kernel's ramp injection and the corrected viewline.

**The one-sentence version.** The aurora alert banner escalates amber → red today, colours that
mean "marginal / stand down" everywhere else in PhotoCast; an aurora storm is good news, so
severity moves onto the aurora ramp, the banner gains a 96×118 thumbnail of tonight's aurora field
(Kp and a nine-step meter in the same slot until per-location scores exist), and on a phone it
collapses to a 56px glow strip.

**The one-paragraph correction.** The spec is right about the design and approximately right
about the code; §1 lists where it is not. The two that change the build: (1) the kernel's ramp and
bloom injection the spec says to "build first or confirm" **has landed** (`utils/heatField.js`
takes `opts.ramp`/`opts.bloomRGB`; the ramp is `utils/auroraRamp.js`, whose stop 3 is `#1F7F78`,
not the spec's `#22867E` — an owner decision, aurora-heat §5 Q3), and the geometry helpers the
spec calls `HeatField.bbox` live in `utils/heatGeometry.js`; (2) `AuroraBanner` is mounted in
`App.jsx` **outside** `WindowFirstBriefingProvider`, which is where the planning area
(`heatSpots`, `reachById`, `origin`) lives — so "framed to your planning area" and "ties → shorter
drive" need the banner brought inside that provider (§4 D1), not a new data path. Everything else
the thumbnail needs — lat/lon per rated location — already rides the two aurora score payloads.
No backend change anywhere: `hexColour` stays on the API, unread.

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (~6 prosecutor lenses + one refuter per charge, all **read-only**) → fix survivors →
commit → push → PR → CI green → Codex clean → squash-merge. Frontend gate before any push:
`npm run lint && npm test && npm audit --audit-level=high && npm run build`, gated on **exit codes,
never on a grep of the output**. Every phase adds a `changelog.d/YYYYMMDD-<slug>.md` entry (never a
direct `CHANGELOG.md` edit) and appends its own row to §0 in the same commit. Paste the relevant
section of THIS plan and the spec into every review agent's prompt — review agents cannot see
untracked context, and must never write to the working tree.

**Autonomy note (2026-10-12).** This series runs overnight on the owner's instruction, with no
owner interaction unless genuinely blocked. Decisions a session would normally ask about are taken
here in §4 and named as such; the one deliberately left to the owner is the viewline flag (§4 D4).
Sessions never tag and never run `release.sh`.

**Check open PRs first.** `gh pr list --state open` and grep the titles for *aurora*, *banner*,
*viewline*. Checked 2026-10-12 at plan time: **no overlap** (#843 is a CLAUDE.md facts PR; #815 a
measurement doc). The stale remote branch `claude/aurora-banner-inconsistency-b2c6a3` is the
already-merged #1056 work (topic dating), not this feature.

---

## §0 Status

**Status: B0 (this plan) open.** Phase log (every phase appends its own row in the same commit as
its code; the `PR` column names the PR, not a SHA, because the repo squash-merges):

| phase | what | PR | notes |
|---|---|---|---|
| B0 | vendoring, this plan | — | this document |
| B1 | severity on the aurora ramp; banner ≥640 with the Kp slot; glow strip <640; `breathe`; CSS block | pending | |
| B2 | `useAuroraScores`; the lazy field thumbnail; the best line; App brings the banner inside the briefing provider | pending | |
| B3 | viewline on the thumbnail behind `VIEWLINE_RELIABLE = false`; CLAUDE.md sweep; close-out | pending | |

---

## §1 Corrections to the spec (what the code actually does)

Read off the code on 2026-10-12 at `793c9e5d`. The *design* stands; these change only what a
session builds against.

1. **The kernel dependency has landed.** `frontend/src/utils/heatField.js` takes `opts.ramp` and
   `opts.bloomRGB` (aurora-heat A1, #1097). The ramp module is `frontend/src/utils/auroraRamp.js`:
   `AURORA_RAMP = { rgb, hex, gradientCss }`, `auroraRampRgb(score)`, `auroraRampHex(score)`,
   `auroraRampGradientCss()`, `AURORA_BLOOM = { rgb: [120,255,170], from: 3.4, a: 150 }`.
   ⚠️ **Stop 3 is `#1F7F78`, not the spec's `#22867E`** — an owner decision for AA contrast
   (aurora-heat §5 Q3). Every ramp colour this banner paints — the thumbnail, the meter bars, the
   strip's bottom edge — comes from that module, so the deviation is inherited, never re-typed.
   `HeatField.bbox` is `bbox(spots, padDeg)` in `utils/heatGeometry.js`; `drawGeo`, `load`, `land`
   are in `utils/heatField.js`. There is no `HeatField` namespace object to import.
2. **`useIsMobile` is the breakpoint.** `hooks/useIsMobile.js` is `(max-width: 639px)`, i.e.
   `< 640`, exactly the spec's switch. No `matchMedia` of its own.
3. **`useAuroraStatus()` returns `{ status, loading }`** from `AuroraStatusContext` (one fetch,
   5-minute poll). `status` also carries `currentNightDate` and `currentNightEndsAt`; "today" for
   a stored-results read is **`resolveAuroraNight(status)`** (`utils/mapDates.js`), which before
   nautical dawn is yesterday — never the civil date (CLAUDE.md, aurora bullet).
4. **Two score sources, both already fetched by `MapView`, neither shared.** Live:
   `getAuroraLocations({ maxBortle: 9, minStars: 1 })` (`api/auroraApi.js`) — no date parameter,
   the alert's own scoring held in `AuroraStateCache`, rows `{ location: { id, name, lat, lon,
   region, bortleClass, … }, stars, alertLevel, summary }`. Stored:
   `getAuroraForecastResults(night)` — rows `{ locationId, locationName, lat, lon, bortleClass,
   stars, triaged, triageReason, … }`, simulated rows already filtered server-side (V154). **Both
   carry lat/lon**, so the thumbnail's POINT SET needs no join to the roster; only its FRAME does
   (§4 D1). `MapView`'s own fetch effects carry per-night cancellation and retry semantics tuned
   over #803/#814 and are **not** refactored by this series (§6).
5. **There is no `nextScoringAt`.** Nothing on `AuroraStatusResponse` or any other payload names
   when the per-location run lands. The spec says omit the line when unknown, so the
   `Location scores due 19:40` line is **never drawn**. No backend change to add one.
6. **`AuroraGlyph` is a private function in `AuroraBanner.jsx`.** The strip lives in the same
   file, so nothing is exported.
7. **The banner is outside the briefing provider.** `App.jsx` mounts `<AuroraBanner>` at ~:654 in
   the `flex-shrink-0` banner block; `WindowFirstBriefingProvider` (heatSpots, reachById, origin,
   homePlace) wraps only `<main>`'s shell, inside `PlanErrorBoundary` (~:733). Calling
   `useWindowFirstBriefing()` outside the provider does not throw — the context default carries
   `heatSpots: EMPTY_ARRAY`, `reachById: EMPTY_REACH` — it just answers empty. §4 D1.
8. **`status.hexColour` has no other frontend reader** (grep 2026-10-12: `AuroraBanner.jsx` only).
   It stays on the API; the banner stops reading it. Backend untouched.
9. **A6 has landed, but the viewline flag stays false.** The spec's "open dependency" — the
   forecast line refreshing and the Kp table being corrected — is #1107 (merged, unreleased at plan
   time). The spec still says *ask the user before enabling*; §4 D4.
10. **The existing pulse rule IS the spec's `breathe` rule.** `isFavourable = !simulated &&
    triggerType !== 'forecast' && bz < -1` — "live and Bz < −1 nT, not simulated". Keep the
    predicate, replace the keyframes.
11. **`bzStatus()` has an importer outside the banner** (`SkyRatingEvalView.jsx`,
    `test/instantsAbroad.test.jsx`). It stays exported even though the banner no longer renders the
    sentence it builds. `formatDetectedAt` stays and is used by the "Active now · detected HH:MM"
    line.
12. **Styles.** The current banner injects three `<style>` strings from JSX. The v2 surfaces put
    their CSS in `frontend/src/index.css` as `.wf-*` blocks (the peek sheet, the tide strip, Ask),
    pinned by tests that read the stylesheet (`askCss.test.js`). The new banner does the same.
    Per-level colour reaches the CSS as two custom properties on the root element (`--acc`,
    `--acc-ink`), the pattern the current banner already uses for `--aurora-accent`.
13. **jsdom has no canvas.** `getContext('2d')` returns null; `WindowFirstHeatStrip.test.jsx`
    shows the pattern — `vi.mock('../utils/heatField.js', …)` for the kernel, a `getContext` stub
    where a context must exist, and the FIELD is never asserted pixel by pixel (the kernel's own
    tests do that).
14. **The coastline is in the repo.** `heatField.load()` imports `assets/uk-land-50m.json` and
    resolves the shared `LAND`; `drawGeo` returns null until it has. No `world-atlas` fetch.
15. **The CTA routes exist.** `onViewOnMap` (from `App.handleAuroraViewOnMap`) opens the Map tab on
    the aurora night; simulated → `window.location.hash = 'manage'` (a documented no-op since v1
    retirement D1, kept and named). The strip's `Map →` and the card's whole-surface click take the
    same handler.
16. **What the redesign deletes from the current banner**: the Kp meta line (`Kp 6 forecast
    *tonight*`), the Bz sentence (`✅ Bz south (−9.2 nT) — favourable for aurora`), the live
    viewline sentence (`🌌 …`) and its `useAuroraViewline` call, the roundel, the rail and the
    corner glow. What survives: the overcast gate and its copy, dismiss per level with re-show on
    escalation, `role="alert"`, keyboard activation, the simulated hatch and dashed border.

---

## §2 What the codebase already has (do not rebuild)

- `utils/auroraRamp.js` — the ramp, the bloom constants, the gradient string (§1 #1).
- `utils/heatField.js` `drawGeo(cv, w, h, spots, win, opts)` — host A, static canvas clipped to the
  coastline, with `fit`, `sea`, `plate`, `stroke`, `line`, `radius`, `grid`, `blur`, `bloom*`,
  `ramp`. `load()` for the land chunk; `land()` to tell "not loaded" from "declined".
  `fit(cv, w, h)` handles device pixel ratio. Returns the projection, which the viewline needs.
- `utils/heatGeometry.js` `bbox(spots, padDeg)`.
- `utils/planningArea.js` — the planning area (`areaSpots`, `regionDriveMinutes`) — and
  `utils/planOrigin.js`'s `scopeSpots(heatSpots, reachById, origin)`, the area at home and the
  origin's own region when away; `WindowFirstMapPane.jsx` ~:461 is the Map tab's own framing call.
- `context/WindowFirstBriefingContext.jsx` → `useWindowFirstBriefing()` — `heatSpots` (the
  catalogue with lat/lng/regionName/id), `reachById` (HOME drive minutes by location id),
  `origin`.
- `hooks/useIsMobile.js`, `hooks/useAuroraStatus.js`, `hooks/useAuroraViewline.js`
  (`useAuroraViewline(enabled, { includeForecast })` → `{ viewline, source }`; the response carries
  `southernmostLatitude`, `kp`, `asOf`).
- `api/auroraApi.js` — `getAuroraLocations`, `getAuroraForecastResults`.
- `utils/mapDates.js` `resolveAuroraNight(status)`.
- `components/AuroraBanner.jsx` — `formatDetectedAt`, `bzStatus`, `AuroraGlyph`, the overcast
  gate, dismiss state, activation handlers; `test/AuroraBanner.test.jsx` (809 lines) covers them.
- `utils/windowFirstSpots.readableInkOn(fillHex)` — if any ink ever has to be derived from a fill.
  This design needs none: the ink column is fixed per level.

---

## §3 Phases

Each phase is one Sonnet session in its own worktree, one PR. B1 → B2 → B3 are sequential (one
file). Each session's first act is reading §0 and `gh pr list --state open`.

### B1 — severity on the aurora ramp; the banner at ≥640 with the Kp slot; the glow strip at <640

Pure presentation over `status`. **No new fetch, no new hook, no App change.** After B1 the
thumbnail slot ALWAYS shows the pending state (Kp + meter), because nothing yet knows whether
scores exist; B2 swaps the field in.

- `components/AuroraBanner.jsx`:
  - Severity from `status.level`, never `hexColour`: `MODERATE → { acc: '#3AC884', ink: '#8CE6B6',
    fill: 62 }`, `STRONG | SEVERE | EXTREME → { acc: '#76FFA0', ink: '#A8FFC4', fill: 92 }`. Export
    the table (`SEVERITY`) so tests read the same constants. `ALERT_WORTHY` stays
    `MODERATE|STRONG` (the API emits those two; SEVERE/EXTREME map onto STRONG's row if they ever
    arrive).
  - `useIsMobile()` picks the layout: `<AuroraStrip>` below 640, `<AuroraCard>` at 640 and up. Both
    are inner components in the same file, both `role="alert"`, `tabIndex={0}`, whole surface
    clickable with Enter/Space, dismiss button `stopPropagation`. `data-testid="aurora-banner"` on
    the root of EITHER layout, `data-layout="card"|"strip"`.
  - **Card** (`.wf-aurora-card`): thumbnail slot `.wf-aurora-thumb` (96 wide, 118 tall, radius 8,
    `overflow:hidden`, bg `#13100e` = `var(--color-heat-sea)`, inset 1px border) holding, in B1,
    the **pending** state `.wf-aurora-thumb-pend`: `Kp N` (mono 22px 700, ink, text-shadow
    accent 50%), the vertical nine-bar meter (`34×4`, gap 2, radius 1.5, `flex-direction:
    column-reverse`; bar `k` lit when `k < round(displayKp)`, colour `auroraRampHex(1 + 4·k/8)`,
    bars `k ≥ 6` add `box-shadow: 0 0 5px <same>`; unlit `rgba(242,231,211,.08)`), then
    `SCORING / LOCATIONS` (mono 8.5px, .06em, uppercase, muted, two lines). Simulated: the slot
    holds a 🧪 (centred, ~28px) instead. `displayKp = status.forecastKp ?? status.kp`, rounded; with
    no Kp at all the slot shows the meter unlit and no figure.
  - Card body: pill (`.wf-aurora-pill`: leading 6px dot in accent with `0 0 8px` glow; text
    `Moderate · G2` from `levelWord` + `status.gScale` when present, `Moderate · simulated` when
    simulated); when-line (`Forecast · tonight` when `triggerType === 'forecast'`, else `Active now`
    plus ` · detected HH:MM` from `formatDetectedAt` when it answers, `Simulated` when simulated);
    title `status.description` (15px 700, 1.3, ink `#F2E7D3`); **best line** in its B1 form —
    pending: `<b>{N}</b> dark-sky sites clear` where N is `clearLocationCount` (fall back to
    `<b>{darkSkyLocationCount}</b> dark-sky sites` when triage has not run, exactly the current
    branch), with ` · Bz <b>−9.2 nT</b>` appended when live and `bzNanoTesla != null`
    (`toFixed(1)`, a true minus sign U+2212, never a hyphen); all-overcast replaces the whole line
    with the existing copy `All locations overcast — no clear skies forecast tonight`
    (`data-testid="aurora-banner-overcast"` kept); CTA `View on map →` / `Generate scores →`
    (12px 600 ink).
  - Dismiss: 44×44, `margin: -4px -8px 0 0`, `✕` 16px secondary, hover `rgba(255,255,255,.06)`,
    `aria-label="Dismiss aurora banner"`, `data-testid="aurora-banner-dismiss"`. Dismissed per
    level; escalation re-shows (unchanged logic).
  - **Strip** (`.wf-aurora-strip`): `min-height:56px; padding:0 6px 0 12px; display:flex;
    align-items:center; gap:11px; overflow:hidden; position:relative`, `--fill: 62%|92%`; `::after`
    2px bottom edge `background: auroraRampGradientCss()` (so it carries the `#1F7F78` stop — put
    the gradient in the CSS block as a literal copied from that function's return, and pin equality
    in the CSS test), `clip-path: inset(0 calc(100% - var(--fill)) 0 0)`, `box-shadow: 0 0 10px
    var(--acc)`. Glyph 22px with `filter: drop-shadow(0 0 6px var(--acc))` (🧪 when simulated).
    Line 1: level word (mono 10px 600 uppercase ink) + `Aurora tonight` / `Aurora active now` /
    `Aurora simulated`, 13.5px 700, single line, ellipsis. Line 2 (mono 11px muted, ellipsis):
    `<b>Kp N</b> · {N} dark sites clear`; with `clearLocationCount === 0` on a STRONG alert it reads
    `<b>Kp N</b> · all sites overcast` (§4 D12). `Map →` (mono 12px 600 ink). Dismiss 44×44, no
    negative margin. No Bz, no overcast sentence, no viewline.
  - **`breathe`**: class `wf-aurora-breathe` on either layout's root when `isFavourable` (§1 #10).
    Keyframes exactly the spec's, 3.2s ease-in-out infinite, `@media (prefers-reduced-motion:
    reduce) { animation: none }`. Delete `PULSE_STYLE`.
  - Simulated keeps the hatch + dashed border, now as `.wf-aurora-simulated` in the CSS block
    (delete `SIM_BORDER_STYLE`). Delete `AURORA_A_STYLE`, the rail, the glow, the roundel.
  - Remove the `useAuroraViewline` call and the `aurora-banner-viewline` paragraph (B3 brings the
    hook back behind the flag, for the thumbnail only). Remove the `aurora-banner-bz` paragraph
    and the Kp meta line. Keep `bzStatus` exported.
- `index.css`: one new block, headed `/* ── Aurora alert banner (docs/engineering/
  aurora-banner-plan.md B1) ── */`, holding every `.wf-aurora-*` rule, the keyframes and the
  reduced-motion rule. Fonts via `var(--font-mono)`/`var(--font-sans)`; surface, border and ink via
  the `--color-plex-*` tokens where they are byte-equal to the spec (`#221A15`, `#3A2C23`,
  `#F2E7D3`, the .66/.42 inks — they are); `#13100e` via `var(--color-heat-sea)`.
- Tests. Rewrite `test/AuroraBanner.test.jsx` around the new layout (mock `useIsMobile` as
  `FiltersPopover.test.jsx` does, default desktop): MODERATE sets `--acc` `#3AC884` and STRONG
  `#76FFA0` **with a contradicting `hexColour`** in the fixture so the test fails if the API colour
  is read; the pill text with and without `gScale`; the when-line in all three states; the pending
  slot shows `Kp 6` and exactly 6 lit bars of 9, bars 6–8 unlit, the glow only on lit bars ≥ 6;
  Kp null → no figure; `breathe` only when live and Bz < −1 and not simulated (four cases); the
  strip at 639px and the card at 640px (`data-layout`); strip line 2 pending and all-overcast
  forms; every surviving behaviour test (gates, overcast, dismiss, escalation, keyboard, hash
  fallback, `formatDetectedAt`, `bzStatus`) kept and green; the deleted lines asserted ABSENT
  (`aurora-banner-bz`, `aurora-banner-viewline`, "forecast tonight"). New
  `test/auroraBannerCss.test.js` reading `index.css`: the keyframes exist once, the reduced-motion
  rule names `.wf-aurora-breathe`, the strip's gradient literal equals
  `auroraRampGradientCss()`, no `aurora-pulse` anywhere in `src/`.
- Changelog: `### Changed — the aurora alert banner escalates on the aurora ramp, shows Kp on a
  nine-step meter, and collapses to a glow strip on a phone (aurora banner, B1)`.

### B2 — `useAuroraScores`, the lazy field thumbnail, the best line, and the provider lift

- `hooks/useAuroraScores.js` (new): `useAuroraScores(status)` → `{ ready: boolean, spots:
  Array<{ id, name, lat, lng, rid, stars }> }`. Gated exactly as `MapView`'s live fetch: nothing is
  asked unless `status` is alert-worthy and not simulated. Asks BOTH sources in parallel —
  `getAuroraLocations({ maxBortle: 9, minStars: 1 })` and
  `getAuroraForecastResults(resolveAuroraNight(status))` — and publishes **live when it answered
  with ≥ 1 rated row, else stored** (the Map's D1 rule, aurora-heat §5 D1; §4 D2 here). A row is
  rated when `stars` is a finite integer 1–5 and (stored) `triaged !== true`. Live rows map
  `location.{id,name,lat,lon}` → `{ id, name, lat, lng, rid: name, stars }`; stored rows map
  `locationId/locationName/lat/lon`. `rid` is the location name: the thumbnail uses no `focus` and
  draws no region labels, and the kernel only needs it truthy. Re-asked when `status` changes
  (every 5-minute poll, as `MapView` does); a late response is dropped (`cancelled` flag); **a
  failure publishes nothing** — the banner stays on the pending slot, never "no scores".
  `ready === spots.length > 0`.
- `components/AuroraFieldThumb.jsx` (new, **loaded with `React.lazy`** from the banner): takes
  `spots`, `fit`, draws a 96×118 canvas through `drawGeo(cv, 96, 118, points, 0, { fit, ramp:
  AURORA_RAMP.rgb, radius: 7, grid: 3, blur: 2.2, bloom: true, bloomFrom: AURORA_BLOOM.from,
  bloomRGB: AURORA_BLOOM.rgb, bloomA: 140, bloomBlur: 1.6 })` with `points = spots.map(s => ({ lat,
  lng, rid, r: [s.stars] }))`. Calls `load()` first and draws when it resolves; a rejection, a null
  projection or a null `getContext` leaves the canvas blank over the slot's `#13100e` ground — the
  slot never changes size, so there is no layout shift. Canvas `aria-hidden`; the best line is the
  accessible answer. Exposes the projection it drew with through an `onProjection` callback (B3
  needs it for the viewline). `radius`, `grid`, `blur` and the bloom dials are the spec's — this is
  a new host, not a retune of an existing one.
  ⚠️ Why lazy: `heatField.js` pulls `d3-geo` and the land chunk; `App.jsx` imports the banner on
  every tab, and `WindowFirstMapPane.jsx`'s own note records that a static kernel import on a
  shared surface puts `d3-geo` on the network for readers who never see it. The `Suspense`
  fallback IS the pending slot, so the swap is invisible in layout.
- `components/AuroraBanner.jsx`: `useWindowFirstBriefing()` for `heatSpots`, `reachById`,
  `origin`; `fit = bbox(scopeSpots(heatSpots, reachById, origin), 0.25)` (`scopeSpots` from
  `utils/planOrigin.js`) when that set is
  non-empty, else `bbox(spots, 0.25)` over the rated spots (§4 D8). `ready` swaps the pending slot
  for `<Suspense fallback={<PendingSlot/>}><AuroraFieldThumb …/></Suspense>`. **Best line, ready
  form**: `Best: <b>{name}</b> <span class="s">{stars}★</span> · {N} sites clear` (+ Bz when live),
  where best = max `stars`, ties → shorter HOME drive (`reachById.get(id)?.driveMinutes`, unknown
  last), then name A→Z (§4 D3). Strip line 2 ready form: `<b>Kp 8</b> · best {name} {stars}★`.
  Simulated never asks for scores and never shows the field (🧪).
- `App.jsx`: bring the banner block inside the provider — `PlanErrorBoundary` → 
  `WindowFirstBriefingProvider` now wrap BOTH the `flex-shrink-0` banner block and `<main>`, in
  that order, with `AskWhenLive` staying where it is around the shell. Neither adds a DOM node, so
  the Map tab's flex column (`flex-shrink-0` block + `flex-1` main as direct children of the root)
  is unchanged — pin that in a test. Update the two comments that describe the structure
  (the "provider is mounted INSIDE the boundary" note still holds; the "banner block is outside"
  state no longer exists). §4 D1 records the crash-semantics change.
- Tests: `useAuroraScores.test.jsx` (not asked when QUIET / simulated / null; live wins over
  stored when both answer; stored serves when live is empty; a triaged stored row is not a spot;
  a late response for a superseded status is dropped — awaited `act`, see
  `frontend-test-standards.md` "A late response"; a rejection publishes nothing and `ready` stays
  false; refetch on status change). `AuroraFieldThumb.test.jsx` with `heatField.js` mocked:
  `drawGeo` receives 96, 118, the mapped points, `fit`, `AURORA_RAMP.rgb` and `AURORA_BLOOM.rgb`;
  `load` rejection → no throw, no draw; the canvas is `aria-hidden`. `AuroraBanner.test.jsx`:
  ready renders the lazy thumb (await it) and the `Best:` line with the tie rule (two 5★, the
  nearer wins; unknown drive loses to a measured one); pending slot before scores; the slot's
  CSS size identical in both states; LITE never reaches here (status null). `App.test.jsx` /
  `AppSettingsRoutes.test.jsx` green; one new assertion that the banner block is a direct child of
  the root alongside `<main>`. Build check: after `npm run build`, `grep -l "geoMercator" dist/
  assets/index-*.js` finds nothing (the kernel is in a lazy chunk, not the entry).
- Changelog: `### Added — the aurora banner shows tonight's aurora field and the best location
  once per-location scores exist (aurora banner, B2)`.

### B3 — the viewline on the thumbnail, behind a flag; docs sweep

- `components/AuroraBanner.jsx`: `export const VIEWLINE_RELIABLE = false;` with a comment naming
  §4 D4 and the owner question. `useAuroraViewline(viewlineEnabled && VIEWLINE_RELIABLE)` (both
  sources — live first, forecast else — the hook's default), so with the flag false **no viewline
  request is made at all**. Pass `viewline` to the thumb.
- `components/AuroraFieldThumb.jsx`: takes `viewline`; after drawing, when `viewline?.active` and
  a projection exists: `y0 = projection([-2, viewline.southernmostLatitude])[1]`,
  `y = min(y0, 116)`; dashed `#E0A542`, 1.2px, `setLineDash([4,3])`, horizontal across the 96px.
  Label (DOM, not canvas, so it is text): `Kp {round(kp)} line`, or `Kp {kp} · all ↓` when
  `y0 > 116`; absolute `top:4px; left:5px`, mono 8.5px 600 `#E0A542`, `white-space:nowrap`,
  text-shadow `0 1px 3px #000`; `kp` from the viewline response (`viewline.kp`, nullable) falling
  back to the banner's `displayKp`. ⚠️ `-2` is `UK_REFERENCE_LONGITUDE_DEG`'s value — the
  FORECAST response's `southernmostLatitude` is already evaluated at that longitude (CLAUDE.md,
  A6), so a horizontal line at that latitude is the honest reading; the LIVE response's figure is
  the contour's true minimum (west end), which this horizontal line slightly overstates
  southward — stated in the component's comment, accepted (the Map draws the real curve).
- Tests: flag false → `getAuroraViewline` never called and no `.wf-aurora-vk` label; with the flag
  true (test via a prop the banner threads, default `VIEWLINE_RELIABLE`, so the constant itself is
  not mutated in tests) the label reads `Kp 6 line` for a latitude inside the frame and
  `Kp 8 · all ↓` when the projected y exceeds 116; canvas draw calls asserted on the stubbed
  context (`setLineDash([4,3])`, `moveTo(0,y)`, `lineTo(96,y)`).
- Docs: CLAUDE.md's aurora bullet — replace `` `AuroraBanner` React component (shows Kp reading) ``
  with a sentence naming the field thumbnail, the Kp meter, the strip, the ramp-driven severity,
  `hexColour` unread, and `VIEWLINE_RELIABLE` as the owner's flag; `AGENTS.md` if it names the
  banner (it does not at plan time — check). §0 final row; `aurora-heat-plan.md` §7 gains a
  pointer here if it lists the banner as a follow-up.
- Changelog: `### Added — the aurora banner's thumbnail can carry the southern viewline, off until
  the owner enables it (aurora banner, B3)`.

---

## §4 Deliberate disagreements with the spec, and the decisions taken

1. **D1 — the banner comes inside the briefing provider; the provider does not move out of its
   boundary.** The spec's frame rule ("planning area, same rule as Map tab") and tie rule ("shorter
   drive") both read per-user data that lives only in `WindowFirstBriefingProvider`
   (`heatSpots`, `reachById`, `origin`). Three routes were weighed: (a) frame the rated spots alone
   — rejected, because aurora rates highest in Scotland, so a rated Skye would push the reader's
   own area to three pixels at the bottom of a 96×118 frame, the exact failure `planningArea.js`
   exists to prevent; (b) lift the provider above `PlanErrorBoundary` — rejected, `App.jsx`
   records that the provider is inside the boundary so a provider crash lands on the same fallback
   as a shell crash; (c) **chosen**: move `PlanErrorBoundary` → `WindowFirstBriefingProvider` up
   one level so they wrap the banner block AND `<main>`. Neither renders a DOM node, so the Map
   tab's flex column is byte-identical. The one behaviour change, stated: a shell or provider crash
   now also removes the banner block (session-expiry, rewind pill, run-complete, backend-down)
   behind the boundary's fallback, which carries its own Sign out. Accepted — a crashed page with
   a session-expiry banner above the fallback was not a state anyone relied on.
2. **D2 — live first, stored else, on the night in progress.** The spec says "scores ready" is
   either endpoint returning ≥ 1 rated location. Where both answer, the banner is ABOUT the live
   alert, so the live scoring wins (the heat field's D1 rule on the Map, aurora-heat §5). The
   stored read is for `resolveAuroraNight(status)` — never the civil date.
3. **D3 — the tie rule's drive is HOME.** `reachById`, never `effectiveReachById`: an away origin
   would make "best" a statement about Keswick (the `AskContext` rule). Unknown drive sorts last;
   then name.
4. **D4 — `VIEWLINE_RELIABLE = false` ships; flipping it is the owner's call.** A6 corrected the
   line, but the spec says "ask the user before enabling" and the owner is not present for this
   series. The code and tests for the line are built behind the flag so the flip is one constant.
   ⚠️ Open owner question — the morning report names it.
5. **D5 — CSS in `index.css`, not `<style>` strings.** §1 #12. The custom properties `--acc`,
   `--acc-ink`, `--fill` are set inline on the root element per render, the existing pattern.
6. **D6 — the thumbnail is a lazy chunk**, with the pending slot as its `Suspense` fallback. §3 B2.
7. **D7 — copy follows the spec where they differ**: `147 dark-sky sites clear` (pending) and
   `147 sites clear` (ready) replace `147 dark-sky locations clear`. Singular forms: `1 dark-sky
   site clear` / `1 site clear`. The overcast sentence is unchanged (the spec keeps it).
8. **D8 — frame fallback.** `scopeSpots(heatSpots, reachById, origin)` is empty before the scores
   payload has loaded (the provider's `heatSpots` is a join with the scores) and whenever the
   roster is unknown; then the frame is the rated spots' own padded box rather than no thumbnail.
   The rated-spots frame is the degrade path, never the rule.
9. **D9 — a simulated alert asks for nothing.** No score fetch, no viewline fetch, 🧪 in the
   slot, `Generate scores →` to `#manage` — the admin preview tool must cost nothing and show
   nothing real (CLAUDE.md, simulation isolation).
10. **D10 — the overcast gate is unchanged**: MODERATE + all overcast → not rendered; STRONG + all
    overcast → rendered with the overcast sentence in the best line's place; unknown clear count →
    fail open.
11. **D11 — dismiss state stays in memory**, per level, re-show on escalation. The spec's "keep
    existing rule".
12. **D12 — the strip's line 2 under full overcast reads `Kp 8 · all sites overcast`.** The spec
    puts the overcast note on the Map tab only, but `{0} dark sites clear` is a worse sentence than
    the truth. Decided here; one phrase.
13. **D13 — `SEVERE`/`EXTREME` take STRONG's row.** `ALERT_WORTHY` is unchanged (`MODERATE`,
    `STRONG`); the table carries the two extra keys so the mapping is one lookup if the API ever
    emits them, as the spec's table says.
14. **D14 — nothing moves in the backend.** `hexColour` stays on `AuroraStatusResponse`; no
    `nextScoringAt`; no viewline change. A frontend-only series, no migration, no `job_run`.

---

## §5 Risks

- **The provider lift (B2) touches `App.jsx`'s root.** Mitigation: both wrappers are DOM-free;
  `App.test.jsx` and `AppSettingsRoutes.test.jsx` run; a new assertion pins the root's direct
  children. If a route into settings (CLAUDE.md's five) regresses, the test counts the modals.
- **Two extra requests per poll while an alert is active**, PRO/ADMIN only, cached nowhere. The
  Map tab already makes the same two. Acceptable; a shared hook for both surfaces is §6.
- **`role="alert"` on a card whose content changes** (pending → ready) re-announces once. The
  canvas is `aria-hidden`; the announced text is the best line. Reduced motion disables
  `breathe`.
- **Phone**: the strip replaces the card entirely below 640px; `useIsMobile` is the one gate. The
  phone shell's banner slot is the same `max-w-4xl` block, so the 56px strip sits above the tabs.
  Check at 390px in the browser recipe (§8).
- **The `#1F7F78` stop.** The spec's gradient literal has `#22867E`; the module's has `#1F7F78`.
  Any literal copied from the spec into CSS re-introduces the AA failure — B1's CSS test pins the
  strip gradient to `auroraRampGradientCss()`.
- **`drawGeo` declines silently** (no land yet, null context). The thumb must stay on the ground
  colour, not throw, and the pending slot must not flash back in — `ready` is the hook's answer,
  not the draw's.

---

## §6 Follow-ups named here, not built here

- A shared `useAuroraScores` for `MapView` as well — the spec suggests it; `MapView`'s per-night
  cancellation and retry semantics (#803, #814) make that a refactor with its own review, so the
  banner's hook is banner-only.
- `nextScoringAt` on `AuroraStatusResponse`, if the owner wants the "Location scores due" line.
- Flipping `VIEWLINE_RELIABLE` (owner, D4).
- The simulated CTA's `#manage` hash is a documented no-op (v1 retirement D1 §8.11) — unchanged.

---

## §7 Vendoring log

`docs/design/aurora-banner/README.md` and `Aurora Alert Banner.html` copied verbatim from
`PhotoCast Aurora Alert Banner.zip` on 2026-10-12. The bundle's `heat-field.js` is byte-identical to
`docs/design/aurora-heat/heat-field.js` and its `plan-data.js` to
`docs/design/map-tab-v2/plan-data.js` (both verified with `diff`), so neither is copied again. See
`docs/design/aurora-banner/VENDORING.md`.

---

## §8 Browser verification recipe (owner)

A session cannot sign in, so every phase states "tested, not seen". For the owner: backend
`./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local` (port 8083; `--server.port=8093`
if held), `npm run dev`, sign in. **Operations → Aurora → Simulate** (STRONG) puts the alert ACTIVE:
expect the card with the 🧪 slot, the dashed hatched border and `Generate scores →` (a simulated
alert never shows the field). For the real shape there is no local substitute for NOAA — on a
production night with an alert, expect: the card at ≥640px with the pending Kp meter until the
per-location run lands, then the green field framed to your planning area and `Best: … N★ · N
sites clear`; the strip at 390px with the ramp edge filled to 62% (Moderate) or 92% (Strong); the
Strong card breathing only while live with Bz south; Escape does nothing (not a dialog), ✕
dismisses, the whole card opens the Map tab on the aurora night.
