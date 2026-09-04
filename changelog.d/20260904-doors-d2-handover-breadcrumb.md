### Added — a Map tab breadcrumb that names what a Plan door carried, and a way to clear it

Phase D2 of `docs/engineering/plan-to-map-doors-plan.md`. Nothing user-visible yet — no door button
ships in this phase — but the whole handover payload the doors need lands here, on the SAME
`mapTabHandoff`/`tabRequest` nonce channel the Map overlay's own "open the full map" hatch already
uses, distinguished downstream by a new `source: 'plan'` field. `App.jsx` gains
`openMapTabFromPlan(door)`; `WindowFirstShell.jsx` gains one internal `openMapTab(door)` that
closes the popup and the window sheet first and merges in the Plan's own rating/reach lens values
at the moment of the tap (unused until D3/D4 wire real buttons to it). `WindowFirstMapPane.jsx`
tells a door handoff apart from the hatch's own on the one shared channel and forwards it to
`MapView` as a single `planHandoff` prop, never touching the five older per-field `handoff*` props
a door must not also trigger. One nonce-keyed effect in `MapView.jsx` applies the whole payload
atomically — event, floor (a Plan "Any" rating lands as the map's loosest real floor, 1★+, never a
`null` state the map has never had), reach tier, region (via the tab's own `jumpToRegion`/
`resetToMyArea` scope-flip semantics, never a bounds-only fit), and the carried location, resolved
off the full roster so a location the carried floor would otherwise filter out of the visible pool
still gets its selection callout.

A new `components/map/MapBreadcrumb.jsx` mounts above the map frame, outside the Leaflet container
entirely, whenever a door handoff is live on the tab: `← Plan`, the active window's day and kind,
and — only while each fact still genuinely holds on the map's own live state, never a stored
snapshot of what was carried — a `carrying …` clause naming the origin, the rating floor, the reach
tier and the region, each derived fresh on every render. `clear` resets all four in one press
(rating, reach, scope, then the shared origin) without touching subjects or dark-sky, which were
never carried. `← Plan` lands back on the Plan tab itself, reopening no dialog and carrying no
window key.

Origin is deliberately never part of the payload — it is shared state the Map tab already reads
from the same context the Plan tab does, so carrying it would be the increment's own `org`-in-the-
URL mistake in reverse.
