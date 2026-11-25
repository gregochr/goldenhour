# The card says why a window was not scored (2026-11-25)

Status: **planned, not built.** The owner has queued it behind other work. Two phases, C1 (backend) then C2
(frontend), one PR each, built by Sonnet under CLAUDE.md's owner rule. Revised after one adversarial review
(§7).

## 1. What prompted it

The owner's screenshot of This week on Wed 25 Nov 2026 at the **45-minute tier**:

| Card | Face |
|---|---|
| Fri 27 sunrise | Poor · within 45m · 1/25 rated · best 3★ The Tees Transporter Bridge |
| Fri 27 sunset | Poor **unsettled** · within 45m · none rated yet · The Lake District · beyond 45 min |
| Sat 28 sunrise | **Not scored** · within 45m · Spread empty · Best "Nothing to show for this one." |
| Sat 28 sunset | Poor **unsettled** · within 45m · none rated yet · Northumberland & Tyneside · beyond 45 min |

The owner's reading: "Sat morning being *not scored* vs *unsettled* seems inconsistent when we've measured Sat
sunset." Neither Saturday window was rated by Claude inside the ring. Both are blanks. Two different rules word
them, and the card's face drops one of the two reasons.

## 2. What the code does today

Saturday is T+3. Gate 4 scores T+3 only where stability says SETTLED, never T+4, and does score T+0 to T+2. The
collector runs weather triage BEFORE Gate 4, so an unscored T+3 window is usually a mix of triage stand-downs and
stability skips.

**Served path (tier "Any").** `BriefingHonestyFilter` blanks a zero-coverage region (empties its slots) and words
it by `CoverageReasons.of` over each slot's latest disposition (`latestBySlot`):
- `STOOD_DOWN` (STAND_DOWN, "Too unsettled to forecast", or "Fog forecast" via `fogMajority`) only when the triage
  count beats stability by a strict majority, with every slot accounted for;
- a tie, or stability ahead, gives `NOT_ATTEMPTED` (AWAITING, "Not scored at this range");
- half or more failed gives `FAILED`; any slot with no record gives `UNKNOWN`; `AWAY` for a travel day. All of
  these are AWAITING.

The region carries `label`, `summary`, the gloss and `coverageReason` (`BriefingRegion:279`, NON_NULL, set only in
`fullRewrite`, carried by every `with…` method, so `LitePickShaper` keeps it). It is serve-time only: the serve path
re-enriches on every assembly, so it is never stale. No frontend file reads it yet.

**Drive-tier path (the screenshot).** `ReachVerdictService.prune` re-rolls each region over its in-reach slots:
- a region the served filter blanked has no slots, so at a finite tier it is **dropped**. The away arm does the same
  (`ReachVerdictService.java:170-172, 197-199`). If every region drops, the window takes the projector's empty
  answer: AWAITING, `topRegionName` null, no reason;
- a region left with no rated place goes through `blankedForReach(rerolled, anyTriaged)`:
  `STOOD_DOWN` if **any** in-reach slot carries the `triaged` stamp, otherwise `NOT_ATTEMPTED`, **at every
  horizon** (`BriefingHonestyFilter.java:303-304`, `ReachVerdictService.java:213`);
- `ReachVerdictResponse.Window` carries `blanked` (STAND_DOWN over an empty-slot region) and **no reason**.

**The card.** `verdictBlankedOf` (STAND_DOWN only, `windowFirstCards.js:583`) then sets `verdictBlanked`, and
`WindowFirstHeatStrip` prints a hard-coded "unsettled" in the mean's slot, including over a "Fog forecast"
region. Every AWAITING prints a bare "Not scored". The note is suppressed on away cards (`!card.away`,
`WindowFirstHeatStrip.jsx:1813`, `windowFirstStrip.js:123`).

**Which path made each Saturday card:**
- **Sat sunset, "Poor unsettled": proven.** At a finite tier, `blanked` needs an empty-slot region, and served-
  blanked regions are dropped. So the only source is `blankedForReach` returning STOOD_DOWN because at least one
  in-reach place was triaged. The "beyond 45 min" line confirms the region had rated places outside the ring.
- **Sat sunrise, "Not scored": almost certainly every region served-blanked, then dropped.** "Nothing to show for
  this one." is `spreadTitle`'s empty-pool sentence (`windowFirstSpread.js:384-385`), so the window had no slot
  anywhere. That points to every region having been blanked on the served path (at T+3, most likely
  `NOT_ATTEMPTED`). This is plausible rather than traced end to end, and production is not readable from here.

