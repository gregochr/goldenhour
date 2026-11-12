# Weather stand-downs count in the heat field — plan

Source: design handoff `design_handoff_stood_down_field` (2026-11-12). One PR, two parts. Frontend only.

## The defect

Sunday 15 Nov, sunrise, within 45 min: 25 places, 6 rated (best 4★), 19 stood down by the weather. The card
says `Too few · 6/25 rated`, but the field thumbnail is solid amber. `heatPointsFor` emits a point only for a
finite score, so a stood-down place adds nothing to the blend; the 6 rated places decide the colour and the
coverage clamp makes it opaque. The stand-down hatch is drawn over the amber and does not change it. The
confidence haze (`conf`) answers "how far ahead", not "how many were judged", so it cannot catch this.

## Decisions

**D1 — no backend field; the colour reads the list the hatch already draws (handoff option a).** The handoff
preferred a served `weatherStandDown` boolean decided before Lite reshaping. Plan review rejected it:

- It would leak the fact `TriageReason.forLite` exists to hide (tide-light-pro-plan A9): a Lite row with
  `triageReason: GENERIC` and no flag could only be a hidden `TIDE_MISALIGNED`.
- The case it guards no longer arises: tide triage was deleted in #1483 (`WeatherTriageEvaluator` emits only
  `HIGH_CLOUD`, `PRECIPITATION`, `LOW_VISIBILITY`); `TIDE_MISALIGNED` exists only on historical rows, and
  `GENERIC` comes only from the V96 sentinel backfill, which is weather.

So the population is exactly `weatherStoodOf(stood)` (`utils/mapStandDown.js`): every stand-down except
`TIDE_MISALIGNED`, the same predicate, on the same list, that the stand-down hatch is drawn from. Hatch and
colour therefore agree for every reader, Lite included (a Lite reader's GENERIC row is hatched as weather and
now also blends at 1★). This is a licensed client derivation (a drawing choice, like the ramp stops):
record it in `architecture-decisions.md`'s Backend-heavy bullet.

**D2 — the 1★ points reach the kernel through a NEW kernel option, never through the `spots` argument and
never into `heatPointSets`.**

- Not through `spots`: `drawGeo`/`drawTiles` hand the same projected `pts` to `ratedMaskPoints(pts)` for the
  unrated hatch and the stand-down hatch (`heatField.js` ~L1203, ~L1212; `drawTiles` ~L1272, ~L1280/1309).
  A 1★ point counts as "rated" there, so the stand-down hatch would vanish at every stood place and the
  unrated hatch near them would be suppressed.
