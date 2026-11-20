### Changed — Glance-to-Map cleanup (G2c): what the window popup left behind is gone

Frontend and docs only; no rating, verdict, Best bet choice or Claude prompt changes. Deleted the exports, modules, CSS
rules, keyframes, tests and doc text that only the removed window popup and pick dialog used: the `buildWindowRows` chain
and `card.rows`, `buildRegionRows`/`buildRegionSeries`, the provider's `regionPicks`/`regionSeries`/`scoreIndex`, the
dawn-race model, `scaleRgbaAlpha`, `kmPerPx`, the orphaned `SortableHeader`, the popup's warning row (the shared
`WindowWarningsBody` stays), `sheetSpotOf`/`sheetOffersMore`, the spot list's Plan-tab opening branches and 91 dead CSS
classes. Stale comments and the Plan tab reference now describe the Map-first flow.
