# Broken low cloud: does the prompt miss colour that a gappy low deck gives?

**Status:** draft, revised after adversarial review, 2026-11-16 (two read-only reviewers: physics and
statistics, and the code and replay tooling; §8). Nothing built. Phase A is read-side and spends nothing;
Phase B spends Claude money on a replay, gated on the owner's approval of a printed cost; Phase C changes
the production prompt only if A and B pass. One PR per phase.

**Trigger.** Angel of the North, 31 Oct 2026 sunset, evaluation 137844 (the 12:38 UTC run), sky_rating
3★, fiery 28, golden 62. Summary: "no mid or high cloud anywhere to catch colour". The owner photographed
a textured band along the western horizon lit orange under a pale high veil, and first described it
(this session, and `model-disagreement-sizing-plan.md` §1) as broken altocumulus. ERA5
(`cloud_verification`, verified 2026-11-07) disagrees:

| 12:38 run | Forecast | ERA5 |
|---|---|---|
| Horizon cone low (113 km) | 19 | 20 (bearings 5–37) |
| Horizon cone mid / high | 0 / 0 | 6 / 23 |
| Overhead low / mid / high | 0 / 0 / 0 | 5 / 0 / 66 |
| Far 226 km low / mid / high | 26 / 0 / 0 | 16 / 0 / 36 |

ERA5 is a ~31 km reanalysis, so it cannot overrule a photograph, but it has almost no mid cloud anywhere.
**The working hypothesis** is that the lit band was broken low cloud, which the forecast had almost
exactly, and that the prompt read it as a clear sky. Phases A and B test that hypothesis; they do not
assume it. The missed high veil belongs to `model-disagreement-sizing-plan.md`.

