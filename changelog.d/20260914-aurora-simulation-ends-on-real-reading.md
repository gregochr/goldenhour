### Fixed — an aurora simulation ends at the next real reading, instead of outliving the alert it faked

An admin's aurora simulation (`POST /api/aurora/admin/simulate`) outlived the alert it faked. The
state machine held it in two fields, `simulated` and `simulatedData`, and only `activateSimulation`
and `reset()` wrote them. The CLEAR branch of `evaluate` reset the level, the state, `activeSince`,
the scores and the counts, and left the simulation where it was. So when the admin did not press
Clear:

1. The first night-time real-time poll read a quiet sky and CLEARed. The banner went away, so the
   simulation looked finished. The flag stayed set.
2. From then on `GET /api/aurora/status` took its simulated branch. It never called NOAA, and it
   served the simulation's frozen Kp, OVATION, Bz and G-scale.
3. When a real alert later NOTIFYed, every Pro user's banner said "(SIMULATED)", with the
   simulation's G-scale — and its Kp, until the new alert recorded a trigger of its own. It skipped
   the all-overcast gate, and it offered "Generate scores →", a hash write that does nothing. The
   admin forecast preview and runs used the fake data too.
4. While the simulation was still ACTIVE, a real alert at or below its level was SUPPRESSed. It was
   measured against the fake storm, and never scored.

The javadocs claimed the opposite. `AuroraStateCache` said the polling job "will override this state
once a real geomagnetic event is detected", and `AuroraAdminController` said much the same.

**What a simulation does now.** The next real reading the machine evaluates ends it.

- A quiet reading CLEARs it, as it would any alert.
- An alert reading is a new alert at the real level, answered as it would be from IDLE: it starts
  clean, with no previous level and nothing of the simulation, and it is scored. A simulated alert
  was never a real one, so there is nothing to suppress a real alert against or escalate it from.
- After dark the real-time path evaluates a reading on every poll, whether or not NOAA answers — its
  client fails open, to its cache or to an empty reading, which derives QUIET. So a night-time
  simulation lasts until the next poll: five minutes at most by default. On a quiet night it already
  vanished from the banner in that time; now the flag goes with it.
- By day only the forecast lookahead evaluates, and only when tonight's forecast reaches the alert
  threshold. So a daytime simulation still lasts until dusk unless a real alert arrives first.
- With `aurora.enabled=false` or the `aurora_polling` job paused, nothing evaluates, and a
  simulation lasts until it is cleared, as before.

The admin Job Runs screen's "🧪 Simulated" button now goes back to "🧪 Simulate" once a real reading
has ended a simulation, at the screen's next status fetch. The forecast modal's simulated badges
follow from its next opening.

**Clear clears only a simulation.** `POST /api/aurora/admin/simulate/clear` used to reset the
machine whatever it held. The admin screen polls its status, so it can go on showing a simulation
for minutes after a real reading has ended it. If that reading was an alert, a Clear pressed there
wiped the real alert, scores and all, for every Pro user until the next poll paid to score it again.
The stuck flag used to put a real alert under that button indefinitely, so the exposure was worse
before. Now Clear ends a simulation only if one is running, and otherwise answers 200 saying nothing
was cleared. The modal still shows its own "Simulation cleared." either way.

**Every transition writes a whole state.** CLEAR, `reset()` and a simulation ended by a real reading
return every field to the value a fresh machine starts with. The start of a new alert or a simulation
writes every field too. All of them go through one private `become(…)`. Each route used to write only
the fields its author remembered, which is how CLEAR came to leave the simulation behind. Three
consequences reach beyond the simulation. The first two bring the code into line with its own
javadoc:

- **A CLEAR drops the trigger type and Kp.** `getLastTriggerType()` and `getLastTriggerKp()` have
  always said "`null` when IDLE", and no CLEAR ever made them so. A simulation plants a trigger — a
  forecast trigger, with its fake Kp — and a forecast-lookahead NOTIFY records its own trigger only
  after a further NOAA fetch. For that fetch the leftover trigger stood in: the simulation's, or the
  last real event's, in the new alert's banner Kp and G-scale, its forecast viewline, the aurora hot
  topic and the briefing. Nothing shows the trigger while the machine is idle. The status still
  serves it, but the banner and the viewline wait for an alert level, and the hot topic and the
  briefing wait for the level or `active`.
