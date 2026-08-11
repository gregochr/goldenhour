# PhotoCast — window-first Plan tab redesign. Continue at P14.

*Handover written 2026-08-09, immediately after P13 merged. Paste this as the opening prompt of the
new session. Every file:line and every number below was checked against `main` at `b4e4654f` on the
day this was written — but this project has had citations rot inside a week, three times now, so
**verify before building on any of them.***

⚠️ **P14 is a frontend phase, like P13.** Same ladder, same standards document
(`frontend-test-standards.md`), same review cadence. What is *different* from P13 is that P14 is
almost entirely CSS and layout, so the browser is not a final check — it is the whole instrument.
A responsive pass that is only unit-tested has not been done, because **jsdom has no layout**: it
computes no widths, applies no stylesheet, and resolves no media query.

## Read first, in this order

1. `docs/engineering/window-first-redesign-plan.md` — the spec. For P14: the **P14 row in §5**, then
   **§5a, §5c, §5e, §5f and §5h**, each of which parks something explicitly for the responsive pass.
   §5h is P13's and is the freshest.
2. `docs/design/window-first/README.md` **§"Responsive Behaviour"** (`:218-232`) — thirteen bullets,
   and the closest thing to a spec P14 has.
3. `docs/engineering/frontend-test-standards.md` — before touching any test. Note its line about
   responsive breakpoints being thresholds you *cannot* assert by rendering.
4. CLAUDE.md § "UI Work — Review Cadence" — **this phase is bound by it.** build → tests →
   adversarial review of the diff → fix survivors → re-verify → commit. Review agents are
   **READ-ONLY**. `git add -A` first.
5. `docs/engineering/window-first-p13-handover.md` — the previous handover. Its "Traps that carry
   forward" still hold, minus the two P13 corrected (below).

---

## State

