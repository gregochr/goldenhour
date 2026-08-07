# PhotoCast — window-first Plan tab redesign. Continue at P10′.

*Handover written 2026-08-07, immediately after P9 merged. Paste this as the opening prompt of the
new session. Every file:line below was opened and checked by a verification pass; where the plan doc
is wrong, the correct value is given.*

## Read first, in this order

1. `docs/engineering/window-first-redesign-plan.md` — the spec. §5 is the build order. For P10′:
   **the P10′ row (`:581`)**, **§5a's P10 paragraph (`:688-698`)**, **§5a's P6 click-to-map bullet
   (`:600-604`)**, §6 (`:1117-1143`), §7's peek and touch bullets (`:1155-1160`), and **§5d, which
   P9 just added**.
2. CLAUDE.md § "UI Work — Review Cadence" — mandatory: build → tests → adversarial review of the
   diff → fix survivors → re-verify → commit. Reviews run as a Workflow; review agents are
   **READ-ONLY** (one destroyed uncommitted work on this project once). `git add -A` first.
3. `docs/engineering/frontend-test-standards.md` before touching any frontend test. Note `:156`
   names `CardHoverPreview` **by name** as the example of "an `aria-hidden` panel must never be the
   only route to anything" — that standard is the reason for half of P10′'s scope decisions.
4. `docs/design/window-first/Adversarial Review.html` — §6 (`:1140-1142`) makes reading it an
   ongoing precondition. **Charge c2, "Four ways to see more spots", is already marked Guilty.**
   The peek is a fifth. See "The argument you have to make" below.

⚠️ `DELTA.md` does not exist and never has. The vendored `README.md` and `PROMPTS.md` are
superseded by §5.

## State

- Phases done: P0, P0′, P1/P1′, P2, P3, P4a, P4b, P4c, P5, P6, P7, **P8 (#438)**, **P9 (#440, merged
  2026-08-07 as `6cb3daa4`)**.
- **Branch from `main`.** At handover it is `6cb3daa4`, CI green (Backend, Frontend, CodeQL all
  pass). Frontend suite **2,636 tests / 113 files**.
- Flag default is still `v1`. The flip is P15's.
- Latest migration on `main` is `V138__durham_heritage_coast_locations.sql` — **read it off the tree
  on main, never from a written-down number.** P10′ needs none.
- ⚠️ `fix/refresh-token-rotation-race` is a **local-only branch, 1 unpushed commit, no PR.** Not part
  of this work. Do not delete or rebase it. `docs/changelog-v2.17.13` is a stale merged branch.

---

## Task — P10′: `WindowSpotPeek`

### The scope is ONE component, not two

The §5 row's Work cell reads "Peek content kind 1 (spot) **+ click-to-map**". **Click-to-map shipped
at P6** — the row was never updated, and §5a`:603` already corrects it: *"P10′ still owns
`WindowSpotPeek`, which is the part that is actually new."* The code is in the tree: every spot is a
`<button>` whose `onClick` calls `onOpenSpot?.(spot)` (`WindowSpotStrip.jsx:161`), the `◍ Open on
map →` line is at `:218`, and the positional handoff is at `WindowFirstShell.jsx:185`.

`grep WindowSpotPeek` returns exactly one hit — a forward reference in a Javadoc
(`WindowFirstWindowCard.jsx:126`). No component, no test, no CSS.

### Three decisions the plan does NOT make, and P10′ must

These are the first hour. The plan's row reads as though they were settled; they were not.

**1. Which host?** The row says "the host is P4b", implying `PopoverHost` is the answer. It may not
be, and the plan never weighs it:

| | `PopoverHost` + `usePopoverHost` (P4b) | the `CloseToHome` fixed-position hatch |
|---|---|---|
| Escapes clipping | **portals to `document.body`** | `position: fixed` only |
| Scroll dismissal | **free, capture-phase** (`usePopoverHost.js:66`) | hand-wired `onScroll` |
| Resize + Escape | **free** (`:63`) | Escape only, `window`-level |
| Placement | **above only, never flips** (`popoverPlacement.js:36`) | above/below flip + arrow tether |
| Panel hover handlers | **nowhere to hang them** — props are `{popover, className}` | `onPointerEnter/Leave` props |
| ARIA | `role="tooltip"` | `aria-hidden` |

