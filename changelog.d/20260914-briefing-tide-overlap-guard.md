### Fixed — a briefing build or a tide refresh can no longer run twice at once

Neither job had a guard of its own, and the scheduler's `wrapTarget` has none either. The batch
submissions and the cloud-verification backfill already refuse an overlapping run; these two now do
as well.

**The tide refresh** could be started by three routes at once:

- the Monday `tide_refresh` schedule;
- the Scheduler screen's Run Now;
- the Job Runs screen's "Refresh Tide Data", which answers 202 immediately, so it could be pressed
  again straight away.

Two refreshes that reached a location before either had committed it both paid WorldTides for the
same window. Each then deleted and re-inserted that window in its own transaction under
`uq_tide_extreme (location_id, event_time)`. The slower one's inserts could collide with the rows
the faster one had written, and its run logged a failure for a location that had in fact been
refreshed.

**The briefing** could be built by the batch pipeline's BRIEFING phase and the admin
`POST /api/briefing/run` at once. Two builds each fetch the whole roster's weather and each pay for
the gloss and best-bet Claude calls. Both then race to write the in-memory cache, the last-known-good
copy and `daily_briefing_cache`, where the last writer wins — and the last writer can be the build
that started first and read older evaluations.

**The two guards differ, on purpose.**

- **Tide:** one `AtomicBoolean` in `ScheduledForecastService`, shared by every route. The admin
  route now takes the guard on the request thread, *before* handing the refresh to the executor,
  and the task releases it when it ends. So there is no accepted-but-not-started gap in which a
  second press could also be accepted. A lock could not do this, because a lock is owned by the
  thread that took it. A refused admin press gets **409**. A refused schedule fire or Run Now is
  logged and skipped.
- **Briefing:** a `ReentrantLock` in `BriefingService` with two entry points.
  - `refreshBriefingIfIdle()` refuses when a build is running. The admin endpoint uses it, and
    answers **409**. So does the dormant `daily_briefing` scheduler target.
  - `refreshBriefing()` **waits** for the running build and then runs its own. This is the
    pipeline's entry point, and it must not refuse. Its build is the first to see its own cycle's
    batch results, and `PipelineOrchestrator` persists this cycle's picks from the cache straight
    afterwards. Had it refused, it would have persisted whichever build the cache held — possibly
    one that read the evaluations from before this cycle's batches landed.
  - ⚠️ **The wait is not short.** It lasts as long as the running build, and the gloss and best-bet
    Claude calls run under the Anthropic SDK's per-request timeout and retries, not the 30-second
    REST read timeout. So in the worst case a BRIEFING phase now takes two builds' time rather than
    one. It logs when it starts waiting, so a phase queued behind an admin build does not read as a
    hung one.
  - A `ReentrantLock` rather than `synchronized`, because a held monitor pins a virtual thread on
    Java 21.

The Job Runs screen now shows a 409 as "… is already in progress. Wait for it to complete.",
matching the batch buttons' existing wording, instead of "… failed. Check the logs." There is
nothing in the logs to check.

**Two corrections to the entry below**, *a double-click on the Scheduler's Run Now queues one run,
not two*:

- It says the daily briefing and the tide refresh have no guard, "so one double-click ran them
  twice". For the briefing it could not have. V103 deleted the `daily_briefing` scheduler row, so
  the Scheduler screen has no Run Now for the briefing; the briefing is built at the tail of each
  pipeline cycle. The tide half stands.
- Its "giving the briefing and the tide refresh the same guard is an owner decision, and it is not
  built here" is superseded: this entry builds it.

**Still open.**

- The Scheduler's Run Now still shows "Triggered ✓" for a tide refresh the guard then skips,
  because `triggerNow` queues the run before the guard is consulted.
- `wrapTarget` records a skipped fire as the job's last run.
- The tide guard covers the roster-wide refresh only. It does not cover the 12-month backfill, or
  the single-location fetch `LocationService` makes when a coastal location is added or edited. A
  refresh that picks up a location in the moment between its save and that fetch can still collide
  with it.
- An admin tide refresh that throws outside its per-location loop is still not logged, because the
  `CompletableFuture` is discarded. This predates the change; the scheduler route does log.
- The guards are per JVM. Production runs one.

**Tests.** 16 new backend tests and 4 frontend ones.

- **Tide:** the guard is an `AtomicBoolean`, owned by no thread, so its tests stay single-threaded:
  an executor that queues without running, and a re-entrant call from inside a running refresh.
  Each refusal is followed by a second probe, so a refusal that released the guard it never took
  is caught.
- **Briefing:** the lock is owned by the thread that holds it and is re-entrant, so its tests hold a
  build open on a real second thread, with every wait bounded. The build is held through *each*
  entry point in turn, so an admin entry that only looked at the lock is caught too. The "a throwing
  build releases the lock" test retries on another thread for the same reason: on the thread whose
  build threw, a missing unlock would go unnoticed.
- **Frontend:** each 409 test is paired with the sharpest input that must NOT read as "in
  progress" — an adjacent 400, and a network error with no response.

**Mutated 22 ways, all killed.** Four of them survived the first cut and were found by review.

- **Tide guard, seven:**
  - unguarded scheduler target;
  - unguarded admin route;
  - admin task never releases;
  - rejected executor never releases;
  - release only on success;
  - a skip that releases the guard it never took;
  - an admin refusal that does the same.
- **Briefing lock, five:**
  - the pipeline entry refusing instead of waiting;
  - the pipeline entry unlocked;
  - the admin entry unlocked;
  - the admin entry checking `isLocked()` without taking the lock;
  - unlock only on success.
- **Callers, four:**
  - the dormant scheduler target taking the waiting entry point;
  - the briefing endpoint taking the waiting entry point;
  - the briefing endpoint building twice per press;
  - the tide endpoint ignoring the refusal.
- **Frontend, six:** each 409 branch dropped; each made to swallow every error; a dropped optional
  chain; any 4xx read as a 409.

**Tested, not seen.** No browser check was made. The Job Runs screen is behind the admin sign-in.
