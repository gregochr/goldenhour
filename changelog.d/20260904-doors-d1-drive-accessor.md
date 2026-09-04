### Changed — the Map tab reads one drive accessor, and draws home geography only at home

Phase D1 of `docs/engineering/plan-to-map-doors-plan.md`. Four leaks closed: the Regions jump
list ran its own second precedence expression for "which drive map is in force"
(`driveOverride || reachById`) beside `driveMinutesFor`'s own; `mapReachMeasured` (which gates the
ring labels' duration-vs-distance wording and the Legend panel's rings-segment offer) tested
`homeCoords` alone, so an away origin with a fully measured region-base matrix still read
"unmeasured"; and `driveMinutesFor` itself read a separately-fetched `userDriveTimes` at home
while the Plan cards and the jump list already read the shared `reachById` map, so the tab and the
Plan tab could disagree about a drive time after a refresh until the tab's own fetch caught up.

Verified in the backend first: `GET /api/user/settings/drive-times` (`DriveTimeResolver.
getAllMinutes`) and `GET /api/user/settings/reach` (`ReachService.getReach`, which calls the SAME
`getAllMinutes`) both read `UserDriveTimeRepository.findByUserId` — one query, one
`user_drive_time` table — so the Map tab's `driveMinutesFor` now reads `reachById`, the prop the
pane already passes from the same provider that feeds the Plan cards, and no longer fetches drive
times itself. The frozen Plan-tab overlay, mounted outside that provider and handed no `reachById`
at all, keeps its own fetch — two sources split by *surface* is fine; two sources on *one* surface
was the defect.

`MapView` also gains an `origin` prop (the pane's live value; the overlay never passes one) and
derives `homeGeo = origin ? null : homeCoords`, fed to the HOME marker, the reach rings, their
labels and the Legend panel's rings toggle instead of the raw postcode coordinate — so planning
from a region base no longer draws a HOME dot and rings around the reader's actual house beside
that base's drive times. The `⌂` control stays present while away (it already refits to whichever
area is framed, home or origin-scoped) and `heat.beyondRegionNames` stays the one deliberately
home-only read, recorded in the accessor's own doc block rather than left for the next audit to
refile as a leak.

Adversarial review caught the `⌂` control prompting for a postcode while away: it read the raw
`homeCoords` prop alone for its own enabled/label state, so a reader planning from an away origin
who had never saved a home postcode still saw "Set your home postcode in Settings" and a click
opened Settings, for a reset action that needs no postcode once an origin is in force. It now also
takes `origin` and is actionable whenever a home coordinate or an origin exists.

19 new or rewritten tests across `MapViewReachMeasured.test.jsx` and
`MapViewDriveOverride.test.jsx`: the away/home pairs for the jump list, the HOME marker/rings/ring
labels/legend toggle, `mapReachMeasured`'s own away case (measured matrix with no home postcode
at all → true; empty matrix → false), a negative test pinning that the overlay still renders a
drive figure from its own fetch with no `reachById` prop, and the `⌂` control's four home×origin
state-and-behaviour cases.
