### Removed — six optimisation strategies unable to act since v2.7.2, and the claims made for them

The Run Config screen's **Cost Optimisation** panel listed eight toggles. A deliberate trace found
that six of them had been unable to affect any run for five months, and that the panel never
touched the scheduled batches at all.

**What was dead, and how it got that way.** `SKIP_LOW_RATED`, `SKIP_EXISTING`, `FORCE_IMMINENT`,
`FORCE_STALE`, `EVALUATE_ALL` and `NEXT_EVENT_ONLY` were evaluated only inside
`OptimisationSkipEvaluator.shouldSkip`. They did act at first (V41, 2026-03-03), skipping slots on
hand-started runs. v2.7.2 (2026-04-06) put that call behind `!triggeredManually`, deliberately:
`SKIP_LOW_RATED` was dropping locations from an explicit Run Forecast. Every caller of that engine —
the five `ForecastController` endpoints that reach it — passes `manual = true`, and its scheduled
triggers had been retired on 2026-02-27, before the strategies existed. So from v2.7.2 none of the six
could act on any path. They are removed, along with the evaluator, the never-built `BATCH_API`
placeholder and every mutual-exclusion rule (the two survivors are independent). **V153** deletes
their rows; because the enum values go in the same change and `strategy_type` maps through
`@Enumerated(STRING)`, a row left behind would make Hibernate throw on read.

**What stays, and what it actually does.** `SENTINEL_SAMPLING` and `TIDE_ALIGNMENT` are read straight
from the enabled set, outside that guard, so they do act — on hand-started runs only: the
Very-Short-Term, Short-Term and Long-Term runs on Job Runs, and Run Forecast on a map location.
Scheduled batches, overnight and intraday, read neither: all five of their calls into
`fetchWeatherAndTriage` pass `tideAlignmentEnabled = false`, and they have no sentinel phase. A
hand-started run's rows are still served by `GET /api/forecast`, so what these toggles change can reach
readers. The panel now says all of this on screen.

**Claims corrected.**
- `TIDE_ALIGNMENT` was labelled "Weather/Tide Triage", described as applying four checks, and said to
  give a slot "a canned 1★ result". Weather triage runs unconditionally, the switch only adds the tide
  check, and a tide-triaged slot is stood down with no rating at all. It is now "Tide Triage", and its
  help text says each of those things.
- The run confirmation dialog warned that "JFDI (Always Evaluate Today)" or "Next Event Only" would
  override the reader's slot selection. That was never true: a deselected slot is excluded before any
  strategy is consulted (`excludedSlots.contains(...) || … || shouldSkip(...)`), so no strategy could
  bring one back — and after v2.7.2 none was consulted on these runs at all. The warning had no test.
  It is gone. (It also named `FORCE_IMMINENT` "JFDI", which was `EVALUATE_ALL`'s label.)
- CLAUDE.md's "active strategies snapshot on each job_run" is never written on a current path: all
  five endpoints pre-create the run with a null snapshot, so the job_run holds no record of which
  strategies a run used (the executor still logs them at INFO). CLAUDE.md now says so; the snapshot
  itself is left as it is.

**Fixed along the way.**
- **A request body the server cannot read now returns 400, not 500 — app-wide.**
  `GlobalExceptionHandler`'s catch-all turned `HttpMessageNotReadableException` into a 500 for any
  malformed body, the same gap it had already closed for missing parameters. Retiring the enum values
  made it reachable a new way: a stale client naming one sends a body that cannot be read. The
  response message is fixed and nothing is logged, so caller-supplied content is neither echoed nor
  written to the logs. Outbound read failures and server-side converter errors stay 500 — Spring wraps
  them in other exception types.
- **The sentinel threshold's buttons** fell back to 3 when no value was stored — Skip Low-Rated's
  default. The sentinel's is 2, so the panel highlighted a threshold the run was not using. They also
  now expose `aria-pressed`, and each strategy toggle has an accessible name and the scope note as
  its description; before, a screen reader heard only "ON" or "OFF".
- **Local dev no longer breaks on the retired rows.** It runs no migrations, so V153 never reaches a
  local H2 database. `OptimisationStrategyService` now deletes the same explicit list at startup, then
  fills any missing default row — so an existing local database also gains the `TIDE_ALIGNMENT` rows
  the old seed never wrote.
- ⚠️ **The startup delete is an explicit list, deliberately.** An earlier cut deleted "every type the
  enum does not know". That runs on every start in every profile, and production sets
  `validate-on-migrate: false`, so an older image redeployed against a database that had since gained
  a newer type would boot and silently, permanently delete that type's rows. Review caught it before it
  was pushed. Naming the retired types leaves an unknown one to fail loudly on read instead.

**Verification.** V153 is proven against Postgres 17 by a Flyway-driven migration test (stop at V152,
change a surviving row the way an admin would, apply V153, check every remaining row loads); that needs
Docker, so it runs in CI only. The startup delete's SQL has its own test against H2, including the
rollback case. Behaviours pinned by new tests were mutated out during development to confirm each
test fails without them.
