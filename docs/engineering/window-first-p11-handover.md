# PhotoCast — window-first Plan tab redesign. Continue at P11.

*Handover written 2026-08-07, immediately after P10′ merged. Paste this as the opening prompt of the
new session. Every file:line below was opened and checked; where the plan doc or the mock is wrong,
the correct value is given.*

## Read first, in this order

1. `docs/engineering/window-first-redesign-plan.md` — the spec. §5 is the build order. For P11:
   **the P11 row (`:582`)**, **§5a's three P11 bullets (`:610-619`)**, **§5c's persistence bullet
   (`:866-876`)** and **its `scroll-margin-top` bullet (`:905-915`)**, §2.5 (`:298-320`), §6
   (`:1298-1325`), §7 (`:1326`), and **§5e, which P10′ just added (`:1122-1297`)**.
2. `docs/design/window-first/Plan Window First v2.html` — **the drilldown is the one part of the mock
   P11 must actually read.** Search for `/* drilldown` (the CSS) and `function openSheet` (the
   behaviour). It is a real, working prototype and its filter-bar semantics are the spec.
3. CLAUDE.md § "UI Work — Review Cadence" — mandatory: build → tests → adversarial review of the
   diff → fix survivors → re-verify → commit. Reviews run as a Workflow; review agents are
   **READ-ONLY** (one destroyed uncommitted work on this project once). `git add -A` first.
4. `docs/engineering/frontend-test-standards.md` before touching any frontend test.
5. `docs/design/window-first/Adversarial Review.html` — §6 (`:1320-1322`) makes reading it an ongoing
   precondition. **Two charges land directly on P11 and both are marked Guilty: c2 "Four ways to see
   more spots" and c6 "Reach is stated in three places".** See "The two arguments you have to make".
6. `docs/engineering/window-first-p10-handover.md` — the previous handover, now in-repo. Its
   "Running it locally" and "Standing gotchas" sections are still accurate; this doc corrects and
   extends them rather than repeating everything.

## State

