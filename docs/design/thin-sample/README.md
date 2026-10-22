# Handoff: a heat field that shows what was not looked at

## Overview
The Plan strip's heat field draws rated places only, so on the plate *nobody looked* and *no places here* look the same. A window crowned on a handful of ratings (Sat 24 sunrise, 6/83 rated, 4.0★) reads as confident as a well-rated one. This change adds a **per-place hatch around every unrated place**, so a thin window reads as a small warm patch inside a large hatched area. It also adds a thin-sample count to the verdict line and replaces the legend's `unshaded — not scored` entry with two swatches. It applies to the Plan thumbnails, the open row's field map and the Map tab.

Companion change, separate work: `PICK_ELIGIBILITY.md` in this folder (a forced sample can no longer take BEST BET / ALSO GOOD). The "after" artboards assume it. Either can ship first.

## About the design files
`Thin Sample Field.html` is a **design reference built in HTML**, not production code. Recreate it in the existing components (`frontend/src/utils/heatField.js`, `WindowFirstHeatStrip.jsx`, `WindowRowFieldMap.jsx`, `MapHeatLayer.jsx`) using their established patterns. The new layer is `unknownHatch()` in the HTML's inline script; the host `draw()` beside it shows where it slots into `drawGeo`'s paint order. `heat-field.js` is the design-side copy of the kernel, with the same arithmetic as production.

The place catalogue in the reference is **synthetic** (clustered by region, denser than production). The six Sat-sunrise places are real Tyneside coordinates. Card copy and histogram counts come from the 22 Oct production screenshot.

## Fidelity
High fidelity for the new layer, the legend and the verdict-line count. Everything else on the card is the shipped card, re-drawn for context; do not restyle it.

## The layer: hatch around unrated places

**Paint order in `drawGeo` (and the tile host):** sea → plate → **unknown hatch (new)** → field (rated points only, unchanged) → whole-plate hatch if no rating at all (unchanged) → coastline stroke.

Drawing the hatch under the field is the overlap rule: **colour wins** where a rated place is near.

