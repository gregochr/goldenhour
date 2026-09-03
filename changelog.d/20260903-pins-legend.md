### Added — Pins mode and the Legend panel on the Map tab (map-v2 P10)

The Map tab's Heat/Medallions segment becomes **Heat/Pins**. A new `components/map/PinsLayer.jsx`
draws the "honest comparison" the design bundle keeps deliberately alongside the field: one dot per
location in the filtered pool, drawn weakest-first so the best sit on top, with no density ramp and
no dropped names — every spot in the pool gets a pin, unlike the chip layer's zoom-budgeted set.
Named locations (every one in this catalogue today) are 26px with the rating and a star glyph
inside, ink chosen per fill via `readableInkOn` — never a fixed colour pair; the 13px unnamed class
exists for a catalogue that has no such rows yet. An unrated or stand-down pin paints the app's
shared no-data grey rather than the ramp's bottom stop, which would otherwise read as an
unsubstantiated "1★". Clicking a pin opens the P9 callout through the exact same handler the
label chips already call; hovering (desktop) shows the identical tooltip card the chip layer
built at P8, reused class-for-class since the two layers never mount together. The home marker
still places itself through the shared greedy pass, seeded from the full live-chrome obstacle
list — the design bundle's own prototype places its home label against an empty obstacle list,
which this port treats as a shortcut, not a design choice.

`MapHeatLayer.jsx` gains a `fieldEnabled` prop (default `true`, so every existing caller is
unaffected). Pins mode passes `false`: the score field, its bloom and the reach rings are
withheld, but the coastline stroke keeps drawing from the same land `Path2D` the clip uses — the
coast is furniture, not a claim about the data, so it survives the toggle. The medallion markers
are held fully hidden (0% opacity, inert, out of the click path) regardless of zoom, overriding the
ordinary zoom-keyed handover that would otherwise hand them back in full past the county scale —
`PinsLayer` is now the one place a location renders in this mode. `MarkerClusterGroup` itself stays
mounted through the toggle exactly as before (an existing, deliberate invariant: an open popup, a
spiderfied cluster and the selected marker must survive a view switch); only the pane's opacity
changes, never the component tree. Azimuth lines are dropped in Pins mode on the tab (decision D-9)
— they were marker-layer furniture with no host in the new pin vocabulary — while the overlay,
which never enters Pins mode, keeps them untouched.

A new `components/map/MapLegendPanel.jsx` is **added alongside** the existing in-map heat legend
key (`wf-map-heat-legend`, unchanged, still rendered) as a `▤ Legend ▾` chip (desktop, bottom-left)
opening a 262px panel: the ramp bar painted from `rampGradientCss()` — never the design bundle's
own stale red-amber-green gradient, which would invert what the field's colours mean since the
temperature scale shipped — with whole-star labels `1★ poor / 3★ / 5★ go`; a `Field → Handing
over → Locations` indicator reading the same handover fraction the canvas fade itself paints from;
the reach-rings toggle, which is `ringsEnabled`'s first writer (the state has existed since P8,
defaulting on, with no way to turn it off until now — gated on a real home COORDINATE, the same
test the ring paint and the ring labels use, never the roster-level `heat.hasHome` signal a first
cut wired it to); and the confidence note. Consolidating the two legend surfaces into one is an
owner call for a later phase, not this one's. The panel joins the tab's popover-exclusivity group
and hides entirely in Pins mode, matching the design's own rule. The handover fraction's underlying
maths (`fadeAt`) moved from `MapHeatLayer.jsx` into a new dependency-free `utils/heatHandover.js`
so the Legend panel can read it without pulling that module's own `d3-geo` chain into the Plan-tab
overlay's bundle — `MapHeatLayer.jsx` re-exports the function so its own imports and tests are
unaffected. Its own Suspense boundary is also now separate from `MapLabels`/`PinsLayer`'s: sharing
one would have re-suspended the field's already-painted picture (and the coastline stroke it now
hosts) for however long the OTHER mode's chunk takes to resolve on the reader's first switch
between views.

The Legend chip shares the bottom-left corner with the LITE viewline-upsell chip in one shared
flex-column wrapper (`.wf-map-chrome-bl`), stacked with a gap rather than one suppressing the
other — they are NOT mutually exclusive: the upsell keys on `auroraStatus`'s alert level (live
regardless of event type), the Legend chip keys on `heatView`/`heatOffered` (which excludes aurora
MODE only), and a LITE reader can never enter aurora mode at all, so an alert can fire while they
sit on an ordinary Heat-view sunset — both must render.

The overlay is untouched: it never receives the `heat` prop, so `heatOffered` stays false there
and none of this — the Pins segment, the coastline-in-Pins-mode contract, the Legend chip — is
reachable from it. Azimuth lines are dropped in Pins mode on the tab (decision D-9) — they were
marker-layer furniture with no host in the new pin vocabulary — while the overlay, which never
enters Pins mode, keeps them, pinned by a recording-`Polyline`-mock test.

An adversarial review round found 9 confirmed issues against the first cut (the bottom-left corner
collision above; the rings toggle's wrong gate above; two missing integration tests for the rings-
toggle chain and the handover indicator; a stale changelog claim; no D-9 azimuth coverage; stand-
down pins losing their dark-red distinction from a plain unrated grey — now threaded through and
painted with the medallions' own `STAND_DOWN_COLOUR`; and a misattributed doc citation in
`PinsLayer.test.jsx`) — all fixed here.
