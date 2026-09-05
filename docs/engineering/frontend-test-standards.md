# Test Standards — PhotoCast Frontend

The counterpart to `test-improvement-standards.md`, which is **backend-only** — its title says so and
every example is Java and Mockito. The philosophy below is the same. Almost none of the mechanics are.

Read this before writing or modifying any frontend test.

---

## Philosophy (shared with the backend doc)

Tests exist to protect features, not to hit coverage targets. A test that passes when the feature is
broken is worse than no test at all.

Three questions every test must answer:

1. **What specific behaviour is being tested?** Not "the panel works" but "a window whose top region
   has no gloss renders no Best Bet block and still renders its spot strip."
2. **What exact inputs produce what exact outputs?** Specific values. If a threshold is at 2, test 1
   and 2 and 3.
3. **What breaks if this test fails?** If you cannot answer, the test is not protecting a feature.

And the rule that catches most of it: **if you cannot imagine a code change that would break this
test, it is not protecting anything.**

---

## The stack, and the conventions already in it

Vitest + React Testing Library + jsdom. `npm run test` (~50s for the whole suite), or
`npx vitest run src/test/Thing.test.jsx` for one file.

These are conventions the existing ~95 files already hold. Follow them; do not start a second style.

**Mock at the API-module boundary.** 60 files do `vi.mock('../api/somethingApi')` and resolve the
functions per test. Nothing mocks axios, and there is no MSW. A test that reaches for either is
introducing a third way to do what the suite already does one way.

```jsx
vi.mock('../api/briefingApi', () => ({ getDailyBriefing: vi.fn() }));
import { getDailyBriefing } from '../api/briefingApi';
// ...
getDailyBriefing.mockResolvedValue(BRIEFING_WITH_TWO_WINDOWS);
```

**`fireEvent` is the house style** (798 uses against 17 of `userEvent`). Reach for `userEvent` only
where the difference is the thing under test — typing that fires per-keystroke handlers, hover and
pointer sequences, tab order. Do not convert existing `fireEvent` calls in passing.

**Prefer `findBy*` to `waitFor` + `getBy*`.** There are 590 `await waitFor` calls in the suite and
many are a `findBy*` written the long way. `findBy*` is the same wait with a better failure message —
it names the element it could not find.

**And wait on something the fetch actually gates.** A wait is only worth what the element you waited
for proves. `SkyRatingEvalView.test.jsx` waited for the run button and then asserted the charts
synchronously — but the button sits *above* the component's `loading ?` gate, so it is on screen
from the first paint and the wait was satisfied before any data arrived. It passed on a quiet
machine and failed roughly one full-suite run in six. Wait for the thing the response renders.

**And never put the click inside the wait.** `waitFor` re-runs its callback every ~50ms, so a
`fireEvent.click` in there re-fires on every poll: a failing assertion on an expand/collapse toggle
collapses and re-expands the row twenty times a second instead of failing. That is a hang dressed as
a slow test, and it took a `--testTimeout` and a process kill to recognise. Await the element, click
once, then assert:

```jsx
fireEvent.click(await screen.findByTestId('expand-user-1'));
expect(screen.getByTestId('terms-accepted-1')).toHaveTextContent('1 Apr 2026');
```

---

## Timezone