- Phases done: P0, P0′, P1/P1′, P2, P3, P4a, P4b, P4c, P5, P6, P7, P8 (#438), P9 (#440),
  **P10′ (#441, merged 2026-08-07 as `9480e33a`)**.
- **Branch from `main`.** At handover it is `9480e33a`, CI green (Backend, Frontend, CodeQL, and
  ShellCheck all pass). Frontend suite **2,714 tests / 115 files**.
- Flag default is still `v1`. The flip is P15's.
- Latest migration on `main` is `V138__durham_heritage_coast_locations.sql` — **read it off the tree
  on main, never from a written-down number.** P11 needs none.
- `CHANGELOG.md`'s `[Unreleased]` now holds **two** entries (P10′ then P9). It was last promoted at
  `e0d2511b` (v2.17.13); check whether it has been promoted again before anchoring an entry.
- ⚠️ `fix/refresh-token-rotation-race` is a **local-only branch, 1 unpushed commit, no PR.** Not part
  of this work. Do not delete or rebase it. `docs/changelog-v2.17.13` and `feature/window-first-p9`
  are stale merged branches.
- ⚠️ **The local backend is DOWN** as of this handover. `.claude/launch.json` was repaired at P10′
  (port 8083 + the `-Plocal-dev` profile), so `preview_start({name:"backend"})` now works — it did
  not before. `.claude/` is gitignored, so that fix is local to this machine and survives.

---

## Task — P11: the drilldown sheet, the rating floor, and the type control

The row reads: *"Drilldown sheet — **plus the rating floor and type controls** and their persistence
| Grown by what P8 shed"*.

### The scope is three things, and they are not equally specified

The **sheet** is fully specified by the mock. The **rating floor** is nearly so. The **type control**
is specified against a taxonomy that does not exist in this product. See decision 2.

### What the mock actually says (read it, don't take my summary as the source)

`Plan Window First v2.html`, `function openSheet` / `drawSheet` / `closeSheet`:

- Opens from a per-window **"See all N →"** in the strip footer.
- A modal: scrim `rgba(8,6,5,.72)`, centred card `width: min(880px, calc(100vw - 40px))`,
  `top: 34px`, `max-height: calc(100vh - 68px)`, `border-radius: 13px`, `z-index: 120`.
- **Header**: `{when} · {time}` + `{N} spots · best {B}` + — *only when the sheet's reach differs
  from the bar's* — `· widened for browsing`, then a `Close · Esc` button.
- **Filter bar** (`.fbar`): three segmented controls — reach, rating, type.
- **List**: a 3-column grid of the same `.spot` cards, dropping to 2 columns below 620px.
- **Empty state**: *"Nothing matches — widen the reach or drop the rating floor."*
- **Footer**: *"Showing N of M loaded · ranked by rating, then drive"* and *"Rating floor is
  remembered · reach and type reset each visit"*.
- `dReach = reach` **on open** (it inherits the bar's tier); `dLoc = 'any'` **on close** (type
  resets); the rating floor calls `save()` (it persists). Escape closes.

---

## Four decisions the plan does NOT make, and P11 must

These are the first hour. Do not start writing components before they are settled and written down.

### 1. ⚠️ Where do the rating floor and the type control actually live? The plan and the mock disagree.

**§5c`:907-909` assumes the lens bar**: *"⚠️ It tracks the bar's HEIGHT, so P11 (which adds a rating
floor and a type control to the same row) and P14 must re-measure."* That sentence is load-bearing —
it is why `scroll-margin-top: 60px` exists and it explicitly names P11 as the phase that invalidates
the 53.5px measurement.

**The mock puts all three in the sheet**, and argues for it in its own prose: *"Click All N for this
window and the browsing filters appear where you are actually browsing — reach, rating floor, type —
over the whole list rather than the top four."*

They cannot both be right, and the difference is not cosmetic:

| | in the lens bar | in the sheet |
|---|---|---|
| Scope of the filter | every window on the page | one window |
| `scroll-margin-top` | **must be re-measured** (§5c) | untouched, 60/76 still hold |
| Charge c5 ("a control that needs a 'what is this?' control") | a third control on a sticky bar | filters where browsing happens |
| Charge c2 ("four ways to see more spots") | worse — more chrome, same routes | the mock's own answer |
| P14 (responsive) | a sticky bar that must not wrap to two lines on phone | a sheet that already reflows |

The mock's case is strong and the two Guilty charges both point the same way. **But make the argument
explicitly and record it in §5f**, because §5c wrote a warning for the other answer and a future
reader will find it.

### 2. ⚠️ The mock's type taxonomy does not exist in this product

The mock ships `TYPES = [['any','Any'],['coast','Coast'],['river','River & lake'],['upland','Upland'],
['landmark','Landmark']]`.

The real enum is `backend/.../entity/LocationType.java`: **LANDSCAPE, WILDLIFE, SEASCAPE, WATERFALL,
BLUEBELL, CANOPY**. Not one of the mock's five words appears in it. §6 bans invented vocabulary, so
shipping the mock's labels would put five terms on screen that no other surface in the product uses
and that no field in the payload can populate.

Three further facts that bear on this:

- **The spot descriptor carries no type at all.** `buildWindowSpots` (`utils/windowFirstSpots.js:165-205`)
  emits `{key, locationId, locationName, regionName, rating, driveMinutes, distanceMiles, far}`.
- **The join already exists in the v1 arm**: `DailyBriefing.jsx:1053-1059` builds a name→`locationType`
  map from `locations`, and `WindowFirstShell` **already receives `locations`** (P9 added it for the
  regional door). So the data is one prop away without touching the briefing payload.
- **The strip deliberately drops canopy spots**, and §5a`:610-611` names P11 as the fix: *"a bluebell
  wood is a good destination this strip will not list. **P11's drill-down, which can carry a type
  control, is where that belongs.**"* So the sheet's population is **not** the strip's, which is a
  decision in itself and one the empty state's wording depends on.

### 3. Persistence — §5c already ruled on the shape, and the mock violates it

§5c`:866-876`: *"⚠️ **This was one `photocast.planLens` key holding every lens setting, merged
read–modify–write so P11's fields could ride along, and it was the wrong shape twice over.** … **P11
takes its own key**, and gets its own expiry policy with it."*

The mock does exactly what was rejected: `save()` writes `{reach, dRate, day: TODAY}` into one key.
And CodeQL flagged the read-modify-write pattern (`js/clear-text-storage-of-sensitive-data`) as a
conduit — a false positive about that key, a fair comment about the pattern. **Write a whole value,
never read storage back to merge into it.** The shipped precedent is `PLAN_REACH_KEY =
'photocast.planReach'` (`utils/reachLens.js:86`) holding `{reach, reachDay}` and nothing else.

Per the mock's own footer the policy is: **rating floor persists** (no day stamp — it is taste, not an
investigation), **reach inherits** from the bar and is never stored by the sheet, **type resets** on
close. That gives P11 exactly one new key holding one field.

