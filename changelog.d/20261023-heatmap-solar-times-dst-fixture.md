### Fixed — HeatmapGrid solar-times test no longer breaks when the UK clocks change

The "day header solar times" tests built their fixtures from `futureDateStr(n)` (wall-clock relative) and hard-coded a +1 h BST offset, so once a fixture day crossed the October clock change (25 Oct 2026) the expected UK times were wrong and `main`'s frontend job went red. The block now uses fixed summer (BST) and winter (GMT) civil dates, so it passes on every day of the year and still fails if the component stops converting UTC to UK time.
