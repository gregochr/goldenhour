# Far-shut canvas: is a clear horizon under a canvas, with the far corridor shut, over-rated?

**Status:** revised after adversarial review, 2026-11-19 (§9). **Measurement only.** Nothing in production
changes. The draft's candidate prompt rule is withdrawn: the mechanism was overstated, the trigger evening does
not fit it, and the regression suite holds an owner-observed counter-example. Phase A (a free sizing query, a
case check of 19 Nov, and blind protocol classes assigned from stored values) decides whether a rule is ever worth
drafting. The owner pre-approved any later replay spend (2026-11-19); that approval does not bypass the gates.

---

## 1. Trigger

**19 Nov 2026, sunset.** Copt Hill (176853) and Penshaw Monument (176875), 4★, fiery 66–68, the window's
**BEST BET**; the owner, beside Copt Hill, saw a flat grey lid and rated it 1★.

| Run 13:33 (T+0) | Copt Hill / Penshaw | Angel (176835) |
|---|---|---|
| Overhead low / mid / high | 5–9 / 88–93 / 0 | 5 / 88 / 0 |
| Cone (113 km) low / mid / high | 8 / 70 / 0 | **28** / 58 / 0 |
| Far (226 km) low / mid | 93 / 45 | 98 / 5 |
| Low trend earliest → event | 69 → 8 | 77 → 28 |

- **Only Copt Hill was observed.** Penshaw is an unobserved neighbour; the trigger is **n = 1**.
- **The Angel is not in the class** (cone low 28 ≥ 20). With a 77 → 28 trend it resembles 16 Nov (near-field
  sunward low cloud), a separate question.
- **Counter-evidence already in the suite:** Steel Rigg, 24 Oct sunrise (cone 0/0/87, far low 80), owner-observed
  **4★** and pinned at exactly 4 (`SkyRatingEvalFixtures`, `steel-rigg-24oct-worth-going`). It is the suite's
  only clear-horizon far-shut case, with a high canvas.

---

## 2. Physics, corrected (`far-cloud-lid-plan.md` §2)

- At 226 km the sun's line is ~4.1 km high at α = 0, so low cloud there (tops 1–3 km) **does not block direct
  light before sunset**.
- For an overhead deck the underlighting ray dips below low tops at 226 km only from observer depression
  δ ≈ (b − z + 4.0 km) / 226 km: about **1.0–2.3°** for a mid deck (≈ 9–20 min after a late-November sunset at
  55° N), ≈ 2.3–2.8° for an 8 km high deck (≈ 20–24 min), later still for a deck over the cone.
- So a shut far corridor **cuts the late, reddest afterglow; it does not remove underlighting.** The early
  afterglow depends on the 0–150 km corridor. The deck's own clear slot at ≈ 2Rδ is a far **mid/high** question.
- **19 Nov does not fit this mechanism**: it predicts "colour early, red cut short", not a grey lid. More
  economical blockers: the 58–70% mid deck at the cone sitting on the golden-hour sun line (lid plan §2(A).2),
  a deck continuing sunward (far mid 45%), or a timing error in the "clearing" low band (69 → 8).

---

## 3. What the prompt does, and decisions any later rule must respect

- The far low figure is printed every time, but the rules use it only when the 113 km horizon is cloudy (THIN
  STRIP, FAR CORRIDOR, `PromptBuilder` ~l.147–168).
- **Ledger rule:** "never give the far reading escalatory force again", measured on blanket calls (113 km low
  ≥ 50) with ERA5 "open" at ≤ 30. Any rule here *would* be escalatory; the owner revisits that rule only with
  evidence stated on the ledger's own definitions (§5 A1).
- **Pinned rules any rule would touch:** the thick-mid rate-4 floor (l.128–139, regression-pinned), IDEAL
  (l.126–127), the `[CLEARING]` label (l.302–314), and Steel Rigg's exact-4 pin.
- **Far-cloud P1 F5** refuted far *high* cloud as a sufficient lid detector.

---

## 4. Evidence so far, with its limits (production, 2026-11-19, 60 days)

