# Month rules in Watching (2026-11-25)

Status: **planned, not built; decisions W1 and W2 made.** Queued by the owner. One frontend PR, built by Sonnet per CLAUDE.md's owner rule. It
amends `screen-spec.md` D2 (§6.2).

## 1. What prompted it

The owner, on desktop, with three watched tide runs: Watching lists them as `23–29`, `9–12` and `21–27` with no month,
while the month lists below say `DEC 2026` and `JAN 2027`. "I'm not sure which month the tides occur in." The date rail
prints days only, because in the month lists the month rule above it supplies the month. Watching
(`FurtherAhead.jsx`, `further-ahead-watching`) reuses the same entry cards but not the month rules, so its rail has
nothing to read against. Its order (soonest first) is also invisible once it wraps a year: `23–29` above `9–12` reads as
a sorting error.

## 2. The change

**Watching draws the same month rule as the month lists, above each month's run of watched rows.**

- **Grouping.** `groupEntriesByMonth` (`utils/comingUpFeed.js`) on the watching views: the same function, so a run that
  crosses a month sits under the month it starts in, exactly as below. No new date logic; this is the same licensed
  view grouping the month lists use (`architecture-decisions.md`, "Backend-heavy": "month grouping").
- **The rule.** The same markup and classes as the month lists (`wf-cu-month`, `wf-cu-month-name`, `wf-cu-month-year`,
  `wf-cu-month-rule`): `DEC 2026 ───`. Drawn even when every watched row is in one month (the point is to say which
  month). The first rule in Watching gets `data-first` (11px top padding) so it sits close under the Watching heading.
- **Lists.** As in the month lists, one `role="list"` per month, because a list may only own list items. The rule is a
  sibling before its list, not inside it. Its `aria-label` is `Watching, December 2026`, not the month lists' bare
  `December 2026`, so a screen reader's list of lists never names two different lists the same.
- **"No longer listed" rows.** Each has a date (`unlistedWatches` returns `date`). They go into the month of that date,
  after that month's cards, so every row in Watching is under a month. A month that holds only such rows still gets its
  rule. (Today they trail the cards as one run, which already hides their month: the visible text is
  `King tide · no longer listed`.)
- **Test ids.** The Watching rules and lists must NOT reuse `coming-up-month` / `coming-up-list`: tests and the E2E read
  those as "the month lists" (`monthIds()` in `FurtherAheadWatching.test.jsx` would start counting watched entries).
  Use `watching-month` and `watching-month-list`. `watching-list` moves to a wrapper `div` around the month groups,
  `role="group"` with `aria-labelledby="further-ahead-watching-h"`, so `ids(screen.getByTestId('watching-list'))` and
  `roles-write.spec.js:102` still find every watched card. The one test that asks for the old single list by role,
  `FurtherAheadWatching.test.jsx:94` (`getByRole('list', { name: 'Watching' })`), is rewritten to the group.
- **Focus.** Unchanged. The move-focus effect finds the toggle by `data-entry-id` and the unlisted Stop button by
  `data-watch-key`, wherever they sit. `stopUnlisted(row, index)` must keep receiving the row's index
  in the flat, date-sorted `unlisted` array, not its index inside its month group (precompute it, or
  `unlisted.findIndex((r) => r.key === row.key)`). With that, "the next unlisted row" is still the next one down the
  page, because both inputs are date-sorted and the groups are in month order (the tests at
  `FurtherAheadWatching.test.jsx:240–250` pin it).
- **No new data, no backend change.**

**Height (spec §4).** Each rule is about 40px (19 + ~12 + 9), the first about 32px. Watching over two months grows by
about 72px; one month by about 32px. Watching sits below This week, so nothing in §4's first-screen budget moves. It
displaces nothing; Further ahead's month lists start that much lower.

## 3. Owner decisions

Both decided by the owner on 2026-11-25: **W1 yes** (a rule even over a single month) and **W2 yes** (the year always printed).

- **W1, a rule over a single month.** *Recommended: yes, always.* The month is the missing fact even with one row.
  Alternative: only when Watching spans two or more months, which saves ~32px but leaves a lone `9–12` unexplained.
- **W2, the year.** *Recommended: as the month lists, always printed* (`DEC 2026`). Consistent, and the feed already
  does it.

## 4. Build (one PR)

1. `FurtherAhead.jsx`: build the Watching groups as a `Map` keyed on the month (`startDate.slice(0, 7)` for a card,
   `date.slice(0, 7)` for an unlisted row), cards first then unlisted rows within a month, and render the keys sorted.
   (`groupEntriesByMonth` alone is not enough: it only groups consecutive months, and an unlisted-only month can fall
   between two card months.) Render wrapper → per month (rule + list) as §2. Share the month-rule markup with the month
   lists (a small local component, `MonthRule`, with a `first` prop: `data-first` goes on Watching's first group and,
   separately, on the month lists' first group, as today).
2. CSS: none expected; the existing `.wf-cu-month*` rules apply. If the first rule's padding against the Watching
   heading looks wrong, adjust under `.wf-cu-watching .wf-cu-month[data-first]` only.
3. **Tests** (`FurtherAheadWatching.test.jsx`, frontend standards):
   - watched entries in December and January draw two `watching-month` rules, `DEC 2026` and `JAN 2027`, each above its
     own entries, and the lists are named `December 2026` / `January 2027`;
   - a single watched month still draws its rule (W1);
   - a run crossing a month sits under its start month;
   - an unlisted row sits in its date's month, after that month's cards; an unlisted-only month gets a rule, including
     one that falls BETWEEN two card months (December card, January unlisted, February card: three rules in order);
   - the lists are named `Watching, December 2026`, and the wrapper is the group named `Watching` (the rewrite of :94);
   - Stop watching on an unlisted row in the second month focuses the next unlisted row (global index);
   - `monthIds()` and the month lists' `coming-up-month` count are unchanged by watching (no leakage);
   - the other move-focus and Stop-watching focus tests still pass unchanged.
4. **E2E:** `roles-write.spec.js` ("the watch moves into Watching") should pass unchanged; add an assertion that the
   watched card sits under a `watching-month` rule.
5. **UI review cadence:** one adversarial review of the diff (a11y of the per-month lists, focus after a move, the
   unlisted merge order, no test-id leakage). Check in the browser on local 8083 at 390×844 and 1280; say which claims
   were seen and which only tested.
6. **Docs in the same PR:** `screen-spec.md` §6.2 (Watching has the month rules); `plan-tab.md`'s Watching sentence;
   changelog `changelog.d/YYYYMMDD-watching-month-rules.md`.
7. **Gates:** the frontend four, diff-cover, the E2E smoke.

## 5. Review record (2026-11-25, one adversarial agent)

| # | Finding | Taken |
|---|---|---|
| 1 | `FurtherAheadWatching.test.jsx:94` asks for the list named Watching, which goes | §2: the wrapper is a group named Watching; the test is rewritten |
| 2 | `stopUnlisted` needs the flat index, not the group's | §2 Focus, §4.1, a test |
| 3 | Two lists both named `December 2026` | §2: `Watching, December 2026` |
| 4 | `groupEntriesByMonth` only groups consecutive months; an unlisted-only month can sit between | §4.1: a Map sorted by month, a test |
| 5 | `data-first` needs a prop on the shared rule | §4.1 |
| 6–7 | Other test ids, Open all and move-focus are unaffected | Verified, no change |
