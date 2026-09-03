### Fixed — the Map callout's region-gloss fallback never fired against real briefing data

`buildRegionGlossIndex` read `region.name` where the served `BriefingRegion` field is
`regionName`, so against a real `/api/briefing` payload the index was always empty and the
callout's reason prose could never fall back to the region gloss when a location's own window
carried no summary (map-v2 §3 P9). The bug was masked by its own tests: both `mapCallout.test.js`
and `MapCallout.test.jsx` built their region fixtures with the same wrong `name` field, so the
suite stayed green while the feature was dead in production. The index now reads and keys on
`regionName` — matching every other region join (`windowFirstRegions.js`, `heatSpots.js`) — and
the fixtures now use the served field name, so they would catch a regression to `name`.
