# PhotoCast — window-first Plan tab. Continue at P15a → phone heatmap → P15b.

*Handover written 2026-08-11. Every file:line and number below was checked against `main` at
`7443e0ac` on the day it was written — but **this project has had citations rot inside a single
commit**, twice, most recently when P14 added 186 lines to `index.css` and invalidated line numbers
written in the same commit. **Verify before building on any of them.***

Paste this as the opening prompt of the new session.

---

## Start here

```bash
git pull && git log --oneline -5 && git branch -vv && git status --short
```

⚠️ **Also run `git branch --show-current` before you push anything.** A review agent ran
`git checkout main` mid-session on 2026-08-09 and it went unnoticed until push time — a whole
phase's commit had landed on `main` instead of its feature branch. `git status` cannot see this.
Recovery, if it happens: `git branch -f <feature> <sha>`, `git checkout <feature>`,
`git branch -f main origin/main`.

## Read first, in this order

1. `CLAUDE.md` — especially § "UI Work — Review Cadence" (**this work is bound by it**) and
   § "Speeding Up the Dev Build Cycle".
2. `docs/engineering/window-first-redesign-plan.md` — the spec. §5 build order, §5i (P14's
   decisions), §6 pre-pilot sweep, §7 deviations.
3. `docs/engineering/frontend-test-standards.md` — before touching any test.
4. `docs/design/window-first/README.md` § "Responsive Behaviour" — **nine** bullets, not thirteen.

---

## State, verified at handover

- `main` = **`7443e0ac`**. Frontend suite **125 files / 3035 tests**, all four CI steps green.
- Latest migration **`V139__tide_refresh_description_horizon.sql`** — read it off the tree before
  naming a new one; none of the work below needs a migration.
- The flag default is still **v1**. Flipping it is a separate, later, one-token change.
- ⚠️ **`feature/p15a-operations-tab` is committed LOCALLY at `1ef317d1` and NOT pushed.** An
  adversarial review of it was in flight when this was written and its verdict was never seen. **Do
  not push or PR it until that review is re-run and its findings addressed** — see task 1.

### ⚠️ Deployment — check this before believing anything about production

`main` is **13 commits ahead of `v2.17.14`**, and **no `v2.17.15` tag exists**, locally or on origin
(`git ls-remote --tags origin | grep v2.17.15` → nothing), even though `fad19bfe` promoted the
CHANGELOG to v2.17.15 and three more PRs merged after it. `deploy.yml` fires **only** on
`push: tags: v*`.