### 4. What hosts the sheet

`BottomSheet` is the wrong answer here (it is the phone sheet, has no Escape handler — verified at
P10′ across `BottomSheet.jsx`, `useDialogFocus.js` and its test, zero hits). The right one is almost
certainly **`components/shared/Modal.jsx` in `bare` mode**:

- It does focus-in-and-restore via `useDialogFocus`, and `WindowPickDialog` is this arm's precedent
  for exactly that call (`WindowPickDialog.jsx:10-27` argues why a dialog and not `PopoverHost`).
- `closeOnEscape` is **opt-in per caller** (`Modal.jsx:20-29` explains why) and P11 should opt in —
  the sheet holds no unsaved state.
- `bare` exists so a caller can own its own panel element, which is what the mock's 880px card needs;
  `maxWidth` is only `sm|md|lg` Tailwind classes and cannot express `min(880px, 100vw - 40px)`.

Confirm this by reading `Modal.jsx` yourself rather than taking it from here.

---

## The traps, in the order they will bite

### ⚠️ 1. There is no local data path to the spot strip at all, so there is none to the sheet either

`BriefingHonestyFilter.fullRewrite` replaces a region's slot list with `List.of()` whenever
`scoredLocationCount == 0`, and it is 0 for every region because the batch pipeline has never run
locally — `cached_evaluation`, `forecast_evaluation` and `forecast_score` all have zero rows. **This
survives `POST /api/briefing/run`**: that call works (~43s, £0, ADMIN) and refreshes the cache dates,
and the payload still comes back with `slots: []` everywhere. Verified at P10′.

So the strip renders nothing, and **everything P11 draws is fixture-only**. Budget for the fixture
before budgeting for the component.

### 2. The fixture recipe that works, and the four things that cost time

Wrap `XMLHttpRequest.prototype.open/send`; on `readystatechange` at `readyState === 4`, redefine
`responseText`, `response`, `status` and `readyState` on the instance. Override `/api/briefing`
(patch `days[].eventSummaries[].regions[].slots` in place and set `scoredLocationCount`),
`/api/briefing/evaluate/scores`, and `/reach`. Clear `photocast_swr:*` from localStorage first, guard
with `if (window.__fixture) return`, then remount through the real UI.

- **Patch the real payload in place; do not author one.** Dates, windows, verdicts and event times
  come out right for free, and the payload is keyed to the local date automatically.
- **Build the reach and score arrays UP FRONT, not while patching the briefing.** The provider fires
  briefing, scores and reach concurrently; at P10′ reach resolved first and got an empty array
  because it was being accumulated inside the briefing patch. Cost a round.
- **`eventTime` must be a full ISO datetime** (`2026-08-08T21:03:00`). A bare `21:03` yields `''` and
  every sun time silently vanishes — it looks exactly like a rendering bug.
- **Fixture location names must exist in the real roster** if you want to test the map handoff. There
  are **16** real locations; `Wastwater` and `Roseberry Topping` are real, `Bamburgh Castle` and
  `Robin Hood's Bay` are **not** (the real ones are `Bamburgh Beach` etc.). At P10′ the click-to-map
  path appeared broken for ten minutes purely because the fixture named a location `buildMapOverlay`
  could not find. `GET /api/locations` lists them.

### ⚠️ 3. The Browser pane freezes `setTimeout` and dispatches NO scroll events while hidden

This is the single biggest time sink at P10′ and it will bite P11 harder, because a sheet has an
open/close animation and filters that re-render.

`document.hidden === true` between tool calls. Consequences, both measured:

- **Timers do not fire.** A peek that "failed to open" three times in a row was `document.hidden`,
  not a defect. Sandwich any timing-dependent check between `computer{action:"screenshot"}` calls —
  the screenshot is what wakes the renderer.
- **Real scroll events are never dispatched.** `scrollLeft` moved 0 → 291 with **zero** listeners
  firing at window, document *and* element level. Exercise scroll paths with
  `el.dispatchEvent(new Event('scroll'))`, which proves the capture-phase wiring, and say plainly
  that it does not prove Chrome's dispatch.
- The pane also repaints only dirty regions while hidden, so a screenshot after a programmatic scroll
  shows a torn composite that looks exactly like a positioning bug. **The DOM is authoritative** —
  read `getBoundingClientRect()`.
- **Read DOM state in a LATER tool call than the one that triggered it.** React batches, and a
  synchronous read in the same call sees pre-render state. This caught out the collapse-dismissal
  check at P10′ (`strips: 1` in the same call, `strips: 0` in the next).

