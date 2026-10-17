# Handoff: Aurora alert banner — field thumbnail (desktop) + glow strip (phone)

**Scope:** `components/AuroraBanner.jsx`. v2 UI.
**Date:** 2026-10-11
**Fidelity:** High. Colours, sizes and copy are final.
**Depends on:** `design_handoff_aurora_heat` (aurora ramp + `bloomRGB` in `heatField.js`). Build that first, or confirm it has landed.

## Overview
The aurora alert banner escalates amber → red today. Those colours read as warnings, and in PhotoCast amber/red already mean "marginal / stand down". An aurora storm is good news for a photographer, so severity moves onto the **aurora ramp** used by the Map tab's aurora heat field.

One component, two layouts:
- **≥ 640px (desktop, iPad):** banner with a **96×118 thumbnail of tonight's aurora field** at left, and the best location named. While per-location scores are not yet available, the thumbnail slot shows **Kp + a vertical nine-step meter** instead.
- **< 640px (phone):** a single **56px glow strip**. Tap → Map tab.

## About the design files
`Aurora Alert Banner.html` is a **design reference** in plain HTML/JS, not production code. Recreate it in the React + Vite app with Tailwind v4 `plex-*` tokens and PropTypes. Open it in a browser — prose above each row is explanation, not UI.

`heat-field.js` is the already-ported shared kernel (`frontend/src/components/heatField.js` or `utils/`). The thumbnail uses its `drawGeo` host. Do not fork it. `plan-data.js` is mock data only.

---

## Severity scale

| Level | Accent | Ink (text) | Ramp fill | Motion |
| --- | --- | --- | --- | --- |
| MODERATE (G1–G2) | `#3AC884` | `#8CE6B6` | 62% | none |
| STRONG (G3+) incl. SEVERE/EXTREME | `#76FFA0` | `#A8FFC4` | 92% | `breathe` only if live **and** Bz < −1 nT |
| Simulated | unchanged | unchanged | — | none; keep hatched + dashed border; 🧪 replaces thumbnail |

