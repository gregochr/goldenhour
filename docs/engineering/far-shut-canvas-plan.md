# Far-shut canvas: a clear horizon under a canvas, with the far corridor shut

**Status:** draft, 2026-11-19. Nothing built in production. Phase A is read-only and free (a sizing query and
two new classes on the daily protocol list); Phase B spends Claude money on a replay, gated on the owner's
approval of a printed cost; Phase C changes the production prompt only if A and B pass, and needs an explicit
owner decision to revisit the ledger rule in §3. One PR per phase.

---

## 1. Trigger

**19 Nov 2026, sunset, Angel of the North, Penshaw Monument and Copt Hill**: all 4★ (fiery 66–68), the
window's **BEST BET**; the owner, watching from beside Copt Hill, saw a flat grey lid and rated it 1★.

| Run 13:33 (T+0) | Angel (176835) | Copt Hill (176853) | Penshaw (176875) |
|---|---|---|---|
| Overhead low / mid / high | 5 / 88 / 0 | 5 / 88 / 0 | 9 / 93 / 0 |
| Cone (113 km) low / mid / high | 28 / 58 / 0 | 8 / 70 / 0 | 8 / 70 / 0 |
| Far (226 km) low / mid | **98** / 5 | **93** / 45 | **93** / 45 |
| Low trend earliest → event | 77 → 28 | 69 → 8 | 69 → 8 |

The summaries read the clearing horizon under a mid canvas ("colour should light up on cue"). The far
reading was not mentioned except at Penshaw, as "timing risk". The overhead mid deck stayed unlit.

**16 Nov** (Angel 4★, owner 1★) is a **different** shape (far low 0; low cloud overhead and just west) and is
not part of this plan; it belongs to the "near-field sunward low cloud" question.

---

## 2. Physics (`far-cloud-lid-plan.md` §2)

- A deck is lit **from below** only through a clear slot sunward. For a deck overhead, in the first 7–12 minutes
  after sunset the ray returns to the deck's base at about 2Rδ: ~220 km at δ = 1° (§2 (C)). The 226 km point
  is that slot.
- Low cloud at 226 km (tops 1–3 km) lies **below** the sun's line at sunset (the line is ~4 km high there), so it
  does not stop golden light before sunset. It stops the **after-sunset underlighting** of a mid deck: the
  fiery phase.
- So the expected effect of a shut far corridor is on **fiery_sky** and the afterglow, less on golden hour.

---

## 3. What the prompt does, and decisions this plan must respect

- The prompt reads the 226 km low reading only when the 113 km horizon is itself cloudy (THIN STRIP, FAR
  CORRIDOR ALSO CLOUDY, `PromptBuilder` ~l.147–168). With a clear 113 km horizon it is ignored.
- **Ledger rule** (`claude-cost-ledger.md` / `architecture-decisions.md`, directional cloud): "Never give the far
  reading escalatory force again." It was measured on blanket calls (113 km low ≥ 50), where the far reading
  over-forecast by ~68 pp when it hardened. **This plan's shape is different** (113 km clear), and §4 shows the
  far reading is reliable there, but any Phase C rule *is* escalatory, so it needs the owner to revisit that
  rule explicitly, with this evidence.
- **Far-cloud P1, F5** refuted far **high** cloud as a sufficient lid detector (two evenings). This plan is about
  far **low** cloud closing the underlighting slot; P1 treated "far low clear" as a premise of a good go.

---

## 4. Evidence so far (production, 2026-11-19, 60 days)

Sonnet 5.5, T+0/1, scored, clear horizon (cone low < 20), canvas (mid or high ≥ 50 at cone or overhead), rated
3★+:

| Forecast far low | Slots | Rated 4★+ | ERA5 also ≥ 80% | ERA5 < 50% | ERA5 mean |
|---|---|---|---|---|---|
| < 50% | 3,311 | 2,437 (74%) | 69 / 2,293 (3%) | 94% | 9 |
| 50–79% | 526 | 364 (69%) | 52 / 380 (14%) | 75% | 27 |
| **≥ 80%** | **698** | **559 (80%)** | **298 / 520 (57%)** | 33% | **67** |

- With a clear horizon, the forecast's "far shut" is **usually real** (57% confirmed ≥ 80 vs a 3% baseline).
- The prompt rates the far-shut shape **higher**, not lower (80% 4★+). ~560 4★+ slots in 60 days (about 1 in 6
  of clear-horizon-with-canvas 4★+), ~300 of them ERA5-confirmed shut.
