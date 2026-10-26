### Changed — tide heights and the tide match are Pro on the last two open paths

`GET /api/tides`, `/api/tides/stats` and `/api/tides/stats/all` now serve Pro and Admin only; a Lite
reader gets 403. `GET /api/forecast/{id}` drops `nextHighTideHeightMetres`, `nextLowTideHeightMetres` and
`tideAligned` for Lite and keeps the tide state, the high and low water times, the lunar tide type and
the surge fields for everyone. No Lite screen read either path, so nothing visible changes for Lite;
Pro and Admin responses are unchanged.
