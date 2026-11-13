# Reach field: the At a Glance thumbnails follow the drive tier

Design handoff: `design_handoff_reach_field` (2026-11-13). One PR.

## Problem

Under a finite drive tier the card's words (verdict, spread, best place, `· N rated`) count `card.pool`, the
places within the tier. The thumbnail behind them is painted from every place in scope whatever the drive. So
a card reading `Maybe 3.3★ · within 45m` can glow amber over the Dales, two hours away. Changing the drive
buttons changes the words but not the picture.

## Decision

Split each thumbnail's field by the selected tier:

- **Within reach** (drive ≤ `limitMinutes`, or unknown drive): painted at today's full strength with today's
  options. It is a re-mean over the within places only, so next to a beyond place its colour and coverage differ
  from today's single pass (the beyond place no longer pulls it). Only the no-beyond case is pixel-identical.
- **Beyond reach**: painted from those places' own scores, **faded** (alpha × `REACH_FADE` = 0.3,
  desaturated 50%, no bloom). Its "not rated yet" and "stood down" hatches fade by the same factor.
- **Tier Any, or no measured drive** (`measured === false`, i.e. no postcode or Lite): one layer, options
  object identical to today's, so the paint is identical to today's.
- **Away origin**: the split reads `effectiveReachById` (the same map `card.pool` was gated with), never the
  home `reachById`.

Fade rather than hide: the card already points beyond reach ("best: The Lake District · beyond 45 min"), and a
faded glow keeps that reason to widen the drive visible without passing it off as the reachable morning.

This supersedes the heat-field plan's "the field shows the forecast, not your reach" position (heat-field plan
§4 copy, and the strip comment that framing must never become "the reach filter"). Framing is still never
reach-filtered: `framed`/`fitTo` stay on home `reachById` + `scopeSpots`, unchanged. Only the paint splits.

## Corrections to the handoff's recipe (found reading the code)

1. **Not two `drawGeo` calls.** `drawGeo` paints the whole canvas each call (sea fill, land plate, field,
   coastline), so a second call erases the first. It also hardcodes `alpha` (206, or 238 with `focus`),
   so a caller cannot fade through it. The two passes go **inside** one `drawGeo`, behind a new opt-in
   option `beyond`.
2. **The strip and the popup do not hold the right drive times.** `WindowFirstHeatStrip` receives home
   `reachById` (for framing), and the popup's `openField.reachById` is home too. Both need the effective map
   passed separately for the split.
3. **Unroutable places.** `card.pool` uses `gateSpotsByReach`, which also drops a place whose drive is
   `UNROUTABLE` when the reader is measured and a tier is set. The split must use the **same predicate**, so an
   unroutable place is beyond (faded), not within.

## Design

### `utils/reachLens.js`: one predicate

