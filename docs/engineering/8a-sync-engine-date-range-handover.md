# Fix the synchronous forecast engine's UTC-derived date range

Tracked as the last open item in §8a of `docs/engineering/intraday-settled-refresh-plan.md`.

## Background

Three merged PRs already fixed how the forecast pipeline derives `daysAhead` (the
forecast horizon, T+N) and the related "today" calendar:

- #481 — the core fix: `daysAhead` now derives from the UK civil date
  (`Europe/London`), not UTC, via a new `util/ForecastHorizon` class
  (`today(clock)` / `daysAhead(date, clock)`). The domain rule: a forecast target
  date names a solar event at a UK location, so "today" must be the UK calendar —
  under BST, `LocalDate.now(ZoneOffset.UTC)` is a day behind between 23:00 and
  00:00 UTC, so it overstated every horizon by one.
- #482 — deleted a dead method (`ForecastDtoMapper.toSparseDto`) that #481 had
  pre-emptively fixed before establishing it had no caller.
- #484 — collapsed four batch classes that were hand-rolling the same
  `Europe/London` derivation independently of `ForecastHorizon` onto it
  (`ForceEvalHeadlineSelector`, `BatchRetryService`,
  `ScheduledBatchEvaluationService`, `BriefingRollupBuilder`).

All three deliberately left one thing alone: **the synchronous forecast engine's
date *range* is still UTC-derived.** This task is that follow-up. Read §8a in full
before starting — it records the reasoning, what was and wasn't fixed, and a false
claim that was caught by adversarial review and corrected (see "A caution" below).

## The defect (established by direct code reading during #481 — verify it still
## holds, since main has moved)

Two separate UTC derivations in the sync engine, in two different classes, for two
different purposes:

1. **`ForecastCommandFactory`** (`defaultDates`, ~line 107) builds the *list of
   dates* a manually-triggered run covers:
   `return runType.defaultDateRange(LocalDate.now(ZoneOffset.UTC));`
   `RunType.defaultDateRange(today)` produces e.g. `[today, today+1]` for
   VERY_SHORT_TERM, `[today, today+1, today+2]` for SHORT_TERM, `today+3..today+7`
   for LONG_TERM.

2. **`ForecastCommandExecutor`** (`execute(...)`, ~line 236) derives its own
   `today`/`now` for a per-slot skip gate:
   ```java
   LocalDate today = LocalDate.now(ZoneOffset.UTC);
   LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
   ...
   shouldSkipEvent(targetDate, targetType, location, today, now)
   ```
   `shouldSkipEvent` (private, ~line 752):
   ```java
   private boolean shouldSkipEvent(LocalDate targetDate, TargetType targetType,
           LocationEntity location, LocalDate today, LocalDateTime now) {
       if (!targetDate.equals(today)) {
           return false;
       }
       LocalDateTime eventTime = targetType == TargetType.SUNRISE
               ? solarService.sunriseUtc(location.getLat(), location.getLon(), targetDate)
               : solarService.sunsetUtc(location.getLat(), location.getLon(), targetDate);
       return now.isAfter(eventTime);
   }
   ```
   This applies to **every** call to `execute()`, not just default-range runs —
   `today`/`now` are derived once per call regardless of how the `dates` list was
   built.

**The concrete failure mode** (derived by reasoning, not observed — verify with a
test before trusting it): in the 23:00–00:00 UTC BST band, `LocalDate.now(UTC)` is
UK-yesterday. So a VERY_SHORT_TERM manual run triggered in that band evaluates
`{UK-yesterday, UK-today}` instead of the intended `{UK-today, UK-tomorrow}`.
UK-yesterday's events are already in the past, so `shouldSkipEvent` correctly skips
them via the `now.isAfter(eventTime)` instant check — no wrong evaluation happens —
but UK-tomorrow is never evaluated at all. The same one-day-early shift applies to
SHORT_TERM and LONG_TERM's ranges. This only affects manually-triggered admin runs
(`POST /api/forecast/run/very-short-term|short-term|long-term` — ADMIN-only), and
only inside that one-hour band.

## The fix — what to change and what NOT to

