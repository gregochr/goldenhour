# Broken low cloud as a canvas: does a gappy low deck earn colour the prompt never credits?

**Status:** draft, 2026-11-16. Nothing built. Phases A and B are read-side (A spends nothing; B spends
Claude money on a replay, gated); Phase C, a prompt change, happens only if A and B pass and goes through
the prompt-regression suite. One PR per phase.
**Trigger:** Angel of the North, 31 Oct 2026 sunset, rated 3★ (fiery 28, golden 62). The summary said
"no mid or high cloud anywhere to catch colour". The owner photographed a band of textured cloud along the
western horizon lit orange from below. ERA5 (`cloud_verification`, verified 2026-11-07) settles what it was:

| 12:38 run | Forecast | ERA5 |
|---|---|---|
| Horizon cone low (113 km) | 19 | 20 (bearings 5–37) |
| Horizon cone mid / high | 0 / 0 | 6 / 23 |
| Overhead low / mid / high | 0 / 0 / 0 | 5 / 0 / 66 |
| Far 226 km low / mid / high | 26 / 0 / 0 | 16 / 0 / 36 |

ERA5 has almost no mid cloud anywhere, so the lit band was **broken low cloud** (horizon ~20%, gappy:
one bearing 5%, another 37%), which the forecast had almost exactly. The high veil overhead (66% in
ERA5) was missed but stayed pale in the photo; that miss belongs to `model-disagreement-sizing-plan.md`,
not here. **The data was right; the reading was not.**

**Builds on:** `far-cloud-lid-plan.md` §2 (geometry of underlighting) and its §9 decisions;
`far-cloud-p1-plan.md` (exports, `premise.py` slot selection, `canvas.py`, the replay harness
`PromptReplayHarnessTest` and `replay_set.py build-r1`). Nothing here overrides them.

---

## 1. What the prompt does today

`PromptBuilder.java` (system prompt):
- ~l.119–127: solar horizon low cloud is graded only as a **blocker**: > 60% blocked, 40–60% penalised,
  < 20% "strong light penetration". Nothing says low cloud can itself take colour.
- ~l.178–179 and ~l.260–263: "low cloud (0–3 km) sits near the horizon and blocks light; mid and high
  catch it"; "fiery_sky requires clouds (mid/high) to catch light. Clear sky = 20–40".
- ~l.196: "Patchy low cloud (20–50%) with breaks — crepuscular rays" exists, but inside the **mist**
  guidance block, as a mist/visibility positive, not as a canvas rule.

So a slot with ~15–45% broken low cloud at the horizon and no mid/high is scored as a **clear sky**
(fiery 20–40). On 31 Oct that gave fiery 28; the photo shows more colour than a clear sky gives.

**Physics, briefly** (`far-cloud-lid-plan.md` §2 (C)). A low deck is underlit only while the sun is below
that deck's own horizon, so broken low cloud colours in a short window around and just after sunset, and
only through gaps sunward of it. A 1–2 km deck near the observer lights for minutes; one near 113 km
lights at about the moment of sunset. It is real but brief and shape-dependent, so a rule should be a
**bounded credit**, never a route to 4–5 on its own.

---

## 2. Phase A: sizing and outcome evidence (no network, no spend)

### A0. Export stored prompts NOW (owner, before 2026-11-30)

Stored prompts are pruned after 30 days. The 31 Oct prompts go about 30 Nov; every day lost shrinks
Phase B's set. Run P1's three exports today, from `scripts/far-cloud-p1/` (README §1):
`export_prompts.sql`, `export_forecasts.sql`, `export_outcome_ratings.sql`. Copy the CSVs somewhere
durable; they hold production data and are git-ignored.

### A1. The shape (pre-registered before any data is read)

