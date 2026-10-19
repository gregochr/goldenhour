# Same-cell rating spread — measurement and guard options

**Status:** measured 2026-10-16. Option (b) was chosen by the owner on 2026-10-17 and built and merged as
`docs/engineering/neighbour-guard-plan.md` (G1a–G4, on by default); option (a), the FAR bucket on
Sonnet 5.5, remains the owner's Models-screen call. No prompt or pipeline behaviour has
changed. The instrument is `scripts/measurements/same-cell-rating-spread/` and its numbers are in
that directory's `results.md`.

## 1. Why this exists

On 2026-10-16 Ask offered Caw Gap at sunrise on Sunday 18 October as its top pick, rated 4★. Its
neighbours along Hadrian's Wall were rated 2★ and Cawfields 1★. The rating was real: the nightly
batch wrote it at T+2 on Haiku 4.5 (the FAR bucket). The two stored prompts
(`forecast_evaluation_prompt` rows 106949 and 106950) differ by 1 point of solar-horizon low cloud,
0.2 °C of dew point, 0.2 µg/m³ of PM2.5, nine seconds of sunrise time and the place name. Both
prompts sit on the same decision line: 37–38% solar-horizon low cloud falls between the prompt's
"clear below 20%" and "penalise at 40–60%" rules, and an upwind sample reading 85% now and 0% at
the event can be read either way.

Nothing in the pipeline compares a rating with its neighbours. `RatingValidator` only checks that a
star is on the 1–5 scale. `VerdictSampleGate` protects a REGION's verdict word, because one lone
rating cannot carry a region past five rated voting slots and half the roster examined. An Ask pick
is per place, and needs only a served rating of 3★ or more in a verdict-eligible region
(`AskSnapshot.isPickEligible`). The Plan card's best-reachable line also picks one place on its
score. So one noisy star among quiet neighbours goes straight to the two surfaces that name a place.

## 2. What was measured

Every rated sunrise and sunset in `forecast_evaluation` over the last 60 days, sky locations only
(no wood, bluebell site or wildlife hide). Two groupings:

- **Same cell.** Places sharing `locations.grid_lat`/`grid_lng`, rated in one run, for one window,
  by one model, at one horizon. 33 cells hold two or more enabled sky places (82 places in all).
- **Near twins.** Two places within 12 km, same run, window, model and horizon, whose solar-horizon
  low, mid and high cloud agree to within 5 points. This catches the Caw Gap case, which the exact
  cell does not: the ten Hadrian's Wall sites sit in seven different `grid_lat`/`grid_lng` cells, and
  Caw Gap and Cawfields are in different ones, although their prompts carry identical observer-point
  cloud.

A coastal tidal place's served star averages in its own tide score, so tidal neighbours can differ
honestly. The noise measure is therefore the **inland** figure (no tidal member) and, where every
member has one, the **sky** figure on `sky_rating`.

## 3. Findings

**Haiku 4.5 disagrees with itself; Sonnet 5.5 does not.**

| | Haiku 4.5 | Sonnet 5.5 |
|---|---|---|
| same-cell inland groups 2+ stars apart | 3–8% by horizon | 0.3–0.5% |
| same-cell groups 2+ apart on `sky_rating` | 4.5–14% | 0.2–0.3% |
| near-twin inland pairs 2+ stars apart | 6–11% | 0.2–0.3% |
| guard-flaggable rows, inland (3★+, 2+ above every twin) | 27 at T+2, 9 at T+3 in 60 days | 1 in 34 runs |

- **It is the model, not the horizon or the weather.** Haiku rated T+0/T+1 until 4 October. At that
  same horizon it disagreed 7.4% and 8.3% of the time on inland cells, against Sonnet's 0.3% and
  0.5% since 8 October. In the same week, under the same weather, Haiku's T+2 inland pairs
  disagreed 11.8% of the time and Sonnet's T+0/T+1 pairs 0.2–0.3%.