- **A simulation keeps nothing of what it replaces** — a real alert or another simulation — its
  scores and counts included. The clear count's javadoc already said it is `null` "during
  simulation".
- **A new alert starts clean**, so a scoring that landed after the previous alert ended no longer
  carries into it.

**A simulation cleared mid-request no longer throws.** `AuroraController.getStatus` read
`isSimulated()` and then `getSimulatedData()`, as two separate volatile reads. `activateSimulation`
wrote the flag before the data, and `reset()` cleared the flag before the data. So a request could
find the flag set and no data in two ways: its two reads fell between an activation's two writes,
or a Clear's two writes fell between its two reads. Either way it hit a `NullPointerException` at
`simData.kp()`, served as a 500. `AuroraForecastRunService.getPreview` and `runForecast` read the
pair the same way.

- The simulation is now one reference. Its presence is the flag.
- `isSimulated()` is gone, so the pairing cannot be written again.
- Each reader reads the simulation once.

The status also reads it **first**, before the level, and `become` writes it on the side that serves
such a reader: a new simulation is set before the level, an ending one cleared after it. So a
request that finds no simulation cannot then find a simulated level that is only now ending and serve
it as a real alert. That torn read would otherwise have been new with this change, on every CLEAR
after a simulation. A switch from one simulation to another never shows none.

**The thread-safety javadoc was wrong.** It said the machine was written "from a single background
thread", and that `evaluate` needed no guard because "only the polling job calls it". In fact the
machine is written from four places:

- the scheduler's threads — a scheduled poll and a Scheduler Run Now can overlap;
- the admin `run` endpoint, which calls the orchestrator's real-time path, `evaluate` included;
- the admin `simulate`, `simulate/clear` and `reset` endpoints, on request threads;
- the batch result path (`AuroraResultHandler`).

Every write now holds one `ReentrantLock` for the whole of its transition. It is a lock rather than
`synchronized` because the request threads are virtual, and a held monitor pins a virtual thread on
Java 21. This change needs the lock. A CLEAR now writes the simulation, so a simulation starting while
a CLEAR ran could have kept the simulated level and lost the simulation: a fake alert served as a real
one, by day until dusk. It also closes an older race: two evaluations at once — a poll and an admin
`run`, or a scheduled poll and a Run Now — could both find the machine IDLE, both NOTIFY, and pay for
scoring twice.

A SpotBugs exclusion for the class gave the same single-writer reason. It is deleted: SpotBugs'
inconsistent-synchronisation detector skips volatile fields, and every field here is volatile or
final, so it never matched anything.

**Reconciled with #849**, which landed on `main` first and rewrote the polling paths around this
same class while this branch was in review. Neither fix changed the other's reason for existing —
#849 stops one poll evaluating the machine twice in a night; this stops a simulation outliving the
alert it faked — but both touch `evaluate`, so landing second meant merging behaviour, not just text:

- **#849 added `wouldNotify`/`wouldClear`**, a peek a poll asks before fetching the data a scoring
  needs, backed by static `notifies`/`clears` predicates shared with `evaluate`. The takeover this
  fix adds had to become part of that shared logic, or a poll's peek could disagree with what
  `evaluate` decided a moment later. `notifies` now takes a third argument, whether a simulation is
  running, and answers NOTIFY for any alert-worthy reading while one is — never measured against the
  simulated level, so a real reading at exactly that level now NOTIFIES where it used to read false.
  Two rows of #849's own truth table (`SIM_MODERATE, MODERATE` and `SIM_STRONG, STRONG`) flip from
  `false, false` to `true, false` for exactly that reason, and both `wouldNotify`/`wouldClear` now
  take the transition lock too, so a poll's peek can never straddle an admin write mid-read.