Extract `spotPassesReach(spot, limitMinutes, measured)` (the body of `gateSpotsByReach`'s filter callback) and
have `gateSpotsByReach` call it. Behaviour of `gateSpotsByReach` unchanged; its existing tests must pass
untouched.

### `utils/heatSpots.js`: `splitByReach`

```js
splitByReach(points, reachById, limitMinutes, measured) -> { within, beyond }
```

- `limitMinutes == null` or `measured !== true` or `!points?.length`: return `{ within: points, beyond: EMPTY }`
  with `within` the **same array reference** (memo and options identity; byte-identical paint).
- Otherwise for each point: `entry = point.id != null ? reachById?.get(point.id) : null`; test
  `spotPassesReach({ driveMinutes: entry?.driveMinutes ?? null, driveStatus: entry?.driveStatus ?? null }, …)`.
  A point with no id or no entry is unknown and passes.
- If nothing lands beyond, return the input array as `within` (same reference) and the shared empty `beyond`.
- Order within each half is preserved.

Applies to every point shape used by the field: heat points, unrated points, stood points (all carry `id`).

### `utils/heatField.js`: opt-in faded layer in `drawGeo`

New option `beyond: { points, unrated?, stood?, stoodField? }` (absent = every existing host unchanged, which
is the byte-identical guarantee; no branch may run when it is absent). New exported constants `REACH_FADE = 0.3`
and `REACH_DESAT = 0.5`.

New opt-in `desat` (0–1) in `field()`: after the existing `unc` greying, mix each colour toward its grey mean by
`desat`. Absent or 0 leaves `field()` exactly as today. The kernel, ramp stops, bloom gate and confidence haze
are not touched.

Paint order inside `drawGeo` when `beyond` is set:

1. sea, plate, veil (unchanged)
2. **beyond unrated hatch** then **beyond stood hatch**, each with `opacity: REACH_FADE`
3. within unrated hatch, within stood hatch (unchanged; default opacity)
4. **beyond field**: `paint(...)` over the projected beyond points (+ beyond `stoodField` via
   `withStoodField`), with `alpha: (focus ? 238 : 206) * REACH_FADE`, `desat: REACH_DESAT`, and the bloom keys
   removed (`bloom: null`)
5. within field (unchanged)
6. outline, plate hatch, coastline (unchanged)

**Rated masks (review finding 2).** The within hatches subtract the **within** rated points only; the beyond
hatches subtract **all** rated points. Otherwise a reachable unrated place beside a rated place beyond reach
loses its full-strength "not rated yet" hatch and reads as a dim colour. This departs from "hatched ground
exactly today's" under a tier (never under Any); test it.

**Opacity (review finding 1).** The hatch fade is `(opts.opacity ?? 1) * REACH_FADE`, passed to the hatch calls
only. The beyond field's `paint` options carry **no** `opacity` key (`blitField` reads `opacity` too, default
0.92; a shared object would fade the field twice, to ~0.08). Test that the beyond field blits at 0.92.

The beyond points are built exactly like `pts` (`{x, y, sc: s.r[win], rid}` through the same projection), and
`withStoodField` gets the beyond `stoodField` only. Each layer is limited to where its own places are by
`field()`'s coverage clamp (`cov`); the clip is drawGeo's hard land clip, unchanged. `paint` wraps its blit in
`save()`/`restore()`, so the two layers share no canvas state. Where a within and a beyond place sit together
the within layer paints over at full strength. The whole new branch sits behind `if (opts.beyond)` and the
desat behind `if (opts.desat > 0)`, so no new float work runs without them.