- **The FAR bucket is still Haiku.** Every T+2 and T+3 star is Haiku's. A weekend seen from Thursday
  or Friday, which is what Ask is asked about, is Haiku's until the nightly run re-scores it at T+1.
- **Sonnet's served-rating spread is tide.** It shows 7% of same-cell groups 2+ apart on the served
  star, but 81 of its 84 such groups have a tidal member, and on `sky_rating` they agree.
- **The worst inland cells are valley and fell sites**: Swaledale (Angram & Thwaite Barns), Little
  Langdale, Malham, the College Valley, Low Force and Bothal, each 2+ stars apart in 7–18% of their
  groups on Haiku.

## 4. Options

### (a) Move the FAR bucket from Haiku 4.5 to Sonnet 5.5

**What changes.** The `BATCH_FAR_TERM` row on the Models screen. The model for a run type is decided
by the `model_selection` row, so this is an owner action with no code or deploy. T+2 and T+3 SETTLED
sky ratings would come from the model that already rates T+0/T+1.

**Cost.** Measured over the nine nightly runs since 8 October (`far-cost.sql`):

| | calls a day | cost a call | cost a day |
|---|---|---|---|
| FAR on Haiku 4.5 today | about 195 | $0.00098 | $0.19 |
| FAR on Sonnet 5.5 | about 195 | $0.0028 (measured on its T+0/T+1 calls) | $0.55 |
| difference | | | **+$0.36 a day, about $11 a month** |

That raises the nightly sky batch by about 12% (near-term Sonnet costs about $2.82 a day). The
batch cache primer warms one prefix per model and prompt builder, so FAR and near-term on one model
share their prefixes; the primer may then submit one or two fewer primer batches a night. That
saving is small and not measured.

**What it breaks.** Nothing in code. The sky-rating eval harness runs its own fixed models
(`SkyRatingEvalService.SCHEDULED_MODELS`: Haiku, Sonnet 4.6, Opus) and does not read the Models
screen. The prompt-regression suite fixes its model per case, and `PromptReplayHarnessTest` takes
its models from `REPLAY_MODELS`.
`forecast_evaluation_prompt` stores the user message alone, so a stored FAR prompt replays on any
model. Two known costs carry over from the near-term switch, which the owner already accepted
there: Sonnet 5.5 failed one regression case that Sonnet 4.6 passes (2026-10-04), and it is not
in the eval harness's scheduled models, so its accuracy is not tracked weekly.

**What it does not fix.** A Sonnet near-twin disagreement still happens, about 1 in 300 inland pairs.

### (b) A neighbour consistency guard

A served rating of 3★ or more that sits at least 2 stars above every near-twin is flagged. What then
happens to it is the real choice:

| response | for | against |
|---|---|---|
| **clamp** to the twins' median | cheap, deterministic | rewrites Claude's output. `RatingValidator` rejects rather than clamps on purpose, so an error stays visible; a clamp hides it, and would also hide a real local difference (a fell top above a valley inversion) |
| **re-score** the flagged slot | keeps the rating a model's judgement | needs a new pipeline step after the batch lands, about 1–2 extra calls a night at FAR volumes (pennies) but a second batch round trip or a synchronous call, and a Haiku re-score is noise judging noise; it would have to use Sonnet |
| **serve with a provisional mark** | no data change | does not stop Ask picking it or the best-reachable line naming it, which is the harm |
| **withhold from place-naming surfaces** (Ask pick eligibility, best-reachable line), keep the star on the map and in the region mean | stops the actual harm; read-only; reversible | a new rule in `isPickEligible` and the card's reach pool; a real outlier is skipped for one cycle |

**Where it would sit.** At serve time, over the served slots: each `BriefingSlot` carries
`locationId` and `skyRating`, and the location coordinates are one lookup away (`AskLocationNames`
already loads them for Ask). It needs no migration and no batch change. It must compare
`sky_rating`, not the served star, or it catches every tidal place that out-rates its neighbour on
tide: on Sonnet, 46 of the 47 rows the served-star guard would flag are that case. A row written
before V164 has no `sky_rating` and is never flagged. The solar-cloud similarity test the
measurement uses needs `forecast_evaluation` columns the served slot does not carry; a serve-time
guard would use distance alone (say 12 km), which is looser and should be tuned against
`guard-candidates.sql` before shipping.

