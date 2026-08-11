# PhotoCast — window-first Plan tab. Continue after P15a, the phone heatmap, and P15b.

*Handover rewritten 2026-08-11, replacing the version written the same morning. Every file:line and
number below was checked that day — but **this project has had citations rot inside a single
commit**, three times now, most recently when the phone heatmap shifted `HeatmapGrid` by 13 lines and
invalidated a line number written in the same commit. **Verify before building on any of them.***

---

## Start here

```bash
git pull && git log --oneline -5 && git branch -vv && git status --short
```

⚠️ **Also run `git branch --show-current` before you push anything.** A review agent ran
`git checkout main` mid-session on 2026-08-09 and it went unnoticed until push time — a whole phase's
commit had landed on `main` instead of its feature branch. `git status` cannot see this. Recovery:
`git branch -f <feature> <sha>`, `git checkout <feature>`, `git branch -f main origin/main`.

## Read first, in this order

1. `CLAUDE.md` — especially § "UI Work — Review Cadence" (**this work is bound by it**) and
   § "Speeding Up the Dev Build Cycle".
2. `docs/engineering/window-first-redesign-plan.md` — the spec. §5 build order, §6 pre-pilot sweep,
   §7 deviations.
3. `docs/engineering/phone-heatmap-blast-radius.md` — **read this before touching any component the
   two arms share.** It is the worked example of how the blast radius is not the diff's headline
   line.
4. `docs/engineering/frontend-test-standards.md` — before touching any test.

---

## State, verified at handover

- `main` was `7443e0ac` at the start of the session and has taken **#467** (P15a, `adda80e8`) and
  **#468** (the phone heatmap) since.