Clear horizon, canvas, rated 3★+, **counted by run** (to be replaced by A1's per-slot numbers):

| Forecast far low | Runs | Rated 4★+ | ERA5 ≥ 80 | ERA5 < 50 | ERA5 mean |
|---|---|---|---|---|---|
| < 50 | 3,311 | 74% | 3% | 94% | 9 |
| 50–79 | 526 | 69% | 14% | 75% | 27 |
| ≥ 80 | 698 | 80% | **57%** | 33% | 67 |

- **Precision 57% against a prevalence of ~13%**; one in three FS runs had ERA5 far low < 50.
- The comparison is at the **pre-event hour**; the mechanism acts after sunset (the dark-hour columns exist and
  A1 uses them). ERA5 "low" (σ 0.8, ≈ 2 km) and Open-Meteo "low" (0–3 km) bound layers differently.
- FS's higher 4★ share may be the **incoming system** (deeper decks, thick-mid floor), not the corridor.
- **Nothing here shows that a shut corridor means less colour.** ERA5 sees cloud, not colour.

---

## 5. Phase A (read-only, free)

**A0. Case check of 19 Nov** (on or after 2026-11-26, when ERA5 has it): ERA5 cone low/mid, far low/mid, the
dark-hour far readings, and a Meteosat look. Was the near horizon really clear, and did the deck run past
226 km? Recorded here before anything else.

**A1. Sizing.** `docs/engineering/cost-queries/far-shut-canvas-sizing.sql` (tested on PostgreSQL 16):
one slot per (location, date, event) chosen before any class filter; classes **FS** (far low ≥ 80), **FM**
(50–79), **FO** (< 50, the control); per class, event and canvas type: slots, evenings, 4★+ share, fiery,
ERA5 at the event hour (≥ 80, ≤ 30 "open", mean) and at the dark-side hour; precision vs prevalence;
confounders inside FS (far mid deck ≥ 50, deep deck at 700/500 hPa); FS's share of 4★+; FS frequency per
location; outcome ratings classed from the run the reader saw, with FS split by ERA5 shut/open (dose-response).

**A2 amendment (2026-11-20, in force from the day `overhead-low-deck-plan.md`'s PR merges; no admin rating on an
FO-qualifying slot existed before then):** a fifth class, OL (overhead low deck ≥ 60 with a clear horizon and an
open far corridor), is checked before FS and FO, so a low-deck evening is never an FO control. FS's listing is
unaffected (OL needs far low < 50), but **FS rows with overhead low ≥ 60 are a confounder**: `fs_size.py` reports
them separately, and A3.3 is evaluated on FS rows with overhead low < 60, the same filter FO now has. A1's
confounder list gains "overhead low ≥ 60". Moving OL out of FO slows FO's accrual toward 12.

**A2. Blind protocol classes.** The classes BL, CL, OL, FS and FO (one CASE, first match wins, as in
`scripts/far-cloud-p1/bl_today.sql`) are assigned afterwards from stored values to the owner's ratings at the
protocol locations (`broken-low-canvas-plan.md` A4: Copt Hill, Penshaw Monument, Angel of the North). The owner
never sees a class and rates every watched sunrise or sunset there, whatever happened. `bl_today.sql` remains
an optional reminder that prints only place, event, local time and stars; the protocol does not depend on it
(amendment 2026-11-20, `broken-low-canvas-plan.md` A4). (A slot that qualifies as both CL and FO is classed as
CL; this slows FO slightly and keeps the broken-low study's pre-registered definitions.)

**A3. Decision point, fixed now:** **2027-01-31, or 12 FS and 12 FO protocol evenings, whichever comes later**,
evaluated once (no repeated looks). Tooling then: a sibling `fs_size.py` reusing `bl_size.py`'s ratings join
and day-block bootstrap, **stratifying on forecast stars 3, 4 and 5** (FS is ~80% 4★; `bl_size.py`'s 1–3
strata would drop it). Proceed to drafting a rule only if all hold:
1. **A0** shows 19 Nov's near horizon was clear and the far corridor shut (else the trigger is explained
   elsewhere; record it).
2. **A1**: FS precision against ERA5 ≥ 50%, and FS's 4★+ excess over FO is not explained by the far mid or
   deep-deck confounders (it persists inside the confounder-free FS rows).
3. **Outcomes** (protocol, `by_admin`, "didn't see it" excluded): FO − FS mean outcome stars at matched forecast
   stars ≥ **1★** point estimate with a 5-day block-bootstrap lower bound **> 0**; and within FS, ERA5-shut
   evenings are no better than ERA5-open ones (the dose-response has the right sign).

Otherwise: record the result here and in the ledger, and close.

---

## 6. If A passes: what a rule would have to satisfy (not drafted)

- Scope: Sonnet near-term T+0/1, canvas present, not `[BUILDING]`; mirrored for sunrise ("after sunrise" is
  the pre-dawn twilight); **no effect when the canvas is mainly high cloud** (Steel Rigg).
- **Effect sized to the mechanism**: the late afterglow (fiery), never golden hour; a star cap only if the
  outcomes support one.
- **Precedence stated** against IDEAL, the thick-mid rate-4 floor (which it would amend, an owner decision) and
  `[CLEARING]`.
- Replay (owner pre-approved spend): A/A arm; groups FS, FO, FM, FS with cone mid > 80, FS meeting IDEAL, FS
  with `[CLEARING]`, a Haiku 4.5 arm only if a T+2 ERA5 check supports it; **Steel Rigg and Low Hauxley stay
  exactly 4 with fiery and golden inside their `RegressionBounds` in every run**.
- Owner decision framed on the ledger's definitions: FS slots ERA5-open (≤ 30) as a count and rate, expected
  wrong demotions per month, and the amendment to the pinned floor.

---

## 7. Limits

- n = 1 trigger; Steel Rigg is a counter-example; outcome ratings are few and one person's.
- ERA5 is a ~31 km reanalysis with different layer boundaries; one far point stands for a moving ray path.
- The far reading is the pre-event hour; the dark-hour reading is the better match and is used in A1.
- 16 Nov (near sunward low) and 19 Nov's Angel are the same ray-path question at a shorter distance; results
  here do not cover them.

## 8. Out of scope

Far high cloud (P1 F5), blanket horizons (existing rules), near-field sunward low cloud (a possible separate
plan if more cases accrue).

---

## 9. Review record (2026-11-19)

One read-only reviewer, no refuter (measurement-only plan). Changes from the draft:
- **Withdrawn:** the candidate rule R-FS. It would have failed the pinned Steel Rigg fixture (an owner-observed
  far-shut 4★), collided with IDEAL, the thick-mid floor and `[CLEARING]` with no precedence, and rested on an
  overstated mechanism.
- **Physics corrected:** a shut far corridor cuts only the late afterglow; 19 Nov does not fit it.
- **Evidence restated:** precision vs prevalence, runs vs slots, pre-event vs dark hour, the incoming-system
  confounder, the ledger's own "open" cut.
- **Query rebuilt:** latest run chosen before filtering, run-before-event, dark-hour and confounder columns,
  per-location frequency, outcomes classed from the run the reader saw, dose-response.
- **Protocol blinded**, the trigger counted as n = 1, the Angel excluded, a single fixed evaluation point with a
  ≥ 1★ effect bar and stars 3–5 strata.
