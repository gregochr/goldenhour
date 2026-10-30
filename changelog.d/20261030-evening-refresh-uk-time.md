### Fixed — Evening sunrise refresh runs at 21:00 UK time all year

The evening sunrise refresh was seeded as a UTC cron (`0 0 20 * * *`), so it fired at 21:00 in summer
but at 20:00 GMT once the clocks went back. Scheduler jobs now carry an optional time zone
(`scheduler_job_config.time_zone`, V203; empty means UTC, so every other job is unchanged) and the
evening refresh runs `0 0 21 * * *` in `Europe/London`. The forecast-cycle watchdog judges that job's
fires in the same zone, and the Scheduler screen shows its time as "21:00 UK time".