- **Not shown:** that a shut corridor means less colour. ERA5 sees cloud, not colour. That needs outcomes (§5).

---

## 5. Phase A (read-only, free)

**A1. Sizing.** `docs/engineering/cost-queries/far-shut-canvas-sizing.sql`: the §4 table split by event and
canvas type (mid-led vs high-led), the FS share of all 4★+, and readers' historic outcome ratings by class
(descriptive; selected by whoever chose to rate). Classes: **FS** far low ≥ 80, **FM** 50–79, **FO** < 50 (the
control); all with cone low < 20, canvas ≥ 50, not `[BUILDING]`.

**A2. Protocol classes on the daily list.** `scripts/far-cloud-p1/bl_today.sql` gains two classes next to BL and
CL, from the same latest T+0 run per (location, event):
- **FS**: cone low < 20; max(cone mid, cone high, overhead mid, overhead high) ≥ 50; far low ≥ 80; not
  `[BUILDING]`; triage clear; sky rating present.
- **FO**: the same, with far low < 50 (the control).

FS and FO have a canvas, so they never overlap BL or CL (which require none). The owner rates **every listed
FS and FO evening watched**, dull or not, as for BL/CL; protocol evidence counts from the date this lands.

**A3. Gate to Phase B** (fixed now):
1. **Common and over-rated**: FS ≥ 3% of distinct evenings at the owner's locations, and FS rated 4★+ in ≥ 50%.
2. **The far reading holds**: ERA5 confirms far low ≥ 80 in ≥ 50% of checked FS slots (already 57%; re-check in A1
   by event and canvas).
3. **Outcome evidence** (protocol ratings, `by_admin`, "didn't see it" excluded): ≥ **8** FS and ≥ **8** FO
   evenings, and at matched forecast stars the FO − FS difference in mean outcome stars has a block-bootstrap
   lower bound **> 0** (5-day blocks, by evening). Tooling: reuse `bl_size.py`'s ratings join, matched-strata
   and bootstrap functions in a sibling `fs_size.py`, built when ≥ 8 + 8 evenings exist (not before).

If 3 lacks evenings: "waiting for protocol ratings". If 1 or 2 fails: stop and record it.

---

## 6. Phase B (paid replay, gated) — only if A passes

A candidate rule, **R-FS**, inserted after the far-corridor rules (text fixed before the replay, as a
`build-r1` patch copied from the dumped prompt):

> "FAR CORRIDOR SHUT UNDER A CLEAR HORIZON: when solar horizon low cloud is below 20% but the beyond-horizon
> (226 km) low cloud is 80% or more, the low sun still lights the scene before sunset, but the after-sunset
> underlighting of a mid or high canvas needs that far corridor open. Expect a short, muted afterglow: lower
> fiery_sky by 15–25 points and do not exceed 3★ unless the canvas is high cloud above 8 km. Golden hour is
> unaffected. Do not apply when the horizon is itself cloudy (the THIN STRIP and FAR CORRIDOR rules govern)."

Replay as `broken-low-canvas-plan.md` §3 does: SONNET_55 three runs per prompt; an A/A arm; groups FS, FO, FM,
FS with a high canvas, FS with `[CLEARING]`, the two pinned prompts; a Haiku arm on a T+2 sample; batch mode;
the owner approves each printed cost. Pass: compliance (FS fiery falls 15–25, ≤ 3★ except high canvas), no harm
(no FO or FM prompt moves beyond the A/A rate + 2 pp), pinned prompts unchanged.

## 7. Phase C — only if A and B pass, and the owner revisits the §3 ledger rule

Sonnet adds R-FS's replayed text; golden master regenerated and reviewed; `SkyRatingRegressionTest` and the Sky
Eval run by the owner; never-edit files untouched; `architecture-decisions.md` records the rule and the scoped
exception to "never escalatory". After deploy, the protocol continues, and a summer re-check is booked.

---

## 8. Limits

- ERA5 is a ~31 km reanalysis; "confirmed" means ERA5 agrees, not that the corridor was seen.
- One far point (226 km) stands for the corridor; the slot an overhead deck needs moves outward as the sun sinks.
- Outcome ratings are few and one person's; only protocol ratings decide.
- A mid deck can also stay grey for reasons the inputs don't show (thicker than forecast, lower base).

## 9. Out of scope

16 Nov's near-field low cloud; far **high** cloud (refuted as a sufficient lid, P1 F5); blanket horizons
(existing rules).
