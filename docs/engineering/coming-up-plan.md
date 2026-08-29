# Coming up — one chronology, with a surprise model: implementation plan

**Design bundle:** `docs/design/coming-up/` (`README.md` is the handoff spec; `Coming Up.html` is the
design of record — open it in a browser, the model spec is rendered below the frame).
**Status:** planned, not started. Phases are sized for one focused session each and are written to be
executed by a session that has NOT read the conversation that produced this plan — everything
load-bearing is restated here. This plan has been through one full adversarial review (six lenses,
~40 confirmed findings, all incorporated — §14).

**How to use this document.**
- Read order: this file top to bottom, then `docs/design/coming-up/README.md`, then CLAUDE.md's
  "Where a rating lives" table and its Almanac endpoint section.
- **Re-verify every `file:line` and symbol named here against the tree before editing** — citations
  rot, and one round of review already corrected several.
- **Paste your phase's section PLUS §2 (decisions) and §13 (schema) into every review agent's
  prompt** — an untracked or unfamiliar plan is invisible to an agent in another worktree, and a
  compliance lens with no spec returns zero findings and looks clean.
- **Deviation protocol:** if a phase discovers a §2 decision is wrong, do not silently deviate.
  Record the contradiction and its evidence in the phase log (§3), edit the D-number in place in the
  same commit, name the change in the PR description and the CHANGELOG entry. Any change to §13's
  schema is made in §13 in the same commit.

### Phase log

Later phases append a row. `notes` is what a reader of a later phase needs and would not get from
the diff.