### 4. Logging in — the documented JS snippet does NOT work

The P10′ handover's native-setter snippet leaves the submit button `disabled`. What works:
`read_page` → `form_input(ref_1, 'admin')` → `form_input(ref_2, 'golden2026')` → then
`document.querySelector('form').requestSubmit(submitButton)` via `javascript_tool`. Turnstile's test
widget supplies a dummy token by itself.

Flip to v2 through the ⚙ → "Window-first Plan" switch, and close the modal by clicking the button
whose text is `×` (a `/close|✕/i` sweep does not match it). The stored value is JSON-encoded — `'"v2"'`
**with the quotes** — so a hand-set `localStorage.setItem` silently does nothing.

### ⚠️ 5. Editing source mid-browser-session breaks the fixture via HMR

At P10′ two Javadoc edits triggered HMR, the provider remounted, the score index came back empty, and
three separate hovers "failed" before the cause was found. **Finish the browser pass, then edit.** If
you must edit, `location.reload()` and reinstall the fixture.

### 6. The peek is now a page-level singleton, and `openPeek` is NOT exported

`hooks/useSpotPeek.js:55` holds a module-scoped `let openPeek = null` — a dismisser plus an owner
token — because a `focusin` listener alone could not stop two panels being on screen at once (it
fires on a focus *change*, so a pointer moving between two expanded windows' strips sends the first
strip nothing). It is deliberately module-private.

So **P11 cannot dismiss the peek directly.** What *should* happen is that `Modal` moves focus into the
dialog, `focusin` fires, `e.target !== anchorRef.current`, and the peek dismisses
(`useSpotPeek.js:191`, registered at `:196`). **Verify that in the browser rather than assuming it** — a peek left
floating at `z-index: 60` under a sheet at 120 would be invisible but live, and would still be there
when the sheet closes.

Related: both the peek and `Modal`'s `closeOnEscape` register document-level `keydown` handlers. They
should never both be open, but a reviewer will ask, so check it and write down the answer.

### 7. Numbers from the mock that are mock shorthand, not the spec

- `REACHES = [['30','45 min'],['60','1h 30'],['120','2h 30'],['any','Any']]` — the **values disagree
  with their own labels**, and both disagree with what shipped. The real tiers are
  `REACH_TIERS = [45, 90, 150]` (`utils/reachLens.js:50`) with the **label derived from the
  threshold** so no second number exists to drift (§5c). Use `REACH_TIERS` and `ANY_TIER_ID`; the
  gate is `gateSpotsByReach(spots, limitMinutes)` (`:209`).
- `LIM` in the mock is the same shorthand. §5c already settled this once — do not re-derive it.

### 8. `compareSpots` was exported at P6 specifically so P11 could not disagree with the strip

`utils/windowFirstSpots.js:118`. §5a`:616`: *"`compareSpots` is exported for P11 so the two can never
disagree."* The sheet's footer claims "ranked by rating, then drive" — and §6 requires that *"every
footer's claimed sort and count matches what is rendered"*. `spotOrderStatement` (`:140`) derives
that sentence from the spots rather than hard-coding it; the sheet should do the same or state why
not.

### 9. `scroll-margin-top` tracks the lens bar's measured height — and only if the controls go there

`index.css:1526` sets 60px on `.wf-spot, .window-card-pick, .wf-film-btn, .wf-exp, .wf-door`;
`:1536` overrides `.wf-exp` to 76px. Both derive from the bar's measured **53.5px** plus the ring's
2 + 2. The comment at `:1512-1515` names P11 explicitly. If decision 1 puts the controls in the
sheet, **the numbers stand and you should say so** rather than leaving the warning to rot. If it puts
them in the bar, re-measure in the browser — jsdom has no layout and cannot test this.

### 10. Testid collision with the v1 arm's own tests

`CloseToHome.test.jsx:497` and `:509-510` assert `cth-hover-scores` and `cth-hover-upsell` are
**ABSENT**. **Prefix everything `wf-`.** P10′ has a test pinning that no `cth-`-prefixed testid
appears in its subtree; consider the same.

### 11. Two things are P15's, not P11's — do not fix them in passing

Both are recorded in §5e and both are cross-arm:

- **`--color-marginal` is declared nowhere in `index.css`.** Three components name it and
  `getComputedStyle` returns the empty string, so `CardHoverPreview`'s and `CloseToHome`'s stars have
  always inherited body ink. The v1 arm is frozen for the flag comparison.
