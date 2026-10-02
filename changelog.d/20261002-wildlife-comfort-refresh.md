### Fixed — wildlife hides have an hourly comfort forecast again

The hourly comfort table (temperature, feels-like, wind and rain between sunrise and sunset) for
wildlife hides had no data source since 2026-02-27, when its scheduled trigger was commented out
by accident in an unrelated commit and never replaced: production held no `HOURLY` rows at all, so
every hide read "No hourly forecast available". A new job, `WildlifeComfortRefreshJob`
(`wildlife_comfort_refresh`, seeded by V161), runs at 05:30 and 17:30 UTC for every enabled
location whose only type is WILDLIFE, for today and the next five days. It makes one batched
Open-Meteo forecast request for all hides, no air-quality request and no Claude call, and reads
only the six comfort fields, so a missing cloud or visibility hour no longer costs a hide its
table; an hour with no temperature is skipped on its own.

Each run replaces the previous `HOURLY` rows for the place and date inside one transaction, so
storage stays flat rather than growing about 230 rows a run. A failed fetch, a hide missing from
the response or an empty extraction leaves the previous rows in place. This makes `HOURLY` rows the
one exception to `forecast_evaluation` being insert-only; the delete is scoped to `HOURLY` in the
query itself and cannot reach a scored sunrise or sunset row. Waterfalls are not included — the
backend has never written waterfall hourly rows, and CLAUDE.md's claim that it did is corrected.

The table is served by `GET /api/forecast` as before; drawing it on the Map tab is a separate
change, so until that lands it appears only in the Plan-tab overlay popup. The old `RunType.WEATHER`
engine is left in place, untriggered, for a follow-up to remove. The unbound
`forecast.schedule.wildlife` keys are removed from `application-prod.yml` and `application-dev.yml`.
