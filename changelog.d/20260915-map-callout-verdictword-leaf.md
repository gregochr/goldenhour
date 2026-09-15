### Fixed — `MapCallout`'s one import was defeating the Map tab's `d3-geo` lazy boundaries

`MapView.jsx`'s comment above its `lazy()` calls claimed the selection callout (`MapCallout.jsx`)
"imports no `d3-geo`-carrying module" and so needed no lazy boundary of its own. That was false:
`MapCallout.jsx` statically imported `verdictWord` from `utils/mapLabels.js`, and `mapLabels.js`
is not a leaf — it statically imports `centroid` from `utils/heatField.js`, which statically
imports `d3-geo` and `topojson-client`. "MapCallout only reads `verdictWord`, which never touches
`centroid`" is true and irrelevant: a source-level `import` names a module, not the one export a
caller happens to use, and Rollup's chunk-splitting did not cleanly separate the two.

Measured on the pre-fix build, the eager `MapView` chunk's full transitive closure was 18 chunks /
842,323 bytes raw. Two of those chunks were the `geo` chunk (`d3-geo` + `d3-array`, forced into
its own chunk by `vite.config.js`'s `manualChunks`) and a second, Rollup-merged chunk holding
`centroid`, the heat-field canvas kernel and topojson's feature decoder — `regionLabelItems`/
`hottestRegion`, dead code on this path since `MapCallout` calls neither, were duplicated straight
into the `MapView` chunk alongside `verdictWord`, and pulled that merged chunk in with them. Net
effect: ~32.3 KB raw / ~12.75 KB gzip of `d3-geo`/topojson/heat-kernel code was loading eagerly
for every Map tab open **and** every Plan-tab overlay mount (`WindowFirstMapPane.jsx`, which
mounts `MapView` before a reader has chosen Heat or Pins, or opened the callout at all) — precisely
the weight the `lazy()` boundaries around `MapHeatLayer`/`MapLabels`/`PinsLayer` exist to keep off
that network path, for the two sessions (Pins-only, and the overlay) that never render any of them.

Fixed by extracting `verdictWord` (and its two threshold constants) out of `mapLabels.js` into its
own leaf module, `utils/verdictWord.js`, with nothing else in it for a future addition to
accidentally import `heatField.js` next to. `MapCallout.jsx` now imports directly from there;
`mapLabels.js` re-exports the same three bindings (pinned by a test) so `MapLabels.jsx`/
`PinsLayer.jsx` need no import-path change. Verified two ways rather than reasoned:
`MapCallout.jsx`'s full source-level transitive import closure (21 files) no longer reaches
`mapLabels.js`, `heatField.js`, `d3-geo` or `topojson-client` at all — a structural absence of the
edge, not Rollup tree-shaking one away — and the built `MapView` chunk's own transitive closure
dropped to 17 chunks / 809,126 bytes raw, with neither the `geo` chunk nor any heat-field/topojson
code present.

`MapView.jsx`'s comment now names `MapCallout`'s real leaf-module imports (`utils/mapCallout.js`,
`utils/verdictWord.js`, `utils/scoreRamp.js`, `utils/locationSheet.js`, `utils/locationTypes.js`,
`utils/windowFirstSpots.js`) and records the measurement, so the next audit has the numbers rather
than another one-hop claim to re-verify from scratch.
