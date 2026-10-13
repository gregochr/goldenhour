### Added — a `SOLAR_OFFSET` schedule type, its trigger, a switch endpoint and a preview (no job uses it yet)

Solar-relative refresh plan, P2a. The scheduler can now time a job a fixed number of minutes before sunrise or
sunset at a reference point (Durham by default, `photocast.scheduler.solar-reference.lat|lon`; a bad value logs
ERROR and uses Durham, it never stops startup). **No production row is switched by this change**: migration V171
adds the nullable `solar_event` and `solar_offset_minutes` columns (with CHECKs) and touches no row.

`SolarOffsetTrigger` computes in instants (event minus offset, so DST cannot create a missing or doubled hour),
never fires before 00:05 UK of the day it serves, never returns null (a throwing or NaN calculator, the
midnight sentinel for an event that does not occur, or a fire outside 00:05-06:00 UK for sunrise or 10:00-20:00
UK for sunset all fall back to the job's stored cron, and as a last resort to a time a day on), fires strictly
after both now and the last scheduled execution, and logs one INFO line per fire. Only `near_term_batch_evaluation`
and `intraday_forecast_refresh` may become solar.

`PUT /api/admin/scheduler/jobs/{jobKey}/schedule-type` (ADMIN) switches a job between `CRON` and `SOLAR_OFFSET`
(`{scheduleType, solarEvent?, solarOffsetMinutes?}`). It validates and builds the new trigger before anything is
written or cancelled, writes the three columns in one column-scoped update, and installs the new schedule before
cancelling the old one; the stored cron is kept unchanged so the switch is its own rollback. The job list DTO gains
`solarEvent` and `solarOffsetMinutes`. `GET /api/admin/scheduler/solar-preview` (ADMIN) shows, for both cycles,
today's and tomorrow's event and fire instants, the year's earliest and latest fire time of day, the reference
point and the median cycle duration, all from the trigger's own arithmetic.

On startup a solar job that missed today's fire runs once immediately if no run of its cycle has got past
submission since the fire (a run the restart killed before submitting does not count) and useful lead remains
(before 05:00 UK for the morning cycle, at least 90 minutes before sunset for the afternoon one). The pipeline's
startup recovery scan is now ordered to finish before the scheduler starts, so it can never take a catch-up run
that has just begun for one the previous process left behind. The existing admin screen still treats a solar row like a fixed-delay one (P2c).
