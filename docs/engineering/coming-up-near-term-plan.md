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
  ordinary eligible-pair path (`mergeCoincidences` / `findSupermoonPartner`). Two deliberate
  differences from the partner path: a near-term tide run now gets its superlative and threshold line,
  because it joins `markTideSuperlativesAndThresholds`; and two near-term events with no eligible
  partner now produce a merged card where before they produced nothing.

## Changes

### Backend
1. `AlmanacService.assemble`: `eligible = all` filtered by `!event.endDate().isBefore(builtFor)`;
   call the two-argument `assembler.assemble(builtFor, eligible)`. Drop the `planOwned` list and its
   comment. Rewrite the class Javadoc's **Eligibility** paragraph to the new rule and cite this plan.
2. `ComingUpAssembler`: the `partnersOnly` path is now unused by production. **Keep it in this PR**
   (its own tests still cover it). Add one Javadoc sentence on the three-argument `assemble` saying
   production no longer passes partners (this plan) and the path is a candidate for removal. Removing
   it is a separate cleanup.
2a. Stale comments elsewhere: `TideRunPeakHistory.java` ~98–100 ("Eligibility only requires an entry to
   end beyond Plan's window"); `ComingUpAssembler` two-arg `@param` (~147), three-arg `@param` (~165)
   and the `partnerOnly` field doc (~296); `AlmanacService` method Javadoc and comments ~286–304.
3. `PlanHorizon` stays because `BriefingService` uses it for the hot-topic window. Update its Javadoc
   if it claims to drive Coming up eligibility.
4. Tests:
   - `AlmanacServiceTest`: the stubs at ~391–401 (`aFailedRefreshKeepsThePreviousFeed`, a spy stubbing
     the THREE-arg `assemble` with a partner list) and ~447 (`failingAssembler` on a plain mock) must
     be re-pointed at the TWO-arg signature, or they silently stop matching. The partner-case tests at
     ~596 and ~615 need rewriting to the new rule, not deleting their intent. The "eligible set" now
     includes them. Sources never return an event ending before `from`, so the "ended yesterday" case
     needs a stub source.
   - `AlmanacServiceTest`, the "Eligibility (plan D1)" section (~549): rewrite to the new rule. Pin these cases:
     an event wholly inside today..today+3 is served; a single-day event today is served; a straddling
     run is served; an event that ended yesterday (if a source returns one) is not; and the
     assembler receives no partners. Update the mock helper that matches `(eligible, planOwned)`.
   - `ComingUpAnnualBadgeCensusTest` (~154): mirror the new filter. It already calls the two-arg assemble. Its band
     assertions must still pass unchanged. If they don't, stop and report: that would mean the
     calibration depended on the old rule.
   - `PlanHorizonTest`: only if it asserts the Coming up rule.

### Frontend
5. `WindowFirstComingUp.jsx` empty state: `No dates coming up in the next ${ALMANAC_DAYS} days.`
   (drop "beyond the four-day forecast"). Keep the ⚠️ comment's point (scoped to the dated list), but
   rewrite its wording. Update `WindowFirstComingUp.test.jsx:125`.
5a. `comingUpFeed.js` `FOOTER` (shown on every status) says "This list starts where the four-day
   forecast stops…", which becomes false. New copy: `This list shows dates fixed in advance, from today
   on. Weather-driven topics can't be known that far out, so they appear only in Next four days.`
   Update its Javadoc and the two tests that pin it (`comingUpFeed.test.js` ~839,
   `WindowFirstComingUp.test.jsx` ~383).
6. Comments that state the old rule as fact: `api/almanacApi.js:20-21`, `comingUpFeed.js` (`overlapsNext4Days` "DEFENSIVE"
   note; `countdownFor`'s "plan D1's eligibility rule admits only…"), `fourDayTiles.js` class doc ("a
   run that ends inside the four days has no entry"), `comingUpHandoff.js` if it says the same.
   Comment-only edits, no logic change.
7. Stale test wording: rename `comingUpFeed.test.js` ~881 ("DEFENSIVE … server never serves one
   ending inside the window"). The case is now normal. Reword the ⚠️ comment on `fourDayTiles.test.js` ~208
   so the run is "not rendered" (e.g. filtered out), not "not served".
   Tests: add or extend one `fourDayTiles` test proving that a chip on a date covered by a rendered entry
   lying wholly inside the four days gets `jump` (not `target`). Extend one `comingUpFeed` test proving
   that such an entry gets `inNext4Days: true` and a `now`/`tomorrow` countdown.

### Docs
8. `coming-up-plan.md`: under D1, add a dated "Superseded 2026-10-27" note pointing here; amend §11.10
   the same way. `supermoon-tide-run-plan.md` §4.7 (Plan-owned partners) gets the same note.
9. `docs/engineering/reference/plan-tab.md` (the "In next 4 days" tag paragraph): drop "the `endDate`
   half is defensive…". Add a line that near-term entries are served (this plan).
11. `changelog.d/20261027-coming-up-keep-near-term.md`, `### Changed — …`.

## Side effects accepted

- **Enrichment failure scope**: near-term events now go through the unguarded eligible staging loop.
  Partners were staged inside a try/catch and dropped on failure. A failing near-term event now fails
  the build exactly as a far-term one always has: `refresh` keeps the previous feed, and a cold build
  throws. This is consistent with the far-term behaviour, so it is accepted without a new guard.
- **Ask `get_coming_up`**: `AskSnapshot.listedBy` now suppresses near-term SPRING_TIDE / SUPERMOON /
  LUNAR_ECLIPSE hot topics in that tool, because the almanac entry lists them. `get_hot_topics` still
  returns them. That is benign. No Ask guard or test assumes entries start after today+3 (`AskSnapshot.timeline`,
  `AskReadyFreshness.liveEvent` already filter on "not ended").

- **Ask** (`AskSnapshotBuilder.comingUp`) now also sees near-term almanac entries, alongside the hot
  topics it already had. That is more context, not a contradiction. Reviewers should check that no Ask
  guard or test assumes Coming up entries start after today+3.
- **Wider comparisons**: superlatives and "the other N runs in this window ranged…" are computed over
  eligible runs, so they now include the near-term run. The figures change; that is the correct reading.
- **Tile route**: a near-term non-weather chip (tide, supermoon, meteor, eclipse) used to open the
  Plan window card for that date (`onGoToPlanWindow`). Because a jump takes priority, it now scrolls to its
  Coming up card instead, so that chip loses its door to the Plan window. Weather chips are unchanged.
- **Doors on the first qualifying entry**: the Lite tide-light Pro door and the S5b-2 outside door
  (`WindowFirstComingUp.jsx` ~121-147) will usually land on the near-term run now. That is better.
- **Month grouping** is by start month, so on 1 Nov a 30 Oct – 2 Nov run heads the list under "Oct".
  That already happened for runs crossing the boundary; it is now more common. Accepted.
- **Duplicate display**: the same event now appears as a tile chip and a card. That is the owner's call.

## Gates

Backend build ladder through `clean verify` (with the integration exclusion); frontend lint → test →
audit → build.