**What this plan must fix, stated plainly.** The trigger was forecast 3★, and a rule capped at 3★ would
not have changed its stars, only its fiery score and summary. So the rule earns its place only if Phase A
finds one of two user-visible errors at a useful rate:
- **(E1) stars**: broken-low evenings forecast 1–2★ (sky_rating ≤ 2; A3 measures this from forecasts
  alone, and whether readers would have rated them higher is A5's protocol question);
- **(E2) words**: summaries that tell readers there is "no canvas" or "no cloud to catch colour" on
  evenings that had a broken low deck.

If neither is common, the plan stops at Phase A.

**Builds on:** `far-cloud-lid-plan.md` §2 (geometry) and §9; `far-cloud-p1-plan.md` and
`scripts/far-cloud-p1/` (exports, `premise.py` slot selection and unit machinery, `stats.py`,
`canvas.py`'s ratings join, `canopyreplay.py` as the pattern for a replay sibling, the harness
`PromptReplayHarnessTest`, `replay_set.py build-r1`). Nothing here overrides them.

---

## 1. What the prompt does today

`PromptBuilder.java`, system prompt (exact text is in the dumped `system-inland.txt`, §5):
- ~l.119–125: solar-horizon low cloud has three bands, all about **blocking**: > 60% blocked, 40–60%
  penalised ("mid/high cloud above may still catch colour if gaps exist", so the gaps serve only mid/high),
  < 20% "strong light penetration". **20–39% has no rule at all.**
- ~l.262–263: "fiery_sky: … Requires clouds (mid/high) to catch light. Clear sky = 20-40."
- Existing credit for low cloud, none of it solar-side: antisolar low cloud "can itself catch reflected
  colour" (~l.141–143); "broken cloud canvas" appears only in the definition of a 5 (~l.90–91); "Patchy low
  cloud (20-50%) with breaks — crepuscular rays" (~l.196) sits in the **mist** block.
- ~l.177–179 ("low cloud blocks, mid and high catch it") is the fallback for when directional data is
  missing; it does not apply to these slots.

So a slot with broken solar-side low cloud and no mid or high anywhere is scored as a clear sky
(fiery 20–40). That is the gap.

**Physics** (`far-cloud-lid-plan.md` §2 (C), corrected after review). With the sun on the observer's
horizon, a deck over the 113 km cone is about 1° below the sun's line and is **lit from above**, not
underlit; its underside sits near 0° apparent elevation and cannot be seen. Orange **undersides** seen at
sunset are near-field cloud, about 20–80 km sunward, base 1–3° up, underlit through a clear slot that runs
through and beyond the 113 km cone. Distant broken cloud contributes lit tops and rims. The colour window
runs from about sunset to ~20 minutes after it (mirror: ~20 minutes before sunrise). So:
- the 113 km cone low reading is a proxy for **the gaps the light needs**, not the canvas itself;
- the shape needs a **clear slot further out** (the 226 km far point low);
- the canvas is the broken deck nearer the observer, so results are split by **observer low**.

---

## 2. Phase A: is there an error worth fixing? (no network, no spend)

### A0. Exports (owner, before 2026-11-30)

Stored prompts are deleted after `photocast.forecast-prompt.retention-days` (30) by
`ForecastPromptCleanupJob` (nightly 03:50 UTC), so the 31 Oct prompts go about 30 Nov–1 Dec. From the
MacBook clone, `scripts/far-cloud-p1/`, over SSH to the production host (README §1 pattern):
`export_prompts.sql`, `export_forecasts.sql`, `export_summary.sql`, and the new
`bl_export_ratings.sql` (A2). CSVs are git-ignored; keep a durable copy.

### A1. The shape (fixed before `bl_size.py` is first run)

The 31 Oct values and P1's canvas analyses were already seen; the numbers below were drawn from that one
case and are labelled as such.

**Unit.** `premise.build_slots`: the latest eligible run per (location, date, event) before the event,
batch engine, prompted, sky locations, T+0 or T+1. The script prints which run it chose for 31 Oct (it
may be later than 137844).

**Broken-low (BL)**, on the stored forecast:
- solar cone low **20–39** (the band with no current rule; 15–19 and 40–45 are reported as sensitivity
  bands, never in a decision);
- solar cone mid **< 10** and high **< 10**, and antisolar mid **< 10** and high **< 10** (no canvas on
  either side, so no IDEAL or rate-4 rule can apply);
- far 226 km low **< 30** (a clear slot beyond the cone);
- observer low **< 60**;
- no `[BUILDING]` trend (a building deck is the gaps closing);
- prompted and rated.

**ERA5-consistent BL** (diagnostic, not a gate): ERA5 horizon low 10–50, mid < 20 and high < 20. The
bearing spread (`horizon_low_max − horizon_low_min`) is reported as "heterogeneous" (≥ 20), not as
gappiness: the cone mean cannot see which bearing is solid, which is a stated limit.

**Control (CL)**: the same filters except solar cone low **< 10** and far low < 30: truly clear.

### A2. Ratings: whose, and how

`export_outcome_ratings.sql` deliberately omits `user_id`, and several readers can rate one slot
(`canvas.load_ratings` returns a list). Phase A adds `scripts/far-cloud-p1/bl_export_ratings.sql`: the
same columns plus a non-personal `by_admin` flag (`app_user.role = 'ADMIN'`). Rules:
- rows with an empty rating (`seen = NOT_SEEN`, "didn't see it") are excluded from every n;
- the decision uses `by_admin` ratings (the owner's account); all readers are reported beside them;
- several ratings on one slot are averaged within the slot;
- "forecast" means `outcome_rating.forecast_rating` (the star the reader saw); the shape uses
  `sky_rating`.

**The selection problem.** People notice broken low cloud mainly when it lights up, so unprotocolled
ratings over-sample good BL evenings. Historic ratings are therefore **descriptive only**. Evidence that
can decide comes from a **prospective protocol** (A4).

### A3. What `bl_size.py` reports

1. **Frequency**, in distinct (date, event) evenings and per location, by month and event: BL, the
   ERA5-consistent share, CL.
2. **E1 rate**: the share of BL slots with sky_rating ≤ 2.
3. **E2 rate**: the share of BL slot summaries matching a fixed phrase list ("no canvas", "nothing to
   catch", "no cloud to catch", "no mid or high cloud", "clear sky", case-insensitive), reported with 20
   random examples for the owner to check the matcher.
4. **Historic ratings, descriptive**: BL vs CL outcome stars **at matched forecast stars** (stratified by
   forecast_rating 1/2/3), with n in evenings; never a pass or fail.
5. Splits by observer low (0–5 vs 5–60) and by sub-band (15–19, 20–29, 30–39, 40–45).
6. The 31 Oct row on its own (excluded from every count, as are the `far-cloud-lid-plan.md` §1 cases).

### A4. The prospective protocol (owner)

The owner rates **every sunrise and sunset they watch at a protocol location, whatever happened, including
"nothing"**, in "How was it?". The protocol locations are the owner's home places, Copt Hill (176853),
Penshaw Monument (176875) and Angel of the North (176835), passed as `bl_size.py --locations`. The class of
each rated slot is assigned afterwards from the stored forecast (`bl_size.py` reads the forecast export, never
a list), so most rated evenings fall in no class and are simply not counted. `bl_size.py --protocol-start
<date>` reads only ratings on or after that date as protocol evidence.

**Amendment (2026-11-20): the daily list is optional.** The owner does not run `scripts/far-cloud-p1/bl_today.sql`,
and the analysis never needed it: it was a reminder of which evenings mattered. Blindness holds without it (the
owner never sees the class). The guard against selection is the habit above: rating every watched event, not
only the memorable ones.

### A5. Gate to Phase B

**Proceed only if:**
1. **An error worth fixing**: E1 ≥ **10%** of BL slots, or E2 ≥ **30%** of BL slots. (E2 alone justifies
   only a words-and-fiery change, which Phase B still tests.)
2. **It happens**: BL occurs on ≥ **3%** of distinct evenings at the owner's usual locations.
3. **Outcome evidence** (protocol ratings only): ≥ **8** distinct BL evenings and ≥ **8** CL evenings, and
   at matched forecast stars the BL − CL difference in mean outcome stars has a block-bootstrap lower bound
   **> 0** (`stats.py`, 5-day blocks, by evening).

If 3 has too few evenings, the result is **"waiting for protocol ratings"**, not a fail. If 1 or 2 fails,
stop and record it here.

---

## 3. Phase B: replay a candidate rule (spends money; gated)

### B1. The candidate rule, R-BL

Written as a replacement patch for `replay_set.py build-r1` (fenced ```` ```old 1 ```` / ```` ```new 1 ````
blocks). `old 1` is the `< 20%` line copied **exactly from the dumped `system-inland.txt`**, never from
this plan; `new 1` is that line, a newline, then:

> "- BROKEN LOW CLOUD: when solar horizon low cloud is 20-39%, the far beyond-horizon low cloud is below
> 30%, and there is no mid or high cloud on either side, the low deck is broken with a clear path for the
> low sun. Its undersides nearer the observer can light orange or red from sunset to about 20 minutes
> after (from about 20 minutes before sunrise until sunrise). Treat it as a modest canvas, not a clear sky:
> fiery_sky 35-55, and do not describe the sky as having no canvas. This credit alone never takes the
> rating above 3, and it never lowers a rating another rule gives. Do not add mist credit for the same
> broken low cloud. [BUILDING] and approach penalties, caps and the inversion rules apply after it,
> unchanged."

The numbers (20–39, < 30, 35–55, 3) come from one case and P1's geometry. The owner may change them after
reading Phase A and **before** B runs, and the change is recorded here; never after B.

### B2. Tooling and replay set

New `scripts/far-cloud-p1/bl_replay.py select|report`, on `canopyreplay.py`'s pattern (imports
`premise` and `replay_set`, P1 files unedited). `select` filters with `replay_set.is_coastal_prompt` and
excludes 31 Oct and the P1 training cases. Groups (fixed seed; caps in brackets):
- **BL** (150), the A1 shape;
- **Interaction**, each up to 40: BL plus antisolar mid/high 20–60 (the IDEAL collision); BL plus
  `[BUILDING]` or upwind ≥ 60; solar low 20–39 with solar mid/high 10–20; solar low 40–60 with canvas;
  THIN STRIP or FAR CORRIDOR pairs; opposite-orientation slots; inversion-flagged slots;
- **Near-BL**, 15–19 and 40–45 with no canvas (60);
- **General control**, random outside all of the above (150);
- **Coastal sample** (60), so R-BL is tested where Phase C would ship it (the coastal prompt is the
  inland one plus a suffix);
- **Pinned**: `p0-replay/low-hauxley-1004.coastal.txt` (exactly 4) and
  `prompt-regression/steel-rigg-2026-10-24-sunrise.txt` copied as `.inland.txt` (exactly 4). The other
  regression fixtures are `AtmosphericData` JSON the harness cannot replay; they are proven only in Phase C.

**Arms**, each run with `-Pprompt-regression -Dtest=PromptReplayHarnessTest#replayStoredPrompts`,
`REPLAY_N=3`, `REPLAY_BATCH=true`, `runs.csv` copied away after each (it is overwritten):
1. **A/A**: production prompt, SONNET_55, run twice (noise baseline).
2. **R-BL**: `REPLAY_SYSTEM_PROMPT_FILE` = the patched inland prompt, SONNET_55.
3. **Haiku**: production and R-BL on HAIKU (Far-Term's model), on a T+2 sample of BL and general control
   (60 each).

`REPLAY_MODELS` is always set explicitly (the default is three models and `REPLAY_N` defaults to 20).
The harness prints the request count and cost and refuses to submit until `REPLAY_CONFIRM_COST` matches;
**the owner approves each printed figure**.

### B3. Pass criteria for Phase C (fixed before B runs)

1. **Compliance** (not evidence the rule is right): BL median fiery under R-BL in 35–55; no BL summary
   under R-BL matches the A3 "no canvas" phrases.
2. **No harm**: no BL or interaction prompt's median rating or fiery under R-BL is below production's.
3. **No leak**: in the general control and the coastal sample, the R-BL vs production change rate in
   median rating, minus the A/A change rate, has an upper bound ≤ **2 pp**; median |Δ fiery| minus A/A's
   ≤ **3**. Near-BL: the same with ≤ **5 pp**.
4. **Pinned unchanged**: Low Hauxley and Steel Rigg exactly 4 in every run.
5. **Haiku**: criteria 2–4 hold on the Haiku arm.
6. **Outcomes** (descriptive): for protocol-rated BL slots, whether R-BL's rating sits closer to the
   outcome than production's.

---

## 4. Phase C: the production change (only if A and B pass)

- Sonnet adds R-BL's exact replayed text to `SYSTEM_PROMPT` in `PromptBuilder.java` (the coastal prompt
  inherits it: `CoastalPromptBuilder.getSystemPrompt()` is the inland prompt plus a suffix), with unit tests
  that the text is present in both, and a `changelog.d/` entry.
- `PromptGoldenMasterTest` pins the full prompt byte for byte: regenerate with
  `-Dprompt.golden.regenerate=true` and review the diff. Re-run `SystemPromptCacheabilityTest` and
  `SystemPromptTokenCountTest`. `prompt_hash` (V182) changes after deploy; note it.
- The owner runs `./mvnw test -Pprompt-regression -Dtest=SkyRatingRegressionTest -Dsky.runs=8` and the
  Sky-Rating Eval across the scheduled models. ⚠️ **Never edited by a session:**
  `eval/SkyRatingRegressionTest.java`, `SkyRatingEvalFixtures.java` (the bands) and
  `eval/RegressionBoundsRegistryTest.java`. Any failure stops the change; the owner decides.
- `docs/engineering/reference/architecture-decisions.md` (directional cloud bullet) records the rule.
- After deploy: the protocol continues; re-check in summer (fair-weather cumulus is a different regime) and
  report sunrise separately. Shipping sunset-only until sunrise evidence exists is an owner option.

---

## 5. Build (per CLAUDE.md: Sonnet writes, the planner reviews)

- **Phase A PR**: `bl_size.py`, `bl_export_ratings.sql`, `bl_today.sql`, `tests/test_bl_size.py` (no
  network), README section, changelog. Imports `premise`, `canvas`, `stats`; P1 files are not edited.
  Gate: `python3 -m unittest discover -s tests` in `scripts/far-cloud-p1`.
- **Phase B PR**: `bl_replay.py` (`select`, `report` with fiery and A/A logic), `bl_system_prompt_patch.md`,
  tests, README runbook (the dump: `PromptReplayHarnessTest#dumpSystemPrompts` with
  `REPLAY_DUMP_SYSTEM_PROMPTS_DIR`; `build-r1` must exit 0 on it). No production code.
- **Phase C PR**: production code per §4, with the backend build ladder.

---

## 6. Limits

- **Outcome ratings are few, one person's, and only the protocol ones can decide.** Replay measures model
  behaviour, not truth.
- **ERA5 is a ~31 km reanalysis**; it is the reason for the hypothesis, not proof of it.
- **The cone mean hides which bearing is solid**, and grazing angles make 30–39% look more closed than
  the number suggests; the sub-band split in A3 shows whether 30–39 behaves like 20–29.
- **Season**: the replay prompts cover mid-October to mid-November only.

---

## 7. Out of scope

- The missed high veil (`model-disagreement-sizing-plan.md`).
- **Antisolar broken low cloud**, front-lit while the sun is still up. The prompt calls antisolar low cloud
  irrelevant to blocking; whether it deserves canvas credit is a separate question.
- Low cloud above 45% (existing blocker rules and the strip/blanket logic).
- Triage, the cloud-approach rules and the regression-pinned rate-4 rule.

---

## 8. Review record (2026-11-16)

Two read-only reviewers before any build. What changed:
- **Purpose**: the trigger was 3★ and a 3★-capped rule would not change its stars, so the plan now names
  the errors it must find (E1 stars, E2 words) and stops at A if neither is common.
- **Physics**: the 113 km deck is lit from above at sunset; the cone is a proxy for gaps, the canvas is
  nearer; the far-point clear slot and the observer-low split were added; the window is sunset to ~20 min
  after.
- **Rule conflicts**: no canvas on either side (the IDEAL collision); the band moved to 20–39, which has no
  current rule (15–19 overlapped "< 20% strong light", 40–45 overlapped the penalty band); never lowers
  another rule's rating; precedence and mist double-credit stated; `[BUILDING]` excluded.
- **Evidence**: ratings cannot be tied to the owner without a new export (`by_admin`); historic ratings are
  descriptive (selected by outcome); a prospective protocol with controls decides; n counted in evenings;
  matched forecast stars; a bootstrap bound.
- **Replay**: a new `bl_replay.py` (the existing selector and report cannot do it); patch text copied from
  the dumped prompt; an A/A arm for noise; interaction, near-BL and coastal groups; a Haiku arm; the two
  replayable pinned prompts named; explicit `REPLAY_MODELS` and `REPLAY_N`.
- **Phase C gates**: the correct regression command, the never-edit files, the golden master and the cache
  and token tests.
- **Trigger**: the earlier "altocumulus" description is acknowledged; low cloud is a hypothesis to test.