**The suite runs in UTC, and that is a fact about the repository, not about your laptop.**
`src/test/setup.js` pins `process.env.TZ = 'UTC'`. Before it did, the machine's zone decided: dev
machines here are `Europe/London` and GitHub runners are UTC, so the two were running measurably
different tests. Both of those passed, which is why nobody noticed —
`TZ=America/New_York` did not, and the failure was a *product* defect (`UserManagementView`
formatting a UK date in the device's own zone) that a zone nobody ran had hidden.

Three rules follow:

- **Do not read the machine's zone in a fixture.** A bare `new Date().toLocaleDateString()` or a
  `new Intl.DateTimeFormat(…)` with no `timeZone` is answering a question about the runner. If the
  code under test means the UK calendar, the fixture must say `Europe/London` explicitly — the same
  rule the app itself now follows since #500.
- **A file may pin its own zone, and it wins.** Setup files run before the test module is evaluated,
  so a file-scope `process.env.TZ = 'Europe/London'` (four files) or `'America/New_York'`
  (`mapDatesAbroad.test.js`, `instantsAbroad.test.js`) is applied second. Put the assignment above the
  imports, as those files do.
- **Zone-separation coverage belongs in a file pinned to a non-UK zone.** Under a UK pin, "the UK
  calendar" and "the browser's calendar" are the same string and no assertion can tell them apart —
  and under the UTC pin they still never differ by a whole *day*, which is the form these defects
  keep taking. `mapDatesAbroad.test.js` (map dates), `instantsAbroad.test.jsx` (backend instants
  on nine surfaces) and `jobRunSlotDatesAbroad.test.jsx` (the admin run dialog's outgoing slot
  dates) exist for exactly that and say so at length. That is the designed detection
  mechanism; do not rely on whose laptop happens to run the suite.
- **An abroad file's own "is the pin still in force" guard has to be checked against its instants.**
  Asserting that the device date differs from the UK date is the usual form, but it only detects a
  lost pin when the device and *UTC* also differ — otherwise the file passes identically under the
  suite default and decays into a duplicate without failing. `jobRunSlotDatesAbroad.test.jsx` sits
  on an instant where New York and UTC share a date, so it asserts
  `Intl.DateTimeFormat().resolvedOptions().timeZone` directly. `instantsAbroad.test.jsx` hit the
  same rule from the other end a day earlier: its *hot-topic day word* block re-asserts the
  disagreement at its own instant rather than leaning on the file's opening check, because a day
  word only discriminates inside the divergent band. Both say the same thing — the guard has to be
  measured where the assertions are. Work out which form your instants need rather than copying a
  sibling's.

`testEnvironmentTimezone.test.js` asserts the pin is in force, because a deleted pin fails silently:
every test would go back to reading the developer's own zone and the suite would stay green on their
machine.

**A date fixture is written on a *basis*, and the basis is part of the code under test.**
`NlcSightingBanner.test.jsx` built its fixtures with local wall-clock literals and `setHours`, on
explicit and correct reasoning: the formatter read local getters, so pinning the wall clock "removes
the timezone from the question entirely". When that formatter moved to `Europe/London`, the reasoning
inverted — the local literal became the thing that moves with the runner, and `setHours(23, 10)` on
10 August is 00:10 BST on the *eleventh*, so the "yesterday" case tested the wrong branch. Three
files needed re-anchoring on explicit instants.

So when you change which calendar a formatter reads, **the fixtures are part of the change**, and the
comment explaining the old basis is the first thing to re-read: it is usually right about the code it
was written against and silently wrong about the code in front of you. Prove the result across zones
rather than on yours — `TZ=Pacific/Auckland` caught one of these that `TZ=America/New_York` did not,
because a zone *east* of the UK fails in the opposite direction.

---

## Queries: roles where there is a role contract, test-ids elsewhere

The suite is test-id heavy (~2,000 `getByTestId` against 63 `getByRole`), and CLAUDE.md asks for
`data-testid` on key elements, so this is not an instruction to convert anything.

But **where an element has a role contract, the test must assert through it**, because the role, the
accessible name and the state *are* the behaviour for anyone not using a mouse:

```jsx
// Not enough — passes even if the tab has no accessible name and never flips aria-selected
const t = screen.getByTestId('window-first-tab-map');
fireEvent.click(t);

// Right — the contract a screen reader and a keyboard user actually depend on
const t = screen.getByRole('tab', { name: 'Map' });
expect(t).toHaveAttribute('aria-selected', 'false');
fireEvent.click(t);
expect(t).toHaveAttribute('aria-selected', 'true');
```

Role queries are **required** for: anything interactive (`button`, `switch`, `link`, `checkbox`,
`radio`, `tab`, `slider`), dialogs, and headings. Test-ids are the right tool for data cells, chips,
strip items, and structural containers with no role of their own.

**Never `container.querySelector`.** There are 18 in the suite and each one is a test that stopped
describing the UI and started describing the DOM. If nothing can be queried, the component is missing
a role or a test-id — add one.

---

## Assertions

**No `toBeTruthy()` on a query result.** There are 24 in the suite. `getBy*` already throws when it
finds nothing, so the assertion adds nothing but noise; and on a `queryBy*` it passes for any
non-null, including the wrong element. Assert the thing you mean: `toBeInTheDocument()`,
`toHaveTextContent('4.0★')`, `toHaveAttribute('aria-checked', 'true')`.

**Assert the value, not that a value exists.** `expect(pill).toBeInTheDocument()` survives a change
that renders the wrong verdict. `expect(pill).toHaveTextContent('Worth it')` does not.

**Assert the negative too.** Most of this project's rules are about what must *not* appear — no Best
Bet when the gloss is null, no confidence marker outside the window badge, no "within reach" when
nothing was gated. Those need `queryBy*` + `toBeNull()`, and they are usually the more valuable half.

---

## What "boundary" means in a UI

The backend doc's rule — every threshold tested at the boundary, one below, one above — transfers
directly, and the thresholds are just somewhere else:

- **Numeric thresholds in derivation.** Confidence tiers by horizon: T+1 and T+2 either side of the
  HIGH/MEDIUM line, T+3 and T+4 either side of MEDIUM/LOW. A rating floor of 3 tested at 2, 3 and 4.
- **Count boundaries.** Zero, one, and many. A strip with one card, a region with one location, an
  empty list — these are where "3 spots" becomes "3 spot" and where a `.filter()` chain returns
  `undefined` instead of `[]`.
- **Absence.** null, undefined, empty string, empty array, and a field missing from the payload
  entirely are five different inputs, and legacy cached payloads make the last one real.
- **Responsive breakpoints** are thresholds. jsdom has no layout, so a rule that only exists in a
  media query cannot be asserted by rendering — assert the class or attribute the component sets, and
  leave the pixel behaviour to the browser check.

---

## Degrade paths get their own tests

This codebase's hardest-won rules are all about what happens when data is missing, and they are the
first thing a refactor quietly drops. Each one is a named test, not a branch inside a happy-path test:

- a carrier that is empty after a restart → falls back to the fact line, **never synthesises**
- a gloss the serve path nulled → no Best Bet block, the rest of the card intact
- no drive times for this user → every tier passes, the count drops the word "reach"
- no representative location → no chart, and no unattributed clock time
- a fetch that rejects → the escape hatch is still on screen

Write the test so its name states the rule. `survivesAFailedSettingsFetch` is a rule; `handlesError`
is not.

---

## Accessibility is assertions, not a manual pass

Every interactive element added or changed gets, at minimum:

- an accessible name asserted through `getByRole(role, { name })`
- its state asserted (`aria-checked`, `aria-expanded`, `aria-current`, `disabled`)
- keyboard operation where it is not a native `<button>`

Two traps specific to this codebase:

- **Borrowed classes bring the previous control's assumptions and not its guarantees.** Reusing
  `.quality-toggle-track` for something that is not the quality toggle inherits its animation and
  none of its focus treatment. `index.css` has explicit `:focus-visible` rules with comments about
  the 3:1 non-text contrast minimum — a new control needs its own, and a test that pins it.
- **`aria-hidden` decorative panels must never be the only route to anything.** `WindowSpotPeek` is
  `aria-hidden` precisely because the card behind it stays a real button. If a change makes such a
  panel the sole path to a destination, that is a defect, and a test should make it fail.

---

## Structure

- **One behaviour per test.** If the name needs "and", split it.
- **`describe` blocks name the surface; test names state the rule.** Read together they should form a
  sentence a non-author can check against the product.
- **Comment the *why*, never the *what*.** The valuable comment is the one recording why this case
  exists at all — the bug it prevents, the rule it pins. The suite already does this well; match it.
- **Fixtures near the top, shared and named for their shape** (`PRO_SETTINGS`, `LITE_WITH_HOME`).
  A fixture named for its meaning is reusable; one named `data` is not.
- **Do not test a hook through a bespoke harness component when a real consumer will do.** A `Host`
  wrapper written only for the test proves the harness works. Prefer rendering the component that
  actually uses the hook; keep a harness only for a hook with no single natural consumer, and say so
  in a comment.

---

## What NOT to do

- Do not add tests that assert a constructor set a field, or that a component rendered at all with no
  claim about what it rendered.
- Do not use `container.querySelector` — add a role or a test-id.
- Do not use `toBeTruthy()` where a specific matcher exists.
- Do not `setTimeout` or sleep. The suite has zero and should keep it that way — use `findBy*`,
  `waitFor`, or fake timers.
- Do not mock the component under test's own children to make a test pass. If it needs that, the test
  is at the wrong level.
- Do not skip or disable a test. Fix it or delete it.
- Do not assert on a snapshot of the whole tree. Nobody reads the diff, and it fails on every
  unrelated change.
- Do not stub an async action with a promise that never settles. React 19 entangles async actions
  process-wide: while one is in flight, the state a *later* `useActionState` returns waits on it. So
  one `new Promise(() => {})` does not stay inside its own test — every subsequent submit in the
  file sits on its pending label with its error state never committing. Hand the test the resolver
  and settle it before the test ends; `OutcomeModal.test.jsx` and `LoginPage.test.jsx` both do, with
  an `afterEach` net so an assertion that throws first cannot poison the rest of the file.
- Do not depend on a mock implementation some other test installed. `vi.clearAllMocks()` clears
  calls, not implementations, so a `mockResolvedValue` is still in force in the next test and in the
  next `describe`. Re-assert every default a suite relies on in its own `beforeEach`, and give every
  `describe` its own setup rather than borrowing a sibling's. `DailyBriefing.test.jsx` (since deleted,
  v1 retirement D2) failed both ways at once: a leaked Close-to-home panel put a second "Keswick" on
  screen and broke an unscoped `getByText` elsewhere, and a block with no `beforeEach` got `undefined`
  back from `useAuth`.