`PopoverHost.jsx:10-15` argues `position: fixed` is "necessary but not sufficient" and portals
precisely so "a later phase adding a transition to a window card cannot break this". **P10′ is that
later phase** — `.wf-spot:hover { transform: translateY(-2px) }` (`index.css:1775`) creates a
containing block for fixed descendants, live on this exact surface. So the portal is the right
instinct; but `computePopoverPlacement` cannot place below and the host has no panel-hover slot, and
the 120ms panel-leave timer *needs* one. Whatever you choose, **write down why**.

**2. What triggers the phone peek?** The row says both "on touch the card activates the map" **and**
"phone peek via `BottomSheet`" — and names no trigger for the sheet. Not in the row, not in §5a
`:694-698`, not in §7 `:1159-1160`. Shipping the sheet with no trigger is dead code; shipping a
control whose only effect is the sheet runs into §6 `:1134`. The honest options are a second
affordance, or **no phone peek at all** (the card already goes to the map, which is what a phone user
wants). Decide it explicitly.

**3. Does a summary-less spot get a peek?** `CloseToHome.jsx:835-836` has `if (!summary) return;` —
no Claude sentence, no peek. That rule was written when the score bars had been *removed* from the
panel, so "a peek without the why just restates the card" settled it. **P10′ puts the scores back**,
so the premise is gone. Re-decide rather than copy.

### What the plan says to reuse — and why "reuse" is impossible

§5a`:692` says P10′ works by *"reusing `previewPlacement`, the fixed-position escape hatch and the
open/hold/dismiss timers"*, then says one line later to leave `CloseToHome` untouched. **Both cannot
be true.** `CloseToHome.jsx`'s only export is the component itself at `:796`. Module-private:
`previewPlacement` (`:431`), its seven geometry constants (`:406-412`), `PEEK_OPEN_DELAY_MS` /
`PEEK_HIDE_GRACE_MS` (`:488-489`), and `previewSummary` (`:477-479`). Adding an `export` is an edit to
the frozen v1 arm. **Read "reusing" as "copying"** — consistent with §5a`:677`'s copy-don't-extract
rule for P6.

**Genuinely importable, no coupling:** `BottomSheet`, `useDialogFocus`, `useIsMobile`,
`useIsCoarsePointer`, `firstClause`, `formatDriveDuration`, `lookupBriefingScore`, `PopoverHost`,
`usePopoverHost`, `computePopoverPlacement`.

**`CardHoverPreview` cannot be imported once the score bars return.** It hard-codes
`className="cth-hover-preview"`, the `--cth-arrow-left` custom property, five `cth-hover-*` testids
and the string "Click for the full read + map →". No `className` prop, no content slot. Copy it and
rename to `wf-`.

---

## The traps, in the order they will bite

### ⚠️ 1. The pane renders ZERO cards from 2026-08-09, and it will look like your regression

The local briefing cache is dated **2026-08-04** and `selectUpcomingEvents`
(`WindowFirstBriefingContext.jsx:57-71`) drops past events before any card is built. Cards rendered:
3 on 07 Aug after noon → 2 on 08 Aug before noon → 1 after noon → **0 from 09 Aug onward**.

`POST /api/briefing/run` (ADMIN, ~6s, £0, needs Open-Meteo which is UP) regenerates it. **Do this
before any browser work** or every height measurement is meaningless.

### ⚠️ 2. Regenerating will NOT give you slots, ratings or anything rich

`BriefingHonestyFilter.fullRewrite` replaces the slot list with `List.of()` for any region with
`scoredLocationCount == 0` (`BriefingHonestyFilter.java:269-270`, `:284`) — and that is every region,
because scoring is done by the batch pipeline and `cached_evaluation` has **zero rows**. Verified at
the row level: `forecast_evaluation` 0, `cached_evaluation` 0, `forecast_score` 0, `tide_extreme` 0,
`actual_outcome` 0, `user_drive_time` 0, `travel_day` 0, `astro_conditions` 0.

