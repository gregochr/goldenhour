# Dev setup, the no-Docker rule, test standards, UI review cadence, build ladder, solar-utils

> Moved verbatim from `CLAUDE.md` on 2026-10-22 to keep the always-loaded file small.
> Read the section you need; this file is not loaded automatically.

## Dev Setup

The backend builds and runs on **JDK 25** (`<java.version>25</java.version>`; CI and the images use Temurin 25) — point `JAVA_HOME` at one.

```bash
# Backend (H2, no Docker)
export ANTHROPIC_API_KEY=your-key
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Frontend
cd frontend && npm run dev

# Trigger forecast run
curl -X POST http://localhost:8082/api/forecast/run

# Run all tests — ALWAYS with this exclusion; see the Docker note below
cd backend && ./mvnw clean verify -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false

# Prompt regression tests (requires ANTHROPIC_API_KEY)
cd backend && ANTHROPIC_API_KEY=... ./mvnw test -Pprompt-regression
# The sky class alone: quick (1 run per fixture, every assertion hard) or eval (strict pass^k on gated fixtures)
cd backend && ANTHROPIC_API_KEY=... ./mvnw test -Pprompt-regression -Dtest=SkyRatingRegressionTest
cd backend && ANTHROPIC_API_KEY=... ./mvnw test -Pprompt-regression -Dtest=SkyRatingRegressionTest -Dsky.runs=8
# Model: -Dsky.model > SKY_MODEL > -Dregression.model > -Deval.model > REGRESSION_MODEL > EVAL_MODEL > SONNET_55
# (conflicting legacy settings fail fast; legacy SONNET still means Sonnet 4.6). One fixture: -Dsky.fixture=<substring>.
# Observed sky-rating days live in ONE registry (eval/SkyRatingEvalFixtures.ALL) shared with the weekly Sky-Rating
# Eval: SkyRatingRegressionTest runs all of them and, for a fixture with a RegressionBounds, also checks its fiery and
# golden bounds. Add a JSON + one registry line + the literal bounds in RegressionBoundsRegistryTest. Bounds are the
# owner's call only.

# Frontend tests
cd frontend && npm run test
```

### ⚠️ There is no Docker on this machine — and that is not going to change

**Do not tell anyone to start Docker Desktop, and do not spend a session looking for it.** This
guidance previously said "Docker must be running to run the backend test suite", which sent session
after session hunting a daemon that has never been installed here. What follows is the truth.

**Twenty-three** classes extend `IntegrationTestBase` (re-counted 2026-10-20 after the evening refresh's `EveningSunriseRefreshSeedMigrationTest`, the earlier count being 20 on 2026-10-17, with `grep -rl 'extends IntegrationTestBase' backend/src/test/java | wc -l`; `HttpIntegrationTestBase` is one of them and has subclasses of its own; Ask PhotoCast's three migration tests, `OutcomeRatingMigrationTest` and the login-registration series' five — `PasswordResetTokenMigrationTest`, `EmailClaimMigrationTest`, `CredentialsChangedAtMigrationTest`, `PostcodePromptDismissedMigrationTest` and `AccountDeletionIntegrationTest` — are among them) (`backend/src/test/java/.../integration/`) and start a
`postgres:17-alpine` Testcontainer with `spring.flyway.enabled=true`. There is no failsafe plugin
and no surefire exclusion, so they run in the ordinary `test` phase — meaning a bare `./mvnw test`
or `./mvnw clean verify` fails here with an opaque `Could not find a valid Docker environment`,
not a skip.

**So the local gate always carries the exclusion.** This is the normal command, not a workaround:

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
```

**⚠️ CI is the only place a migration is proven BEFORE it merges.** Be precise about this, because
the sloppy version of the claim is wrong: `application-dev.yml` and `application-prod.yml` both set
`spring.flyway.enabled: true` against Postgres, so **production runs every migration at startup** —
that is what migrations are for — and a `dev`-profile run against a real Postgres executes them too.

What is true is narrower, and is the part that matters when reviewing a PR:

- The **default local paths never run migrations at all.** `application-local.yml` sets
  `spring.flyway.enabled: false` (H2 + `ddl-auto: update`), and the default test profile does the
  same (H2 + `create-drop`). So the ordinary edit-compile-test loop cannot exercise one.
- The only **pre-merge** execution is `IntegrationTestBase`, which turns Flyway on against a
  Postgres 17 Testcontainer — and that needs Docker, which this machine does not have.
- CI's `Backend — Build, Test & Coverage` job runs `./mvnw clean verify` with **no exclusion** on a
  runner that does, so CI is where a migration is proven before merge.

If you do have a native Postgres to hand, running the `dev` profile is a genuine local verification
route — it just is not the default and does not exist on this machine.

Two consequences worth internalising, because they end the recurring conversation:

- **A migration PR is not "unverified" locally — it is *pending CI*.** Write the Testcontainers
  test, run the local gate above, open the PR, and read CI's Backend job before merging. Saying
  "I couldn't verify the migration" is only half the story; the other half is that nobody can,
  here, and CI already does.
- **Postgres-specific SQL cannot break local dev**, because local dev never runs migrations at all.
  Write the migration for Postgres and do not contort it for H2.

Running the app locally needs no Docker either (H2 file DB).

**Local H2 and V190's columns.** An existing local H2 file gains `app_user.verification_reminders_sent` and `final_reminder_sent_at` through `ddl-auto: update` (the entity's `columnDefinition` carries the `DEFAULT 0`). If it ever refuses, delete `backend/data/goldenhour.mv.db`. Local profiles never run Flyway, so the `pending_registration_sweep` scheduler row is absent locally and the job never fires there: exercise it with its tests.

**Local H2 and V193's column.** `app_user.dormancy_warning_sent_at` arrives in a local H2 file through `ddl-auto: update` (a nullable `TIMESTAMP`, no default needed). Likewise the `dormant_account_sweep` scheduler row (V193, seeded PAUSED) is absent locally, so the dormant-account sweep never fires there and "Run now" has no job to run: exercise it with `DormantAccountSweepJobTest` and the repository and purge tests. `photocast.dormancy.dry-run` defaults to true.

Default local credentials: `admin` / `golden2026`

H2 console: `http://localhost:8082/h2-console` (JDBC: `jdbc:h2:file:./data/goldenhour`, user: `sa`, pass: empty). Reset: delete `backend/data/goldenhour.mv.db` and `.lock.db`.

**Ask PhotoCast locally (no Docker, no spend).** `application-local.yml` commits `photocast.ask.enabled: true` and `stub: true`: `StubAskEngine` answers from the real tools with templated text, makes no Anthropic call and writes no cost row, so Ask appears and a local run costs nothing. ⚠️ **Turning `stub` off locally bills the real Anthropic key (about 1p a typed question) — never do it to "try the real thing"**; override it on the command line for one deliberate run. `seed-local-fixture` is `false` in the file and is a one-run override (`-Dspring-boot.run.arguments=--photocast.ask.seed-local-fixture=true`, best against a fresh file: `--spring.datasource.url=jdbc:h2:file:./data/ask-fixture;AUTO_SERVER=TRUE`, port 8083, add `--server.port=8093` if a parallel session holds it): under the `local` profile only (it refuses otherwise) it writes three `Fixture …` regions, 22 locations named `… (fixture)`, rated entries through `BriefingEvaluationService.mergeFromBatch`/`mergeWoodlandFromBatch` — the pipeline's own write path, never the table — and synthetic tide extremes into YOUR H2 file, then builds a briefing, which makes the gloss and best-bet Claude calls with whatever `ANTHROPIC_API_KEY` is set (hence not a default; set a dummy key and a dead HTTPS proxy to be certain nothing leaves the machine). Then `POST /api/admin/ask/ready/precompute` (works on the stub), `npm run dev`, and the OWNER signs in — a session cannot. There is no BEST BET locally (a window's pick needs the region's Claude gloss headline and the fixture makes none), so the Ready `BEST_*` anchor can only be rehearsed by unit fixtures, and "pick 1 wears BEST BET" needs a real gloss. `AskLocalFixtureSeeder` lives under `backend/src/local/java`, compiled only by `-Plocal-dev` (so the recipe's `./mvnw -Plocal-dev spring-boot:run` must keep the profile; a run without it has no seeder bean, and neither it nor `LocalH2EnumWidener` is in the production jar or priced by any CI gate; their tests are under `backend/src/local-test/java`, run only with `-Plocal-dev`). `docs/engineering/ask-photocast-plan.md` §9 is the recipe, §11 the owner's browser checklist and §12 the production enable checklist.