- **#849 removed the lock** (there wasn't one to remove — this branch is the one that adds it) and
  instead reads `state`, `currentLevel` (and now, on this branch, whether a simulation is running)
  into locals at the top of `evaluate`, deciding every branch from those locals rather than the
  fields again. That shape is kept, now inside the lock: belt-and-suspenders once locked, and it
  matches the class's existing style.
- **#849's own class javadoc claimed single-writer-via-polling-cycle**, true of its own change but
  never of this branch's admin/batch writes; rewritten to describe the lock, what it does and does
  not serialise (a poll's peek and its own evaluate are still two separate lock acquisitions, so an
  admin write between them can still move what the peek answered for — #849's own callers already
  re-fetch on that mismatch rather than trust the peek).
- **#849's "a CLEAR does not reset it" trigger-getter javadocs are now false**, superseded by this
  branch's CLEAR-drops-the-trigger rule; corrected in place rather than left standing, since they
  describe the method these tests now pin the opposite of.
- **#849's own status-endpoint javadoc already narrowed the "NOTIFY in several steps" residual**:
  both polls fetch NOAA before the state machine moves, so the gap this branch's own first cut
  described as "a new alert's level with no trigger yet" now lasts only a few field writes, not a
  whole NOAA round trip — folded into this entry's own residual below rather than left overstated.
- **`AuroraSimulationLifecycleTest` was ported**, not merely fixed to compile: #849 made
  `AuroraOrchestrator.runForecastLookahead`/`runNightPoll` package-private, reachable only through
  the new `AuroraPollingJob.runCycleIfIdle()`, so the reported sequence is now driven through the
  real polling job rather than the orchestrator directly. Its scenarios moved to night polls (dusk
  already passed) with an empty `kpForecast`, so the level comes from `recentKp` alone and #849's
  hold-for-a-late-reading behaviour (`pendingHold`, `readingDue`) never engages — a deliberate
  simplification, since that machinery belongs to #849's fix, not this one, and is untouched here.
- **Left alone, on purpose**: #849's `pendingHold` — a night poll's alert held open across the
  dawn boundary while its Kp block's reading is still due — can be handed a hold that started under
  a simulation, or under a real alert an admin then reset or re-simulated over; #849's own PR lists
  this as an open follow-up. `become()` does not reach into `AuroraOrchestrator`, and fixing it means
  deciding what a hold *for* a state that no longer exists should do, which is `AuroraOrchestrator`'s
  call, not this class's.

**Reconciled with #841**, which landed after the #849 reconciliation above and, separately, added
`currentNightDate`/`currentNightEndsAt` to this same status response and a `CurrentNight` read to
this same method — the map's window list needed to stop offering nights that are already over. No
behavioural overlap: #841 never reads or writes the simulation, and this fix never touches the night
fields, so the two diffs collided only textually, both inserting new reads immediately after
`activeSince` in `getStatus()`. Resolved by keeping this fix's reordering (the simulation read first,
before `cachedLevel`) and folding #841's `CurrentNight` block in after it, dropping the now-redundant
`isSimulated()`/`getSimulatedData()` re-read #841's diff carried from the pre-fix code it branched
from. `AuroraControllerStatusSnapshotTest` gained #841's own
`dawnDuringNoaaCalls_answersWithTheNightTheRequestBeganIn` case in the same fold — unrelated to this
fix, listed here only because it now lives in a file this entry also changes. The full local gate
(8056 backend tests, Checkstyle, JaCoCo, SpotBugs) re-ran clean after this fold.

**Reconciled with #847**, which landed after the #841 reconciliation above. #847's own title is "an
admin's simulation stops at the admin, never reaches real users": it gates three call sites
(`AuroraHotTopicStrategy`, `BriefingAuroraSummaryBuilder`, `BriefingRollupBuilder`) on
`AuroraStateCache.isSimulated()`, so a running simulation cannot reach a real user's Plan cards, the
best-bet prompt, or a persisted tonight summary; and it adds a durable `simulated` marker
(`aurora_forecast_result.simulated`, V154) so a forecast run made against fake data is never served
back as real. `isSimulated()` is exactly the flag this fix deletes, so **all three call sites needed
the straight substitution** — `getSimulatedData() != null` — agreed with the peer session that built
#847 before either branch landed (see [[aurora-simulation-leak]]).