**There is no sequence of local commands that produces a rating.** Ratings, `bestRating`,
`confidence`, `pick`, `tide`, badges, hot topics, spot strips, drive times, **and every input the
peek displays** are fixture-only. See "The fixture" below.

### 3. Collapsing a card unmounts the peek's anchor — this hazard is new at P9

P9 shipped collapse/expand. A peek open on a spot inside a card whose expander is then pressed loses
its anchor mid-flight. **Nothing existing dismisses on collapse.** The card's collapsible region is
`WindowFirstWindowCard.jsx`'s `window-card-body`; the state lives in `WindowFirstShell`
(`isCardOpen`/`toggleCard`).

### 4. The strip scrolls three ways, and has no `onScroll` today

`WindowSpotStrip.jsx:150`'s scroller (`data-testid="window-spot-scroller"`, `.wf-spots`) has none.
`CloseToHome.jsx:655` dismisses on the strip's scroll and explains why: `position: fixed` coordinates
are captured on mouseenter, and no `mouseleave` fires when the card slides out from under a
stationary pointer. Three routes: the `‹ ›` nudge buttons (`WindowSpotStrip.jsx:134`, `scrollBy`), a
trackpad swipe, and **the browser's own scroll-into-view when a card is tabbed to**.

That third one is a trap of its own: **if the peek opens on `focus` synchronously, the scroll-into-view
that follows a Tab into a partly-offscreen card fires immediately after and dismisses it** — the peek
would flash and vanish on exactly the cards near the strip's edges. The open *delay* sidesteps it.
Do not drop the delay for the focus path.

### 5. Three clipping/containing-block facts, one of them inline

- `.wf-spots` is `overflow-x: auto` (`index.css:1707`), which computes `overflow-y` to auto too
  (`index.css:1661-1662` records this) — the scroller clips on **both** axes.
- The window card root sets `overflow: 'hidden'` as an **inline style**
  (`WindowFirstWindowCard.jsx:163`). An inline declaration cannot be overridden by a stylesheet rule
  without `!important`.
- `.wf-spot:hover { transform: translateY(-2px) }` (`index.css:1775`) — a transform is a containing
  block for **fixed** descendants, so a fixed panel rendered inside the button is re-based onto it
  while hovered.

Portal to `document.body` and all three questions disappear.

### 6. Anything appended INSIDE `.wf-spots` lights the wrong edge fade

`useStripEdges` computes `more` as `scrollWidth - clientWidth - scrollLeft > 4`
(`WindowSpotStrip.jsx:42-44`). A peek node inside the scroller widens `scrollWidth` and spuriously
lights the right arrow and the right edge fade on a strip that does not overflow.

### 7. Testid collision with the v1 arm's own test file

`CloseToHome.test.jsx:497` and `:509-510` assert `cth-hover-scores` and `cth-hover-upsell` are
**ABSENT**. Name P10′'s restored score bars `cth-hover-scores` and those negative assertions start
failing on a component P10′ never touched. **Prefix everything `wf-`.**

### 8. The timings in the plan are not a re-parameterisation of what ships

