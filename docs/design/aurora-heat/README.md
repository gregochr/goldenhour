# Handoff: Aurora mode on the heat map + southern viewline investigation

**Scope:** Map tab, `eventType === 'AURORA'`. v2 / Plan First UI only.
**Date:** 2026-10-11
**Fidelity:** High. Colours, ramp stops and copy are final.

This bundle has **three tasks**. Do Task 3 (investigation) **before** changing any viewline code, and **ask the user the questions in Task 3 before proposing a fix**.

1. Aurora mode renders as a heat field with an aurora-specific colour ramp (replaces medallions).
2. The best-location card ("🏆 Hackfall Wood ★★★★☆ … Centre map") gets a dismiss button.
3. Investigate why the aurora southern-limit line (viewline) sat over North Yorkshire during the 10 Oct 2026 G3–G4 storm, when aurora was reported as far south as England's south coast.

---

## About the design files

The HTML/JS files here are **design references**, not production code. Recreate them in the existing React + Vite frontend using its patterns (`react-leaflet`, Tailwind v4 `plex-*` tokens, PropTypes).

`heat-field.js` is the exception: it is the **same kernel already ported** for the sunrise/sunset heat map (see `design_handoff_heat_map/README.md`). This bundle only adds one backward-compatible option to it (`bloomRGB`, below). Do not fork the kernel for aurora.

| File | What it is |
| --- | --- |
| `Map Aurora Heat.html` | Spec. Open it — prose above and notes below the map are explanation, not UI. Strip `.hd`, `.cap`, `.two`, `.try`. |
| `aurora-map.js` | Behaviour reference. Contains the aurora ramp (port this constant) and a **mock** scoring function (do NOT port). |
| `heat-field.js` | Shared kernel, with the `bloomRGB` addition. |
| `plan-data.js` | Mock catalogue. Replace with real data. |

---

## Task 1 — Aurora as a heat field

### Current state (the bug)
In aurora mode the Map tab still draws medallion clusters (gold/orange "3★" coins). Sunrise/sunset already use the heat field. Aurora must use it too; the heat/medallion toggle and zoom handover behave identically to the other modes.

### What changes vs sunrise/sunset — only two things
1. **Score source.** Per location, use the aurora rating already in `MapView.jsx` → `getRatingForLocation(loc)` for `AURORA`:
   `storedAuroraResults[loc.name]?.stars ?? auroraScores[loc.name]?.stars ?? null`.
   Pass it via the kernel's existing `opts.score` hook in `drawTiles`. Locations with `null` are **omitted** from the points array (no data ≠ 1★).
2. **Colour ramp.** Pass `opts.ramp = auroraRamp`.

Everything else is unchanged: cull, bucketing, coverage clamp, confidence haze, `radiusFor(map, 8500, 34, 240)`, `grid: 6`, `blur: 4`, the z10.6→12.2 handover to pins, rAF-throttled `render()` + un-throttled `renderNow()`.

### Aurora ramp — port exactly

| Score | RGB | Hex | Meaning |
| --- | --- | --- | --- |
| 1 | 56, 66, 98 | `#384262` | dark sky, no show (still visible so it differs from no data) |
| 2 | 44, 84, 112 | `#2C5470` | |
| 3 | 34, 134, 126 | `#22867E` | teal |
| 4 | 58, 200, 132 | `#3AC884` | |
| 5 | 118, 255, 160 | `#76FFA0` | **aurora green** |

Linear interpolation between stops, clamp 1–5. Luminance rises monotonically, so on the dark CARTO tiles brightness alone ranks the field.

```js
const AURORA_STOPS=[[1,[56,66,98]],[2,[44,84,112]],[3,[34,134,126]],[4,[58,200,132]],[5,[118,255,160]]];
```

Why not reuse the shared temperature ramp: its 5★ is red, which reads as "great sunset" and makes aurora mode look like the Sunset tab. 1★ is slate rather than red because a poor aurora night is an ordinary night, not a failure.

### Kernel options for aurora
```js
HeatField.drawTiles(canvas, map, spots, 0, {
  score: s => auroraStars(s),          // null-scored spots filtered out beforehand
  ramp: auroraRamp,
  radius: HeatField.radiusFor(map, 8500, 34, 240), grid: 6, blur: 4,
  conf, opacity: 0.9 * heatAlpha,
  bloom: true, bloomFrom: 3.4, bloomRGB: [120,255,170], bloomA: 150,
});
```

**Kernel change (`heatField.js`):** the bloom colour was hard-coded `255,138,66`. Replace with
`const bc = opts.bloomRGB || [255,138,66];` and write `bc[0..2]`. Default unchanged, so sunrise/sunset are unaffected — add a test asserting that.

`conf`: use the night's forecast confidence if the API exposes one; otherwise `1` for tonight and decreasing for later nights (match how solar windows do it). Lower confidence already desaturates and hazes.

### Pins at zoom (handover)
Same as other modes, coloured with `auroraRamp`. Ink via `HeatField.ink(c)`. Named pins 30px with the rounded star number, scatter 16px. For score ≥ 4 add `box-shadow: 0 0 14px rgba(r,g,b,.7)` (glow). Labels appear past 55% blend, as today.

### Legend (heat panel, bottom-left)
- Label: `COLOUR = AURORA RATING`
- Bar: `linear-gradient(90deg,#384262,#2C5470 25%,#22867E 50%,#3AC884 75%,#76FFA0)`, 9px tall, radius 999px, `box-shadow: 0 0 10px rgba(118,255,160,.25)`
- Ends: `1★ dark sky, no show` · `3★` · `5★ aurora green`
- Note: **Rating, not Kp.** Each location's score already folds in latitude, cloud and sky darkness. The dashed line is where the aurora clears the northern horizon.