**What it costs.** No API spend for the withhold variant; one guard function, its tests, and a line
in CLAUDE.md's pick-eligibility description. **What it breaks.** Nothing in the eval harness, the
regression suite or replay, which score prompts, not served slots. It changes which places Ask can
name, so Ask's own fixtures (`AskSnapshot` tests, the Ready freshness re-check, which compares a
stored pick with live eligibility) need a case for it.

**If it is built, withhold is the right response.** It is the only one that stops the harm without
rewriting a model's output, and it is reversible by deleting one clause.

### (c) One prompt per grid cell, fanned out

**What changes.** Places in one cell are scored once on one prompt with the per-place differences
(name, dew point, PM2.5, sunrise time to the second) dropped, and the result is written to every
place in the cell.

**What it costs.** It saves calls: the 33 shared cells hold 82 of the 231 enabled sky places, so at
most 49 calls a window and cycle, about 20%, before the coastal exclusion below shrinks it.

**What it breaks.**
- **It misses the case that started this.** Caw Gap and Cawfields are in different exact cells. A
  grouping wide enough to catch them (near twins by distance and inputs) is not a grid fact; it
  would be decided per run, which makes "which prompt scored this place" a runtime question.
- **Coastal places cannot share.** The prompt and the served star carry each place's own tide
  state, and the coastal and inland prompt builders differ. Most shared cells on the coast (Craster,
  Flamborough, Whitby, Lindisfarne) would still need one call a place.
- **Every per-place record changes meaning.** The batch `custom_id` is per location
  (`fc-<locationId>-<date>-<event>`), `ForecastResultHandler` scores one `forecast_evaluation` row per
  response, `forecast_evaluation_prompt` holds one prompt per evaluation row, and
  `cached_evaluation` is keyed by location name. Fan-out touches all four, and replay would replay a
  prompt that names no place.
- **It hides the noise rather than removing it.** A cell scored once is consistent, but a Haiku
  coin-flip now lands on the whole cell, and neighbouring cells still disagree.

## 5. Recommendation

**Do (a): move the FAR bucket to Sonnet 5.5 on the Models screen.** It removes about 95% of the
measured disagreement for about $11 a month, needs no code, and is reverted on the same screen.
After a week of nightly runs, re-run `summary.sql` and `neighbour-pairs.sql` with `since` set to the
switch date; Haiku's T+2/T+3 rows stop and Sonnet's inland figures should stay near 0.3%.

**Hold (b) in reserve.** Build the withhold variant only if the post-switch measurement shows Sonnet
still producing flaggable inland rows, or if Ask's top picks keep naming lone outliers. It is the
cheap, safe second layer, not a substitute for (a): it hides one bad star per run but leaves the
region means and the map fed by a noisy model.

**Do not do (c).** It misses the motivating case, cannot cover the coast, and rewrites four
per-place contracts to save money the project is not short of.

**An optional confirmation before switching, costing pennies.** `PromptReplayHarnessTest`
(`prompt-regression` profile, needs `ANTHROPIC_API_KEY`) replays stored prompts N times on chosen
models. Replaying the Caw Gap and Cawfields prompts (`forecast_evaluation_prompt` 106949 and 106950)
20 times each on `HAIKU,SONNET_55` would show the same-prompt spread directly, separate from the
neighbour comparison. It bills the real key, so it is the owner's run.

## 6. Open questions for the owner

1. Switch FAR to Sonnet 5.5 now, or first add Sonnet 5.5 to the eval harness's scheduled models so
   its accuracy is tracked weekly?
2. If (b) is wanted later, is withholding a place from Ask and the best-reachable line, while the
   map keeps its star, the right product behaviour?