Plan: "140ms open, 160ms strip-leave, 120ms panel-leave" (`:697`, and `140/160/120ms` at `:581`).
Shipped: `PEEK_OPEN_DELAY_MS = 180` and `PEEK_HIDE_GRACE_MS = 140` — **two** constants, with one
grace used for **both** card-leave and panel-leave (`closePreview` is passed as the card's
`onMouseLeave` *and* the panel's `onPointerLeave`). So P10′ needs a **third** constant and
`closePreview` split in two. And 140ms open is **shorter** than the shipped 180ms, whose Javadoc
(`CloseToHome.jsx:481-487`) argues 180 is "the whole difference". Record the deviation; do not adopt
it silently.

### 9. `PREVIEW_ESTIMATED_HEIGHT = 170` is premised on content P10′ removes

Its Javadoc (`CloseToHome.jsx:400-404`) licenses the estimate because the content is bounded to three
short lines. **The score bars break that premise** — 170 will under-estimate, so the above/below flip
chooses 'below' when there is no room. Re-derive it.

### 10. The absent `max-height` is deliberate, and P10′ re-creates what it fixed

`index.css:578-579` records that removing `max-height` was the fix for a panel that "capped at 260px
with overflow hidden, which is what cut the second score bar in half". **P10′ restores the score
bars** — this is the rule most at risk of being silently re-broken.

### 11. `BottomSheet` has no Escape handler

Verified across `BottomSheet.jsx`, `useDialogFocus.js` and `test/BottomSheet.test.jsx`: zero hits.
Only the backdrop and the ✕ close it. Both other peek paths on this screen close on Escape
(`CloseToHome.jsx:874`, `usePopoverHost.js:63`), so a reviewer will raise it and it does not come
free. `useDialogFocus` is focus-in-and-restore, **not a trap** — its Javadoc says so at `:16-22`.

### 12. Rules the peek must not break

- **Rating comes from the CARD, never the score index** (`CloseToHome.jsx:849-852`). The spot
  descriptor already carries `spot.rating` (`windowFirstSpots.js:186`).
- **Opening must cost nothing.** `CloseToHome.jsx:462-463`: a pointer sweeping a strip must never
  become a burst of requests. The data is already in memory — `evaluationScores` on the v2 context
  carries `fierySkyPotential`, `goldenHourPotential` and `summary`
  (`WindowFirstBriefingContext.jsx:257-265`, exposed at `:339-340`).
- **The peek must NOT be a second confidence render site.** §2.7 and §6 `:1128-1130` make the window
  card's verdict badge the single site, and §6 asks specifically that it be checked **absent from
  the spot strip**. No dimming by confidence, no `◎`, no `ProvisionalMark`.
- **`role` must not reach the card subtree.** §5c: it enters at the provider and stops there; P7 has a
  test pinning the absence of `role`/`isPro`/`isLiteUser` props below the shell. §7 says the peek
  needs **no** new gating (`freemium_ui_strategy.md:79-80` lists scores and the Claude summary as
  LITE-included).
- **`scroll-margin-top` rides `.wf-spot`** at 60px and `.wf-exp` at 76px, both derived from the lens
  bar's measured 53.5px (§5c, §5d). P10′ adds nothing to the bar, so both still hold — but do not
  remove or re-scope them.
- **z-index landscape:** sticky lens bar **20** (`index.css:1367`), focused spot card 3 (`:1794`),
  edge fades 2 (`:1689`), heatmap hover scoped to 10 inside the door panel (`:1341`),
  `.cth-hover-preview` 1200 (`:582`), `BottomSheet` 9999/10000 inline. Clear 20; you do not need 9999.

### 13. The argument you have to make

`Adversarial Review.html` charge **c2 — "Four ways to see more spots", severity high, verdict
Guilty** — already counts strip scroll, `‹ ›`, "See all N" and the drilldown. §6 `:1140-1142`: *"New
elements earn their place against that list, and something should usually come out when something
goes in."* The peek is a fifth. The honest defence is §7's — in this arm the peek is the **only**
route to the scores — but it has to be *made*, not assumed.

---

## ⚠️ Six wrong citations in the plan doc — verified, with corrections

Fix them or work around them, but do not propagate them. (P8 shipped two wrong comment citations,
one copied straight out of the plan, which is why this list exists.)

| Plan line | Cites | Actually |
|---|---|---|
| `:74` | `index.css:459-467` for the peek's `position: fixed` | **tide-run footer CSS.** Real rule: `.cth-hover-preview` at `index.css:580`, `position: fixed` `:581`, `z-index: 1200` `:582`, reasoning `:571-579`. ⚠️ **This is the one P10′ is most likely to follow** — §1's trap table is where a new phase looks for the peek's lifetime guarantee |
| `:688` | `index.css:888` for ScrollRail's "only handle a mouse user has" | `.map-home-control button`. Real line **`index.css:1062`** |
| `:372` | `index.css:58` for `--color-pick-also` | ships at **`index.css:53`** |
| `:373` | `index.css:655-669` for the `data-pick` chip accents | at **`index.css:785, :790, :795, :796`** |
| `:1199` | `App.jsx:380` for the flag branch | off by one — comment ends at 380, branch at **`:381`**, `<main>` at `:382` |
| `:692` | "reusing `previewPlacement`" | **impossible** — module-private; read as "copying" (see above) |

Also **understated**, not wrong: `:690-691` says the Javadoc "lists the score bars, header bar and
footer as deliberately removed". The actual list at `CardHoverPreview.jsx:23-26` is **eight items**
(score bars, generated-at, region line, tide detail, header bar, ✕, footer, location name), and §7's
reconciliation addresses only the bars. **State a position on the other five.**

And two figures that are timestamps, not properties: §5c `:962` ("0 spots across 5 windows") and §5d
`:1105-1106` ("0 spots across 3 windows") are both correct-as-written and both a pure function of days
elapsed since 2026-08-04.

---

## Running it locally — this works

**Backend** (may already be running — check first):
```
cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local
```
Port **8083**. ⚠️ `-Plocal-dev` is load-bearing: H2 is `test` scope and that profile re-adds it at
runtime. Without it you get `Cannot load driver class: org.h2.Driver`.

⚠️ **`.claude/launch.json`'s `backend` entry is broken twice** — it declares `"port": 8082` (should be
8083) *and* omits `-Plocal-dev`. `preview_start({name:"backend"})` will never come up. Fixing it is
local-only (`.claude/` is gitignored).

