### Changed — Leaflet and its stylesheet no longer load before the first screen

The vendor split moved from a first-match `manualChunks` function to rolldown priority groups
(`react`, `leaflet`, `geo`, `recharts`). React previously rode inside the `leaflet` chunk, so every
reader's first load fetched Leaflet (about 47 KB gzip) and its render-blocking stylesheet (about
6 KB gzip) whether or not they ever opened the Map. React now has its own chunk, and Leaflet loads
only with the Map view. The first-load closure drops from 286.75 KB to 236.33 KB gzip. CI gains a
`check:entry` step that fails the build if `dist/index.html` ever preloads Leaflet, its CSS, or the
recharts, geo or heat-field chunks again.
