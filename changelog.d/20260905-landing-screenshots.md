### Added — real screenshots in the landing page's two figure slots

The revamp shipped with dashed placeholders because the existing `screenshot*.png` showed
superseded UI. Both slots now carry current captures, redacted and cropped.

The hero shows the Map tab's heat field over northern England for tomorrow's sunrise,
cropped to the map pane itself: the app's own masthead and window control are dropped so
the CSS verdict card — the design's device for "the call is already made" — has clean map
under it rather than a second masthead competing with the landing page's own. The features
figure shows a location sheet with the Fiery Sky and Golden Hour bars, the blue and golden
hour times, and the written explanation, which is exactly what its caption promises.

**The owner's home postcode was redacted from both.** It appeared as `HOME · DH3 4NG` in
the masthead of one and `1h 55min from DH3 4NG` in the sheet header of the other. A UK
postcode identifies a handful of houses, so publishing it on a marketing page would have
published an approximate home address. Both were removed by sampling the surrounding
background and painting over the glyphs, so the UI reads as though the text was never
there; the masthead pill was additionally reflowed left — text and rounded end cap
together — so it closes up rather than leaving a hole. Verified by asserting zero light
pixels remain in either region.

Served as WebP at 1600px: **113 KB for both**, against 679 KB for the equivalent PNG pair,
at slightly better fidelity (RMSE 1.99 vs 2.21 for the hero). `nginx:alpine`'s bundled
`mime.types` was checked on the production container before committing to the format —
it maps `image/webp`. Both carry `width`/`height` so neither shifts layout while loading,
and descriptive `alt` text.

⚠️ Both `COPY` lines were added to `landing/Dockerfile` in the same commit as the files.
That file enumerates assets one line at a time, so an image added without one is a broken
image in production while looking correct locally — the same trap that would have shipped
the stylesheet missing.
