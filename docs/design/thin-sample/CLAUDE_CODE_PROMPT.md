Implement the thin-sample heat field change described in `design_handoff_thin_sample/README.md`.

The HTML/JS in this folder is a design reference, not production code. Recreate it in `heatField.js`, `WindowFirstHeatStrip.jsx`, `WindowRowFieldMap.jsx` and `MapHeatLayer.jsx`, following the repo's patterns.

Scope: the unknown-ground hatch, the verdict-line count and the legend. Don't touch the ramp, bloom, haze, field alpha, stars, verdict words or picks (picks are `PICK_ELIGIBILITY.md` in this folder, separate work).

Do:
1. Add the per-place hatch layer to `heatField.js`, drawn on the plate under the field, using the mask formula in the README and the existing hatch constants. Reuse the kernel's bucketing.
2. Wire it into `drawGeo` (thumbnails, row map) and `drawTiles` (Map tab, inside the soft land clip). Pass all catalogue points; unrated = no score for that window.
3. Keep the whole-plate hatch for windows with no rating; skip the new layer there.
4. Add `· N rated` to the verdict line only when the spread row's gate fails. Same count, same gate.
5. Replace `unshaded — not scored` with the two swatches and the new right-hand copy. Add the same key to the Map tab.

Check against `screenshots/` at desktop and phone widths, then on real data with a thin window and a well-rated one side by side.
