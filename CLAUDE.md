# CLAUDE.md — The Photographer's Golden Hour

## Project Overview

A full-stack app that evaluates sunrise/sunset colour potential at configured locations.

- **Backend**: Spring Boot 4 REST API — Open-Meteo weather + air quality, WorldTides tide data, Claude (Anthropic SDK) evaluation, H2/PostgreSQL persistence, scheduled runs, notifications (email/Pushover/macOS toast).
- **Frontend**: React 19 + Vite + Tailwind — Map tab (Leaflet, v2: heat field default, chronological window control), Plan matrix (labelled *At a Glance*; day × event heatmap, popup and location sheet), Coming up feed, Operations tab, JWT-authenticated, and **Ask PhotoCast** (Pro and Admin; a flag-gated plain-English question box over the served forecasts, linked to the Map and the Plan matrix). Outcome recording has one UI: the Map tab's "How was it?" block, where Pro and Admin readers rate a past sunrise or sunset in the location callout (`docs/engineering/outcome-rating-plan.md`); the older free-text `POST /api/outcome` stack has had none since 2026-02-27 (`docs/engineering/v1-retirement-plan.md` §8).

---

## ⚠️ Keep this file small

This file is loaded into **every** session and every subagent, so each kilobyte here is paid on every turn. It
grew to 481 KB (about 120k tokens) by October 2026 and was cut back on 2026-10-22. The detail did not go away:
it moved **verbatim** into `docs/engineering/reference/` (index below), and the per-feature plans in
`docs/engineering/*-plan.md` remain the record of each series. Code comments and plans that cite
"CLAUDE.md's <section/rule>" (about 240 files) mean that same text, now in `docs/engineering/reference/`:
grep there for the phrase.

**Rules for editing it:**
- Put feature behaviour, history, review findings, "round N" corrections and ⚠️ war stories in the matching
  `docs/engineering/reference/*.md` file or the feature's plan, **not here**.
- Add a line here only for something a session must know *before it opens any file* to avoid breaking
  things: a cross-cutting invariant, a command, a safety rule. One or two lines, then a pointer.
- If you are tempted to write more than a paragraph here, it belongs in a reference doc.

---

## ⚠️ How every code change is made (owner rule, 2026-10-22)

**Opus and Fable plan and review; Sonnet writes all the code.** An Opus or Fable session never edits
source, tests, build files or migrations itself, not even a one-line fix. (Docs-only edits such as
plans and this file may be written by the planning session.)

1. **Plan, then review the plan adversarially.** Read the code, write the plan (a multi-phase series
   gets a plan doc under `docs/engineering/`), and review it with agents sized to the risk: one or two
   for a one-file fix, about six lenses plus refuters for a cross-cutting or UI change (see
   *UI Work — Review Cadence*).
2. **Delegate the build to a Sonnet subagent**: the Agent tool with `model: "sonnet"` and its own
   worktree (never `subagent_type: "fork"`, which ignores the model). The brief carries the plan, the
   files, the tests to write, the local gates (the build ladder below and the frontend's four
   commands) and the `changelog.d/` entry. Sonnet runs the gates until green. The planner reviews the
   diff read-only and sends any fixes back to Sonnet.
3. **Commit** on a feature branch (conventional commit, changelog entry).
4. **Push the branch and open a ready PR** (not a draft) against `main`.
5. **Wait for CI and Codex, and fix what they find** (through Sonnet, as in step 2), then push again.
   Codex reviews every PR automatically: a 👍 reaction on the PR is a clean review, 👀 means it is
   still running (never merge on it), and comments are findings. On a later round, judge only
   comments and reviews on the current head commit; ask for another round with an `@codex review`
   comment.
6. **Merge (squash) only when CI is green and Codex has given 👍 on the exact head commit.** Re-read
   both immediately before `gh pr merge`.
7. **Multi-phase plans repeat steps 2–6 for each phase**: one PR per phase, each branched from the
   updated `main` after the previous phase merged.

Still never done by a session: creating or deleting tags, cutting a release (the owner runs
`release.sh`), changing prompt-regression assertions, pushing directly to `main`.

---

## Reference index — read the file for the area you are touching