**Local H2 clean-up beans (`backend/src/local/java`, `local` profile, H2 only, never fail startup).** Local runs never apply Flyway, so a migration that deletes rows never reaches `backend/data/`. `LocalH2EnumWidener` keeps old enum columns accepting new values; `LocalH2TermRunTypeCleanup` (sync-engine retirement Phase 3, the local twin of V196) deletes the retired `VERY_SHORT_TERM`/`SHORT_TERM`/`LONG_TERM` rows of `job_run` (and their `api_call_log` children) and `model_selection`, and every `prompt_test_result`/`prompt_test_run` row, in `afterSingletonsInstantiated` so it runs before anything reads `job_run`. Their tests live in `backend/src/local-test/java` and run with `-Plocal-dev`. Name a new bean here when you add one.

---


## Test Standards

Read the standards for the side you are working on before writing or modifying any test class.
They share a philosophy and almost no mechanics, so the wrong one is close to useless:

| Side | Document |
|---|---|
| Backend (Java, Mockito, JUnit) | `docs/engineering/test-improvement-standards.md` |
| Frontend (React, Vitest, Testing Library) | `docs/engineering/frontend-test-standards.md` |

**Suite sizes, measured 2026-10-06 at `29382830` (Ask PhotoCast through F5, before Phase Z) — re-measure rather than trust, the same way the
migrations table refuses to name its latest number:** backend **11,146** tests run with **1** skipped (`BatchSchemaIntegrationTest`) and the
CI-only integration classes excluded (`./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**'
-DfailIfNoSpecifiedTests=false`, read off the surefire summary line; `src/test/java/.../integration/` holds 103 more `@Test` methods that run
only in CI), frontend **8,398** Vitest tests in **331** files (`npx vitest run`). `AskPromptRegressionTest` is in neither figure (tagged
`prompt-regression`, excluded by default). A run well below these numbers has stopped early, not passed.

---

## The E2E smoke gate (Playwright, a required check)

`E2E — Playwright smoke` (the `e2e` job in `.github/workflows/ci.yml`) is a **required, merge-blocking check**,
and because `deploy.yml` calls `ci.yml` it also blocks a tagged release. Plan and rationale:
`docs/engineering/engineering-gaps-plan.md`, Phases 2, 3a.1, 3a.2 and 3b (3c adds an advisory accessibility scan).

**What it is.** The real backend (local profile, a fresh H2 file **carrying a seeded fixture**, Ask on its stub, a dead
outbound proxy) and the built frontend, driven by Chromium through
`frontend/playwright.smoke.config.js`. The frontend is served in one of **two modes** (see "Two web modes" below): CI
runs it through the production `nginx.conf` in an `nginx:alpine` container; a local run defaults to `vite preview`. Specs live in `frontend/src/test/e2e/smoke/`; the shared fixture is `fixture.js`
(login, hygiene, the tile counter, the dialog-depth observer and the helpers that read the served forecast);
`sheet-helpers.js` holds the location-sheet assertions; `global-setup.js` writes the one-time bits of per-user
state (the admin's postcode prompt dismissal, and the Coming up "seen" mark for all three accounts). The older `src/test/e2e/forecast.spec.js` (run by
`playwright.config.js`, which ignores `smoke/`) is manual.

**The `deploy-smoke` project (Phase 4)** is the last of the four projects (`smoke`, `roles-write`, `logout`, `deploy-smoke`; it depends on all three). Its one spec, `deploy-smoke.spec.js`, runs `scripts/post-deploy-smoke.sh` (the production post-deploy probe) against the seeded stack as `pro`, and against a tiny Node stub site (`deploy-smoke-stub.js`, a free port) that breaks one thing at a time, asserting the exit codes (0 pass, 1 release broken, 3 inconclusive, 4 misconfigured). It spawns the script **asynchronously** (a blocking spawn would stop the stub answering). It logs in as `pro` and logs out, which is account-wide, hence last. The shell-script tests (`scripts/test/*.test.sh`, CI job `Shell — script tests`) are described in `platform.md`, "Automatic rollback".

**The seeded fixture** (`E2eLocalFixtureSeeder` in `backend/src/local/java`, with its readiness bean
`e2eFixtureHealthIndicator`). It gives the Map and Plan journeys data with **no network call and no Claude call**: 3
regions of 6 places (Northumberland Coast with three coastal places, Lake District, Yorkshire Dales), rated for every
window from T-3 to T+4 (T-4 is left empty on purpose, so the window control reaches its floor), with a BEST pick.

- **Gating, in four independent ways.** `@Profile("local & !prod")`, `photocast.e2e.seed-fixture=true` (only
  `scripts/e2e-smoke-backend.sh` sets it), a runtime refusal unless the profile is `local` and the database is H2, and a
  refusal unless the `locations` table is empty. Every refusal throws and stops startup. Any other failure is logged at
  ERROR and leaves `e2eFixture` DOWN; the seeder never swallows it (unlike `AskLocalFixtureSeeder`).
- **It never runs a briefing build.** `BriefingService.refreshBriefing*` always fetches live weather and calls two Claude
  services. The seeder assembles the briefing from the same builder beans with synthetic weather. **The write order is
  load-bearing:** regions, places and tides; `forecast_evaluation`; `cached_evaluation` through `mergeFromBatch`; slots
  and days; `BriefingRegionEvaluationRollup.enrich` (persisting without it drops every pick, because the serve path nulls
  a gloss when the verdict changes); the stamped gloss and headline; the `daily_briefing_cache` row; `loadPersistedBriefing`;
  an almanac refresh; then the self-check (a BEST pick is served, the rows read back). Only then does it go UP.
- **Role users and sub-region enforcement (3a.2).** `E2eRoleUsers` (same gating, called only by the seeder, after the
  places are saved) inserts a `lite` (`LITE_USER`, `E2eLite#Pass1`) and a `pro` (`PRO_USER`, `E2ePro#Pass1`) beside the
  seeded admin (`admin` / `golden2026`), through the repository, mirroring `LocalAdminSeeder`: an encoded password,
  `enabled`, `createdAt`, and the insertable-but-`updatable = false` columns set on the builder (`passwordChangeRequired =
  false`, `postcodePromptDismissedAt`, so the postcode prompt never holds the app inert). Nothing else gates a first sign-in:
  terms are accepted at registration only. The script also passes `--photocast.sub-regions.enforce=true`, as production
  does, so `LitePickShaper` strips and marks `gated` the ratings outside a Lite reader's picks. The seeder writes one
  sub-region per region (`Northumberland Shore`, `Lakeland Fells`, `Dales Limestone`, each holding all six of its region's
  places, through `SubRegionMembershipService`) and gives `lite` picks for the first two, so it holds **some but not all**
  (Yorkshire Dales is gated). `lite` has picks, so its standing is `OPEN` (no grace stamp, no change lock) and the
  first-choice sign-in prompt, which shows only at `FIRST_CHOICE`, never appears. `pro` and `admin` are unrestricted. The
  self-check fails the seed unless both users exist with their roles and `lite` reads back exactly two of three picks.
- **The ratings are one pure function** (`E2eLocalFixtureSeeder.rating`): a region's base cycles 4, 3, 2 and rotates every
  window, and a place adds -1, +1 or 0 (zero over a region). So every place changes star between adjacent windows, and every
  window has a Worth it, a Maybe and a Stand-down region. Specs do not mirror it: they read the served `/api/forecast` as the
  **oracle** and compare against that.
- **Readiness waits for the seed.** Tomcat accepts requests before `ApplicationReadyEvent` listeners finish, so the script
  adds `e2eFixture` to the `container` health group (`--management.endpoint.health.group.container.include=db,ping,e2eFixture`)
  and sets `--management.endpoint.health.cache.time-to-live=0s` so a stale DOWN is never served. Only the smoke run changes
  the group.
