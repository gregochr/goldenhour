# Plan: the star and its two scores must agree

**Status: DRAFT v1, 2026-11-27. One phase, one PR.** Owner decisions taken 2026-11-27 are in §2;
the draft bands in §4 need the owner's sign-off before merge.

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
- Not in scope: the tide lift in `RatingCombiner` (the owner may raise it separately).

## 3. The change

All edits in `PromptBuilder` (the coastal builder appends a suffix to the same system prompt, so it
inherits them). No migration, no frontend, no new field.

**3a. A STAR AND SCORE BANDS rule in `SYSTEM_PROMPT`**, placed directly after the three scale
definitions. It states that `rating` is decided first and both scores must then be consistent with
it, using the §4 table, and that every other rule's numeric instruction (penalties, ceilings, the
IDEAL / thick-mid / blocked / clear-sky ranges) is applied *within* the band for the rating given.

**3b. The approach rule reworded** so its score penalty cannot leave the band. Today:
"fiery_sky is 20-30 points lower than the conditions alone would give, golden_hour is likewise
20-30 points lower, and rating is the LOWER of what the sky earns and 3". New intent: the rating
clause is **unchanged**; each score is still 20-30 points lower than the conditions alone would
give, **but never below the floor that the STAR AND SCORE BANDS set for the rating given**. The
schema descriptions get the same clause (see 3c).

**3c. Binding placement on the output fields.** The veto doc measured that a constraint stated only
in rules prose is advisory (golden_hour moved 4.4 points against 20-30) and one restated on the
output field is binding (19.5 points). So:

- the system prompt's "Output your evaluation as JSON with these fields" line names the band rule
  against `fiery_sky` and `golden_hour`;
- `buildOutputConfig`'s `fiery_sky` and `golden_hour` descriptions carry the compact §4 band
  (one line each) and, for `golden_hour`, the reworded approach clause. `rating` stays first in
  the fixed property order, so the model commits to the star before writing either score, which
  is what "scores follow the star" needs.

## 4. Draft bands (owner sign-off needed)

Keyed on the **higher of the two scores** ("best"): the star says how good the best thing the sky
offers is. A per-score band cannot work: the prompt's own CLEAR SKY rule rates a cloudless sky
≤3★ with `fiery_sky` 20-40 and `golden_hour` 65-85, and the BLOCKED rule gives 1-2★ with
`golden_hour` 15-30.

| Star | Higher of the two scores |
|---|---|
| 1 | 0–35 |
| 2 | 20–55 |
| 3 | 40–85 |
| 4 | 55–95 |
| 5 | 70–100 |

Bands overlap on purpose: a one-star wobble is acceptable, a two-star flip is not
(`rating-band-plan.md` §1).

Checked against every existing prompt rule:

| Rule | Its stated numbers | Fits |
|---|---|---|
| Blocked solar horizon, 1-2★ | fiery 5-20, golden 15-30 (best ≤30) | 1★ ≤35, 2★ ≥20 ✓ |
| Overcast | fiery 5-15, golden 10-30 | 1★ ✓ |
| Clear sky, ≤3★ | fiery 20-40, golden 65-85 | 3★ 40–85 ✓ |
| IDEAL, 4-5★ | fiery 70-90 | 4★ and 5★ ✓ |
| Thick mid, rate 4 | fiery reduced, unstated | needs best ≥55 (normally golden) |
| Approach signal, ≤3★ | 20-30 off each score | floored at 40 for 3★, 20 for 2★ |

And against every guard band in `SkyRatingEvalFixtures` (these are regression assertions; this
change must stay inside them, never edit them):

| Fixture bounds | Compatible |
|---|---|
| ≤2★, fiery ≤25, golden ≤35 | ✓ |
| exactly 4★, fiery 30-85, golden 30-85 | ✓ (best ≥55 inside 85) |
| 1-3★, fiery ≤70, golden ≤65 (ApproachFalsePositive) | ✓ |
| 1-3★, fiery ≤40, golden ≤55 (OvercastWithApproach) | ✓ (a 3★ needs golden 40-55) |
| 3-4★, fiery ≥25 | ✓ |
| ≤3★, fiery ≤40 | ✓ |
| exactly 4★, fiery 55-85, golden ≥55 | ✓ |
| exactly 4★, fiery 60-90 | ✓ |

**The Low Hauxley card under the bands:** 3★ needs the best score ≥40, so the approach penalty
stops there. Expect 3★ with a golden hour of about 40-50, and a sentence that matches "maybe"
rather than "muted and brief".

## 5. Gates

Local (Sonnet runs these to green):
- `PromptBuilderTest` pins the old approach wording (l.978-995). Update those pins to the new text
  (these are unit tests of the prompt string, not prompt-regression assertions) and add pins for
  the band rule in the system prompt and on both schema fields.
- Backend build ladder through the CI-equivalent `clean verify`.

Stop-points (owner-owned, not run by the session):
- `Prompt regression — sky` (CI, waits for owner approval; spends real money). Report its result;
  change no assertion. The eval fixtures in §4 are expected to stay green.
- `SystemPromptTokenCountTest` (tagged prompt-regression): the change only adds text, so the
  4,096-token cache floor cannot be crossed downwards. One cache write on deploy (the system
  prompt and output_config change).

## 6. After it ships

The sentence that names the approach risk stays (the prompt still asks for it). Cards with the
approach signal should show bars that match their star. No other rule's star moves by design; if
the prompt-regression run shows a star moving, that is the finding to report, not to fix by
editing bounds.