That part was mechanical. Two parts were not, and are worth recording precisely:

- **#847 independently found and partly fixed the exact bug this whole entry is about.** A later
  commit on #847 (a Codex-review fix) added `simulated = false; simulatedData = null;` to
  `evaluate()`'s CLEAR branch, with a comment describing the identical scenario as this entry's
  opening section: a lingering simulation surviving a real CLEAR and silently suppressing the next
  real alert from every `isSimulated()`-gated reader. Its fix is a strict subset of this branch's
  `become()` unification — same two fields cleared, but not the trigger (this branch's own,
  separately-decided CLEAR-drops-the-trigger rule), and by a direct field write rather than through
  one shared path — so on rebase this branch's `become(State.IDLE, …)` call simply superseded #847's
  two lines outright: same outcome for the fields #847 touches, plus everything else `become()`
  already did. Nothing of #847's intent was lost; there was nothing left for its patch to do once
  `become()` ran first.
- **#847 also independently fixed the identical two-read NPE this entry fixes**, in
  `AuroraForecastRunService.getPreview()` and `.runForecast()` — the same `isSimulated()`-then-
  `getSimulatedData()` pattern, the same single-read cure. Unlike the CLEAR-branch case, #847's
  version was the one kept: its `simulated`/`isSimulated` locals feed straight into the new
  `.simulated(...)` marker it stamps on every persisted row and the `replaceNightResults(date, …,
  simulated)` calls beside them, code this branch never had reason to write. Adopting #847's version
  of both methods outright (rather than this branch's differently-named equivalent) kept that
  plumbing intact without re-deriving it. `AuroraForecastRunServiceTest` gained #847's own
  `runForecast_simulated_marksPersistedResultsAsSimulated` and this branch's three NPE/real-CLEAR
  tests side by side — different questions about the same two methods, not a real conflict.
  `AuroraStateCacheTest` auto-merged without a marked conflict, but silently kept two of #847's own
  new cases calling the now-deleted `isSimulated()`; fixed to `getSimulatedData()` semantics, no
  behavioural change.

The full local gate re-ran clean again after this fold: **8074 tests, 0 failures**, Checkstyle 0
violations, SpotBugs "BugInstance size is 0", JaCoCo "All coverage checks have been met".

**Still open.**

- **Readers take no lock**, so a reader that reads several fields can still straddle a transition.
  That is the second residual of *an aurora status response no longer straddles its own NOAA calls*.
  Of the two simulation cases that entry names:
  - the 500 is gone: `simulated: true` with no data can no longer be read;
  - a simulation starting mid-read can still be read as its level beside `simulated: false`. It now
    takes a request whose two reads straddle both of the start's writes.

  There is also a new case, the reverse: a request that found the simulation just before a real alert
  ended it can serve that alert's level beside the simulation's data. Each is one response, which a
  client can go on showing until its next status fetch. One immutable snapshot published per
  transition would close all of them.
- **The orchestrator still writes a NOTIFY in several steps.** That is the same entry's first
  residual, and #849 already narrowed it: both polls fetch their NOAA snapshot before the state
  machine moves, so the gap between a NOTIFY and its trigger being recorded is a few field writes,
  not a NOAA round trip. Its "CLEAR never resets the trigger" no longer holds under this branch's
  rule, so what stands in that gap has changed: a new alert now shows no trigger for those few
  writes, where it used to show the leftover one — the simulation's, or the last real event's. An
  escalation still shows the previous NOTIFY's trigger, for the same few writes. The banner reads a
  missing trigger as real-time, so for that gap a forecast-triggered alert can say "Aurora active
  now"; that was already so after every restart, reset or Clear, and is now also so after a CLEAR.
  Only a poll's own NOAA-outage fallback (an admin write it missed) still fetches after the state has
  moved, which is the residual #849's own status javadoc names. The orchestrator handing over level
  and trigger in one write would close both. The same split lets a scoring's writes land after a
  later transition has moved past the alert they were computed for: a CLEAR, a reset, a simulation,
  or a later NOTIFY whose trigger, counts and scores they overwrite.
- **At night an admin has five minutes at most between Simulate and a forecast run** meant to use the
  simulated values. The forecast modal fetches its preview once, when it opens. A run started after
  the next poll uses real NOAA data while the modal still says "Using simulated geomagnetic data",
  and the result does not say which data it used. The stuck flag used to keep the preview and the
  run simulated for as long as it stood.
- **The order inside `become` is not pinned by a test.** A mutant that sets or clears the simulation
  on the other side of the level survives, because the order matters only to a reader racing the
  writes themselves, and no deterministic test can place one there. The rule is written at `become`
  and in the class comment instead.
- **The lock test cannot see a write that waits for the lock and then writes after releasing it.**
  It catches a write that takes no lock, or never lets it go. Telling the two apart needs a hook
  inside the transition.

**Tests.** 47 new backend test cases against #849's `main`, counting each parameterised case, and the
existing simulation tests moved off `isSimulated()`:

- **`AuroraStateCacheTest`** (37), on the real machine:
  - a real CLEAR ends a simulation and everything it planted, for QUIET and MINOR;
  - a real alert during a simulation is a new alert, starting clean, for all eight pairings of a
    simulated QUIET, MINOR, MODERATE or STRONG with a real MODERATE or STRONG, with a scoring landed
    under the simulation first. Every one used to keep the simulation, and three were SUPPRESSed;
  - after a CLEAR ends a simulation, the machine is idle and unsimulated before the next alert;
  - CLEAR drops the trigger;
  - a simulation keeps nothing of a scored real alert or of another simulation;
  - every route to IDLE — five, including a Clear — lands on a fresh machine's state;
  - `endSimulation` ends a running simulation, and leaves alone a real alert that took one over;
  - an evaluation queued behind a simulation's start ends that simulation, and two evaluations of
    one alert from IDLE notify once;
  - `wouldNotify` and `wouldClear` each wait for a transition in progress too, answering for the
    state a queued admin write left behind, not the one they were called against;
  - every write, including every branch of `evaluate`, waits for a transition in progress, then goes
    through and lets go. The lock is held on the test thread and each write runs on a second one,
    because the lock is re-entrant. Every wait is bounded.

  Two of #849's own truth-table rows (a real MODERATE or STRONG reading at exactly a simulated
  MODERATE or STRONG) flip from `false, false` to `true, false` — the takeover, not an escalation,
  so it fires even at equal severity.
