# PhotoCast — window-first Plan tab redesign. Continue at P12.

*Handover written 2026-08-08, immediately after P11 merged. Paste this as the opening prompt of the
new session. **Every file:line below was re-verified against HEAD by four agents on the day this was
written** — where §3 of the plan doc is stale or wrong, the correct value is given here and the
plan's own number is marked. Trust this document over §3 where they disagree.*

⚠️ **P12 is a BACKEND phase — the first since P3.** Eight consecutive frontend phases (P4a–P11) mean
the muscle memory from the last session is the wrong muscle memory: different build ladder, a
different test-standards document, and Docker is required. See "Running it locally".

## Read first, in this order

1. `docs/engineering/window-first-redesign-plan.md` — the spec. For P12: **§3 in full (`:493-542`)**,
   the P12 row (`:583`), the **P12 fallout note (`:811-817`)** and the migration note (`:819-822`).
   §5f (`:1298`) is P11's record and matters only for context.
2. **CLAUDE.md § "Speeding Up the Dev Build Cycle"** — the backend ladder, the Docker requirement,
   and the exit-code-not-grep rule. This is the section that stops P12 wasting a 7-minute build.
3. `docs/engineering/test-improvement-standards.md` — **the backend one.** The frontend document
   (`frontend-test-standards.md`) governed P4–P11 and does not apply here; they share a philosophy
   and almost no mechanics.
4. CLAUDE.md § "UI Work — Review Cadence". ⚠️ **Read it, then note that it does not literally bind
   P12** — it governs commits that touch the UI, and P12 is backend-only. The adversarial review has
   nonetheless found real defects in every phase it has run on, including two rounds on P11. Recommend
   running one on the diff; it is a judgement call, not a rule, for this phase.

## State

- Phases done: P0, P0′, P1/P1′, P2, P3, P4a, P4b, P4c, P5, P6, P7, P8, P9, P10′, **P11 (#442, merged
  2026-08-08)**.
- **Branch from `main`.** At handover it is `c319f445`, CI green (Backend, Frontend, CodeQL, ShellCheck).
- Frontend suite **2,852 tests / 118 files**. Backend ~3,307 tests (from a local surefire run dated
  7 Aug — re-measure rather than quoting this).
- Spring Boot **4.1.0**, Java 21.
- Flag default is still `v1`. The flip is P15's. **Nothing P4–P11 built is visible to a pilot user yet.**
- Latest migration on `main` is **`V138__durham_heritage_coast_locations.sql`** — read it off the tree
  before naming a new one: `ls backend/src/main/resources/db/migration/ | sort -V | tail -1`.
