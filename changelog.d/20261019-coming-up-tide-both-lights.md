### Added — Coming up tide-run cards name the high water at sunrise and at sunset

Every tide-run card now shows two dated rows, sunrise first, in place of the single `tide …` alignment row:
`sunrise · HW 07:40, 10m before · Wed 28` (or `no high water within an hour · nearest HW …`, qualified
"on the days measured" for a partly measured run). The rows name HIGH water only: a low water at sunrise or sunset
is no longer named on the card. The run's day is the true peak day when its water is aligned for that event,
otherwise the closest day. The rows also ride a supermoon-hosted merged card. The old `alignment`/`alignmentDate`/
`noAlignment` meta are no longer written, the merged-card ` on the <ordinal>` suffix is gone (both rows are always
dated), the coincidence line's HW clock reads the new rows, and the sparkline's `tide.phase` is always `HW`. On a tide
card each row takes its own line and wraps without leaving a bare `·` at a line start.