| Area | File |
|---|---|
| Core pipeline, scoring, evaluation, command pattern, optimisation strategies, cost tracking, job metrics and per-location failure tracking, resilience, batch cache primer | `docs/engineering/reference/pipeline.md` |
| Daily Briefing, the Plan tab matrix (cards, popup, location sheet, dialog stacking), Plan-screen confidence, `VerdictSampleGate`, neighbour guard | `docs/engineering/reference/plan-tab.md` |
| Map tab (v2), locations, thin-sample hatch, verdict and picks, phone peek sheet, past windows, "How was it?" rating | `docs/engineering/reference/map-tab.md` |
| Ask PhotoCast (Ready answers, engine, typed guards, thread, night tool, client) | `docs/engineering/reference/ask-photocast.md` |
| Aurora (polling, state machine, banner, viewline, simulation) | `docs/engineering/reference/aurora.md` |
| Tide runs, lunar eclipse, tide data, storm surge | `docs/engineering/reference/hot-topics-tides-eclipse.md` |
| Wildlife comfort rows, waterfalls, cloud inversion, valley mist, astro conditions | `docs/engineering/reference/conditions.md` |
| Auth, user settings and drive times, dynamic scheduler (solar offset, watchdog, evening refresh), admin features and Rewind, deployment | `docs/engineering/reference/platform.md` |
| Key architecture decisions (Jackson graphs, backend-heavy and the licensed client derivations, cloud sampling, virtual threads, tide axes, hot topics, Gate 4, `daysAhead`, honesty filter …) | `docs/engineering/reference/architecture-decisions.md` |
| Where a rating lives (`cached_evaluation` / `forecast_score` / `forecast_evaluation`), retraction, submission-order writes | `docs/engineering/reference/ratings-stores.md` |
| Every API endpoint, with its role gate and status codes | `docs/engineering/reference/api-endpoints.md` |
| Roles, freemium split, role gating UI pattern, planned features | `docs/engineering/reference/roles-and-gating.md` |
| Notable migrations | `docs/engineering/reference/database-migrations.md` |
| Configuration keys (`photocast.*`, scheduler, Ask) | `docs/engineering/reference/configuration.md` |
| Dev setup, the no-Docker rule in full, test standards, UI review cadence, build ladder, solar-utils | `docs/engineering/reference/dev-and-testing.md` |

Search them with `grep -rn '<ClassName or term>' docs/engineering/reference/` rather than reading whole files.

---

## Cross-cutting invariants (each is expanded in the reference docs)

- **The API serialises with an auto-configured Jackson 3 (`tools.jackson`) mapper.** `AppConfig.objectMapper()` is Jackson 2, used internally only, and is not in the HTTP response path. Wire format is pinned by `JsonDateFormatContractTest`.
- **Backend-heavy.** The frontend is a render layer. The few client-side derivations are a *named, licensed* list in `architecture-decisions.md` ("Backend-heavy"); read it before adding one. Never re-derive a served verdict or Best pick on the client.
- **Personal data never rides an ETag-shared payload** (`/api/briefing`, `/api/almanac`, `/api/ask/ready` …). Per-user data lives under `/api/user/settings*` and `/api/outcome/ratings*`, which `HttpCachingConfig` never caches; a new personal path must be added to `HttpCachingConfigTest.personalDataPathsAreNeverFiltered`.
- **"Today" and `daysAhead` are the UK civil date**, derived only through `util/ForecastHorizon` with an injected `Clock`. Client clock reads go through `appNow()` (Rewind).
- **Three rating stores, one is a different engine.** The UI shows `cached_evaluation`; `forecast_evaluation` is the synchronous engine's insert-only table and is load-bearing (do not "clean it up"). Writes are ordered by submission, never arrival. See `ratings-stores.md`.
- **The two-question rule.** Hot topics and Coming up answer "what is happening" and are never silenced by a stability skip or triage stand-down; ratings answer "where is worth going" and are retracted. Hot topics are recomputed live on every serve, so build-time enrichment rides an in-memory carrier, never the briefing cache.
- **Tide axes.** "King" is lunar only; never OR a height test into the king label. Spring qualifies on either axis. The map's tide question is `tideAligned` (preference), never `tideOnTheLight`.
- **Column-scoped writes.** `app_user` settings and credential columns, and `locations.enabled` / failure columns, are `updatable = false` and written only by scoped repository updates. `setX()` + `save()` on an existing row writes none of them.
- **Adding an enum value to an `@Enumerated(STRING)` column** needs a line in `LocalH2EnumWidener`'s `TARGETS` so existing local H2 files accept it. Ask's night windows use Ask-local `AskWindowKind`, never a new `TargetType`.
- **No marker clustering, anywhere, at any scale.** If density ever bites, the answer is canvas rendering.
- **Simulations never reach user surfaces** (aurora and hot-topic simulation are gated by `isSimulated()` / `simulated` columns).
- **Dialogs**: at most two stacked; every route out of the Plan tab closes its dialogs via `selectTab`; all Map Escape/outside-press rules read `utils/mapForeignModal.js`; `.wf-map-tab` is a selector (`MAP_PANE_SELECTOR`), not just CSS.
- **Phone-only work stays phone-only** (`hooks/useIsMobile.js`, `(max-width: 639px)`); desktop and tablet must be provably unchanged.
- **Which model runs a run type is the database `model_selection` row**, not the code. Production's Batch Near-Term and Far-Term run on Sonnet 5.5.
- **Ask PhotoCast is off by default** (`photocast.ask.enabled`). `stub: true` under `prod` fails startup; turning `stub` off locally bills the real Anthropic key.
- **Local forecast runs and briefing builds can spend real Anthropic money.** Do not trigger them casually.
- **Production is a separate Linux host.** Never diagnose production from local logs or the local DB.

