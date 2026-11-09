### Added — 7, 90 and all-time ranges on the Runs Cost view

Operations, Runs, Cost now offers 7, 14, 30, 60 and 90 days and All time. All time runs from the earliest
recorded run (the Runs history is capped at 400 days) to yesterday with nothing to compare against: the
verdict card says what was spent since that day, the table shows Type, Spend and a bucketed sparkline, and
the chart's bars touch when a slot is narrow. `GET /api/admin/runs/cost` takes `days=7|14|30|60|90|all`
(anything else is 400) and serves a new `allTime` flag; how a cost is computed is unchanged. On a phone the
period buttons read 7d, 14d, 30d, 60d, 90d and All (their accessible names stay long); `range=all` survives
in the address.
