### Fixed — aurora alerts no longer pay for themselves every five minutes after dark

After dark, each five-minute aurora poll ran the forecast lookahead and then the real-time path
against the one state machine, and the two read tonight differently. The lookahead took the highest
Kp of every 3-hour block that overlapped tonight's dark window, including blocks that were already
over, because NOAA's product keeps its observed blocks for a week. The real-time path looked only six
hours ahead. So after a storm that peaked earlier in the night, or with a peak forecast more than six
hours after dusk, every poll went the same way:

1. The lookahead raised MODERATE and NOTIFIED.
2. That paid for weather triage and a synchronous Claude call, a `job_run` and an `api_call_log`
   row, and the scores were cached.
3. The real-time path read MINOR or QUIET and CLEARED those scores straight away.
4. The next poll started from IDLE and paid again.

That is up to about twelve Claude calls an hour. Meanwhile `GET /api/aurora/status` almost never
showed the alert, because the scores only existed for the moment between the two paths. NOTIFY never
sent email or push; it only scores. This was confirmed in code and by a test that replays both
nights. It has not yet been seen in the production logs.

**Both paths now read tonight through one figure**: the highest Kp of the blocks still ahead in
tonight's dark window, the running one included and finished ones never. The real-time level is the
higher of that figure and the Kp for now, mapped through the same rule, so after dark it can never
clear what the lookahead has just raised. A heads-up for a small-hours peak now stays up through the
evening, and is paid for once.

- **The Kp for now bridges block boundaries.** It counts the block that has just ended as well as the
  latest published reading. A block's reading only appears after the block ends and then sits in the
  client's 15-minute cache, so without the bridge the level dipped at the end of every storm block:
  one poll cleared, and a later one re-alerted and paid again.
- **One NOAA snapshot and one clock reading per poll.** The same instant decides whether it is dark,
  which night is "tonight" and how much of it is left. At nautical dawn itself the night is now over
  on every rule.
- **A lowered `aurora.triggers.kp-threshold` now applies to the lookahead too.** It used to map Kp
  through a fixed 5, so at 4.5 it would have cleared what the real-time path raised.
- **`POST /api/aurora/admin/run` now runs the scheduled cycle itself.** It goes through the same
  guard as the schedule and answers 409 while a cycle is running. Before, it called the real-time
  path directly from the request thread, with that path's own horizon, and could run alongside a
  scheduled cycle. Its response is now `{status, dark, lookahead, realtime}` rather than
  `{status, action}`; no screen calls it.

`AuroraNightRuleAgreementTest` now pins the polling job's night rule to
`AuroraForecastRunService`'s across a year of instants. That comparison could not be written while
the job read the wall clock. `kp-clear-threshold` and `ovation-clear-threshold` are now documented as
unread: the de-escalation hysteresis they were meant to configure has never been built.