**Mask, per cell (grid 4px, same radius `R` the field uses on that surface):**
```
su = Σ exp(-d²/R²) over UNRATED places within d² ≤ 6R²
sr = Σ exp(-d²/R²) over RATED places within d² ≤ 6R²
cu = 1 - exp(-su / 1.15)        // same coverage clamp as the field
cr = 1 - exp(-sr / 1.15)
a  = clamp(cu / 0.45, 0, 1) * clamp(1 - cr / 0.45, 0, 1)
```
- 0.45 ≈ one isolated place's own peak coverage (0.58) less a margin, so a lone unrated place gets a solid hatch disc and a lone rated place fully suppresses hatch at its centre.
- Upscale the mask with the **field's own blur**: `blur + (1 - conf) * 2.6` px (thumbnail `blur` 2.4; Map tab 3). The edge then feathers at the same rate as the colour.
- Rule the hatch over the full surface, apply the mask with a single `destination-in`, then draw clipped to land (hard clip on thumbnails; the tile host's soft land mask on the Map tab).
- Use the **existing bucketing** for the two sums. Do not ship the reference's brute-force loop.

**Hatch mark:** unchanged constants from `hatchPlate`: 45°, `HATCH_GAP` 7 CSS px, `HATCH_LINE` 0.6, `HATCH_INK` `rgba(242,231,211,.18)`. Fixed in CSS px, so it is the same mark on a 300px card and at map scale.

**Population:** every place the field draws (the whole forecast catalogue in frame), **not** the reach pool. A place counts as unrated for a window when it has no score for that window (`r[win] == null`).

**States on the plate:**
- colour: rated places
- hatch: places exist but are not rated
- plain plate: no places
- whole-plate hatch: the window has no rating at all (unchanged; the per-place layer is skipped)

**Must not change:** ramp stops, bloom, confidence haze, field alpha, rated places' colour, stars, verdict words.

## Verdict line: thin-sample count
- Appears only when the spread row's own gate fails (`windowFirstSpread.js`: bars need `rated >= 5` and `rated >= half` the reach pool). Same population and threshold, so the verdict and spread rows never disagree.
- Copy: `Worth it 4.0★ · 6 rated`. The number is the reach-pool rated count (the one already in `6/83 rated`).
- Style: separator ` · ` in `--ink-3` `#857C70`, 400; `6 rated` in `--ink` `#F2E7D3`, 500; verdict word and star unchanged (IBM Plex Mono 12.5px; word 700 in grade colour, star 400 `#ADA394`). Solid colours only; no opacity muting.
- Not shown on unscored windows (the whole-plate hatch says it).
- Fits one line on a 300px phone card (screenshot 05).

## Legend line (strip footer, `wf-heat-foot`)
Before: `[ramp] poor → worth it · later days look hazier — lower confidence · unshaded — not scored` … right: `Colour shows the whole forecast — the cards allow for your drive`

After:
- `[ramp] poor → worth it`
- `later days look hazier — lower confidence`
- `[hatch swatch] not rated yet` (replaces `unshaded — not scored`)
- `[plain swatch] no places`
- right-aligned: `Colour and hatch cover every place in the forecast — the cards allow for your drive`

Swatches: 26×13px, radius 2, fill plate `#241d18`, inset 1px `rgba(242,231,211,.3)` (the coastline ink). The hatch swatch uses `repeating-linear-gradient(-45deg, rgba(242,231,211,.32) 0 .8px, transparent .8px 5px)`; the swatch ink is brighter than the map's .18 so it survives at 13px. Text 11px IBM Plex Mono `#ADA394`, gap 8px 14px, wraps.

Map tab key (bottom-right panel): `[ramp] rated · [hatch] not rated yet · [plain] no places`, same swatches, 10.5px.

## Map tab
Same layer through the tile host, with `R` = `radiusFor(map, 7200, 30, 400)` (unchanged) and the existing soft land clip (`clipSoft` 4). The hatch spacing stays 7 CSS px at every zoom. Zoomed in on one region, unrated places read as hatched ground around their markers; where places are sparse the hatch breaks into discs with plain ground between. The window chip carries the same `· N rated` count under the same gate. The reference's markers (filled square = rated, hollow ring = unrated) are stand-ins; keep production's markers and check they still separate rated from unrated.

## State / data
No new data. Per window the client already holds `spots` (catalogue with coordinates) and the rated points; unrated = catalogue minus rated, per window. The count and gate come from the existing spread computation.

## Tests to add
- `field`-style unit test on the mask: an isolated unrated point gives `a = 1` at its centre; an isolated rated point gives `a = 0` on top of a co-located unrated one; empty ground gives 0.
- Bucketed mask equals a brute-force mask (mirror the existing kernel test).
- Window with zero ratings: whole-plate hatch only, per-place layer not drawn.
- Verdict count appears iff the spread gate fails; absent on unscored windows.
- Legend renders both swatches and the new right-hand copy.

## Design tokens used
`--bg #181210` · `--panel #1E1712` · `--border #3A2C23` · `--ink #F2E7D3` · `--ink-2 #ADA394` · `--ink-3 #857C70` · sea `#13100e` · plate `#241d18` · coastline `rgba(242,231,211,.30)` · hatch `rgba(242,231,211,.18)` · verdict green `#A8C795`, amber `#EFC377`, red `#E58C7A` · IBM Plex Mono / IBM Plex Sans.

## Files
- `Thin Sample Field.html`: the design canvas (strip before/after, phone card ×3, legend, Map tab ×4, decisions)
- `heat-field.js`: design-side kernel it loads
- `PICK_ELIGIBILITY.md`: backend brief for the companion pick-eligibility change
- `screenshots/`: 01–04 strip before/after (sunrise row, sunset row + legend), 05 phone card, 06 legend, 07 Map tab zoomed out, 08 Map tab zoomed in
