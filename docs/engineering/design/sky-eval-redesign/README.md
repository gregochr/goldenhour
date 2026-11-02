# Handoff: Sky Eval redesign (Operations → Tuning → Sky Eval)

## Overview
Sky Eval is the test harness for sky-rating accuracy. It runs fixed fixtures through each Claude model and checks whether the ratings land inside each fixture's expected band. The redesign is built around three jobs:

1. **Which model to use in production.** Two production lanes:
   - **Sonnet 5.5** rates **T and T+1**. It must be as accurate as possible.
   - **Haiku 4.5** rates **T+2 and T+3**. It's cheaper and needs to be accurate enough to plan with.
2. **Did a prompt or code change make ratings worse?** Every eval run is tied to a version (commit).
3. **Kick off a quick manual check** after a change.

It replaces the current screen (`screenshots/00-current-screen.png`), which has three rows of model checkboxes, a five-line spaghetti chart, eight small-multiple drift charts and a flat list of 62 runs.

## About the design files
`prototype/` contains **design references built in HTML and vanilla JS**. Recreate them in the PhotoCast admin codebase using its framework, components and data layer. All data is **synthetic**: a seeded generator in `sky-eval.js` sets "now" to **Mon 2 Nov 2026 09:26**. Open `sky-eval-redesign.html` to click through it.

## Fidelity
**High fidelity.** Colours, type, spacing and copy are final, matching the PhotoCast dark Operations skin and the Runs deep-dive bundle. The charts are hand-rolled SVG. Use the codebase's chart library if one exists, but keep the encodings described below.

## Screenshots
- `00-current-screen.png`: the current screen, for reference.
- `01-models.png`: Models tab, the default view.
- `02-fixtures-haiku55-drift.png`: Fixtures tab for Haiku 5.5, with a fixture expanded to its rating history.
- `03-fixtures-sonnet55.png`: Fixtures tab for Sonnet 5.5.
- `04-runs-sessions.png`: Runs tab, with the latest session expanded.
- `05-run-a-check.png`: the Run a check panel open.

---

## Page header
- Title "Sky Eval" (Newsreader 600 26px). On the right is the primary button **Run a check**.
- The sub-line reads: `Sky-rating calibration · 10 fixtures · weekly, Sun 04:00, on Sonnet 5.5, Haiku 4.5, Haiku 5.5 · Edit in Scheduler`.
  - This **replaces the "Weekly run" checkbox row**. Weekly model selection moves to the Scheduler.
- Tabs are underline style: **Models** · **Fixtures** `10` · **Runs** `20 sessions`. The selected tab persists.
- The old "Show on chart" checkboxes are replaced by the clickable model chips on the chart.

## Run a check (panel under the header)
- **Models**: multi-select chips; production models carry a `PROD` tag. The default and the "Production pair" shortcut both select Sonnet 5.5 and Haiku 4.5.
- **Runs per fixture**: `1` · `3 · quick` (default) · `8 · same as weekly`.
- **Fixtures**: `All 10` · `Missed last time · n` (fixtures with any miss in the most recent session).
- **Version**: the current HEAD commit id, its message and "pushed 2h ago". The check always runs on HEAD.
- Footer: `Estimate ~£0.04 · ~2m 15s · 60 ratings`, then Cancel and **Run check**.
  - Cost = Σ model cost per weekly run × (fixtures / 10) × (runs / 8).
  - Time = the slowest model, because models run in parallel.
- When you submit, the panel closes, the view switches to **Runs**, and a new session appears expanded with a progress bar per model. When the runs finish, every chart updates.
- Other places open this panel prefilled: the "Run a check" link on a lane that hasn't been checked on HEAD, and "Check them on a91b0e2" for stale models.

---

## Models tab

### 1. Verdict card
The headline (Newsreader 600 22px) is computed for the HEAD version against each production model's last runs on the previous version:
- If any production model dropped 3 or more points: "a91b0e2 made Haiku 4.5 worse."
- If a production model hasn't run on HEAD: "a91b0e2 hasn't been checked on Sonnet 5.5 yet."
- Otherwise: "Both production models held up on today's change."