- **Nothing leaves the machine.** The script starts the JVM behind a **dead proxy** (`-Dhttps.proxyHost=127.0.0.1
  -Dhttps.proxyPort=9`, and the http pair, with `http.nonProxyHosts=localhost|127.0.0.1`): every outbound call, Open-Meteo,
  NOAA and the Anthropic SDK's OkHttp client alike, fails at once with a refused connection. The external health probes read
  DOWN, so the status stream shows DEGRADED; no spec asserts on that banner, and it produces no console error. A CI step after
  the suite fails the job if `backend.log` contains `Daily briefing refresh started`, `Briefing weather fetch`,
  `Horizon cloud fetch` or `[ASK FIXTURE]`, **or if `[E2E FIXTURE] seeded` is absent** (so the check cannot pass on a log that
  never seeded).
- **The seeder's own gate.** `src/local` and `src/local-test` are outside the backend job (no JaCoCo, checkstyle or SpotBugs),
  so the E2E job runs `./mvnw -Plocal-dev test -Dtest='E2eLocalFixtureSeeder*,E2eRoleUsers*'` and `./mvnw -Plocal-dev generate-test-sources
  checkstyle:check`. ⚠️ The `generate-test-sources` is not optional: `-Plocal-dev` adds the two roots in that phase, and a bare
  `checkstyle:check` audits neither (it passes with a deliberate violation in `src/local`).
- **Dates.** Everything is relative to the server's UK "today" at boot. A run that crosses a sunrise or sunset (about 0.2-0.4%
  of runs) makes the first window past, so **no spec uses the first window**, none asserts a fixed count, and every expected
  star comes from the served forecast, keyed by the window's `data-ev-id`.

**The specs** (all on the shared fixture, with the hygiene assertion and no sleeps):