- Route `today`'s calendar basis through `util/ForecastHorizon.today(clock)` in
  both classes, mirroring exactly how #481 and #484 did it: inject a `Clock`
  constructor parameter (the bean already exists, unconditional, in `AppConfig`),
  update Javadoc, update every test construction site.
- **Do NOT touch the instant comparison inside `shouldSkipEvent`.**
  `now.isAfter(eventTime)` is a genuine "has this already happened" check on real
  instants, not a calendar-day comparison — it must stay as-is. Only the `today`
  value feeding `targetDate.equals(today)` should move calendars. Get this
  distinction right; conflating "which day is today" with "has this moment
  passed" is exactly the kind of mistake this class of bug is made of.
- Check whether `today`/`now` in `ForecastCommandExecutor` have any other
  consumers beyond what's shown above before changing their derivation — grep
  first, the same discipline #481 required.
- State in the commit which shape you chose and why, same as #481 did.

## A caution — read this before writing any comment, doc, or commit message

The daysAhead fix (#481) initially asserted, in its own source comment, a test's
rationale, CLAUDE.md, the CHANGELOG, and §8a, that a specific pre-fix disposition
row "read `daysAhead=0` beside a skip reason that only holds above zero." An
adversarial review of that PR proved this false by reading the actual pre-fix
variable flow — the row in question had in fact agreed with its reason. The claim
had started as an unverified prediction in an earlier planning doc ("would read")
and was promoted to a statement of history without anyone checking which variable
reached which line. Five documents had to be corrected afterward.

The lesson, concretely: before writing any claim about what the code "does" or
"did," verify it with `git show`/a direct read of the actual line, not by
restating a summary. This applies especially to the failure-mode paragraph above —
verify it yourself before repeating it, and correct this prompt's own account if
it's wrong.

## Test requirement

A case pinning a `Clock` inside the 23:00–00:00 UTC BST band (e.g.
`2026-08-11T23:30:00Z`, as #481's test used) that asserts:
- `ForecastCommandFactory`'s generated range for VERY_SHORT_TERM is
  `{UK-today, UK-tomorrow}`, not `{UK-yesterday, UK-today}`.
- `shouldSkipEvent`'s already-past gate still correctly skips a genuinely-past
  event and still correctly includes a genuinely-future one — i.e., prove the
  instant comparison wasn't broken by the calendar-basis change.

## Environment

- **`jitpack.io` is blocked by egress policy (403)** — `solar-utils` cannot
  resolve from it. Install the in-tree vendored jar into `~/.m2` under the
  coordinate the POM asks for: copy
  `backend/.m2/repository/com/gregochr/solar-utils/2.1.0/solar-utils-2.1.0.jar` to
  `~/.m2/repository/com/github/gregochr/solar-utils/2.1.0/`, write a minimal pom
  there with `groupId com.github.gregochr`, delete any `*.lastUpdated` marker.
  Maven Central itself is reachable.
- **No Docker**, so the 5 `IntegrationTestBase` classes cannot run locally.
  Exclude them and say so in the commit/PR:
  `./backend/mvnw -f backend/pom.xml verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`
- **Gate on Maven's exit code, never on a grep of its output** — with `-q`,
  violations are suppressed and `$?` after a pipeline is grep's status.
- Ladder: `compile` → single test class → `checkstyle:check` → full `verify`.
- Work in a fresh git worktree off the current `origin/main` tip (fetch first —
  main has moved through #481/#482/#484 since this task was written; don't branch
  from a stale SHA). Read the current line numbers from the tree before citing
  them anywhere — every number above is "as of #484" and may have shifted.

## Process (from CLAUDE.md — binding)

- Conventional commits. CHANGELOG entry only if there's a genuine user-facing
  effect to describe (there likely is here, unlike #484's pure internal
  deduplication) — user-facing prose, no jargon.
- Update §8a of `docs/engineering/intraday-settled-refresh-plan.md` to mark this
  item resolved, and `ForecastHorizon`'s own javadoc (which currently calls this
  "a genuine exception" that's "still UTC-derived").
- Run an adversarial review of the diff before proposing a merge — this project's
  established practice for correctness-sensitive changes (see #481's review, which
  caught the false claim above). Reviewers must be read-only.
- Backend test standards: `docs/engineering/test-improvement-standards.md`.
- Create the PR, watch CI, fix any issues that surface, then merge once every
  check is green.