- Not into `heatPointSets` / `heatPointsFor`: they have readers that count or rank (`mapEvents.pastWindowBest`,
  the region panel's top four in `mapDrilldown`, the popup's `points`, `MapView`'s `pointsByKey`).

Kernel (`utils/heatField.js`): a new option, `opts.stoodField` (an array of `{lng, lat, rid}`), projected and
appended to the list `paint()` receives (and only that list) as points scored `STOOD_FIELD_SCORE`, in both
`drawGeo` and `drawTiles`. Absent or empty → the options and the paint input are exactly today's. Check
`field()`'s early `null` return (no contributing points) is fed the combined list, so a window with only
weather stand-downs now paints cool ground (intended: say so in the doc). The bloom gate is 3★ so 1★ adds no
glow. Focus weighting applies to the stood points by their `rid` like any point.

`utils/heatSpots.js`: `export const STOOD_FIELD_SCORE = 1;` plus one producer every host uses:

```js
/** The stood places a window's field blends at STOOD_FIELD_SCORE: weatherStoodOf(stood). */
export function stoodFieldPointsFor(stood)
```

(If importing `mapStandDown.js` into `heatSpots.js` creates a cycle, put the producer in `mapStandDown.js`
and the constant in `heatSpots.js`; one producer either way.)

Hosts — pass `stoodField` from the SAME stood list the host already hands the hatch, with the same guards:
- `WindowFirstHeatStrip.jsx` (~L1140, `drawGeo`): withheld on `card.away` exactly as `stood` is (~L1165).
- `WindowRowFieldMap.jsx` (~L526, `drawGeo`): the popup crops `stood` to the framed regions (~L398-402)
  while `points` is not cropped. Use the same cropped list the hatch uses and note the edge effect in a
  comment (a stood place outside the frame does not tint the edge; rated ones still do).
- `MapHeatLayer.jsx` (~L413, `drawTiles`): its `stood` prop (already dark-sky/lens narrowed in `MapView`,
  consistent with its points).
- `AuroraFieldThumb` is not a solar host: untouched.

**D3 — Part 2: a window whose served verdict is `UNRATED` is muted.** Helpers in `utils/confidenceUtils.js`:

```js
export function fieldConfidence(tier, verdict) // min(confidenceScalar(tier), confidenceScalar('low')) when verdict === 'UNRATED', else confidenceScalar(tier)
export function fieldBloomAllowed(verdict)     // verdict !== 'UNRATED'
```

The verdict is the served word printed beside the field, per host:
- Heat strip: `card.verdict` (the printed word, strip ~L1443/L1695; under a scoped headline it is the in-reach
  word, which is what is printed). Gate the strip's temperature-mode bloom (`THUMB_BLOOM`, ~L1183-1188) with
  `fieldBloomAllowed`, and `conf` with `fieldConfidence`.
- Popup: `WindowRowFieldMap` gets a `verdict` prop from `WindowSheetDialog` (the word it prints, ~L181/515/553).
  Gate its bloom (~L551-553) and `conf`.
- Map: **not** `card.verdict` — `WindowFirstMapPane` deliberately does not forward it (~L805-819); the Map's
  word comes from `utils/mapVerdict.js` and moves with scope and drive tier. In `MapView`, read the active
  window's tier it already computes (`activeWindowTier`, ~L4327), cap `conf` where it is handed to
  `MapHeatLayer` (~L6802-6804), and pass a new boolean prop (e.g. `bloomAllowed`) that `MapHeatLayer` uses to
  withhold its temperature-mode `{ bloom: FIELD_BLOOM }` (~L431-433). The aurora `bloom` arm is untouched.
  Past windows: unchanged.

Any other verdict: the same scalar and bloom as today (options object unchanged).

**D4 — what stays exactly as it is.** Ramp stops, bloom gate (3★), hatch geometry and the hatches themselves
(their rated-mask input is unchanged by construction), `unrated`/`gated`/`na`, every count (`· N rated`,
spread histogram, best-spot, `ceilingOf`, verdict, Ask, markers, popups, region panel, `pastWindowBest`), the
haze for every non-UNRATED window, no backend change. Note for future readers: `evaluationScores`
(`WindowFirstBriefingContext.jsx` ~L925) picks fields and does not feed `buildHeatSpots`; nothing here uses it.

## Tests (read `docs/engineering/frontend-test-standards.md` first)

- `heatField` kernel: with `stoodField` given, the stand-down mask is still painted at stood places
  (extend `heatFieldStandDown.test.js` or its neighbour) — the regression the review caught. Without
  `stoodField` the output/options are byte-identical to today. A Sunday-15 fixture (6 rated incl. one 4★, 19
  weather stood): decode `field().img` colours against `rampRgb` (or colour distance) at a cell the rated
  points cover far from the 4★ — warm without, cool with; near the 4★ still warm. A stood-only window now
  returns a non-null field.
- `heatSpots`/`mapStandDown`: `stoodFieldPointsFor` excludes `TIDE_MISALIGNED`, keeps GENERIC/null-reason;
  `STOOD_FIELD_SCORE === 1`; input not mutated.
- Hosts (spy on `drawGeo`/`drawTiles` as existing tests do): the strip passes `stoodField` for a weather-stood
  window and none on an away card; the popup passes the cropped list; the Map passes its stood list; an
  all-rated or all-tide window's options object has no `stoodField` key. Existing `toBe(points)` identity
  assertions must still pass (points unchanged).
- "Never a count": `pastWindowBest` on an all-weather-stood past window still returns null; the strip's
  `· N rated` and spread for the Sunday-15 fixture are unchanged.
- `confidenceUtils`: `fieldConfidence` caps only `UNRATED` and never raises a lower scalar; `fieldBloomAllowed`.
  Hosts: UNRATED → capped `conf` and no bloom key (strip, popup, Map); MAYBE → unchanged.

## Docs

- `docs/engineering/reference/map-tab.md` (stand-down hatch bullet): weather stand-downs blend at 1★ through
  the kernel's `stoodField` (draw time only, never a point set, never counted); the UNRATED mute.
- `docs/engineering/reference/plan-tab.md` if it describes the thumbnail field/bloom.
- `docs/engineering/reference/architecture-decisions.md`, Backend-heavy bullet: one line licensing
  `STOOD_FIELD_SCORE` over `weatherStoodOf` (and why not a served flag: A9).
- `changelog.d/20261112-stood-down-field.md`.