**Frontend:** `frontend/.env.local` already sets `VITE_API_TARGET=http://localhost:8083`.
⚠️ Other sessions hold **5177** (P9) and 8083. `launch.json` carries `frontend-p7` (5175),
`frontend-p8` (5176), `frontend-p9` (5177) — **add `frontend-p10` on 5178.**

**Login** `admin` / `golden2026`. Turnstile's test widget clears itself but wipes the fields, and
`computer type` does not stick in the React inputs. What works:
```js
(function(){function set(el,v){const s=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value').set;
s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));}
set(document.querySelector('input[type=text]'),'admin');
set(document.querySelector('input[type=password]'),'golden2026');
setTimeout(()=>document.querySelector('button[type=submit]').click(),150);})()
```

**Flip to v2** via the ⚙ in the masthead → "Window-first Plan". The stored value is JSON-encoded —
`'"v2"'` **with the quotes** — so a hand-set `localStorage.setItem('photocast.planLayout','v2')`
silently does nothing.

### The fixture — you will see nothing rich without one

Wrap `XMLHttpRequest.prototype.open/send`, and on `readystatechange` at `readyState === 4` redefine
`responseText`, `response`, `status` and `readyState` on the instance. Override `/api/briefing`,
`/api/travel-days`, `/api/user/settings/reach`. Clear `photocast_swr:*` from localStorage first.
Five things that cost time at P6–P9:

- **Key the fixture to the LOCAL DATE, never the payload's array index.** From 09 Aug *every* index
  in the cached payload is past.
- **`eventTime` must be a full ISO datetime** (`2026-08-08T21:03:00`). `formatTime` runs it through
  `formatEventTimeUk`, so a bare `21:03` yields `''` and every sun time silently vanishes — it looks
  exactly like a rendering bug. Cost a round at P9.
- **A reload does not clear the fixture if the SPA does not actually reload.** Two stacked wrappers
  register listeners in the wrong order and the *older* one wins. Use `location.reload()`, and guard
  with `if (window.__fixture) return 'already installed'` to detect it.
- **The one-shot `useEffect`s run at provider mount.** Install the fixture, *then* remount through the
  real UI: click `[data-testid="window-first-exit"]` → `[data-testid="settings-cog-btn"]` →
  `[role="switch"]` → close, in one JS call with `setTimeout` steps.
- **Read DOM state in a LATER tool call than the one that triggered it.** React batches.

