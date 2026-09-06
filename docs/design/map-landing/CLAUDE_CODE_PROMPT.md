# Claude Code prompt — Map tab: verdict, picks and the landing card

Paste everything below into Claude Code from the repo root, with `design_handoff_map_landing/`
copied into the repo (or its path substituted).

---

Implement the **verdict, picks and landing card** increment on the Map tab, from the design
bundle in `design_handoff_map_landing/`.

**Read `design_handoff_map_landing/README.md` first and in full.** It is the specification:
every threshold, hex value, copy string and layout constraint in it is final and intended to be
matched. This prompt is the working order, not a substitute for it.

This is an **increment on the Map tab (v2)** already specified in `design_handoff_map_tab/`. If
that work is not in the repo yet, do it first — this builds on its event list, field, callout
and location sheet. Do not re-tune the field kernel, the coast mask, the ramp or the basemap.

## What the files are

- `Map Landing.html` + `map-tab-v4.js` — the design, as a working Leaflet prototype. **Design
  reference, not production code.** Recreate in React using this repo's patterns
  (`react-leaflet`, `components/MapView.jsx`, `components/BottomSheet.jsx`,
  `hooks/useIsMobile.js`). Do not port imperative DOM code.
- `Map Verdict Options.html` + `map-tab-v3.js` — the three options this was chosen from (on the
  control / a week rail / picks pinned to the ground). Context only. **Build only option A**,
  which is what `Map Landing.html` is.
- `heat-field.js`, `plan-data.js` — unchanged kernel and mock catalogue, so the prototype runs.

The notes column in `Map Landing.html` is part of the spec. The rejections in it are
load-bearing: a filled medallion, a pick chip pointing at other windows, dismiss-on-drag, and
naming the least-bad region were all built and cut for stated reasons.

## Build order

1. **Verdict source.** Wire the window verdict to the **API's regional verdict**
   (`region.verdict`: `GO` / `MARGINAL` / `STANDDOWN` → Worth it / Maybe / Poor), reusing the
   mapping already in `components/HeatmapGrid.jsx` and `components/DailyBriefing.jsx` so the two
   tabs cannot disagree about a night. Keep the field-derived fallback and its thresholds
   (>= 3.7 / >= 2.8) for the case where no regional verdict exists. README section 1.
2. **Exclude filters, include scope.** Verdict, region label and picks must be computed from the
   scope-limited catalogue *before* rating / reach / subject / dark-sky filtering. Cache on
   `(window, scope, origin)`. This is check 1, and it is the rule most likely to be broken by a
   convenient refactor.
3. **The region label.** Name the top region; `+N` when others share the tier; `everywhere in
   your area` when every region in scope does. All three cases, including the third.
4. **The pill.** Verdict word with the region stacked under it, tier tint as an inset left bar,
   medallion as an outline chip with glyph and words as **separate elements**, stepper verdict
   ticks. Then satisfy the three layout constraints in README section 2 — bounded control group,
   never-truncating day label, shrinkable pill on phone with the glyph surviving. All three were
   regressions; check 4 is what catches them.
5. **Picks.** Solar windows only, confidence-nudged rank, both-differ rule, no picks below 2.8.
   Outline medallion on the pill only when the current window is a pick, plus on every menu row.
   Do not add a chip that points at other windows.
6. **The landing card.** Next two solar windows, header derived from those rows, picks riding
   their rows, earlier picks suppressed, the all-Poor branch naming a strictly-later Worth it
   window. Dismiss on close / Escape / row selection only. Reopen row at the top of the pill
   menu.
7. **Decide before ship:** how often the card appears (recommendation: once per forecast run,
   using the run age the app already has). README `OPEN 3`.
8. **The two panels.** Window → regions → region, ranked by mean with the ceiling beside it, the
   four note variants, full location name in the footer action, ending in the **existing**
   location sheet. Extend that sheet; do not build a second one.
9. **Panel persistence.** A click on the map no longer closes the week menu, the region panels,
   Filters or Legend. Tapping bare ground still deselects a location.
10. **Responsive** per the README table: region and medallion words drop at phone width, the
    control stays one row at all three sizes.

## Verify by measurement

Run all seven checks in the README's *Verify by measurement* section and report the numbers, not
screenshots. Checks 1, 2, 4 and 6 each caught a real defect during the design:

1. Filters do not change the verdict; scope does.
2. All three region-label cases render, including `everywhere in your area`.
3. Picks: solar only, differ in both window and region, absent below 2.8.
4. Control is one row at desktop / iPad / iPhone; next-stepper right edge left of the Regions
   chip; day label not truncated; medallion glyph font-size non-zero on phone.
5. Card survives pan / zoom / wheel / map click; closes on the close button, Escape, or a row;
   reopens from the menu with the same header.
6. All-Poor card: both rows Poor, "next up" strictly later and Worth it, no pick earlier than
   the first row.
7. Night events: no verdict, no tint, not pick candidates, correct panel note.

## Do not change

The field kernel, coast mask, ramp, bloom gate, basemap, label density rules, anchored callout,
reach rings, location sheet, or the Plan tab. If the map becomes the landing tab, the Plan tab's
scope is a separate decision and a separate piece of work — flag it, do not start it.
