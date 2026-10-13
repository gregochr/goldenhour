### Added — a forecast-cycle watchdog that mails the admins when a cycle does not start or has not finished

Solar-relative refresh plan, P2b (B2). A new scheduled job, `forecast_cycle_watchdog` (migration V172 seeds one
ACTIVE row, every 10 minutes, first check 10 minutes after startup; no existing schedule is touched), checks the two
forecast cycle jobs and emails the admins once per occurrence:

- **Did not start:** the job is ACTIVE, its most recent scheduled fire (a CRON job's last fire in UTC, or a
  SOLAR_OFFSET job's own fire for today or yesterday) was more than 30 minutes and no more than 6 hours ago, and no
  pipeline run of its cycle type has been triggered since.
- **Did not finish:** a run of the cycle triggered today is still RUNNING, or has a forecast batch still outstanding,
  from 20 minutes before until 60 minutes after the event it serves (nightly: today's sunrise; intraday: today's
  sunset, at the scheduler's reference point).

Each alert names the job, the fire or run, the event in UK local time and what is missing, and is latched in memory
per (job, fire) or (job, run), so a still-true condition is not mailed again every ten minutes. The watchdog only
reads: it never starts, stops or retries a run, and any failure inside it is logged and swallowed. It needs
`notifications.admin-alerts.enabled` and working mail, like Ask's spend-cap alert. It works with today's CRON
schedules unchanged.
