# Ask Ready: offer the events questions only when there is a live event (2026-11-09)

## Why

The owner asked why Claude money was spent on 8 Nov 2026 during a travel trip (Mon 9 to Thu 12 marked as
travel days). Most of it was legitimate: the overnight run rated Sunday 8 Nov, which was not a travel
day, and the travel-day gate in `BriefingCandidateCollector` skipped the rest. The largest slice was
**Ask ready answers** (about £0.40 that day).

`ReadyQuestion.RARE_EVENTS` and `ReadyQuestion.SNOW_TOPS` are "always asked": their `offer()` returns
an offer unconditionally. So every scheduled precompute (four pipeline cycles a day) spends one
Claude conversation per scope (every enabled region plus `ALL`) on each of them, even when the
snapshot holds no event the answer could carry. Such a run can only end in `CANT` (counted as skipped
in `AskReadyPrecompute.runOne`) or in an answer that `AskReadyFreshness` would withhold at serve
(rule 5: "any event's topic is no longer live"). The call is pure spend.

The pick questions (`BEST_*`, `COASTAL_HIGH`, `AM_OR_PM`, `NIGHT_SKY`) already gate on pick-eligible
candidates, and the snapshot already drops travel-day windows (`AskSnapshotBuilder`), so they cost
nothing on a travel day with no rated windows.

### Considered and rejected

- **"Skip everything when every day in the window is a travel day."** The briefing window is 5 days
  (`ForecastHorizon.BRIEFING_WINDOW_DAYS`), so it would not have fired on any day of this trip (8 Nov's
  window held Sun 8; 9 Nov's holds Fri 13). It saves nothing.
- **Short-circuit the best-bet advisor when the rollup has no pick-eligible event.** The call costs a
  few pence, and the advisor can legitimately return a colour-exempt **stay-home** pick
  (`BestBetRanker.isColourExempt`), which a short-circuit would replace with no picks. That would be a
  visible behaviour change for a trivial saving. Left alone.

## The change

1. Add one predicate shared by the offer and freshness, so the two can never drift. For example,
   add a method on `AskSnapshot` (or a package-private static in `ReadyRelevance`):

   `boolean hasLiveEvent(Predicate<String> typeAdmitted, AskScope scope)`

   It is true when either of these holds:
   - some `hotTopics()` entry `t` has `typeAdmitted.test(t.type())` and `t.inScope(scope)`;
   - some `comingUp()` entry `e` has `typeAdmitted.test(e.type())` and `!e.endDate().isBefore(today)`
     and `!e.startDate().isAfter(today.plusDays(MAX_COMING_UP_DAYS - 1))`.

   These are exactly the per-entry conditions `AskReadyFreshness.liveEvent` uses today, minus the
   per-event type and date match. Refactor `liveEvent` to use the same per-entry helpers (for example
   `Topic.isLiveIn(scope)` and `ComingUp.isLiveOn(today)`) so the window logic exists once.
2. `RARE_EVENTS.offer` returns `Optional.empty()` unless
   `snapshot.hasLiveEvent(type -> ReadyRelevance.admitsEvent(RARE_EVENTS, type), scope)`.
   `SNOW_TOPS.offer` does the same with `SNOW_TOPS`. The offer's text, `windowIds` and
   `contextWindowId` are unchanged when it is offered, so already stored rows still match freshness
   rule 2.
3. Update the Javadoc on both constants ("Always asked" is no longer true) and the enum's class
   Javadoc (`ReadyQuestion.java` around lines 29-31 says every predicate reads only `candidates(...)`;
   the two events questions now read live events). Add a one-line note to
   `docs/engineering/ask-ready-failures-plan.md` where it says "always asked", and mention the gate in
   the Ready catalogue text in `docs/engineering/reference/ask-photocast.md`.

## Effects

- **Precompute.** A not-offered question already counts as `skipped` in `AskReadyPrecompute.work`, so
  the counts keep their meaning. Fewer engine runs means fewer `ask_ready` API calls.
- **Serve.** `AskReadyFreshness.check` already withholds a stored row whose question is no longer
  offered (rule 2). A stored `SNOW_TOPS` row whose snow topic has gone was already withheld by rule 5,
  so nothing that used to be served stops being served.
- **Typed questions are untouched.** `offer()` is read only by the Ready precompute, Ready freshness,
  and the engines and validator through an existing `basis`.
- **Expected saving (review-corrected).** `SNOW_TOPS` is skipped whenever no snow hot topic
  (`SNOW_TOPS`, `SNOW_FRESH`, `SNOW_MIST`) is live in scope. No almanac type is snow, so only hot
  topics count. That is most days outside winter cold spells. `RARE_EVENTS` admits any type, and the
  90-day almanac (tides, meteors, supermoons, eclipses, alignments) is essentially never empty, so its
  saving is about zero. It is gated anyway for consistency and for the case where the almanac read
  fails. The ceiling is (regions + 1) × 4 `SNOW_TOPS` conversations a day.

## Tests (backend, follow `docs/engineering/test-improvement-standards.md`; pin every Mockito argument)

- `ReadyQuestion` offer tests for both questions:
  - no topics and no Coming up → empty;
  - an admissible hot topic in scope → offered;
  - an admissible hot topic out of scope (regional topic, other region) → empty;
  - an inadmissible type for `SNOW_TOPS` (for example `AURORA`) → empty, while `RARE_EVENTS` is offered;
  - a Coming up entry ended yesterday → empty;
  - a Coming up entry starting at `today.plusDays(90)` → empty;
  - a Coming up entry starting at `today.plusDays(89)` → offered (matches `AskReadyFreshness` line 244);
  - a region-less topic → offered in every scope.
- The `hasLiveEvent` helper or the extracted per-entry helpers, at their boundaries.
- `AskReadyFreshnessTest.eventNoLongerLive` (around line 209) asserts the reason contains
  "no longer live". With the gate, rule 2 (the question is no longer offered) fires first for a
  snapshot with no events. Update that test so rule 5 is still covered: give the live snapshot some
  other admissible live event, so the question stays offered but the stored event is gone. Add a
  rule-2 test for the no-events case, and add the same pair for `SNOW_TOPS`.
- `AskReadyPrecompute` test: a snapshot with no events runs no engine call for `RARE_EVENTS` and
  `SNOW_TOPS` (verify the engine is never called for them) and counts them as skipped.
- Fix any existing test that relied on "always asked" (grep `RARE_EVENTS` and `SNOW_TOPS` under
  `backend/src/test`). Never touch `src/test/java/.../regression/`.

## Gates

The backend build ladder from CLAUDE.md, ending in the full `clean verify` with the integration
exclusion (per-class coverage 95% line / 90% branch). This change touches no frontend. Add
`changelog.d/20261109-ask-ready-event-offer-gate.md` with a `### Changed — …` heading.
