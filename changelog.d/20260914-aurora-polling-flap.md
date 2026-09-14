### Fixed — aurora alerts no longer pay for themselves every five minutes after dark

After dark, each five-minute aurora poll evaluated the one state machine twice: first a forecast
lookahead, then a real-time path. The two read tonight differently. The lookahead took the highest Kp
of every 3-hour block that overlapped tonight's dark window, including blocks that were already over,
because NOAA's product keeps its observed blocks for a week. The real-time path looked only six hours
ahead of now.

The flap happened whenever the lookahead reached an alert level and the real-time path did not —
after a storm that peaked earlier in the night, or with a peak forecast more than six hours ahead.
Every poll then went:

1. The lookahead NOTIFIED.
2. That paid for weather triage and, if any location was clear, a synchronous Claude call, with a
   `job_run` and an `api_call_log` row.
3. The real-time path CLEARED the scores it had just bought.
4. The next poll started from IDLE and paid again.

That is up to about twelve times an hour. Meanwhile `GET /api/aurora/status` almost never showed the
alert. When the real-time path came out *higher*, the poll that raised the alert NOTIFIED twice and
threw the first scoring away. NOTIFY never sent email or push; it only scores. This was confirmed in
code and by tests that replay whole nights; it has not been confirmed in the production logs.

**Every poll now evaluates the state machine at most once.**

- **In daylight** it reads the forecast for tonight.
- **After dark** it takes the higher of the forecast for the rest of tonight — the blocks still ahead
  before dawn, the running one included and finished ones never — and the conditions now.
- **The alert is attributed to the forecast**, with planning wording and tonight's window, when the
  forecast alone reaches that level. It is real-time ("act now") only when the conditions now go
  beyond it.

So a heads-up for a small-hours peak now stays up through the evening, and is paid for once.

- **The Kp for now is NOAA's figure for the most recently completed block.** Once that block's
  reading is out, it is the reading. A reading appears only after its block ends and then sits in
  the client's 15-minute cache, so until it lands the figure is the higher of the latest reading and
  NOAA's estimate for the block. The estimate may raise it, never lower it. Without the estimate the
  level would dip at the end of an isolated storm block. If it could lower the figure, a storm NOAA
  under-estimated would CLEAR until its reading landed, then NOTIFY and pay again. The running block,
  whose value is a forecast, is never reported as "now".
- **One NOAA snapshot and one clock reading per poll.** The daylight poll now fetches the full
  snapshot before the state machine moves, and still only when it is about to NOTIFY
  (`AuroraStateCache.wouldNotify`), so a fetch that throws can no longer leave an ACTIVE alert with
  no scores. At nautical dawn itself the night is now over, on both of the app's night rules.
- **A lowered `aurora.triggers.kp-threshold` now applies in daylight too.** The lookahead used to map
  Kp through a fixed 5, so at 4.5 it would have cleared what the real-time path raised.
- **`POST /api/aurora/admin/run` now runs the scheduled cycle itself, through the same guard.** It
  answers 409 while a cycle is running, and still runs on the request thread. Before, it called the
  real-time path directly, with that path's own horizon and no guard, so it could clear a heads-up the
  next poll would pay to raise again, or run alongside a scheduled cycle. In daylight it can no longer
  clear a stale alert; `POST /api/aurora/admin/reset` does that. Its response is now
  `{status, dark, level, action, trigger}` rather than `{status, action}`; no screen calls it.
- **The same guard absorbs the scheduler's own overlap.** Update Schedule and Resume re-arm the job
  with an immediate run, even while a cycle is still going.

`AuroraNightRuleAgreementTest` now pins the polling job's night rule to `AuroraForecastRunService`'s
across a year of instants. That comparison could not be written while the job read the wall clock.
`poll-interval-minutes`, `kp-clear-threshold` and `ovation-clear-threshold` are now documented as
unread.