So there are four defects:

1. **The card drops the reason.** "Not scored at this range" reaches the region record and stops there.
2. **The tier rule is not the served rule.** "Any triaged" makes a scoped "Poor unsettled" from one stood-down
   place, where the served fold needs a majority with every slot accounted for. This is what made Sat sunset.
3. **The tier rule says "at this range" at T+0 to T+2,** where Gate 4 does score. That is false today.
4. **Dropping a served-blanked region drops its reason.** This is what made Sat sunrise a bare "Not scored".

## 3. The design

### 3.1 One fold, both paths (fixes 2 and 3, and lifts the old fog limit)

Make the reach path call the **same** `CoverageReasons.of` and `fogMajority` the served filter calls, instead of
approximating them with one boolean. During enrichment, stamp each slot with its latest disposition as a
`@JsonIgnore` re-run input, next to the existing `forced` and `triaged` stamps:

- **What:** a new field `BriefingSlot.disposition` (a small record holding the category and the `lowVisibility`
  flag, or the existing `EvaluationViewService.LatestDisposition` if it is reachable from `model` without a cycle).
- **Where:** it is stamped in `ServedBriefingAssembler`'s re-enrich, where `serveTriage.latestBySlot()` is already
  loaded (`ServedBriefingAssembler.java:436-442`). No extra query.
- **Why D5 allows it:** reach-scoped-card-plan D5 forbids the reach path *re-reading* the stores. A stamp from the
  same evidence is what D5 already licensed for `triaged`.

`blankedForReach(rerolled, inReach, date, type, daysAhead)` then builds a `latestBySlot` view from the in-reach
slots' stamps and calls `CoverageReasons.of` and, for `STOOD_DOWN`, `fogMajority`. Ties, missing records, failures
and the T+4 rule behave exactly as on the served path, and a tier card over fog says "fog". `daysAhead` comes from
`ForecastHorizon` with the injected `Clock`. An unstamped slot (no record) folds to `UNKNOWN`, the fold's own safe
default.

The `triaged` stamp stays (other readers use it), but `blankedForReach` stops reading it.

### 3.2 The window carries the reason (fixes 1 and 4)

The server picks the reason; the client never selects a region to read one from. Today, among several blanked
AWAITING regions, both `BY_RANK` and the client's `pickMeanlessWordRegion` break the tie on the region **name**. A
`FAILED` region whose name sorts first would make the card say "run failed". So:

