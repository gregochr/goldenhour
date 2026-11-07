# Closing the "Where it falls short" gaps

Status: planned 2026-11-07. Owner decisions recorded below. Phase 1 merged (#1761). Phase 2 merged (#1764) and is a required check. Phase 3 next.

`landing/engineering.html` (`#falls-short`) and `landing/how-its-built.html` (`#improving`) list five gaps.
This plan closes the ones worth closing, in the order below, and changes the copy only once each fix is
live. The copy follows the voice in `how-its-built-plan.md`: warm, straightforward and measured, in British
English, without overclaiming.

| # | Gap as published | Plan |
|---|---|---|
| 1 | Mutation testing covers the server only | **Out of date.** Stryker now tests `frontend/src/utils` weekly (`frontend-mutation-testing-plan.md`). Phase 1 corrects `engineering.html`. |
| 2 | The real-browser tests run by hand | **Partly closed.** Phase 2 (merged, #1764): a Playwright smoke suite in CI, **a required check**. The rest is Phase 3: seeded data, so the Map and Plan specs run too. |
| 3 | The prompt regression suite runs by hand | Phase 5: run it on PRs that change prompts or evaluation code, behind an approval environment. |
| 4 | One host, no staging copy | Phase 4: a smoke check straight after deploy and an automatic rollback. A staging host is an owner decision (below). |
| 5 | Not a professional penetration test | `security.txt` (Phase 6). Whether to pay for a test is an owner decision. |

Each phase is one PR and follows CLAUDE.md's workflow: the planner writes and reviews, Sonnet builds in a
worktree, and the PR merges only with CI green and Codex 👍 on the exact head.

---

## Phase 1: correct the mutation-testing copy (`engineering.html` only)

`how-its-built.html` is **not** touched here. Its mutation sentences belong to Phase 2 of
`frontend-mutation-testing-plan.md`, which a separate session is carrying out, and which also sets the
Stryker floor. Touching it here would conflict with that work.

In `landing/engineering.html`, every claim must match the merged `stryker.yml` and `stryker.config.mjs`:

- **The checks table:** add a row after PIT:
  `Stryker mutation testing | Weekly | The app's client logic | An issue is opened for me if the run fails`.
  Do not claim a score threshold: `thresholds.break` is still `null`.
- **"Testing beyond coverage":** extend the mutation bullet with one sentence. Stryker does the same for the
  app's client logic each week. The app's screens are not mutation-tested yet.
- **"Where it falls short":** replace the mutation line with:
  "Mutation testing covers the server and the app's client logic, but not the app's screens, and runs
  weekly rather than on every change."
- **The plan doc:** update the matching lines in `docs/engineering/engineering-page-plan.md`.
- **Changelog:** add a `changelog.d/` entry. It is published with `./release-landing.sh`, which the owner runs.

---

## Phase 2: a Playwright smoke suite that blocks merging

Owner decision (2026-11-07): the smoke suite is a **required check**, so it must be reliable enough to block a
merge, and (because `deploy.yml` calls `ci.yml`) a tagged release too. Reliability is a design requirement,
not something to tune afterwards. This section was revised after an adversarial review on 2026-11-07; the
numbered review items are folded in below.

### The stack
- **Job:** a new `e2e` job in `.github/workflows/ci.yml`, named exactly **`E2E — Playwright smoke`** (the
  branch-protection string).
  - Runs on `ubuntu-24.04`: pinned, never `ubuntu-latest`, and never the self-hosted runner, which is shared
    with production.
  - `needs: changes`, with `timeout-minutes: 15`.
  - Every expensive step carries `if: needs.changes.outputs.docs_only != 'true'`, plus the same "skipped"
    echo step the other jobs use. **No job-level `if`**, so the check always reports, Dependabot PRs
    included (the job needs no secrets).
- **Backend:**
  - Build: `./mvnw -Plocal-dev -Dmaven.test.skip=true package`. `-Plocal-dev` adds H2 and `src/local/java`
    to the jar.
  - Run: `java -jar target/*.jar --spring.profiles.active=local --server.port=8083`, from a temporary
    working directory, so the H2 file is fresh on every run.
  - **Readiness: `/actuator/health/container` only**, which covers `db` and `ping`. The bare `/actuator/health`
    aggregates the Open-Meteo, tide and Anthropic probes. Those make real outbound calls, so waiting on it
    would tie the gate to third-party uptime.
- **Frontend:** `npm run build`, then `vite preview --host 127.0.0.1 --port 4173 --strictPort` with
  `VITE_API_TARGET=http://127.0.0.1:8083`. Preview inherits `server.proxy`. Without the variable the proxy
  falls back to 8082 and every call returns 502.
- **Both servers start from Playwright:** a `webServer` array in `playwright.smoke.config.js`, so the recipe
  is the same locally and in CI, with built-in timeouts and the servers' output on failure.
- **Logging in:** the specs post to `/api/auth/login` **through the preview origin**, so the proxy is
  exercised and no backend port is hard-coded. The local admin is created by `LocalAdminSeeder`
  (`admin` / `golden2026`).
- **No spend, by construction:**
  - The job gets no secrets, and `ANTHROPIC_API_KEY` is set to a fake value. Ask is on its stub (local
    default: enabled, stub).
  - The backend does make outbound calls: the status stream runs the health probes, which call
    `api.anthropic.com` and get a free 401, and `api.open-meteo.com`. Those components are soft, so a failure
    shows a DEGRADED banner and fails nothing.
  - So **no spec asserts on the absence of the health banner**.
- **Invariant: the smoke database has no `scheduler_job_config` rows.** Every scheduled job (aurora polling,
  NLC scraping, tide gauges, forecast runs) registers only from those rows, and they come from Flyway
  migrations, which are off on the `local` profile.
  - A spec asserts that `/api/scheduler/jobs` returns an empty list, so a future seeder that adds rows fails
    loudly instead of quietly starting jobs.
  - `photocast.ask.seed-local-fixture` stays off, because it triggers a briefing build.

### Reliability rules
1. **No service worker:** `use: { serviceWorkers: 'block' }`. The PWA's worker (`autoUpdate`, `CacheFirst`
   on `/assets/`, a navigation fallback) would otherwise take over partway through a test, and requests it
   handles bypass `page.route`.
   - If blocking registration makes `registerSW.js` raise a `pageerror`, build the smoke bundle with the PWA
     register disabled. Do not allow-list the error.
2. **Third parties are fulfilled, never aborted:** an aborted request logs `net::ERR_FAILED` as a console
   error. A shared fixture fulfils:
   - `server.arcgisonline.com` tiles, with a real 1×1 PNG;
   - `challenges.cloudflare.com/turnstile/v0/api.js`, with a script defining
     `window.turnstile = { render: (el, o) => { o.callback('e2e-token'); return 'w1'; }, remove() {} }`.
     The widget otherwise waits 10 s before enabling the submit button. The backend's empty
     `turnstile.secret-key` admits the login on the verified path, so the fallback budget never applies.

   Every other off-origin request is **recorded** and asserted empty at the end of each test. On-demand
   third parties (the guide iframe, Nominatim) are not touched by these specs.
3. **No fixed sleeps.** Only web-first assertions and `waitForResponse`.
4. **No clock freezing in Phase 2:** nothing on an empty database depends on "today", and freezing only the
   browser clock splits the client's date from the server's. Pinning the date belongs to Phase 3, together
   with the backend's clock or Rewind.
5. **Chromium only, pinned:**
   - `"@playwright/test": "1.64.1"` exactly. Update the lockfile's root `packages[""]` entry too, or
     `npm ci` fails.
   - Cache `~/.cache/ms-playwright` keyed on that version, and always run
     `npx playwright install-deps chromium`, because the apt packages are not cached.
   - `workers: 2`, fixed.
5a. **Sign-out runs alone, last** (its own project, `dependencies: ['smoke']`, `workers: 1`): logout bumps the account-wide credentials version and would 401 any parallel spec sharing the admin.
6. **Retries: 0.** Traces with `retain-on-failure`; on failure, the report, traces and both servers' logs are
   uploaded as an artifact.
7. **Console and network hygiene.** A fixture records `console` messages of type `error`, `pageerror`
   events, and responses with status ≥ 400 from the app's own origin. Each test ends by asserting there were
   none, apart from a **named allow-list**, where each entry gives its reason and a pointer to its fix.
   Chromium logs every failed XHR as a console error, so this rule also covers 4xx and 5xx responses.
8. **Time budget:** the job runs in under 10 minutes and the specs in under 2.

### Build order
1. **Local proof of a fresh boot.** Delete the data directory, build and run the jar as above on JDK 25, and
   record in the PR that `/actuator/health/container` is UP and admin login works. No `application.yml` is
   present (it is gitignored, and CI won't have one).
2. **Discovery run.** Write the specs, then run them with the hygiene assertions switched to *record only*.
   List every console error, `pageerror` and non-2xx response per tab in the PR. Each must be fixed in this
   PR if it is small and clearly a bug, or otherwise allow-listed with a reason and a follow-up.
   - Check logout specifically: the owner saw console errors on logout around v2.38.1.
3. Turn the assertions on.
4. **Proof of reliability:** both are temporary and removed before merge, and both results are posted on the
   PR.
   - `--repeat-each=20` in a temporary step, with its own `timeout-minutes`, and it must pass every time.
   - The whole job, boot included, 5 times. Use a temporary `attempt: [1, 2, 3, 4, 5]` matrix; it must pass
     every time.

### What the smoke suite covers (empty database)
- **Login and logout:** the form renders and accepts input (with the Turnstile stub), and the admin logs in.
  Logging out returns to the form cleanly.
- **Each top-level tab renders its container `data-testid` and empty state:** Map, the Plan matrix
  (*At a Glance*), Coming up, and Operations (as admin).
- **The Map:** the Leaflet container draws and tile requests are made, though fulfilled by the stub.
- **Ask PhotoCast** (enabled locally): the box opens. If the stub answers on an empty database, the answer
  renders; if it doesn't, assert only that the box and its empty state render. Record which in the PR.
- **Phone width:** use an iPhone-sized viewport on Chromium and assert only on the tab bar. The peek sheet
  can't be tested with no locations.
- **The scheduler invariant above.**

### Files
- `frontend/src/test/e2e/smoke/*.spec.js`, the shared fixture, and `frontend/playwright.smoke.config.js`.
- `"test:e2e:smoke"` in `package.json`.
- The existing `playwright.config.js` gains `testIgnore: '**/smoke/**'`. Otherwise it and the two existing
  specs are unchanged; they move to Phase 3.
- Docs:
  - `docs/engineering/reference/dev-and-testing.md`: the gate, the local recipe and the rules above.
  - CLAUDE.md: one line beside the frontend commands.
  - A changelog entry.

### After merge (owner)
Add **"E2E — Playwright smoke"** to the branch-protection required checks. The landing copy changes only once
the check is required.

## Phase 3: seeded data, real-browser journeys, nginx and accessibility

Planned 2026-11-07 from a research pass over `origin/main` (`3ac0bb8a`). Four PRs, in this order:

| PR | Scope | Required? |
|---|---|---|
| **3a.1** | The seeder, plus Map, window stepping, forecast drill-down, Plan dialogs and `door1-obstacles` specs | Inside the existing required smoke job |
| **3a.2** | LITE and PRO users, and the role-gating specs | Inside the existing required smoke job |
| **3b** | The smoke job serves the built app through the **production nginx config** instead of `vite preview` | Inside the existing required smoke job |
| **3c** | An axe accessibility scan of each tab and dialog | **Advisory** job; becomes required only once clean, on the owner's say-so |

### 3a.1: the seeder and the journeys

**Why a seeder, not the real build.**
- `BriefingService.doRefreshBriefing` always fetches live Open-Meteo weather and calls two Claude services (the gloss and the best bet), and no property switches them off. `AskLocalFixtureSeeder` triggers that build, which is why `application-local.yml` warns about it.
- So the e2e seeder must **never** call `refreshBriefing*`. It assembles the briefing itself from the same builder beans, using synthetic weather.

**The seeder.** `src/local/java/com/gregochr/goldenhour/service/E2eLocalFixtureSeeder`.
- **Gating:** `@Profile("local & !prod")` and `@ConditionalOnProperty(name = "photocast.e2e.seed-fixture", havingValue = "true")`, with the same runtime refusals as `AskLocalFixtureSeeder` (local profile, H2 URL). Only `scripts/e2e-smoke-backend.sh` sets the property. `photocast.ask.seed-local-fixture` stays off.
- **When it runs:** after `LocalAdminSeeder`, never at `@PostConstruct`. The admin seeder only runs on an empty user table.
- **What it writes, in order:**
  1. About 3 regions and about 12 locations, a few of them coastal with tide preferences. They are saved through the **repositories**, never `LocationService` or the controller, which can call `LocationEnrichmentService` and fetch elevation from Open-Meteo.
  2. Synthetic tide extremes for the coastal locations, as the Ask seeder does.
  3. **`cached_evaluation`, through `BriefingEvaluationService.mergeFromBatch`**, for every location and every window from today to T+4. Keys come from `CacheKeyFactory`.
     - Every location is rated in every window, so the sample gate and honesty filter never blank a region.
     - The rating is deterministic and differs between adjacent windows: `1 + ((epochDay*2 + typeIndex + locIndex) % 5)`.
     - `mergeFromBatch` writes the in-memory map and the database, so no restart is needed.
  4. **`forecast_evaluation`, through its repository**, for T-4 to T+4, with the same star for each slot as `cached_evaluation`.
     - Fields: `rating`, `fierySkyPotential`, `goldenHourPotential`, `summary`, `solarEventTime`, temperature, wind, precipitation probability, and tide where relevant.
     - `forecastRunAt` and `scoredAt` are set before each event for past slots, so `AsOfEventResolver` serves them.
     - Never `PENDING`.
     - These rows have an `id`, so the forecast detail works.
  5. **The briefing:**
     - Build a synthetic `OpenMeteoForecastResponse` per location.
     - Run `BriefingSlotBuilder.snapshotFor` and `buildSlot`, then `BriefingHierarchyBuilder.buildDays`, then `BriefingHeadlineGenerator`.
     - Stamp a fixed `glossHeadline` and `glossDetail` on each region, so Best picks exist with no Claude call.
     - Persist it to `daily_briefing_cache` id 1, and call the package-private `BriefingService.loadPersistedBriefing()` from the same package. That needs no main-code seam.
     - Ratings and verdicts are applied at serve time by `ServedBriefingAssembler.reEnrichVerdicts`.
     - Verify during the build that the serve path keeps the stamped gloss; the research could not confirm it.
- **Never written:** `scheduler_job_config` and `model_selection`. The scheduler spec keeps asserting `[]`.
- **Dates:** relative to the server's UK "today" at boot (the injected `Clock` / `ForecastHorizon`). No fixed clock; see "Dates" below.

**Readiness waits for the seed.** Tomcat accepts requests before `ApplicationReadyEvent` listeners finish, so `/actuator/health/container` can be UP before the seed is done.
- The seeder exposes an `e2eFixture` `HealthIndicator` that is DOWN until seeding completes.
- The script adds it to the group with `--management.endpoint.health.group.container.include=db,ping,e2eFixture`, and only there. The production group is unchanged.

**No third-party calls from the backend.** Browser hygiene cannot see these, so a CI step after the suite greps `backend.log` for the hosts the seeder must never touch. The health probes' calls to `api.open-meteo.com` and `api.anthropic.com` are known and expected, so the check must tell them apart.
- If it can't do that reliably, record the outbound hosts and assert there is no briefing-build call, e.g. no `BriefingService` refresh log line.
- The exact signal is settled during the build, and the PR says what it is.

**Dates.**
- **Rollover:** the seed covers T-4 to T+4, so a UK-midnight rollover mid-run still leaves at least 6 future windows.
- **The real risk:** a run that crosses a sunrise or sunset, roughly 0.2–0.4% per run. The first window becomes past, and counts shift by one. Specs absorb it:
  - **never use the first window;**
  - assert relationships, not fixed counts;
  - wait on served responses.
- **No clock freezing**, as in Phase 2. If the gate ever flakes on this, the follow-up is a local-only `Clock.offset` bean plus a matching `page.clock`. That needs the owner's sign-off, because it changes the Phase 2 rule.

**Specs** (all on the shared fixture, with the hygiene assertion and no sleeps):
- **Phase 2 specs:** `tabs.spec.js` and `phone.spec.js` are rewritten for seeded data: the Map tab now exists, and the tab bar has four tabs. The empty states stay covered by Vitest; there's no second empty-database backend.
- **The Map:**
  - the Leaflet container draws;
  - a tile request is made (fulfilled by the stub);
  - `map-label-chip`s carry `"{name}, {n} star"` labels that match the served ratings;
  - a chip opens `map-callout`, whose `map-callout-score` matches the served slot;
  - `map-callout-close` and Escape close it.
- **Stepping through the windows** (owner request), with `wf-win-next` / `wf-win-prev` and ArrowRight / ArrowLeft:
  - From the current window, step **forward** to the end. At each step the pill text changes, and at least one seeded chip's star changes, which the seed formula guarantees.
  - At the last window `wf-win-next` is `disabled`, with no wrap.
  - Step **back** past today into the past windows: `wf-win-past` appears.
  - At the oldest loaded day, `‹` loads the next older day; wait on `waitForResponse(/\/api\/forecast\?date=/)`.
  - It stops at the T-4 floor, where the button is disabled and the status line explains why.
- **Drilling into one forecast** (owner request):
  - **From the Map:** `map-label-chip` → `map-callout` → `map-callout-four-days` → `location-sheet`. Expanding a row with `location-sheet-row-toggle` waits on `waitForResponse(/\/api\/forecast\/\d+$/)`, then checks that `location-sheet-facts` and its fact rows show the seeded values (rating, comfort, tide).
  - **From the Plan:** `wf-heat-card` → `window-sheet` → `window-spot` or `wf-row-map-chip` → `location-sheet`, two dialogs deep.
  - **Closing:** Escape closes the top layer first, then the next. The close buttons work. **A third dialog never opens.**
- **`door1-obstacles.spec.js`** moves into `smoke/` on the shared fixture, with its "restart + `POST /api/briefing/run`" setup removed, because the seed replaces it.
- **Ask:** `ask.spec.js` is re-checked on seeded data, because Ready answers may now exist. It stays non-spending.

### 3a.1 review amendments (2026-11-07). These are binding and win over the bullets above where they conflict.

Two adversarial reviews (one on the seeder, one on the specs) read the plan against `origin/main`. Their findings are folded in here.

**The seeder**
1. **Enough places to pass the sample gate.**
   - `VerdictSampleGate` needs at least 5 rated places, covering at least half of each region's roster. "About 12 places in 3 regions" would fail it everywhere, and no Best pick would ever appear.
   - Seed **3 regions × 6 places**, LANDSCAPE or SEASCAPE only. No WOODLAND, BLUEBELL or WILDLIFE: they don't vote.
   - Space every place **at least 10 km** from every other, so the neighbour guard (5 km) never flags a slot. Keep them **at least about 40 px apart at the opening zoom**, and off region centroids, so the chips aren't collision-culled.
   - Avoid ", " in names. Create no marine or big-seas data, so there are no big-seas chips and no `hw:` rows.
2. **The write order.** Persisting without enrichment drops the gloss and every pick (`BriefingRegionEvaluationRollup:308–310` nulls `glossHeadline` when the verdict changes on serve).
   1. Regions, places, tides.
   2. `forecast_evaluation`.
   3. `cached_evaluation` through `mergeFromBatch`.
   4. `snapshotFor` → `buildSlot` → `buildDays`.
   5. **`BriefingRegionEvaluationRollup.enrich(days, scoreResolver, triagedResolver)`**, with the resolvers built as `BriefingService.bulkScoreResolver` / `bulkTriagedResolver` do. That means `EvaluationViewService.getScoresForEnrichmentBulk` and `getTriagedByBatchLocationNamesBulk`.
   6. Stamp the gloss with `region.withGloss(h, d)`.
   7. `headlineGenerator.generateHeadline`, with `BestBetResult.noPicks()`.
   8. Serialise with the injected **Jackson 2** `ObjectMapper` and persist to `daily_briefing_cache` id 1.
   9. Call `loadPersistedBriefing()`.
   10. Call **`almanacService.refresh()`**, so Coming up sees the seeded tides.
   11. Self-check: `getCachedBriefingForApi()` has at least one window with a BEST pick, and the row counts read back. `persistToDb` swallows its own errors.

   Only then does the seeder go UP.
3. **The ratings.** Use a per-region, per-window base plus a small per-place offset, clamped to 1–5.
   - Every chip still changes between adjacent solar windows.
   - Each day has at least one region averaging ≥ 3.5 (WORTH_IT) and one averaging ≤ 2.5, which exercises `AlsoGoodFloor`.
   - The formula lives in one place that the specs can mirror. Better still, specs read the served `/api/forecast` response as the oracle, rather than re-deriving it.
4. **The timestamps.**
   - Every `forecast_evaluation` row has `forecastRunAt` (a naive **UTC** `LocalDateTime`) and `scoredAt` set to `min(seedStart − 1h, event − 6h)`.
   - The event time comes from `SolarService` and is stored in `solarEventTime`. The client's past/future split must agree with the server's.
   - Set `daysAhead` consistently. `batchState` and the triage reason are null.
   - Without this, today's already-passed windows serve no star.
5. **The past range is T-3..T-1; T-4 is left empty on purpose.** The control then reaches its floor with `wf-win-status` reading "No stored forecast for {day}", and `‹` disabled with `aria-describedby`. That is a real assertion, not an empty status.
6. **Readiness.**
   - A separate bean named exactly `e2eFixtureHealthIndicator` (Boot 4 `org.springframework.boot.health.contributor.HealthIndicator`). It is DOWN until the self-check passes.
   - The seeder runs on `@EventListener(ApplicationReadyEvent.class)` with `@Order(LOWEST_PRECEDENCE)`. `LocalAdminSeeder` runs at `@PostConstruct`, so it has always run first.
   - On failure it logs an ERROR and stays DOWN. It never swallows the failure the way `AskLocalFixtureSeeder` does.
   - It refuses to seed unless the `locations` table is empty, as well as the local-profile and H2 refusals.
   - The script adds `--management.endpoint.health.cache.time-to-live=0s`.
7. **Nothing leaves the machine.**
   - The script starts the jar with a **dead HTTPS/HTTP proxy**: `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<unused port> -Dhttp.proxyHost=… -Dhttp.nonProxyHosts='localhost|127.0.0.1'`. Every outbound call fails locally. Prove that OkHttp (Anthropic) honours it in the discovery run.
   - The external health probes then read DOWN, so the status SSE reports DEGRADED. Specs never assert on that banner, and the discovery run confirms it produces no browser console error.
   - A CI step after the suite fails if `backend.log` contains `Daily briefing refresh started`, `Briefing weather fetch`, `Horizon cloud fetch` or `[ASK FIXTURE]`. It also fails if `[E2E FIXTURE] seeded` is **absent**, so the check can't pass vacuously.
8. **The seeder's CI gate.** `src/local` and `src/local-test` are outside CI's backend job, which has no JaCoCo, checkstyle or SpotBugs for them. The E2E job adds, before packaging:
   - `./mvnw -Plocal-dev test -Dtest='E2eLocalFixtureSeeder*' -DfailIfNoSpecifiedTests=false`;
   - `./mvnw -Plocal-dev checkstyle:check`.

   The seeder tests live in `src/local-test/java`.
9. **Ask:** Ready answers will **not** exist, because nothing runs `AskReadyPrecompute`. `ask.spec.js` keeps asserting the box and its allowance only.

**The specs**
1. **The opening tab is a race.**
   - Desktop prefers Map only if `/api/forecast` arrives within 1.5 s.
   - Every spec clicks its tab explicitly (`window-first-tab-*`), then asserts `aria-selected="true"` and its panel.
   - Never assert the desktop default.
   - On phone, assert Plan is selected and the tabs are, in order, `[Plan label, 'Coming up', 'Map', 'Operations']`.
2. **A placement-settled signal.**
   - A one-line frontend seam: `data-placed="true"` on `map-labels` once `isMeasured`, with a Vitest test.
   - Specs also wait for `document.fonts.ready`, and use only retrying assertions (`expect.poll`) over visible chips.
   - Never read positions once and act on them.
3. **Chips.**
   - Select visible chips by role: `getByRole('button', { name: /^(.+?), (\d) star(?:,|$)/ })`. Hidden and culled chips stay in the DOM.
   - Require at least one visible chip. Every visible chip's star must equal the oracle (the served `/api/forecast`).
   - Close the callout (Escape or `map-callout-close`, then `map-callout` count 0) before clicking another chip.
   - Assert `map-callout` `toBeVisible()`, and `map-callout-score` against `/^\d★ /` with the oracle star.
4. **Tiles:** the fixture's tile route keeps a counter. The spec asserts `expect.poll(() => tiles).toBeGreaterThan(0)` after selecting Map. No `waitForRequest`, which could arm too late.
5. **Stepping through windows.**
   - Open the pill menu once and read `[data-testid="wf-win-row"][data-ev-id]` (ids look like `solar:YYYY-MM-DD:TYPE`) and the active row (`aria-selected="true"`). Close the menu with Escape.
   - Step **solar rows only**.
   - After each step, the pill text changes; then check that visible chips present in both windows have different stars. Require that intersection to be non-empty. Expected stars come from the oracle by `data-ev-id`, not by step count.
   - **Keyboard leg:** focus `wf-win-pill` before ArrowLeft/ArrowRight, because focus drops to `<body>` when `›` disables.
   - **The forward end:** `wf-win-next` is `disabled`, and nothing wraps.
   - **Loading older days:**
     - Only two loads happen: T-3 (rows) and T-4 (empty). The initial list covers T-2..T+4.
     - For each load, register `waitForResponse(/\/api\/forecast\?date=\d{4}-\d\d-\d\d$/)` first, then press `‹` (labelled `Load {day}`), and expect a 200.
     - Then `wf-win-loading` count is 0, and the pill shows `wf-win-past`.
     - After a load the selection jumps to that day's **sunset**, so step `‹` to its sunrise before the next load.
     - Assert `wf-win-past` appears on *some* step back, not on a fixed one.
   - **The floor** is item 5 under "The seeder" above.
   - **Phone:** the pill opens the peek sheet, not the menu. Use `wf-map-peek-btn-win`, `aria-expanded`, `wf-map-peek-win-row`, and wait out the 0.28 s transition with web-first assertions.
6. **The drill-down.**
   - **From the Map:** chip → `map-callout` → **`map-callout-reason`** ("Four days here ›"; `map-callout-four-days` is the LITE outside-picks variant) → `location-sheet`. The seed must make the callout's reason non-null: confirm `/api/briefing/evaluate/scores` carries the seeded `summary`, or use the region gloss.
   - **Row expansion:** the sheet opens with one row **already expanded**, and detail responses are cached per module.
     - Register the waiter for the auto-expanded row before the click that opens the sheet.
     - For a second row, pick one with `aria-expanded="false"`, take its forecast id from the oracle, and register `waitForResponse(url ends with '/api/forecast/' + id)` before clicking.
     - Then, scoped to that row, `location-sheet-facts-skeleton` count is 0 and `location-sheet-facts` is visible.
   - **Values:**
     - the rating is in `location-sheet-rating`;
     - comfort is in `location-sheet-comfort`;
     - `location-sheet-fact-rising-tide` appears only when the seeded `nextHighTideTime` is within ±90 min of the event, so seed one coastal slot to hit it;
     - `location-sheet-generated` comes from `forecastRunAt`.
   - **From the Plan:** `wf-heat-card` → `window-sheet` → **`window-spot`** (primary) → `location-sheet`.
   - **Dialog depth:** an `addInitScript` MutationObserver records the maximum concurrent `.app-safe-modal[role="dialog"]` and `[aria-modal="true"]`. Assert ≤ 2 and ≤ 1 at test end; polling would miss a transient third.
   - **Escape order:**
     - On the Plan route, the sheet closes, then the popup.
     - On the Map route, the sheet closes, then focus returns to `map-callout-reason`, then the selection clears.
   - **Don't click** guide links (they open a new tab off-origin), `location-sheet-plan` or `location-sheet-map`.
   - **The browser Back button closes no dialog** (there is no `pushState` for dialogs), so it is **not** tested. That is a UX question for the owner, not a smoke assertion.
7. **`door1-obstacles.spec.js`**
   - Use the fixture login. Desktop viewport only in the gate; the 390×844 variant is added only if the discovery run proves it stable.
   - Wait for `document.fonts.ready` after each sheet opens, and use `expect.poll` stability twice instead of the bounded RAF loop.
   - Assert `cardCount >= 6`. A sample point outside the viewport fails the test.
8. **Shared state:** `global-setup.js` also performs the one Coming up "seen" write (`PUT /api/user/settings/coming-up-seen`). Specs never assert the Coming up badge.
9. **Discovery run on seeded data, per spec, in record mode,** before the hygiene assertions are switched on. Findings go in the PR, as in Phase 2. It covers:
   - the Operations-tab requests;
   - the DEGRADED status under the dead proxy;
   - the door1 phone viewport;
   - big-seas and `hw:` rows;
   - the callout reason.
10. **Time.**
    - The suite budget becomes **under 3 minutes on 2 workers**.
    - The temporary proof uses `--repeat-each=10` over the whole suite, with its own `timeout-minutes: 45`, and the job timeout raised **for the proof commit only**. It must pass every time across the 5-run matrix.
    - The job's permanent timeout becomes 20 minutes, to cover the seeder tests and the boot.

### 3a.2: role gating

- **Users:** the seeder also inserts `lite` and `pro` users after the admin, with an encoded password, `enabled`, `passwordChangeRequired=false` and `postcodePromptDismissedAt=now`. These are insertable columns, set on the builder at insert time.
- **Sign-in:** a `loginAs(role)` helper in `fixture.js`. The specs use these users, which also avoids the shared-admin logout hazard.
- **Sub-regions match production:** the script passes `--photocast.sub-regions.enforce=true`, and the seeder gives `lite` sub-region picks that cover some regions but not all.
- **What each role checks:**
  - **Every non-admin:** no Operations tab, and a 403 from an admin endpoint, called with `page.request` so the page's hygiene never sees the 403.
  - **LITE:**
    - Pro pills and `ProUpsellDoor` doors (`wf-scores-upsell` and others) are present.
    - The Ask trigger shows the Pro lens, and no `/api/ask*` request is made.
    - "How was it?" is locked (`pro-pill`, "Rating is part of Pro.").
    - Chips outside its picks carry `data-gated="true"`, the callout says "Outside your sub-regions", and the location sheet shows `location-sheet-outside`.
  - **PRO:**
    - None of those locks appear.
    - It can rate one past window through `outcome-rating-block`. This is the only spec that writes shared state, so it runs in the serial project with the logout spec.

### 3b: the production nginx config in CI

- **No image build is needed.** The frontend image is plain `nginx:alpine` serving `dist`. Production mounts the repo-root `nginx.conf` at runtime (`docker-compose.yml`). CI does the same: `docker run nginx:alpine` with `frontend/dist` and `nginx.conf` mounted, `-p 127.0.0.1:4173:80`, and `--add-host=goldenhour-backend:host-gateway`. The backend then runs on 8082 in this mode, so the prod upstream name resolves with no edit to the config.
- **Switching modes:** `E2E_WEB=nginx` swaps `webServer[1]` for `scripts/e2e-smoke-nginx.sh`. CI sets it; local runs keep `vite preview`, since there's no Docker locally.
- **What it adds:** a real-browser test of the strict CSP (`style-src 'self'`), the security headers, the SPA fallback, the caching headers and the SSE proxying. A CSP block logs a console error, which the hygiene assertion already fails on.
- **To prove in the discovery run:**
  - `upgrade-insecure-requests` does not upgrade `http://127.0.0.1`. If it does, use a CI-only copy without that directive, and record why.
  - The SSE status stream works through the proxy.
  - Nothing in the app injects a `<style>` element.
- Same reliability proof as Phase 2.

### 3c: the accessibility scan (advisory)

- **The job:** a separate job, `E2E — accessibility (advisory)`, on the 3b stack (seeded, nginx), and **not** a required check. `@axe-core/playwright` scans each main tab and the open dialogs (window sheet, location sheet, callout) at desktop and phone widths.
- **What fails it:** serious and critical violations fail the job. Minor and moderate ones are reported only.
- **The report:** an HTML or JSON report is uploaded every run.
- **Fixing:** existing violations are fixed in their own PRs, **never** suppressed by disabling rules. The owner makes the check required once it's clean.
- **Landing copy is unchanged** until then, and even after it the copy never claims full accessibility.

### Order and gates

- **Order:** each PR follows the Phase 2 recipe. The planner reviews it, Sonnet builds it in a worktree, and the full local gates must pass:
  - backend: checkstyle and `clean verify`, with the seeder's own unit tests;
  - frontend: the four commands.
- **Proof for 3a.1 and 3b:** the temporary 5× job matrix plus `--repeat-each=20`, removed before merge.
- **Copy:** once 3a.1 and 3a.2 are merged, the landing pages drop "the map and planning screens are still checked by hand".

## Phase 4: post-deploy smoke and automatic rollback

- Read `deploy.yml` and the rollback procedure in `reference/platform.md` before planning.
- **The idea:** straight after deploy, probe health and a few endpoints that need no login, plus one that
  does, using a smoke account. If any probe fails, redeploy the previous image tags automatically and open
  an issue.
- **No new infrastructure.** This narrows the "one host" gap but doesn't close it.

## Phase 5: prompt regression on prompt-changing PRs

- **Trigger:** a `pull_request` job with a path filter on the prompt and evaluation code (the exact paths are
  to be listed at planning time).
- **Approval:** the job runs in a GitHub **environment** with the owner as required reviewer, so no run
  spends money without a click. The `ANTHROPIC_API_KEY` secret lives only in that environment.
- **Not required, and never automatic on other PRs.** The assertions in `src/test/java/.../regression/` are
  never changed by a session.
- **Owner setup:** create the environment and its secret.

## Phase 6: `security.txt`

Serve `/.well-known/security.txt` from the landing site and the app, with a contact address and an expiry
date. Update the security copy.

## Owner decisions still open
- **A staging host:** a second small machine, at about £5–10 a month, to which releases go first. This is the
  only option that lets the page drop "no separate staging copy".
- **A professional penetration test:** a few thousand pounds for a CREST-style test of a small web app. It
  is worth it when PhotoCast takes payments or its user base grows. Until then the line stays on the page.

## Copy changes as phases land
Only once each phase is live. Each phase's PR includes its own copy change in both landing pages (except
Phase 1, as above), and the matching plan docs.
