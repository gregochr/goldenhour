### Changed — selection on the Map tab moves off the Leaflet popup, onto an anchored callout (map-v2 P9)

Clicking a marker, a location chip or a pin on the Map tab no longer opens a Leaflet `Popup` (desktop)
or a `BottomSheet` (mobile) — both are gone from the tab, replaced by a 34px selection ring plus a
286px anchored card (`components/map/MapCallout.jsx`), portalled beside `MapLabels`' own hover
tooltip so it clears every chip and chrome piece on the frame. "A popup covers exactly the ground you
just asked about" was the design's own complaint; the ring stays on the point and the card sits beside
it instead.

The card's anchoring (`utils/mapCallout.js`) is recomputed on the same rAF-guarded cadence the field
and the labels already repaint on, so it travels through pan and zoom rather than only updating on
open: it prefers 22px below the marker, flips above when that would run under a chrome bar spanning
at least half the frame's width, clamps horizontally to an 8px margin, and clamps its own tail to stay
inside the card. Opening a selection calls `map.panInside` once, with the same `[70, 150]` padding the
design specifies, so the point and the card both land in view without recentring the whole map.

Contents: the location's name, region and subject tags (as WORDS — "Northumberland & Tyneside ·
Seascape" — never the compact-row icon glyphs `locationTypeIcons` is reserved for); a verdict block
whose `N★ Worth it/Maybe/Poor` reuses `utils/mapLabels.js`'s own `verdictWord` and thresholds rather
than a second copy, filled with the ramp colour at that rating and inked with `readableInkOn`
(`utils/windowFirstSpots.js`) so a dark-red "1★ Poor" badge gets light text instead of the same fixed
dark ink every other rating got — the temperature ramp's hot end is nearly as dark as its cold end is
light, and a hardcoded ink failed contrast at exactly that end; a reason line drawn from that
location's own served summary (`GET /api/briefing/evaluate/scores`, the same feed the Plan tab's
four-day sheet reads) and falling back to the window's region gloss when the location itself carries
none — never invented for a night row, which has neither; a facts row (Drive, Leave by, Dark sky) that
omits any fact an unmeasured drive would have to derive, and that shows straight-line miles only at a
home origin, never alongside an away base's drive minutes; the window's topic tags, with a tide topic
(`KING_TIDE`/`SPRING_TIDE`) shown only on a location that actually carries a tide preference; and a
collapsed "This location, every window" strip whose solar cells read the same per-location score index
as the reason line and whose astro/aurora cells read this location's own served star straight off
`astroConditionsByDate`/`auroraResultsByDate` (`MapView.jsx`'s existing state, the same source
`utils/mapEvents.bestOfNight` already reads) — a cell reads "unscored" only when this location
genuinely has no row in that night's results, never merely because night rows are a different kind of
window. Every null-score surface distinguishes "the ratings response has not landed yet" (`Loading…`/
`…`) from "landed, and this is genuinely unscored" (`Not yet scored`/`—`) via a `scoresKnown` flag
threaded down from the briefing context. The strip's own kind badge no longer collapses Sunrise and
Sunset to the same "SUN" (the design bundle's own recorded ambiguity) — `RISE`/`SET`/`AST`/`AUR`, none
colliding. Two actions close the card out: *Zoom to it* (`flyTo`, floored at zoom 12.6) and *Open in
Plan*, a real cross-tab handoff (`App.jsx`'s new `openLocationInPlan`, mirroring the existing map-tab
hatch in reverse) that switches to the Plan tab through `WindowFirstShell`'s own `selectTab` and opens
that location's four-day sheet as the only dialog layer.

**A live regression, root-caused and fixed before merge**: a chip click reached `onSelect` and set the
selection correctly, but the selection was wiped on the very same click, every time — nothing ever
appeared. Traced to event order: a location chip is a plain HTML `<button>` inside `MapLabels`' own
Leaflet PANE (a real descendant of `.leaflet-map-pane`/`.leaflet-container`), not a Leaflet `Marker` —
so, unlike a real marker (which stops its own click bubbling via `bubblingMouseEvents: false`), the
click kept bubbling into Leaflet's own container listener and fired the map's background-click
handler (P7's `MapBackgroundClickController`) immediately after React's, clearing the very selection
it had just set. Fixed the same way `CentreOnHomeControl` already fixes it for an HTML control sitting
over the map: `L.DomEvent.disableClickPropagation` on the label layer's root, applied once via a
callback ref. A second, related race surfaced during the SAME fix: `WindowControl`/`FiltersPopover`
each close their own menu via a `document`-level `mousedown` listener, independent of and earlier than
this controller's `click` handler, so a background click while a popover was open collapsed the
intended "close the popover first, the callout second" ordering into "close both at once." Fixed by
snapshotting `openMapMenu` on the map's own `mousedown` (which — because `.leaflet-container` is an
ancestor of `document` — reaches this controller before the document-level listener does) rather than
reading it from a `click`-time closure.

A background click and an `Esc` press both close the *nearest* open layer first — a popover, if one
is open — and only take the callout on a second press, matching the two-deep-stack convention the
rest of the app's dialogs already follow rather than the design bundle's own "close everything on one
click/press". Recorded as a deliberate divergence, not an oversight: the bundle had no other
focusable chrome to protect from a stray Escape, and this app does. The inbound `handoffLocationName`
channel now branches on `overlayMode`: the Map tab selects the location and lets the callout pick it
up reactively, with no popup left to open; the Plan-tab overlay keeps its exact previous behaviour,
opening the marker's own bound popup once its fly-to animation settles.

The callout is deliberately not a modal — no focus trap, no `aria-modal`, consistent with this arm's
existing stance that only one stacked dialog is ever the modal layer.
