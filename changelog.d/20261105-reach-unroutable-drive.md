### Fixed — a place with no road route no longer counts as within your drive

A place OpenRouteService cannot route to (a summit such as Helvellyn) has no drive time, and a missing drive
time used to pass every reach tier, so the 45-minute window card could name it as the best place "within
reach". The drive from your home, and from an away base, is now one of three kinds: measured, pending (nothing
has measured it yet, still shown) or unroutable (your home or the base was fully measured after the place last
moved, and there is no route). A finite drive tier now drops the unroutable places, for the spread, the Best
line, the tide fit, the region rail, the "See all spots" sheet and the plan messages alike; "Any" and a reader
with no postcode are unchanged. The Best line's "beyond 45 min" never calls an unroutable place far (it says
"no route from home", or from the base town when planning away), and the spread tooltip says how many places
have no route.

Moving a location's coordinates now discards the drive times to its old point and re-measures them that night
(it used to leave them, and only the admin "Run now" refreshed them); until then it reads as pending. New
column `locations.drive_target_changed_at` (migration V220) records when a place's routing point last changed,
and `regions.drives_measured_at` when a base was last measured in full; both are served (`/api/locations`,
`/api/regions`) and `/api/user/settings/reach` now carries a `driveStatus` per place. A measurement that
returns fewer durations than places is no longer stamped as complete, and a region base measurement that
finishes after the base moved is discarded.