Body: "**Prompt: stricter cirrus penalty** (a91b0e2, 2h ago). Sonnet 5.5 97.5% → **99.4%**, Haiku 4.5 93.8% → **91.9%**." Any non-production model that dropped gets "…but it isn't used in production." appended.

### 2. Production lane cards (two-up, stacked under 1000px)
Each card shows:
- Lane label (mono caps), model name (serif 22px with swatch) and the lane's purpose.
- A four-cell stat strip:
  - **Today's version**: % in band on HEAD, with "2 runs · a91b0e2" underneath.
  - **Last 4 weekly**: % in band.
  - **Misses, last 4**: `1 below 3 above`, with "of 320 ratings".
  - **Per eval run**: £.
- A status bar with three states:
  - Green "Held after a91b0e2: 97.5% → 99.4% over 2 runs."
  - Coral "Dropped −5.2 pts after a91b0e2 (95.6% → 90.4%)." with a "See which fixtures" link to the Fixtures tab for that model.
  - Amber "Not checked on a91b0e2 yet." with a "Run a check" link.
- **Compared with, last 4 weekly**: one row per other model tested in the last 21 days, showing model (PROD tag if relevant), % in band, points difference (green if better, amber if worse) and cost multiple ("0.15× cost"). If that model fell 3 or more points on HEAD, add an amber sub-line: "Fell to 90.4% on a91b0e2".
- A footnote for stale models (not tested in 21 days): "Opus and Sonnet 4.6 last tested 29d ago on an older version." with a link to check them on HEAD.