- Drive from `status.level`, **not** `status.hexColour` (the API's amber/red). Keep `hexColour` unused, or remove after confirming nothing else reads it.
- Ramp (same as aurora heat): `#384262 → #2C5470 → #22867E → #3AC884 → #76FFA0`.
- **Never** use ramp colours below `#3AC884` as text. Text uses the ink column.
- `breathe`: `@keyframes breathe{0%,100%{box-shadow:0 0 0 0 transparent}50%{box-shadow:0 0 22px -4px color-mix(in srgb,var(--acc) 45%,transparent)}}`, 3.2s ease-in-out infinite. Disabled under `prefers-reduced-motion`. Replaces the old `aurora-pulse`.

---

## Layout 1 — Banner (≥ 640px)

Container `.wb`: `display:flex; gap:13px; align-items:stretch; padding:10px 14px 10px 10px; background:#221A15; border:1px solid #3A2C23; border-radius:12px; cursor:pointer`. Whole card clickable → `#map` (simulated → `#manage`), Enter/Space too. `role="alert"`, `tabIndex=0`.

**Thumbnail slot** (left, `flex:none; width:96px; border-radius:8px; overflow:hidden; background:#13100e; box-shadow:inset 0 0 0 1px #3A2C23`).

*A. Scores ready* — `<canvas>` 96×118 drawn with:
```js
HeatField.drawGeo(cv, 96, 118, spots, 0, {
  fit: HeatField.bbox(areaSpots, 0.25),   // planning area, same rule as Map tab
  ramp: auroraRamp, radius: 7, grid: 3, blur: 2.2,
  bloom: true, bloomFrom: 3.4, bloomRGB: [120,255,170], bloomA: 140, bloomBlur: 1.6,
});
```
`spots` = area locations with an aurora rating, shaped `{lat,lng,rid,r:[stars]}`. Locations with no rating are omitted.

Viewline on the thumbnail: dashed `#E0A542`, 1.2px, dash `[4,3]`, horizontal at the projected `southernmostLatitude`. If that latitude is south of the frame, clamp to y=116 and label `Kp 8 · all ↓`; otherwise label `Kp 6 line`. Label: absolute `top:4px; left:5px`, mono 8.5px 600, `#E0A542`, `white-space:nowrap`, text-shadow `0 1px 3px #000`.
**Gate:** draw the viewline and its label only when `viewlineReliable` is true (see *Open dependency*). Until then, omit both; the field alone is still correct.

*B. Scores pending* — `.thumb.pend`, column, centred, gap 7px, height 118px, background `linear-gradient(180deg,#151b1a,#13100e)`:
- `Kp 6` — mono 22px 700, ink colour, `text-shadow:0 0 12px` accent at 50%.
- Vertical meter: 9 bars, 34×4px, gap 2px, radius 1.5px, bottom-up. Bar *k* (0–8) lit if `k < kp`, colour `auroraRamp(1 + 4·k/8)`; bars k ≥ 6 add `box-shadow:0 0 5px` same colour. Unlit `rgba(242,231,211,.08)`.
- `SCORING / LOCATIONS` — mono 8.5px, 0.06em, uppercase, `rgba(242,231,211,.42)`.

"Scores ready" = `getAuroraLocations` / `getAuroraForecastResults(today)` returned ≥ 1 rated location. Swap without layout shift (same slot size).

**Body** (`flex:1; min-width:0; flex-direction:column; gap:7px; padding-top:3px`):
1. Pill: mono 10px 600, 0.09em, uppercase, padding 3px 8px, radius 5px, bg accent 14%, ink colour, inset 1px accent 40%. Leading 6px dot in accent with `0 0 8px` glow. Text: `Moderate · G2` / `Strong · G4` (G-scale from `status.gScale` when present).
2. When: mono 10px, 0.05em, uppercase, muted. `Forecast · tonight` / `Active now · detected 21:34` (existing `formatDetectedAt`) / `Simulated`.
3. Title: 15px 700, line-height 1.3, `#F2E7D3`. `status.description`.
4. Best line, 12px secondary:
   - ready: `Best: **Kielder** 4★ · 147 sites clear` (+ ` · Bz −9.2 nT` when live). Name 600 ink; stars mono 600 in ink colour.
   - pending: `**147** dark-sky sites clear` (+ Bz), then new line muted `Location scores due 19:40` (omit the line if no next-run time is known).
   - all overcast: replace with existing copy `All locations overcast — no clear skies forecast tonight`.
5. CTA: 12px 600, ink colour. `View on map →` (`Generate scores →` when simulated).

**Dismiss:** 44×44 button top-right, `margin:-4px -8px 0 0`, `✕` 16px secondary, hover `rgba(255,255,255,.06)`. `stopPropagation`. Keep existing rule: dismissed per level, re-shows on escalation.

Max width: let it fill its container; the reference renders at ~420px but the layout is fluid.

---

## Layout 2 — Glow strip (< 640px)

`.wc`: `min-height:56px; padding:0 6px 0 12px; display:flex; align-items:center; gap:11px; background:#221A15; border:1px solid #3A2C23; border-radius:12px; overflow:hidden; position:relative`. Clickable as above.

- Bottom edge `::after`: 2px, `background: <ramp>`, `clip-path: inset(0 calc(100% - var(--fill)) 0 0)` with `--fill` 62% / 92%, `box-shadow:0 0 10px` accent.
- Glyph: existing `AuroraGlyph`, 22px, `filter:drop-shadow(0 0 6px accent)`.
- Line 1 (13.5px 700, single line, ellipsis): level word (mono 10px 600 uppercase, ink colour) + `Aurora tonight` / `Aurora active now`.
- Line 2 (mono 11px muted, ellipsis): `**Kp 8** · best Hackfall Wood 5★` ready; `**Kp 6** · 147 dark sites clear` pending.
- `Map →` mono 12px 600 ink colour.
- Dismiss 44×44, no negative margin.
- Bz, overcast note and viewline summary are **not** shown in the strip; they live on the Map tab.

Breakpoint: switch at **640px** container/viewport width (`useIsMobile` if it uses a compatible threshold; otherwise a `matchMedia('(min-width:640px)')`).

---

## Data & state
Existing: `useAuroraStatus()` (level, gScale, kp, forecastKp, bzNanoTesla, triggerType, detectedAt, description, darkSkyLocationCount, clearLocationCount, simulated), `useAuroraViewline()`.
New reads:
- per-location aurora scores for today (reuse what `MapView.jsx` fetches; consider lifting to a shared hook `useAuroraScores(date)` so banner and map share one request).
- planning-area locations (same `areaRids` rule the Map tab uses).
- optional `nextScoringAt` for the pending line — if the API has nothing, omit the line.
- `viewlineReliable` boolean — **false by default** until the viewline investigation lands.

Derived: `best = max(stars)` among area locations; ties → shorter drive time.

## Open dependency — viewline
The southern-limit line is under investigation (`design_handoff_aurora_heat`, Task 3; findings in `docs/aurora-viewline-investigation.md`). Ship the thumbnail **without** the line (`viewlineReliable=false`). Flip it on once the forecast line refreshes / hands over to live and the Kp table is corrected. Ask the user before enabling.

## Tests
- MODERATE → accent `#3AC884`; STRONG/SEVERE/EXTREME → `#76FFA0`; `hexColour` ignored.
- Scores pending renders Kp slot; scores ready renders canvas; no layout shift (slot 96×118).
- `breathe` only when live, Bz < −1, not simulated; absent under reduced motion.
- < 640px renders strip, ≥ 640px renders banner.
- Viewline not drawn when `viewlineReliable` is false; `all ↓` label when below frame.
- Dismiss per level persists; escalation re-shows.
- Simulated: hatched, 🧪 in slot, `Generate scores →`.

## Tokens
Surface `#221A15` · thumb bg `#13100e` · border `#3A2C23` / `#4A3A2E` · ink `#F2E7D3`, `rgba(242,231,211,.66)`, `rgba(242,231,211,.42)` · viewline `#E0A542` · ramp above. IBM Plex Sans / IBM Plex Mono. Radii 5 / 8 / 12px.

## Assets
None new. `AuroraGlyph` SVG already in `AuroraBanner.jsx`. Coastline: `world-atlas@2.0.2` `countries-50m`, as Plan thumbnails.