- **The LITE score split.** `freemium_ui_strategy.md:78` lists Fiery Sky / Golden Hour as
  LITE-included and §7 relies on that; `MarkerPopupContent.jsx:1165` gates them and `:1175` upsells
  them. **This one reaches P11**: if the sheet shows scores, it inherits the same contradiction. Note
  it, do not solve it.

### 12. Rules the sheet must not break

- **The peek must NOT be a second confidence render site, and neither must the sheet.** §2.7 and §6
  `:1308-1310` make the window card's verdict badge the single site, and §6 asks specifically that it
  be checked absent from **the drilldown header** and the spot strip. No dimming by confidence, no
  `◎`, no `ProvisionalMark`.
- **`role` must not reach the card subtree.** §5c: it enters at the provider and stops there.
  `WindowFirstWindowCard.test.jsx:483-484` and `:592-594` pin the absence of `role`/`isPro`/
  `isLiteUser` from that component's propTypes. If the sheet is opened from the strip, the same
  constraint applies to everything between.
- **A lens is not a gate when it has no data** (§2.5 rule 1): a location with no drive time is
  *unknown, not out of reach* and passes every tier. **Rule 2**: with no reach data the count says
  "N spots", not "N within reach" — the word *reach* drops when nothing was gated. Both apply to the
  sheet's own header and footer.
- **Opening must cost nothing.** Everything the sheet needs is already in memory: `card.spots`,
  `reachById`, `scoreIndex` and `locations`. No new request.

---

## The two arguments you have to make

`Adversarial Review.html`'s charge sheet, verbatim, both marked **Guilty**:

- **c2, severity high, "Four ways to see more spots"** — *"Film strip scroll, ‹ › arrows, 'See all N',
  and a drilldown with three of its own filters. Four affordances for one intention, added in three
  separate rounds without removing anything."* Verdict: *"Guilty. Arrows and drilldown overlap badly.
  Keep swipe plus 'See all'; the arrows exist because a mouse cannot swipe, which is a reason to keep
  them only on desktop."* Recommendation: **cut**.
  **P11 ships the third and fourth of those four.** The arrows are already pointer-only by media
  query, which is half the verdict already served. The honest defence is the mock's own — the strip
  is for glancing, the sheet is for browsing, and the sheet's filters exist *because* it is the only
  surface where filtering a whole window makes sense. §6`:1320-1322` also says *"something should
  usually come out when something goes in"*: decide what, or say why nothing does.
- **c6, severity med, "Reach is stated in three places"** — Verdict: *"Guilty enough. One source: the
  lens shows it, the rail stops repeating it, the drilldown inherits and says 'widened for
  browsing'."* Recommendation: **cut**.
  **The mock already implements the fix** (`dReach = reach` on open, the `· widened for browsing`
  suffix only when they differ). Follow it, and check the rail is not repeating a reach — P4c/P8 may
  already have settled that.

---

## Citations that are stale or wrong — verified

| Where | Says | Actually |
|---|---|---|
| §5a`:617-619` | "No 'See all N →', and no 'N of M loaded'… **Both land with P11**" | **Half-stale.** `N of M` shipped at **P8** — `WindowSpotStrip.jsx:224-227` renders it whenever the lens withheld some, and `windowFirstCards.js:207-208` supplies `reachTotal`. Only "See all N →" is still P11's |
| §5c`:907-909` | assumes the rating floor and type control go "to the same row" as the reach tier | A scope assumption, not a citation error — and the mock disagrees. See decision 1. Whatever P11 chooses, update this bullet |
| mock `REACHES` / `LIM` | `30 / 60 / 120` | Shipped tiers are `45 / 90 / 150` (`reachLens.js:50`), labels derived from the threshold |
| mock `TYPES` | coast / river & lake / upland / landmark | No such enum. `LocationType` is LANDSCAPE / WILDLIFE / SEASCAPE / WATERFALL / BLUEBELL / CANOPY |
| mock `save()` | one key holding `{reach, dRate, day}` | The exact shape §5c`:866-876` rejected, for two independent reasons |

The six wrong citations the P10′ handover listed have all been **fixed in the plan doc** — `:74`,
`:687`, `:372`, `:373`, the `App.jsx:380` one, and §5a's "reusing `previewPlacement`". Do not go
looking for them.

---

## Running it locally

**Backend** (currently down):

```bash
cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local
```