⚠️ **The Browser pane repaints only dirty regions while hidden.** A screenshot after a programmatic
scroll shows a torn composite that looks *exactly* like a positioning bug. **The DOM is
authoritative** — read `getBoundingClientRect()` at several scroll positions. Forcing a repaint
(`document.body.style.opacity='0.999'` then restoring on rAF) usually recovers the frame.

---

## Standing gotchas

- Tokens in the plain `@theme` block are **pruned to the empty string** unless referenced by a literal
  `var()`. `@theme static` is not. Verify with `getComputedStyle(document.documentElement)
  .getPropertyValue('--x')` on the running app.
- **Never `git checkout --` to restore a mutation on uncommitted work** — it deletes the change under
  test. `cp` to a scratchpad and back. (Writes *into* the scratchpad are hook-blocked; `cp` works.)
- ⚠️ **A comment edit can silently kill the rule below it.** P9 appended prose after a `*/` and
  disabled `scroll-margin-top` for `.wf-spot` as well as its own new selector — invisible to lint,
  build and 2,636 tests, caught only by reading `getComputedStyle` in the browser.
- **Measure each variant on its own backdrop.** 11px muted is 3.54:1 on a plain card and 3.48:1 on the
  lead card's gold wash. This project has made the muted→secondary correction **six times**. Stop
  reaching for `--ink-3`/`--color-plex-text-muted` at small sizes on these surfaces.
- **ESLint will not catch the a11y rule here.** `jsx-a11y`'s recommended set exempts `aria-hidden`
  subtrees — an `aria-hidden` div with `onClick` lints clean. The only enforcement of "never the only
  route" is a hand-written test, which `frontend-test-standards.md:156-158` demands.
- **Mutation-check every new test**: `cp` the file, break the thing, confirm the *named* test fails,
  `cp` back. P9 ran 39 and all 39 caught; P8 ran 39 and two survived, both exposing weak tests.
- A test that clicks a `disabled` button cannot fail. A fixture where two implementations give the
  same number cannot fail. `expect(x).not.toEqual(expect.arrayContaining([a,b]))` is **conjunctive** —
  use two `not.toContain`.
- Every PR conflicts on `CHANGELOG.md`. ⚠️ `[Unreleased]` was promoted to v2.17.13 on 2026-08-06, so
  check whether it has been promoted again before anchoring an entry.
- `main` moves under you — rebase before opening the PR. **`CodeQL` is a required check** alongside
  Backend and Frontend.
- Backend suite needs Docker for 5 Testcontainers classes; locally use `-Dtest='!**/integration/**'`.
  **Gate on the exit code, never a grep of the output.** `-Dtest='Class#method'` on a JUnit `@Nested`
  method **runs nothing and exits 0**.

---

## What P9's review found, because these species recur

Six prosecutor lenses + one refuter per charge + synthesis (38 agents): **31 charges, 25 refuted,
6 real** — all fixed pre-commit.

- **Five of the six were one species: re-parenting a component does not bring the guards its old call
  site wrapped it in.** The defect is never in the component or the new call site — it is in what the
  *old* parent did around it, which does not appear in the diff. P10′ copies rather than re-parents,
  so the analogous question is: **what does `CloseToHome` do *around* `CardHoverPreview` that a copy
  will not inherit?** The answer is at least the `onScroll` dismissal, the map-overlay dismissal, the
  focus-parity wiring, and the `noHoverPeek` gate.
- **The panel cannot see composition, scroll or paint.** P8's worst miss was an upsell compositing to
  3.68:1 inside an `opacity: 0.45` wrapper; P9's was a comment that swallowed a CSS rule. **Run the
  browser.**
- Prompting the skeptic to **default to REFUTED without citable evidence** is what kept the signal
  usable at that fleet size. The commonest refutation was not "the code doesn't say that" but "the
  scenario cannot occur".
- **Say what you could not stage.** P9 could not reproduce one confirmed defect (no fixture produced a
  document tall enough) and said so rather than implying it was verified.

No screen reader, axe or Lighthouse scan has ever been run on this redesign, and every scroll and
paint measurement to date is Chrome only.