- Phases done: P0…P12, **P13 (#453, merged 2026-08-09 as `b4e4654f`)**.
- **Branch from `main`.** At handover it is `b4e4654f`, all seven checks green.
- Frontend suite **122 files / 2,957 tests**. Backend surefire on that run: **5,387 run, 0 failures,
  2 skipped**. ⚠️ The P13 handover said 6,819 backend tests; I did not reconcile the difference and
  do not know which counting is right.
- Latest migration is still **`V139__tide_refresh_description_horizon.sql`** — read it off the tree
  before naming a new one; P14 should need none.
- Flag default is still `v1`. The flip is P15's. **Nothing P4–P13 built is visible to a pilot user.**
- ⚠️ **`main` is 1 commit ahead of the newest tag (`v2.17.14`), and `Deploy` fires only on
  `push: tags: v*`.** P13 is not in production. That is the owner's call, not something to fix in
  passing.

---

## Task — P14: the responsive pass

Make the v2 arm work on a phone. The README's §"Responsive Behaviour" is the spec; the mock
demonstrates it with a `.mob` class on the wrapper and says in as many words: *"implement as real
media queries."*

### ⚠️ The central problem, and it is not what the row implies

The P14 row reads "Responsive pass — real media queries". **You cannot write a media query against
an inline style, and three of the components the phone spec targets hardest are pure inline style
with no CSS class to hook.** Measured at `b4e4654f`:

| component | `.wf-` classes | `style={{` |
|---|---|---|
| `WindowFirstShell.jsx` | **0** | **14** |
| `WindowFirstDayRail.jsx` | **0** | **13** |
| `WindowPickDialog.jsx` | **0** | **13** |
| `WindowFirstWindowCard.jsx` | 4 | 12 |
| `WindowSpotPeek.jsx` | 4 | 12 |
| `WindowSpotCard.jsx` | 3 | 6 |
| `WindowSpotSheet.jsx` | 16 | 0 |
| `WindowFirstLensBar.jsx` | 10 | 0 |
| `WindowComingUpRow.jsx` | 9 | 0 |
| `WindowSpotStrip.jsx` | 7 | 0 |

Now read the mock's phone block (`Plan Window First v2.html:237-265`) against that table. Nine of
its first twelve rules target `.mast`, `.brand`, `.rail`, `.day`, `.railfoot`, `.tabs` and `.tab` —
every one of which lives in `WindowFirstShell` or `WindowFirstDayRail`, i.e. in the two files with
**zero** classes and twenty-seven inline styles between them.

So P14's first act is a **migration**, not a media query: the shell's masthead, tab bar and rail
footer, and the rail's tiles, have to move from `style={{}}` into `.wf-` rules before the phone
layout can be written at all. That migration touches the arm's spine, and it is a much larger,
riskier change than "add some media queries" sounds. **Decide and record how far it goes before
writing any of it** — the honest options are (a) migrate only the properties the phone layout
changes, leaving the rest inline; (b) migrate those three components wholesale; (c) something in
between, drawn on a stated line. Option (a) leaves one element's geometry split across two files,
which is its own trap.

Note the house pattern permits inline style — it is not a sin to be cleaned up. `index.css`'s own
convention, as P13's research established it, is: **`.wf-` class for anything needing a selector**
(pseudo-class, media query, descendant, `data-` variant), **Tailwind for stateless single-valued
layout**, **inline for off-scale numbers and anything computed at render**. A media query is a
selector. That rule already tells you which properties must move.

### What the design asks for, verbatim

From `README.md:220-232`, thirteen bullets. Reproduced because they are the spec and they are terse:

- Masthead: hide strapline and ghost buttons.
- Rail: `overflow-x:auto`, cells `flex:0 0 150px`, scrollbar hidden.
- Tabs and lens bar: horizontally scrollable, `flex-wrap:nowrap`; **keep group labels visible at 9px
  (do not hide them — the control would be unlabelled)**; hide the right-hand count.
- Window header: rule hidden; meta and badges each take a full row; the expand button pushes right.
- Attribute rows: single column, sparkline hidden, facts stack.
- Narrative: single column.
- Spot strip: `flex:0 0 72%` (~1.4 cards visible); arrows hidden.
- Promoted strip: title takes its own row; figures go 2-up; the *why* clause spans and left-aligns.
- Doors: stack.

⚠️ Several of these **already ship**. Do not re-implement them; check first. There are already
**nine** `@media (max-width: 639px)` blocks in `index.css`, covering `.wf-lens` (single scrolling
row, `.wf-lens-res` hidden, `.wf-lens-k` at 9px), `.wf-spots`, `.wf-rows`/`.wf-frow`/`.wf-mini`
(stacked, chart hidden), `.wf-sheet-*`, `.wf-doors` (stacked) and `.wf-cu-row`. The bullets that are
genuinely **not** built are the masthead, the rail, the tabs, the window header, and the spot
strip's `72%` sizing.

---

## ⚠️ Four traps that are already visible

**1. There is one breakpoint in this arm and it is 639px, stated in two places that must agree.**
`hooks/useIsMobile.js:3` is `const MOBILE_QUERY = '(max-width: 639px)'`, and `index.css` states the
same number in CSS so the sizing has one source of truth. `index.css:2138-2142` records the decision
to use 639 rather than the mock's 620, and why a second number 19px away is not worth keeping in
step. **The mock's own `.mob` wrapper is `max-width:390px` and its single media query is 620px** —
neither is this arm's number. Do not import either.

**2. Two v2 components branch on `useIsMobile` in JS, so a CSS-only pass will desynchronise them.**
`WindowSpotStrip.jsx` and `WindowFirstDoors.jsx` both call it (`CloseToHome` and `MapView` do too,
but those are the frozen v1 arm — leave them). Any breakpoint you add in CSS has to be the same
number, and any layout you move from JS to CSS has to not strand the other half.

**3. The taller rail tile on phone is in the P14 row and nowhere else.** The row says "including the
taller rail tile on phone"; the README says only `flex:0 0 150px`. P4c's decision that **every tile
reserves the two-line chip's height** (§5, P4c row) is what makes the rail's baselines align, and a
phone tile that grows has to keep that property or the rail goes ragged — which P4c already fixed
once. Whatever "taller" means, it must be a decision with a number and a reason, not a guess.

**4. `WindowSpotPeek` has no phone story and §5e decided it should not have one.** P10′ settled
**no phone peek** — the row that proposed a `BottomSheet` named no trigger, and the same paragraph
gives the phone's only tap to the map. So the peek's twelve inline styles are *not* P14's to
migrate, and a phone layout for it would be re-opening a closed decision. Read §5e before touching
it.

---

## Decisions P14 must make, and write down in §5i

- **How far the inline-to-class migration goes**, and where the line is drawn. See above. This is
  the phase's defining decision and everything else depends on it.
- **What the phone masthead keeps.** The mock hides the strapline and the "ghost" buttons. This
  arm's masthead is not the mock's: it carries `BrandLockup variant="compact"`, a ⚙ and a Sign out,
  and §7 records that the design's status pill was dropped. `usePlanLayout.test.jsx` pins that the
  masthead offers **exactly two** controls, so removing one on phone breaks a test that exists to
  stop controls creeping in — decide whether that test's rule is "exactly two" or "exactly two at
  desktop", and say which.
- **Whether the tab bar scrolls, and what happens when Map and Manage arrive.** The mock makes
  `.tabs` a horizontal scroller because it has four tabs. This arm has two, which fit. Building the
  scroller now is either forward-thinking or a control with nothing to scroll — §6 has an opinion
  about the second. P13's `TABS` array is the thing that will grow.
- **What the responsive pass is allowed to assert in a unit test, and what it must prove in a
  browser.** jsdom resolves no media queries, so `toHaveClass` and `toHaveAttribute` are the only
  honest unit assertions, and every geometric claim needs a measurement. Say plainly in §5i which
  claims were measured and at what widths.

---

## Traps that carry forward

1. **There is no local data path for the Plan tab.** `BriefingHonestyFilter.fullRewrite` empties
   every region's slots because the batch pipeline has never run locally, so the rich states need an
   injected fixture. **The Coming up tab is the exception** — it is ephemeris-driven and renders
   real content locally with no evaluation run (11 entries at the time of writing). It is the one
   pane you can judge on real data.
2. ⚠️ **`tide_extreme` is EMPTY locally**, so 6 of those 11 Coming-up rows render dates-only. That
   is the degrade path, free — but it also means **no enriched tide row has ever been rendered**, at
   any width.
3. **The Browser pane freezes `setTimeout` and dispatches no scroll events while hidden.** Sandwich
   timing-dependent checks between `computer{action:"screenshot"}` calls. The DOM is authoritative —
   read `getBoundingClientRect()`, not the screenshot.
4. **Read DOM state in a LATER tool call than the one that triggered it.** React batches.
5. **The Browser pane's `left_click` with a raw `coordinate` is in screenshot-pixel space, not CSS
   pixels.** Clicking by `ref` from `read_page` reports CSS coordinates and is the reliable form;
   a hand-computed coordinate silently missed the target repeatedly during P13. Also: **the first
   click after a `navigate` often does not register** — re-read the page and click again, or use
   `element.click()` via `javascript_tool`.
6. **`npm run test` is NOT the frontend CI job.** That job is lint → Vitest → `npm audit
   --audit-level=high` → build. Run all four. `npm run lint` is `eslint src --max-warnings 0`, so a
   *warning* fails CI.
7. **Tokens in the plain `@theme` block are pruned to the empty string** unless referenced by a
   literal `var()`. New tokens go in `@theme static` (`index.css:80`). Never assemble a token name.
8. ⚠️ **`--color-marginal` and `--color-dust` are declared nowhere** and are used by the frozen v1
   arm (`CloseToHome.jsx:48-49`, `CardHoverPreview.jsx:85`). Both resolve to nothing. Handed to P15;
   do not "fix" them inside a responsive pass.

### Two the P13 session corrected

- **`isDatesOnly` IS on the wire as `datesOnly`** — Jackson serialises the boolean getter. The P12
  and P13 handovers both said it was not. It is also a trap rather than a gift; see §5h.
- **The testid convention is not "prefix everything `wf-`".** `wf-` is the CSS-class prefix. Testids
  in this arm are long-form (`window-first-*`, `window-*`, `coming-up-*`); the actual rule is that
  nothing may use `cth-`, because `WindowSpotPeek.test.jsx:47-52` asserts globally that no `cth-`
  testid exists anywhere in the document.

---

## What P13 left unverified — do not assume otherwise

- **Nothing has been seen below 375px or above 1280px.** The Coming-up pane was checked at 1280 and
  at the 375 mobile preset only. No tablet width, no 400% zoom, no landscape phone.
- **No enriched tide row has ever been rendered** at any width — `range`, `rangeAnomaly`,
  `highWater`, `verdict`, `location` and the `figuresFrom` fallback are fixtures only.
- **No king-tide, solstice or `FORECAST` row exists in any real payload**, so three of the feed's
  rendering branches have never been seen outside a test.
- **No touch, no screen reader, no axe, no Lighthouse. Chrome only.** This is now true of every
  phase P4–P13 and it is accumulating; P15's sweep inherits all of it.
- The `14px 18px 20px` pane inset is a literal duplicated across `WindowFirstShell` and
  `WindowFirstComingUp` with a comment saying they must match and nothing enforcing it. §5h hands
  the arm's 18px gutter to P14 as a CSS rule.

---

## Running it locally

```bash
cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local
```

Port **8083**; `-Plocal-dev` is load-bearing (H2 is `test` scope). `frontend/.env.local` already
points `VITE_API_TARGET` at 8083 — without it Vite proxies to 8082, the Docker port, and every call
502s at the login screen.

Frontend: add a `launch.json` entry on **5182** (5173, 5175–5179 and 5181 are taken by earlier
phases; P13's is `frontend-p13` on 5181). Login `admin` / `golden2026`.

Flip to v2 through ⚙ → "Window-first Plan". The stored value is JSON-encoded — `'"v2"'` **with the
quotes** — so a hand-set `localStorage.setItem` silently does nothing.

Check the almanac feed is alive before judging the Coming up tab:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8083/api/almanac
```

`401` means it is up and secured.

---

## What the last three reviews found, because these species recur

- **The most valuable finding is a test that cannot fail.** P11 found three, P12 one, P13 **two of
  its own** — a `Storage.prototype` spy that the local jsdom substitute never routes through, and a
  hide assertion that only ever read the `hidden` attribute.
- ⚠️ **A test can be green on your machine and blind on CI.** `setup.js:27-37` installs a
  plain-object `localStorage` **only when jsdom does not supply one** — true on this project's Macs,
  false on the runner. So an instance spy on `setItem` passes locally and records nothing on CI, and
  a `Storage.prototype` spy does the reverse. **Never spy on `localStorage` in this suite**; observe
  through `length`/`key`. P13 lost a CI round to exactly this.
- **A fix can introduce the defect, and a fix can also under-deliver while claiming otherwise.**
  P12's threshold decoupling removed an invariant nobody knew was load-bearing. P13's first
  line-measure cap closed 6% of the gap under a comment saying the problem was solved, and its
  explanation of a CSS mechanism was wrong twice in opposite directions before a reviewer read
  Tailwind's preflight and settled it. **If you assert why a CSS rule behaves as it does, verify it
  in the browser rather than reasoning about the cascade.**
- **Measure, do not estimate, and put the measurement in the comment.** "About 150 characters" from
  a screenshot was out by 13 and the arithmetic justifying the fix was invented. A `Range` over the
  real text in the real font took one tool call.
- **Say what you could not verify.** Every handover in this series that named its gaps has had one
  of those gaps turn out to matter.
