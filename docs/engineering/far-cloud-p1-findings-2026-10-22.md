# Far-cloud P1: findings as of 2026-10-22, for peer review

**Purpose.** A self-contained statement of what the far-cloud P1 investigation found, how each
finding was obtained, and what each one does and does not support, written so a reviewer who has
not read the conversation can check every claim against a named file, query or PR. The working plan
is `docs/engineering/far-cloud-p1-plan.md`; its §13–§18 hold the per-step detail and the section
numbers below refer to it. Nothing in production scoring has changed as a result of this work.

**The question.** On 2026-10-13 the app rated the Angel of the North sunset 4★ (a "go") and the
owner, who went, saw a 2★ sky. The owner's requirement (plan §0): a confident go that fails — a
wasted trip — is the error that loses a user; a missed good sky costs much less. Everything below
is in service of "why was the Angel a 4★, and what would stop it happening again".

**The answer in one paragraph.** The Angel's 4★ was a weak local canvas: a clear sky overhead with
cirrus only on the solar horizon, so the only thing the sunset could light was a thin strip 113 km
away. Three candidate fixes were tested and rejected — a fiery-sky cap keyed on "no canvas" (a
blanket: it withdrew 27% of all gos), a switch from Sonnet 5.5 to Sonnet 4.6 (it withdraws a quarter
of gos that ERA5 says were fine, and never saw the far cloud either), and a "far high-cloud lid"
rule (the same lid sat behind a 4★ sky two evenings later). The one rule that fits the facts, "clear
overhead with horizon cirrus only is a weak canvas", has exactly one rated case, and ERA5 contradicts the forecast's own "clear overhead" input in
most of the gos that match it (in nine of ten of Sonnet 5.5's), so it cannot ship yet. A larger,
opposite error class was found on the way: the forecast over-predicts low cloud over the observer
on 13% of slots, and about half of those slots had a real mid/high canopy overhead — missed
opportunities, not wasted trips. No code or prompt change is justified by this evidence today; the
plan's own decision rules (§6) say so, and the owner has chosen to keep Sonnet 5.5 (§9 item 1).

---

## 1. Data, tools and their limits

| Source | What it is | Where | Limits a reviewer should hold in mind |
|---|---|---|---|
| `p1_forecasts.csv` | Every SUNRISE/SUNSET `forecast_evaluation` row since 2026-02-01 (every run, not only the latest) with every cloud column, the location, and `cloud_verification` by LEFT JOIN. Exported 2026-10-14 and re-exported 2026-10-21 (106,118 rows, dates to 2026-10-24). | Owner's Mac, `scripts/far-cloud-p1/p1_forecasts.csv` (git-ignored). SQL: `scripts/far-cloud-p1/export_forecasts.sql` | A snapshot. "Latest run per slot" is chosen by the tools, not the export. |
| `p1_prompts.csv` | `forecast_evaluation_prompt.user_message` for every stored prompt (30-day retention), 8,365 prompts dated 2026-10-04..14, Haiku 1,685 / Sonnet 5.5 6,680. | Same folder. SQL: `export_prompts.sql` | Only ten days of prompts exist; the replay set is fixed from them. |
| ERA5 (`cloud_verification`) | Reanalysis cloud at five points per evaluation (observer, 3-point cone at 113 km, far point at 226 km), written by the hourly backfill job ~6 days behind. Reached 2026-10-15 on 2026-10-21. | Production table; read through the export's `cv_*` columns | **A reanalysis, not an observation.** It shares physics with the forecast model and can share its biases. Cloud-cover % cannot distinguish thin cirrus from an opaque deck. |
| Replay harness | `PromptReplayHarnessTest` re-sends stored prompts to a model (direct or Batch API) with the production system prompt or a patched one, through the production parser. | `backend/src/test/java/.../PromptReplayHarnessTest.java`, `PromptReplayBatch.java` (#1169) | Measures model behaviour on a prompt, not sky truth. |
| Open-Meteo transects | `case.py`: ERA5 plus four forecast models' day-0 layers every 25 km from 0 to 400 km along the sun's azimuth at three hours around sunset. | `scripts/far-cloud-p1/case.py`; reports `~/goldenhour-p1/case_2026-10-13.md`, `case_2026-10-15.md` | Models only. Previous Runs serves no per-layer previous-day cloud, so "as issued the day before" cannot be reconstructed (plan §11). |
| Meteosat frames | Zoom Earth / EUMETSAT dusk composites, 2026-10-13 and 2026-10-15 at 16:50 and 17:10 UTC, viewed by the owner. | `~/goldenhour-p1/meteosat_2026-10-1{3,5}_1650Z_zoomearth.webp` | Read by eye; a composite, not a measurement. The plan's only non-model evidence. |
| Owner ratings | `outcome_rating` ("How was it?", v2.28.0): three rows, all 2026-10-15 SUNSET: Angel 4★ (forecast 3), Copt Hill 4★ (forecast 3), Penshaw Monument 4★ (forecast 4, entered by SQL). | Production table | Three ratings, one evening. The 13 October Angel sky (2★) is the owner's report, not a row. |

Tools: `premise.py` (Part B), `replay_set.py select/split/compare` (Part D), `case.py` (Part A),
`canvas.py` (§17), `lowdeck.py` (§18), `run_option_a.sh` (the replay orchestration), all under
`scripts/far-cloud-p1/` with tests under `tests/` (313 pass as of #1288). Runbook:
`scripts/far-cloud-p1/README.md`.

---

## 2. Findings

Each finding states the claim, the evidence, the numbers, and the weakest point.

### F1. The Angel's 4★ was produced from a prompt that saw a clear sky overhead and a cirrus canvas on the horizon, and nothing beyond 226 km except low cloud

- **Evidence.** The stored prompt for evaluation 101110 (2026-10-13 SUNSET, run 14:22 UTC, Sonnet 5.5): overhead `Low 0%, Mid 0%, High 0%`; solar horizon at 113 km `Low 7%, Mid 24%, High 91%`; antisolar 0/0/0; `Beyond horizon (226km, solar azimuth): Low 0%`. Sonnet 5.5 rated it 4★ in 20 of 20 direct replays (P0); Sonnet 4.6 rated the same prompt 3★ in 20 of 20.
- **Source.** `p1_prompts.csv` row 101110; plan §14.
- **Weak point.** None about the prompt itself. What it implies about the sky is a model's view; see F3.

### F2. Replaying stored prompts (Part D, $6.28) rejected the drafted canvas rule and did not justify a model switch

- **Design.** 1,316 stored prompts (658 gos rated ≥ 4, 658 rated 3) from 2026-10-04..14, replayed once each in Batch mode on: Sonnet 4.6 (production prompt, on the prompts Sonnet 5.5 had rated), Sonnet 5.5 (production prompt, on the prompts Haiku had rated), Sonnet 5.5 with the R1 patch (a fiery_sky cap of 55 when no canvas is present, on the gos only), plus a 150-prompt same-model control and the two training prompts at 20 runs each. A stored production rating stands in as the rating model's own arm. Plan §13.
- **Control (noise floor).** Replaying Sonnet 5.5 on prompts it had itself rated moved 9 of 150: 3 of 69 gos fell below 4★ (4.3%), 2 of 81 three-stars rose to 4★+ (2.5%).
- **R1.** Angel 3★ in 20 of 20 under R1; Low Hauxley (the pinned good case) 4★ in 20 of 20. But R1 took 179 of 658 gos below 4★ (27.2%, cap 15%), 164 of the 513 Sonnet 5.5 gos (32%), and 113 of 246 Sonnet 5.5 sunset gos (46%). Only 10 of the 513 gos had the Angel's shape; R1 demoted 8 of them and 156 others. **Rejected as a blanket** (D-1 part 2 fails; parts 3 and 4 fail in F6).
- **Sonnet 4.6 vs 5.5.** On Sonnet 5.5's 513 gos, 4.6 gave 3★ or below to 127 (24.8%); on its 599 three-stars, 4.6 gave 4★+ to 68 (11.4%). Net, 4.6 awards 4★+ to 454 of 1,112 (40.8%) against 513 (46.1%): about 5 points, under the 8 the plan set (D-2.1 not met). D-2.2 (the Angel itself) is met. The two demotion sets overlap little: of 228 gos demoted by either 4.6 or R1, 63 by both.
- **Weak point.** A stored rating is selected on its value (gos are ≥ 4 by construction), so replays regress toward the mean; the control measures that floor (4.3% / 2.5%) and the effects above stand well clear of it. One run per prompt; P0's 20-of-20 consistency on one prompt is the basis for that. Thinking tokens were billed but the stated cost excludes primer batches.

### F3. Along the Angel's sun line, ERA5 and all four forecast models show a continuous high-cloud deck from about 225 km to 400 km behind the 113 km canvas, and the prompt never carried that reading

- **Evidence.** At 17:00 UTC (sun 1.3°, azimuth 255°): ERA5 high cloud 96–99% from 125 km and 99–100% from 225 to 400 km; best_match, ukmo_seamless, ecmwf_ifs025 and icon_seamless (day-0) all 98–100% high from 225 km. The ray from the Angel at 1.3° reaches the 8 km band at ~225 km. The forecast's far point (226 km) sampled LOW cloud only until V175 (2026-10-13); far mid/high and pressure levels are recorded since but read by no prompt (`FarCloudCaptureInvarianceTest`). Meteosat 16:50 UTC: north-east England clear, streaky cirrus over the Lakes at 50–150 km, a broad continuous sheet over the Isle of Man, the north Irish Sea and Northern Ireland from ~200 km. Plan §14.
- **What it supported at the time.** The far-lid hypothesis (H1/H2): a horizon canvas with a shield behind it that the setting sun could not get under.
- **Weak point, which F5 turned into a refutation.** The same deck was present on a good evening.

### F4. On the verified Sonnet 5.5 era, Sonnet 5.5's gos rarely stand on an ERA5-blocked horizon, and Sonnet 4.6's withdrawals are not where ERA5 says the faults are

- **Evidence (Part B re-run, 2026-10-21, 2,251 location-gos at T+0/T+1, 2,120 with ERA5).** P-low (ERA5 cone low ≥ 60) among gos: Haiku sunrise 79/904 (8.7%), sunset 39/714 (5.5%); Sonnet 5.5 sunrise 9/286 (3.1%), sunset 2/220 (0.9%). Of Sonnet 5.5's 513 replayed gos, 490 pass all three ERA5 premises (horizon clear, canvas present, far low clear); Sonnet 4.6 withdrew 118 of those 490 (24.1%, its overall rate) and 7 of the 9 no-canvas gos. R1 demoted 144 of 613 all-pass gos (23.5%) and 9 of 9 no-canvas gos. Plan §15.
- **What it supports.** Keeping Sonnet 5.5 (owner decision §9 item 1) rather than switching: by ERA5, 4.6's extra withdrawals read as missed opportunities, not avoided wasted trips.
- **Weak point.** The three premises see only the horizon gap, the local canvas and the far LOW corridor; the Angel passes all three. This says 4.6's withdrawals miss the Angel's fault, not that 5.5's gos are right. ERA5 is a reanalysis.

### F5. The far-lid hypothesis is refuted: the same far deck sat behind a 4★ sky on 2026-10-15, and what differed was the cloud overhead

- **Evidence.** 15 October transect at 17:00 UTC (sun 0.7°): ERA5 high 91–100% at every point 0–400 km, including the identical 100% deck beyond 250 km; ERA5 mid 86–95% from 0 to 125 km, thinning to 42% at 175 km, 17% at 250, ≤ 14% beyond. Owner ratings that evening: Angel 4★, Copt Hill 4★, Penshaw right at 4★; the owner's photo shows a mid-level canopy overhead lit from beneath. Meteosat 16:50 and 17:10 UTC: a continuous mottled sheet over the Irish Sea, Northern Ireland, the Lakes and Newcastle, denser than the 13th's streaky cirrus. ERA5 observer cloud: 13 Oct low/mid/high 2/16/8; 15 Oct 2/100/84. Plan §16.
- **What it supports.** The far high-cloud reading, by cover %, is the same on a 2★ and a 4★ evening and so is not a detector. The difference is the local canvas: a mid canopy overhead (lit) versus horizon cirrus only (not).
- **Weak point.** Two evenings. Cover % cannot distinguish thin from opaque cloud, and the far point's pressure-level cloud read under 25% on both evenings, so the capture cannot tell them apart either. The lid is refuted as the Angel's *cause* on these two evenings; it is not shown to be irrelevant everywhere.

### F6. "Canvas with a lid behind it" is common (68% of canvas gos in the first week of capture) and bunched on cirrus days, so a cap keyed on it would be another blanket

- **Evidence.** 3,029 slots dated 2026-10-14..24 carry the far high reading; 276 gos; 187 with a canvas; 128 of those with far high ≥ 60, 100 of the 128 on 15 October alone. ERA5's far high agrees with the forecast's on 469 of 578 verified slots, but those are the two cirrus days, where ERA5 saw a lid on 553 of 578. Plan §16.
- **What it supports.** Dropping the "far high ≥ 60" clause from any rule.

### F7. The weak-canvas shape (clear overhead, horizon cirrus only) is 6.1% of gos, twice as common under Sonnet 5.5 as under Haiku, and the forecast's "clear overhead" is contradicted by ERA5 in most of them

- **Evidence (`canvas.py`, 2,239 testable gos, dates 2026-08-29..10-22).** 137 match (Haiku 75 of 1,615, 4.6%; Sonnet 5.5 62 of 624, 9.9%), on 11 dates (28 Sep 32, 15 Oct 28, 13 Oct 18, 14 Oct 16). ERA5 confirms observer mid and high < 20 for 62 of 137 (Haiku 55 of 75, Sonnet 5.5 7 of 62), cone high ≥ 60 for 70, cone low < 20 for 130; all three for 10 of 137. No outcome rating is joined (none of the three ratings is a weak-canvas slot). Plan §17.
- **What it supports.** The Angel's shape is a sample of one with a rating. A rule keyed on the forecast's own overhead figures would fire mostly on skies ERA5 says had a canopy — the 15 October kind. Only ratings can say what those 137 skies were.
- **Weak point.** Where forecast and ERA5 disagree about the overhead, either could be wrong; both are models.

### F8. The forecast over-predicts low cloud over the observer on 13% of verified slots, and about half of those had a real mid/high canopy overhead; 89% of them were stood down or rated ≤ 3

- **Evidence (`lowdeck.py`, 29,305 verified T+0/T+1 sky slots, 2026-03-07..10-15).** Phantom deck (forecast observer low ≥ 60, ERA5 < 20): 3,811 slots, 13.0% [12.6, 13.4]; the reverse (missed deck) 2.2%. Cone: 14.1% / 0.4%. By event: sunrise 7.6%, sunset 18.8%. By model: Haiku 13.4%, Sonnet 5.5 9.7% — also a date split, since Sonnet 5.5 is all October. Of the 3,811: triaged on low cloud 2,223 (58.3%), rated 1–2 837, rated 3 346, rated 4–5 283 (7.4%); triaged or ≤ 3 together 89.4%, against 82.2% for both-clear slots. An ERA5 canopy overhead is present on 1,913 of the 3,811 (50.2%, against 36.7% of both-clear slots); of those 1,913, 1,087 triaged on low cloud, 579 rated ≤ 3, 1,666 either, 187 rated 4–5. The 15 October Angel/Copt Hill/Penshaw case: forecast overhead low 99/100/100 and horizon low 32/43/0 against ERA5 2/2/2 and 4/3/3; the two with the high horizon figure were rated 3★ (owner 4★), the one with 0% was rated 4★ (right). Plan §18.
- **What it supports.** The phantom low deck is the largest error class measured, and it is a missed-opportunity class, outside the owner's stated priority but far bigger in count.
- **Weak points.** Weather triage fires on the CONE reading above 80%, so cone-phantom triage is mechanical and only the observer figures are informative. Wilson intervals treat slots as independent; many share one weather day, so the true uncertainty is wider. ERA5 is a reanalysis. No fix is proposed because the forecast cannot see ERA5 at forecast time; a lever needs a forecast-time signal (a second low-cloud source, or disagreement between the models Open-Meteo blends).

### F9. Part B's three premises cannot see the Angel class, by construction

- **Evidence.** On the 2026-10-21 re-run the Angel sanity row reads P-low passes, P-far passes, P-canvas passes. The primary tests stay inconclusive (R1: 0 of 67 flagged gos P-low, fewer than 10 events) or fail (R2, R3); R1's cost side is 59 of 67 Angel-shape gos with an ERA5 canvas (88.1%, ceiling 25%). Plan §15.
- **What it supports.** Part B is a check on horizon-blocked gos, which Sonnet 5.5 rarely produces (F4); it is not a test of the Angel's fault.

### F10. The production cycle records, since 2026-10-13, every far-point reading this investigation needed and could not have before

- **Evidence.** V175 (far mid/high and dark-hour low/mid/high at 226 km, on `forecast_evaluation` and `cloud_verification`) and V176 (far 850–300 hPa cloud). No prompt reads them; `FarCloudCaptureInvarianceTest` pins that.
- **What it supports.** The prospective H1/H2/H5 test (plan §12 step 10) runs on stored values from here. Given F5, those columns are no longer the weak-canvas rule's input.

---

## 3. Decisions taken, and not taken

| Decision | Status | Basis |
|---|---|---|
| Fiery-sky cap on "no canvas" (R1, cap 55) | **Rejected** | F2 (blanket: 27% of gos, 46% of Sonnet 5.5 sunset gos), F4 (demotes ERA5-clean gos) |
| Switch Batch Near-Term to Sonnet 4.6 | **Not taken** (owner, 2026-10-15; §9 item 1) | D-2.2 met on one case; D-2.1 not met; F4 shows the withdrawals land on ERA5-clean gos |
| Far-lid rule (canvas + far high ≥ 60) | **Refuted** | F5, F6 |
| Weak-canvas rule (clear overhead, horizon cirrus only) | **Open, not shippable** (§9 item 7) | F7: one rated case; ERA5 confirms the forecast's "clear overhead" in 62 of 137 matches (7 of Sonnet 5.5's 62) and the full shape in 10 of 137 |
| Any change for the phantom low deck | **Not proposed** | F8: no forecast-time signal identified yet |
| D-1 / D-2 thresholds | **Fixed before any result was read** (§6, 2026-10-14) | Pre-registration |

Net: production scoring is unchanged. The owner's app rates the same sky the same way today as on 13 October.

---

## 4. What a reviewer should attack

1. **ERA5 as arbiter.** F4, F6, F7, F8 and F9 all rest on ERA5 cloud. If ERA5 shares the forecast model's low-cloud bias over north-east England, the phantom-deck rate (F8) is overstated and the "4.6 withdraws clean gos" reading (F4) is weakened. The only non-model evidence is two Meteosat frames per evening and three owner ratings, all from 15 October.
2. **Selection in the replay (F2).** Stored ratings are the selection criterion for the arms; the control (4.3% / 2.5%) is the only correction. Is a same-model control of 150 enough, and does "median of one attempt" overstate stability beyond the one prompt P0 measured at 20 runs?
3. **Date clustering.** Every rate in F6–F8 is over slots that share weather days (15 October alone is 100 of 128 in F6 and 28 of 137 in F7). The Wilson intervals in §18 are too narrow by construction; no block-bootstrapped interval was computed for those two tools when this was written (Part B's `premise.py` does block-bootstrap); `canvas.py` and `lowdeck.py` print one beside each Wilson interval since plan §19.
4. **The 226 km single point.** F3 reads a continuous lid from a transect; production stores one point. F5 shows the one point cannot separate the two evenings. Is there any stored figure that could (the dark-hour far readings, the pressure levels, which read < 25% on both evenings)?
5. **Two evenings.** F5's refutation is n = 2 with opposite outcomes. It rules the far-high cover reading out as a *sufficient* detector; it does not measure how often a lid matters when the overhead is clear.
6. **Model-era confound.** Every Sonnet 5.5 figure is October 2026; every Haiku figure is mostly earlier. "Sonnet 5.5 produces the weak-canvas shape twice as often" (F7) and "Sonnet 5.5's phantom rate is lower" (F8) may both be weather, not model.
7. **Triage mechanics.** §18's cone figures are mechanical because triage fires at cone low > 80; the observer figures are the informative ones. Check that the tool's "triaged on low cloud" label (`triage_reason` HIGH_CLOUD with a message naming low cloud) is the right bucket.
8. **The owner's scale.** 1–2★ stay home, 3★ only if close, 4★ game on, 5★ go. "A star short" (F8's 15 October case) is a one-band error at the go threshold; the Angel on the 13th was a two-band error across it. The plan weighs these differently on purpose (§0); a reviewer may disagree with the weighting.

---

## 5. Open candidates, in the order the data supports them

1. **Weak-canvas rule** (§9 item 7): wait for owner ratings on weak-canvas gos (`canvas.py --ratings` with `export_outcome_ratings.sql` once ratings exist beyond 15 October). Cannot ship on n = 1.
2. **Phantom low deck under a forecast canopy** (F8): size, on the verified history, how often a slot with forecast observer low ≥ 60 AND forecast mid or high ≥ 60 turns out lit (ERA5 low < 20 with canopy present), and what a narrow triage change ("do not stand down on low cloud alone when a canopy is forecast") would send to Claude instead. Costs Claude calls; needs the replay harness and owner ratings before shipping. Sized offline in plan §19 (`canopydeck.py`); not replayed.
3. **A forecast-time signal for the phantom deck**: per-model low cloud (ukmo_seamless vs ecmwf vs icon) at the observer point for phantom-deck slots, to see whether model disagreement predicts the phantom. Open-Meteo's free daily limit (~600 weighted calls) makes this a multi-day sampling job.
4. **Part B again** when ERA5 verification passes the end of October, to double the Sonnet 5.5 sample.
5. **Part C** (how common far decks are) is deprioritised by F5.

---

## 6. Pointers

- Plan: `docs/engineering/far-cloud-p1-plan.md` §0 (the requirement), §6 (decision rules), §9 (owner decisions), §12 (next steps), §13 (Part D), §14 (Part A, Angel 13 Oct), §15 (Part B re-run), §16 (lid sizing, 15 Oct transect and Meteosat), §17 (weak canvas), §18 (phantom low deck).
- PRs, all merged: #1164 (plan and tools), #1166 (probe fixes), #1169 (batch replay mode), #1171 (thresholds, §12), #1172/#1173 (trimmed replay with stored arms and a control), #1175 (working-file locations), #1176 (replay orchestration script), #1178 (§13), #1179 (keep Sonnet 5.5), #1261 (§14), #1263 (§15), #1264 (Meteosat), #1265 (§16), #1286 (15 Oct transect), #1287 (lid refuted, weak-canvas rule), #1288 (`canvas.py`, §17), #1291 (`lowdeck.py`, §18).
- Working files on the owner's Mac, not committed: `~/goldenhour-p1/` (replay set and arms, Part D report `part_d.md` and `part_d2.md`, Part B reports `part_b2.md`, transects `case_2026-10-13.md` and `case_2026-10-15.md`, Meteosat frames, `README.md` describing each).
- Production queries used (read-only, `docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour`): verification reach — `SELECT max(fe.target_date), count(*) FILTER (WHERE fe.target_date >= '2026-10-06') FROM cloud_verification cv JOIN forecast_evaluation fe ON fe.id = cv.forecast_evaluation_id;`; ratings — `SELECT o.event_date, o.event_type, l.name, o.forecast_rating, o.rating, fe.solar_high_cloud, fe.far_solar_high_cloud FROM outcome_rating o JOIN locations l ON l.id = o.location_id LEFT JOIN forecast_evaluation fe ON fe.id = o.forecast_evaluation_id ORDER BY 1, 2, 3;`.