Unit: P1's slot, the latest eligible run per (location, date, event) at T+0 or T+1, sky locations only
(`premise.py` selection, reused). **Broken-low (BL)** shape, on the stored forecast:
- solar cone low **15–45** (inclusive);
- solar cone mid **< 10** and solar cone high **< 10** (no forecast canvas, so today's rules see "clear");
- observer low **< 60** (not sitting under the deck);
- not triaged away (a prompted, rated row).

**Confirmed BL** adds ERA5 (`cloud_verification`): horizon low 10–50 **and** horizon bearing spread
(`horizon_low_max − horizon_low_min`) **≥ 20** (gappy, not a uniform thin veil), and horizon mid < 20.
"BL" is what a rule would act on (forecast only); "confirmed BL" checks the forecast was describing a real
broken deck.

### A2. What A reports (`bl_size.py`, new, in `scripts/far-cloud-p1/` beside `canvas.py`)

1. **Frequency**: BL slots as a share of all prompted slots, by event and month; the confirmed share.
2. **Current scoring**: rating and fiery_sky distribution inside BL (expected: fiery 20–40, rating ≤ 3).
3. **Outcome evidence**: owner outcome ratings joined by (location, date, event), with n. For each rated
   BL slot: forecast rating, outcome rating, difference. Same table for a **control**: rated slots with
   cone low < 10 and cone mid/high < 10 (truly clear), so "outcomes beat forecasts" is not just the
   reader rating everything higher.
4. **The 31 Oct row** shown on its own (it is in the training set, §A3).

### A3. Decision to spend on Phase B

**Proceed to B only if all hold:**
1. BL is at least **3%** of prompted slots (otherwise not worth a prompt rule).
2. Confirmed share of BL ≥ **50%** (the forecast shape usually is a real broken deck).
3. Outcome evidence: at least **8** owner-rated BL slots (excluding 31 Oct), and in BL the share rated
   **above** forecast exceeds the control's share by ≥ 20 pp.

**If 3 fails only for n < 8**: record "insufficient outcomes". BL evenings can't be predicted far ahead,
so the owner's standing ask is to rate every sunrise or sunset seen with broken low cloud in "How was
it?". Re-run A when n ≥ 8. **If 1 or 2 fails**: stop and
record it here.

Training set, excluded from the decision and shown as worked examples: 31 Oct 2026 Angel sunset, and the
cases named in `far-cloud-lid-plan.md` §1.

---

## 3. Phase B: replay a candidate rule (spends money; gated)

### B1. The candidate rule, R-BL (text fixed before replay)

Added to the DIRECTIONAL CLOUD rules after the < 20% line, as a patch file `bl_system_prompt_patch.md`
applied with `replay_set.py build-r1 --patch` (no new tooling):

> "BROKEN LOW CLOUD AS CANVAS: when solar horizon low cloud is 15–45% and solar mid and high cloud are
> both below 10%, the low cloud is broken, with gaps for the low sun. Around and just after sunset (just
> before sunrise) its undersides can light orange or red. Treat it as a modest canvas, not as a clear sky:
> fiery_sky 35–55, rating at most 3. It never authorises 4 or 5 on its own. Above 45% the existing
> penalties apply unchanged."

The numbers (15–45, fiery 35–55, cap 3) are this plan's choice and may be changed by the owner **before**
B runs, never after.

### B2. Replay set

From the exported prompts (A0), inland sky prompts at T+0/T+1, in three groups:
- **BL**: every stored prompt in the BL shape (cap 150, random with a fixed seed if more);
- **Near-BL control**: cone low 46–60 or 5–14 with mid/high < 10 (the rule's edges must not leak);
- **General control**: 150 random prompts outside BL (the rule must not move anything else);
- **Pinned**: the Low Hauxley fidelity prompt (`low-hauxley-1004.coastal.txt`, must stay 4) and the
  prompt-regression fixtures' inputs where they are replayable.

Each prompt runs **3 times** on production's model (SONNET_55) under two arms: the production system
prompt and R-BL. Batch mode (`REPLAY_BATCH=true`, half price). The harness prints the request count and
a cost estimate and refuses to submit until `REPLAY_CONFIRM_COST` matches; **the owner approves the
printed figure before any spend**. Thinking tokens and caching are as P1's README describes.

### B3. Pass criteria for Phase C (pre-registered)

1. **BL moves as intended**: median fiery_sky rises into 35–55; no BL run rates 4 or 5 under R-BL.
2. **No leak**: in the general control, ≤ 2% of prompts change median rating and median |Δ fiery| ≤ 3;
   in the near-BL control, ≤ 5% change rating.
3. **Pinned unchanged**: Low Hauxley stays 4 in all runs; every replayable regression input keeps its
   expected band.
4. **Run-to-run noise accounted for**: the production arm's own 3-run spread is reported; a BL shift
   smaller than that spread does not count as movement.
5. **Agreement with outcomes** (descriptive, n small): for rated BL slots, does R-BL's rating sit closer
   to the outcome than production's?

---

## 4. Phase C: the prompt change (only if A and B pass)

- Sonnet edits `PromptBuilder.java` with R-BL's exact text (as replayed), adds unit tests that the text is
  present in the inland and coastal system prompts as intended, and a `changelog.d/` entry.
- The owner runs the prompt-regression suite (`./mvnw test -Pprompt-regression`, real API, costs money).
  ⚠️ **Assertions in prompt-regression tests are never changed by a session.** If any fails, stop: the
  owner decides whether the rule or the expectation is wrong.
- `docs/engineering/reference/pipeline.md` (or the architecture-decisions directional-cloud bullet) records
  the rule and this plan.
- After deploy, watch the shape for a month: BL slots' ratings and any outcome ratings.

---

## 5. Build (per CLAUDE.md: Sonnet writes, the planner reviews)

- **Phase A PR**: `scripts/far-cloud-p1/bl_size.py` and `tests/test_bl_size.py` (P1's `om_fake`-free
  pattern: no network), reusing `premise.py` selection and `canvas.py`'s ratings join; README §-entry;
  changelog. P1's existing modules are imported, not edited, unless a shared helper needs a pure addition
  (say so in the PR). Gates: `python3 -m unittest discover -s tests` in `scripts/far-cloud-p1`.
- **Phase B PR**: `scripts/far-cloud-p1/bl_system_prompt_patch.md`, a `replay_set.py` selector for the
  three groups (or a sibling `bl_replay_set.py` if `replay_set.py` would need more than a small,
  tested addition), README runbook. No production code. Gates as above, plus `build-r1` exits 0 on the
  dumped production prompt.
- **Phase C PR**: production code, per §4, with the backend build ladder.

---

## 6. Limits

- **Outcome ratings are few and one person's.** They are the only ground truth on colour; the n ≥ 8 bar is
  low and stated as such. Replay measures model behaviour, not truth.
- **ERA5 is a ~31 km reanalysis**; "confirmed" means ERA5 agrees, not that the deck was seen.
- **Brief window.** Underlit low cloud colours for minutes; a modest, capped credit reflects that.
- **The shape overlaps mist guidance** (~l.196). R-BL is a canvas rule; the mist rule is left as it is.

---

## 7. Out of scope

- The missed high veil (model disagreement, a separate plan).
- Low cloud above 45% (existing blocker rules and the far-cloud strip/blanket logic).
- Any change to triage, the cloud-approach rules or the regression-pinned rate-4 rule.