- Do not assume file order protects you, and do not assume it can hurt you either. `isolate: true`
  gives every test *file* its own process — nothing leaks between files — so a suspected flake is
  either inside one file or is not a flake at all. `--sequence.shuffle.tests --sequence.seed=N`
  reproduces an intra-file order dependency deterministically; use it before reaching for anything
  cleverer. ⚠️ **But shuffling cannot find the flake below**, and reading it as an order dependency
  is the wrong turning — see the next section.
- Do not put an assertion inside a raw `requestAnimationFrame` (or `setTimeout`) callback and settle
  a promise after it. A throw there never reaches the `resolve()` on the next line, so the promise
  never settles and the test does not fail — it **hangs to `testTimeout`**. The failure you get is a
  bare `Test timed out in 20000ms` naming only the `it(` line; the real `AssertionError` arrives
  separately as a stray unhandled error under Vitest's "the latest test that might've caused the
  error is…" hedge, detached from the test that produced it. Use the waits in **Waiting for a
  deferred effect** below.

---

## The first test in a file is not like the others

A file that renders a component behind `React.lazy` pays for those chunks in whichever of its tests
runs first. That cost is per-**file**; the budget it is charged against is per-**test**. On an idle
machine it is invisible; under load it is seconds. Measured on the Plan shell under a 20-process CPU
load: the first `findByTestId('wf-heat-strip')` took 1051 ms and the first
`findByTestId('window-sheet')` 797 ms, where every later call in the same file took 3.5 ms and 50 ms.