- **`AuroraSimulationLifecycleTest`** (3) drives the real admin controller, the real
  `AuroraPollingJob` and orchestrator, and the real status controller, through
  `AuroraPollingJob.runCycleIfIdle()` — the only entry point left once #849 made the orchestrator's
  poll methods package-private. Only NOAA, weather triage, the roster and twilight are faked, as
  night polls with an empty Kp forecast, so the level comes from the latest reading alone and #849's
  hold-for-a-late-reading behaviour never engages. It runs the reported sequence end to end: simulate,
  a quiet poll (asserting the state between), a real storm, then the status. It also shows a real
  alert below the simulated level being scored, where it used to be suppressed, and a Clear pressed
  after such a takeover leaving the real alert alone.
- **`AuroraControllerStatusSnapshotTest`** (2) lands a Clear, and then a real CLEAR, between the
  request's own state reads, through a getter overridden on the real machine. The simulation's
  G-scale is one its Kp would never derive, so a response that derived one cannot pass for it.
- **`AuroraForecastRunServiceTest`** (3) has a preview on a real machine whose simulation a real CLEAR
  ended. Its preview and run tests answer a second read of the simulation with nothing.
- **`AuroraAdminControllerTest`** (2) pins that Clear ends a running simulation and calls
  `endSimulation()`, never `reset()`, and that Clear with no simulation running resets nothing.

