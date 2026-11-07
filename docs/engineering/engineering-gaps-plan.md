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

## Phase 3: seeded data, so the Map and Plan specs run in CI

- Add a local-only seeder (`src/local/java`), switched on by a property such as
  `photocast.e2e.seed-fixture`, with the same pattern as `AskLocalFixtureSeeder`.
- **What it writes:**
  - a handful of locations;
  - rated windows across six or more days (the stores the UI reads, per `ratings-stores.md`);
  - a served briefing built **from the stub**, with no Anthropic call.
- **What then joins the required suite:** a Plan popup opens and closes, the location sheet opens, the Map
  callout shows a rating, dialogs stack at most two deep, and Escape closes them. `door1-obstacles.spec.js`
  joins too, now that it has real layout to hit-test.

The details are planned when Phase 2 has merged.

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
