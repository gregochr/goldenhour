# Claude Code prompt — Map tab: tide on the window

Paste everything below into Claude Code from the repo root, with `design_handoff_tide_window/`
copied into the repo (or its path substituted).

---

Implement **tide on the window** for the Map tab, from the design bundle in
`design_handoff_tide_window/`.

**Read `design_handoff_tide_window/README.md` first and in full.** It is the specification:
every threshold, hex value, copy string and layout constraint in it is final and intended to be
matched. This prompt is the working order, not a substitute for it.

This is an **increment on the Map tab** already specified in `design_handoff_map_tab/` and
`design_handoff_map_landing/`. If that work is not in the repo yet, do it first — this builds on
its event list, field, chips, anchored callout and location sheet. Do not re-tune the field
kernel, the coast mask, the ramp, the bloom gate or the basemap.

## The problem being fixed

A strong sunrise at mid water is the commonest coastal disappointment there is. Tide reached the
map as a wave glyph on the few locations where high or low water landed within 45 minutes of
the light; every other coastal location looked exactly like an inland one, and where the
mismatch pulled the score down the location left the map entirely. **An empty coast reads as
"nothing here" rather than "not this morning".** That is the bug.

## What the files are

- `Map Tide Window.html` + `map-tide-v5.js` — the design, as a working Leaflet prototype.
  **Design reference, not production code.** Recreate in React using this repo's patterns
  (`react-leaflet`, `components/MapView.jsx`, `components/TideIndicator.jsx`,
  `hooks/useIsMobile.js`). Do not port imperative DOM code.
- `heat-field.js`, `plan-data.js` — unchanged kernel and mock catalogue, so the prototype runs.

The notes column in `Map Tide Window.html` is part of the spec, and the rejection in it is
load-bearing: **do not shade the sea** (README §7 says why, and offers the cheap alternative).

## Build order

1. **The field.** Add `want: 'high' | 'mid' | 'low'` (nullable) to the location record, API and
   admin editor — meaningful only where `coast` is true. This is the **only** new data in the
   whole design; decide the `null` semantics first (README `OPEN 1`; prototype assumes *no
   preference, never dim*).
2. **The model, from real extremes.** Do **not** port the prototype's cosine anchor (`TIDEX`).
   `fetchTidesForDate` already returns the day's extremes with heights — interpolate between
   consecutive extremes with a cosine, not a linear ramp, and derive level, band, direction and
   height from that. `fetchTideStats` gives the "below / above average" note. README §1.
3. **Fit.** `level → band` at 0.75 / 0.25, `fit = 1 - |level - target[want]|`, tiers at 0.80 and
   0.62, solar events only. Memoise per (window, location). README §1.
4. **The chip.** `match` keeps the existing v2 glyph; `miss` gets `opacity:.72` plus the
   wave-with-arrow glyph; `near` is silent. Hover and selection restore full opacity. **Never**
   re-colour the star or the ramp swatch for tide, and **never** remove a coastal chip for tide
   — the label-budget tiebreaker may promote matches but must never demote misses. README §3.
5. **The callout and sheet block.** One component, three tiers, exact copy from README §4,
   including the "Wrong water, not wrong light" heading and the next-fit jump line. It renders
   in both the anchored callout and the shared location sheet — extend that sheet, do not build
   a second one.
6. **The strip.** Geometry, layers and labels per README §2. Two things that will bite if
   skipped: `vector-effect:non-scaling-stroke` on the curve (the SVG is non-uniformly scaled),
   and publishing `--tsh` from the strip's real height so the legend chip and count line clear
   it in both open and collapsed states.
7. **Visibility rule.** Solar event **and** at least one coastal rated location inside
   `bounds.pad(0.12)`. No toggle, no mode. Counts are in-view counts and update on pan.
8. **Next fit.** Dominant unmet want among the dimmed in-view locations; scan forward over solar
   events; honest "beyond these four days" copy when nothing fits. Then read `OPEN 4` — the
   version worth shipping eventually does not need the forecast at all.
9. **Phone.** Strip full-width above `#gnav`; hide the count line while the strip is up.
10. **Decide before ship:** `OPEN 3` (which station's curve the strip states, and saying so on
    the strip) and `OPEN 5` (whether the shipped score already penalises tide mismatch — if it
    does, the user is being told twice and one of the two has to go).

## Verify by measurement

Run all eight checks in the README's *Verify by measurement* section and report the numbers, not
screenshots. Checks 1, 2 and 4 each guard a decision that was made deliberately and is easy to
undo by accident:

1. Tide never moves the heat (identical field alpha across all three `want` values).
2. Nothing is dropped — named coastal chip count is the same on a mid-water and a high-water
   window at the same viewport.
3. Strip visibility: solar + coast → shown; night event → hidden; no coast in view → hidden and
   the chrome returns to `bottom:12px`.
4. `--tsh` clearance ≥ 22px, open and collapsed.
5. Light dot sits on the curve at the event time for every window, `top` within 1px of
   `TY(level)`.
6. Next fit always satisfies `fit >= 0.80`; no false jumps.
7. Every text node in the strip ≥ 4.5:1.
8. Phone: no overlap with `#gnav`; count line hidden.

## Do not change

The field kernel, coast mask, ramp, bloom gate, basemap, label density rules, anchored callout
geometry, reach rings, the verdict / picks / landing card work, the location sheet, or the Plan
tab. Do not fold tide into the score or the ramp. Do not shade the sea.
