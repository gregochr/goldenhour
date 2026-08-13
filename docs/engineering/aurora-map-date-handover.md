# Make the map show the aurora night you just ran

Left open by #496, which fixed the backend half. Recorded in
`docs/engineering/aurora-night-selection.md` under "Open — the map does not default to the night you
just ran".

## Background

Four merged PRs (#492, #493, #494, #496) moved this app's date handling onto deliberate calendars:

- #492–#494 — `daysAhead` and every forecast/almanac "today" now derive from the **UK civil date**
  via `util/ForecastHorizon.today(clock)`, with a `Clock` injected from `AppConfig`.
- #496 — the aurora **night** is different, and the one place a calendar is the wrong tool at all.
  A night runs dusk on D to dawn on D+1, so between midnight and dawn you are inside the window
  named *yesterday*. `AuroraForecastRunService.currentNightDate()` now decides on an **instant**
  (`now.isBefore(nauticalDawn) → yesterday`), mirroring `AuroraPollingJob.calculateTonightWindow()`.
  Read that class's javadoc before starting; it explains why no timezone fixes this.

So the backend now correctly calls the night in progress "Tonight" and stores its results under
**yesterday's date** when you are in the small hours. The frontend was not updated to match.

## The defect

Verified against `origin/main` at `31187f44`. **Re-verify the line numbers — they will drift.**

Run an aurora forecast at 02:00. The backend scores the night in progress, stored under D−1. Then:

1. **`frontend/src/App.jsx:227`** — the map's initial date:
   ```js
   const defaultDate = allDates.find((d) => d >= todayStr) ?? allDates[allDates.length - 1] ?? null;
   ```
   `todayStr` is `App.jsx:221`, `new Date().toISOString().slice(0, 10)`. So the map opens on D and
   the forecast you just paid for is not shown. It *is* reachable — `ForecastController.PAST_WINDOW_DAYS = 2`
   puts D−1 on the strip and `DateStrip.jsx` renders past chips dimmed but clickable — so this is a
   wrong default, not missing data.

2. **`frontend/src/components/MapView.jsx:1545`** — clicking back to D−1 to find the results then
   suppresses the aurora viewline overlay:
   ```js
   {viewlineEnabled && eventType === 'AURORA' && date === new Date().toLocaleDateString('en-CA') && (
   ```

3. **`frontend/src/App.jsx:293`** — the "show on map" jump for aurora goes to today explicitly:
   ```js
   handleShowOnMap({ kind: 'aurora', date: todayStr });
   ```
   The review that found (1) and (2) did not mention this one. Check for others; do not assume this
   list is complete.

### ⚠️ There is a second, independent bug in the same code, and it is the same family

The frontend derives "today" **two different ways**:

- `App.jsx:221` — `new Date().toISOString().slice(0, 10)` → the **UTC** date.
- `MapView.jsx:438` and `:1545` — `new Date().toLocaleDateString('en-CA')` → the **browser-local** date.

For a UK user under BST these disagree between 23:00 and 00:00 UTC — 00:00–01:00 BST — which is
exactly the window this task is about. Establish whether that is real before acting on it (a two-line
scratch script in a browser console or Node settles it), then decide whether fixing it belongs in
this change or a separate one. Do not silently "tidy" one to match the other: pick the right basis
and say why. This is the same two-calendars defect #492 removed from the backend.

## The design question — raise it, do not assume

The backend knows which night is current; the frontend does not. `GET /api/aurora/forecast/preview`
returns `AuroraForecastPreview(List<NightPreview> nights, boolean simulated)` and
`NightPreview.date` — `nights[0].date` **is** the current night. But that endpoint is ADMIN-shaped
(it drives the run modal) and fetching it just to pick a default date may be the wrong dependency.

Options worth weighing before writing code:

- Have the map default to the aurora night when aurora results exist for D−1 and not for D.
- Expose the current night on an endpoint the map already calls (e.g. alongside `GET /api/aurora/status`).
- Derive it client-side from the same dusk/dawn rule — **probably wrong**: it duplicates solar
  geometry the backend owns, and #496's whole point is that this rule should have one home.

There is also a genuine product question you should put to the user rather than decide: should the
map default to the aurora night *only* when the user has just run one, or whenever the current night
is D−1? The first is narrower and less surprising; the second is more consistent. Ask.

## What NOT to do

- **Do not change `AuroraForecastRunService.currentNightDate()` or its callers.** That is settled and
  tested; this task is the client half.
- **Do not point anything at `ForecastHorizon`.** `ForecastHorizon.today` is the UK *civil* date and
  is the wrong answer for a night — see its javadoc, which says so explicitly.
- **Do not widen `PAST_WINDOW_DAYS`.** The date is already on the strip; the default is what is wrong.

## Test requirement

Frontend tests are Vitest + Testing Library; read `docs/engineering/frontend-test-standards.md`
first (it shares a philosophy and almost no mechanics with the backend one).

⚠️ **Freeze the clock in any test whose assertion depends on the date.** Use
`vi.useFakeTimers({ toFake: ['Date'] })` + `vi.setSystemTime(...)`. This project has now been bitten
by wall-clock-dependent fixtures three times in two days, from both directions:

- A backend `Clock.fixed` that resolved to the **real current date** made broken code agree with the
  test — a mutation survived (#493).
- `HotTopicStrip.test.jsx` had a literal `2026-08-12` asserting the lead reads "Today". It went red
  on `main` overnight and blocked an unrelated PR (#496). The same file's timing-lead block already
  froze the clock; that block just had not.

Pick an instant in the small hours (so the current night is D−1) and assert the map's initial date
is the night with results, not the calendar date. A test that passes because it happens to run
before midnight is worthless.

## Browser verification — required, not optional

`CLAUDE.md` mandates it: **every commit touching the UI gets an adversarial review and a browser
check before it lands**, and the project's own note records that browser runs have repeatedly found
defects a green suite, clean lint and successful build all passed over.

```bash
cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local
```
Backend on port **8083** for that profile (not the 8082 quoted elsewhere); frontend `npm run dev`;
sign in as `admin` / `golden2026`.

⚠️ A local H2 DB with no evaluation run has **no aurora results**, so the interesting state needs a
fixture. Say plainly which claims you saw in a browser and which you only reasoned about — do not
imply otherwise.

## Environment

- **`jitpack.io` is blocked (403)** — `solar-utils` will not resolve. It is already installed at
  `~/.m2/repository/com/github/gregochr/solar-utils/2.1.0/`; if that is missing, copy the vendored
  jar from `backend/.m2/repository/com/gregochr/solar-utils/2.1.0/` and write a minimal pom with
  groupId `com.github.gregochr`. Maven Central is reachable.
- **No Docker**, so the 5 `IntegrationTestBase` classes cannot run locally. Exclude them and say so:
  `./backend/mvnw -f backend/pom.xml verify -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`
- **Gate on the exit code, never on a grep of the output.** With `-q` violations are suppressed and
  `$?` after a pipeline is grep's, not Maven's. This has reported a false green here before.
- **`npm run test` is not the frontend CI job.** That job is lint → Vitest → `npm audit
  --audit-level=high` → build. Run all four before pushing:
  ```bash
  cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build
  ```

## Process (from CLAUDE.md — binding)

- Work in a fresh git worktree off the current `origin/main` tip; fetch first.
- Conventional commits. CHANGELOG entry in user-facing prose — there is a real user-visible effect
  here.
- Update `docs/engineering/aurora-night-selection.md`: move this item out of "Open" and record what
  was done, including anything you decided *not* to do.
- **Run an adversarial review of the diff before proposing a merge.** Reviewers must be told to read
  only — a reviewer on this project once probed by mutating source and `git checkout --`'d away
  uncommitted work. If you mutation-test, back up with `cp` and restore with `cp`.
- Create the PR, watch CI, fix what surfaces, merge once every check is green.

## Also open in this area (do not fix here unless asked)

- `AuroraForecastRunService.buildKpSummary` formats the peak Kp block without comparing it to now, so
  at 02:00 the modal can read "Kp 6 expected 00:00–03:00" with two of those hours gone.
- `WeatherTriageService` reads the next `TRIAGE_LOOKAHEAD_HOURS` (6) from now, so from mid-afternoon
  "real" triage measures cloud outside the window it judges. Pre-existing.
- `DURHAM_LAT` / `DURHAM_LON` / `NAUTICAL_BUFFER_MINUTES` are declared independently in up to four
  classes; the two night rules agree only by coincidence of those constants matching.