---

## Monorepo Structure

```
goldenhour/
├── backend/               Spring Boot 4 (port 8082 local; 8083 with -Plocal-dev)
│   ├── src/main/java/com/gregochr/goldenhour/
│   │   ├── client/        @HttpExchange interfaces (Open-Meteo)
│   │   ├── config/        Security, JWT, retry predicates, cost config
│   │   ├── controller/    REST controllers
│   │   ├── entity/        JPA entities + enums
│   │   ├── repository/    Spring Data repos
│   │   ├── service/       Business logic, command/strategy patterns, evaluation/
│   │   ├── model/         AtmosphericData, DTOs, records
│   │   └── util/          GeoUtils, ForecastHorizon
│   ├── src/local/java/    local-only beans (-Plocal-dev): LocalH2EnumWidener, AskLocalFixtureSeeder
│   └── src/main/resources/
│       ├── application.yml          (gitignored)
│       ├── application-example.yml  (committed)
│       ├── application-local.yml    (H2 local dev)
│       ├── application-prod.yml     (production)
│       └── db/migration/            Flyway migrations (latest: read from the tree)
├── frontend/              React 19 + Vite (port 5173)
│   └── src/
│       ├── api/           Axios API modules
│       ├── components/    UI components
│       ├── hooks/         Custom hooks
│       ├── utils/         Pure view-model helpers (most client logic lives here)
│       └── context/       AuthContext, AskContext
├── docs/engineering/      Plans per feature series + reference/ (moved CLAUDE.md detail)
├── changelog.d/           One file per change; folded into CHANGELOG.md at release
└── CLAUDE.md
```

---

## Dev Setup

The backend builds and runs on **JDK 25** — point `JAVA_HOME` at one.

```bash
# Backend (H2, no Docker) — local-dev beans, port 8083
export ANTHROPIC_API_KEY=your-key
cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local

# Frontend
cd frontend && npm run dev
```

Default local credentials: `admin` / `golden2026`. H2 console: `http://localhost:8083/h2-console` (JDBC `jdbc:h2:file:./data/goldenhour`, user `sa`, empty password). Reset by deleting `backend/data/goldenhour.mv.db` and `.lock.db`.

### ⚠️ There is no Docker on this machine, and that will not change

Do not tell anyone to start Docker Desktop. The `IntegrationTestBase` Testcontainers classes (under `src/test/java/.../integration/`) fail with `Could not find a valid Docker environment`, so **every local backend test run carries the exclusion**. Local profiles never run Flyway, so **migrations are proven only in CI's `Backend — Build, Test & Coverage` job**: write the Testcontainers test, run the local gate, open the PR, read CI. Write migrations for Postgres; never contort them for H2. Read the latest migration number from the tree: `ls backend/src/main/resources/db/migration/ | sort -V | tail -1`.

### The build ladder (gate on Maven's exit code, never on a grep of its output)

```bash
cd backend && ./mvnw compile -q                                   # seconds
cd backend && ./mvnw test -Dtest=SomeClassTest -q                  # one class
cd backend && ./mvnw checkstyle:check >/tmp/cs.log 2>&1; echo "exit: $?"   # ~15 s
cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress \
  -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"
```

