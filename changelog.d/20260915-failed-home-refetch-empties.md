### Changed — a failed refetch after a home save empties the drive times and the map's home, rather than leaving the old home's standing

After a home save — a new postcode, or a drive-time recalculation — the Plan provider asks
`GET /api/user/settings/reach` again and `App` re-reads `GET /api/user/settings` for the map's home.
When that newest request failed, both kept the answer from before the save: after a move, the old
house's drive times and leave-by lines on every spot, and the map's HOME marker and reach rings on
the old house, beside a tick line that had already gone to unknown (the provider's own settings read
has always emptied on a failure). The companion fetch-order changes left this open; the owner's
decision (2026-09-15) is to empty instead. Unknown claims nothing, and the old figures claimed a
drive and a leave-by time the reader no longer has. Traced in the code; the app itself was not seen
in a browser.

- **Reach.** The `.catch` now sets the empty map — the state of a reader with no postcode, so the
  strip loses its reach lines and the footer stops naming drive time. It is guarded by the effect's
  cleanup like the answer: a superseded request's failure cannot empty a map the newest request has
  filled, and one landing first cannot empty it while the newest is still out.
- **The map's home.** `useHomeAndMapColour` now starts at `undefined` rather than `null`, and a home
  save's failed read returns it there. The difference is the point: the map's ⌂ control answers
  `null` with "Set your home postcode in Settings", and a failed read is no evidence of that.
  `WindowFirstMapPane` and `MapView` lose their `homeCoords = null` defaults so an unknown home
  reaches the control as unknown, and the control renders nothing while the home is unknown and no
  origin is in force; `index.css` hides its empty Leaflet container (`:empty`), whose border, ground
  and margins would otherwise paint a blank box. It stays attached rather than being re-added, which
  would put it above the zoom bar. This also ends the prompt showing for the length of every page
  load (the old initial `null`) and for good after a read that failed at page load. Under an origin
  the control is actionable either way and is unchanged; `null` still gets the prompt.
- **A colour save** re-reads the same endpoint, and its failure leaves the home alone: it asked about
  a home that has not changed since the answer on screen, and the provider asks nothing on a colour
  save, so emptying the map's home would drop the marker beside a tick line still naming it. The hook
  keeps the home with the home counter it answers and empties it only when that counter has moved
  since. A recalculation's failed read does empty it — the counter cannot tell the two home saves
  apart, and the tick line's own read does the same. The colour preference is kept on any failure.

The price: after a failed refetch the reach lines, leave-by times, marker, rings and ⌂ are gone until
the next home save or a reload, since a close no longer retries (the counter change's price). While
the ⌂ is hidden the zoom bar drops 50px into its place and moves back up when it returns (measured on
the built CSS in the three engines below); at page load the app opens on the Plan tab, so the read has
usually answered before the map is on screen. If keyboard focus is on the ⌂ when a failed read
empties it, focus falls to the page; that needs a failed read while the ⌂ holds focus, and is not
handled.

Pinned in `WindowFirstBriefingHomeSettingsFetchOrder.test.jsx` by two new tests — the newest failure
empties the map over an answer on screen, and a superseded failure landing first leaves it — while the
superseded-failure-after test, which passed either way while the catch wrote nothing, now kills the
guard's removal. In `useHomeAndMapColour.test.jsx`, six new, with the probe telling the three states
apart: unknown until the first answer, and `null` only when the server says so; a home save's failed
read empties; a superseded failure landing after the newest answer, and landing first, does not; a
colour save's failed read keeps the home and the colour; and one after a home save nothing has
answered yet empties. For the ⌂: an empty container while unknown, and one container filled and
emptied as the answer comes and goes, never taken off the map (`MapViewCentreOnHome.test.jsx`); still
actionable under an origin with the home unknown (`MapViewDriveOverride.test.jsx`, whose two
"no postcode" tests now pass `null` rather than omitting it); the pane hands the home down as given
(`WindowFirstMapPane.test.jsx`); and a new `mapHomeControlCascade.test.jsx` resolves slices of the
real `index.css` and Leaflet's sheet, in bundle order — an empty container is not displayed, a filled
one is. The built CSS was also loaded in Chromium 151, WebKit 26.5 and Firefox 153: the empty
container computed `display: none` at 0×0 and the filled one 34×32, and an empty one between two
filled ones took no space. Every late settle is inside an awaited `act`, and each of the nine new or
changed failure tests, run alone with the settle helper's `await` removed, fails. Twenty-one mutants,
all killed, each by the tests that name what it breaks: the reach catch's guard removed, or the catch
writing nothing (two); in the hook, the catch's guard removed, the catch writing nothing, starting at
`null`, clearing to `null`, clearing on every failure, keying on the last counter to move instead of
the home counter the answer is for, forgetting that counter, and resetting the colour on a failure
(eight); a `= null` default put back in the control, in `MapView` or in the pane (three); unknown
ignoring the origin, the button rendered while unknown, the control re-added on each change, and
`null` treated as unknown (four); and in the stylesheet, the `:empty` rule removed, made to show, made
to hide the filled control as well, or widened to hide it outright (four).
