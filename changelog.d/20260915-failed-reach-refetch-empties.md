### Changed — a failed drive-time refetch empties the drive times rather than leaving the old home's standing

When the Plan provider's `GET /api/user/settings/reach` failed after the reader's home or its drive
times changed, it kept the figures from before: after a move, the old house's drive times and
leave-by lines on every spot, beside a tick line naming the new home. The owner's decision
(2026-09-15) is to empty them instead: unknown claims nothing, and the old figures claimed a drive
and a leave-by time the reader no longer had. Traced in the code; not seen in a browser.

The `.catch` now sets the empty map — the state of a reader with no postcode — and is guarded by the
effect's cleanup like the answer, so a superseded request's failure cannot empty a map the newest
request has filled, and one landing first cannot empty it while the newest is still out. It lasts
until the next change to the home or its drive times, or a reload.

What the empty map looks like: the strip loses its reach lines and the footer stops naming drive
time; the map callout drops its drive and leave-by lines, and the ring labels fall back to miles. On
the Map tab, "My area" widens to the whole catalogue and its scope row goes, the camera refits to it
(in My area, with no region jump in force), and the window pill's verdict and the landing card answer
for everywhere — the same state every move already gives until the drive times are recalculated,
because a move clears the stored drive times. Now that re-saving the same postcode moves nothing (a
companion entry), no save that changed nothing can end here.

⚠️ **Not fixed here, and named so it reads as known:** the Map tab's drive filter hides a spot with no
measured drive time, where the Plan tab's reach lens lets it through (`reachLens.js`), so a tier
carried onto the map through a Plan→Map door shows none of the spots once the map is empty — "0 of N
shown · carrying within 45 min". That predates this change — a PRO reader with a postcode and no
drive times yet meets it already — and is filed separately.

Pinned in `WindowFirstBriefingHomeSettingsFetchOrder.test.jsx`: the newest request failing empties an
answer on screen, a superseded failure landing first leaves it, and one landing after the newest
answer leaves that — each settled inside an awaited `act`, each beside a control showing the answer
landed. Mutation-checked with the rest of the change (see the settings-record entry).
