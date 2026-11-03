### Added — Scheduler day view data and schedule preview

The Scheduler gets the data behind its day picture and its schedule editors, backend only for now.
`GET /api/admin/scheduler/day?date=` (admin, a UK date within a week of today) serves, for one day,
every job's fires with their outcome (succeeded, partial, failed, skipped, running, still to come, or
missed when an expected fire left no trace), the fallback flag for a solar job that used its stored
cron, a compact summary for the jobs that repeat (expected count so far, counts by outcome, and the
failed or partial runs), the day's sunrise and sunset, the forecast-cycle watchdog's windows, and the
weekly jobs that do not fire that day. Cycle fires take their end time and outcome from the pipeline
run they started. `POST /api/admin/scheduler/jobs/{jobKey}/schedule-preview` is a dry run: it turns a
list of UK times into the cron, validates it exactly as saving would, and returns the next three
fires, the year's earliest and latest time for a solar rule, and any other job firing within 30
minutes. `PUT /api/admin/scheduler/jobs/{jobKey}/schedule` now accepts an optional `timeZone`
(`Europe/London` or UTC) so a cron can be edited in UK time; leaving it out keeps the job's zone. A
fixed-delay job's displayed next fire now follows the application clock rather than the wall clock.
