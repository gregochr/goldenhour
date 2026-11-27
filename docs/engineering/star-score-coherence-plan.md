# Plan: the star and its two scores must agree

**Status: APPROVED v2, 2026-11-27. One phase, one PR.** v1 was reviewed the same day by two read-only
agents (prompt-rule consistency; regression and model risk), §8. Owner decisions are in §2 and §7.

## 1. The problem, as seen

Low Hauxley, Sat 28 Nov sunrise. The sky was rated **3★** ("maybe"), with **Red Sky 30** and
**Golden Hour 25**, and the summary read "would normally earn a 4, but … any colour will likely
be muted and brief". The bars and the sentence describe a 2★ morning; the star says 3. (The tide
then lifted it to the 4★ the card showed: a separate question, not addressed here.)

The cause is in the system prompt (`PromptBuilder.SYSTEM_PROMPT`). The morning carried the
**combined cloud approach signal** ([BUILDING] trend plus upwind current ≥60%). For that case
the prompt says:

- `fiery_sky` is 20-30 points lower than the conditions alone would give;
- `golden_hour` is likewise 20-30 points lower;
- `rating` is the LOWER of what the sky earns and 3.

"What the sky earns" is the pre-penalty figure (4 here), so the star takes a ceiling while both
scores take a full subtraction. Claude followed the prompt exactly.

More generally, **nothing in the prompt relates the star to its two scores.** They are three
independent judgments emitted by one call, so nothing stops a 3★ with bars at 30/25.

## 2. Owner decisions, 2026-11-27

- **The star and the scores must agree.** It is a foundational part of the forecast.
- **The scores follow the star, not the other way round.** The August measurement
  (`cloud-approach-veto-fix.md` §9, the promptable cut: +3.4pp, then +4.7pp separation in
  observed horizon cloud between fired and not-fired) found the combined approach signal barely
  discriminates. That is why its absolute 1–2★ ceiling was demoted to "cap 3"
  (`veto-demotion-plan.md`). The `golden_hour` −20..−30 was added on 2026-08-18 to close an
  omission and was measured only for compliance, never for size. So on the evidence the
  over-penalised figures are the scores, not the star. The rating cap stays exactly as it is.
- **No code-side consistency check.** No shadow log, no clamp. The fix is the prompt.
- Not in scope: the tide lift in `RatingCombiner`.

## 3. The principle the prompt will state

1. **The rating comes only from the rules.** The bands never raise or lower a star; the star is
   decided first (it is the first schema property) by the existing rules.
2. **The bands then bound the HIGHER of the two scores** (§4). The other score may sit anywhere
   lower. A per-score floor is wrong: `copt-hill-15mar-overcast` caps `fiery_sky` at 40 at up to
   3★ (`SkyRatingEvalFixtures.java:140`).
3. **`golden_hour` carries the band; `fiery_sky` is never raised to meet it.** Red Sky is colour
   in the sky and must stay honest; Golden Hour is overall light quality, the score that moves
   when the light is worth more than the colour (a muted canvas, the thick-mid rate-4 floor).
4. **Hard ceilings win over the bands.** Where a rule states a score ceiling ("non-negotiable"
   blocked horizon; upwind-alone fiery ≤25, golden ≤30), the scores keep the ceiling and the
   exceptions in §7 decide the star.

## 4. Draft bands (owner sign-off needed)

| Star | Higher of the two scores |
|---|---|
| 1 | 0–35 |
| 2 | 15–55 |
| 3 | 40–85 |
| 4 | 55–95 |
| 5 | 70–100 |

v1's 2★ floor was 20; the blocked rule (golden 15-30), overcast golden (10-30) and fiery (5-15)
allow a 2★ best of 15–19, so the floor drops to 15. Bands overlap on purpose: a one-star wobble is
acceptable, a two-star flip is not (`rating-band-plan.md` §1).

Every rule that states star or score numbers:

