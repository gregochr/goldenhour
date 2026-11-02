### Fixed — Tide fetch-window test no longer depends on the real date

`TideFetchWindowTest` mixed a fixed August date with the wall clock and began failing once the real date passed the fixture's frontier minus seven days. `TideService` and `WorldTidesIngestionService` now take an injectable UTC `Clock` (package-private constructors; production still uses the system UTC clock), and the test pins one fixed clock. A new test covers the clamp of the delete's lower bound to the start of today.
