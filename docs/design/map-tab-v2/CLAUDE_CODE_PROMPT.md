# Claude Code prompt — PhotoCast Map tab v2

Paste everything below into Claude Code from the repo root, with `design_handoff_map_tab/`
copied into the repo (or its path substituted).

---

Implement the redesigned **Map tab** in this repo, from the design bundle in
`design_handoff_map_tab/`.

**Read `design_handoff_map_tab/README.md` first and in full.** It is the specification: every
hex value, zoom threshold, density rule, measured luminance figure and copy string in it is
final and intended to be matched. This prompt is the working order, not a substitute for it.

## What the files are

- `Map Tab v2.html` + `map-tab-v2.js` — the design, as a working Leaflet prototype. **Design
  reference, not production code.** Recreate it in React using this repo's existing patterns
  (`react-leaflet`, `components/MapView.jsx`, `components/BottomSheet.jsx`,
  `hooks/useIsMobile.js`, `api/*`). Do not port imperative DOM code.
- `heat-field.js` — **the exception. Port this nearly verbatim** to `utils/heatField.js`. It is
  the real tuned field kernel (gaussian accumulation, coverage clamp, temperature ramp, heat
  bloom, soft coast mask, `ink()`), and it is shared with the Plan tab. Rewriting it makes the
  two tabs disagree about what a colour means, which is the one thing it exists to prevent.
- `plan-data.js` — mock catalogue. Replace with real API data; keep the shape.
- `Plan Tab with Heat v5.html` + `plan-tab-v5.js` — the Plan tab, changed in two ways only
  (heat bloom, and the no-ramp-colour-as-text rule). Not a Plan tab redesign. Apply those two
  changes there so both tabs share one colour language.
- `Light or Dark Map.html`, `Map Basemap Options.html` — the evidence behind the basemap and
  ramp decisions. Read before questioning either.

## Build order

1. **Port `heat-field.js`** to `utils/heatField.js` unchanged. Verify the Plan thumbnails still
   render identically before touching anything else. This is the shared contract.
2. **Basemap.** Esri Dark Gray Canvas warmed by CSS filter, plus the town-name reference layer
   gated to zoom ≥ 11.8. Exact URLs, `maxNativeZoom`, filters and attribution in the README.
3. **Coast mask.** Dilated seaward ~4km, applied as a blurred alpha mask — *not* `ctx.clip()`.
   This is the fix for the original "heat is in the sea" complaint. Check 1 below is what
   proves it did not overcorrect and start erasing coastal locations.
4. **The single window control.** One chronological `EV` list — every solar window, plus each
   night's astro, plus aurora only on nights the forecast flags it. Delete the date strip and
   `ForecastTypeSelector` from this tab. The kind chip carries SUNRISE/SUNSET, so the day label
   must not repeat it.
5. **Filters into a popover** with an active count on its chip. The map takes the whole frame,
   no page scroll.
6. **Labels**: greedy single-pass placement, drop-never-stack, chrome seeded as obstacles,
   density ramping with zoom over what is in view. Sort order is score → tide alignment →
   drive time.
7. **Selection**: anchored callout with a tail into a ringed marker, re-anchored every paint.
   Not a Leaflet popup. Clamp it to the band left clear by the overlay chrome, not to the map
   box.
8. **Regions jump list**; remove the search field from this tab's masthead (panning is the
   search here — the postcode becomes a statement of what drive times are measured from).
9. **Tide alignment glyph** on location chips, per the README.
10. **Responsive** passes for iPad and iPhone per the README's table.

## Four things to verify by measurement, not by eye

Each of these was got wrong at least once while designing it. Screenshots will not catch them.

1. **Every rated location paints.** Sample heat-canvas alpha at each location's own lat/lng;
   none may be near zero. Coastal spots are the ones that fail — a raw 1:50m coast clip erased
   7 of 51, including a 5★.
2. **Brightness climbs with score.** Sample composited luminance at a blob core for 3★/4★/5★ on
   each surface; must be monotone. The temperature ramp peaks in the middle, so without the
   bloom the map is loudest where the night is average. Re-measure after any bloom change.
3. **No star numeral is ramp-coloured.** Ramp colour is for fills, which get chosen ink on top;
   as text it fails from both ends of the ramp (1★ and 5★). Minimum 4.5:1; the design measures
   5.56:1.
4. **The callout never covers a control.** Phone layout, several locations, collapsed and
   expanded, assert no overlap with the bottom bar.

## Do not

- Do not even out the temperature ramp's stops or lighten its hot end. Both properties are
  load-bearing and were frozen upstream in `design_temp_scale`.
- Do not try to fix the dark-ground ordering with a blend mode. `screen` and `lighter` are
  monotonic in source luminance and cannot make a dark red outrank a light gold. The
  score-keyed bloom layer is the fix.
- Do not clip the field with a hard path, and do not union per-location discs into the mask.
  Both are explained in the README with what they looked like.
- Do not add a text search field to this tab.

## Three open questions — ask before implementing

- **Astro and aurora scoring.** The prototype's night models are mine, not shipped. Replace
  from `astroApi` / `auroraApi`. If the real astro score only exists for dark-sky locations,
  the event row must say so rather than silently thinning the map.
- **Subject tags.** Woodland / Waterfall / Wildlife are derived in the prototype; they need to
  come off the location record. Filter logic is unchanged.
- **Basemap terrain.** Whether hillshade should be an option or the default is a product call —
  see `Map Basemap Options.html`.

When each step is done, say what you changed and which of the four checks you ran.
