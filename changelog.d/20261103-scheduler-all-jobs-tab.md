### Changed — Scheduler: All jobs tab with plain-English descriptions and a richer job panel

The Scheduler now opens on an **All jobs** tab: every job is grouped by how it is scheduled
(sun-relative, cron, fixed delay), with a plain-English paragraph on what it does, a purpose tag, a
£ Claude tag where it spends money, its schedule, its next fire and how its last run went. You can
search by name, alias, description or purpose, and filter by Paused, Off in config, Uses Claude and
Last run failed. The header shows the job count, the UK time zone, how many are paused and the next
scheduled job. A job's panel is now a drawer over the list and reads top to bottom: what it does,
its schedule and why it runs then, the next three fires, its last run and one-line result, its
Claude spend over 30 days, what it calls out to, how it connects to other jobs (each a link) and the
existing Run now and Pause controls. A **Day's jobs** tab is a placeholder until the day picture
arrives. The tab and date live in the address (`?view=all`), and older job links still work.