The last command is the local equivalent of CI's backend job (it reaches `jacoco:check`, 80% line coverage **per class**, and `spotbugs:check`). Run Maven in the foreground with a timeout, not as a background task.

Frontend CI is lint → Vitest → `npm audit --audit-level=high` → build; run all four before pushing:

```bash
cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build
```

When `npm audit` fires, edit the lockfile surgically rather than `npm audit fix`, check `package.json`'s `overrides` pin first, and prove it with `rm -rf node_modules && npm ci`. Details are in `dev-and-testing.md`.

Prompt regression tests: `cd backend && ANTHROPIC_API_KEY=... ./mvnw test -Pprompt-regression` (real API, costs money).

---

## Test Standards

Read the standards for your side before writing or modifying any test class:

| Side | Document |
|---|---|
| Backend (Java, Mockito, JUnit) | `docs/engineering/test-improvement-standards.md` |
| Frontend (React, Vitest, Testing Library) | `docs/engineering/frontend-test-standards.md` |

⚠️ **When mutating source to prove a test can fail, never restore with `git checkout --` / `git restore`.** It silently deletes uncommitted work in that file. Back up and restore with `cp`, commit first, or mutate in a separate worktree.

---

## UI Work — Review Cadence

Every commit that touches the UI gets an **adversarial review before it lands**: build → tests → adversarial review of the diff → fix what survives → re-verify → commit. Verify in the browser where possible (local backend on port 8083) and say plainly which claims were seen and which were only tested. ⚠️ **Review agents must never write to the working tree**; give anything that mutates source its own worktree. Full rationale and the agent shape are in `dev-and-testing.md`.

---

## Roles (summary)

`ADMIN` (everything, Operations tab) · `PRO_USER` (enhanced scores, aurora, past-window ratings, drive times) · `LITE_USER` (basic scores; read-only forecast). New UI must be assessed for role gating: admin-only features are hidden; PRO features show LITE a "Pro" pill and open a worded upsell (greyed only where too small to carry one; never a link; `/upgrade` is not a route). Backend 403 is the real enforcement. See `roles-and-gating.md`.

---

## Code Standards

### Backend
- Checkstyle: every public type, method and constructor in `src/main` must carry Javadoc (`MissingJavadocType`/`MissingJavadocMethod`; bean getters/setters and `@Override` methods exempt, test sources exempt via `backend/checkstyle-suppressions.xml`, which the pom's `suppressionsLocation` finds from any directory and `backend/Dockerfile` must `COPY`), no unused imports, 4-space indent, 120-char lines
- SpotBugs: `High` threshold (`<threshold>High</threshold>` in `backend/pom.xml`), FindSecBugs plugin, bound to `verify`
- No business logic in controllers; no magic numbers; graceful error handling

### Frontend
- ESLint + Prettier | `data-testid` on key elements | Tailwind only (no inline styles) | PropTypes on all components

### Commits
Conventional commits: `feat:`, `fix:`, `chore:`, `test:`, `docs:`, `refactor:`

---

## Git Conventions

- Never commit `application.yml` — only `application-example.yml`
- `.gitignore`: `application.yml`, `*.env`, `node_modules/`, `target/`, `backend/data/`, `.claude/`
- Branch naming: `feature/`, `fix/`, `chore/`
- Every meaningful commit adds a changelog entry as its **own file**: `changelog.d/YYYYMMDD-<slug>.md`, opening with a `### Category — title` heading (see `changelog.d/README.md`). **Never write under `CHANGELOG.md`'s `[Unreleased]` directly** — that line is where every pair of open PRs used to conflict; `release.sh` folds the entry files into `CHANGELOG.md` at release time
- **Push only feature branches, as part of the workflow above** (push, open a PR, merge once CI is green and Codex has given 👍). Never push directly to `main`, and never force-push a branch someone else is working on.
- **NEVER create or delete git tags** — tags mark tested, confirmed releases. Wait for the user to instruct this after real-world testing.
- **NEVER change assertions in prompt regression tests** (`src/test/java/.../regression/`) — these encode ground-truth expectations against real Claude output and must only be updated by the user.
- When you change behaviour described in a `docs/engineering/reference/` file, update that file in the same PR.
