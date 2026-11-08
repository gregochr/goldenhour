# Closing the "Where it falls short" gaps

Status: planned 2026-11-07. Owner decisions recorded below. Phase 1 merged (#1761). Phase 2 merged (#1764) and is a required check. Phase 3a.1 merged (#1772). Phase 3a.2 merged (#1778). Back 2b merged (#1781). Phase 3b merged (#1786). Phase 3c built on `test/e2e-a11y` (advisory workflow `a11y.yml`; it lands red on three serious rules, each fixed in its own PR). Phase 4 built on `feature/post-deploy-smoke` (PR pending; it ships **report-only** until the owner completes the actions listed under "Phase 4: owner actions").

`landing/engineering.html` (`#falls-short`) and `landing/how-its-built.html` (`#improving`) list five gaps.
This plan closes the ones worth closing, in the order below, and changes the copy only once each fix is
live. The copy follows the voice in `how-its-built-plan.md`: warm, straightforward and measured, in British
English, without overclaiming.

| # | Gap as published | Plan |
|---|---|---|
| 1 | Mutation testing covers the server only | **Out of date.** Stryker now tests `frontend/src/utils` weekly (`frontend-mutation-testing-plan.md`). Phase 1 corrects `engineering.html`. |
| 2 | The real-browser tests run by hand | **Partly closed.** Phase 2 (merged, #1764): a Playwright smoke suite in CI, **a required check**. Phase 3a.1 (built, PR pending): seeded data, so the Map and Plan specs run too. The rest of Phase 3: roles, nginx, accessibility. |
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
   - **The browser Back button closes the top dialog** (amended by `back-closes-dialogs-plan.md`, PR 2a onwards: dialogs push a history entry through `utils/dialogHistory.js`). It is tested in `smoke/back.spec.js`, which waits for `<html data-back-depth>` to settle before every `goBack`; the first 3a.1 draft said it was not tested.
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

### 3a.1 build notes (2026-11-07)

Built as planned and amended. Where the build differed, and why:

- **`-Plocal-dev checkstyle:check` is vacuous.** The source roots are added by build-helper in `generate-test-sources`, which a bare goal never runs; it passed with a deliberate violation in `src/local`. CI and the docs use `./mvnw -Plocal-dev generate-test-sources checkstyle:check`. It also audits the local tests (four long lines were found in the first draft).
- **`--repeat-each` repeats only the projects it is asked for, and runs dependencies once.** The proof is therefore two invocations: `--project=smoke --repeat-each=10` and `--project=logout --repeat-each=10`.
- **The Map tab appears late** (only once the forecast has loaded), so every spec waits for it before reading the tab bar. `map-labels` is a zero-size pane host, so Playwright calls it hidden: specs wait for `[data-placed="true"]` with a count, not visibility.
- **Rising-tide is asserted on the Plan route** (Bamburgh Beach through the window sheet), because the Map opens at a zoom that culls that chip.
- **A peek-sheet row click on a phone keeps the sheet open**; the phone leg closes it with the same button.
- **The dead proxy is port 9** (nothing listens; the connection is refused at once). The Anthropic SDK's OkHttp client honours it (proved: with the flags the call fails `Connection refused`, without them it reaches Anthropic and gets a 401 for the fake key).
- **Added `sheet-helpers.js`** for the location-sheet assertions shared by both drill-down specs.

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

### 3a.2 build notes (2026-11-07)

Built as planned, with these differences:

- **The role users live in `E2eRoleUsers`**, a second local-only bean (same gating) that the seeder calls after the places are saved and again from its self-check, rather than more constructor arguments on a 750-line class. Its tests are `E2eRoleUsersTest` (run by the E2E job with the seeder's: `-Dtest='E2eLocalFixtureSeeder*,E2eRoleUsers*'`) and the seeder's own test, which pins when it is called and with what.
- **Sub-regions:** one per region, each holding that region's six places; `lite` picks Northumberland and Lake District (limit 3, the policy default), so Yorkshire Dales is gated. `lite` has picks and no grace stamp or change lock, so its standing is `OPEN` and the first-choice prompt never appears. The seeded `lite` and `pro` have `postcodePromptDismissedAt` set at insert, so `global-setup.js` writes only the Coming up "seen" mark for them.
- **Two spec files, not one.** `roles.spec.js` is read-only and runs in `smoke` in parallel (it uses `lite` and `pro`, so the shared-admin logout hazard does not apply); `roles-write.spec.js` (the Pro rating) is the serial `roles-write` project, after `smoke` and before `logout`, which now depends on both.
- **Test ids.** The Ask trigger's Pro lens is the `.wf-lens-pro` pill inside `ask-field[data-locked="true"]` (`ask-field-upsell`), and Ask's upsell popover is the bare `wf-ask-upsell`. The LITE doors asserted are `wf-heat-bestbets-upsell-*` (the Plan) and `wf-scores-upsell-*` (a location sheet inside the picks); the outside place has `wf-subregions-place-upsell-*`. "No `/api/ask*` request" means a pathname starting `/api/ask`: `/api/user/settings/ask` is 200 for every role by design and is not Ask. The LITE rating taps need `click({ force: true })` because the controls are `aria-disabled` (Playwright reads that as not enabled; the tap is accepted by design).
- **The smoke ports can be moved for a local run** (`E2E_API_PORT`, `E2E_WEB_PORT`; defaults 8083 and 4173, so CI is unchanged). A local run reuses whatever answers on the fixed ports, and a second checkout running the suite on the same machine made this phase's first `--repeat-each` attempt drive (and lose) the other's backend.
- **Chip choice for the callout's lower controls.** A Map callout opens beside its chip and cannot scroll, so the rating block's stars can fall below the fold for a chip low on the map (found by `--repeat-each`, one failure in about 280 runs). The rating specs use a 1280x1000 viewport and click the chip nearest the vertical middle (`clickMiddleChip`), and the LITE spec asserts the star is inside the viewport first, so a repeat prints the boxes.
- **Discovery found no hygiene finding and the allow-list is still empty.** The LITE upsell flows make no 403.
- **Two product defects found, neither pinned by a spec.** (1) Opening the Map after the Plan tab in one session sometimes mounts it at an extreme zoom with most chips culled (admin too; roughly half the runs of a two-tab spec), so the role specs open the Map first. (2) `PUT /api/outcome/ratings` on an EXISTING rating answers with the previous star (a stale re-read in `OutcomeRatingService.rate`, probably open-in-view), and the client commits that response over its optimistic value, so "How was it?" shows the old rating after a change until a reload. `roles-write.spec.js` asserts the block's value on a first rating only, and the stored row through `GET` on every rating, so it survives `--repeat-each`.

### 3b: the production nginx config in CI

- **No image build is needed.** The frontend image is plain `nginx:alpine` serving `dist`. Production mounts the repo-root `nginx.conf` at runtime (`docker-compose.yml`). CI does the same: `docker run nginx:alpine` with `frontend/dist` and `nginx.conf` mounted, `-p 127.0.0.1:4173:80`, and `--add-host=goldenhour-backend:host-gateway`. The backend then runs on 8082 in this mode, so the prod upstream name resolves with no edit to the config.
- **Switching modes:** `E2E_WEB=nginx` swaps `webServer[1]` for `scripts/e2e-smoke-nginx.sh`. CI sets it; local runs keep `vite preview`, since there's no Docker locally.
- **What it adds:** a real-browser test of the strict CSP (`style-src 'self'`), the security headers, the SPA fallback, the caching headers and the SSE proxying. A CSP block logs a console error, which the hygiene assertion already fails on.
- **To prove in the discovery run:**
  - `upgrade-insecure-requests` does not upgrade `http://127.0.0.1`. If it does, use a CI-only copy without that directive, and record why.
  - The SSE status stream works through the proxy.
  - Nothing in the app injects a `<style>` element.
- Same reliability proof as Phase 2.

#### 3b build notes (planned 2026-11-07, from `origin/main` `a78a1ea0`)

**What exists today.** `frontend/Dockerfile`'s serving stage is a floating `nginx:alpine` with `dist` copied in and no
config of its own; production mounts the repo-root `nginx.conf` over `/etc/nginx/nginx.conf`. CI already runs that
image twice in the backend job (`nginx -t` and `scripts/nginx-log-canary.sh`), so the runner has Docker and the image.
The status stream (`/api/status/stream`, `useHealthStatus`) is SSE, and **nothing sets `X-Accel-Buffering: no` and
`nginx.conf` leaves `proxy_buffering` on**, so whether events reach the browser promptly through the proxy is an open
question that this phase answers rather than assumes.

**The mode switch.** `E2E_WEB` selects the web server; unset (or `preview`) keeps `vite preview` exactly as today, so
a local run with no Docker is unchanged. `E2E_WEB=nginx` (CI sets it on the run step):
- swaps `webServer[1]` for `bash scripts/e2e-smoke-nginx.sh` (new, beside `e2e-smoke-backend.sh`);
- forces the API port to **8082**, because `nginx.conf`'s upstream is `goldenhour-backend:8082` and the config is
  mounted **unedited**. Any other `E2E_API_PORT` in nginx mode is a config error and fails fast with a message;
- keeps `baseURL` at `http://127.0.0.1:${WEB_PORT}` (4173), so every spec, `loginAs` and the hygiene origin are
  unchanged.

**`scripts/e2e-smoke-nginx.sh`.**
- `docker run --rm --name goldenhour-e2e-nginx -p 127.0.0.1:${WEB_PORT}:80
  --add-host=goldenhour-backend:host-gateway`, with `frontend/dist` mounted read-only on `/usr/share/nginx/html`
  and the repo-root `nginx.conf` read-only on `/etc/nginx/nginx.conf`. Image: `nginx:alpine`, the tag the
  shipped image uses (not the `1.27-alpine` that `nginx -t` pins).
- Removes any stale container of that name first; traps EXIT/INT/TERM to `docker rm -f` it, so a cancelled run never
  leaves a container holding the port. Container stdout/stderr (nginx's access and error logs in this image) go to
  `$E2E_LOG_DIR/nginx.log`, which the existing failure upload already collects.
- Fails with a clear message if `frontend/dist/index.html` is missing or the Docker daemon is not reachable.
- The backend must accept connections from the Docker bridge, not only loopback: Spring's default bind is all
  interfaces, and the backend script sets no `server.address`; the builder confirms this rather than assumes it.

**CORS and Host.** nginx forwards `Host $host` (no port), so the backend sees a cross-origin request from
`http://127.0.0.1:4173`; `--cors.extra-origins` already allows it. No backend flag changes.

**New spec, `smoke/nginx.spec.js`** (in the `smoke` project; every test `test.skip`s unless `E2E_WEB=nginx`, with the
reason in the skip message). It reads the expected values **from `nginx.conf` itself** where it can, so the spec cannot
drift from the file:
1. **Mode guard:** the document response for `/` carries `Content-Security-Policy` equal to the `$csp` string parsed
   from `nginx.conf`. This is what makes a silently-fallen-back `vite preview` run fail in CI.
2. **Security headers** on `/`, on a hashed `/assets/*.js` and on a deep link: the seven `add_header` names from the
   config, each present once.
3. **Caching:** `/` and a deep link `Cache-Control: no-cache`; `/assets/*` `public, immutable` with a far `Expires`.
4. **SPA fallback:** a deep link that is not a file (for example `/plan/anything`) answers 200 with the shell, and the
   app boots on it with clean hygiene.
5. **The status stream through the proxy:** a signed-in page receives its first `/api/status/stream` event within a
   stated bound (pick it from the discovery run, with headroom). If discovery shows nginx buffering it, **stop and
   report** with the measured delay: the fix (`proxy_buffering off` on that location, or the backend setting
   `X-Accel-Buffering: no`) is a production change the planner decides, not something to tune the test around.
6. **No inline `<style>` element** in the document after visiting each main tab and opening the window sheet (the
   strict `style-src 'self'` would block it, and the hygiene fixture fails on the console error either way; this
   names it).
- The **whole existing suite** also runs behind nginx, so every journey is now a CSP test: a blocked style or script
  logs a console error, which hygiene already fails on.

**Discovery, before writing assertions** (run with `E2E_HYGIENE=record`, report what it finds):
- Does `upgrade-insecure-requests` rewrite `http://127.0.0.1:4173` requests? Chromium treats loopback as potentially
  trustworthy and is expected not to. If it does, the fallback is a CI-only copy of the config with **only** that
  directive removed (`sed`), the reason recorded in the script and here, and spec 1 compares against the edited copy.
- Any CSP violation, 4xx or off-origin finding that `vite preview` hid. Each is either fixed in its own PR or put on
  `ALLOW_LIST` with a reason and a follow-up; nothing is silently tolerated.
- The SSE timing above.

**CI.** The smoke job's run step gets `E2E_WEB: nginx` and `E2E_API_PORT: 8082`; a `docker pull nginx:alpine` step
before it, so pull time is not charged to Playwright's 60 s server timeout. The backend-log assertion step and the
artifact upload are unchanged (the upload already takes `test-results/`). The job's `timeout-minutes` stays 20 unless
the proof shows it needs more.

**Local.** Default runs stay on `vite preview`. A machine with Docker can run `E2E_WEB=nginx npm run test:e2e:smoke`
(the cloud session's container can start `dockerd`; the owner's laptop has no Docker, and nothing requires it).

**Proof.** The temporary tip commit (5x job matrix, `--repeat-each=10` for each project, quoted step names), as 3a.1,
3a.2 and Back 2b used; reverted before merge, then `@codex review` on the new head.

**Docs in the same PR.** `reference/dev-and-testing.md` ("The E2E smoke gate": the two modes, the 8082 rule, the
script), the status line at the top of this file, and a `changelog.d/` entry.

**3b post-merge finding (2026-11-08).** In a 5x proof on another PR, 2 of 5 smoke jobs failed, each time on the FIRST
test after the nginx container started (`ask.spec.js`, then `auth.spec.js` at the start of a later repeat invocation):
hygiene caught `Failed to load resource: net::ERR_NETWORK_CHANGED` on `/registerSW.js` and `/assets/*.js`, then the login
form timed out at 30 s. Cause: Chromium's NetworkChangeNotifier watches the host's interfaces over netlink and aborts
in-flight requests on any change, and `docker run` on the default bridge attaches a new veth to `docker0` (carrier up,
IPv6 link-local, DAD), a moment AFTER the container already answers, so the change lands just after the browser launches.
Fix: the container runs on the **host network** (`--network host`, no `-p`), so no interface is created or changed. nginx
then listens on the host's port 80 (`listen 80` in the unedited config), so in nginx mode the web port is **80** and any
other `E2E_WEB_PORT` is refused; the backend's CORS origin is `http://127.0.0.1` (a browser omits the default port).
`--add-host=goldenhour-backend:127.0.0.1` is honoured with host networking. Do not switch back to `-p`.

**Amendments from the plan review (2026-11-07).** These override the notes above where they differ.
1. **Container lifecycle.** Playwright SIGKILLs a webServer that has no `gracefulShutdown`, so a trap never runs
   and the container outlives the run. The nginx entry gets `gracefulShutdown: { signal: 'SIGTERM', timeout:
   10_000 }`; the script runs `docker run … &` then `wait $!` (never `exec`, never foreground) with the trap doing
   `docker rm -f`. The entry passes `E2E_LOG_DIR` and `E2E_WEB_PORT` in `env`. In nginx mode `reuseExistingServer`
   is **false** even locally (a stale container would otherwise be reused silently), and the script's
   remove-stale-first step covers a container left by a hard kill.
2. **Header assertions.** Use `page.request.get(...)` and `headersArray()` (duplicate-aware; `headers()` omits some
   security headers). `/assets/` sends two `Cache-Control` headers (`expires 1y` adds `max-age=31536000`): assert
   it contains `public` and `immutable`, and that `Expires` exists. The seven names come from the `add_header …
   always` lines of `location /` only. A browser navigation is kept for the mode guard alone.
3. **The nomodule hash.** Chromium skips `<script nomodule>` before CSP, so a stale hash logs nothing. nginx.spec
   adds a Node-side check: the sha256 (base64) of the nomodule body in `dist/index.html` appears in the parsed `$csp`.
4. **Status-stream timing.** The first `status` event is sent on connect and later ones every 30 s, the same as
   the suite's 30 s test timeout. So if buffering holds the first event, the test simply times out and never
   reports how long the event took. Measure in the page with `fetch('/api/status/stream', { headers:
   { Authorization } })` and a `getReader()` loop until `event:status`. The discovery run uses
   `test.setTimeout(90_000)` and records the time. The final spec uses a fixed bound well under 30 s.
   `runProgressApi.js` streams through the same location, so a buffering finding covers it too.
5. **The mode guard's parse.** It must fail loudly: assert the `set $csp "…";` match is non-null. The script
   exports the path of the config it actually mounted (`E2E_NGINX_CONF`), and the spec reads that file, so the
   `sed` fallback copy, if one is ever needed, stays consistent.
6. **The proof** is `--repeat-each=10` (the count 3a.1, 3a.2 and Back 2b used); "Order and gates" below is
   updated to match.
7. **The image pull.** The `nginx:alpine` runs that already exist are in the backend job, on another runner, so
   the smoke job's own `docker pull` step is required, not optional.
8. **What the SPA fallback proves.** The app has no client router, so the fallback spec tests nginx's
   `try_files` and nothing more. Also assert that `/assets/missing.js` is a 404 that still carries the
   security headers, since that location has no `try_files`.
9. **Not covered:** `worker-src`. The suite blocks service workers, so do not list `worker-src` as covered.

### 3c: the accessibility scan (advisory)

- **The job:** a separate job, `E2E — accessibility (advisory)`, on the 3b stack (seeded, nginx), and **not** a required check. `@axe-core/playwright` scans each main tab and the open dialogs (window sheet, location sheet, callout) at desktop and phone widths.
- **What fails it:** serious and critical violations fail the job. Minor and moderate ones are reported only.
- **The report:** an HTML or JSON report is uploaded every run.
- **Fixing:** existing violations are fixed in their own PRs, **never** suppressed by disabling rules. The owner makes the check required once it's clean.
- **Landing copy is unchanged** until then, and even after it the copy never claims full accessibility.

#### 3c build notes (planned 2026-11-07, stacked on the 3b commit `c8b4e4e4`)

**Branching.** 3c needs 3b's nginx mode, so it is built on `test/e2e-a11y` from the 3b commit (never from 3b's
temporary proof commit). Its PR opens once 3b has merged, with `main` merged in first.

**The dependency.** `@axe-core/playwright` as a devDependency, **pinned exactly** (no caret, as `@playwright/test`
is), at a release at least two weeks old. `npm audit --audit-level=high` must stay clean.

**Config, with no change to the smoke gate.** `frontend/playwright.a11y.config.js` imports the smoke config and
overrides only `testDir` (`./src/test/e2e/a11y`), `outputDir`, the reporter and `projects`. It keeps the same
`webServer` list, `globalSetup`, workers, `retries: 0` and the service-worker block, so the 3b stack and its
`E2E_WEB=nginx` switch are reused as they are. `playwright.smoke.config.js` itself is not edited, and the smoke
`testDir` never sees the a11y specs. A script, `test:e2e:a11y`, is added beside `test:e2e:smoke`.

**Projects.** `a11y-desktop` (Desktop Chrome, 1280×800) and `a11y-phone` (Pixel 7, which is Chromium and so needs no
second browser). Both run read-only as the admin account, so `logout` and `roles-write` hazards do not apply.

**What is scanned** (each as its own test, so one failure never hides another):
- the sign-in page (signed out);
- Map, Plan (*At a Glance*), Coming up and Operations, once each has settled (reuse the smoke helpers
  `loginAndWaitForForecast`, `openTab` and `openMapTab`);
- the open dialogs: the window sheet (Plan card), the location sheet, and the Map callout. On the phone, the
  callout is the peek sheet.

The specs import `test` from `smoke/fixture.js`, so the tile and Turnstile stubs and the hygiene assertion apply
here too.

**The scan.** `new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])`, scoped to the
whole page. For a dialog, the whole page is scanned with the dialog open: axe skips inert or hidden content
anyway. **No `disableRules` and no `exclude`**, ever, which follows from the plan's "never suppressed" rule.
Each test:
- attaches its full axe JSON (`testInfo.attach`);
- writes a one-line-per-violation record (scan name, rule id, impact, node count, help URL) to
  `test-results/a11y/<project>-<scan>.json`;
- fails only if any violation has impact `serious` or `critical`. The failure message lists those rules and
  targets.

**The report.** A small Node script, `scripts/a11y-summary.mjs`, folds the per-scan files into a markdown table
(rule, impact, scans affected, total nodes). It is appended to `$GITHUB_STEP_SUMMARY` and saved as
`test-results/a11y/summary.md`. It runs with `if: always()`, and the whole `playwright-report/` and
`test-results/` directories are uploaded with `if: always()`, which gives "uploaded every run".

**The CI job.** `E2E — accessibility (advisory)`:
- It runs on ubuntu-24.04 with the same step-level `docs_only` guards and closing "skipped" echo step as the smoke
  job.
- It uses the same build steps: JDK, Node, `npm ci`, the local-dev jar, `npm run build`, the cached Chromium plus
  deps, and `docker pull nginx:alpine`.
- It then runs `npm run test:e2e:a11y` with `E2E_WEB: nginx` and `E2E_API_PORT: '8082'`.
- It skips the seeder unit tests and the checkstyle steps, which the smoke job already covers.
- It is **not** added to branch protection.

**How "advisory" sits with the merge rule.** CLAUDE.md merges on "CI green". Until the owner makes this job
required, the merge decision reads the **required** checks plus Codex, and a red advisory job is reported to the
owner in the PR summary but never blocks the merge. This exception covers this one job, by name, and the
reference doc records it. Once it is clean and required, it is an ordinary required check.

**Discovery first.** Run the scan locally in nginx mode, then report every violation by rule, impact and count
before CI is wired. Serious or critical findings are expected (colour contrast on a dark map UI is the usual one).
They do **not** block this PR: the job lands red, and each rule gets its own fix PR afterwards, planned from the
discovery list.

**Docs in the same PR.**
- `reference/dev-and-testing.md`: the a11y gate, how to run it, and the advisory merge exception.
- This file's status line.
- A `changelog.d/` entry.

### Order and gates

- **Order:** each PR follows the Phase 2 recipe. The planner reviews it, Sonnet builds it in a worktree, and the full local gates must pass:
  - backend: checkstyle and `clean verify`, with the seeder's own unit tests;
  - frontend: the four commands.
- **Proof for 3a.1 and 3b:** the temporary 5× job matrix plus `--repeat-each=10`, removed before merge.
- **Copy:** once 3a.1 and 3a.2 are merged, the landing pages drop "the map and planning screens are still checked by hand".

## Phase 4: post-deploy smoke and automatic rollback

- Read `deploy.yml` and the rollback procedure in `reference/platform.md` before planning.
- **The idea:** straight after deploy, probe health and a few endpoints that need no login, plus one that
  does, using a smoke account. If any probe fails, redeploy the previous image tags automatically and open
  an issue.
- **No new infrastructure.** This narrows the "one host" gap but doesn't close it.

### Phase 4 build notes (planned 2026-11-08, from `origin/main` `92e928f1`)

**What exists today.**
- **The deploy job** (`deploy.yml`, run over SSH on the host) does these steps in order: the rollback-pin guard, materialise
  `nginx.conf` and `docker-compose.yml` from the tag, the PGDATA guard, `pre-release-backup.sh <version>` (a database
  dump plus a manifest of the **outgoing image digests**, written before the pull moves `:latest`), `pull`, `up`,
  `prod-health.sh --deploy` (compose health, cloudflared, Tailscale, a real 401 from outside the tunnel), and finally
  `record-deployed-images.sh`.
- **When it fails**, `notify-failure` opens an issue.
- **Nothing currently checks that the release behaves.** `prod-health.sh` says so itself: "says nothing about whether
  the release behaves correctly, only that it is up".
- **Nothing rolls back on its own.** `scripts/rollback.sh <version>` is a manual Plan B: it **restores the database**
  (which discards everything written since), then pins the manifest's digests in `docker-compose.override.yml`. The
  deploy job refuses to run while that pin exists.

**Owner decisions this plan assumes (confirm or change before the build):**
1. **The automatic rollback is IMAGE-ONLY and never restores the database.**
   - It pins the manifest's previous digests, the same mechanism `rollback.sh` step 4 uses, and leaves the data alone.
   - Restoring the database stays a human decision, through `rollback.sh`.
   - Why this is safe enough:
     - production runs Flyway with `validate-on-migrate: false`, so an older image starts against a newer schema;
     - Hibernate `validate` only fails if a release dropped or renamed something the old code reads, and that case
       shows up as a failed health check after the rollback, below.
2. **A dedicated production smoke account**, `smoke-deploy`, as a LITE reader. You create it in Operations and put
   its password in the repository secret `SMOKE_DEPLOY_PASSWORD`.
   - Until the secret exists, the logged-in probe is **skipped with a warning**, never failed, so merging this phase
     can't trigger a rollback on the next release.

**The new step: `scripts/post-deploy-smoke.sh`**, run on the host right after `prod-health.sh --deploy`. Every probe
goes through the public URL (`https://app.photocast.online`, overridable as `APP_URL_BASE`), so it covers Cloudflare,
the tunnel, nginx and the backend together.
1. **The shell:** `GET /` answers 200 and HTML, carries a `Content-Security-Policy` header, and contains a
   hashed asset (`/assets/index-*.js`) that itself answers 200 with `Cache-Control` containing `immutable`. This
   catches a broken frontend image or nginx config.
2. **Backend health:** `GET /actuator/health/container` answers 200 with `"status":"UP"`.
3. **Auth boundary:** `GET /api/briefing` with no token answers 401. `prod-health.sh` already checks this; it is
   repeated here so this script stands on its own.
4. **Logged in** (only when `SMOKE_DEPLOY_PASSWORD` is set):
   - `POST /api/auth/login` with no Turnstile token. `TurnstileGate`'s fallback admits it: 5 per account and 20 per
     address per 15 minutes, 60 per hour globally.
   - `GET /api/briefing` with the token answers 200, and its JSON has the briefing's top-level shape. Check keys
     only, never values: the forecast can legitimately be thin.
   - `GET /api/user/settings` answers 200.
   - Then `POST /api/auth/logout`, so no refresh tokens pile up.
5. **Behaviour:**
   - Each probe retries for up to about 60 s with a 5 s interval, because a fresh backend warms up.
   - It prints one `ok` or `FAIL` line per probe, plus `::error::` annotations under Actions, as `prod-health.sh` does.
   - It never prints the token or the password.
   - It exits non-zero on any failure.
   - It needs only `curl` and `jq` (what `prod-health.sh` already needs). If `jq` might be missing on the host, check
     first.

**The rollback: `scripts/auto-rollback.sh <version>`.** Deploy runs it only when the smoke check or `prod-health.sh
--deploy` fails after `up`.
- It reads `${BACKUP_DIR}/pre-release/<version>.json` through the same field reader as `rollback.sh`. Share it
  through a small sourced helper, `scripts/lib/release-manifest.sh`, rather than copying it.
  - If the digests are missing, it does nothing and says so loudly. A manifest pinning nothing is worse than leaving
    the failed release up for a human.
- It writes `docker-compose.override.yml` pinning the two previous digests, the same file and format `rollback.sh`
  writes, so the existing deploy guard and the "remove it to roll forward" instruction apply unchanged. Then it runs
  `docker compose up -d --no-deps goldenhour-backend goldenhour-frontend`.
- It re-runs `prod-health.sh --deploy`, then the smoke script, against the rolled-back build.
  - **If both pass**, it prints a ROLLED BACK banner (the version, the digests now running, "database NOT restored",
    and how to roll forward), then exits **1**, so the deploy job still fails and `notify-failure` opens the issue. A
    rolled-back release is a failed release.
  - **If either fails**, the previous build won't come up either; the usual cause is a migration the old code can't
    read. It prints a loud "MANUAL ACTION NEEDED" banner that points at `rollback.sh <version>` (the full restore,
    database included). It leaves the pin in place, so the next deploy can't silently go out, and exits **2**.
- It never runs `pg_restore` or `psql` and never touches the database. A test pins this.
- It takes `--dry-run`, which prints the plan and runs nothing.

**Wiring in `deploy.yml`.**
- The SSH script runs `prod-health.sh --deploy`, then the smoke script, under a small function. On failure, and only
  for steps after `up`, it runs `auto-rollback.sh "${RELEASE_VERSION}"` and exits non-zero.
- The steps before `up` keep failing exactly as they do today, with nothing changed and nothing to roll back.
- `record-deployed-images.sh` runs only on success, as now: a rolled-back release must not be recorded as running.
- The smoke password reaches the host the same way `RELEASE_VERSION` does: listed in `envs:` from
  `secrets.SMOKE_DEPLOY_PASSWORD`. It is never echoed.
- `notify-failure` is unchanged. Optionally, its issue title says "rolled back" when the job's output contains the
  banner; nice to have, not required.

**Tests (CI, no production access).**
- **The smoke script against the seeded e2e stack.** A new step in the `E2E — Playwright smoke` job runs, after the
  suite:

  ```
  APP_URL_BASE=http://127.0.0.1 SMOKE_DEPLOY_USERNAME=lite SMOKE_DEPLOY_PASSWORD=<the fixture's> \
    scripts/post-deploy-smoke.sh
  ```

  That stack is nginx on the host network on port 80 plus the seeded backend, so it is close to production. Run it
  while the servers are still up, e.g. as a Playwright globalTeardown hook or a small spec that shells out; choose the
  simplest reliable way. Show it fails when it should:
  - a stopped backend;
  - a wrong password;
  - a missing CSP header, using an edited config copy, as `nginx.spec` does.
- **`auto-rollback.sh` with a stub `docker`** on `PATH` and a fixture manifest, in a new `scripts/test/` shell test.
  Run it from the ShellCheck job, or a new small job. Cover:
  - a missing manifest, so nothing happens;
  - a manifest with no digests, so nothing happens;
  - the happy path: the override is written with the exact digests, and `up` is called with `--no-deps` on exactly
    those two services;
  - a failing post-rollback health check, so it exits 2 and the pin stays;
  - **no database command is ever issued**, asserted on the stub's call log;
  - `--dry-run` calls nothing.
- ShellCheck on both scripts and the helper.

**Docs.**
- `reference/platform.md`, in the deployment paragraph and the rollback notes: the smoke step, the image-only
  automatic rollback, what it never does, and the owner's smoke-account setup.
- This plan's status line.
- A `changelog.d/` entry.

**Owner actions after merge.** Create the `smoke-deploy` LITE account in production, and add the
`SMOKE_DEPLOY_PASSWORD` secret. Until then, the logged-in probe is skipped with a warning.

**Not in this phase.**
- A staging host (still the owner's decision).
- Smoke-testing Ask, aurora or any write path.
- A rollback that restores data.

#### Phase 4: owner decisions and plan-review amendments (2026-11-08)

**Owner decisions:**
- **The automatic rollback is image-only.** It never restores the database.
- **The smoke account is a new PRO account, `smoke-deploy`**, not LITE. The briefing then carries real scores, and the
  probe asserts that at least one served rating is present, which proves the PRO shaping.

Two lenses reviewed the plan: deploy and rollback mechanics, and the probes and their tests. The amendments below
override the build notes above where they differ.

**Getting started safely**
- **R1. Report-only first.** A repository variable `SMOKE_ENFORCE` gates the whole feature.
  - **Unset** (the default after merge): the smoke check runs and reports. A failure fails the deploy job, which
    opens the issue, but `auto-rollback.sh` is **not** run.
  - **Set to `true`:** a failure triggers the rollback.
  - The owner turns it on after a few releases have run clean through production's Cloudflare. None of these probes
    has ever passed through it.
- **R2. Cloudflare-aware probes.**
  - Send a distinct `User-Agent: photocast-deploy-smoke/1`.
  - Add a cache-buster query to `GET /`.
  - A 403 carrying `cf-mitigated: challenge`, or any Cloudflare challenge page, is **INCONCLUSIVE**: exit 3 and never
    roll back.
  - The asset caching check accepts `immutable` **or** `max-age` of at least a day, because Cloudflare may rewrite
    `Cache-Control`. Record which one it saw.

**Classifying failures (exit codes; only exit 1 can roll back, and only when enforced)**
- **0:** pass.
- **1:** release broken.
- **3:** inconclusive or infrastructure. This covers a Cloudflare challenge, cloudflared, Tailscale, the database
  container unhealthy, or a network error before any response.
- **4:** smoke misconfigured. This covers a login 401, 403, 429, or `passwordChangeRequired: true` in the login body.
  - **Never retry the login on 401, 403 or 429.** Retry only on 5xx or a connection error.
- Read `prod-health.sh --deploy`'s output per check, or split it with a flag, so that only the backend or frontend
  container checks count as "release". Tunnel, Tailscale and database failures count as infrastructure (3).
  - The builder chooses the least invasive way to get that signal out of `prod-health.sh`, for example a
    `--json`/`--checks` option. If it needs a change to `prod-health.sh`, keep `--daily` behaviour identical.

**Deploy-job mechanics**
- **R3. Scripts on the host.** Deploy copies scripts out of the tag one file at a time into `/tmp`, so a sourced
  helper would not be there.
  - Extract the needed `scripts/` files from the tag into one temporary directory with the layout intact
    (`git archive "$RELEASE_VERSION" scripts | tar -x -C "$tmp"`), and run them from there.
  - `auto-rollback.sh` sources its helper via `$(dirname "$0")`.
  - `rollback.sh` keeps its own copy of the field reader, and a CI test asserts the two readers are identical. Do not
    make the manual script depend on the host's working copy.
- **R4. `set -e` in functions.** Each step inside the check function is written `… || return N`. The caller captures
  the code (`rc=0; check || rc=$?`), then branches on it. The rollback's 1 or 2 is passed through with
  `exit "$rc"`.
- **R5. The time limit.**
  - Set `command_timeout: 30m` on the SSH action.
  - Give the smoke script an overall deadline: `SMOKE_DEADLINE_SECONDS`, default 120.
  - Give `auto-rollback.sh` one overall deadline too, and print the banner even when it is reached.
- **R6. Configuration rolls back with the images.**
  - `pre-release-backup.sh` also saves the **outgoing** `nginx.conf` and `docker-compose.yml` beside the manifest,
    read from the host's current files before deploy overwrites them. So the deploy job must run the backup **before**
    it materialises the new files; reorder the steps and check the PGDATA guard still reads the right compose file.
  - `auto-rollback.sh` restores both files before `up`.
  - If either saved file is missing (manifests written before this change), roll back the images only and say so in
    the banner.
- **R7. Never overwrite a rollback point.** `pre-release-backup.sh` refuses to overwrite an existing manifest or dump
  for the same version. On a re-run of the same tag it keeps the first and logs that it did. Check this doesn't abort
  a legitimate re-run: keep the original rollback point and carry on.
- **R8. One deploy at a time.** Add `concurrency: { group: prod-deploy, cancel-in-progress: false }` to `deploy.yml`.
- **R9. If the previous build won't start**, `auto-rollback.sh` handles it in one of two ways:
  - **Only the smoke failed** (`prod-health` passed): remove the override, restore the new release's config, bring the
    new release back up, then exit 2 with "rollback failed, the release is back up as it was".
  - **Otherwise:** leave the pin and exit 2 with MANUAL ACTION NEEDED.

  Recommend `rollback.sh` (the database restore) **only** when the old image fails its own in-container health check
  after a schema change.
- **R10. Schema warning.** The banner shows the schema version captured before the release (it is in the manifest)
  against the current one. When they differ, it says loudly that the new release applied migrations, that the old
  code may fail on writes even though it reads fine, and that the database was not restored.
- **R11. The pin is named.** The override file's header comment names the script and the version that wrote it. The
  deploy guard's error message says which one it was. The ROLLED BACK banner warns that removing the pin without a
  new deploy lets a bare `docker compose up` bring back `:latest`, which is the bad release.
- **R12. A missing manifest or missing digests exits 2**, with a clear message.

**Probes**
- **R13. Account setup (owner).**
  - Create `smoke-deploy` as PRO, with **no email address**, so it is never swept as dormant and never mailed.
  - **Sign in once in a browser and change the temporary password**, because admin-created accounts must change
    their password at first login, and until then every API call answers 403.
  - Then put the new password in the `SMOKE_DEPLOY_PASSWORD` secret.
  - The script detects `passwordChangeRequired` and exits 4, naming the cause.
- **R14. 204 is a FAIL.** `/api/briefing` answering 204 means the cached briefing is missing or failed to
  deserialise, which is a real class of broken release.
- **R15. More probes:**
  - **The new build is the one running.** `/actuator/info` `git.commit.id` must equal
    `git rev-list -n1 "$RELEASE_VERSION"`. This probe is skipped in rollback mode, and a missing field gives a
    warning, not a failure.
  - **Assets:** **every** `/assets/*.js` and `*.css` that `index.html` references answers 200.
  - **Rollback mode** (the old image): health falls back to `/actuator/health` when `/container` returns 404. The
    briefing check uses only keys that are stable across versions.
- **R16. Secrets.**
  - The login body is built with `jq --arg` and sent on stdin (`--data @-`).
  - The bearer header goes through a mode-600 temporary file or `-H @file`, never in argv.
  - No `set -x`, and no appleboy `debug`.
  - Log out from an EXIT trap.
- **R17. A visible skip.** When the password is unset:
  - print a `::warning::` line and a line in `$GITHUB_STEP_SUMMARY`;
  - if the repository variable `SMOKE_LOGIN_REQUIRED=true` is set, the skip becomes exit 4;
  - the daily `prod-health` workflow warns while the variable is unset.

  Wire the secret in both `env:` and `envs:`, and add a CI check (grep `deploy.yml`) that both mention it.

**Tests**
- **R18. A fourth Playwright project, `deploy-smoke`**, with `dependencies: ['smoke','roles-write','logout']`. It
  runs `scripts/post-deploy-smoke.sh` through `spawnSync`, after the suite and with the servers still up, and asserts
  the exit code. It needs a test timeout above 30 s and `SMOKE_RETRY_SECONDS` / `SMOKE_DEADLINE_SECONDS` set small.
  - **Positive:** the seeded `pro` account against nginx on port 80. That proves the full path, including PRO
    ratings present.
  - **Negative, through a tiny Node stub server** on a free port (`APP_URL_BASE`):
    - a missing CSP gives 1;
    - a 500 from health gives 1;
    - a 204 briefing gives 1;
    - a Cloudflare challenge gives 3;
    - a login with `passwordChangeRequired` gives 4;
    - a login 429 gives 4, with exactly one login attempt made.
  - **Negative against the real stack:** a wrong password gives 4.
  - Note in the plan that CI runs with Turnstile unset, so the login goes through the `OK_VERIFIED` path, not the
    production fallback. `TurnstileGateTest` covers the fallback.
- **R19. The `auto-rollback.sh` shell tests** gain:
  - restoring the config files;
  - the R9 restore-forward path;
  - the schema-mismatch banner;
  - exit 2 on a missing manifest or digests;
  - a command log proving `pg_restore`, `psql` and `pg_dump` are never run.
- **R20. The `pre-release-backup.sh` change gets a test:** no overwrite, and saving the config files.

**Docs.**
- The deploy and rollback notes live in `docs/engineering/reference/platform.md` line 18 (Deployment), and
  `scripts/rollback.sh`'s header. The builder finds the right place for a short "Automatic rollback" subsection,
  likely `platform.md` under Deployment, and updates `rollback.sh`'s header to mention the image-only path.

#### Phase 4: owner actions (after merge)

Nothing here can be done by a session; production is never reachable from one. Until step 4 the feature only
**reports**: a failing check fails the deploy job and opens the issue, and nothing is rolled back.

1. **Create `smoke-deploy` in Operations as a PRO account with no email address** (so it is never swept as dormant and
   never mailed). Use a password of **only letters, digits, `.`, `_` and `-`**: the SSH action's `envs:` forwarding turns
   the secret into a shell export, so anything else can arrive altered. The smoke refuses any other character (exit
   `4`, "contains characters the SSH forwarding can mangle") and the deploy's first step warns, never printing it.
2. **Sign in once in a browser as `smoke-deploy` and change the temporary password.** Admin-created accounts must change
   it at first login, and until then every API call answers 403 (the script reports exit `4`, naming the cause).
3. **Set the repository secret `SMOKE_DEPLOY_PASSWORD`** to the new password. Until it exists the logged-in probes are
   skipped with a warning on every deploy (and the daily `prod-health` run warns too).
4. **Later, set the repository variable `SMOKE_LOGIN_REQUIRED=true`**, once the account works, so a lost secret fails the
   smoke (exit `4`) instead of skipping the login probes.
5. **After a few releases have run clean through production's Cloudflare, set the repository variable
   `SMOKE_ENFORCE=true`.** From then on a release the checks call broken (exit `1`, and only exit `1`) is rolled back to
   the previous images automatically. Leave it unset to keep reporting only.

Build notes (2026-11-08), where the build chose or departed from the text above:
- **The login body and the `Authorization` header never reach argv.** The body is built by `jq` from the environment
  (`$ENV.…`, not `--arg`, which would put the password in `jq`'s argv) into a mode-600 file deleted right after the
  request; the bearer header goes through `curl -H @file`.
- **`deploy-smoke` spawns the script asynchronously, not with `spawnSync`**, because its negative cases serve a stub
  site from the same process and a blocking call would stop it answering. The "wrong password" case uses an account
  that does not exist, so no real account's failed-sign-in counter (10 in 15 minutes) moves under `--repeat-each`.
- **R9, refined.** If the rolled-back build is healthy but the smoke on it is *inconclusive* (`3` or `4`), the rollback
  stands (exit `1`, flagged `INCONCLUSIVE`) rather than flipping back to a release already known to be broken. Only a
  smoke `1` on the old build (it fails the probes too, so the rollback bought nothing) restores the new release and
  exits `2`.
- **R10 reads the tag, not the database.** The rollback may never run `psql`, so "the current schema" is the newest
  `V…` migration in the tag (`git ls-tree`), compared with the manifest's `schema_version`.
- **R15's commit probe will warn until the backend serves it.** The production images carry no `git.properties`
  (`backend/Dockerfile`: `GitInfoService` falls back to environment variables), so `/actuator/info` has no
  `git.commit.id` and the probe skips with a warning. A small `InfoContributor` publishing `GIT_COMMIT_SHA` would
  switch it on; that is a backend change and not part of this phase.
- **Probe ordering when they disagree (revised after review): `1`, then `4`, then `3`.** A real break is never hidden
  behind a misconfiguration or an edge answer elsewhere. Every Cloudflare edge response is `3` (challenge, WAF page,
  1015), a login that 5xx'd and is then refused is `1`, and login is tried at most 3 times.
- **`SMOKE_PASSWORD_CHARSET_CHECK=off` is test-only.** The `deploy-smoke` Playwright project sets it because the seeded
  `pro` password contains a `#` and that project runs the script directly, with no SSH forwarding. Never set it in
  deploy. The `deploy-smoke` cases that need the production nginx config (the seeded stack) skip outside
  `E2E_WEB=nginx`; the stub-site cases run in both modes.
- **Zero served ratings is a warning, not a failure** (revised after review): a stand-down or stability skip can null
  every rating. The failing signals are no days, no slot with a `locationName` (regioned or unregioned) and any `gated`
  marker.
- **The health check no longer ends the run on infrastructure.** `prod-health.sh` returning `3` still lets the smoke run
  (an nginx regression answering 404/502 for `/api` looks like infrastructure to the health check); the results combine
  by the same precedence. Caveat: a database outage that takes the backend with it can make the smoke report `1`, and
  with `SMOKE_ENFORCE=true` that rolls back; the images were not at fault, and the rollback never touches data.

## Phase 5: prompt regression on prompt-changing PRs

- **Trigger:** a `pull_request` job with a path filter on the prompt and evaluation code (the exact paths are
  to be listed at planning time).
- **Approval:** the job runs in a GitHub **environment** with the owner as required reviewer, so no run
  spends money without a click. The `ANTHROPIC_API_KEY` secret lives only in that environment.
- **Not required, and never automatic on other PRs.** The assertions in `src/test/java/.../regression/` are
  never changed by a session.
- **Owner setup:** create the environment and its secret (the list is under "Owner setup (PR 5b)" below).

### Phase 5 build notes (planned 2026-11-08, from `origin/main` `e6229496`)

**What exists today.**
- **Thirteen classes are tagged `prompt-regression`.** Surefire excludes them by default; `-Pprompt-regression`
  runs them. They need only `ANTHROPIC_API_KEY`: no database, no Docker, no Spring context. They use no batch API.
- **Only `real-api-smoke.yml` uses the key in CI**, on a daily cron. No workflow uses a GitHub `environment:` yet.
- **Two sky classes share one fixture registry**, `eval/SkyRatingEvalFixtures.ALL`, holding 10 observed days. Each
  fixture's rating band is identical in both classes.

| | `PromptRegressionTest` | `SkyRatingEvalTest` |
|---|---|---|
| Fixtures | The 8 that carry `RegressionBounds`, plus a hard-coded Angel of the North case | All 10 |
| Runs per fixture | 1 | 8 (pass^k) |
| What it asserts | Rating band plus fiery and golden bounds | Rating band only |
| Monitored (non-gated) fixtures | Hard-asserted | Reported only |
| Default model | Haiku | Sonnet 4.6 |

**Owner decisions (2026-11-08):**
- **Unify the two sky classes into one.**
  - Each run asserts **the same things in both modes**: the rating band, plus the fixture's fiery and golden bounds
    where it has `RegressionBounds`.
  - **Quick mode** (`k=1`, the PR default): all **10** registry fixtures, every assertion hard.
  - **Eval mode** (`k=8`): strict pass^k on gated fixtures. A run counts as a pass only when every check passes.
    Monitored fixtures are reported, never failed.
- **Retire Angel of the North.** Its input shape (observer-only, no directional cloud) is no longer produced.
- **CI runs the sky classes on Sonnet 5.5**, the production near-term model.
- The bounds and bands themselves are **unchanged**. This is a restructure the owner asked for, with the semantics
  above. No assertion value is edited, and no session changes one.

**PR 5a: one sky class.**
- **The class.** `eval/SkyRatingRegressionTest` replaces both `service/evaluation/PromptRegressionTest` and
  `eval/SkyRatingEvalTest`, and is tagged `prompt-regression`.
- **Runs per fixture.** `sky.runs` / `SKY_RUNS` sets it, default **1**. The full eval is `-Dsky.runs=8`. `MIN_PASSES`
  stays equal to the run count (strict).
- **Model.** `sky.model` / `SKY_MODEL`, default **`SONNET_55`**. The old `regression.model`/`REGRESSION_MODEL` and
  `eval.model`/`EVAL_MODEL` are still read as aliases, so nothing anyone runs today breaks. Document the precedence.
- **One fixture.** Keep `-Deval.fixture=<substring>` (with a `sky.fixture` alias) to run a single fixture.
- **One run** of one fixture:
  1. `strategy.evaluate(data)`;
  2. assert the scores are not null;
  3. check the rating against `fixture.band()`;
  4. if `fixture.regression()` is present, check `bounds.rating()`, which today always equals the band (the class
     asserts that equality once, as a guard), plus `fieryMin`/`fieryMax` and `goldenMin`/`goldenMax`.

  The run passes only if every check holds. Record the result per run, with direction where it applies (rating
  above or below the band; fiery or golden above or below its bounds), through `PassRateReport`, extended if it is
  needed to count bound misses separately.
- **Verdict.**
  - **k=1:** every fixture must pass (hard), monitored included, which is today's regression behaviour.
  - **k>1:** gated fixtures need `passes >= MIN_PASSES`. Monitored fixtures print `[MONITORED]` and never fail,
    which is today's eval behaviour.
  - The report always prints before the assertion.
- **What moves with it:**
  - **`RegressionBoundsRegistryTest`** (untagged, offline) uses `PromptRegressionTest.regressionFixtures()`. Point it
    at the new class. Its literal bounds stay byte-identical.
  - **The pom's PIT `excludedTestClasses`** and **`PitExclusionDriftTest`**: replace the two names with the new one.
  - **Javadoc and comment references.** `SkyRatingEvalFixtures`, `SkyRatingEvalFixture`, `RegressionBounds`,
    `SkyRatingEvalService` and `SkyRatingEvalController` (the in-app weekly eval, which stays as it is), plus
    `BriefingBestBetAdvisor`, `ValleyMist`, `BestBet` and the Ask classes, refer to the old names. Update the
    references. Do **not** touch the historical "ported from PromptRegressionTest#…" fixture descriptions; they are
    provenance.
  - **Docs.** `reference/dev-and-testing.md` (the run command and the registry note), `backend/AGENTS.md`, and live
    plans that give the run command: `far-cloud-lid-plan.md` and `sky-user-message-trim-plan.md`. Update the
    commands. Leave historical plan narrative alone.
- **Proof, offline (no API).**
  - A unit test drives the class's per-run judgement with a fake strategy returning scripted `SunsetEvaluation`s:
    - in band with bounds held → pass;
    - rating out of band → fail with the direction;
    - fiery below `fieryMin` → fail;
    - golden above `goldenMax` → fail;
    - k=1 with a monitored fixture out of band → fail;
    - k=8 with a monitored fixture at 7/8 → pass with `[MONITORED]`;
    - k=8 with a gated fixture at 7/8 → fail.

    Extract the judgement into a small package-private helper so it can be tested without the API.
  - Mutation-prove those tests.
  - Discovery: `regressionFixtures` and its replacement return all 10, and the old classes are gone.
- **No real API call in the build.** The owner may run `-Pprompt-regression -Dtest=SkyRatingRegressionTest` once
  locally to confirm, but that is not required.

**PR 5b: the gated workflow (`.github/workflows/prompt-regression.yml`).**
- **Triggers:** `pull_request` (`opened`, `synchronize`, `reopened`, `ready_for_review`) and `workflow_dispatch`.
- **A `changes` job.** No secrets, always runs. Same-repository PRs only:
  `github.event.pull_request.head.repo.full_name == github.repository`. It computes two outputs from the PR's changed
  files:
  - `sky`:
    - `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/**`
    - `…/model/**`
    - `…/eval/**`
    - `backend/src/main/resources/eval/**`
    - `…/service/{ValleyMistCalculator,CoastalMistCalculator,InversionScoreCalculator,DirectionalSamplingGeometry,WoodlandVerdictEvaluator}.java`
    - the sky test classes and fixtures;
  - `ask`:
    - `…/service/ask/**`
    - `…/service/DriveTimeResolver.java`
    - `backend/src/test/java/**/service/ask/**`
    - `backend/src/test/resources/prompt-regression/**`;
  - **shared, which sets both:**
    - `…/service/evaluation/{AnthropicApiClient,ModelRequestSupport}.java`
    - `…/entity/EvaluationModel.java`
    - `…/service/ModelSelectionService.java`
    - `…/config/AppConfig.java`
    - `backend/pom.xml`, **only if** the diff touches `anthropic-sdk.version` or the `prompt-regression` profile. A
      plain grep of the pom diff is fine.

  `workflow_dispatch` takes these inputs instead: `suite` (`sky`, `ask` or `both`) and `sky_runs` (`1` or `8`).
- **Jobs `sky` and `ask`.** Each runs only when its output is true, with `environment: prompt-regression`. **The owner
  is the required reviewer, so nothing spends money without a click.** `ANTHROPIC_API_KEY` is an environment
  secret, never a repository secret for this workflow. Each job:
  - fails fast if the key is empty. The Ask classes otherwise skip silently;
  - **sky:**

    ```
    ./mvnw -B test -Pprompt-regression \
      -Dtest='SkyRatingRegressionTest,ValleyMistPromptRegressionTest,BestBetAuroraPromptRegressionTest,SystemPromptTokenCountTest' \
      -DfailIfNoSpecifiedTests=true
    ```

    with `SKY_RUNS` from the input (default 1), and `SKY_MODEL`/`REGRESSION_MODEL=SONNET_55`.
    `ValleyMistPromptRegressionTest`'s javadoc says its assertions await the owner's approval: run it, but with its
    result reported and not failing the job (a separate step with `continue-on-error: true`), until the owner
    approves those assertions;
  - **ask:** `-Dtest='Ask*RegressionTest'`;
  - **never** runs `PromptReplayHarnessTest`, which throws without its config;
  - sets `timeout-minutes`: sky 30 (eval mode 60), ask 75;
  - uses `concurrency: prompt-regression-${{ github.ref }}-<job>` with `cancel-in-progress: true`, so a stale run is
    not paid for;
  - writes a summary to `$GITHUB_STEP_SUMMARY`, one row per class (passed, failed, skipped), parsed from the surefire
    XML;
  - uploads the surefire reports as an artifact on every run (`if: always()`).
- **Never required.** It is not added to branch protection. It is excluded from CLAUDE.md's "CI green" merge rule in
  the same way as the advisory a11y workflow: an unapproved or skipped run is normal. **Add a line to CLAUDE.md
  step 6** naming it (the owner approved this pattern for a11y; confirm it for this workflow in the PR).
- **Tests.** A `scripts/test/` shell test or a small Node script checks the path-filter logic against fixtures of
  changed-file lists: sky only, ask only, shared (both), pom with the sdk version (both), pom without it (neither),
  frontend only (neither). Validate the YAML. `actionlint` too if available; otherwise note that it wasn't run.
- **Owner setup after merge:**
  1. **Settings → Environments → New environment `prompt-regression`.** Add yourself as required reviewer and allow
     only `main` and PR refs.
  2. Add the environment secret `ANTHROPIC_API_KEY`. Consider a separate, spend-capped key.
  3. On the first prompt-changing PR, approve the run in the Actions tab.

**Costs (estimates, from the research).**
- **Sky quick:** about 25 calls on Sonnet 5.5, roughly $0.10–0.30, about 5 minutes.
- **Sky eval (dispatch only):** 80+ calls, roughly $1–2, 20–40 minutes.
- **Ask:** about 145 asks on Haiku, roughly $1.5–3.6, 30–50 minutes.

#### Phase 5 plan-review amendments (2026-11-08)

Two lenses reviewed this plan. These amendments override the notes above where they differ.

**PR 5a (the unified sky class)**
- **A1. The registry test moves package.** `RegressionBoundsRegistryTest` (package `service.evaluation`) calls the
  package-private `PromptRegressionTest.regressionFixtures()`, which can't be reached across packages once the new
  class lives in `eval`. Move the registry test to `eval`.
  - Its discovery size changes from `hasSize(8)` to **10** for the k=1 list, and its test name changes to match.
  - The 8 literal `EXPECTED` bounds stay **byte-identical**.
- **A2. The band-equals-bounds guard lives in the free offline test**, not the paid class. Add
  `assertThat(f.band()).isEqualTo(f.regression().rating())` for every fixture that has bounds. Today all 8 are equal.
  Fix the `RegressionBounds` Javadoc that says the bounds "may be wider than the eval fixture's band".
- **A3. Null scores.** A null rating, fiery, golden or summary is recorded as a **failed run (MALFORMED)**, never an
  `assertNotNull` or NPE inside the loop. The report prints first, then the verdict is asserted.
- **A4. `PassRateReport` stays test-only, and its existing meaning is kept.**
  - Keep `record(int)` and `passes()` as they are, because `PassRateReportTest` pins them.
  - Add `record(RunOutcome)`. `RunOutcome` holds the rating direction, the fiery direction, the golden direction and
    a malformed flag.
  - Add counts for the new outcomes, for example `boundMisses()` and `malformed()`.
  - The in-app `SkyRatingEvalService` does not use it and is untouched.
- **A5. Precedence is explicit.**
  - The model is resolved in this order: `-Dsky.model` > `SKY_MODEL` > `-Dregression.model` > `-Deval.model` >
    `REGRESSION_MODEL` > `EVAL_MODEL` > `SONNET_55`.
  - If two legacy settings disagree, fail fast.
  - `@BeforeAll` prints the resolved model and where it came from.
  - Document that a legacy `EVAL_MODEL=SONNET` still means Sonnet **4.6**.
  - Runs and fixture follow the same pattern: `sky.runs` > `SKY_RUNS`, and `sky.fixture` > `eval.fixture`.
- **A6. The seam.**
  - Type the strategy field as the public `EvaluationStrategy` interface.
  - Pure package-private helpers:
    - `RunOutcome judge(SkyRatingEvalFixture, SunsetEvaluation)`
    - `Verdict verdict(SkyRatingEvalFixture, PassRateReport, int k)`
    - `List<SkyRatingEvalFixture> fixtures(String filter)`
  - Offline tests use lambdas or fakes, never the API.
- **A7. The reference list, corrected.**
  - **Remove:** `BriefingBestBetAdvisor`, ValleyMist, BestBet, the Ask classes and `backend/AGENTS.md`. These only
    matched the class names as substrings.
  - **Update:**
    - `LowHauxleyFixture.java` (~26), `SteelRiggFixture.java` (~22), `SkyRatingEvalFixturesTest.java` (~24),
      `PassRateReportTest.java` (~15);
    - `SkyRatingEvalService.java` (~41, ~78): reword `DEFAULT_RUNS_PER_FIXTURE`'s "matches the gated test's depth"
      to "eval mode, `-Dsky.runs=8`", and note in its Javadoc that the in-app recorder checks the band only while
      the JUnit class also checks bounds;
    - `prompt-change-session-brief.md` (~56);
    - the pom comment (~847);
    - the pom's PIT `<param>` lines (~876, ~884);
    - `reference/dev-and-testing.md`;
    - `far-cloud-lid-plan.md` and `sky-user-message-trim-plan.md`: their run commands only.
  - **Do NOT edit `V117__create_sky_rating_eval_tables.sql`.** Applied migrations are checksummed.
  - **Do not edit `PitExclusionDriftTest`.** It works from tags, not class names.
  - Leave historical narrative and the "ported from PromptRegressionTest#…" fixture descriptions alone.

**PR 5b (the workflow)**
- **B1. Manual runs.** The `changes` guard is `github.event_name == 'workflow_dispatch' || <same-repo PR test>`.
  The step branches on the event and reads `inputs.suite` / `inputs.sky_runs` on a manual run.
- **B2. ValleyMist** is NOT in the hard sky step's `-Dtest`. It gets its own step with `continue-on-error: true`.
  That step, the summary and the upload all use `if: ${{ !cancelled() }}`, so a red sky step doesn't skip them. No
  `clean` runs between the steps.
- **B3. Key and approval.**
  - The environment secret is **`PROMPT_REGRESSION_ANTHROPIC_API_KEY`**, a **separate, spend-capped key. This is
    required, not optional.**
  - The approval is a cost gate, not a sandbox. Once approved, the PR head's workflow, tests, pom plugins and
    wrapper run with the key. Document an owner pre-approval checklist: diff `.github/**`, `backend/pom.xml`,
    `.mvn/**`, `mvnw` and `src/test/**`.
  - Leave deployment branches **unrestricted**: a branch rule would reject PR runs.
  - Leave "prevent self-review" **off**, so the owner can approve PRs opened under their own account.
- **B4. The diff is three-dot** against the merge base (`"$BASE"..."$HEAD"`), with `fetch-depth: 0`. Use plain git,
  no third-party path action, matching `ci.yml`.
- **B5. Globs, widened and named explicitly.** Paths are under
  `backend/src/main/java/com/gregochr/goldenhour/` unless given in full.
  - **sky** adds:
    - `util/DayLabels.java`
    - `entity/{ForecastStability,TargetType,AlertLevel}.java`
    - `backend/src/test/java/**/TestAtmosphericData.java`
    - `backend/src/test/java/**/eval/**`
    - `backend/src/test/java/**/service/evaluation/*{Regression,Fixture,PromptTrims,TokenCount}*.java`
    - `backend/src/test/resources/prompt-regression/*.txt`
    - `backend/src/test/resources/eval/**`
  - **ask** adds:
    - `util/{DayLabels,PlaceNames}.java`
    - `backend/src/test/resources/prompt-regression/ask/**`
  - **Shared** adds `service/evaluation/{PromptUtils,RatingValidator}.java`.
  - Name every test path explicitly in the workflow.
  - **The pom trigger:** grep `git diff -U8 "$BASE"..."$HEAD" -- backend/pom.xml` for `anthropic|prompt-regression|surefire`.
- **B6. The merge rule.** A run awaiting approval shows as a pending check, and expires red after 30 days. The
  CLAUDE.md step-6 exception names the exact job names and says a run that is waiting, skipped or expired is normal
  and is never awaited before a merge. The planner writes that line, since CLAUDE.md is a docs edit.
- **B7. Dependabot** never runs it: `github.actor != 'dependabot[bot]'`. For an SDK bump, the owner runs it
  manually.
- **B8. Least privilege.** Set workflow-level `permissions: contents: read`, and `persist-credentials: false` on every
  checkout.
- **B9. The summary** reads `tests`, `failures`, `errors` and `skipped` from each `TEST-*.xml` `<testsuite>` root with
  Python's `xml.etree`. Zero reports means "build failed". Any Ask `skipped > 0` is shown red.
- **B10. Concurrency, time limits and forks.**
  - The group is `${{ github.head_ref || github.ref }}`.
  - Sky's `timeout-minutes` is `${{ inputs.sky_runs == '8' && 60 || 30 }}`.
  - Pass `-DforkCount=1` on the Ask job, to avoid 429s.
- **B11. The CI model setting** is `SKY_MODEL=SONNET_55` only.

#### Owner setup (PR 5b)

The workflow lands inert until these are done: its paid jobs wait for an environment that does not exist yet.

1. **Settings, Environments, New environment `prompt-regression`.** Add yourself as a **required reviewer**.
2. Leave **deployment branches unrestricted** (a branch rule would reject PR runs), and keep **"prevent self-review" off** so you can
   approve PRs opened under your own account.
3. Add the **environment secret** `PROMPT_REGRESSION_ANTHROPIC_API_KEY`: a separate key with a spend cap, never the production or
   smoke key. (Not a repository secret.)
4. On the first prompt-changing PR, read the pre-approval checklist in `reference/dev-and-testing.md` ("Prompt regression in CI"),
   then approve the run in the Actions tab.
5. Do **not** add the workflow to branch protection.

As built (5b): the Ask side of `prompt-regression/` resources is `prompt-regression/ask/**` only; top-level `*.txt` there are sky;
any other file in that directory sets both suites.

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