**Visual check (review finding 4).** At ×0.3 the unrated ink (.18) is about .05 and may vanish on a thumbnail.
Check in the browser; if it is invisible, record it and keep the literal factor (the handoff's words) unless
the owner says otherwise.

### `components/WindowFirstHeatStrip.jsx`

- New prop `fieldReachById` (Map, optional; the shell passes `effectiveReachById`). Absent: no split.
- In `paint`, per card (not away cards: an away card's paint is unchanged): split `points`,
  `unratedFor(card)`, and `stoodSets.get(card.key)` with `card.reachLimitMinutes` and `card.reachMeasured`.
  Pass the within halves where today's lists go (with today's guards: `unrated`/`stood`/`stoodField` keys
  present only when non-empty), and spread `beyond: {...}` **only when** some half of the split is non-empty.
  So tier Any, no postcode, or everything within → the options object is exactly today's.
- `stoodField` is derived per half with `stoodFieldPointsFor`.
- Add `fieldReachById` to the paint callback's deps. The tier already reaches the callback through `cards`
  (`reachLimitMinutes` lives on each card), which rebuilds when the lens changes; confirm with a test.
- The hidden sentence / accessible name: read what it says today. If it describes the field's colour and a
  tier is set, it must say the colour is within the tier ("within 45 min"). If it already scopes to the pool,
  leave it. Card text is unchanged.

### `components/WindowRowFieldMap.jsx` + `WindowSheetDialog.jsx` + `WindowFirstShell.jsx`

- `openField` gains `fieldReachById: effectiveReachById` (keep `reachById` as it is: it frames and draws the
  rings).
- `WindowSheetDialog` passes it, plus `card.reachLimitMinutes` and `card.reachMeasured` (or a single
  `reachSplit` object), to `WindowRowFieldMap`.
- `WindowRowFieldMap` splits `points`, `unrated`, and its cropped `stood` the same way and passes `beyond`
  under the same "only when non-empty" rule. Add the new inputs to its paint deps. `focus` behaves as today
  on both layers (the faded layer's alpha is the focused alpha × fade).
- The Map tab (`drawTiles`, `MapHeatLayer`) is **not** changed.

### Scope of "within" (review round, data-flow finding 2)

"Within" is the **reach** gate only, not the origin gate (`gateSpotsByOrigin`) nor the sub-region picks. Away,
the frame is already the origin's own region (`scopeSpots`), so a neighbouring region's places sit at the frame
edge at most; a neighbour within the tier paints at full strength there, as it does today. Picks keep their own
veil and outline. Recorded, not fixed: a second rule here would be a second gate to keep in step.

### Guards, deps, identity (data-flow findings 3–5, 7)

- Split the **already-guarded** lists: apply today's `card.away`, `plateHatched(card)` and empty-list guards first,
  then split what survives. A plate-hatched card or an away card never gets `beyond.unrated` / `beyond.stood`.
- `openField`'s memo deps (`WindowFirstShell.jsx` ~L1508) gain `effectiveReachById`.
- Pass primitives to `WindowRowFieldMap` (`fieldReachById`, `reachLimitMinutes`, and the `reachMeasured` it
  already gets), never an inline object, so `paint`'s identity does not change every render.
- With `focus` on a region whose places are all beyond, the within pass goes blank (focus weights 1e-4) and the
  faded layer shows the region. Correct; pin it in a test.

### Hidden sentence (data-flow finding 6)

The thumbnail's hidden sentence speaks the pool clause, which is already tier-scoped, and `stoodPhrase` /
the visible `stoodWords`, which count stood places **in frame**. Decision: keep the stood count frame-scoped.
Those places are still drawn (faded), and the visible card text must not change. No accessible-name change.

## Backend-heavy check

The split filters already-served per-place scores with the already-licensed reach gate (the same predicate
`card.pool` uses, over the same `effectiveReachById`) and changes only how strongly each place is drawn. It
derives no verdict, count, mean or pick. It belongs to the existing reach-scoped client gate class
(architecture-decisions "Three kinds of drive" / A10–A11): add one sentence there naming the field split as a
draw-only member of that class.

## Tests

All test files live under `frontend/src/test/`.

- `reachLens.test.js`: `spotPassesReach` (within, at the boundary, beyond, unknown, unroutable measured vs
  unmeasured, Any).
- `heatSpots.test.js`: `splitByReach`: in, out, boundary, unknown drive (no entry / null minutes / no id),
  unroutable, Any returns the same reference, `measured: false` returns the same reference, everything within
  returns the same reference, away map used (pass a map that differs from home and assert it decides).
- `heatField.test.js`: `field()` with `desat` absent produces identical image data to today; with `desat` the
  colour is greyer and alpha unchanged. `drawGeo` without `beyond` makes exactly today's call sequence (spy on
  the canvas or on `paint`); with `beyond` the faded layer is painted before the within layer, with
  `alpha` × 0.3 and no bloom, and the hatch opacity is 0.3; the rated mask includes beyond rated points.
- `WindowFirstHeatStrip.test.jsx`: tier Any → `drawGeo` options have no `beyond` key and equal today's;
  `reachMeasured: false` → same; tier 45 with a place beyond → `beyond.points` holds it and the main points do
  not; away origin → the effective map decides; unrated and stood split; a lens change repaints; card text
  identical with and without the split.
- `WindowRowFieldMap.test.jsx`: same three cases (Any unchanged, split under a tier, effective map), plus the
  focus-on-a-beyond-region case.
- `WindowFirstHeatStrip.test.jsx`: a plate-hatched card under a tier has no `beyond.unrated`; an away card has
  no `beyond` at all.
- `WindowSheetDialog.test.jsx`: the split inputs reach `WindowRowFieldMap`.
- A Shell test: under an away origin the strip gets `effectiveReachById` as `fieldReachById` while `reachById`
  (framing) stays home.
- `heatFieldUnknown.test.js` / `heatFieldStandDown.test.js`: hatch `opacity` 0.3 reaches the hatch's
  `globalAlpha`.
- `heatSpots.test.js`: a string id `"5"` against a Map keyed `5` is unknown and passes (no coercion).

## Docs

- `docs/engineering/reference/plan-tab.md`: the "three grounds" bullet gains the reach split, one or two
  sentences.
- `architecture-decisions.md`: the sentence above.
- `changelog.d/20261113-reach-field.md` (`### Changed — …`).