- Frontend suite **126 files / 3067 tests**, all four CI steps green with **exit 0**.
- The flag default is still **v1**. Flipping it is a separate, later, one-token change.
- `v2.17.15` is tagged. ⚠️ **Three merges landed AFTER that tag** (#464, #465, #466) and everything
  from this session is after it too — so "tagged and deployed" no longer means "all of main is in
  production". Check what the deploy actually took before assuming.

### Landed this session

| | |
|---|---|
| P15a — the admin Operations tab | #467 |
| The heatmap's phone layout (+ the `scrollable` opt-in, and its review round) | #468 |
| WebKit verification of the phone heatmap — a first for this series | in #468 |
| P15b — the Map tab | `feature/p15b-map-pane`, see below |

---

## Where P15b is

Branch **`feature/p15b-map-pane`**, commits `714c581f` (the feature) and `b8f39aa4` (a focus fix
found by measuring). Gate green with exit 0: lint, 3067 tests, `npm audit`, build.

What it does:

- **`WindowFirstMapPane`** — a date strip plus the full map. The strip is over `allDates` from
  `GET /api/forecast`, **not** the rail's six briefing events: different endpoints, different
  horizons, and the map's is the longer one. The *selection* stays in `App`, shared with the v1 Map
  tab, so the two arms cannot disagree about which day is on screen.
- **A nonce'd `tabRequest` on the shell.** The overlay's "open the full map" hatch is restored for
  v2. It is not a ternary flip: `openFullMapTab` called `setViewMode('map')`, which this arm ignores
  entirely. The shell declines a request for a pane it was never handed.
- **`MapView` gained `resizeNonce`** (default null → v1 untouched, the same opt-in shape the phone
  heatmap settled on). The pane watches its own box with a `ResizeObserver`, because a hidden panel
  plus a rotation leaves Leaflet holding a stale size and painting grey.
- **Focus follows a request, and only a request.** Measured: after the hatch, `activeElement` was the
  document root — the dialog closed and took its restoration target with it. Now the requested tab
  takes focus, reached by DOM id rather than through the index-based `tabRefs`.

**Verified in a browser** at 390 and 1280 against an injected forecast fixture (see below): four
tabs, the map 1044×500 at 1280 and 330×500 at 390, markers and a five-day strip, the hatch selecting
the tab and re-landing on a second press, and — switching to Plan, resizing, returning — 6 tiles
becoming 12 for the wider box, which is the `invalidateSize` path working.

---

## What is left

1. **The pre-pilot sweep (§6), then flip the flag default.** The plan's P15 row lists the blockers;
   two of the four are LITE-only and cannot fire for an owner who is the only user and an admin, and
   `--color-marginal` / `--color-dust` were declared in P14a, so **that list is stale** — re-derive it
   rather than trusting it.
2. **The grid has no table semantics.** `HeatmapGrid` has no `role="grid"`/`rowheader`; a cell's
   accessible name is its own text with no region and no date in it, so VoiceOver gets ~42
   near-identical buttons. Pre-existing and **identical in both arms**, which is exactly the species
   §2.8 says to decide once across both. It also weakens the WCAG 1.4.10 argument for the phone
   heatmap's two-dimensional scrolling, which rests on it being a data table.
3. **Two costs of never unmounting a pane, recorded rather than fixed.** Once the Map tab has been
   visited, its `MapView` lives for the session: `useAuroraViewline`'s 5-minute poll keeps running
   behind the Plan tab (and doubles while an overlay is open), and `fetchTravelDayRanges` is
   duplicated with the one the briefing provider already makes. This is the same species as the
   Operations pane's Scheduler poll, which P15a recorded and left. If it is not wanted, release
   panes in the shell rather than deleting the comments.
4. **On a phone, the map overlay's own bottom sheet covers its "Open the full Map tab" button.**
   Pre-existing, in `MapOverlay`/`MapView`, and reproduced at 390px while verifying P15b — the
   Playwright click was intercepted by `bottom-sheet-root`. Nothing to do with the tab; it means the
   hatch is unreachable on a phone once a location is selected.
5. **The rem/px seam is still live for `useIsMobile`'s other callers.** `WindowFirstDoors` no longer
   has a side in it (the phone heatmap removed its gate), but the hook is still `(max-width: 639px)`
   in px against Tailwind's `sm:` at `40rem`.

---

## Traps that carry forward

1. **Gate on the exit code, never on the output — and never through a pipe.** `npm run lint | tail`
   reports `$?` from **tail**, which is 0 while eslint is returning 1. That happened again this
   session and hid three errors. Redirect to a file and echo the status as its own statement:
   `npm run lint >/tmp/l.log 2>&1; echo "exit: $?"`.
2. **jsdom evaluates no CSS.** `vite.config.js` sets `css: false`; `matchMedia` is a static
   `{matches:false}` stub. **No unit test can assert any media query or stylesheet value** — assert
   the class or attribute the component emits, and measure pixels in a browser.
3. **A container-relative length fails silently.** `100cqw`/`100cqi` resolves against the *viewport*
   when no query container matches. An unscoped rule shipped this session rendered a panel 1280px
   wide inside an 830px grid, with nothing erroring.
4. **`position: sticky` on a grid item is clamped by the GRID BOX, not the track list.** Without
   `min-width: max-content` on the grid, a pinned column silently does not pin. Measured at x = −196.
5. **An inline style beats every stylesheet rule.** `gridTemplateColumns` is inline, so a media query
   cannot reach it — which is why the phone heatmap's trigger is a `minmax()` floor rather than a
   breakpoint.
6. **`npm run test` is NOT the frontend CI job.** It is lint → Vitest → `npm audit --audit-level=high`
   → build. `npm run lint` is `--max-warnings 0`, so a *warning* fails CI.
7. **Review agents must be told READ-ONLY, explicitly and including branches.** Two incidents on this
   repo. Commit before review, not just stage.
8. **Playwright: run it from `frontend/`, and `npx playwright install <x>` PRUNES the others.**
   Installing webkit deleted the cached chromium mid-session. Browsers now match the module, so
   `chromium.launch()` needs no `executablePath`. A *mismatched* cached build hangs rather than
   erroring — if launch succeeds but the first page call never returns, suspect the version.

---

## Running it locally

```bash
cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local   # port 8083
cd frontend && npm run dev
```

`admin` / `golden2026`. `-Plocal-dev` is load-bearing (H2 is `test` scope). `frontend/.env.local`
already points the Vite proxy at 8083. Flip to v2 through ⚙ → "Window-first Plan"; the stored value
is JSON-encoded — `'"v2"'` **with the quotes**.

### What local data cannot show you, and the fixture that fixes it

The batch pipeline has never run here. `GET /api/forecast` returns **0 rows**, so `allDates` is empty
and the Map tab does not even appear. `/api/briefing` *does* return five days × four regions × two
events with real verdicts — but dated **2026-08-07**, so only "today" survives the upcoming filter,
and the honesty filter empties every slot so every cell reads "Poor".

**Route interception is the cheapest fix and it exercises the real component from the payload down.**
Both were used this session and both are legitimate — say plainly that you did:

- **Briefing**: shift every `YYYY-MM-DD` in the response forward by 4 days, and set
  `displayVerdict` / `regionTemperatureCelsius` / `slots[].claudeRating` to get non-Poor cells, spot
  strips and "Open on map" buttons. Give each region's slots **unique location names** or React logs
  duplicate keys.
- **Forecast**: `GET /api/locations` works, so build rows from the real roster —
  `{locationName, locationLat, locationLon, targetDate, targetType, rating, solarEventTime, …}` — and
  fulfil `/api/forecast` with them. Match on `/\/api\/forecast(\?|$)/`; a `**/api/forecast*` glob also
  catches nothing useful if you try to sniff `/api/locations` in the same run, which raced and
  produced an empty page twice.

---

## Not verified, said plainly

- **No real device.** WebKit at 390×844 with `hasTouch` now backs **both** the phone heatmap (see
  `phone-heatmap-blast-radius.md`) and P15b — the Map tab renders at 330×500 with its five-day strip,
  and rotating to landscape *while on the Plan tab* and returning re-tiles the map from 6 tiles to 12
  for the wider box, which is the exact scenario the `ResizeObserver` exists for. It is the same
  engine as iOS Safari, but it is not an iPhone: no real touch surface, no iOS chrome, no VoiceOver.
- **No screen reader, no axe, no Lighthouse, no forced-colors, nothing above 1440px.** True of every
  phase P4 → P15b and accumulating.
- **The Map pane has never been seen on real data** — local `/api/forecast` is empty, so every
  measurement of it is against the injected fixture above.
