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
# Observed sky-rating days live in ONE registry (eval/SkyRatingEvalFixtures.ALL) shared with the weekly Sky-Rating
# Eval: a fixture with a RegressionBounds is also a PromptRegressionTest case. Add a JSON + one registry line + the
# literal bounds in RegressionBoundsRegistryTest. Bounds are the owner's call only.

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
`docs/engineering/engineering-gaps-plan.md`, Phase 2. Phase 3 adds seeded data so the Map and Plan specs run too.

**What it is.** The real backend (local profile, a fresh H2 file, Ask on its stub) and the built frontend behind
`vite preview`, driven by Chromium through `frontend/playwright.smoke.config.js`. Specs live in
`frontend/src/test/e2e/smoke/`; the shared fixture is `fixture.js`; `global-setup.js` dismisses the one-time postcode
prompt for the local admin. The older `src/test/e2e/*.spec.js` (run by `playwright.config.js`, which now ignores
`smoke/`) are manual and move to Phase 3.

**Run it locally** (JDK 25, as for any backend work; no `application.yml` needed; ports 8083 and 4173 free):

```bash
cd backend && ./mvnw -Plocal-dev -Dmaven.test.skip=true package     # the jar, with H2 and src/local/java
cd frontend && npm run build
cd frontend && npx playwright install chromium                       # once
cd frontend && npm run test:e2e:smoke
```

Playwright starts both servers (`scripts/e2e-smoke-backend.sh` and `vite preview`), whose logs go to
`frontend/test-results/e2e-logs/`. Locally an already-running server on those ports is reused, which is **not** a fresh
database; stop it first when a spec fails oddly. If the machine's preinstalled Chromium does not match the pinned
Playwright, `E2E_CHROMIUM_PATH=/path/to/chrome` points the run at it (local only; CI never sets it). `E2E_HYGIENE=record`
prints the hygiene findings below instead of failing on them, which is how to discover a new tab's errors.

**The rules** (each was put in the plan by an adversarial review):

- **Reliability is the requirement.** Chromium only, `@playwright/test` pinned exactly (also in the lockfile's root
  entry, or `npm ci` fails), `workers: 2`, `retries: 0`, traces `retain-on-failure`, `serviceWorkers: 'block'` (the PWA's
  worker would otherwise take over mid-test). No fixed sleeps: web-first assertions and `waitForResponse` only. No clock
  freezing in this phase.
- **No spend, by construction.** The job has no secrets and the backend script forces a fake `ANTHROPIC_API_KEY`. The
  backend still makes outbound health probes (Anthropic 401, Open-Meteo), which are soft and show a DEGRADED banner, so
  **no spec asserts on the absence of the health banner**. Readiness is `/actuator/health/container` only (db and ping),
  never the bare `/actuator/health`.
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
- **Never fail the admin's sign-in on purpose.** Ten failed sign-ins lock an account out with a 429, which then
  rejects every other spec's login (found by `--repeat-each=20`). The failed-sign-in spec uses an unknown user.
- **The scheduler invariant.** The smoke database has no `scheduler_job_config` rows (they come from Flyway, which the
  local profile does not run), so no scheduled job registers. A spec asserts `GET /api/admin/scheduler/jobs` is empty, so a
  future seeder that adds rows fails loudly. `photocast.ask.seed-local-fixture` stays off: it triggers a briefing build.
- **An empty database has no Map tab.** `App.jsx` offers it only with a forecast or a real aurora alert. The spec asserts
  the three-tab bar; Leaflet and tile assertions arrive with Phase 3's seeded data.
- **Ask on an empty database** renders its box and the allowance line. A typed question returns 503
  `TYPED_UNAVAILABLE`, which the dock renders gracefully but which is a console error, so the spec sends none.
- **Time budget:** the job under 10 minutes, the specs under 2.

**After merge (owner):** "E2E — Playwright smoke" is added to the branch-protection required checks.

---

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
`ci-failure-issue.yml`, as `pitest.yml` does. `thresholds.break` is `null`: a floor is set only after
the first full measurement (plan Phase 2).

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
