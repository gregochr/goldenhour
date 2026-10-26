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

Exit `0` is the gate; the grep is only for reading the detail. Use the `!**/integration/**` path glob rather than a list of class names — a name list rots (`HttpIntegrationTestBaseProbeTest` ends in `ProbeTest` and slips past `!*IntegrationTest`), and `-Dtest='!com.gregochr.goldenhour.integration.*'` does not work at all, as surefire ignores that form. JaCoCo coverage is then computed without those tests, i.e. slightly pessimistic — if it passes locally it passes on CI. **JaCoCo's rule is 80% line coverage per class**, which bites small new records: cover the defensive null branches with real assertions rather than deleting the guards.

**Don't use background Bash tasks for long Maven builds** — the background task output files are unreliable (often 0 bytes until the process finishes writing). Always run `./mvnw` in the foreground with an explicit `timeout` parameter so results come back immediately.

**Frontend tests are fast — run them separately:**

```bash
cd frontend && npm run test -- --reporter=dot   # ~90s, much faster than backend
```

⚠️ **`npm run test` is NOT the frontend CI job.** That job is lint → Vitest → **`npm audit
--audit-level=high`** → build, and the audit step is the one nothing local runs. It blocks on a
*transitive* advisory nobody in this repo introduced, so the PR goes red on a change that touches no
dependency file — which is exactly how P11 lost a CI round. Run all four before pushing:

```bash
cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build
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

The core rule: `compile → single-class test → checkstyle:check → full verify` as a ladder. Only climb to `clean verify` when you're confident everything is clean — and gate each rung on the exit code, not on what the output appears to say. ⚠️ Every rung that reaches the full `test` phase must carry `-Dtest='!**/integration/**'`, because there is no local Docker and never will be; without it you get a Testcontainers stack trace instead of a test failure. The integration classes run in CI.

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