| Rule (PromptBuilder line) | Its stated numbers | Under the bands |
|---|---|---|
| Blocked solar horizon, 1-2★ (:119-122) | fiery 5-20, golden 15-30 | ✓ |
| Overcast scale (:263-265) | fiery 5-15, golden 10-30 | 1★ ✓ (2★ needs best ≥15) |
| Clear sky, ≤3★ (:181-183, :262-265) | fiery 20-40, golden 65-85 | 3★ ✓. A clear sky held to 2★ (orientation cap, haze) needs golden ≤55: accepted, the 65-85 assumes good air and a facing horizon |
| IDEAL, 4-5★ (:126-127) | fiery 70-90 | ✓ |
| Thick mid, rate 4 (:128-140) | fiery reduced, golden unstated | golden ≥55 carries the 4★ (principle 3). Narrows today's `st-marys` latitude (golden 30-85) but stays inside it |
| [BUILDING] alone (:271-272) | fiery −15-30, no star cap | IDEAL 5★ with fiery 40-75 needs golden ≥70 or the star stays 4: compliant |
| Combined approach signal, ≤3★ (:273-287) | scores −20-30 | **reworded:** the penalty stops at the band floor for the star given (3★: higher score ≥40) |
| Upwind alone, persists (:288-289) | fiery ≤25, golden ≤30 | best ≤30 fits only 1-2★. Collides with IDEAL / thick-mid 4★: **§7 Q1** |
| Inversion boost (:229-233) | MODERATE +1★, STRONG → 5★ | needs best ≥55 / ≥70, often over a blocked horizon whose ceiling is 30: **§7 Q2** |
| Valley mist "may rate 4-5" (:356-360) | no horizon colour | same as inversion: **§7 Q2** |
| Orientation, cap 3, fiery −10-20 (:210-214) | — | ✓ |
| THIN STRIP 3-4, FAR CORRIDOR "3 available", [CLEARING], coastal suffix | no scores | ✓ |

Every guard band in `SkyRatingEvalFixtures` (regression assertions: stay inside, never edit) was
checked by both reviewers against `RegressionBounds(rating, fieryMin, fieryMax, goldenMin,
goldenMax)` and all eight are satisfiable under the bands.

**The Low Hauxley card under the bands:** 3★ needs the higher score ≥40, so the approach penalty
stops there: expect a sky of 3★ with Golden Hour about 40-50. Two things will NOT change: the tide
still lifts it to 4★ on the card (below the 4★ band, as the combiner is out of scope), and the
summary's tone is not driven by this change, so "muted and brief" may persist. If it does, that is
a follow-up on the summary, not a reason to widen this one.

## 5. The change

All edits in `PromptBuilder` (`CoastalPromptBuilder` appends a number-free suffix to the same
system prompt). The bluebell and woodland builders have their own prompts and are untouched.

- **5a. A STAR AND SCORE BANDS block** after the three scale definitions, stating §3's four
  principles and §4's table, plus the §7 rulings.
- **5b. The combined approach clause reworded:** rating clause unchanged; "fiery_sky and
  golden_hour are each 20-30 points lower than the conditions alone would give, but the HIGHER of
  the two never falls below the floor of the band for the rating given".
- **5c. Binding placement** (veto doc: prose moved golden_hour 4.4 points, the field restatement
  19.5): the "Output your evaluation as JSON with these fields" line and the `golden_hour` schema
  description both carry the band and the reworded clause; `fiery_sky`'s schema description says
  it is never raised to meet the band. Both descriptions must still start `0-100 inclusive.`
  (pinned, `PromptBuilderTest:446`). `rating` stays first in the property order.
- **5d.** The 3★ headline example "Soft pastels overhead but the horizon's blocked" (:317)
  contradicts the blocked ceiling; reword to "half-blocked".

## 6. Gates

Local (Sonnet, to green):
- `PromptBuilderTest` l.978-995: update the old-wording pins to the new text, **keeping both
  placements pinned** (rules prose and the field list: the measured lesson), add a pin on the
  `golden_hour` and `fiery_sky` schema descriptions (none exists today), pins for the band block.
- `PromptGoldenMasterTest`: regenerate `src/test/resources/prompt-golden/*.txt` with
  `-Dprompt.golden.regenerate=true`; the planner reviews the diff.
- Backend build ladder through the CI-equivalent `clean verify`.