- ⚠️ **A release is mid-flight.** `CHANGELOG.md`'s `[Unreleased]` is **empty**; `## [v2.17.13] -
  2026-08-08` holds P7, P8, P9, P10′ and P11. The tag **does not exist yet** — `v2.17.12` is the
  latest. A CI run from 2026-08-06 15:28 is still `status=queued` (an Actions outage), which is why
  v2.17.13 was promoted in #439 and never tagged. **Do not add to `[Unreleased]` assuming it will be
  promoted with P12** until you know whether v2.17.13 has been tagged; check `git tag -l "v2.17.*"`.
- ⚠️ `fix/refresh-token-rotation-race` is a **local-only branch, 1 unpushed commit, no PR** — on the
  *Study* machine. Not part of this work. Do not delete or rebase it.
- ⚠️ **There are two machines.** This work has run on `Chriss-MacBook-Pro` and on `Study`, both at
  `/Users/chrisgregory/IdeaProjects/goldenhour`. They have diverged once already and cost a
  non-fast-forward push and a rebase. **`git pull` before starting**, and push before switching.

---

## Task — P12: the almanac feed, and the tide fetch-horizon decision

The row reads: *"Backend: almanac feed (§3) + the tide fetch-horizon decision"*.

Two deliverables. The endpoint is the larger one; the horizon decision is the one with fallout.

### Nothing of the almanac surface exists yet

Verified: no `/api/almanac` route, no `AlmanacEvent` type, no almanac controller or service anywhere
in `backend/src` or `frontend/src`. P12 builds it from nothing. `HotTopic` (`model/HotTopic.java:50`)
is the record `AlmanacEvent {startDate, endDate, kind, title, detail, meta, regions}` has to be
reconciled with — it begins `String type, String label, String detail, …`.

**The three precedents P12 will reach for, verified:**

- **Security needs no new rule.** `SecurityConfig.java:78` is `.requestMatchers("/api/**")
  .authenticated()`, and only `/api/auth/**`, `/api/waitlist`, `/actuator/health`, `/h2-console/**`
  and the swagger paths are `permitAll()`. So `GET /api/almanac` is Bearer-and-no-role-gate **by
  inheritance** — which is exactly what §3`:536` asks for, and why it also asks you to state it
  explicitly rather than leave it inferred. `GET /api/tides` is the standing precedent for "almanac
  data is Bearer, not ADMIN" (CLAUDE.md records the doc being wrong about that once).
- **The daily cache shape.** `BriefingService.java:130-131` holds **two** `AtomicReference`s — `cache`
  and `lastKnownGood` — alongside a `DailyBriefingCacheRepository` for the `daily_briefing_cache`
  table. Copy or reject deliberately; the `lastKnownGood` half is the part people forget.
- **Hot topics really are recomputed per request.** `hotTopicAggregator.getHotTopics(today,
  today.plusDays(3))` is called at `BriefingService.java:268` (the **serve** path) and `:617` (the
  **build** path). ⚠️ Both line numbers are stale in §3, which cites `:264` and `:550` — six commits
  have moved them. The behaviour is unchanged and is the trap in item 5 below.

---

## ⚠️ The finding that changes what P12 IS

**§3`:527-529` says "The range plumbing already exists: `HotTopicStrategy.detect(from, to)` and
`HotTopicAggregator.getHotTopics(from, to)` both take arbitrary ranges."**

The **signatures** are right and current:
- `service/HotTopicStrategy.java:26` — `List<HotTopic> detect(LocalDate fromDate, LocalDate toDate);`
- `service/HotTopicAggregator.java:57` — `public List<HotTopic> getHotTopics(LocalDate fromDate, LocalDate toDate)`

**The behaviour is not.** Of 13 strategy implementations, **only 3 can actually answer for an
arbitrary far-future range**:

| Honours an arbitrary range | Bounded regardless of what you pass |
|---|---|
| `MeteorHotTopicStrategy` (static peak table, range filter at `:123`), `EquinoxHotTopicStrategy` (day loop `:89`), `SupermoonHotTopicStrategy` (day loop `:100`) | `SpringTide`/`KingTide` — read `briefingService.getCachedDays()`, capped at 5 days; `Aurora` — only ever inspects `fromDate` and `fromDate+1` (`:83-85`); `Nlc` — reads `NlcClarityService.getCached()`, built only over the briefing window; `Bluebell`, `Dust`, `Inversion`, `SnowFresh`, `SnowTops`, `StormSurge` — all `survivorSignalReader.read(fromDate, toDate)`, bounded by what the pipeline persisted |

So passing `(today, today+90)` to the existing aggregator returns a 4-day answer with a 90-day
signature. **That is P12's actual work**, and §3's sentence is the one most likely to make someone
under-scope it.

### Two more §3 claims that need correcting before you build on them

- **`NlcHotTopicStrategy` is listed as Almanac and is not ephemeris-driven.** §3`:531` puts "NLC
  season bounds" in the Almanac bucket, but the strategy gates on `NlcClarityService.getCached()`
  (`NlcHotTopicStrategy.java:63`) — a cloud-clarity scan built during the briefing run. Its *season
  bounds* are almanac; its *firing condition* is a forecast. It cannot serve 90 days without a
  second, season-only path. Decide this explicitly rather than discovering it.
- **`LunarPhaseService.classifyTide` is called by neither tide strategy.** §3`:499-500` says it is
  "already injected into `MeteorHotTopicStrategy` and `SupermoonHotTopicStrategy`". The *service* is
  injected into both (`Meteor:64,:75`; `Supermoon:67,:82`) — but neither calls `classifyTide`; they
  call `getIlluminationFraction` and `isFullMoon`/`daysFromNearestPerigee`. The real `classifyTide`
  callers are `model/ForecastDtoMapper.java:338` and `:556`, and `service/TideFactDeriver.java:101`.
  **`SpringTideHotTopicStrategy` and `KingTideHotTopicStrategy` do not use it at all** — they derive
  spring/king from the cached briefing days. So P12's two-source detection is a genuinely **new call
  path**, not a re-use of one. It is pure arithmetic and safe to call 90 days out
  (`LunarPhaseService.java:182`; the class has no injected dependencies, only `static final` epochs).

---

## The tide fetch-horizon decision

§3`:514-519`: raise `FETCH_LENGTH_SECONDS` to ~97 days, or accept the degrade rule at `:509-512`
(beyond the window a Coming-up row states the date and run position and **nothing numeric** — never
synthesise). §3 says "Do not do neither."

Everything you need to price the change, all re-verified:

**Confirmed exactly as §3 cites them** — `TideService.java:53-54` (`FETCH_LENGTH_SECONDS = 14L * 24
* 3600`), `:198` (the `T+0 to T+13` log), `:141-150` (the `length` query param, at `:148`), `:187-190`
(the windowed delete, deriving `windowEnd` from the same constant — so it scales for free),
`:234-237` (the backfill writes strictly into the past), `:517-537` (`SPRING_TIDE_FACTOR` × average
high; P95 for king). `TideService` is now **738 lines** and has changed once since §3 was written
(`4380ad76`), entirely below every cited region.

**Corrections to §3 / the P12 fallout note:**

| Cited | Correct |
|---|---|
| `TideServiceTest.java:365-367` | **`:365-368`** — line 368 carries a second "14 days" in the failure message |
| `ScheduledForecastService.java:74-92` | **`:71` + `:75-91`** — the method is `:73-96`; "per week" is in the Javadoc at `:71`, not in the body; the call site is `:85` |
| `TideService.java:96, :111` as "14 days / T+0 to T+13" | Both say **"14 days"** only. `T+0 to T+13` is at `:198` alone. True as a group, wrong per line |
| `pipeline-reference.html:1471` as a "14-day / T+13 claim" | **14-day yes, T+13 no** — `grep` finds no `T+13` anywhere in that file |
| `refreshTideExtremes` sends `length` (§3`:514`) | `refreshTideExtremes` is in **`ScheduledForecastService`** and sends nothing; the sender is `TideService.fetchAndStoreTideExtremes` (2-arg, declared `:122`) |

**Sites the fallout note MISSES.** This is the deliverable half — an incomplete list is what makes
the change dangerous:

- Production prose: `TideService.java:36` (the **class** Javadoc — the note caught the two method
  Javadocs above it and missed this one), `ForecastController.java:408`.
- Tests: `TideServiceTest.java:344` (a `@DisplayName`).
- **Four production `T+13` rationale sites**, each explaining why a missing sea state must not
  suppress a row: `BriefingWindowTide.java:48`, `WindowTideRollupBuilder.java:52` and `:70`,
  `frontend/src/utils/windowFirstRows.js:133`. Extending the fetch window does **not** move the wave
  horizon (T+4), so the reasoning survives — only the number rots.
- Tests/docs: `PlanWindowProjectorTest.java:854`, `BriefingEventSummaryWindowSerializationTest.java:138`,
  `WindowTideRollupBuilderTest.java:374`, `frontend/src/test/windowFirstRows.test.js:118`,
  `forecast-evaluation-architecture.md:486`, and **the plan doc's own §3 at `:242`, `:245`, `:502-503`**.

**Checked and clean, so you need not re-search:**
- **No `1209600` literal anywhere** — the value exists only as `14L * 24 * 3600` at `TideService.java:54`.
- **No config override.** `WorldTidesProperties` carries `apiKey` and nothing else; the `worldtides:`
  blocks in all three profiles carry only `api-key`. The constant is the single source of truth.
- **No test asserts the `length` parameter value**, and none asserts the seeded scheduler description.
  The two integration tests touching V68 assert row existence only, so an UPDATE migration breaks nothing.

**The argument for writing the migration, which the plan does not make:** `V68__scheduler_job_config.sql:22`
seeds `'Refreshes 14 days of tide extremes…'`, and `frontend/src/components/SchedulerView.jsx:278`
renders `{job.description}` straight from the DB row. **The stale string is visible in the admin UI**
(Manage → Operations → Scheduler), not a dormant DB value. If you extend the horizon and skip the
migration, the app tells its operator something false on a screen they use.

---

## Traps that carry forward

1. **Docker must be running** for anything that reaches the `test` phase. Five classes extend
   `IntegrationTestBase` and start a `postgres:17-alpine` Testcontainer; with Docker stopped you get
   an opaque `Could not find a valid Docker environment` stack trace, not a skip.
2. **Gate on the exit code, never on a grep of the output.** `$?` after a pipeline is grep's status.
   This has produced a false green twice on this project. Redirect to a file and echo `$?` as its own
   statement.
3. **Reproducing CI locally needs the integration exclusion**, or the build never reaches
   `jacoco:check` and `spotbugs:check` — the two gates most likely to fail CI:
   `./mvnw clean verify --batch-mode -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`.
   Use the path glob, not a class-name list.
4. **JaCoCo is 80% line coverage per class**, which bites small new records — cover the defensive
   null branches with real assertions rather than deleting the guards. A new `AlmanacEvent` record
   and a new controller are exactly the shape that trips this.
5. **⚠️ Hot topics are recomputed LIVE on every serve.** CLAUDE.md's architecture bullet: anything
   derivable only during `refreshBriefing` and written into `daily_briefing_cache` is serialised on
   the build path and **thrown away on the first request** — and because `HotTopic` is a record whose
   `equals` compares every component, persisting a not-live field *guarantees* it is discarded. The
   working shape is an in-memory `AtomicReference` populated in `refreshBriefing` and read back inside
   `detect()`. If P12's almanac cache is built the obvious way it will hit this.
6. **The frontend audit gate.** `npm run test` is not the frontend CI job — that job is lint → Vitest
   → `npm audit --audit-level=high` → build. P11 lost a CI round to the audit step on a change that
   touched no dependency file. P12 is backend, so this bites only if you touch `frontend/`.
7. **Never `git checkout --` to restore a mutation on uncommitted work** — it deletes the change under
   test. `cp` to a scratchpad and back.

---

## Running it locally

```bash
cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local
```

Port **8083**. `-Plocal-dev` is load-bearing — H2 is `test` scope and that profile re-adds it at
runtime. `preview_start({name:"backend"})` works (`.claude/launch.json` is repaired). Frontend on
5179 via `frontend-p11`, or add `frontend-p12` on 5180; `frontend/.env.local` already points at 8083.
Login `admin` / `golden2026`.

⚠️ **The local DB has never had an evaluation run** — `cached_evaluation`, `forecast_evaluation` and
`forecast_score` are empty, and `BriefingHonestyFilter.fullRewrite` empties every region's slots. For
P12 this matters less than it did for P4–P11: the almanac path is ephemeris-driven and `tide_extreme`
**does** have real rows. But anything you check through the *briefing* still needs a fixture.

---

## What P11's review found, because these species recur

Two rounds (a pre-build decision panel, then six prosecutor lenses over the diff with one refuter per
charge). Both found real defects a green suite had passed over:

- **The most valuable findings were tests that could not fail** — three of them. One asserted "no
  panel appears" with no fake timers and an empty lookup, so nothing could have appeared either way.
  Another passed on a different branch than the one it named. A third passed because a *dialog's focus
  move* dismissed the panel, not because the flag under test did. **Where two mechanisms can produce
  the same observable, a test of one has to neutralise the other first.**
- **A charge can be right about the blob it read and wrong about the tree.** One finding (a footer
  clipped at 400% zoom) was correct against the staged diff and obsolete by the time it landed,
  because a *different* fix from the same review had already closed it. I built the fix, measured,
  disproved it, and reverted. Measure before you ship a fix for a reviewer's arithmetic.
- **Citations rot fast.** Of the §3 citations checked for this handover, two were stale by 4 and 67
  lines respectively, and three claims were wrong as written. §3 is one week old.
- **Say what you could not verify.** P11's record names the fixture-only paths, the absent screen
  reader/axe pass, and Chrome-only measurement, rather than implying coverage.