Releases are cut with **`./release.sh`**, which promotes the CHANGELOG via an auto-merged PR and
**then** tags — so a merged "promote [Unreleased]" PR with no tag yet is normal *briefly*. This has
not been brief. **Do not create or push tags** (CLAUDE.md forbids it; they mark tested releases and
are the owner's call) — but do tell the owner, once and factually, because if the tag never went out
then P13, P14, P14a and the three fixes below are **not in production**, and the owner believes they
are comparing the two arms on a current build.

---

## The three tasks, in the owner's chosen order

### 1. Land P15a — the admin Operations tab

Built, committed at `1ef317d1`, gate green (lint, 3035 tests, `npm audit`, build — **exit 0**).
Never reviewed to completion, never pushed.

**Do:** re-run the adversarial review over `git diff 7443e0ac..HEAD`, fix survivors, re-verify,
push, PR, merge.

What it does, so the review can be aimed properly:

- `TABS` entries may carry a `slot`; a slotted tab renders **only** when the shell is handed that
  pane. `App` holds `isAdmin` and withholds `operationsPane` — **that is the entire admin gate**, and
  it means no role, no boolean and nothing role-shaped crosses into the arm (plan §5c). Do not
  "improve" this by passing `isAdmin`.
- The panel **element** is always present so `aria-controls` resolves; its **contents** mount on
  first selection and then stay. Eager mounting would pull ~633 KB and fire ManageView's fetches on
  every Plan-tab paint; remounting would discard its sub-view, since it parses the hash at mount only.
- `effectiveTab` is load-bearing: a selection can outlive its tab when a session loses admin, and
  without the fallback no tab holds `tabIndex={0}` — the whole keyboard entry point.
- **Known and accepted:** `ManageView` writes `#manage/<tab>` on sub-tab clicks, `App` reads it, so
  the v2 exit button can then land an admin in *v1* Manage. Judged coherent; left deliberately.

**Verified in a browser** (admin, v2): three tabs; bar overflow 0 and page overflow 0 at
320/375/390/1280; the dashed right-pushed marker; ManageView absent before selection and mounted
after; no page errors. Mutations caught: undelivered tab list (7 failures), `effectiveTab` removed
(1), eager mount (1).

### 2. The full plan on a phone — the owner's own report, and the biggest remaining gain

> *"the desk top view gives me access to the full plan at the bottom of the screen - is that
> deliberately left out of the phone?"*

Yes, and not for a good reason. `WindowFirstDoors` hides the Regional planner door below the sm
breakpoint because `HeatmapGrid` is `hidden sm:grid` / `hidden sm:flex` and renders **nothing** below
640px — so the door would open an empty bordered box (a P9 review finding). Nobody decided phone
users don't need the full plan; the heatmap has no phone layout and v2 inherited that.

⚠️ **`HeatmapGrid` is shared with the frozen v1 arm**, so this changes both arms at once. That is the
central decision of the task, and it needs stating before any CSS: the v1 arm is frozen *for the
side-by-side comparison the owner is running right now*.

**Why it matters more than it looks:** the owner told us *"I never really used the iPhone browser as
the ui got too complicated. Now it['s] usable after this redesign."* The phone is a real surface now,
and v1's phone behaviour has therefore **never been exercised** — the hot-topic overflow (task-0
below) was present in both arms and unnoticed for months for exactly that reason. Expect more of the
same shape in re-parented v1 components.

### 3. P15b — the Map tab

The slot mechanism from P15a is already there: `WindowFirstShell` takes a `mapPane` prop and will
grow the tab the moment `App` passes one. The work is the *pane*, not the tab.

Carries every risk that could not be checked locally:

- **`GET /api/forecast` returns 0 rows locally**, and `App` gates `MapView` on
  `allDates.length > 0` — so the Map pane **cannot be seen populated at all** on this machine.
- **Leaflet in a hidden panel**: `MapSizeSync` is `enabled={overlayMode}`, so a map that ever renders
  while `display: none` paints grey with nothing to correct it. Budget an `invalidateSize` on reveal.
- **The date contract is unsettled.** The Map pane wants its own `DateStrip` over `allDates`
  (`/api/forecast`); the rail's domain is up to six briefing events. Different endpoints, different
  horizons. The recommendation on file is to keep the strip, because dropping it strands the tab on
  `effectiveDate` — a capability regression against v1.
- **Restore the overlay's hatch.** `App` currently withholds `onOpenFullMap` from v2 with a comment
  saying it will not name a destination it cannot reach. That expires with this task — but it is
  **not a ternary flip**: `openFullMapTab` calls `setViewMode('map')`, which v2 ignores. The design
  on file is a nonce'd tab request (the idiom already exists in `App`).
- Keep `MapOverlay` as well as the tab. Eight call sites hand it a specific location/region/event
  with a focus, a caption and a preserved narrative that a bare tab would discard. v1 runs both.

---

## Already done this session — do not redo

| | |
|---|---|
| Hot-topic row spilled its chevron past the card on a phone | `bb51c1be` (#464) |
| `formatReportedAt` test failed for the first ~50 min of every day | `7581da73` (#465) |
| Rail BEST/ALSO chip opens the pick's Claude prose | `7443e0ac` (#466) |

The rail chip and the window card's pick badge now open **one** dialog from two triggers, asserted
textually identical. `--color-marginal` and `--color-dust` were declared in P14a, so the P15 row's
list of flip blockers in `window-first-redesign-plan.md` is **stale** — and two of the others are
LITE-only, which cannot fire for an owner who is the only user and an admin.

---

## Traps that carry forward

1. **Gate on the exit code, never on the output.** A run printed `3035 passed` while exiting **1**:
   `scrollIntoView` does not exist in jsdom, so a keyboard handler threw on every arrow press and
   Vitest reported it only as "7 unhandled errors" beneath a green summary.
2. **jsdom evaluates no CSS.** `vite.config.js` sets `css: false`; `matchMedia` is a static
   `{matches:false}` stub that ignores the query. So **no unit test can assert any media query or
   any stylesheet value** — assert the class or attribute the component emits, and measure pixels in
   a browser. `toHaveStyle` is honest only for values written inline.
3. **Never spy on `localStorage`/`sessionStorage`.** `setup.js` installs a plain-object substitute
   *only when jsdom does not supply one* — true on this Mac, false on CI. An instance spy passes
   locally and records nothing on the runner. Observe through `length`/`key`, with a control write.
4. **An inline style beats every stylesheet rule.** Two phone rules shipped dead this way before
   being caught: an inline `flex` shorthand sets `flex-basis`, and an inline `gap` shorthand sets the
   `row-gap` **longhand**. If a media query must reach a property, that property cannot be inline.
5. **`npm run test` is NOT the frontend CI job.** It is lint → Vitest → `npm audit --audit-level=high`
   → build. Run all four; `npm run lint` is `--max-warnings 0`, so a *warning* fails CI.
6. **Review agents must be told READ-ONLY, explicitly and including branches.** Two incidents on this
   repo: `git checkout --` destroyed unstaged work, and `git checkout main` moved a commit to the
   wrong branch. Commit before review, not just stage.
7. **The Browser pane can wedge permanently.** Fall back to Playwright from Bash — the module is
   installed but its chromium is not, so pass the cached binary explicitly:
   `~/Library/Caches/ms-playwright/chromium_headless_shell-1208/chrome-headless-shell-mac-x64/chrome-headless-shell`
   via `chromium.launch({ executablePath })`. A hook blocks `Write` outside the repo, so pipe the
   script in with `node --input-type=module -e "$(cat <<'EOF' … EOF)"`. It is faster than the pane
   anyway, because resizing needs no separate tool call.

---

## Running it locally

```bash
cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local   # port 8083
cd frontend && npm run dev                                                          # 5182 is free
```

`admin` / `golden2026`. `-Plocal-dev` is load-bearing (H2 is `test` scope). Flip to v2 through ⚙ →
"Window-first Plan"; the stored value is JSON-encoded — `'"v2"'` **with the quotes** — so a hand-set
`localStorage.setItem` silently does nothing.

**What local data cannot show you.** The batch pipeline has never run here, so there are **no
ratings, no picks and no spots**: the pane reads "0 spots across N windows", the rail's BEST/ALSO
chip does not render at all, `tide_extreme` is empty, and `GET /api/forecast` returns 0 rows. The
spot strip, attribute rows, the window header's meta row and every enriched tide row have therefore
**never rendered on real data in any phase** — they were built against injected fixtures, one of
which had to be hand-written into a state "the roster never produces". Injecting the component's
verbatim markup to test a CSS rule is legitimate and was used repeatedly; say so plainly when you do.

The cheap fix, if a task needs real states: hand-seed `cached_evaluation` and `tide_extreme` through
the H2 console (`http://localhost:8083/h2-console`, `jdbc:h2:file:./data/goldenhour`, user `sa`, no
password) — that sits *below* the honesty filter and the projector, so every layer above runs for
real for the first time.

---

## Not verified, said plainly

- **No iOS Safari, ever.** Every measurement in this series is headless Chromium on macOS. WebKit
  lays button content in an anonymous centring container, which is the one thing that could move the
  rail chip's box — and the rail's baseline reservation is derived from that box. The owner reads on
  an iPhone.
- **No touch, no screen reader, no axe, no Lighthouse, no forced-colors, no non-Chrome.** True of
  every phase P4 → P15a and accumulating.
- **Nothing seen below 320px or above 1440px.**
- The **rem/px seam** is live and deferred by the owner to its own commit: `useIsMobile` is
  `(max-width: 639px)` in **px** while Tailwind's `sm:` is **`40rem`**, and `WindowFirstDoors` gates
  on the first because `HeatmapGrid` is `hidden sm:grid`. At a non-default browser font size they
  diverge and that band renders a bordered box around a `display:none` grid. Mechanism certain from
  the code; **never reproduced** — media-query `rem` resolves against the *initial* root font size,
  which JavaScript cannot change, so only the browser's own font setting reaches it. **Task 2 above
  is in exactly this area — read this before touching `WindowFirstDoors`.**
- `<main class="px-4 py-6">` adds 16px each side at every width, so the real phone inset is 16 + 14 =
  **30px**, not the mock's 14. Shared App chrome; deliberately untouched.
