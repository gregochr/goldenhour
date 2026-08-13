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
vi.mock('../api/briefingApi', () => ({ getDailyBriefing: vi.fn(), getCloseToHome: vi.fn() }));
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
// Not enough — passes even if the switch has no accessible name and never flips aria-checked
const t = screen.getByTestId('settings-plan-layout-toggle');
fireEvent.click(t);

// Right — the contract a screen reader and a keyboard user actually depend on
const t = screen.getByRole('switch', { name: 'Window-first Plan' });
expect(t).toHaveAttribute('aria-checked', 'false');
fireEvent.click(t);
expect(onChange).toHaveBeenCalledWith('v2');
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
- **`aria-hidden` decorative panels must never be the only route to anything.** `CardHoverPreview` is
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
  `describe` its own setup rather than borrowing a sibling's. `DailyBriefing.test.jsx` failed both
  ways at once: a leaked Close-to-home panel put a second "Keswick" on screen and broke an unscoped
  `getByText` elsewhere, and a block with no `beforeEach` got `undefined` back from `useAuth`.
- Do not assume file order protects you, and do not assume it can hurt you either. `isolate: true`
  gives every test *file* its own process — nothing leaks between files — so a suspected flake is
  either inside one file or is not a flake at all. `--sequence.shuffle.tests --sequence.seed=N`
  reproduces an intra-file order dependency deterministically; use it before reaching for anything
  cleverer.