- **`BriefingWindow`** gains `coverageReason` and `fog` (both NON_NULL, serve-time). Set them from the region whose
  word the window wears (the projector's `top`), only when that region is blanked. Then add a **reason-priority
  step** to `BY_RANK`'s meanless tail, after the existing "blanked STAND_DOWN before blanked AWAITING" step and
  before the name: `NOT_ATTEMPTED` / `AWAY` (facts about the date) ahead of `FAILED` ahead of `UNKNOWN`. The word and
  the reason then come from one region, chosen by a rule rather than an alphabet.
  *Builder: confirm the projector is the one place `BriefingWindow` is built; if the served window is assembled
  elsewhere too, set it there as well.*
- **`ReachVerdictResponse.Window`** gains the same two fields from the region `windowBody` already picks (`region`),
  when that region is blanked.
- **Every region dropped at a finite tier (fix 4, owner decision D1 below):** when the tier empties the window
  *only* because every region was served-blanked, carry the reason **only if every dropped region has the same
  date-level reason**, `NOT_ATTEMPTED` or `AWAY`. Those are facts about the day and the horizon, not about a far
  region's weather, so they are true inside the ring. A `STOOD_DOWN`, `FAILED`, `UNKNOWN` or mixed set stays silent
  (reason null). A far region's weather must not speak for a ring it is not in, which is why R2 drops it.

### 3.3 The card's words (D2)

A lookup on the served enum. This is the `VERDICT_LABEL` precedent: no threshold and no selection, so it stays
inside the backend-heavy licence. The visible form goes in the mean's slot (the "unsettled" precedent, about 54px at
10px). The spoken form is the **served label**, so the card and the region rail share one vocabulary
(`windowFirstCards.js:529-532`'s own rule):

| Reason | Visible | Spoken (sentence, `title`) = served label |
|---|---|---|
| `STOOD_DOWN` | `unsettled` (unchanged) | Too unsettled to forecast |
| `STOOD_DOWN` + `fog` | `fog` | Fog forecast |
| `NOT_ATTEMPTED` | `too far out` | Not scored at this range |
| `FAILED` | `run failed` | Forecast unavailable |
| `AWAY`, `UNKNOWN`, null | nothing (today) | nothing (today) |

`AWAY` never reaches a card face (away days get no card). Do not use "unsettled" for `NOT_ATTEMPTED`, even though
stability skips for an unsettled *pattern*. Two meanings of one word on adjacent cards is the confusion being fixed.

## 4. Owner decisions (confirm before C1 starts)

- **D1, a window emptied at a tier.** Carry a uniform `NOT_ATTEMPTED`/`AWAY` reason, and stay silent otherwise
  (§3.2). *Recommended.* The alternative is silence always, which leaves Sat sunrise exactly as it is.
- **D2, the visible words** in §3.3. "too far out" and "run failed" are the proposals; width is measured in C2.
- **D3, the change in what cards say.** Under §3.1, fewer drive-tier cards say "Poor unsettled" at T+2/T+3.
  Some say "too far out", and a T+0–T+2 blank with an unclear record says plain "Not scored". That is the served rule
  applied on both paths, not a regression. The changelog says so.

## 5. C1: backend (one PR)

1. **Stamp** (§3.1): add the `BriefingSlot.disposition` field (`@JsonIgnore`, not persisted, carried by every slot
   `with…` method, listed in `@JsonIgnoreProperties`) and stamp it in the enrich pass from `latestBySlot`.
   `ScoreEnricher` or the rollup that stamps `triaged`, whichever owns the slot rebuild.
2. **`blankedForReach`** calls `CoverageReasons.of` and `fogMajority` over the in-reach stamps (§3.1). Keep it pure
   and package-private. Delete the `anyTriaged` parameter.
3. **`BriefingWindow`** gets `coverageReason` and `fog`, and `BY_RANK` gets its reason-priority step (§3.2).
   **`BriefingRegion`** gets `fog` (NON_NULL Boolean, set by `fullRewrite` when `fog`, carried by every `with…`).
   Update `coverageReason`'s javadoc: the Plan card now reads it, through the window.
4. **`ReachVerdictResponse.Window`** gets `coverageReason` and `fog` (§3.2), with the uniform-reason rule for an
   emptied window.
5. **Caching and roles:** `/api/user/settings/reach-verdicts` is already uncached and is Pro/Admin only
   (`UserSettingsController.java:259`), so no gated slots reach it. Nothing personal is added to `/api/briefing`;
   the window and region reason are free for every role (sub-regions §9 Q5). Pin in `LitePickShaperTest` that a
   shaped window and region keep `coverageReason` and `fog`. No `HttpCachingConfig` change.
6. **Tests** (pin every Mockito argument):
   - `BriefingHonestyFilterTest` / `CoverageReasonsTest`: `blankedForReach` over stamped slots:
     - triage majority gives STOOD_DOWN;
     - 1 of 4 triaged with 3 stability gives NOT_ATTEMPTED;
     - a tie gives NOT_ATTEMPTED;
     - 2 triaged, 1 stability, 1 error gives STOOD_DOWN, as on the served path;
     - 3 triaged with 2 unstamped gives UNKNOWN;
     - a T+1 non-triaged blank gives UNKNOWN, not NOT_ATTEMPTED;
     - a fog majority gives the fog marker;
     - a canopy slot counts on neither side.
   - `ReachVerdictServiceTest`:
     - the Sat-sunset shape (1 of 4 triaged at T+3, 45m) is AWAITING + NOT_ATTEMPTED, with `blanked` false;
     - the Sat-sunrise shape (every region served-blanked NOT_ATTEMPTED, 45m) is AWAITING + NOT_ATTEMPTED;
     - a mixed dropped set (one STOOD_DOWN, one NOT_ATTEMPTED) has reason null;
     - the away arm keeps its origin region's reason.
   - `PlanWindowProjectorTest`: blanked FAILED "A…" vs blanked NOT_ATTEMPTED "B…" gives the window NOT_ATTEMPTED,
     whatever the name order; reason null on a rated window.
   - The briefing contract test, if it pins keys: the NON_NULL keys are absent on unblanked regions and windows.
   - **Update** the existing tests that encode "any triaged" (the reach-scoped tests).
7. **Docs in the same PR:**
   - `architecture-decisions.md`, the honesty-filter bullet: one fold on both paths, the stamp, the window reason;
   - `reach-scoped-card-plan.md`, a dated note under R2's "Honesty for an emptied sample" (lines 447-450), which
     supersedes "any triaged";
   - `plan-tab.md` where it describes the qualifier;
   - changelog `changelog.d/YYYYMMDD-unscored-reason-card-c1.md`.
8. Gates: the build ladder to `clean verify` (integration exclusion), checkstyle, changed-lines. Mutation-prove the
   tie test and the uniform-reason test.

## 6. C2: frontend (one PR, after C1 merges)

1. **`windowFirstCards.js`:** replace the boolean `verdictBlanked` with `blankedReason`, set to `'STOOD_DOWN' |
   'FOG' | 'NOT_ATTEMPTED' | 'FAILED' | null`. It is read **from the window**: the scoped tier window's
   `coverageReason` and `fog` when a scoped headline is worn, else the served `BriefingWindow`'s. No region selection
   on the client: retire `verdictBlankedOf`, and leave `pickMeanlessWordRegion` for whatever else reads it. The note
   shows only where no mean is printed (today's rule).
   **Legacy fallback** for a body without the fields: a STAND_DOWN over an empty-slot region prints "unsettled"; an
   AWAITING prints nothing.
2. **One table** keyed by reason, `{ visible, spoken }` (§3.3), replacing `BLANKED_STAND_DOWN_VISIBLE/SPOKEN`. Its
   readers are `windowFirstStrip.js:121` and `WindowFirstHeatStrip.jsx` (the note span, its `title`, the accessible
   sentence). `data-testid="wf-heat-blanked"` stays and gains `data-reason`. Away cards still show no note (unchanged;
   say so in the code comment).
3. **Width:** measure "too far out" and "run failed" in Chromium at 320, 375 and 640px, and docked 1024. The value row
   must not wrap, and must not clip "Not scored" or "Poor". If either clips, shorten it and record the measurement in
   the code comment, as `VERDICT_LABEL`'s comment does.
4. **Spoken sentence:** with a reason note present, say the note's spoken form once and drop the duplicate
   `notScoredWords` clause ("Not scored, Not scored at this range, not scored" otherwise).
   **Stood places:** a card whose reason is `NOT_ATTEMPTED` may still have a minority of triaged places, which
   `stoodInFrame` hatches, and whose Spread/Best rows say "stood down by the weather". Keep that hatch and its words.
   They describe the places, while the note describes the window, and both are true. Test that the sentence says each
   once and does not contradict the other.
5. **Out of scope** (note in the PR): the location sheet and Map callout slot words (`unratedSlotWord`), the Map pill,
   the region rail and Ask. They already speak the served labels.
6. **Tests** (frontend standards):
   - the reason table, including null and the legacy fallback;
   - one card per reason (visible text, `title`, accessible name, no duplicate "not scored");
   - scoped window vs served window as the source;
   - a printed mean suppresses the note;
   - a `NOT_ATTEMPTED` card with stood places.
7. **UI review cadence:** adversarial review of the diff with about six lenses and refuters (wording honesty,
   backend-heavy, a11y, width at phone sizes, Lite, legacy payload). Check in the browser on local 8083 at 45m and
   Any with a T+3 window, and say which claims were seen and which only tested.
8. **Docs:** `plan-tab.md` (the card qualifier). Changelog `…-unscored-reason-card-c2.md`.
   **Gates:** the frontend four, diff-cover, E2E smoke.

## 7. Review record (2026-11-25, one adversarial agent)

| # | Finding | Taken |
|---|---|---|
| 1 | Sat sunrise is the dropped-region case, which the first draft left bare | §3.2 uniform-reason rule, D1 |
| 2 | "Strict majority over scoreable" is not the served fold (failures and missing records) | §3.1 stamp, one fold |
| 3 | `NOT_ATTEMPTED` at T+2 is false; Gate 4 scores T+2. Today's `blankedForReach` already says it at every horizon | §3.1 (the fold's horizon rule) |
| 4 | The reach-side `fog` could never be set in the first draft; the note is suppressed on away cards | §3.1 makes fog real; the suppression is stated in C2.2 |
| 5 | The AWAITING reason would be chosen by region name | §3.2 window-level reason, `BY_RANK` reason step |
| 6 | The spoken words broke the "speak the filter's label" rule; clash with the stood-places channel | §3.3 speaks served labels; C2.4 |
| 7 | The `triaged` stamp is weaker evidence than the fold | Moot under §3.1 |
| 8 | Lite: reach-verdicts is Pro/Admin only | C1.5 |
