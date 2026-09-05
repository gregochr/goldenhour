### Changed — landing site revamped: new skin, map-first hero, platform status

The five pages under `landing/` are rewritten onto a shared stylesheet
(`landing/photocast.css`) — Bricolage Grotesque for display type, IBM Plex Sans for
body, DM Mono for labels, over a warm graphite ground. The accent pair is the app's
own verdict language: lichen green for Worth it, amber for Maybe.

The hero now leads on the map having already made the call, with the verdict card
rendered in CSS rather than baked into a screenshot. A new `#platforms` section states
where you can actually get it — Browser *very close*, iPad and iPhone *coming soon* —
and the nav links to it as "Apps". The six existing features are kept verbatim and
renumbered 02–07 beneath a new 01 covering the map landing. The story is retold
against the season rather than a fixed February date, and the stale bluebell teaser is
gone. Pro's price reads "TBC, billed monthly" in `terms.html` section 4, matching the
pricing section, which is the only change to the legal copy — privacy, terms and
acknowledgements are otherwise ported word for word.

Two defects in the incoming bundle were fixed before it landed. `landing/Dockerfile`
copies files one by one and had no line for the new stylesheet, so the deployed
container would have served every page unstyled while the local files looked correct.
And `.mast nav a` (specificity 0,1,2) out-specified `.btn` (0,1,0), which painted the
sticky "Start free" button — the primary call to action on all five pages — in
`--ink-soft` beige on amber at **1.24:1** contrast, dropping to 1.71:1 on hover.
Excluding `.btn` from both masthead nav rules, the way the bundle's own mobile
`display` rule already did, restores the intended near-black at **9.03:1**.

The three screenshot slots in `index.html` are deliberate dashed placeholders: the
existing `screenshot*.png` files show superseded UI and were not carried over.
`favicon.png`, `logo.png` and the screenshots all stay on disk; `logo.png` is no longer
referenced, as the wordmark is now type plus a CSS dot.