The view-toggle swatch for Heat in aurora mode uses `linear-gradient(90deg,#22867E,#76FFA0)`.

Filters: aurora mode keeps hiding location-type and dark-sky chips (existing behaviour). The ramp-compare toggle in the prototype (`Aurora ramp / Shared ramp`) is **for review only — do not ship it**.

### Viewline over the heat
`AuroraViewlineOverlay` stays, drawn above the heat canvas (z-index above 410). No styling change in this task — but see Task 3 before touching its data.

---

## Task 2 — Dismiss the best-location card

`MapView.jsx`, `data-testid="aurora-best-location-card"`. There is currently no way to close it.

- Add a `✕` button after `Centre map`, `aria-label="Dismiss best aurora location"`, `data-testid="aurora-best-location-dismiss"`, 44×44 hit target, colours `text-indigo-300 hover:text-indigo-100 hover:bg-indigo-800/40`.
- State: `bestAuroraDismissedKey`, set to `` `${date}|${location.name}` `` on click. Card renders only when the key differs — so it returns if the date changes or a different location takes the top spot.
- Session-only (React state) is fine; persist to `localStorage` only if the user asks.
- A first-pass edit with exactly this shape was made in the design project's copy of `MapView.jsx`; reapply it in the real repo and add a test.

---

## Task 3 — Investigate the southern viewline (ask first)

### What the user saw
On 10 Oct 2026 a G3, possibly G4, storm was in progress and aurora was widely reported down to England's south coast. The app's line was **dashed amber** across roughly 54°N (North Yorkshire, just south of Hackfall Wood). The user expected it much further south.

### What the frontend tells us
- Dashed amber = `viewline.isForecast === true` → this is the **forecast** viewline, not live OVATION.
- `hooks/useAuroraViewline.js`: when `triggerType === 'forecast'` it fetches `GET /viewline/forecast` **once and never polls** (comment: "deterministic table lookup"). Only the live path polls every 5 min.
- `MapView.jsx` chooses the path from `auroraStatus.triggerType`, fixed when the alert fired. If the storm escalated after a forecast-triggered alert, the line stays at the original Kp.
- The overlay only renders when `date === today` (`toLocaleDateString('en-CA')`).
- ~54°N corresponds to roughly Kp 6; a Kp 8 (G4) horizon-visible line should sit around 51–52°N.

### Investigate (backend + frontend)
1. Find the Kp→latitude table behind `/viewline/forecast`. Report its values. Check whether it is **geographic or geomagnetic latitude**, whether it represents overhead or horizon-visible extent, and whether it saturates or caps (e.g. a max Kp row, rounding down, integer Kp only).
2. Which Kp feeds it: current 3-hour, max of tonight, or the forecast at trigger time? Is it refreshed when NOAA/Met Office revise upward?
3. For the live path (`/viewline`): what OVATION product is used, how "southernmost" is derived, and whether any latitude clamp/filter exists.
4. Trace `auroraStatus.triggerType` transitions: does a forecast-triggered alert ever switch to `realtime` once the storm is underway?
5. Check logs/DB for the 10 Oct night: the Kp used, the viewline returned (`southernmostLatitude`, `points`), and when they were computed.

### Questions to ask the user before fixing
- What time on 10 Oct was the screenshot taken (it shows 22:43)? Was the app open for long, or freshly loaded?
- Did the line ever change during the night, or stay put?
- Should the line show **naked-eye** visibility or **camera** visibility? Phone cameras catch aurora several degrees further south than eyes, which is what most "south coast" reports were.
- Preferred behaviour when forecast and live disagree: always show live once a storm is in progress, or show both?
- Is the dashed/solid + amber/green convention still wanted, given the new green aurora ramp? (Green line on a green field may clash — propose a recolour if so.)

### Likely fixes to propose (after findings)
- Poll the forecast viewline too, or switch to live once `level` is MODERATE/STRONG and live data exists.
- Correct/extend the Kp table (Kp 7 ≈ 53°N, Kp 8 ≈ 51.5°N, Kp 9 ≈ 50°N horizon-visible for UK longitudes; verify against NOAA's published viewline).
- Label the line with its source and age, e.g. `Forecast · Kp 6 · 19:40` vs `Live · 22:40`.

Write findings to `docs/aurora-viewline-investigation.md` and stop for user review before changing the table.

---

## Design tokens (unchanged from heat-map handoff)
Page `#181210` · surface `#221A15` · panel `#1E1712` · border `#3A2C23` / `#4A3A2E` · ink `#F2E7D3`, `rgba(242,231,211,.66)`, `rgba(242,231,211,.42)` · amber viewline label `#E0A542` · aurora green `#76FFA0`.
Type: IBM Plex Sans (UI), IBM Plex Mono (labels, figures). Radii: 8px controls, 9px cards, 12px panels.

## Assets
None. Tiles are the existing CARTO `dark_all`.

## Tests to add
- Aurora mode renders the heat canvas, not medallion clusters, by default.
- `auroraRamp(1)`, `(3)`, `(5)` return the stop colours; clamps outside 1–5.
- Locations with no aurora rating are excluded from field points.
- `bloomRGB` default leaves the sunrise/sunset bloom unchanged.
- Best-location card: dismiss hides it; changing date or best location shows it again.