**It does not present as a slow test — it presents as `Test timed out in 5000ms` naming only the
`it(` line.** Testing Library's ceiling here is `asyncUtilTimeout: 4000` (`src/test/setup.js`), so a
test crossing two boundaries in sequence can exhaust the whole budget before either `findBy*`
reports which wait was stuck. `vite.config.js` now sets `testTimeout: 20000` for that reason; its
note carries the derivation.

- **Do not reach for `--sequence.shuffle.tests` first.** It relocates this cost onto whichever test
  lands first, so every seed fails somewhere different and it reads as an order dependency that does
  not exist. **The reproduction that works is concurrency plus CPU starvation**: run the full suite
  N times at once (3 is enough) with ~16 busy-loop processes running. It failed 3 of 3 runs where a
  single file, and even the thirteen shell files together, stayed green under the same load.
- **Report `findBy*` figures as wall times.** Such a wait covers module resolution, the lazy
  suspend, the component's first render and up to a 50 ms poll tick. Calling one "the lazy boundary"
  and then adding a separate line for "the first render" double-books the render — which is how one
  investigation of this reached a wrong conclusion about its own residue.
- ⚠️ **Warming the boundaries in a `beforeAll` was tried, measured and rejected. Do not rebuild it.**
  A `warmPlanChunks` helper that `import()`ed the shell's four lazy chunks, called from thirteen
  files, did work: it cured the popup's wait outright (797 ms → 2.4 ms). It is still not worth
  carrying. With it neutralised and the raised ceiling in place, the same reproduction is green 3 of
  3 with a worst first test of 5744 ms against 5065 ms with it — a 12% tail reduction on a budget
  with 2.7x headroom at the worst first test measured anywhere (7412 ms). And its membership rule ("renders the real shell", not "imports it") was wrong
  at BOTH ends on the first attempt: a file that `vi.mock`ed the shell was warmed for nothing, and a
  file reaching it through `App.jsx`'s static import was missed. A mechanism that is easy to get
  wrong and changes no outcome earns nothing.
- **What the timeout costs, since it is not free.** A tight per-test budget is this suite's only
  performance-regression detector, and this defect was found *because* the budget was tight enough
  to fail on it. At 20 s a shell test that regresses to 6 s passes silently. That is the trade; the
  4000 ms Testing Library ceiling still catches the ordinary "element never appears" case.

---

## Waiting for a deferred effect (a frame, not a tick)

