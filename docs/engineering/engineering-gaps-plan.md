# Closing the "Where it falls short" gaps

Status: planned 2026-11-07. Owner decisions recorded below. Phases 1 and 2 are in progress.

`landing/engineering.html` (`#falls-short`) and `landing/how-its-built.html` (`#improving`) list five gaps.
This plan closes the ones worth closing, in the order below, and changes the copy only once each fix is
live. The copy follows the voice in `how-its-built-plan.md`: warm, straightforward and measured, in British
English, without overclaiming.

| # | Gap as published | Plan |
|---|---|---|
| 1 | Mutation testing covers the server only | **Out of date.** Stryker now tests `frontend/src/utils` weekly (`frontend-mutation-testing-plan.md`). Phase 1 corrects `engineering.html`. |
| 2 | The real-browser tests run by hand | Phase 2: a Playwright smoke suite in CI, **a required check**. Phase 3: seeded data, so the Map and Plan specs run too. |
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
merge. Reliability is a design requirement, not something to tune afterwards.

### Shape
- **Where it runs:** a new job, `e2e`, in `.github/workflows/ci.yml`. It runs on `ubuntu-latest`, never the
  self-hosted runner, which is shared with production. It `needs: changes` and skips its expensive
  **steps** on a docs-only diff, exactly as the `backend` and `frontend` jobs do. The job therefore always
  reports, which a required check needs.
- **The stack:**
  - **Backend:** build the jar with `./mvnw -Plocal-dev -DskipTests package` and start it on the `local`
    profile, on a fresh H2 file in the job's workspace.
  - **Frontend:** `npm run build`, then `vite preview` with `VITE_API_TARGET` pointing at the backend.
    That serves built code, which is closer to production than the dev server.
  - **Startup:** wait on `/actuator/health`, with a timeout and logs printed on failure.
- **No spend, by construction:**
  - The job is given **no secrets**. `ANTHROPIC_API_KEY` is set to an obviously fake value, and the
    WorldTides and mail keys stay empty.
  - Ask stays on its stub (the local default). No forecast run or briefing build is triggered.
  - Any accidental Anthropic call fails with a 401 rather than spending money.
- **Login:** the specs log in through `/api/auth/login` (as `forecast.spec.js` does) with the local default
  admin. Check how the Turnstile widget behaves when `turnstile.secret-key` is empty. If the login form
  needs a site key, the specs that exercise the form must account for it.

### Reliability rules (these are what make it fit to block)
1. **No third-party network.** In a Playwright fixture shared by every spec, `page.route` stubs:
   - map tiles (`server.arcgisonline.com`) with a 1×1 PNG;
   - Turnstile (`challenges.cloudflare.com`);
   - anything else off-origin.

   Any unexpected off-origin request fails the test, so a new third-party dependency is noticed.
2. **No fixed sleeps.** Only web-first assertions (`expect(locator).toBeVisible()` and so on) and
   `waitForResponse`. No `waitForTimeout`.
3. **The clock is frozen** with `page.clock`, at a fixed UK date, wherever a spec depends on "today".
4. **Chromium only** in the required job. Pin the Playwright version exactly, not with a caret, and keep
   the browser install cached on that version.
5. **Retries: 0 in the required job.** A retry hides flakiness, which is the thing a gate must not tolerate.
   `trace: 'retain-on-failure'`, with the report and traces uploaded as an artifact when the job fails.
6. **Console errors fail the test.** A fixture collects `console.error` and `pageerror` events. Each spec
   ends by asserting there were none, apart from a named allow-list, which should be empty to begin with.
   This catches the class of bug the owner saw on logout.
7. **Time budget:** the whole job runs under 8 minutes and the specs under 2.
8. **Proof before it becomes required:**
   - The PR runs the suite with `--repeat-each=20` in a temporary step, and it must pass every time.
   - The step is then removed before merge.
   - The repeat-20 result is posted on the PR.

### What the smoke suite covers (empty database, no ratings)
- **Login and logout:** the login form renders and the admin logs in. Logging out returns to the login form
  with no console errors.
- **Each top-level tab renders:** Map, the Plan matrix (*At a Glance*), Coming up, and Operations (as
  admin). Each shows its own container `data-testid`, and the empty states draw without errors.
- **The Map pane:** the Leaflet container draws and the tile layer is requested, though stubbed.
- **Phone width** (`devices['iPhone 13']` viewport on Chromium): the tab bar works and the Map's phone peek
  sheet does not cover the tabs. Use only checks that hold with no data.
- **Ask PhotoCast:** check whether it is enabled locally. If it is, the box opens and a stubbed answer
  renders. If it is off by default, leave it out of the suite.

### Existing specs
- `forecast.spec.js`: keep the specs that pass on an empty database in the new suite and move the rest to
  Phase 3. List which went where in the PR.
- `door1-obstacles.spec.js`: needs seeded ratings, so it moves to Phase 3.
- **Separate configs:** put the required suite under `src/test/e2e/smoke/`, with its own
  `playwright.smoke.config.js` (retries 0, Chromium, preview server). Add a `test:e2e:smoke` script. The
  existing config and its local recipe stay as they are.

### After merge (owner)
Add **"E2E — Playwright smoke"** to the branch-protection required checks, as was done for OWASP.

### Docs
Update `docs/engineering/reference/dev-and-testing.md` with the new gate, how to run it locally, and the
reliability rules above, plus CLAUDE.md's frontend-commands line (one line). Changelog entry.

---

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