| Spec | What it asserts |
|---|---|
| `tabs.spec.js` | The tab bar is Plan, Coming up, Map, Operations (waits for the Map tab, which appears only once the forecast has loaded); each tab renders (heat cards, the almanac list, a Leaflet container, Operations). Never the desktop's opening tab: Map is preferred only if `/api/forecast` lands within 1.5 s, so every spec clicks its tab. |
| `phone.spec.js` | At 390 px the four tabs are there and Plan is selected. |
| `map.spec.js` | Leaflet draws and tiles are requested (a counter, polled); every visible chip's star equals the served star for the active window; a chip opens the callout whose score agrees; the close button and Escape both close it. Chips are the visible ones **by role** and polled, after `data-placed="true"` on `map-labels` (the placement-settled signal, a one-line frontend seam). |
| `window-stepping.spec.js` | From the current window forward to the last (`›` disables, no wrap), back through today into the past (`wf-win-past` appears), the keyboard with the pill focused, then `‹` loading the two older days (T-3 with rows, T-4 empty) and stopping at the floor, where `‹` is disabled and the status line explains why. At each step the pill changes and every chip present in both windows changes star. A phone leg uses the peek sheet. |
| `drilldown-map.spec.js` | Chip, callout, "Four days here", the location sheet (one row opens expanded; its detail response is awaited from a waiter registered before the click), a second row's lazily fetched detail, and the Escape order back out (sheet, focus to the reason line, selection). |
| `drilldown-plan.spec.js` | Heat card, window sheet, a place, the location sheet: two dialogs deep, never three (a MutationObserver installed before load records the maximum concurrent `.app-safe-modal` and `aria-modal`). Bamburgh Beach shows the rising-tide fact; an inland place does not. Escape closes the top layer first; the close buttons work. |
| `door1-obstacles.spec.js` | Moved here from `src/test/e2e/`: no overlay control covers a field chip in any matrix window, at 1280 and at 390 px. The pointer is parked off the sheet after each card click so a spot card's hover peek cannot cover the field; a failure names the covering element. |
| `roles.spec.js` | Read-only role gating, signed in as `lite` and `pro` (not the admin, so the sign-out hazard does not apply). Every non-admin: no Operations tab, and `GET /api/admin/scheduler/jobs` is 403 through `page.request` (the page's hygiene never sees it), with the admin's 200 as the control. LITE: Pro doors (`wf-heat-bestbets-upsell-*` on the Plan, `wf-scores-upsell-*` in a location sheet) open a worded popover with a Pro pill and no link; Ask is locked (`ask-field[data-locked]`, the Pro lens, the upsell) and no `/api/ask*` request is made; "How was it?" is locked (`pro-pill`, "Rating is part of Pro.", no write); chips outside the picks carry `data-gated`, the callout says "Outside your sub-regions" and the sheet shows `location-sheet-outside`; and the served `/api/forecast`, `/api/briefing/evaluate/scores` and `/api/briefing` carry no Fiery/Golden scores, no written why and no picks, with the gated places unrated, against PRO's full bodies as the contrast. PRO: none of the locks. |
| `roles-write.spec.js` | The Pro reader rates one past window (`outcome-rating-block`): the `PUT /api/outcome/ratings` is 200, the block reads rated, and the row reads back from `GET /api/outcome/ratings`. The only spec that writes shared state, so it is its own serial project, `roles-write` (`dependencies: ['smoke']`, `workers: 1`), before `logout`. |
| `nginx.spec.js` | The production nginx config, read FROM the mounted file (`E2E_NGINX_CONF`; each parse asserts it matched). **Skips unless `E2E_WEB=nginx`.** The document's CSP equals the config's `$csp` (the mode guard: a silent fall back to `vite preview` fails here); the seven `add_header ... always` names of `location /`, each once with the configured value, on the shell, a hashed asset and a deep link (`page.request` and `headersArray()`); caching (`no-cache` on the shell and a deep link, `public` and `immutable` plus a far `Expires` on assets); the SPA fallback (`try_files` only: the app has no client router); `/assets/missing.js` is a 404 that still carries the headers; the sha256 of the `<script nomodule>` body is in the CSP (Chromium skips that script before CSP, so a stale hash logs nothing); the first `/api/status/stream` event arrives within 8 s through the proxy (measured at about 20 ms; a buffering nginx would hold it to the 30 s heartbeat); no inline `<style>` on any tab or in the window sheet. Not covered: `worker-src` (service workers are blocked). |
| `ask.spec.js`, `scheduler.spec.js`, `auth.spec.js`, `logout.spec.js` | As in Phase 2. Ask asserts the box and its allowance only: nothing runs `AskReadyPrecompute`, so no Ready answers exist. |

The browser Back button closes the top dialog (`back-closes-dialogs-plan.md`; PR 2a wires Settings, the guide, the Ask sheet,
confirms, the aurora modals and the sub-region sheet), and `back.spec.js` tests it; see "Back (dialog history)" below.

**Two web modes** (`E2E_WEB`, read by `playwright.smoke.config.js`):

- **`preview`** (unset; the local default): `vite preview` on 4173, the API on 8083. No Docker needed.
- **`nginx`** (CI sets `E2E_WEB: nginx` and `E2E_API_PORT: '8082'`): `scripts/e2e-smoke-nginx.sh` runs `nginx:alpine` as
  `goldenhour-e2e-nginx` on the **host network** (`--network host`, no `-p`), with `frontend/dist` and the repo-root
  `nginx.conf` mounted **read-only and unedited**, exactly as `docker-compose.yml` mounts it, and
  `--add-host=goldenhour-backend:127.0.0.1` (honoured with host networking). nginx therefore listens on the host's
  **port 80** (`listen 80` in the config), so **in nginx mode the web port is 80**: `baseURL` is `http://127.0.0.1`, the
  backend's `--cors.extra-origins` is `http://127.0.0.1` (a browser omits the default port from `Origin`), and any other
  `E2E_WEB_PORT` is refused at once, in the config and in the script. The script also fails fast if port 80 is taken
  (it needs to be bindable by Docker, which runs as root on a CI runner).
  ⚠️ **Why host networking, so nobody switches back to `-p`:** `docker run` on the default bridge attaches a veth to
  `docker0` (carrier up, IPv6 link-local, DAD), and those interface changes land a moment AFTER the container answers,
  just after Playwright has launched Chromium. Chromium's NetworkChangeNotifier watches the host's interfaces over
  netlink and aborts in-flight requests on a change: `net::ERR_NETWORK_CHANGED` on `/registerSW.js` and `/assets/*.js`
  and a 30 s wait for the login form, on the first test of a run (2 of 5 jobs in the 3b proof). With host networking no
  interface is created or changed.
  Every journey is then also a CSP test (a blocked script or style logs a console error, which hygiene fails on), and
  `nginx.spec.js` asserts the headers, caching, fallback and SSE. **The backend must listen on 8082**, because the
  config's upstream is `goldenhour-backend:8082`: any other `E2E_API_PORT` is refused at once, in the config and in the
  script. A missing `frontend/dist` and an unreachable Docker daemon also fail fast with a message.
- **Lifecycle.** The script runs `docker run ... &` then `wait` (never `exec`) and a trap on EXIT, INT and TERM runs
  `docker rm -f`. The web server entry sets `gracefulShutdown` (SIGTERM, 10 s): Playwright otherwise SIGKILLs a webServer, the
  trap never runs and the container outlives the run. `reuseExistingServer` is **off** in nginx mode, even locally (a stale
  container would be reused silently). Ctrl-C (SIGINT) tears everything down cleanly (checked). ⚠️ A SIGTERM to the Playwright
  process itself (or SIGKILL) is not handled by Playwright for ANY web server: the backend JVM and the container are
  orphaned. The next run then stops at Playwright's own "http://127.0.0.1:80 is already used" check (4173 in preview mode), before the script runs
  (the script's own `docker rm -f` covers a container that exists but is not answering); the fix is
  `docker rm -f goldenhour-e2e-nginx` (and stopping the orphaned backend).
  nginx's access and error logs (stdout and stderr in this image) go to `frontend/test-results/e2e-logs/nginx.log`.
- **Locally with Docker:** `docker pull nginx:alpine`, build the jar and `npm run build` as below, then
  `cd frontend && E2E_WEB=nginx npm run test:e2e:smoke` (ports 8082 and 80 must be free; the cloud session's container can start
  `dockerd`, the owner's laptop has none and does not need it). CI pulls the image in its own step, so pull time is not
  charged to Playwright's 60 s server timeout.

**Run it locally** (JDK 25, as for any backend work; no `application.yml` needed; ports 8083 and 4173 free, or 8082 and 80 in nginx mode):

```bash
cd backend && ./mvnw -Plocal-dev -Dmaven.test.skip=true package     # the jar, with H2 and src/local/java
cd frontend && npm run build
cd frontend && npx playwright install chromium                       # once
cd frontend && npm run test:e2e:smoke
```

The ports are 8083 and 4173 (fixed in CI). ⚠️ Two checkouts running the suite on one machine collide on them, and because a local
run reuses whatever already answers there, each can silently drive (and later lose) the OTHER's servers: a run that fails
with `ERR_CONNECTION_REFUSED` halfway, or a backend that exits 143 under a later invocation, is that. `E2E_API_PORT` and
`E2E_WEB_PORT` move both (the config and the backend script read the same two), and a `--repeat-each` proof that chains
invocations should wait for the first one's backend to stop answering before starting the next.

Playwright starts both servers (`scripts/e2e-smoke-backend.sh` and `vite preview` or `scripts/e2e-smoke-nginx.sh`), whose logs go to
`frontend/test-results/e2e-logs/`. Locally an already-running server on those ports is reused, which is **not** a fresh
database; stop it first when a spec fails oddly. If the machine's preinstalled Chromium does not match the pinned
Playwright, `E2E_CHROMIUM_PATH=/path/to/chrome` points the run at it (local only; CI never sets it). `E2E_HYGIENE=record`
prints the hygiene findings below instead of failing on them, which is how to discover a new tab's errors.

**The rules** (each was put in the plan by an adversarial review):

- **Reliability is the requirement.** Chromium only, `@playwright/test` pinned exactly (also in the lockfile's root
  entry, or `npm ci` fails), `workers: 2`, `retries: 0`, traces `retain-on-failure`, `serviceWorkers: 'block'` (the PWA's
  worker would otherwise take over mid-test). No fixed sleeps: web-first assertions and `waitForResponse` only. No clock
  freezing in this phase.
- **No spend, by construction.** The job has no secrets, the backend script forces a fake `ANTHROPIC_API_KEY`, and the
  JVM runs behind a dead proxy (see the fixture above). The external health probes therefore read DOWN and show a DEGRADED
  banner, so **no spec asserts on the health banner**. Readiness is `/actuator/health/container` only (db, ping and
  `e2eFixture`), never the bare `/actuator/health`.
- **Third parties are fulfilled, never aborted** (an aborted request logs `net::ERR_FAILED` as a console error): ArcGIS
  tiles get a real 1x1 PNG and the Turnstile script gets a stub that verifies at once. Any other off-origin request is
  recorded and fails the test.
- **Hygiene on every test.** The fixture records console errors, `pageerror`s, responses of 400 or more from the app's
  own origin, and unexpected off-origin requests, and each test ends by asserting there were none apart from a named
  allow-list (`ALLOW_LIST` in `fixture.js`; each entry needs a reason and a follow-up). A test that provokes an error on
  purpose declares it with `hygiene.expect(...)`, scoped to that test.
- **The CORS gotcha.** The browser's `Origin` is the preview origin and the proxy passes it on. The backend allows
  `http://localhost:*` but not `127.0.0.1`, so the backend script adds `--cors.extra-origins=http://127.0.0.1:4173`; without
  it every browser POST, the login form's included, is a 403.
- **Logging in** posts to `/api/auth/login` through the preview origin (so the proxy is exercised) with the local admin
  `admin` / `golden2026`. `LocalAdminSeeder` makes it.
- **Sign-out runs alone, last.** `logout.spec.js` is its own Playwright project (`dependencies: ['smoke', 'roles-write']`,
  `workers: 1`): signing out bumps the admin's account-wide credentials version, so a parallel spec would get a 401 on its
  token. The role specs use `lite` and `pro`, so they carry no such hazard.
- **Opening the Map after the Plan tab used to leave it at zoom 16** (found by 3a.2; fixed in `HeatBoundsController`, see
  `map-tab.md` "A Map built inside a hidden tab"). `map-hidden-mount.spec.js` holds the Map chunk until after the Plan click
  and proves it; specs may visit the tabs in any order.
- **A re-rating answers with the PREVIOUS star** (a stale re-read in `OutcomeRatingService.rate`, found by 3a.2), so the
  "How was it?" block shows the old value until a reload. `roles-write.spec.js` asserts the block's value on a first rating
  only, and the stored row (through `GET`) on every rating.
- **Never fail the admin's sign-in on purpose.** Ten failed sign-ins lock an account out with a 429, which then
  rejects every other spec's login (found by `--repeat-each=20`). The failed-sign-in spec uses an unknown user.
- **The scheduler invariant.** The smoke database has no `scheduler_job_config` rows (they come from Flyway, which the
  local profile does not run), so no scheduled job registers. A spec asserts `GET /api/admin/scheduler/jobs` is empty, so a
  future seeder that adds rows fails loudly (the seeder never writes `scheduler_job_config` or `model_selection`).
  `photocast.ask.seed-local-fixture` stays off: it triggers a briefing build.
- **The Map tab appears late.** `App.jsx` offers it only once a forecast (or a real aurora alert) exists, so the tab bar
  has three tabs for a moment after login. Every spec waits for the Map tab before reading the bar.
- **Ask** renders its box and the allowance line. A typed question returns 503 `TYPED_UNAVAILABLE` from the stub, which the
  dock renders gracefully but which is a console error, so the spec sends none.
- **Time budget:** the suite under 3 minutes on 2 workers; the job's `timeout-minutes` is 20 (it also runs the seeder's
  tests and the boot).
- **Reliability proof.** `--repeat-each` repeats only the projects it is asked for, and a project's dependencies run once, so
  the whole suite repeated is three invocations: `--project=smoke`, `--project=roles-write` and `--project=logout`, each with
  `--repeat-each=10`.

**After merge (owner):** "E2E — Playwright smoke" is added to the branch-protection required checks.

### The accessibility scan (advisory, Phase 3c)

`E2E — accessibility (advisory)` (`.github/workflows/a11y.yml`) runs `@axe-core/playwright` (pinned exactly) on the same seeded,
nginx-served stack as the smoke gate. ⚠️ It is **its own workflow, not a job in `ci.yml`**: `deploy.yml` calls `ci.yml` through
`workflow_call`, so a red advisory job there would block every release. It triggers on `pull_request` and pushes to `main`, with
`paths-ignore` for `**/*.md` and `docs/**` (nothing waits on it, so a skipped run is harmless), its own `concurrency` group and a
20 minute timeout. Config: `frontend/playwright.a11y.config.js`, which imports the smoke config and overrides only `testDir`,
`outputDir`, `reporter` and `projects` (`a11y-desktop` 1280x800 and `a11y-phone` Pixel 7, both with `reducedMotion: 'reduce'`, never
`bypassCSP`). The default `playwright.config.js` ignores `**/a11y/**`.

- **Run it:** build the jar and `npm run build` as above, then `cd frontend && E2E_WEB=nginx E2E_API_PORT=8082 npm run test:e2e:a11y`
  (needs Docker), or without `E2E_WEB` for `vite preview` on 8083. `npm run a11y:summary` prints the folded table
  (`scripts/a11y-summary.mjs`, also saved as `test-results/a11y/summary.md` and appended to `$GITHUB_STEP_SUMMARY` in CI).
- **What is scanned**, each as its own test per project: the sign-in page, Plan, Coming up, Map, Operations, the Plan window sheet,
  the location sheet (opened over the window sheet) and the Map callout (the peek sheet on a phone). Whole page, tags `wcag2a`,
  `wcag2aa`, `wcag21a`, `wcag21aa`, **no `disableRules` and no `exclude`**. A test fails only on `serious` or `critical`; each
  scan writes `test-results/a11y/<project>-<scan>.json` and attaches the full axe result. The records live beside
  `test-results/a11y-artifacts` (Playwright's outputDir, emptied at each start), never inside it.
- **Not covered:** the basemap tiles are a stubbed 1x1 PNG, so contrast of chips over real imagery is not tested; axe's `incomplete`
  results (what it could not decide, mostly contrast) are counted separately in the summary, never as failures.
- **Existing violations are fixed in their own PRs, never suppressed.** The job lands red; the owner makes it required once clean.
- ⚠️ **The advisory merge exception.** CLAUDE.md merges on "CI green". For this one job, by name (`E2E — accessibility
  (advisory)`), a red result never blocks a merge: the merge decision reads the **required** checks plus Codex, and the red job is
  reported in the PR summary. That includes the PR that adds the job. The exception ends when the owner adds it to branch
  protection.

### Prompt regression in CI (approval-gated, never required)

`Prompt regression (approval-gated)` (`.github/workflows/prompt-regression.yml`, Phase 5b) runs the real-Claude
`prompt-regression` classes on pull requests that change prompt or evaluation code. It is **its own workflow**, never a job in
`ci.yml`, and **never a required check**: a run that is waiting for approval, skipped or expired is normal and is not awaited
before a merge (CLAUDE.md step 6 names its jobs, `Prompt regression — sky` and `Prompt regression — ask`).

- **How it triggers.** `pull_request` (opened, synchronize, reopened, ready_for_review) and `workflow_dispatch`. A `changes` job
  (no secrets, no environment) takes a three-dot `git diff` against the merge base (`fetch-depth: 0`) and feeds it to
  `scripts/prompt-regression-paths.sh`, which prints `sky=` and `ask=`. The globs are written out in that script and pinned by
  `scripts/test/prompt-regression-paths.test.sh`; shared files (the Anthropic client, `PromptUtils`, `AppConfig`, `DayLabels` and
  so on) set both, and `backend/pom.xml` sets both only when its diff mentions `anthropic`, `prompt-regression` or `surefire`.
  Fork PRs and Dependabot never reach the paid jobs; for an SDK bump the owner runs the workflow manually.
- **What runs.** `sky`: `SkyRatingRegressionTest`, `BestBetAuroraPromptRegressionTest` and `SystemPromptTokenCountTest` with
  `SKY_MODEL=SONNET_55` and `SKY_RUNS` (1 on a PR; 1 or 8 by the dispatch input). `ValleyMistPromptRegressionTest` runs in its own
  `continue-on-error` step and is reported, never failing, until the owner approves its assertions. `ask`: `Ask*RegressionTest`
  with `-DforkCount=1`; any skipped Ask test is shown red in the summary, because a skipped paid test checked nothing.
  `PromptReplayHarnessTest` never runs. The step summary (`scripts/prompt-regression-summary.py`, read from the surefire
  `TEST-*.xml`) and the uploaded reports use `if: ${{ !cancelled() }}`, so a red test step still produces them.
- **Approval.** Both jobs run in the `prompt-regression` GitHub environment, whose required reviewer is the owner; nothing spends
  money without a click. The key is the environment secret `PROMPT_REGRESSION_ANTHROPIC_API_KEY`, a separate, spend-capped key
  (never the production or smoke key), and a run fails fast when it is empty. ⚠️ **The approval is a cost gate, not a sandbox.**
  Once approved, the PR head's workflow, tests, pom plugins and Maven wrapper all run with the key.
- **Owner's pre-approval checklist (B3).** Before clicking Approve, read the PR's diff of `.github/**`, `backend/pom.xml`, `.mvn/**`,
  `mvnw` and `backend/src/test/**`. Anything there that is not obviously about the prompt under test is a reason to reject the run.
- **Costs** (estimates): sky quick (k=1) about 25 calls on Sonnet 5.5, roughly $0.10-0.30 and 5 minutes; sky eval (k=8, dispatch
  only) 80+ calls, roughly $1-2 and 20-40 minutes; ask about 145 asks on Haiku, roughly $1.50-3.60 and 30-50 minutes. A newer push
  to the same branch cancels a stale run (`concurrency` per job), so an unapproved run costs nothing.
- **Manual runs.** Actions, Prompt regression, Run workflow: choose `suite` (`sky`, `ask`, `both`) and `sky_runs` (`1` or `8`). The
  same environment approval applies.
- **Tests.** `scripts/test/prompt-regression-{paths,summary,workflow}.test.sh` run in `Shell — script tests`; the scripts are
  ShellChecked by `Shell — ShellCheck`. `actionlint` is not part of CI.

---

## Dialog history tests (`utils/dialogHistory.js`)

The store behind "Back closes the top dialog" (`docs/engineering/back-closes-dialogs-plan.md`) is tested against a
**fake Chromium history**, not jsdom. `src/test/fakeBrowserHistory.js` (`createFakeBrowser()`) models what jsdom does
not: every queued traversal runs, a push truncates the forward entries, an out-of-range `go` does nothing, a `go` is
relative to the entry the browser is on when it RUNS, `popstate` fires only when the test steps or flushes the queue,
and `userActivation` and `scrollRestoration` exist (with an ordered `log`). The store takes the fake through its seam:

```js
const b = createFakeBrowser();
__resetForTests({ history: b.history, win: b.win });   // enabled: false exercises the off switch
```

`__resetForTests()` (no arguments) puts the real globals back; call it in `afterEach`. Assert **spy calls**
(`go` called once with `-2`), never `popstate` counts. The timeout branch uses `vi.useFakeTimers()` and must call
`createFakeBrowser()`/`__resetForTests` again afterwards so the store's `setTimeout` is the faked one. `tick()` lets the
store's reconcile microtask run; in a React test wrap `tick()` and `b.flush()` in `act`.

**jsdom limits** (why the fake exists): two `history.back()` calls merge into one, a traversal followed by `pushState`
is dropped, and there is no `navigator.userActivation` and no `history.scrollRestoration`. Real jsdom is used only for
a few wiring tests. **Playwright limits**: `goBack` ignores Chromium's skippable-entry rule and always grants user
activation, so Chromium's own activation gate, skippable-entry behaviour and history-manipulation intervention cannot
be automated; they belong to the manual device pass in the plan (a release blocker). The first-run guard's
"before the first click" state IS covered in `smoke/back.spec.js`, by an init script that stubs `navigator.userActivation`
to report active only after a trusted click or key press (the stub never expires and cannot tell click from pointerdown;
the Vitest hook test covers that). Before any `goBack`/`goForward`/
reload, wait for the store to settle by polling `data-back-depth` on `<html>` (a number when idle, `busy` while a
traversal or reconcile is outstanding).

### Back (dialog history), tests of the wired consumers

- **Vitest.** A wired consumer is tested with `src/test/backTestKit.js`: `installBackBrowser()` installs a fresh fake
  Chromium history behind the store's seam (`b.userBack()` / `b.back()` press Back and settle inside `act`;
  `uninstallBackBrowser()` in `afterEach`). Assert what the dialog DID (its `onClose` ran, it stayed open, the layer count
  and `committedDepth`), never real history counts. Suites: `backDialogsConfirm`, `backDialogsSettings`,
  `backDialogsGuide`, `backDialogsAsk`, plus cases added to `UserManagementView`, `LazyEntrySettings` and
  `AppSettingsRoutes` (the engine boots at `App` mount). A suite that fakes only `Date` (the Ask shell) cannot use
  `tick()`'s fake-timer branch: wait a real macrotask instead.
- **Playwright** (`smoke/back.spec.js`). Rules: (1) before every `goBack` / `goForward` / reload, poll `<html data-back-depth>`
  to the expected number (it reads `busy` while anything is outstanding); (2) take `navigation.currentEntry.index` before
  anything opens: after a x, Escape or Back it must return to that baseline with no `history.state.phLayer`, which is the
  "no dead press" property; (3) never click `confirm-dialog-confirm` (the Operations case would reset the shared admin);
  (4) the guide iframe is stubbed by `stubGuideFrame(page, hygiene)` in `fixture.js`: route-fulfilled plain pages that load the repo's REAL
  `landing/guide/guide.js` (served from the stubbed origin), so the real script reports the page and replaces the location on
  a guide link and the history on a `#section` link; the guide host is an expected off-origin request for that test only; (5) `bottom-sheet` is shared, so sheets are selected by `aria-label`; (6) an Operations
  panel is closed by Back only when the entry below it is also a `#manage/...` hash (a non-manage hash is ignored by
  `ManageView`), so the spec clicks the Users tab first; (7) opening Settings runs `selectTab`, which scrolls the page to the
  top and focus-return scrolls to the cog, so the scroll case uses the fixed Ask bar on a phone, scrolls the page WHILE the sheet is up and asserts that value survives
  each close (and `history.scrollRestoration` is `'auto'` again), which fails if the engine stops setting `'manual'`.

## UI Work — Review Cadence

**Every code commit that touches the UI gets an adversarial review before it lands, and the tests go
past "it renders".** Not a rubber stamp and not after the fact: the review runs against the working
tree, its surviving findings are fixed, and only then does the commit happen.

The order is: **build → tests → adversarial review of the diff → fix what survives → re-verify →
commit.**

**Why.** The UI is the product here — the forecasts only reach a user through it, and a pilot with a
handful of people gives very few chances at a first impression. Every review run on this redesign so
far has found real defects that a green test suite, clean lint and a successful build had all passed
over: Tailwind theme tokens silently pruned to the empty string, an escape hatch that disappeared on a
failed settings fetch, a plan citation that concealed a missing region ordering. Review-after-green is
where this project's defects actually live.

**Shape that works** (~15 agents): about six prosecutor lenses over the diff — runtime behaviour,
CSS/tokens, test quality, accessibility, project conventions, and what it makes harder for later
phases — then one refutation agent per charge, prompted to *refute*, defaulting to REFUTED without
citable evidence, then a synthesis pass. Report what was *not* examined as plainly as what was; a
charge that fell below a verification cut is not a finding.

**Verify in the browser rather than asserting — the local path works now, and it earns its keep.**
Run the backend with `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local` (port
**8083**, not the 8082 this file records elsewhere) and the frontend with `npm run dev`; sign in as
`admin` / `golden2026`. The first phase built with it (P4c) found three defects a green suite, a
clean lint and a successful build had all passed over — a theme token pruned to the empty string, a
12px baseline drift across a rail, and a label rendered twice — plus one that P4a's adversarial
review had explicitly **refuted**. Weigh a reviewer's confidence against a screenshot accordingly.

Where the browser cannot reach, say so rather than implying otherwise: a local DB with no evaluation
run has no ratings, so the rich states need a fixture. State which claims were seen and which were
tested.

⚠️ **Review agents must never write to the working tree.** A reviewer that probes by mutating source
— stripping a guard to see whether a test catches it, then tidying up with `git checkout --` — will
delete unstaged work it never knew was there. This has already happened once: a P0 reviewer's cleanup
destroyed uncommitted test improvements, unrecoverably, because they had never been staged. Tell
review agents to read only, and give anything that genuinely needs to mutate its own copy
(`isolation: 'worktree'` on the Agent/Workflow call). Commit or stash before starting a review that
runs mutations.

---


## Speeding Up the Dev Build Cycle

`./mvnw clean verify` runs Checkstyle → compile → test → JaCoCo → SpotBugs → repackage — ~6–7 min, and
**cannot run bare on this machine** — the `test` phase includes the 23 `IntegrationTestBase`
Testcontainers classes and there is no local Docker. Always pass `-Dtest='!**/integration/**'`.
Use targeted commands instead:

```bash
# Compile only — catches import/signature errors in seconds        [no Docker]
cd backend && ./mvnw compile -q

# Run a single test class — fastest inner loop   [no Docker, unless it extends IntegrationTestBase]
cd backend && ./mvnw test -pl . -Dtest=AuroraStateCacheTest -q

# Run all aurora-related tests only                                [no Docker]
cd backend && ./mvnw test -Dtest="Aurora*,ClaudeAuroraInterpreter*" -q

# Skip Checkstyle + SpotBugs (still runs all tests + JaCoCo)
cd backend && ./mvnw verify -Dcheckstyle.skip=true -Dspotbugs.skip=true \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false -q

# Full verify but skip clean (shaves ~30s)
cd backend && ./mvnw verify -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false -q
```

**Checkstyle pre-flight before full verify** — run Checkstyle alone first; it fails fast (~15s) and catches line-length / unused-import violations before wasting 6 min:

```bash
cd backend && ./mvnw checkstyle:check -q
```

⚠️ **Gate on Maven's exit code, never on a grep of its output.** With `-q`, Checkstyle's violation lines are suppressed, so `| grep -E "ERROR"` finds nothing and looks clean. Worse, `$?` after a pipeline is the status of **grep**, not Maven — it is almost always `0`. That combination reported a green build twice on 2026-07-25 while Maven was in fact returning `1`, hiding both a Checkstyle violation and a genuinely failing test until CI caught them. Redirect to a file and echo the exit code as its own statement:

```bash
cd backend && ./mvnw checkstyle:check >/tmp/cs.log 2>&1; echo "exit: $?"
```

**Reproducing CI's `Backend — Build, Test & Coverage` locally** — a plain `./mvnw clean verify` cannot do this. The classes under `src/test/java/.../integration/` need Testcontainers, which needs Docker, which this machine does not have; surefire aborts on them and the build **never reaches `jacoco:check` or `spotbugs:check`** — so the two gates most likely to fail CI are exactly the two a local `verify` silently skips. Exclude them and the build gets there (⚠️ this also means the integration classes, and therefore every Flyway migration, are proven **only in CI** — read the Backend job on the PR):

```bash
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
grep -E "BUILD (SUCCESS|FAILURE)|Rule violated|Tests run: [0-9]+, Fail" /tmp/v.log | tail -6
```

Exit `0` is the gate; the grep is only for reading the detail. Use the `!**/integration/**` path glob rather than a list of class names — a name list rots (`HttpIntegrationTestBaseProbeTest` ends in `ProbeTest` and slips past `!*IntegrationTest`), and `-Dtest='!com.gregochr.goldenhour.integration.*'` does not work at all, as surefire ignores that form. JaCoCo coverage is then computed without those tests, i.e. slightly pessimistic — if it passes locally it passes on CI. **JaCoCo holds every class to 95% of lines and 90% of branches on its own**, plus a bundle floor (99.3% lines, 95.8% branches, measured 2026-11-05 without the integration tests, rounded down). The seven classes that carry an unreachable branch have their own `<rule>` in `backend/pom.xml`, each at its measured figure with the reason in an XML comment; they are excluded from the default rule and nothing is excluded from checking. A new class arrives with its tests, which bites small new records: cover the defensive null branches with real assertions rather than deleting the guards. Never lower the default to fit a class; add an override only for a branch that truly cannot be reached, with the reason, and raise an override when its class improves.

**Backend changed-lines check.** On `pull_request` runs the backend job also runs `diff-cover` (pinned `10.0.0`) over `backend/target/site/jacoco/jacoco.xml` against the PR's base branch and fails when under 90% of the lines the PR adds or changes under `backend/src/main` are covered; it needs `fetch-depth: 0` on the checkout and a `git fetch` of the base ref, like the frontend step. JaCoCo's `sourcefile` paths are package-relative (`com/gregochr/.../Foo.java`), so `--src-roots backend/src/main/java` is required to map them onto the diff's `backend/src/main/java/...` paths, and it must run from the repo root. ⚠️ Without `--src-roots` nothing matches, diff-cover prints "No lines with coverage information in this diff" and exits 0 (proved locally), so a green run on a PR that changes `src/main` must show real percentages in the job summary. Tests, resources and a PR with no `src/main` change are absent from the report and pass. Line coverage only. Locally, after the `verify` command above (the per-class gate may fail it; the XML is written at the `test` phase either way): `pip install diff-cover==10.0.0` then, from the repo root, `diff-cover backend/target/site/jacoco/jacoco.xml --src-roots backend/src/main/java --compare-branch=origin/main --fail-under=90`. The diff covers committed, staged and unstaged changes.

**Frontend coverage floor.** CI's Vitest step is `npm run test:coverage` (`vitest run --coverage`, v8 provider via `@vitest/coverage-v8`, pinned to the exact vitest version). The floor is `test.coverage.thresholds` in `frontend/vite.config.js`: global lines / statements / functions / branches, the figure measured on 2026-11-04 rounded down to a whole percent (99 / 98 / 98 / 95; raised three times: 97 / 96 / 96 / 93 → 98 / 97 / 97 / 93 by tests for the API wrappers and the simulation UIs → 99 / 98 / 98 / 94 by tests for the auth context, hooks, utils and remaining API wrappers → 99 / 98 / 98 / 95 by the per-file branch tests below). `include` is `src/**/*.{js,jsx}` so untested files count as 0%. **Per-file floor:** every file in `src/**` must also reach **95% lines and 90% branches on its own**, enforced by a glob entry with `perFile` in the same `thresholds` block (the run fails naming the file), so a new file must arrive with its tests. Vitest applies a top-level `perFile` to every file and a glob entry cannot relax it, so the glob excludes by name the two files whose remaining branches truly cannot be reached, and each has its own entry with its measured figure (they still cannot fall further): `TurnstileWidget.jsx` (81% branches: the `typeof document` SSR guard, a null container ref, and a re-render over an existing widget id that the once-only effect can never hold, and the `!cancelled` re-checks after the cleanup has cleared the poll) and `runs/StartRunScope.jsx` (87%: the `if (allRef.current)` null arm in an effect, since React sets the ref first). A guard that protects real behaviour (a double submit, a race, a stale response) is never an exception: it gets a test (`FixedDelayEditor`'s double-click guard is tested with two native clicks in one `act`). Do not add a file to that list to dodge writing a test. **Changed-lines check.** On `pull_request` runs CI also runs `diff-cover` (pinned `10.0.0`, installed with pip) over `coverage/lcov.info` against the PR's base branch and fails when under 90% of the lines the PR adds or changes under `frontend/src` are covered, so a PR cannot hide untested code inside a high global total. Only files present in lcov count (the `include`/`exclude` above), so tests, `src/test/**` and `src/main.jsx` are ignored; a PR with no covered source changes reports "No lines with coverage information in this diff" and passes. It gates line coverage only: diff-cover reads `BRDA` but cannot gate branches separately. Locally, after `npm run test:coverage`: `pip install diff-cover==10.0.0` then, from the repo root, `sed 's#^SF:#SF:frontend/#' frontend/coverage/lcov.info > /tmp/lcov-root.info && diff-cover /tmp/lcov-root.info --compare-branch=origin/main --fail-under=90` (the `SF:` rewrite is needed: lcov paths are relative to `frontend/`, diff-cover resolves them against the repo root, and without it nothing matches and the check passes vacuously). **Ratchet rule: raise the numbers when coverage rises; never lower them without the owner's say-so.** `npm test` stays the plain, no-coverage run. Output goes to the ignored `frontend/coverage/`.

**Don't use background Bash tasks for long Maven builds** — the background task output files are unreliable (often 0 bytes until the process finishes writing). Always run `./mvnw` in the foreground with an explicit `timeout` parameter so results come back immediately.

**Frontend tests are fast — run them separately:**

```bash
cd frontend && npm run test -- --reporter=dot   # ~8-10 min for the full suite (15.6k tests); coverage overhead was within run-to-run noise in one local measurement
```

⚠️ **`npm run test` is NOT the frontend CI job.** That job is lint → Vitest with the coverage floor → **`npm audit
--audit-level=high`** → build, and the audit step is the one nothing local runs. It blocks on a
*transitive* advisory nobody in this repo introduced, so the PR goes red on a change that touches no
dependency file — which is exactly how P11 lost a CI round. Run all four before pushing:

```bash
cd frontend && npm run lint && npm run test:coverage && npm audit --audit-level=high && npm run build
```

And when `npm audit` does fire, prefer a **surgical lockfile edit** to `npm audit fix`. The fix
command also rewrites lockfile metadata to whatever the local npm emits — on npm 11 / Node 25 it
strips the `libc` field from every optional platform-specific rollup binary, 72 lines of it, and CI
runs Node 22 whose npm does not write that field. `libc` is what disambiguates the glibc and musl
builds of a native dependency, so a security patch would be carrying a change that could alter which
binary the Linux runner installs. Edit the three lines (version, resolved, integrity — take the
integrity from `https://registry.npmjs.org/<pkg>/<version>`), then prove it the way CI will:
`rm -rf node_modules && npm ci` must exit 0, report 0 vulnerabilities, and leave the lockfile
unchanged. ⚠️ **Check `package.json`'s `overrides` first**: `brace-expansion` is pinned there
to one exact version so every `minimatch` shares a node, and twice now (#421, and the v2.22.2
release failure) the pin was itself the newly-vulnerable version — with the pin unbumped, the
three-line lockfile edit leaves `npm ci` refusing the tree (`Missing: brace-expansion@<old>`, once
per `minimatch`), and an audit run over an EMPTY `node_modules` then reports 0 vulnerabilities,
which is not evidence of anything.

**Gate values are published.** When a gate value changes (`vite.config.js` coverage thresholds, the JaCoCo minimum, the SpotBugs threshold, `failBuildOnCVSS`, PIT `mutationThreshold`, the ZAP gate's risk level, or the required checks on `main`), update `landing/engineering.html` and its "Last checked" date in the same PR.

The core rule: `compile → single-class test → checkstyle:check → full verify` as a ladder. Only climb to `clean verify` when you're confident everything is clean — and gate each rung on the exit code, not on what the output appears to say. ⚠️ Every rung that reaches the full `test` phase must carry `-Dtest='!**/integration/**'`, because there is no local Docker and never will be; without it you get a Testcontainers stack trace instead of a test failure. The integration classes run in CI.

---

## Backend mutation testing (PIT)

`.github/workflows/pitest.yml` runs PIT weekly (Monday 06:00 UTC) over the service and utility layers; configuration is the `pitest-maven` plugin in `backend/pom.xml` (`mutationThreshold` 60). It never blocks a PR.

- **Runner.** `ubuntu-latest` (free, the repo is public), not the self-hosted runner that serves production deploys. PIT excludes the Testcontainers and full-context tests (`PitExclusionDriftTest` enforces the list), so it needs no Docker.
- **Shards.** One cold run is ~2h07m (10,506 mutations at the last success), so the workflow runs five parallel shards, each with its own report artifact `pitest-report-<n>` and a summary line. `pom.xml` takes the targets from the `pit.targetClasses` property (default: the full list); explicit plugin configuration beats `-D`, so the property is the only override route. Each shard passes `-Dpit.targetClasses=...`. ⚠️ In PIT globs `*` matches dots, so `service.*` already covers every sub-package: shards split the top-level `service` classes by first letter (class names are uppercase, sub-package names lowercase) and list sub-packages explicitly. The `plan` job asserts that every class under `service/` and `util/` that the default targets match is matched by exactly one shard pattern, so a new class or package cannot be missed or mutated twice; add a new sub-package to a shard when it fails. Run one shard locally with `./mvnw test-compile pitest:mutationCoverage -Dpit.targetClasses='com.gregochr.goldenhour.util.*'`.
- **No incremental analysis.** Open-source PIT 1.30.1 has none: `historyInputFile` and `historyOutputFile` fail with "History has been enabled but no history plugin has been installed" (history is the commercial arcmutate plugin). Sharding is the answer.
- **Timeouts.** Each shard 350 minutes (GitHub's cap is 360), the PIT step 320, so the summary and report upload still run.
- **Why it changed.** The last success (2026-10-05) took 2h23m (PIT 2h07m, 10,506 mutations, 77% killed, test strength 80%). The runs of 2026-10-12, 10-19, 10-26 and 11-02 were all `cancelled` by the old 180-minute cap and nobody was told: `notify-failure` used `if: failure()`, and a timed-out job ends as `cancelled`, so the dependent job was skipped. `pitest.yml` and `stryker.yml` now notify on `failure` **or** `cancelled`; `ci-failure-issue.yml` assumes no particular conclusion (its text says only "failed").
- **Never run the full PIT locally**: it takes hours.

## Frontend mutation testing

Stryker (`@stryker-mutator/core` + `@stryker-mutator/vitest-runner`, pinned to the same exact version
so Dependabot bumps them together as the `stryker` group) mutates `frontend/src/utils/*.js` and checks
that some test notices each fault. Config: `frontend/stryker.config.mjs`; workflow:
`.github/workflows/stryker.yml`; plan and review record: `docs/engineering/frontend-mutation-testing-plan.md`.
It **runs weekly (Wednesday 03:00 UTC) and never blocks a PR**; a failed run opens a report through
`ci-failure-issue.yml`, as `pitest.yml` does. Each shard has a mutation-score floor (`thresholds.break`).

- **Per-shard floors.** `thresholds.break` applies to each Stryker process's own score and each CI shard is its own process, so one number would fail the weaker shards. The floors are the scores of the first full forced run after the survivor sweeps (run 42380927482, 2026-11-07), rounded down: overall 96.28% (18,402 killed, 710 live), up from an 87.81% pre-sweep baseline (run 42202344388).

  | shard | score | floor |
  |---|---|---|
  | 1 | 96.77% | 96 |
  | 2 | 95.99% | 95 |
  | 3 | 94.15% | 94 |
  | 4 | 97.90% | 97 |
  | 5 | 95.47% | 95 |
  | 6 | 96.51% | 96 |
  | 7 | 96.75% | 96 |
  | 8 | 97.21% | 97 |
  | 9 | 96.20% | 96 |
  | 10 | 96.50% | 96 |

  The floor lives in the `plan` job's matrix (`"break"` per shard, asserted numeric), reaches Stryker as `STRYKER_BREAK` on the run step, and `stryker.config.mjs` reads it (a whole number 0-100, otherwise it throws; unset, as in every local run, it is `null`, so there is no floor). Ratchet rule: raise a floor, never lower one without the owner's say-so.

- **Locally:** `cd frontend && npm run test:mutation` (add `-- --mutate 'src/utils/heatField.js'` to
  narrow it). The dry run alone is about 18 minutes (15.4k tests, one worker, because the runner forces
  `maxWorkers: 1`); a full run is 18,902 mutants at roughly 18 per minute (measured 2026-11-06 on a loaded laptop, four
  runners), about 17 hours, so **always narrow it**. An interrupted run (Ctrl-C) saves a partial
  incremental file and restores the originals; it may leave `stryker-setup-*.js` files behind (git-ignored). ⚠️ Interrupt only the main `stryker`
  process (SIGINT) and let it finish restoring: killing its children, or deleting `.stryker-tmp/` first,
  strands the whole tree with a `// @ts-nocheck` header and the mutants' instrumentation (1,286 files
  were left modified once). **Run mutation testing only in a separate clean worktree** (`git worktree add`), so nothing of yours is in the tree; then the recovery is to delete that worktree. If you must recover in place, commit or copy your own work out first, then restore (CLAUDE.md forbids restoring with `git checkout --` over uncommitted work).
  Stryker runs `inPlace: true` (the originals are backed up to `.stryker-tmp/` and restored on exit):
  `windowFirstTopics.test.js` reads a backend source file by a path that climbs out of `frontend/`,
  which the default one-level-deeper sandbox copy breaks. Run it on a clean checkout, and if a run is
  killed, check `git status` for a half-mutated `src/utils` file. The pool is `forks`, not the runner's
  default `threads`, because the suite pins `process.env.TZ`.
- **Shape:** ten shards, split by first letters of the file name and balanced by line count (about 5k
  lines, 1.9k mutants each), because one job would run for most of a day. The `plan` job owns the
  shard list and fails the run if the globs do not match `src/utils/*.js` exactly once, so a new file
  whose name starts outside every shard (for example `e…`, `k…`, `n…`) fails that check loudly: add its
  glob to the lightest shard. **Add shards, never widen one**, if the run time grows; the first full run
  of a shard must fit in its 350-minute job cap on its own.
- **Incremental state** (`incremental: true`) is saved only when a shard's run finishes. It travels as an
  artifact `stryker-incremental-<shard>` (30-day retention, uploaded `if: always()`), fetched at the start
  from the newest of the last 10 completed `stryker.yml` runs on `main` that has one for the shard, whatever its conclusion; `actions/cache` is not
  used because GitHub evicts caches unused for 7 days, which is the weekly boundary. With no earlier run,
  a shard starts from scratch.
- **Forced runs.** Incremental mode cannot see changes to `setup.js`, `vite.config.js` or dependency
  bumps, so `stryker run --force` re-tests everything: via the `force` input of a manual dispatch, and
  automatically on any scheduled run whose UTC day of the month is 7 or less (the first Wednesday).
- **Deliberate trade-offs, revisit once reports exist:** `ignoreStatic: true` (module-level tables are
  static mutants that re-run every covering test) and `mutator.excludedMutations: ['StringLiteral']`
  (strings in `utils` are mostly user-facing labels: low value, high cost). `coverageAnalysis` is not
  set; the vitest runner always uses per-test coverage.
- **Reading survivors.** A mutant in code that only runs inside a `React.lazy` import can survive falsely:
  the first test that triggers the lazy load may time out, and a covering test that runs only after the
  module is cached sees no difference. Check the report (`reports/mutation/mutation.html`, uploaded as the
  `stryker-report-<shard>` artifact) before writing a test for a survivor.
- `.stryker-tmp/` and `reports/` are git-ignored and docker-ignored, and `**/.stryker-tmp/**` is excluded
  from Vitest so a failed run's leftovers cannot duplicate tests.

---

## solar-utils

Resolved from **JitPack**, not GitHub Packages. `backend/pom.xml` declares
`com.github.gregochr:solar-utils:2.1.0` against the single `<repository>` whose `<id>` is `github` but
whose URL is `https://jitpack.io` — the id is misleading. No `read:packages` token is required.

A jar is also vendored in-tree at `backend/.m2/repository/com/gregochr/solar-utils/2.1.0/` and staged by
`backend/Dockerfile`, but its groupId is `com.gregochr` — a *different* coordinate from the one the POM
asks for, so it cannot satisfy the dependency; the Docker build still resolves 2.1.0 from JitPack.
Treat the vendored copy as a leftover from the pre-JitPack local-install era.

Public API (v2.1.0, `com.gregochr.solarutils.SolarCalculator`): `sunrise`, `sunset`, `civilDawn`,
`civilDusk`, `goldenHourStart`, `goldenHourEnd`, `solarNoon`, `dayLengthMinutes`, `sunriseAzimuth`,
`sunsetAzimuth`. The jar also ships `LunarCalculator` and `MoonriseMoonsetCalculator`.