Some behaviour in this app is deliberately deferred by one animation frame rather than run in the
commit that causes it. `useDialogFocus` is the one most tests meet: it moves focus to the dialog root
inside a `requestAnimationFrame`, because a dialog that mounts in the same commit as its content
would otherwise take focus before the browser has laid anything out, and Safari drops that focus
silently. `WindowFirstShell`'s tab-moving and matrix handoffs defer too, but for their **own,
different** reason — the element they focus may be rendering for the first time on that very commit
(`WindowFirstShell.jsx:694`, `:1162`) — so do not merge the two rationales when citing one. **A test
of such behaviour has to wait for a frame, and *how* it waits decides what a failure looks like.**

Three forms are in use. Two are waits; the third is a substitution.

- **Real timers — `await waitFor(...)`.** The default, and the one to reach for.
  `BottomSheet.test.jsx` and `Modal.test.jsx`'s `focus` block are the reference sites:

  ```js
  it('takes focus when it opens', async () => {
    render(<BottomSheet open onClose={vi.fn()}><p>Body</p></BottomSheet>);
    await waitFor(() => expect(screen.getByTestId('bottom-sheet')).toHaveFocus());
  });
  ```

  `waitFor` re-runs the callback and *catches* its throws, so a genuine failure surfaces as the
  expectation's own message at the 4000 ms `asyncUtilTimeout`, not as a test-level timeout.

  ⚠️ It waits for the answer to become right, which is not the same as the answer *being* right.
  Where the claim is "and it **stays** there", force a frame and assert after it:
  `await new Promise((r) => { requestAnimationFrame(() => requestAnimationFrame(r)); })` with the
  expectation OUTSIDE the callback (`Modal.test.jsx:156` and `:410`). At `:410` that is load-bearing
  and measured: flipping `useDialogFocus(true)` to `useDialogFocus(!stacked)` leaves the preceding
  `waitFor`-shaped assertion passing 20 of 20 idle while the forced-frame one fails 20 of 20, because
  that mutant schedules a SECOND frame which only a forced frame reveals.

- **Fake timers — `act(() => vi.advanceTimersByTime(n))`.** Where a file already runs on fake timers,
  advancing them lands the frame. This works because Vitest's fake timers **do** fake
  `requestAnimationFrame` (measured, not assumed: under `vi.useFakeTimers()` a rAF callback does not
  run until the clock is advanced). `WindowFirstShellSheet.test.jsx`'s `settle()` helper is the
  reference site — and it is load-bearing rather than decorative: a mutation sweep found that
  hovering before the focus move settled produced a test that passed with the behaviour under test
  deleted, because it was pinning the focus rule instead of the suppression.

- **Substituting a synchronous rAF** —
  `vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => { cb(); return 0; })`. Not a
  wait at all: it collapses the frame so a *synchronous* sequence can be asserted in order —
  "focus went **here** and not there" — which no `waitFor` can express, since waiting for the right
  answer cannot distinguish it from the wrong answer arriving first. `WindowFirstShell.test.jsx`,
  `WindowFirstShellTabs.test.jsx`, `planOriginShell.test.jsx`,
  `WindowFirstShellLocationSheetHandoff.test.jsx` and `locationSheetShell.test.jsx` all use it — the
  last of those wraps the spy across two lines, so audit the set by grepping `mockImplementation`
  rather than the whole one-line form. Restore it in a `try/finally`, or rely
  on a file-level `afterEach(() => vi.restoreAllMocks())` — but have one of the two, because
  `vi.clearAllMocks()` clears calls, not implementations, and a leaked synchronous rAF **immunises**
  every later test in the file against exactly the deferral they exist to check.

### ⚠️ Never assert inside the rAF callback itself

There is a fourth shape, and it is the one to avoid. It was live in this suite until it was
measured out:

```js
// DO NOT. A throw inside the callback never reaches `resolve()`.
return new Promise((resolve) => {
  requestAnimationFrame(() => {
    expect(document.activeElement).toBe(screen.getByTestId('window-sheet'));
    resolve();
  });
});
```

It passes and it fails on the right behaviour — so it is not *wrong*, it is **undiagnosable**.
Measured on one mutant (`dialog.focus()` deleted from `useDialogFocus`), same file, same machine:

| form | fails at | message |
|---|---|---|
| raw rAF callback | **20009 ms** — i.e. `testTimeout`, whatever it is set to | `Test timed out in 20000ms.`, pointing at the `it(` line |
| `await waitFor(...)` | **4768 ms** | `expect(element).toHaveFocus()`, attached to the test |

The assertion is not wholly lost in the first row — it resurfaces as a detached unhandled error with
Vitest's "might've caused" hedge — but the *failure* is a timeout, and a bare timeout is the exact
symptom the previous section spent a whole investigation decoding. Raising `testTimeout` to 20000 ms
made this form four times slower to diagnose than when it was written, which is what prompted the
rewrite.