| phase | date | PR | notes |
|---|---|---|---|
| P1 | 2026-08-28 | — (not yet pushed) | Shipped as planned: `PlanHorizon`, `model/comingup/*` (all five §13 record shapes declared now, per the P1 brief, so `ComingUpResponse`'s component list never has to grow), eligibility filter, `enteredWindow`, the handoff row. Two implementation choices for a later phase to know about, neither a D-deviation: the handoff row de-dupes hot topics by `type` **alone** — D14 says "type + family" but `HotTopic` has no `family` field, that concept lives only on the almanac/chronology side (D6); and the row's swatches use a small **local** hex palette in `comingUpHandoff.js` (mirroring `HotTopicStrip.jsx`'s existing one), not D6's `--color-topic-*` tokens — those are chronology-only and land in P3a. Adversarial review (4 lenses) caught two real bugs before landing, both fixed: the handoff row's accessible name ran every phrase together with no word boundary (JSX drops whitespace-only text between sibling tags, and a separator nested as a wrapped element's leading child is trimmed before accname joins it — fix interleaves bare `' '` strings as true siblings); and `useComingUpFeed`'s wrapper-shape guard (`typeof data === 'object'`) also accepted a bare array (`typeof [] === 'object'`), silently defeating its own degrade-to-`{entries:[]}` intent on a reverted/mixed-version backend. Backend gate green (7617 tests, JaCoCo/SpotBugs clean); frontend green (175 files/4283 tests, lint clean); browser-verified desktop + 390px, including the handoff row's click→Plan-tab-focus behaviour via live DOM inspection. |
| P2 | 2026-08-28 | — (not yet pushed) | Shipped: `service/comingup/SurpriseScore` (pure, `rarity`/`magnitudeFromHistory`/`bandOf`), `ComingUpScoringProperties` (all knobs, documented in `application-example.yml`'s new `coming-up.scoring` block), `ComingUpAssembler` (the enrichment pass — bits, facts, first-of-type prose, coincidence merge, superlatives, thresholds, counts, bands), and two new `TideRunBuilder` methods (`peakRange`/`peakRangeAt`, exposing a run's numeric peak range and letting the historical distribution pin a **fixed** representative rather than re-selecting one per historical run) plus `TideRunPeakHistory` (replays `TideSizeIndex`'s roster-wide qualification backward to build that distribution). ⚠️ **One schema addition beyond the original brief, made under the deviation protocol: `entries[].interim` (boolean, §13).** D4 says cold-start tide magnitude must "never badge", but §13 as written gave a future badge reader (P5) no per-entry carrier to act on — only `conditions[].interim` existed, and that is P4-owned. Rather than leave a silent gap for P5 to discover, P2 adds the field now: false by default, true ONLY on the tide branches that never reach a mature (≥60-observation) empirical magnitude (cold-start bucketing or an unmeasurable representative) — narrowed from an earlier draft that defaulted every entry interim, which a pre-merge conformance review caught: that default made the badge structurally unfireable for the WHOLE feed (every non-tide type would have stayed permanently interim, since none ever reaches a mature distribution), contradicting D4 (whose 1.0 magnitude default for non-tide types is "by definition typical", not provisional) and §11.13's ~10-badges-a-year census premise. P5 must read it before deciding whether an entry may clear a band — this is now load-bearing for that phase, not optional polish.

**Post-merge conformance review (2026-08-28, before #682 merged) found four more real defects, all fixed on the branch:** (1) the `interim`-defaults-true bug above; (2) `mergeCoincidences` ran BEFORE `markTideSuperlativesAndThresholds`, so a tide run absorbed into a supermoon's coincidence line dropped out of the range-comparison set entirely — since a supermoon's default score (~6.9 bits) beats a tide run's default (~3.9+1.0) in the common case, the biggest run in a window was often exactly the one that got merged away, letting a smaller later run falsely claim "biggest until X" while the true biggest was still visible one line down in that entry's own `coincidence.factsLabel`; fixed by computing superlatives/thresholds over the full pre-merge set (merging never touches `tideRangeMetres`, so this is a pure reordering, not a second computation); (3) `withoutKeys` dropped `rangeAnomaly` from `meta` unconditionally, but `tide.delta` — the typed field meant to supersede it — is only built when a peak, `TideStats`, AND `avgRangeMetres` all resolve; on any miss the fact vanished from the payload with nothing replacing it (every OTHER dropped tide/meteor/eclipse key is safe because something else — `tideFacts`/`meteorFacts`/`eclipseFacts`/`peakDateOf` — reads it unconditionally whenever it's present; `rangeAnomaly` was the one exception, since nothing reads that string at all, `tide.delta` is computed from an independent DB re-query); fixed by dropping it only when `s.tide` was actually built. (4) The original `superlativeAndThresholdAcrossTideRuns` test only covered the smaller-run-first case, which passes against the pre-fix (later-only-comparison) code too — added a bigger-earlier-run case that fails without the fix, plus a three-run merge-absorbs-the-biggest case for defect (2). **One judgement call, recorded rather than silently resolved: §11.21** — a lone tide run in a window ships `threshold: null`, contradicting §5's "required on every tide-run entry" literally; left as a named gap (not computed) rather than inventing an unreviewed degraded-line design under review-fix time pressure.

Four adversarial review lenses (scoring-model correctness, data/query correctness, schema fidelity to §13, test quality) found four real, fixed defects, none of them shallow: (1) **a straddling in-progress run could contaminate its own historical comparison** — `TideRunPeakHistory.peakRanges` originally bounded its lookback at "yesterday" only, but a run detected today can have been walked backward to a start date a day or two in the past (`TideAlmanacSource.completeRun`'s backward walk, and eligibility only constrains `endDate`, never `startDate`); the run's own already-elapsed days would then re-qualify as roster-wide spring/king days inside its own comparison window, scoring its peak against a copy of itself and silently depressing the magnitude — fixed by adding a `runStartDate` parameter and clamping the window to end before whichever of "today" or the run's own start is earlier. (2) **the superlative "biggest until X" could be asserted for a run that was never actually biggest** — the original loop only compared a run against *later* ones; a run with a bigger *earlier* run in the same window would still print "biggest until <next bigger>", a falsifiable claim a reader could refute by scrolling up — fixed by tracking a running "biggest so far" and gating the claim on the current run clearing it. (3) **promoted `meta` keys were never dropped**, contrary to §5's explicit instruction — every type's enrichment now strips the specific keys it re-expressed as a typed field or verbatim fact text (documented per-type in `ComingUpAssembler`'s `withoutKeys` calls); the residual keys per type are the ones nothing yet reads (e.g. tide's `highWater`/`alignmentDate`). (4) the cold-start "never badge" gap above, closed by `interim`. Test-quality review additionally found real coverage gaps (the entire NLC-season branch, standalone supermoon, `scoreNote`, and the tide-wins-a-merge direction were all untested) — closed with new tests rather than left as debt.

Two things a later phase should know that the diff won't show on its own. **The coincidence merge is scoped narrowly, on purpose**: it only fires for a tide-run × supermoon date overlap (D10's one worked example, and the only pairing with a documented "one perigee, one cause" join test) — it is not a general "any two overlapping entries merge" rule, and extending it to another pair needs its own reasoning, not a copy-paste. **Two rarity config defaults are honest placeholders, not measured figures**: `supermoonMeanGapDays` (60.0, chosen so `log2(60)` lands near the README's own "~5.9" reference) and `eclipseMeanGapDays` (1500.0, sized only to clear the README's "≥10 bits" note) — both explicitly flagged in `ComingUpScoringProperties`' Javadoc as pending a catalogue/ephemeris-derived figure, which P5's census is the natural point to supply. Threshold lines for tide runs compare only against *other tide-run entries already in the assembled window* (P2 has no `conditions[]` to compare against — that's P4's own strip); P4's own threshold computation, once conditions exist, should read as a refinement of this, not a contradiction. Backend gate: run and green (see PR); JaCoCo/SpotBugs clean on all new `service/comingup/*` and `model/comingup/*` classes. |
| P7 | 2026-08-29 | — (not yet pushed) | Shipped as planned, out of phase-map order (after P1, before P3–P6 land) — deliberately invisible: `topic_daily_log` (V151, next-free-number re-verified against the tree on this branch AND origin/main at write time, not the plan's stale guess) + `TopicDailyLogJob`, a nightly job seeded at 04:40 (re-verified free against every migration that seeds or updates `scheduler_job_config`, not just the ones the brief named — the brief's own list turned out incomplete but its conclusion held). Logs presence/intensity/`landed_on_window` for the eight candidate topics per region for the UK-civil "yesterday", each from the least-triage-biased store available (documented per topic in the job's own Javadoc, per the round-3 "denominator lesson"): DUST and STORM_SURGE from the complete `forecast_evaluation` population (both columns are written pre-triage from `AtmosphericData`, confirmed by tracing all seven `ForecastService.buildEntity` call sites — none skip it, including both triage branches); INVERSION from `forecast_score` (survivor-only, the best available — the persisted score is Claude's own output per `InversionDetails`'s Javadoc, so it's null on anything that never reached Claude), restricted to SUNRISE rows only (`InversionHotTopicStrategy`'s own Javadoc: a SUNSET row's score is "physically meaningless"); SNOW from `survivor_atmosphere` via `SnowFreshHotTopicStrategy.isFreshSnow` — the codebase actually has two live snow strategies (`SnowFreshHotTopicStrategy` and `SnowTopsHotTopicStrategy`) against the plan's one candidate name, and the job's Javadoc now says explicitly why the lying-depth one was chosen over the fell-summit derived one, not just which; SPRING_TIDE/KING_TIDE by replaying `TideSizeIndex`'s per-day-per-location logic itself (same London-day bucketing, same thresholds via `TideService.getTideStats`), because `TideSizeIndex`'s own public API only answers roster-wide unions, not per-region — `landed_on_window` is left null for both, a recorded gap in the spirit of §11.21 rather than replicating `TideRunDay.alignedEvent`; AURORA from `aurora_forecast_result`, silent (no row at all) for a region with nothing stored that night rather than a false `present: false`, since the table is written only on a manual admin trigger; NLC as `NlcClarityService.isNlcSeason` — re-confirmed `NlcSightingClient`/`NlcSightingService` persist nothing and free-text-report a location that doesn't map to the region roster, so there is genuinely no other per-night signal anywhere in the tree — logged identically across every region since the season boundary isn't region-specific. AURORA and NLC always log `intensity: null` per the brief's own instruction. Adversarial review (one read-only pass, cross-checked against `TideSizeIndex`, `ForecastService`, `DynamicSchedulerService`, both snow strategies, and every `scheduler_job_config`-touching migration directly rather than trusting this diff's own comments) found one real defect, fixed: `persist()`'s check-then-act (`existsBy…` then `save()`) isn't transactional, so a scheduled fire racing an admin `triggerNow` could hit the table's unique constraint — uncaught, that would have aborted the rest of that topic's regions (and, for the shared DUST/STORM_SURGE call, silently dropped STORM_SURGE too) with no backfill path on an append-only table. Now caught per row (`DataIntegrityViolationException`, logged at DEBUG, loop continues), with a dedicated collision test. Review also flagged one test that didn't exercise what its name claimed (`tides_outsideDayOrNoStats_producesNothing` never actually constructed a boundary extreme) — split into a real adjacent-local-day boundary test and a separate no-usable-stats test. Backend gate green (7756 tests, JaCoCo/SpotBugs/Checkstyle clean); no frontend diff, so no browser pass. **Revisit the interim rarity/magnitude constants (dust and inversion config fallbacks, tide cold-start bucketing) after ~90 days of accrued rows**, per the brief. The optional admin-triggered dust/surge backfill from historical `forecast_evaluation` was skipped — time was not tight, but the phase's own scope reads as complete without it and a backfill is easy to add later without touching this commit's shape. |

---

## 0 · What the design asks for, in one paragraph

The Coming up tab (currently a flat ~11-row almanac table: `WindowFirstComingUp.jsx` over
`GET /api/almanac?days=90`) becomes: a **handoff row** stating that Plan owns the next four days; a
**standing conditions strip** (one line per frequent topic, expandable to every occurrence with the
score it received); a **chronology** of dated entries at two densities in the existing topic colours,
each with exactly one action; and a **conditional tab badge** with four escalation bands. Underneath
is a scoring model — `S = rarity + magnitude`, both surprisal in bits — that decides what appears
where. The same work **removes the Hot topics panel from the Plan tab** (design §10). The design
README calls the model "the substance of this handoff"; its §"First question" asks whether the
historic data the model needs exists. §1 below answers that from the codebase.

---

## 1 · The data audit — the design's "first question", answered

Verdict per topic type, against the three questions (per-day presence history? intensity history for
past occurrences? depth?):

| Topic | Presence history | Intensity history | Depth / notes |
|---|---|---|---|
| SPRING_TIDE / KING_TIDE | **recomputable** (ephemeris + `tide_extreme`) | **yes** — every HW/LW height in `tide_extreme`; per-port `TideStats` | forward 97 days rolling; backward = whatever the admin 12-month backfill has fetched (capability, not guarantee). ⚠️ `TideStats.p75/p90/p95HighMetres` are percentiles of individual **high-water heights** — see D4 for why they must NOT be used as the run-magnitude distribution |
| SUPERMOON / METEOR / EQUINOX / SOLSTICE / ECLIPSE / NLC-season | **recomputable for any date** (`LunarPhaseService` turning-point search; hardcoded 5-shower table in `MeteorHotTopicStrategy.SHOWERS`; hardcoded `MonthDay` anchors; `EclipseCatalog` 2026–2030 forward-only; `NLC_SEASON` constant) | rate is exact, not estimated | infinite, except eclipse (five hardcoded events, no solver, no past data) |
| DUST | **derivable** by replaying `DustHotTopicStrategy` thresholds (AOD > 0.3 or dust > 50, PM2.5 < 35) | **yes** — `forecast_evaluation.aerosol_optical_depth/dust/pm2_5` columns on **every** row incl. triaged (V3, project inception; the *values* can be null when the aerosol fetch degraded — column-on-every-row ≠ value-on-every-row); also `survivor_atmosphere` since 2026-06-20 | deepest weather series in the DB |
| STORM_SURGE | derivable (`surge_risk_level == HIGH`) | **yes** — six `surge_*` columns on every `forecast_evaluation` row since V61 (2026-03-31) | good |
| INVERSION | partial | `forecast_score` INVERSION rows (score 0–10, band in `summary`) since **2026-06-20 (V114** seeded the type; V108 created the empty table on 06-11**)**, **survivors only**; `forecast_evaluation.inversion_score` is null on triaged rows | survivor-biased: absence means "triaged out or not evaluated", not "weak" |
| SNOW | partial | survivor surface only (`snow_depth_m`/`freezing_level_m` V115 since 2026-06-20, temp V124 since 2026-07-12). The V113 `forecast_evaluation` columns were **dropped in V116** — that history is gone | thin; and it is August |
| AURORA | **no** | `aurora_forecast_result` (V57) is written only by the manually triggered run (ADMIN or PRO_USER), delete-then-insert per night, past nights skipped; Kp is never persisted anywhere else | not a series; treat as no history |
| NLC (clarity) | no | none — clarity is an ephemeral in-memory cache | zero |

Other questionnaire rows: **regional granularity** exists by joining `location_id → locations.region_id`
(reflects *today's* membership retroactively; never key a new sink on region *name* — V137 had to
clean up after a rename, and `RegionService.setName` makes renames routine; V144's header is the
cautionary note). **Sunrise/sunset for past dates** is trivially cheap (`SolarService` → stateless
`SolarCalculator`, no I/O, any date). **Tide predictions for the 90-day forward window**: yes, 97
days rolling, weekly refresh.

**Consequences (this is the fork the whole plan hangs on):**

1. The **deterministic branch gets exact RARITY on day one** — tide runs at the true 14.8-day
   rate, showers/equinoxes/supermoons at their exact rates. **Magnitude is NOT complete on day
   one**: a tide run's magnitude needs a distribution of *run peaks* (~26 observations/year — see
   D4), so ~60 observations means ~2.5 years of stored history, and the 12-month backfill yields
   ~25 at best. Cold-started ports score magnitude from bucketed reference points and **do not
   badge** (D4). The consequence worth saying out loud: **at first ship, tide runs take chronology
   rows but do not badge** until their history matures. That is the design's own cold-start rule
   ("list, do not badge") applied where it actually bites.
2. The **recurrent/persistent branch ships as the README's documented interim rule** — with one
   deliberate upgrade: **dust** rarity is *computed* from observed trailing-60-day arrivals
   (replayed over the complete `forecast_evaluation` population — NOT the survivor stores, which
   are triage-biased; D4) wherever the evidentiary bar (≥ 5 arrivals) is met, because a config
   constant sitting beside a live observed-rate label would print two disagreeing numbers on one
   strip row. **Inversions have no unbiased population until P7 and stay on the config rarity.**
   Config constants are the *fallback*, not the default, wherever a real count is possible (D4). The band names and escalation are a UX contract and do not change
   when the full model arrives.
3. **Start logging now** (README's instruction): a per-day per-topic presence/intensity log is P7.
   Revisit the interim pieces after ~90 days of rows; season-matching needs ≥ 2 years.
4. **Aurora and NLC never reach the strip or the chronology in v1.** Aurora is already excluded
   from badging by the design ("forecast never badges"; it has the banner and the Plan pills); with
   no rate history it cannot be a standing condition either. NLC keeps its almanac season-boundary
   rows only. Both ARE logged by P7 (presence with null intensity) so the clock starts.

---

## 2 · Decisions record

Each of these was a genuine fork; an implementing session must not relitigate them without new
evidence — see the deviation protocol at the top. Numbered for citation from phase briefs.

**D1 — Plan's boundary is `today .. today+3`, extracted as a shared constant.** The design's
eligibility rule ("only dates beyond Plan's four days can badge or take a row") needs Coming up to
know where Plan stops. The truly-rendered horizon is the last element of
`DailyBriefingResponse.renderedEvents` (`PlanWindowProjector.renderHorizon`, 6 events) — but that is
computable only at briefing serve time, varies with elapsed events, and wiring it into
`AlmanacService` would break the almanac cache's purity (D2). The hot-topic window is *already*
`today .. today+3`, hardcoded twice in `BriefingService` (`:253` serve overlay, `:497` build), both
deriving `today` as `LocalDate.now(clock.withZone(LONDON))` — identical to `ForecastHorizon.today`.
**Extract that into one named class** (`service/PlanHorizon.java`: `PLAN_OWNED_DAYS = 4`,
`lastPlanDate(today) = today.plusDays(3)`; it may own `today(clock)` too since both call sites
agree), point both `BriefingService` call sites at it, and let the Coming up assembly use the same
constant. Behaviour-preserving at those two sites — pin with a test that the briefing hot-topic
window still spans 4 dates. Do NOT use `BRIEFING_WINDOW_DAYS` (5 — its fifth date is deliberately
never rendered) and do NOT use `renderedEvents`.

**Eligibility rule (one sentence, no exceptions): an entry is eligible for the chronology iff
`endDate > PlanHorizon.lastPlanDate(today)`.** A run that straddles the boundary is in the
chronology and its dates say so; a run wholly inside Plan's window is strip-material only. This
restates the design's "the chronology never contains a date inside Plan's window" as "…never
contains an entry that *ends* inside Plan's window" — recorded as §11.10. Status interaction: see
D11's precedence rule.

**D2 — The payload stays on `GET /api/almanac`, wrapped, and stays user-independent.** The response
changes from a raw `List<AlmanacEvent>` to a wrapper DTO — the complete schema is **§13, the single
source of truth**. The frontend is the endpoint's only consumer, so the shape change is coordinated
in one phase (P1). The endpoint keeps: Bearer no role gate, ETag revalidation (body-derived
`ShallowEtagHeaderFilter`; the whitelist match is on the servlet path, so no `HttpCachingConfig`
change), the single-slot `AtomicReference` cache. Two cache rules now carry more weight:

- **Everything in the payload must be a function of `(builtFor, days)` and slowly-varying stores** —
  the cache is one slot keyed on exactly that, and its Javadoc makes purity load-bearing (two
  concurrent builders must produce identical answers). Weather-derived content (condition peaks)
  reads persisted stores (`SurvivorSignalReader`, `tide_extreme`, config), never live APIs, and
  accepts one-day staleness. `AlmanacService.evict()` exists (`:139`) but has **zero production
  callers** — its Javadoc's "admin refresh path" is stale. If a condition peak ever must be fresher
  than daily, wiring a writing job to `evict()` is a NEW dependency to be justified in that phase,
  not an existing pattern — and it rebuilds the whole feed, not one entry. Never add a second cache
  key term.
- **Nothing per-user may ride this payload.** `/api/almanac` is on the ETag whitelist, which forces
  `Cache-Control: private, no-cache` — a browser disk cache JavaScript cannot evict on logout. So
  `lastSeenAt`, `isNew`, and the badge itself are NOT in this response (D3, D12).
- **No colour values cross the wire.** Entries carry `family`; the client maps family →
  `--color-topic-*` (D6). A hex on the wire would be a second copy of a token that drifts silently.

**D3 — Arrival is computable (with one named exception); only `lastSeenAt` is stored, per user.**
The badge counts "arrivals into the 90-day window since the user last opened the tab". The feed's
window is `today .. today+days−1` (`AlmanacService.getFeed` builds
`build(today, today.plusDays(clamped - 1L))`), so an event with start `S` first appears on
`S.minusDays(days − 1)` — **`minusDays(89)` at the default horizon, not 90; the off-by-one is not
cosmetic, it swallows exactly the arrivals the badge exists for** (an entry arriving today would
claim yesterday, and a user who marked seen yesterday evening never sees it). Each entry carries a
server-computed `enteredWindow` date = `startDate.minusDays(DEFAULT_DAYS − 1)` — computed against
the **fixed** `DEFAULT_DAYS = 90`, never the request's clamped `days`, so a caller passing
`?days=30` cannot redefine another user's `isNew`. Pin with a boundary test: an entry with
`startDate == builtFor + DEFAULT_DAYS − 1` has `enteredWindow == builtFor`.

- **The purity exception:** `TideAlmanacSource` detects runs from stored `tide_extreme` heights
  (`qualifies` → `sizes.springOn/kingOn`), refreshed by the **weekly** Monday fetch — so a run near
  the window's far edge can genuinely appear days after `startDate − 89`, and an entry that appears
  for any other late reason (a newly enabled coastal location, an `EclipseCatalog` release, a
  shower-table edit, the `sizes.usable()` flag flipping on a repaired DB) carries an
  `enteredWindow` already in the past and **never badges**. Decision: **accept it** — a
  late-arriving entry silently fails to badge, which is the quiet failure, consistent with every
  other bias in this design ("silence is the normal state"). The clamp alternative
  (`max(enteredWindow, firstSeenDate)`) needs a persisted first-seen store and was rejected for v1.
  Recorded as §11.11.
- **`lastSeenAt`** is a nullable no-default `TIMESTAMP WITH TIME ZONE` column on `app_user` (number
  from the tree AND main at write time; V149 was next as of 2026-08-28), following the V136/V147
  precedent verbatim. NULL means "never opened", which must render as **no badge and no NEW flags**
  (a brand-new account opens quiet). Served on `UserSettingsResponse` — **as
  `comingUpLastSeenDate`, the Europe/London civil date derived server-side** via `ForecastHorizon`,
  so the client compares two ISO date strings and no timezone rule leaks into the browser (the
  timestamp column stays as the durable truth). Written by its own
  `PUT /api/user/settings/coming-up-seen` (never folded into `saveHome` — a partial body would null
  the postcode; this is the recorded reason `map-colours` got its own endpoint). Add the new path
  to `HttpCachingConfigTest.personalDataPathsAreNeverFiltered`'s `@ValueSource` **in the same
  commit** (the test already pins write-only paths for exactly this reason).
- **Client derivation:** `isNew = enteredWindow > comingUpLastSeenDate` (string compare of ISO
  dates; null → nothing is new). Badge from new entries clearing bands (D4's served `bands`),
  filtered to `kind === 'ALMANAC'` — forecast entries never badge. See D12 for why this client
  derivation is licensed.
- **Bootstrap, or the badge never turns on** (external-review finding, §14 round 3): with NULL
  rendering as "nothing is new" and `Mark seen` the only write — and the since-line that hosts
  `Mark seen` rendering only when something IS new — a null-state account can never reach the
  write, so the timestamp stays null and the badge is permanently disabled for **every** account
  (the migration deliberately has no backfill). Fix: on the first open of the Coming up tab with a
  null `comingUpLastSeenDate`, the client **quietly fires the same `PUT` with now** — that first
  visit shows no badge and no NEW flags (the chosen quiet bias, unchanged), and arrivals flag from
  then on. One write, only on the null→set transition; a failed bootstrap write stays null and
  retries on the next visit, never loops.

**D4 — Scoring: what is real, what is interim, and where the numbers live.** All scoring maths
lives in one pure class `service/comingup/SurpriseScore.java`; all knobs in
`ComingUpScoringProperties` (`@ConfigurationProperties`, documented in `application-example.yml`).
The UI never hardcodes a bit value. Bands are **lower-inclusive** (`S ≥ edge`), matching the design
matrix's `>=` comparisons.

*Almanac topics (this is P2 work — the badge depends on it):* every ALMANAC entry gets non-null
`bits`. Rarity from the exact rate: `log2(mean gap in days)` — showers ≈ 8.5 (annual), equinox/
solstice ≈ 7.5 (twice-yearly each), NLC season boundary ≈ 8.5, supermoon from `LunarPhaseService`'s
own rate (~5.9), eclipse from `EclipseCatalog` spacing (≥ 10). Magnitude defaults to the **median,
1.0** — a shower's ZHR is a catalogue constant, so its occurrence is by definition typical — unless
a real per-occurrence distribution exists (tides, below). The 1.0 default keeps the solar turning
points off the Announced contour (7.5 + 0 would sit exactly on it); the showers still land exactly
on 9.5 — §11.13 and P5's census are where that is resolved, not the default.

*Tide runs (the one almanac type with a real magnitude):* magnitude = `−log2 P(R ≥ r)` where **R is
the distribution of RUN PEAK RANGES at the representative port** over stored history and `r` is
this run's peak range. ⚠️ **Not `TideStats` percentiles** — `p75/p90/p95HighMetres` are percentiles
of individual high-water *heights* (~2/day), and a run's peak is the max of ~28 highs, so every run
would land at ~p97 by construction: the magnitude axis would carry zero information and every
fortnightly spring would badge. (`TideRunBuilder.java:739-742` already records the sibling refusal:
above-P95 is "true by construction of every card that can display it".) Build the run-peak series
by replaying `TideRunBuilder`'s per-day range over `tide_extreme` history at almanac build time —
a new query, affordable once per day per representative port. **Cold start:** under **60 stored run
peaks** (~2.5 years; the 12-month backfill yields ~25) score magnitude from the bucketed reference
points (median→1.0, p90→3.3, p95→4.3, p97→5.1) against whatever the series supports, mark the
condition `interim`, and **never badge from a bucketed magnitude** — list, don't badge, per the
design's cold-start rule. A port with null `TideStats` (< `MIN_HIGHS_FOR_THRESHOLDS = 28` highs)
contributes rows with no magnitude claim at all.

*King tides:* scored as a **large spring run** — rarity of the spring *process* (14.8 d → 3.9) plus
this run's own magnitude. `KING_TIDE` is deliberately NOT a separate rarity topic despite being a
separate `AlmanacEvent.type`: giving it a near-annual rate would add ~8.5 to a magnitude that
already encodes "this one is big" (~13 bits, permanent interrupt) — the double-count the design's
"never add linked causes" rule forbids, one level down. The strip lists king runs inside the one
coastal-tides condition's occurrences (D11), exactly as the design of record does (its 26 Nov king
occurrence sits in the spring-tides condition list).

*Recurrent/persistent topics (dust, inversions):* rarity is **computed from observed trailing-60-day
arrivals** (`log2(60 / arrivals)`, window ending yesterday) wherever ≥ 5 arrivals exist AND an
**unbiased presence population** exists to count them in — this is the evidentiary bar, and it
makes the seasonality axis live from day one (November dust really does score higher than August
dust). ⚠️ **The survivor stores are not that population** (external-review finding, §14 round 3):
`survivor_atmosphere`/`forecast_score` hold only rows that passed triage, so a dusty day that was
triaged out reads as an absence — gaps stretch, rarity inflates, and routine conditions get
promoted, the unsafe direction. So: **dust** counts arrivals by replaying `DustHotTopicStrategy`'s
thresholds over the complete `forecast_evaluation` population (aerosol columns on every row incl.
triaged, §1), collapsed to per-day presence across the roster; **inversions stay on the config
fallback rarity until P7's log exists**, because no unbiased inversion population does
(`inversion_score` is null on triaged rows, `forecast_score` is survivor-only). Below the bar, or
without an unbiased population, the config fallback rarity applies and the rate label degrades
honestly (P4). Magnitude is interim: a config-defined bucketed mapping from the
topic's intensity scale to bits — **never labelled `median` or `p90`**; the quant line names it as
a threshold (`promoted above 7/10 (interim)`). The strip's `quant` line is two independently
degradable halves: the rarity half always present; the distribution half present only where a real
intensity history is consulted, omitted otherwise — omitted, not synthesised.

*Cold start vs the evidentiary bar — two rules, not one:* the **evidentiary bar** (≥ 5 in 60)
governs whether a *rate* is computed vs config-fallback; **cold start** (< 60 observations of
intensity) governs whether a *magnitude distribution* may be claimed, and its "assume high rarity,
list it, do not badge" arm applies to the deterministic branch too (the tide bucketing above).

*Hysteresis:* **v1 ships none, deliberately.** A 1-bit dead zone is a band-*transition* rule — it
needs last night's band, and the feed is stateless, rebuilt daily, with purity load-bearing. It is
also not expressible as a bits adjustment: the card renders `rarity 2.8 + magnitude 5.1 = 7.9
bits`, so nudging bits makes the printed sum wrong. Deferred to P7, whose `topic_daily_log` gains a
`band` column (the only per-night store), after which band assignment becomes
`SurpriseScore.bandOf(bits, priorBand)` — a pure function of both, testable without the store.
Until then band flapping is accepted and bounded: interim scores are constants; the one branch that
genuinely moves nightly (tide percentiles drift as history accrues, run figures firm up at the far
edge) is exactly the branch cold-start keeps out of badging. Recorded as §11.12.

*Band edges are knobs that WILL be wrong at ship:* on the v1 inventory the placeholder edges
5/7.5/9.5 over-fire — 5 showers and the NLC boundary land ≈ 9.5 (interrupt) and 4 solar turning
points land 8.5 (announced), already exceeding the design's "~10 badges a year, one or two
interrupts" before anything else counts. **P5 does not ship until a census over one synthetic year
of the assembled feed has been run and the edges set from it; the census lands as a test fixture so
a later topic addition re-checks the count.** Recorded as §11.13, along with the design's own
internal contradiction on spring-run "silence".

**D5 — The peak gate is a THIRD alignment window, and in v1 it is satisfied by construction.** The
codebase already has two same-named constants: `TideRunBuilder.ALIGNED_WINDOW_MINUTES = 60`
(tide-in-light) and `SurgeRunDayBuilder.ALIGNED_WINDOW_MINUTES = 90` (surge-peak-in-light). The
design's peak gate ("a promoted occurrence must land within 90 minutes of a light window") is a
third question — give it a third name, `PEAK_LIGHT_WINDOW_MINUTES = 90`, in the scoring config, and
touch neither existing constant (a third `ALIGNED_WINDOW_MINUTES` in that package would be a
collision hazard). Scope: it gates **standing-condition peaks only**, never almanac entries (a
meteor shower peaks at 02:00 by definition). In v1 the gate cannot fail — every survivor row is
keyed to SUNRISE or SUNSET, so any peak read from `SurvivorSignalReader` is already inside a light
window; the constant is defined now and becomes load-bearing when sub-daily intensities exist
(P7's `landed_on_window`). State the gate in P4's peak selection anyway, with a test, so the rule
exists in code rather than only in prose.

**D6 — Topic colours: seven tokens in `@theme static`.** The design's §8 claims all family hues are
"existing PhotoCast tokens". Audit: `#6FA8B0` (`--color-tide`) and `#9B8FD4` (`--color-nlc`) exist;
`#8AAE72` exists only as `--color-verdict-go` and `#C9A24B` only as `--color-close-to-home` — both
carrying *other* meanings; `#C08552` (dust) and `#8FA3B8` (air) exist nowhere. Add a topic-family
block to `index.css`'s **`@theme static`** (never plain `@theme` — Tailwind v4 prunes an
unreferenced token to the **empty string** with nothing failing; the file records a live instance):
`--color-topic-coastal/aurora/air/night-sky/sun-moon/dust/eclipse`. Aurora and sun-moon are
value-aliases of the verdict-go and close-to-home hexes — same hue, own name, so a verdict-ramp
retune cannot silently recolour topics (the `index.css:29-34` verdict/scoreRamp lockstep comment
stays true). `--color-topic-eclipse` aliases the existing `#C4787F` so the eclipse treatment
survives `HotTopicStrip`'s deletion (P6) — the design's `#f87171` escalation is NOT adopted
(§11.4). **Family→chip mapping:** `Sun & moon` covers `sun-moon` AND `eclipse`; `Air & dust`
covers `air` AND `dust`; `aurora` is a legal family with no chip and is unreachable in v1 (§1.4) —
a test asserts the `All` count equals the sum of the family-chip counts.

**D7 — Hot topics door removal: the design's premise is wrong about blast radius; here is the real
one.** Design §10 says "`HotTopicStrip.jsx` is still used elsewhere; check call sites". It is not:
its ONLY production importer is `WindowFirstDoors.jsx:3`. Removing the door therefore orphans, in
one stroke: `HotTopicStrip.jsx` (1310 lines, incl. every LITE freemium topic treatment),
`TideRunRow.jsx` (the CLAUDE.md-documented 24-hour tide chart — its only renderer),
`SurgeRunRow.jsx`, `components/shared/CertaintyChip.jsx` + `utils/topicCertainty.js` (+
`test/topicCertainty.test.js`), the `briefing.auroraTonight`/`auroraTomorrow` wire fields (their
only reader), and the `kind:'topic'` branches of `App.handleShowOnMap` / `mapOverlay.buildMapOverlay`
(sole producer of that handoff shape: `WindowFirstDoors.jsx:224`). **Delete the door, the strip,
and the orphan chain in P6** — with these exceptions and ledger duties:

- KEEP `windowFirstTopics.js`, `WindowTopicRows.jsx`, `buildTopicIndex` wiring, and
  `briefing.hotTopics` on the wire: the matrix-card topic chips and the popup topic rows are live
  and are two of the "three better places" §10 relies on — and D14's handoff row becomes a third
  consumer.
- `TideRunRow.jsx`/`SurgeRunRow.jsx`: decide in P6 with P3's phase-log entry in front of you (P3b
  records whether it reused `chart/solarDayGeometry.js`); if deleted, say so loudly in the
  CHANGELOG — the tide-run chart is documented at length in CLAUDE.md and its removal will
  otherwise read as an accident.
- LEDGER: `auroraTonight`/`auroraTomorrow` become write-only — record in the v1-retirement §8 style
  rather than deleting the backend fields (owner call), and update CLAUDE.md's claim that they
  "keep a live reader".
- `planDoors.js` needs **one line**: remove `'topics'` from `DOOR_IDS` (`:27`). The ignore path
  already exists — `readStoredDoors` filters unknown ids (`:44`) and its Javadoc names this case.
  Write no compat shim.
- `index.css`: precise delete/keep list in P6 (§9) — including one rule that must NOT be swept.

**D8 — Map deep links.** "Show coastal spots for `<date>` →" has a working *mechanism* but not a
working *route*: the `filterAction` handoff into `MapView` exists, but its only producer is the
door P6 deletes, so P3b **builds** the channel rather than reusing it. Route Coming up's actions
through a **new `kind:'coming-up'` trigger modelled on the `topic` branch** (`mapOverlay.js:
107-125`) — the one branch that deliberately claims nothing about ratings. **Do NOT widen
`kind:'event'`**: that branch derives tone/subLine/clock from `ratingFor`/`solarTimeFor` for the
trigger date, and a Coming-up date is past the forecast horizon — it would dress "no data" as
"stand down". The destination is the **App-level overlay** (`App.jsx:529` region — rendered
outside the shell, so it works from the Coming up tab with no tab switch); `onShowOnMap` is
already a `WindowFirstShell` prop and only needs passing into `WindowFirstComingUp`. Dark sky:
`darkSkyFilter` is component-local `MapView` state with no handoff path — add a `darkSky` flag to
the handoff object and a new effect beside `handoffFilterAction`'s, **depending on
`[handoffDarkSky, handoffNonce]` and setting the flag in BOTH directions**
(`setDarkSkyFilter(!!handoffDarkSky)`): the existing effect omits the nonce, and copying that
shape for a boolean latches the filter permanently in the never-unmounted Map pane. Test the
*second* handoff, not just the first. Decide in P3b whether the trigger carries `date` at all:
`App.jsx:277` writes it into `selectedDate` and `App.jsx:545` hands it to `MapView`, where nothing
beyond T+3 has a score.

**D9 — What the chronology can actually contain at first ship.** Fixed dates: everything the six
`AlmanacSource`s already emit (tide runs, meteor peaks, supermoon, equinox/solstice, NLC season
boundaries, eclipse). Forecast peaks: the survivor pipeline only reaches T+0..T+3 — *inside*
Plan's window — so at first ship a dust/inversion peak will almost always be
`inside Plan's four days →` in the strip and will almost never earn a dashed chronology row. That
is correct behaviour under the eligibility rule, not a bug. The footer's fixed/forecast counts are
served (§13 `counts`), so a zero-forecast footer reads correctly. `AlmanacKind.FORECAST` gets its
first use whenever a forecast peak does clear the boundary. Horizon extension for aerosol is out of
scope (§11.7).

**D10 — Coincidence cards are an assembly-time merge, scored by the max rule, with the winner
named.** A tide run and a supermoon overlapping in dates become ONE entry with two lines and a
joining sentence (`coincidence` + `joinNote` in §13). The merge lives in the assembly layer (P2),
not in either source — sources stay ignorant of each other per the `AlmanacSource` contract.
King/spring + supermoon are causally linked (one perigee): score = **max of the pair**, and the
`reason` label is mandatory wherever the max was taken. The max can land on **either** topic (in
the design's 27 Oct example the supermoon's 7.4 would beat the run's lower score), so the label
names which topic carried it, not merely that a max was taken. `LunarPhaseService` answers perigee
proximity for the join test. All clock times and metres in the joining sentence are formatted on
the backend.

**D11 — The strip's tide row is ONE condition, and statuses have a precedence.** The standing
conditions at first ship are **Coastal tides** (not "Spring tides" — the runs list from
`TideSizeIndex`/`TideRunBuilder` yields both spring AND king runs, a run keeps one name for its
whole length per CLAUDE.md's two-axes doctrine, and no run may appear in two strip rows; each
occurrence carries its own run label from the source, never re-derived in the strip), **Saharan
dust**, and **Valley inversions**. Occurrence statuses, derived not recomputed: **`promoted`
outranks `insidePlan`** — `promoted` means "this occurrence's run has an assembled chronology
entry" and is derived FROM membership of the assembled `entries` list (carrying that entry's id);
`insidePlan` applies only to an occurrence with NO entry (wholly inside Plan's window); else
`heldBack`. Without the precedence a straddling run would render `inside Plan's four days →` while
its row exists — the dead-promise failure the design's "three statuses, and they must be accurate"
forbids. `TideSurfaceAgreementTest` is extended to pin the strip, the Plan tab, and the chronology
to one height and one threshold (P4).

**D12 — The per-user-join client class, named.** The client derives: `isNew` per entry, the badge
`{band, count}`, and the since-line's entry selection (highest-bits new entry). This is a **new
named class of client-side derivation** in the sense of CLAUDE.md's Backend-heavy bullet, licensed
by the same reasoning as the reach-scoped class: `lastSeenAt` is per-user, `/api/almanac` is
ETag-shared, so "what is new *to you*" has no servable answer on the shared payload — exactly the
reasoning that keeps reach off `GET /api/briefing`. Members: those three, nothing else — chip
counts, month grouping, and sparkline geometry are presentation arithmetic over served data (the
already-licensed filter/map class), not members. The exit, if ever needed, is a never-cached
per-user `GET` (the O-4 analogue). **P5 amends CLAUDE.md's Backend-heavy bullet to name this class
in the same PR** — the bullet enumerates its classes exhaustively and an unlisted member makes it
false.

**D13 — The badge forces the almanac fetch to become eager, and that is a recorded reversal.**
`useComingUpFeed` is gated on the tab being open (`WindowFirstShell.jsx:378`), and BOTH the hook
(`useComingUpFeed.js:9-13`) and the pane (`WindowFirstComingUp.jsx:36-44`) record laziness as a
deliberate refusal of exactly this badge ("it could only ever appear after the reader had already
looked"). The design overrules it: in P5, the fetch becomes eager (fire after first paint; the
response is ~a few KB and ETag-revalidated), and both comments are rewritten to say the request is
now spent on every reader because the badge is a decision-changing signal where a row count was
not. Do NOT source the badge from the briefing payload — a second source of truth on
`enteredWindow`. The kind-chip refusal (`:46-53`) is a separate, free comment rewrite in P3a.

**D14 — The handoff row is client-rendered from `briefing.hotTopics`; nothing about it rides
`/api/almanac`.** The first draft computed `handoff.liveTopics` at almanac build time from
`HotTopicAggregator` — rejected on review: the aggregator's **simulation override** and
**travel-day filter** are two of the three reasons `AlmanacService`'s own Javadoc (`:17-24`)
forbids it ("any one would be enough"), and a daily-cached ETag'd payload would serve simulated
topics for up to 24 h with no eviction (the briefing serve path re-overlays live topics *precisely
because* a cached answer is wrong there). Instead: `WindowFirstShell` already holds
`briefing?.hotTopics` live (it builds `topicIndex` from it at `:617`); the handoff row renders its
swatches client-side from that list — filtered to Plan's window, de-duped to type + family,
**no cap**: the count word derives from the list length (`Three topics live…` / `One topic
lives…`), every live topic gets a swatch and a name (the row wraps), and zero topics renders `No
topics live on those four days`. The two tabs then agree **by construction** — the same property
`TideSurfaceAgreementTest` exists to protect. When the briefing is absent, degrade to the
label-only row (`Now — Mon 31` / `On Plan →`); never synthesise. This is the filter/map class, not
D12's.

---

## 3 · Phase map

| Phase | One-line scope | Depends on |
|---|---|---|
| P1 | Contract migration: `ComingUpResponse` wrapper, `PlanHorizon`, eligibility, `enteredWindow`, handoff row (client-side, D14); same visuals otherwise | — |
| P2 | Backend chronology enrichment: full §13 entry shape, almanac bits (D4), coincidence merge, tide run-peak series, counts | P1 |
| P3a | Frontend chronology structure: month rules, rail, cards, legend, chips, header/footer copy, CSS | P2 |
| P3b | Sparkline, coincidence cards, actions (incl. the `kind:'coming-up'` map channel + dark-sky flag) | P3a |
| P4 | Standing conditions: backend `conditions[]` + frontend strip with expansion panels | P2 (shape + entry ids); the strip's scroll-to-entry lands with or after P3a |
| P5 | Badge + `lastSeenAt`: migration, settings endpoint, eager fetch (D13), census (D4), tab badge, since-line, NEW flags, Mark seen | P3a, P4 |
| P6 | Plan tab: delete Hot topics door + orphan chain, Regional planner full width, ledger + doc updates | P3b AND P4 |
| P7 | `topic_daily_log` (+ `band` column for future hysteresis) + nightly classifier job | after P1; migration number coordinated with P5 |

P2 → P3a → P3b is a train; P4 can start once P2's shape is merged. P6 must not land before P4 (the
strip is the content's new home) nor before P3b (whose handoff-kind choice determines P6's delete
list — read the phase log). **No release is cut between P2 and P3a** (the P2 wire shape renders
degraded through the old rows in that window). P5 and P7 both add migrations: **two open branches
must never both hold the next V-number** — P7 takes its number only after P5's is on main, or they
coordinate explicitly.

**Cadence, every phase:** build → tests → adversarial review of the diff → fix what survives →
re-verify → commit. Review agents are READ-ONLY (a P0 reviewer once destroyed uncommitted work via
`git checkout --`); paste the phase section + §2 + §13 into their prompts. **Every phase with a
frontend diff (P1, P3a, P3b, P4, P5, P6) verifies in the browser** and states which claims were
seen vs asserted — screenshots at desktop and 390px. The recipe (env var, ports, seeding, H2 lock)
is `docs/engineering/plan-matrix-plan.md` §9 unchanged; note a fresh worktree lacks
`frontend/.env.local` (`VITE_API_TARGET=http://localhost:8083`) and every request 502s at login
without it. Backend gate and frontend CI four-step: §12. CHANGELOG: **every meaningful commit**
appends under `[Unreleased]`; conflicts there are guaranteed across parallel phases — rebase,
don't fight.

---

## 4 · P1 — contract migration (backend + frontend, one PR)

**Backend.**
- `service/PlanHorizon.java` per D1; point `BriefingService:253` and `:497` at it; pin the briefing
  hot-topic window still spans 4 dates.
- `model/comingup/ComingUpEntry.java`: **every `AlmanacEvent` field unchanged — including `meta`,
  which the P1 frontend still reads — plus `enteredWindow`** (D3: `startDate.minusDays(DEFAULT_DAYS
  − 1)`, fixed 90, boundary test). `AlmanacEvent` and the six sources are NOT modified in P1.
- `model/comingup/ComingUpResponse.java` per §13 (P1 fields only: `builtFor`, `entries`;
  `conditions` empty list; `counts` and `bands` arrive in P2, with the band *values* re-set by P5's
  census — but declare the record components now per §13 so the shape never "surprises" later
  phases). `AlmanacController` returns it. `builtFor`
  is the served UK civil date (belt-and-braces beside the client's own latch).
- **Eligibility** (D1): eligible iff `endDate > PlanHorizon.lastPlanDate(today)`. Boundary tests
  under a fixed Clock (never the wall clock in a date fixture): entry ending T+3 excluded, T+4
  included, straddler included.
- Stale-comment debts while in these files (verified list — the earlier draft's was wrong):
  `useComingUpFeed.js:40-48` (the UTC-divergence claim is false since the service moved to
  `ForecastHorizon.today`), the "five sources" claims at `WindowFirstComingUp.jsx:49` and `:196`,
  `comingUpFeed.js:362`, `almanacApi.js:9` (six sources exist), and CLAUDE.md's almanac paragraph
  ("five implementations" — also omits `EclipseAlmanacSource` by name). `AlmanacService` itself
  carries no such claim — do not edit its Javadoc for this.

**Frontend.**
- `almanacApi.getAlmanac` returns the **WHOLE wrapper — do not unwrap to `entries`**; P4
  (`conditions`) and P5 (the census-set band values, the badge's reads of `bands`/`counts`) ride
  the same response. `useComingUpFeed` returns the
  wrapper; its `Array.isArray` guard (`useComingUpFeed.js:103`) becomes an object-shape guard with
  `{ entries: [] }` as the degraded value; both `@returns` Javadocs change.
- Existing `comingUpFeed.js`/`WindowComingUpRow` render unchanged FROM `wrapper.entries` (visuals
  identical this phase — contract first, pixels later). Update the **empty-state and header copy**
  to "beyond Plan's four days": the eligibility split makes the old "Nothing dated in the next 90
  days" sentence false and the empty state genuinely reachable.
- **Handoff row** (design §1) per D14 — client-side from `briefing.hotTopics`, which
  `WindowFirstShell` already holds; render inside the Coming up pane. Click calls `onGoToPlan`
  wired to `selectTab('plan')` in the shell (same component tree; do NOT round-trip through
  `App`'s `tabRequest`). **`onGoToPlan` must also move focus** — the row it was clicked on is
  inside a panel `selectTab` immediately hides, the exact fall-to-`<body>` case
  `WindowFirstComingUp.jsx:100-102` already argues against; focus the Plan tab button
  (`tabRefs.current[tabs.findIndex(t => t.id === 'plan')]?.focus()`, matching `handleTabKey`'s
  precedent). `onGoToPlan(date)` takes the date now even though Plan cannot yet focus one — §11.9.
- Fixture updates: `comingUpFeed.test.js`, `WindowFirstComingUp.test.jsx`,
  `WindowFirstShellTabs.test.jsx` (mocks `getAlmanac` at `:23-24`), `App.test.jsx` (`:48`, `:139`)
  all move to the wrapper shape (fix their own "five sources" prose — `WindowFirstComingUp.test.jsx
  :213`, `comingUpFeed.test.js:397` — while touching them).

**Tests:** `AlmanacControllerTest` (wrapper shape; `meta`/`regions` still absent-not-empty),
`PlanHorizonTest`, eligibility + `enteredWindow` boundary tests, briefing window pin; frontend —
handoff row renders/navigates/focuses/degrades-without-briefing, feed renders from wrapper, latch
behaviour unchanged. Browser-verify the handoff row (desktop + 390px). No migration in this phase.

---

## 5 · P2 — chronology enrichment (backend only)

Grow each entry to the §13 shape. New code in `service/comingup/` (assembler, `SurpriseScore`,
`ComingUpScoringProperties`). `AlmanacService` keeps its shape: sources → assembly pass
(eligibility, merge, score, enrich, sort) → cache. Sources stay untouched except where a value they
already compute is **promoted from a `meta` string to a typed field** — drop the promoted keys from
`meta` in the same commit (P1 shipped frontend+backend lockstep; between P2 and P3a the old rows
render degraded title+detail, which the degrade rule handles — hence "no release between P2 and
P3a" in §3).

Rules to honour, with sources:
- **Bits for every ALMANAC entry** per D4 (rarity exact, magnitude 1.0 default; tide runs from the
  run-peak-range series with cold-start bucketing; king = big spring). This is the badge's food —
  P5 cannot work without it.
- **Facts are ordered server-side** and shaped as §13 says (`segments`/`tone` — extends
  `comingUpFeed.factsFor`'s two-tone vocabulary with `accent`; **no HTML crosses the wire**).
  `AlmanacEvent.meta`'s iteration order is salt-randomised per JVM, which is why order-by-
  construction matters.
- **"Say the definition once":** first-of-type in window computed at assembly → that entry is the
  feature card carrying `prose`; later runs are dense rows.
- **Coincidence merge + max rule + named winner** (D10).
- **Superlatives are computed, falsifiable-proof** ("biggest until November" by comparing every run
  in the window); if the comparison cannot be made, omit the tag — the degrade rule generalises:
  null field, absent key, never a synthesised value.
- **Tide sparkline data:** `tide: { range, delta, phase }` — `delta` is the existing
  `TideRunBuilder.rangeAnomaly` (`range − avgRangeMetres`, already on the wire today as
  `TideAlmanacSource`'s `"rangeAnomaly"` meta key — this is a string→typed promotion, not new
  maths; NOT `highWaterAnomaly`, a different quantity). `phase` is `HW`/`LW` for the marked water —
  it drives the wave inversion (§6b). The design's hardcoded 3.3 m national average is replaced by
  the port's own `avgRangeMetres` (§11.6).
- **`scoreNote`**: one server-authored sentence for high-band entries ("Annual, so rarity alone
  carries it over the top contour") — the since-line and the card threshold both read it; the
  client never composes score prose.
- **Threshold lines** (`threshold`): required on every tide-run entry and every entry of a type
  that will carry a standing condition (coastal tides, dust, inversions) — for tide runs it is
  computable now from the same run-peak series ("The other 4 runs in this window ranged 4.1–4.8 m
  and stayed in the strip"); forecast-peak thresholds (the interim constant cleared) land with P4,
  which owns the conditions. Entries belonging to no standing condition (equinox, meteors) ship it
  null. (P2 cannot ask "is this occurrence promoted?" — conditions don't exist until P4 — so the
  rule is keyed on type, and P4's status derivation then agrees by construction.)
- **Counts served once**: `counts: { fixed, forecast, byFamily }` — footer AND chips read it; no
  client counting of a filterable list (one rule, not two).
- **`bands` served** from `ComingUpScoringProperties` (`{ list, announce, interrupt }`,
  lower-inclusive) — shipped in P2 with the placeholder values so the shape is complete; P5's
  census re-sets the values before the badge goes live.
- **Region scope facts**: a fact row naming the regional scope (the design's `scope` label) is
  emitted only where the underlying read is genuinely regional; omitted entirely otherwise — never
  a synthesised "all regions". It is an ordinary `facts` row (segments), not a schema field.
- **`id`** per §13's rule; deterministic, unique by construction.

**Tests:** assembler unit tests per rule (first-of-type, merge, max-vs-sum with named winner,
superlative omission, counts, ordering, ids); `SurpriseScore` — band edges lower-inclusive, the
run-peak distribution vs the degenerate high-water one (a fixture proving a median run does NOT
score ≥ p95), cold-start bucketing, king-as-big-spring (a king run's bits carry rarity 3.9, not
8.5). ⚠️ `TideService.getTideStats` uses `LocalDate.now(ZoneOffset.UTC)`, not the injected Clock —
a fixed-Clock test of tide bits must stub/derive around it (or the phase fixes it and says so).
JaCoCo bites small new records at 80%/class — cover null branches with real assertions.

---

## 6 · P3a — chronology structure (frontend)

Recreate design §4/§5 against the P2 payload. The design of record is
`docs/design/coming-up/Coming Up.html` — load it in a browser next to the app.

- **Structure:** month rules; two-column entry grid — rail **66px, 58px below 900px, 54px on
  phone**; date-rail box (dow/number/month; ranges as `10–15`; month-crossing runs use BOTH slots —
  `26 SEP` over `–1 OCT`, never `26–1 SEP`); countdown line; card with topic-colour left rule
  (`border-left-style:dashed` when `kind === 'FORECAST'`); title row (name, NEW-flag slot for P5,
  kind tag, superlative tag, headline metric); prose (feature cards); facts row (three tones per
  §13); threshold line; exactly one action link. **Card click invokes the entry's single action** —
  the design's `cursor:pointer`/hover promises a behaviour, and the existing drilldowns can't
  serve T+13 (§11.5); the action is the honest destination.
- **Header/footer copy replaced, not kept**: sub-line becomes `· dated events beyond Plan's four
  days, next 90 days`; the footer paragraph becomes README §5's copy with counts from
  `counts.fixed`/`counts.forecast` — its old vocabulary job now lives on the per-card kind tag, so
  delete it rather than ship both. Keep "fixed in advance", NOT the design's "fixed by orbital
  mechanics" (§11.14 — two sources compute nothing orbital, per the file's own recorded warning).
- **Legend** (`fixed` / `still firming`) rendered unconditionally in the list head.
- **Filter chips** with served counts (`counts.byFamily`; mapping per D6 — Sun & moon covers
  eclipse, Air & dust covers air+dust); counts describe the unfiltered set and never change on
  filter; the All-equals-sum test from D6.
- **Colours/typography:** the seven `--color-topic-*` tokens (D6). Fonts are already self-hosted
  (`fonts.js`; design's weights all covered). Styling in `index.css` following the existing
  `.wf-cu*` bespoke-CSS conventions (Tailwind utilities are not the pattern in this area — §11.15).
- **`.wf-body` inset must not change between tabs** — recorded invariant.
- Replace `comingUpFeed.js`'s derivation where the server now answers; keep surviving pure
  helpers. Rewrite the kind-chip refusal comment (`WindowFirstComingUp.jsx:46-53`) to point at the
  design bundle; the tab-count refusal is D13's and is rewritten in P5.
- Phone (390px) and 320px passes.

**Tests** (per `frontend-test-standards.md`: mock `almanacApi`, `fireEvent`, `findBy*`, UTC suite):
month grouping, rail forms incl. month-crossing, dashed-vs-solid from `kind`, legend, chips + static
served counts, card-click-fires-action, empty/error/loading preserved, tone rendering.
`WindowComingUpRow.test.jsx` dies or is rewritten here, not in P1. Browser verification with
screenshots (desktop + 390px); the local DB has tide data only after a seed/backfill — state seen
vs asserted.

## 6b · P3b — sparkline, coincidence, actions

- **Tide sparkline:** new small component (SVG 104×24 viewBox rendered 84×24,
  `preserveAspectRatio:none`; amplitude `min(10, 3 + (range − portAvg) * 3.5)` with `portAvg` from
  the served delta identity; **sign from `phase` — LW inverts the cosine and drops the marker below
  the axis**; ghost wave at amp 3; marker + `<b>5.2 m</b> +1.9 vs avg` label from served strings).
  `aria-hidden`; the facts text carries the accessible answer. Reuse `chart/solarDayGeometry.js`
  ONLY if it genuinely fits (this is a fixed-phase cosine, not a solar day); **record the choice in
  the phase log** — P6's `TideRunRow` decision reads it.
- **Coincidence card:** per-line swatches in each topic's own colour, joining sentence, dashed
  separators.
- **Actions:** `plan` → `onGoToPlan(date)` (tab switch + focus per P1; date-focus inside Plan is
  deferred, §11.9); `coastal-spots` → the new `kind:'coming-up'` overlay trigger with
  `filterAction:'SEASCAPE'`; `dark-sky-spots` → same trigger with the `darkSky` flag. All per D8 —
  including the nonce-dependent both-directions effect and the second-handoff test. **Record the
  chosen trigger kind in the phase log** — P6's delete list depends on it.
- Sparkline amplitude/inversion unit tests (pure fn); action dispatch tests; the magic-number rule
  applies — name the sparkline constants.

---

## 7 · P4 — standing conditions (backend + frontend)

**Backend.** `conditions[]` per §13, built in the assembly pass:
- **Coastal tides** (D11 — one row, spring and king runs together, each occurrence keeping its own
  run label): rate from ephemeris (14.8 d), runs from `TideSizeIndex`/`TideRunBuilder` over the
  window, per-run bits per D4 (run-peak distribution, cold-start bucketing). Occurrence statuses
  per D11's precedence — `promoted` derived FROM membership of the assembled `entries` list and
  carrying `entryId`; `insidePlan` only for occurrences with no entry; else `heldBack`.
- **Dust / Valley inversions** (D4 interim): **dust** presence over the trailing 60 days ending
  yesterday comes from replaying `DustHotTopicStrategy`'s thresholds over the complete
  `forecast_evaluation` population (per-day presence across the roster) — NOT from
  `SurvivorSignalReader`, whose rows exist only for triage survivors and would inflate rarity
  (D4); the survivor surface still supplies the *forward peak* (it is the forecast side).
  **Inversion rarity stays on the config fallback until P7** — no unbiased inversion population
  exists. Rarity computed from observed arrivals at/above the evidentiary bar where the population
  allows, config fallback otherwise — and below the bar the rate label renders the raw count and
  span (`3 plumes since 12 Aug`) with the cadence clause **omitted entirely**, never synthesised. Quant line per D4's two-halves
  rule. Peak = max forecast intensity in T+0..T+3 **passing the `PEAK_LIGHT_WINDOW_MINUTES` gate**
  (D5 — satisfied by construction in v1, tested anyway: a 9/10 14:00 fixture loses to a 6/10
  sunrise one); no passing candidate → no peak, and the peak cell says so.
- The `reason` tag rides any occurrence whose bits came from the max rule, naming the winner (D10).
- Rate labels carry the region clause only where the read is regional (§5's scope rule).
- The trailing-window read runs once per UK day inside the cache-miss path — that is the README
  Q10 answer; if first-reader latency ever matters, move the build to a job that evicts (D2's
  caveat), never to a second cache key.
- **Interim is said in the UI** (README's explicit clause): conditions whose scoring is interim
  carry `interim: true`, and the strip renders a quiet `scores provisional` marker (header sub-line
  suffix) while any visible condition is interim — dropped automatically as topics go live.

**Frontend.** Design §2: container, header (+ the provisional marker), rows (grid
`auto minmax(0,168px) 1fr auto`, collapsing at 820px and on phone), `cadence` tag
(persistent/recurrent/deterministic — from §13, authored in config until P7 can derive it, §11.16),
caret rotation, expansion panel. **Panel visibility:** carry BOTH the `hidden` attribute and a
display class — Tailwind v4's preflight `[hidden]` rule is a known trap here (the repo documents
it twice; the design bundle's §2.1 warning is the same trap from the other side); pin with a test.
Occurrence rows: date/value/bits/reason/status grid, three statuses with their exact styling,
header counts computed from the occurrence list; rows collapse to `64px 56px 1fr` hiding status
and reason at **760px** (the design of record's own breakpoint; the README's 820 is the
condition-row figure — §11.17), and the row stays clickable when its status text is hidden.
`in the list →` scrolls to and highlights the entry via `entryId` — `scroll-margin-top:
calc(var(--wf-mast-h, 128px) + 6px)`, **`--wf-mast-h`, NOT `--wf-lens-reserve`**: the lens bar is
Plan-only and `useLensReserve` removes its variable on this tab, so the 188px fallback would
over-reserve by a bar that is not on screen. `inside Plan's four days →` calls `onGoToPlan(date)`.
Multiple panels may be open (`openConditions: Set`) — component-local state, NOT sessionStorage
(the doors' persistence is for a working position; a disclosure toggle is not that, and any
storage write would face the whole-value CodeQL rule anyway).

**Tests:** status derivation invariants (promoted ⇔ entry exists; **straddling run is `promoted`,
not `insidePlan`**; `entryId` resolves to a real entry), insidePlan boundary, held-back default,
reason-tag iff max rule, peak gate, evidentiary-bar degrade, quant-line halves (no config constant
ever labelled `median`/`p90`), panel `[hidden]` pin, scroll-to-entry, counts line, provisional
marker on/off, **`TideSurfaceAgreementTest` extended to the strip** (one height, one threshold,
three surfaces). Browser-verify the strip and panels (desktop + 390px).

---

## 8 · P5 — badge, lastSeenAt, arrivals

- **Migration** (check `ls backend/src/main/resources/db/migration/ | sort -V | tail -1` on THIS
  branch AND main; coordinate with P7 per §3): `app_user ADD COLUMN coming_up_last_seen_at
  TIMESTAMP WITH TIME ZONE` — nullable, no default, no backfill, header citing V136/V147's stated
  pattern. Postgres-only concerns are fine; migrations are proven in CI (no local Docker — run the
  local gate with the integration exclusion and read the PR's Backend job).
- **Settings:** `comingUpLastSeenDate` (London civil date, D3) on `UserSettingsResponse`;
  `PUT /api/user/settings/coming-up-seen`; the `@ValueSource` addition in the same commit; frontend
  `settingsApi.js` function beside `map-colours`'s with the same why-not-saveHome comment
  discipline.
- **Eager fetch** per D13, including both comment rewrites.
- **Bootstrap** per D3: on first open of the Coming up tab with a null `comingUpLastSeenDate`,
  quietly `PUT` now — the null→set transition only; without it the badge never activates for any
  account (the deadlock D3 records).
- **Sourcing:** `lastSeen` flows through **`WindowFirstBriefingContext`** — it already calls
  `getSettings()` (`:403-407`) and keeps only the home place; add the field to that same `.then`,
  the `value` memo and its deps. `App.jsx`'s own `getSettings` is a dead end (feeds only
  `homeCoords`/`mapColourScale`) — do not extend it, and do not prop-drill. Expose a
  `setComingUpLastSeenAt` beside `setOrigin` for Mark seen's optimistic clear —
  `homeSettingsVersion` only bumps when the settings modal closes and cannot serve it.
- **Derivation** per D3/D12: `isNew` by ISO-date string compare; badge from new `ALMANAC` entries
  clearing the served `bands` (lower-inclusive). **Census first** (D4): a synthetic-year fixture
  counts announced/interrupt arrivals across the assembled feed; set the shipped band edges from
  it against the design's ~10/year target; the fixture stays as a regression test.
- **Tab badge:** a third span inside the `Coming up` tab button (children are currently glyph +
  label only); give the button an `aria-label` (`Coming up, 1 new announced event`) — the badge
  text would otherwise just concatenate into the accessible name. Re-measure 320px overflow **with
  all four tabs present, i.e. an admin session** (the recorded 22px is that case; a two-tab LITE
  reader has none; the badge adds ~22px; the bar is `overflow-x:auto`, so degradation not
  breakage — record the number).
- **Since-line** above the chips (design §6 markup): client-selected highest-bits new entry,
  rendered from `{bits, title, dates, scoreNote}` — no client-composed score prose. `Mark seen` →
  `PUT` then optimistic clear via the context setter; NEW flags + fresh box-shadow on flagged
  entries; all clear together.
- **CLAUDE.md:** amend the Backend-heavy bullet to name D12's class, in this PR.

**Tests:** null-lastSeen quiet state AND its bootstrap (first open fires the `PUT`, shows no badge
and no NEW flags; a failed write stays null and does not loop), off-by-one boundary
(`enteredWindow == builtFor` entry is new the same day), band inclusivity, forecast-never-badges,
mark-seen round trip + optimistic clear, badge aria, census fixture. NO hysteresis test — v1 ships none (D4). Browser-verify badge states
(the design's demo has three) and 320px.

---

## 9 · P6 — remove the Hot topics door (Plan tab)

Execute D7. Read P3b's phase-log rows first (trigger kind; `solarDayGeometry` reuse). Concretely:
- Delete the door tile + panel from `WindowFirstDoors.jsx`; the surviving Regional planner door
  goes full-width with NO CSS change (`.wf-doors` is flex, `.wf-door` is `flex:1`; the 639px
  column rule becomes a harmless no-op — leave it). Keep or collapse `WindowFirstDoors` as a
  component by judgement with the code open.
- Delete `HotTopicStrip.jsx`, `components/shared/CertaintyChip.jsx`, `utils/topicCertainty.js`,
  and — per the phase log — `TideRunRow.jsx`/`SurgeRunRow.jsx`; their tests
  (`HotTopicStrip.test.jsx`, `TopicFacts.test.jsx`, `topicCertainty.test.js`,
  `TideRunRow.test.jsx`, `SurgeRunRow.test.jsx` as applicable); fix `instantsAbroad.test.jsx`
  (imports the strip at `:39`) and `WindowFirstDoors.test.jsx`. (`WindowFirstShellSticky.test.jsx`
  needs nothing — its fixture is already strip-free.)
- Remove the `kind:'topic'` branches in `App.handleShowOnMap` (`:255`) and
  `mapOverlay.buildMapOverlay` (`:107`) — P3b routed Coming up through `kind:'coming-up'`; verify
  no live caller remains (`grep "kind: 'topic'"`).
- **`index.css`:** delete `.hot-topic-*` (`:306-~395` incl. its 639px media rules) and
  `.tide-run`/`.tide-row`/`.runchip`/`.tr-*`/`.sr-*` (`:402-552`, including the 767px media block
  at `:541-552`; per the TideRunRow decision). ⚠️ **Do NOT touch
  `.wf-door-panel :is(…) { scroll-margin-top }` (`:2259-2267`)** — its comment names hot-topic
  pills but the rule exists for `HeatmapGrid`'s cells under the SURVIVING door, and its ⚠️ records
  the WCAG 2.4.11 defect it prevents; fix the comment, keep the rule. Fix the `.wf-cu-kind`
  comment (`:3539-3544`) that cites `topicCertainty` by name.
- `planDoors.js`: remove `'topics'` from `DOOR_IDS` — one line, no shim (D7).
- Prose references to the strip in surviving files: `windowFirstRows.js:20,28,32,186` (`:28`'s
  "⚠️ reconvergence with `HotTopicStrip` is DUE" is an instruction that becomes unfollowable —
  state in the CHANGELOG that the debt is discharged by removal, not deferred),
  `confidenceUtils.js:19`, `mapDates.js:177`, `WindowFirstLensBar.jsx:164`.
- LEDGER + CLAUDE.md: `auroraTonight`/`auroraTomorrow` write-only (v1-retirement §8 style; backend
  fields stay — owner call); update the Plan-tab "two doors" paragraph and the `auroraTonight`
  reader claim; `HotTopic.tideRun` loses its renderer if `TideRunRow` dies — say so.

**Tests:** single door renders full width; `DOOR_IDS` ignore behaviour; no dangling imports (lint +
`npm run build` are part of the gate). Browser-verify the Plan tab (doors row desktop + 390px).

---

## 10 · P7 — start logging (the model's future food)

- `topic_daily_log` (migration; number per §3's coordination rule): one row per
  `(topic_type, date, region_id)` — **region_id, never region name** (V137/V144 precedent) — with
  `present BOOLEAN`, `intensity NUMERIC NULL`, `landed_on_window BOOLEAN NULL`,
  **`band VARCHAR NULL`** (the prior-band store hysteresis needs — D4), `logged_at`. Append-only,
  no pruning.
- Nightly job after the last briefing build. Occupied cron slots (read the UPDATE migrations too —
  V139/V141 modify seeds): 02:00 Mon, 02:40, 03:00, 03:10, 03:15, 03:30 ×2, 04:00/14:00/22:00,
  05:00, hourly :20 — **04:40 is free**. Register via
  `dynamicSchedulerService.registerJobTarget` + a V146-style seed (the `description` column is
  rendered verbatim in the admin Scheduler view; the seed is its permanent source of truth).
- **Candidates:** DUST, INVERSION, SPRING_TIDE, KING_TIDE, STORM_SURGE, SNOW, AURORA, NLC. Aurora
  and NLC log `present` with null `intensity` — starting the clock is the point (§1.4).
- Optional follow-on: admin-triggered one-off backfill replaying dust + surge from
  `forecast_evaluation` history — buys dust's evidentiary bar instantly.
- No UI change. When P7 lands, note "revisit interim constants after ~90 days of rows" in the
  CHANGELOG and the phase log.

---

## 11 · Deliberate disagreements with the design bundle, and inherited defects

(The plan-matrix precedent: record every place the bundle and the codebase are made to disagree on
purpose, so a later reader stops hunting for the "missing" piece.)

1. **"`HotTopicStrip.jsx` is still used elsewhere" (README §10)** — false here; one caller. The
   removal is bigger than the design believed: D7.
2. **"Existing PhotoCast tokens — do not introduce new ones" (§8)** — two of the six family hues
   don't exist and two exist under other semantics; seven new `--color-topic-*` tokens (D6).
3. **The peak gate** is a third alignment window beside the existing 60 (tide) and 90 (surge)
   constants — named separately, applied to condition peaks only, satisfied by construction in v1
   (D5).
4. **Eclipse escalation `#f87171`** — not adopted. The `rarity` meta string from
   `EclipseAlmanacSource` rides the card as a fact; the prototype's fuller rare escalation
   (`returnYears > 10` → red rule, warm wash, `◆ once` line) is NOT built —
   `EclipseCatalog.nextComparable` is populated for one of five events and the next eclipse in any
   90-day window is 2027-08-02. Revisit with that event. `--color-topic-eclipse` keeps the
   treatment alive past `HotTopicStrip`'s deletion.
5. **Card click "opens the existing drilldown"** — no drilldown exists beyond Plan's window; the
   card click **invokes the entry's single action** instead, honouring the affordance the design's
   cursor/hover promises.
6. **The ghost wave's "3.3 m average tide"** — replaced by the representative port's own
   `avgRangeMetres` (the delta is the existing `rangeAnomaly`, promoted from meta).
7. **Forecast-peak chronology rows** — mostly unreachable at first ship (aerosol horizon T+0..T+3
   sits inside Plan's window, D9); the strip carries those peaks as `insidePlan`. Horizon
   extension is separate, unplanned work.
8. **Recurrent/persistent scoring is interim** (D4) — per the README's own fallback, with rarity
   upgraded to observed rates where the bar is met, and interim-ness **said in the UI** (P4's
   provisional marker) per the README's explicit clause.
9. **Dated Plan actions are half-deferred**: the design's interaction table sends "See the plan
   for 2 Sept" to Plan *for that date*, twice. `onGoToPlan(date)` carries the date from P1, but
   Plan-side date focus is a follow-on — the link lands on the Plan tab. A deferral, not an
   absence.
10. **The eligibility hard rule is restated**: "never contains an entry that *ends* inside Plan's
    window" — a straddling run is in the chronology (and is `promoted` in the strip, D11), where
    the design's absolute wording would exclude it.
11. **`enteredWindow` is a lower bound, not an exact arrival, for tide runs** (weekly-fetch far
    edge) and for anything appearing for a non-sliding-edge reason — such entries silently never
    badge. Accepted: silence is the chosen failure direction throughout (D3).
12. **Hysteresis does not ship in v1** — structurally uncomputable without a prior-band store; the
    store arrives in P7 (`band` column); bands may flap at edges until then, bounded because
    interim scores are constants and cold-start keeps the moving branch (tides) out of badging
    (D4).
13. **The band edges will not survive contact with the inventory** — the design's own placeholder
    edges make every shower and equinox badge (~18 announced/yr vs the stated ~10 target), and the
    design contradicts itself on spring runs ("a spring tide run entering at day 90 is silence"
    while its own arithmetic scores its 12 Sept run 8.2 = Announced). Resolved at P5's census —
    edges move; no topic is special-cased.
14. **"Fixed in advance", not "fixed by orbital mechanics"** in the footer — two sources compute
    nothing orbital (NLC season constant, `MonthDay` anchors), a distinction the current file
    records and this plan keeps.
15. **Bespoke CSS, not Tailwind utilities**, for the new surfaces — matching the `.wf-cu*`
    precedent; the "Tailwind only" standard bans inline styles, which this is not.
16. **The strip's cadence tag is authored (config), not derived, until P7** — the design says
    derived; there is no presence series to derive it from yet.
17. **Occurrence rows collapse at 760px** (the design of record's breakpoint), not the README's
    820 (its condition-row figure) — the bundle disagrees with itself; the narrower value keeps
    the status affordance alive on tablet.
18. **The since-line and badge are client-derived from a user-independent payload** (D3/D12) — the
    design's state sketch put `badge` in server state; the ETag/personal-data doctrine forbids
    that here.
19. **Plan's boundary is the fixed `today+3`** (D1), not the rendered-events horizon.
20. **Standing conditions at first ship: Coastal tides (one row for both run kinds — D11), Saharan
    dust, Valley inversions.** Aurora, NLC, snow excluded (§1.4); the design's strip shows the
    same three topics but names the tide row "Spring tides", which would misname king runs.
21. **A lone tide run in the window ships `threshold: null`, not silently — this is a recorded gap,
    not a design choice.** §5 says threshold is "required on every tide-run entry"; P2's
    `markTideSuperlativesAndThresholds` only computes one when ≥2 tide runs exist in the same
    window to compare against, because the line's whole content ("the other N runs ranged X–Y m")
    is a within-window comparison and has nothing to say with nothing to compare against. A
    degraded single-run line is possible — `TideRunPeakHistory`'s own historical series is already
    fetched for magnitude scoring and could supply a "runs here typically range X–Y m" sentence —
    but that changes the line's meaning from "other runs in this window" to "history at this port",
    which is a real design decision, not a mechanical fix, so P2 left it undone rather than
    inventing the sentence unreviewed. Whoever next touches `markTideSuperlativesAndThresholds`
    (P3a's rendering, or P4 once `conditions[]` exists) should close this rather than assume it was
    intentional.

---

## 12 · Standing gotchas for every implementing session

- **No Docker on the dev Mac.** Backend gate: `./mvnw clean verify --batch-mode
  --no-transfer-progress -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log
  2>&1; echo "exit: $?"` — gate on the echoed exit code, never a grep of `-q` output. Migrations
  are proven in CI only; read the PR's Backend job.
- **Migration numbers rot; two branches must never hold the same next number** (§3).
- **Frontend CI is four steps** — lint, vitest, `npm audit --audit-level=high`, build. The audit
  fails on transitive advisories nothing local surfaces; prefer a surgical lockfile edit to
  `npm audit fix` (which rewrites `libc` metadata CI's npm doesn't emit).
- **Tailwind v4 prunes unreferenced `@theme` tokens to `""` silently** — new tokens go in
  `@theme static`.
- **`[hidden]` vs preflight** — carry the attribute AND a display class; pin with a test.
- **Never `getItem`→`setItem` on web storage** (CodeQL conduit rule) — whole-value writes only.
- **Fixed clocks in date fixtures** — never the wall clock; the frontend suite runs in UTC and the
  backend anchors on Europe/London (`ForecastHorizon.today`). ⚠️ `TideService.getTideStats`
  currently ignores the injected Clock (`LocalDate.now(ZoneOffset.UTC)`) — plan around it or fix
  it explicitly.
- **The Browser pane suspends rAF, `ResizeObserver` and `IntersectionObserver`** while hidden —
  three recorded incidents in the plan-matrix log; measure/animate accordingly (P4's caret, P5's
  badge measurement).
- **Verification recipe:** `docs/engineering/plan-matrix-plan.md` §9 unchanged — `-Plocal-dev`
  port 8083, `admin`/`golden2026`, `frontend/.env.local` needs
  `VITE_API_TARGET=http://localhost:8083` in a fresh worktree, H2 file-lock seeding, grep for
  `Started GoldenHourApplication`.
- **Code standards bite the new code**: PropTypes on all components + `data-testid` on key
  elements (P3/P4 add ~eight components); no magic numbers (the sparkline constants, band edges —
  name them); Javadoc + 120-char + SpotBugs-at-`verify` on the new `service/comingup/` package.
- **Review agents are read-only.** Anything that must mutate gets its own worktree.
- **CHANGELOG conflicts are guaranteed** across parallel phases — rebase, don't fight.
- **The owner cuts releases** — never run `release.sh`, never tag, never push unasked.

---

## 13 · Consolidated response schema — the one place

**A field not in this appendix does not exist. A phase that needs one adds it here in the same
commit.** Phase annotations mark when each field first ships; everything is user-independent (D2).

```jsonc
// GET /api/almanac?days=90 → ComingUpResponse
{
  "builtFor": "2026-08-28",              // P1 — served UK civil date
  "bands": { "list": 5.0, "announce": 7.5, "interrupt": 9.5 },   // P2 (values re-set by P5's census); lower-inclusive
  "counts": { "fixed": 8, "forecast": 1, "byFamily": { "coastal": 4, "night-sky": 3, "sun-moon": 2 } },  // P2 — footer AND chips
  "conditions": [                        // P4 (empty [] from P1)
    {
      "type": "COASTAL_TIDES",           // D11: one row for spring+king
      "name": "Coastal tides",
      "cadence": "deterministic",        // persistent | recurrent | deterministic (authored in config until P7 — §11.16)
      "interim": false,                  // drives the strip's provisional marker (P4)
      "rateLabel": "a run every 14.8 days · fixed by the ephemeris",   // server-formatted, region clause only if regional
      "quantLabel": "rarity 3.9 · 7 runs in 90 days · …",              // D4 two-halves rule; no constant labelled median/p90
      "peak": { "dateLabel": "Thu 26 Nov", "valueLabel": "5.2 m", "bits": 9.0 },  // null when no gated peak
      "occurrences": [
        { "date": "2026-08-30", "dateLabel": "30 Aug", "valueLabel": "4.8 m",
          "bits": 5.4, "reason": null,          // reason: "max w/ supermoon" etc., names the winner (D10)
          "status": "insidePlan",               // heldBack | promoted | insidePlan; promoted outranks insidePlan (D11)
          "entryId": null }                     // non-null iff status == promoted; must resolve to entries[].id
      ]
    }
  ],
  "entries": [                           // P1 ships AlmanacEvent fields + enteredWindow; P2 the rest
    {
      "id": "spring-tide:2026-09-10:2026-09-15",  // `${type}:${startDate}:${endDate}` — deterministic, unique by construction
      "type": "spring-tide",             // the AlmanacEvent type string (P1)
      "family": "coastal",               // P2 — coastal|aurora|air|night-sky|sun-moon|dust|eclipse → --color-topic-* (D6)
      "kind": "ALMANAC",                 // P1 — ALMANAC | FORECAST; drives solid vs dashed left rule
      "startDate": "2026-09-10", "endDate": "2026-09-15",          // P1
      "enteredWindow": "2026-06-13",     // P1 — startDate − (DEFAULT_DAYS−1); D3
      "title": "Spring tide run",        // P1 (today's `title`)
      "detail": "…",                     // P1 passthrough; P3a may stop rendering it where richer fields exist
      "meta": { },                       // P1 passthrough; keys drain into typed fields during P2
      "regions": ["…"],                  // P1 passthrough; @JsonInclude(NON_EMPTY) — absent when empty, as today
      "kindTag": "Almanac",              // P2 — CARD display tag: "Almanac" | "Forecast · peak". NOT the strip's cadence vocabulary
      "superlative": "biggest until November",  // P2, nullable, computed falsifiable-proof
      "metric": "~20/hr",                // P2, nullable — headline metric string
      "prose": "…",                      // P2, feature cards only ("say the definition once")
      "facts": [                         // P2 — ordered; NO HTML on the wire
        { "segments": [ { "text": "biggest ", "tone": "base" },
                        { "text": "Sat 12 Sept", "tone": "strong" },
                        { "text": " · 47m before sunrise", "tone": "accent" } ] }
      ],                                 // tone: base | strong | accent (accent = design's <em>, topic colour)
      "threshold": "…",                  // P2 — required on any promoted-in-strip entry incl. tide runs; else null
      "scoreNote": "Annual, so rarity alone carries it over the top contour.",  // P2, nullable — since-line + card read it
      "action": { "label": "Show coastal spots for 12 Sept →", "kind": "coastal-spots", "date": "2026-09-12" },
                                         // P2 — exactly one; kind: plan | coastal-spots | dark-sky-spots
      "bits": 8.2,                       // P2 — non-null for every ALMANAC entry (D4); nullable on FORECAST until scored
      "interim": false,                 // P2 (added during implementation, not in the original brief —
                                         // see the P2 phase-log row) — false by default; true ONLY
                                         // when magnitude was bucketed (cold start) or entirely
                                         // unmeasurable (no derivable peak, no TideStats) — i.e. only
                                         // on the tide branches D4 names as not badge-worthy. A
                                         // non-tide type's magnitude default (1.0, the median) is "by
                                         // definition typical" per D4, not provisional, so it is NOT
                                         // interim — the field must not default true, or the badge is
                                         // structurally unfireable for the whole feed, contradicting
                                         // both D4 and §11.13's census premise. This is the entry-level
                                         // carrier a client badge reader (P5) needs: exclude interim
                                         // entries from clearing a band regardless of how high bits
                                         // reads, rather than distorting the printed
                                         // "rarity + magnitude = bits" sum to enforce it
      "tide": { "range": 5.1, "delta": 1.9, "phase": "HW" },   // P2, tide entries only; delta = rangeAnomaly; phase inverts the wave
      "coincidence": [ { "family": "coastal", "name": "King tide run", "factsLabel": "biggest Thu 26 Nov · 5.2 m · HW 16:13" } ],
                                         // P2, nullable — one card, ≥2 lines
      "joinNote": "…"                    // P2, nullable — server-authored joining sentence, max arithmetic stated
    }
  ]
}
```

Client-only state (never on this wire): `activeFilter`, `openConditions`, `highlightedEntryId`,
`isNew`/badge/since-line selection (D12, from `comingUpLastSeenDate` on `UserSettingsResponse`).
The handoff row renders from `briefing.hotTopics` (D14) — no handoff fields here.

---

## 14 · Adversarial review record

Round 1 (2026-08-28, pre-commit): six read-only lenses — backend factual truth, design fidelity,
architectural invariants, frontend feasibility/regression, executability-by-a-cold-session, model
correctness — ~40 confirmed findings, all incorporated above. The reversals worth knowing about
when reading old drafts of this plan: tide magnitude moved off `TideStats` height percentiles onto
a run-peak-range distribution with cold-start bucketing (the percentile form was degenerate — every
run ≈ p97); almanac bits moved into P2 (the badge otherwise could never fire); `handoff.liveTopics`
moved off the backend entirely (simulation/travel-day contamination of a cached payload);
`enteredWindow` gained the `−(days−1)` fix and the impurity caveat; hysteresis was declared
inert-in-v1 and deferred to P7's band column; the badge gained the eager-fetch decision (D13) and
the pre-ship census; the eligibility rule was restated on `endDate` with a status precedence; and
the schema was consolidated into §13. Findings explicitly refuted by reviewers (do not re-file):
the ETag whitelist needs no change for a wrapper DTO; `BriefingService:253/:497` extraction is
behaviour-preserving; the blast-radius list in D7 is correct and complete as amended; client-side
`isNew` does not violate the "no percentiles in the browser" rule; multi-day runs correctly enter
by `startDate`.

Round 2 (same day): a consistency pass over the revised text — ten residual defects fixed, the
notable one being that `bands` had no phase owner in the P2 brief (P5's badge would have had
nothing to read); also the `regions` passthrough restored to §13, the P2 threshold rule re-keyed on
type to break a circular P2→P4 dependency, and the §9 `index.css` delete range corrected to
`:402-552`.

Round 3 (external, Codex review on PR #678): two confirmed findings, both fixed in place. (1) The
`lastSeenAt` design deadlocked — null renders "nothing new", `Mark seen` was the only write, and
the since-line hosting it only renders when something IS new, so the badge could never activate
for any account; D3/P5 gained the quiet first-open bootstrap write. (2) The observed-arrival
rarity denominator was triage-biased — the survivor stores hold only rows that passed triage, so
gaps stretched and rarity inflated (over-promotion, the unsafe direction); dust now counts
arrivals over the complete `forecast_evaluation` population and inversion rarity stays on the
config fallback until P7 supplies an unbiased log.
