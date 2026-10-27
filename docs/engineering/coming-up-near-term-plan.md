# Coming up — keep near-term entries in the chronology

**Status:** planned 2026-10-27. One PR.
**Amends:** `coming-up-plan.md` D1's eligibility rule and §11.10.

## Why

D1 admits an almanac event to the Coming up chronology only if `endDate > PlanHorizon.lastPlanDate(today)`
(today+3). An event wholly inside the next four days is shown only as a "Next four days" tile chip.
In practice that throws away the most useful card at the moment it matters most: on 2026-10-27 the
4.8 m spring tide run (Wed 28 – Fri 30) showed as three truncated "Spr… 4.8 m" chips, while the only
full card on screen was the smaller 4.1 m run twelve days away. The tile cannot carry the prose, the
superlative, the best spot, the high-water-vs-light line or the comparison with other runs.

**Owner decision (2026-10-27):** every event that has not yet ended keeps its card, including short
ones wholly inside the four days (an eclipse tonight, a supermoon tomorrow). The tiles stay as a
pointer, and the card is the explanation.

## New rule

An almanac event becomes a `ComingUpEntry` iff `!endDate.isBefore(today)`, i.e. it has not ended.
There is no longer any reference to Plan's horizon in eligibility.

## What already handles this (no change needed)

- **"In next 4 days" tag**: `comingUpFeed.js` `overlapsNext4Days` → `inNext4Days`, rendered by
  `WindowComingUpEntry` (`coming-up-tag-next4`). Its `endDate >= todayStr` half stops being merely
  defensive.
- **Tile → card jump**: `fourDayTiles.js` `jumpFor` already prefers a rendered entry covering the
  chip's date over the Glance route. A near-term chip now scrolls to its card ("shown in the list
  below") instead of opening Glance. This is the intended effect.
- **Date rail**: `countdownFor` already prints `now` for a span under way, then `tomorrow`.
- **Badge / since-line**: `enteredWindow` is `startDate − 89 days`, so a near-term entry entered the
  window long ago and never badges as new. No change.
- **Coincidence merge (D10)**: a near-term supermoon and run are now both eligible and merge through the
  ordinary eligible-pair path, with the same result the partner path gave.

## Changes

### Backend
1. `AlmanacService.assemble`: `eligible = all` filtered by `!event.endDate().isBefore(builtFor)`;
   call the two-argument `assembler.assemble(builtFor, eligible)`. Drop the `planOwned` list and its
   comment. Rewrite the class Javadoc's **Eligibility** paragraph to the new rule and cite this plan.
2. `ComingUpAssembler`: the `partnersOnly` path is now unused by production. **Keep it in this PR**
   (its own tests still cover it). Add one Javadoc sentence on the three-argument `assemble` saying
   production no longer passes partners (this plan) and the path is a candidate for removal. Removing
   it is a separate cleanup.
3. `PlanHorizon` stays because `BriefingService` uses it for the hot-topic window. Update its Javadoc
   if it claims to drive Coming up eligibility.
4. Tests:
   - `AlmanacServiceTest`, the "Eligibility (plan D1)" section: rewrite to the new rule. Pin these cases:
     an event wholly inside today..today+3 is served; a single-day event today is served; a straddling
     run is served; an event that ended yesterday (if a source returns one) is not; and the
     assembler receives no partners. Update the mock helper that matches `(eligible, planOwned)`.
   - `ComingUpAnnualBadgeCensusTest`: mirror the new filter (it copies production's). Its band
     assertions must still pass unchanged. If they don't, stop and report: that would mean the
     calibration depended on the old rule.
   - `PlanHorizonTest`: only if it asserts the Coming up rule.

### Frontend
5. `WindowFirstComingUp.jsx` empty state: `No dates coming up in the next ${ALMANAC_DAYS} days.`
   (drop "beyond the four-day forecast"). Keep the ⚠️ comment's point (scoped to the dated list), but
   rewrite its wording. Update `WindowFirstComingUp.test.jsx:125`.
6. Comments that state the old rule as fact: `comingUpFeed.js` (`overlapsNext4Days` "DEFENSIVE"
   note; `countdownFor`'s "plan D1's eligibility rule admits only…"), `fourDayTiles.js` class doc ("a
   run that ends inside the four days has no entry"), `comingUpHandoff.js` if it says the same.
   Comment-only edits, no logic change.
7. Tests: add or extend one `fourDayTiles` test proving that a chip on a date covered by a rendered entry
   lying wholly inside the four days gets `jump` (not `target`). Extend one `comingUpFeed` test proving
   that such an entry gets `inNext4Days: true` and a `now`/`tomorrow` countdown.

### Docs
8. `coming-up-plan.md`: under D1, add a dated "Superseded 2026-10-27" note pointing here; amend §11.10
   the same way.
9. `docs/engineering/reference/plan-tab.md` (the "In next 4 days" tag paragraph): drop "the `endDate`
   half is defensive…". Add a line that near-term entries are served (this plan).
10. `docs/engineering/reference/ask-photocast.md`: if it describes the Coming up snapshot as
    "beyond four days", correct it.
11. `changelog.d/20261027-coming-up-keep-near-term.md`, `### Changed — …`.

## Side effects accepted

- **Ask** (`AskSnapshotBuilder.comingUp`) now also sees near-term almanac entries, alongside the hot
  topics it already had. That is more context, not a contradiction. Reviewers should check that no Ask
  guard or test assumes Coming up entries start after today+3.
- **Wider comparisons**: superlatives and "the other N runs in this window ranged…" are computed over
  eligible runs, so they now include the near-term run. The figures change; that is the correct reading.
- **Duplicate display**: the same event now appears as a tile chip and a card. That is the owner's call.

## Gates

Backend build ladder through `clean verify` (with the integration exclusion); frontend lint → test →
audit → build.