334 aurora tests pass together, #849's own included: `AuroraPollingCycleTest` (774 lines, replaying
whole nights) and `AuroraNightRuleAgreementTest` neither regressed.

Most of these were written first and failed against the pre-fix code, one as a
`NullPointerException` itself and the rest on their assertions; a handful passed as they should,
pinning behaviour the fix keeps (a simulation starts; `reset()` already cleared everything). The
adversarial review then asked for more: `endSimulation()`'s own tests and the two lock-ordering
tests (`evaluationThatWaitedBehindASimulationsStart_endsIt`,
`twoEvaluationsOfOneAlertFromIdle_notifyOnce`) exercise API the pre-fix code has no equivalent
of — `AuroraStateCache` had neither a lock nor a way to end just a simulation — so they were never
run red; they are pinned by the mutants under "Mutated" below instead. The torn-read tests moved
from overriding `isSimulated()` to overriding `getSimulatedData()`, because the flag no longer
exists.

**Mutated in two rounds.**

- **The first round: 19 mutants, 18 killed.**
  - Lifecycle, seven: CLEAR keeping the simulation; IDLE keeping the trigger; no takeover; a takeover
    that drops only the data; one that keeps the trigger; a simulation inheriting the counts; and
    clearing the simulation first, the order survivor.
  - Lock, seven: each of the six writes taking no lock, and `evaluate` never releasing.
  - Readers, five: the status reading the simulation twice, or the level first; the preview reading
    it twice, for its Kp or for its flag; the run reading it twice.
- **A six-lens adversarial review of the diff found gaps the first round's tests could not see, and
  seven mutants built from those findings — all killed once the tests above were added:**
  - `evaluate`'s CLEAR branch written outside the lock. The first round's lock test drove only the
    NOTIFY-from-IDLE branch, so a CLEAR (or a takeover) hoisted above `lock()` — the exact race the
    lock exists for — passed every test. Killed by the new per-branch `Write` cases.
  - the takeover branch, same shape, same fix.
  - the takeover's `simulatedData != null` check decided before waiting on the lock, so a poll
    queued behind an admin's simulate call judged the alert against no simulation instead of the one
    that had just started. Killed by `evaluationThatWaitedBehindASimulationsStart_endsIt`, which
    queues an evaluation, starts a simulation on the holding thread, then releases.
  - the same shape for the IDLE check, which would let two queued evaluations both NOTIFY. Killed
    by `twoEvaluationsOfOneAlertFromIdle_notifyOnce`.
  - a simulation keeping the scores of a real alert it replaces (only counts were pinned).
  - a takeover keeping a simulation's scores and counts (same gap, the other direction).
  - the storm-scale guard re-reading `getSimulatedData()` instead of the value already in hand,
    reopening the two-read defect for one derived field. Killed by giving the fixture a G-scale its
    Kp would never derive.
- **A second set of seven, aimed at the new behaviour directly rather than at review findings:**
  `become()` never clearing a stale simulation; no takeover at all; `activateSimulation` writing its
  fields piecemeal instead of through `become()`; `endSimulation()` resetting unconditionally, or
  returning `true` without having cleared anything; the Clear endpoint calling `reset()` regardless
  of `endSimulation()`'s answer; and `endSimulation()` itself missing the lock. All seven killed,
  mostly by `AuroraSimulationLifecycleTest.clearAfterARealAlertTookTheSimulationOver_leavesTheRealAlert`
  and its `AuroraStateCacheTest` counterpart — the pair written for the review's most serious finding.

**33 mutants run across both passes; 32 killed, one documented survivor** (the `become()` write-order
mutant above).

**Correction to an earlier entry.** `changelog.d/20260914-aurora-status-one-state.md:37` ("CLEAR
never resets the trigger, so...") describes `AuroraStateCache` as it stood when that entry was
written. This entry's CLEAR-drops-the-trigger rule supersedes it; per `changelog.d/README.md` that
file is left as-is rather than rewritten.

**Tested, not seen.** No browser check was made. The banner needs an aurora alert and a Pro or admin
sign-in. The sequence is proven through the real controllers instead.
