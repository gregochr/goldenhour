### Fixed — aurora alerts no longer pay for themselves every five minutes after dark

After dark, each five-minute aurora poll could evaluate the one state machine twice: first a forecast
lookahead (whenever tonight's forecast reached the MODERATE threshold), then a real-time path. The two
read tonight differently. The lookahead took the highest Kp of every 3-hour block that overlapped
tonight's dark window, including blocks that were already over, because NOAA's product keeps its
observed blocks for a week. The real-time path looked only six hours ahead of now.

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
threw the first scoring away. NOTIFY never sent email or push; it only scores. A test of two
consecutive polls reproduced the flap against the old code, and whole-night replays now pin the fix;
it has not been confirmed in the production logs.

**Every poll now evaluates the state machine at most once.**

- **In daylight** it reads the forecast for tonight.
- **After dark** it takes the higher of the forecast for the rest of tonight — the blocks still ahead
  before dawn, the running one included and finished ones never — and the conditions now.
- **The alert is attributed to the forecast**, with planning wording and tonight's window, when the
  forecast alone reaches that level. It is real-time ("act now") only when the conditions now go
  beyond it.

So a heads-up for a small-hours peak now stays up through the evening, and is paid for once.

- **The Kp for now is NOAA's figure for the most recently completed block**: its published reading
  once it is out, and NOAA's estimate for the block until then. A reading appears only after its
  block ends and then sits in the client's 15-minute cache, so without the estimate the level would
  dip at the end of an isolated storm block. The running block, whose value is a forecast, is never
  reported as "now".
- **An estimate never ends an alert.** An estimate can be revised when its reading is published (the
  09:00-12:00 block on 2026-09-14 was estimated at Kp 3 and published at 2). So while the completed
  block's reading is still due, for up to an hour after the block ends, a night poll that would
  CLEAR holds the alert instead, whatever raised it. Otherwise a storm NOAA under-estimated would
  CLEAR at the block boundary, then NOTIFY and pay again when its reading landed. A reading more than
  an hour late means a late or stale feed, and the estimate stands. The hold only delays a CLEAR, but
  an alert that would have ended at a block boundary shortly before dawn can now stand through the
  day, as any alert standing at dawn already did: no poll clears one in daylight.
- **A night poll decides on one NOAA snapshot and one clock reading.** The daylight poll now fetches
  the full snapshot before the state machine moves, and still only when it is about to NOTIFY
  (`AuroraStateCache.wouldNotify`), so an unexpected error from that fetch leaves the state machine
  untouched. The client fails open, so this guards the unexpected, not an outage. At nautical dawn
  itself the night is now over, on both of the app's night rules.
- **The daylight poll records the trigger straight after its NOTIFY**, as the night poll always has.
  The residual recorded under *an aurora status response no longer straddles its own NOAA calls* was a
  new level served beside the previous alert's trigger for the length of a NOAA fetch. It now lasts a
  few field writes, unless an admin reset or simulation lands mid-poll.
- **A lowered `aurora.triggers.kp-threshold` now applies in daylight too.** The lookahead used to map
  Kp through a fixed 5, so at 4.5 it would have cleared what the real-time path raised.
- **`POST /api/aurora/admin/run` now runs the scheduled cycle itself, through the same guard.** It
  answers 409 while a cycle is running, and still runs on the request thread. Before, it called the
  real-time path directly, with that path's own horizon and no guard, so it could clear a heads-up the
  next poll would pay to raise again, or run alongside a scheduled cycle. In daylight it can no longer
  clear a stale alert; `POST /api/aurora/admin/reset` does that. Its response is now
  `{status, dark, level, action, trigger}` rather than `{status, action}`; no screen calls it.
- **The same guard absorbs the scheduler's own overlap.** Saving an edited schedule, Resume and Run
  Now on the Scheduler screen can each start a poll while one is still running; the guard skips it.

`AuroraNightRuleAgreementTest` now pins the polling job's night rule to `AuroraForecastRunService`'s
across a year of instants. That comparison could not be written while the job read the wall clock.
`poll-interval-minutes`, `kp-clear-threshold` and `ovation-clear-threshold` are now documented as
unread.