Owner-run before merge (real money; the session does not run these or change any assertion):
- **Sky Eval on Haiku 4.5** (Batch Far-Term's model, and the one that ignored prose twice):
  `-Dsky.runs=8 -Dsky.model=HAIKU`, plus `copt-hill-11mar` and `-15mar` against the 12-run table
  in `cloud-approach-veto-fix.md`. Watch for golden bunching at a floor (exactly 40), the table
  read backwards (a high golden lifting the star), and 2★ golden drifting up.
- `Prompt regression — sky` in CI (waits for owner approval). `ValleyMistPromptRegressionTest`
  asserts star ranges only.
- Cost: about four cache writes (inland and coastal × two models). `prompt_hash` starts a new
  calibration bucket; note it in the changelog.

## 7. Owner rulings, 2026-11-27

- **Q1 — upwind alone, the model agrees the cloud persists** (fiery ≤25, golden ≤30): **the star
  drops to ≤2**, binding against IDEAL and the thick-mid rate-4 floor, as the combined-signal cap
  does. A blocked horizon in all but name; the one place the star follows the scores. State it on
  the rule and on the `rating` schema description (binding placement).
- **Q2 — foreground boosts (inversion MODERATE/STRONG, valley mist "may rate 4-5"): exempt from
  the bands.** The scores stay what the sky earns; the summary names the inversion or mist as the
  reason for the star.
- **Q3 — the §4 bands: approved as drafted.**

## 8. Review record, 2026-11-27

Two read-only reviewers. Taken: upwind-alone collision (blocker, §7 Q1); boosts forcing scores up
(§7 Q2); thick-mid and which score carries the band (§3.3); 2★ floor 15; headline example; "each
score" read per-score would break `copt-hill-15mar` (§3.2); clear sky at 2★ (§4); Haiku measurement
(§6); keep both pin placements and pin the schema text (§6); golden masters (§6); "bands never move
the star" (§3.1); the tide-lifted card and summary tone (§4). Confirmed not a problem: the eight
fixture bounds, cost beyond four cache writes, other regression tests.

## 9. v3, 2026-11-27: narrowed after the Haiku measurement

The owner ran the Sky Eval on Haiku 4.5 (8 runs per fixture) on the PR and on `main`. The global band
table destabilised Haiku in both directions the v1 reviewers predicted: it read the table score-first
and pulled scores toward band middles.

| Fixture | `main` | v2 (bands) |
|---|---|---|
| far-corridor-blanket-canvas (gated, blocked) | 2★ ×7, 3★ ×1, 18/28 | 3★ ×5 at 48/62 |
| st-marys-7apr clear-sky cap | 3★ ×7, golden 62-72 | 1★ ×8, golden 28-35 |
| copt-hill-5mar washout (blocked) | 2★, fiery 18 | 2★, fiery 28 (above bound 25) |
| copt-hill-15mar (approach) | 3★, 35/40-45 | 2★, 20/28 |

Sonnet 5.5 passed in CI, but Batch Far-Term runs on Haiku. **Owner decision: narrow the PR.**
- Removed: the STAR AND SCORE BANDS block, the band text on the JSON field list, and the band text on
  the `fiery_sky` / `golden_hour` schema descriptions.
- Kept, local to the combined approach rule: when it leaves the rating at 3, the 20-30 point score
  penalty stops once the higher of the two scores reaches 40 (prose, field list and `golden_hour`
  schema description: the binding placement).
- Kept: upwind alone persists → rating ≤2 (a plain star rule), and the "half-blocked" headline
  example.
- A general star↔score guarantee is not achieved by prompt on Haiku; a code-side check (§2, declined
  for now) is the route if it is revisited. Re-measure v3 on Haiku against the `main` run above.

## 10. v4, 2026-11-27: the approach floor only

The owner's Haiku 4.5 eval on v3 (`a3c6df4`, 8 runs per fixture) was still worse than `main`:
far-corridor-blanket-canvas 3★ in 7/8 runs (`main` 1/8; one summary quoted the new "half-blocked"
headline example back), copt-hill-15mar 2★ ×8 (`main` 3★; the summaries apply the new upwind
"MAXIMUM 2" field-list clause to an upwind bank the model says dissipates), and the clear-sky cap
1–2★ with golden 35 (`main` 3★, golden 62–72). The 40 floor itself held (copt-hill-11mar 3★,
golden 40–50). **Owner decision: keep only the approach-rule floor.** The headline example returns
to `main`'s wording and the upwind-alone ≤2 cap is removed from the prose, field list and `rating`
schema description. Note: far-corridor misses 1/8 on `main` too, so the bar is "no worse than
`main`", not 8/8.
