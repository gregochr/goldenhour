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