Port **8083**. `-Plocal-dev` is load-bearing — H2 is `test` scope and that profile re-adds it at
runtime. `preview_start({name:"backend"})` also works now; give it ~50s and poll
`curl -o /dev/null -w "%{http_code}" http://localhost:8083/api/briefing` for a `401`.

**Frontend:** `frontend/.env.local` already sets `VITE_API_TARGET=http://localhost:8083`.
⚠️ Ports **5175–5178** are taken by earlier phases' `launch.json` entries — **add `frontend-p11` on
5179.** A `502` at the login screen means the backend is down, not that the proxy is misconfigured.

**Login** `admin` / `golden2026`. See trap 4 for the method that actually works.

---

## Standing gotchas

- Tokens in the plain `@theme` block are **pruned to the empty string** unless referenced by a literal
  `var()`. `@theme static` is not. Verify with `getComputedStyle(document.documentElement)
  .getPropertyValue('--x')` on the running app — that is how `--color-marginal` was caught, and it
  had been wrong for months.
- **Never `git checkout --` to restore a mutation on uncommitted work** — it deletes the change under
  test. `cp` to a scratchpad and back. Writes *into* the scratchpad are hook-blocked; `cp` works, and
  so does a bash heredoc.
- ⚠️ **A comment edit can silently kill the rule below it.** P9 appended prose after a `*/` and
  disabled `scroll-margin-top` for `.wf-spot`. Read the added rules back out of
  `document.styleSheets` on the running app — P10′ does this and it takes one `javascript_tool` call.
- **Measure each variant on its own backdrop, and composite the alpha.** P10′ reported a contrast
  figure that was wrong because it measured the token instead of the composite — a chip with its own
  `rgba(255,255,255,.05)` fill is not sitting on the panel colour. This project has now made the
  muted→secondary correction **seven** times; stop reaching for `--ink-3` /
  `--color-plex-text-muted` at small sizes on these surfaces.
- **ESLint will not catch the a11y rule here.** `jsx-a11y`'s recommended set exempts `aria-hidden`
  subtrees. The only enforcement is a hand-written test.
- **Mutation-check every new test**: `cp` the file, break the thing, confirm the *named* test fails,
  `cp` back. P10′ ran 65 and two survived — one an equivalent mutant (a genuinely dead branch, which
  was then deleted), one a test of its own that could not fail. Both were worth finding.
- A test that clicks a `disabled` button cannot fail. A fixture where two implementations give the
  same number cannot fail. `expect(x).not.toEqual(expect.arrayContaining([a,b]))` is **conjunctive** —
  use two `not.toContain`.
- Every PR conflicts on `CHANGELOG.md`.
- `main` moves under you — rebase before opening the PR. **`CodeQL` is a required check** alongside
  Backend and Frontend, and it has already had an opinion about `localStorage` on this feature.
- Backend suite needs Docker for 5 Testcontainers classes; locally use `-Dtest='!**/integration/**'`.
  **Gate on the exit code, never a grep of the output.**

---

## What P10′'s review found, because these species recur

Six prosecutor lenses over the staged diff, one refuter per charge prompted to **default to REFUTED
without citable evidence**, then synthesis. Eighteen charges, eight real, all fixed pre-commit.

- **The most valuable finding was a test defending a defect.** An unrated spot's peek drew `☆☆☆☆☆`,
  coercing *unknown* into *zero* and contradicting the card 10px above it, which omits its badge for
  exactly that reason — and one of the phase's own new tests asserted the `☆☆☆☆☆`. A green suite is
  not evidence; a suite that pins the wrong behaviour is worse than none.
- **Two defects were inherited verbatim from the v1 component being copied.** Closing the map overlay
  restored focus to the card, `onFocus` could not tell that from a reader arriving, and the peek
  re-opened unbidden 180ms after the ✕. Copying is the house rule — but a copy inherits bugs, and
  "the original does this" is not a defence.
- **The `focusin` asymmetry was found by review, not by any test.** It fires on a focus *change*, so
  it covered open-by-pointer-then-Tab-away and not the reverse. No test would have found it because
  no test knew to look.
- **The panel cannot see composition, scroll or paint. Run the browser.** P10′'s browser pass found
  `--color-marginal` resolving to the empty string, which lint, build and 2,714 tests all passed over.
- **Say what you could not stage.** P10′ could not exercise real scroll dismissal, touch gating, or
  anything against real ratings, and said so rather than implying coverage.

No screen reader, axe or Lighthouse scan has ever been run on this redesign, and every scroll and
paint measurement to date is Chrome only.