### 3. Accuracy over time
- y axis 70–100% in band; anything lower is clamped. The x axis covers the last ~125 days.
- One line per model, with one point per session (the mean of that model's runs in the session).
  - Production models are drawn in their colour at 2px. Other models are grey at 1px and 60% opacity.
  - Clicking a model chip (or a row in the All models table) highlights that model instead.
- Manual-run points are **hollow**; weekly-run points are filled.
- **Versions** are gold dashed vertical lines, labelled with a 4-character commit id (staggered). Hovering a label shows the full id, message and date.
- Point tooltip: model · %, date · trigger · n runs, and version.

### 4. "Did a change make ratings worse?"
- Version chips list the last 5 versions that have runs both on and before them. HEAD is selected by default.
- For each model:
  - **Before** = the model's last 2 runs on the most recent earlier version it ran on.
  - **After** = all its runs on the selected version.
- Columns: Model (PROD tag) | Before → After | Change in points (coral if −3 or worse, green if +3 or better) | **Fixtures that moved**.
- A fixture "moved" if its in-band share changed by 20 points or more. Each one shows as "worse/better copt-hill-16mar-horizon-strip 94% → 67% in band · rated too low".
- Models with no runs on that version show "Not run on this version".
- Production models are listed first.

### 5. All models table
- Columns: Model | Used for (PROD lane tag or "eval only") | Last 4 weekly (% with bar: green ≥ 97, amber ≥ 92, coral below) | On HEAD | Misses ↓/↑ | £/run | Last tested.
- Fresh models are sorted first, then by accuracy. Stale rows are dimmed and marked "older version".
- Click a row to highlight that model in the chart.

---

## Fixtures tab
- Model segmented control (5 models, default Sonnet 5.5). The "See which fixtures" and "Fixtures →" links preselect a model.
- **Grid**: rows = fixtures, columns = that model's last 13 runs (oldest on the left).
  - Row header: fixture id (mono) and `band 1–2`. The right-hand column shows the last-4 in-band % (coral below 90%).
  - Rows are sorted by misses in the last 4 runs, worst first.
  - Column headers show the date vertically, with "·m" for manual runs. Where the version changes between columns there's a 2px gold left border and the 4-character commit id above.
- Cells (30×26, radius 4):
  - All ratings in band: soft green `rgba(138,174,114,.2)`.
  - Misses: text `↓n` and/or `↑n` (out of runs per fixture). The background is **tide blue** when mostly below the band and **coral** when mostly above. Alpha = .18 + .6 × miss ratio.
  - The fixture didn't exist yet: `·` with no fill.
  - Tooltip: fixture, date, version, in-band count, below/above counts, mean rating and band.
- A legend sits above the grid.
- **Click a row** to expand a rating-history chart under it.
  - Shaded band (lo−0.5 to hi+0.5), y axis 1–5, and version lines.
  - One dot per run at the mean rating, with a vertical line from min to max across that run's repeats. The dot is in the model colour when all ratings are in band, blue when mostly below, coral when mostly above.
- The grid scrolls horizontally if needed and is designed to work at 30 fixtures.

## Runs tab
- **Sessions**, not individual runs: runs with the same trigger and version that started within 40 minutes of each other are grouped. 62 runs become about 20 rows.
- Columns: When (relative time, with day and time beneath) | Trigger (`WEEKLY` / `MANUAL` tag) | Version (commit tag and message) | Models · in band | Cost | Status (Done / Failed / Running, pulsing tide dot).
  - Models · in band shows a chip per model, e.g. "Haiku 5.5 ×3 90.4%". The % is ink at 97 or above, amber at 92 or above, coral below.
- Click a session to expand the per-run table:
  - Columns: Model #id · fixtures × repeats | In band | Below / above | Cost | Time | "Fixtures →".
  - Failed runs show the error ("Batch API timed out"). Running runs show a progress bar.
- Shows the 10 most recent sessions, then a "Show n older sessions" button.

---

## Definitions
- **Rating**: the model's 1–5 sky score for a fixture. Each run rates each fixture *n* times (runs per fixture, 8 weekly).
- **In band**: the rating is within the fixture's expected band (e.g. 1–2). Pass rate = in-band ratings ÷ all ratings.
- **Below / above** (was DOWN / UP): the rating was under or over the band. Both are equally bad; the colour only shows the direction, to help diagnose.
- **Version**: the git commit of the prompt and scoring code when the run started. The old Commit column was always empty, so **this needs wiring**.
- **Fresh**: the model was tested in the last 21 days.

## Data needed
- Fixtures: id, band lo/hi, date added.
- Eval runs: id, model, trigger (weekly/manual), started_at, commit sha and message, runs per fixture, fixture count, status, error, cost, duration.
- Per run × fixture: n, in-band, below, above, plus mean/min/max rating (or all repeat ratings).
- The production lane config (which model serves which horizon), ideally read from Run Config rather than hard-coded.
- The commit list with timestamps and messages, for chart markers and version chips.
- Run a check: endpoint to start an eval with {models[], runsPerFixture, fixtureIds[]} on HEAD, plus progress polling.

## Design tokens
Colours:
- bg `#181210` · surface `#221A15` · surface-light `#2A2019` · panel `#1E1712`
- border `#3A2C23` · border-light `#4A3A2E`
- ink `#F2E7D3` · ink-2/3/4 = ink at 66/45/26%
- go `#8AAE72` · amber `#E0A542` · coral `#E8593F` · tide `#6FA8B0` · gold `#EBD9A8` (version tags `rgba(201,162,75,.1)` background)

Model colours (oklch):
- Sonnet 5.5 `oklch(.74 .14 340)`
- Haiku 4.5 `oklch(.72 .11 250)`
- Haiku 5.5 `oklch(.8 .1 200)`
- Sonnet 4.6 `oklch(.72 .12 300)`
- Opus `oklch(.82 .12 85)`

Type: IBM Plex Sans for the UI, IBM Plex Mono for numbers, ids, chips and axes (uppercase labels 10px with .1–.12em tracking), and Newsreader 600 for the title, verdict and lane model names.

Radii: cards 12 · buttons 8 · grid cells 4 · tags 4 · chips 20.

Currency is shown in £, consistent with the Runs page. The current screen shows $.

## Files
- `sky-eval-redesign.html`: the shell and all CSS.
- `sky-eval.js`: synthetic data, rendering, charts and interactions.
- `screenshots/*.png`: reference captures.
