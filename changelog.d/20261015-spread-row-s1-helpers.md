### Changed — Plan card Spread row: count, width and qualifier helpers (S1, no visible change)

First phase of `docs/engineering/spread-row-plan.md`. Adds the pure maths the Spread row's pool label
and visible band counts will use, with no component or CSS change: `formatDriveDurationCompact`
("45m", "1h30") beside `formatDriveDuration`, a derived `shortLabel` on every `REACH_TIERS` entry
(`null` for Any), and in `windowFirstSpread.js` `formatSpreadCount`, `spreadColumnWidthPx`,
`spreadCountLayout` (the hatched bar's count takes part in the column width), `spreadQualifier`
(silent under a finite tier unless every drive in the pool is measured), `spokenPoolPhrase` and
`spreadBandsSpoken`. All are filter/map/select over the existing A10/A27 pool, in the class the
module header already licenses.
