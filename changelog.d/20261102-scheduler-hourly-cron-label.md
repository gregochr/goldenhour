### Fixed — Scheduler shows "Every hour at :20" for hourly jobs

The Scheduler tab described an hourly cron such as `0 20 * * * *` as "Daily at Invalid Date GMT", and silently rendered step or list minutes (`*/5`, `0,30`) as `:00` and a range like `MON-FRI` as a single day. Hourly crons now read "Every hour at :20", and any cron the label cannot describe faithfully (steps, lists, ranges) is shown as the raw expression instead of a wrong sentence.
