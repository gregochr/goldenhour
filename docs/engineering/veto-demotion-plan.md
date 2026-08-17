# Plan: demote the cloud-approach veto from absolute ceiling to bounded penalty

**Status: PROPOSED — awaiting user go/no-go. Not a handover task.** This change moves
prompt-regression assertions, which are user-owned, so it carries two hard stop-points (§5) and
should be executed by a session the user is supervising.

## 1. The change

`PromptBuilder` (~lines 248–258), the combined-signal rule. Current text:

> "- Combined signal: when BOTH [BUILDING] trend AND upwind current ≥60% are present, the
> forecast is UNRELIABLE — cloud was physically moving toward the solar horizon at the time this
> data was captured. In this specific case: output rating 1 or 2, fiery_sky ≤20, golden_hour ≤20.
> These ceilings apply even if the solar horizon appears clear, canvas is present, or aerosol is
> favourable — those readings are unreliable when a building approach is confirmed. …the at-event
> upwind sample value is also unreliable and must be ignored. … Do not reference the favourable
> conditions in the summary — write only about the approach risk."

Proposed replacement (exact wording to be settled at implementation, semantics fixed here):

> "- Combined signal: when BOTH [BUILDING] trend AND upwind current ≥60% are present, cloud was
> moving toward the solar horizon when this data was captured and the event-time snapshot may be
> understated. Treat this as a significant reliability risk: reduce fiery_sky by 20–30 points and
> cap the rating at 3. Weigh the coned solar-horizon reading normally — it is a three-point
> average and remains the best gap estimate — but do not rate above 3 while both approach signals
> stand. Name the approach risk in the summary alongside the other factors rather than instead of
> them."

Semantics of the demotion, precisely:

- **Ceiling moves from {1,2} to ≤3**; fiery_sky becomes a −20..−30 penalty instead of a ≤20 cap.
- **The evidence-nullification clauses go**: the instruction to disregard a clear horizon,
  canvas, and aerosol, and to ignore the at-event upwind value, is what made this rule an
  absolute veto rather than a strong signal. Post-F1 the horizon reading it was told to distrust
  is the *coned* average, not the single cell that motivated the distrust.
- **The summary gag goes** with it (summaries may acknowledge the canvas while naming the risk).
- **Unconditional demotion — NOT F2.** F2 keyed the exemption on the coned horizon reading and
  was refuted by Copt Hill, where that exact reading (7% clear) was the misleading input. This
  plan does not condition the demotion on anything; the cap applies whenever both signals stand.
  F2's rejection stands untouched.
- **Everything adjacent stays**: the standalone `[BUILDING]`-only penalty (−15..−30 fiery), both
  upwind-alone rules, the strip-vs-blanket fork, the >60% blocked ceiling. §9 measured none of
  them as defective (the blocked rule's over-pessimism is a separate finding with a separate,
  unbuilt sizing cut — see `corridor-forecast-cut-plan.md`).

## 2. The evidence (veto doc §9, window complete 2026-08-16, n = 29,016)

- The rule fires on **12.6%** of evaluations and forces rating 1–2 on each.
- Fired skies were observed **7.7pp clearer** at the horizon than non-fired skies (49.1% vs
  56.8%) — the veto anti-selects.
- Gap error where it fires: **+35.6** vs +24.6 where it does not; the rule concentrates on the
  forecast's most pessimistic misses, at every wind–sun angle.
- D7 is closed: capped (+36.2) and uncapped (+34.9) halves are indistinguishable. No geometry
  fix rescues the rule; the ceiling is the defect.

## 3. The trade, stated honestly

Copt Hill 2026-03-11 is a real recorded wasted trip (predicted 4★, actual ~2★) that these exact
signals caught, and the ceiling turned into a correct 1–2★. Under the demotion that sky rates at
most 3★ — one band above the recorded outcome. That is the price. What it buys: the ~12.6% of
all evaluations currently forced to 1–2★ on a signal that measurably selects *clearer-than-
average* skies get scored on their evidence with a one-band safety margin. An absolute ceiling
can only ever create missed opportunities; §9 shows it creates them against skies that were fine.
`actual_outcome` is still empty, so this trade cannot be scored against real outcomes yet — the
eval harness and regression diffs are the only pre-deploy instruments, and §5 treats them as such.

## 4. Files touched

- `backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java` — the
  combined-signal block only.
- `docs/engineering/cloud-approach-veto-fix.md` — §4 D2 row and §9 gain a "demotion shipped"
  status line when it lands.
- `CHANGELOG.md`.
- **No** verification-side, sampling, entity, or frontend changes. The verification report keys
  its veto buckets on the persisted trigger columns (`solar_trend_building`,
  `upwind_current_low_cloud`), not on prompt behaviour, so it keeps measuring the same population
  after the demotion — deliberately: that is the before/after instrument.

## 5. Verification — with two hard stop-points

1. `./mvnw compile -q`, then `./mvnw checkstyle:check` (exit codes, not grepped output).
2. **Sky-rating eval harness** (gated pass^k band+bucketing, frozen fixtures, needs
   `ANTHROPIC_API_KEY`): re-run against the all-green Sonnet baseline. Every fixture that moves
   band must be one that exercises the combined signal, and must move in the demotion's
   direction (up, by at most the ceiling relaxation). Any other movement is a defect in the
   wording, not a re-baselining opportunity. **STOP-POINT 1: the band-movement list goes to the
   user before the new baseline is accepted.**
3. **Prompt regression tests** (`-Pprompt-regression`, real Claude calls):
   `PromptBuilderCoptHillSimulationTest` and `PromptRegressionTest:255-279` pin the veto's
   current 1–2★ shape and WILL fail. **STOP-POINT 2: surface the diffs and stop. Assertions are
   user-owned — per CLAUDE.md they are never edited by the model, only by the user.**
4. Post-deploy: re-pull the cloud-verification report on a post-change window. `vetoSeparation`
   will not move (same trigger population by design); the instrument is the **rating
   distribution within the fired population** — the 1–2★ share should fall toward the demoted
   cap, and any rise above 3★ means the prompt wording failed to hold the cap.
5. When `actual_outcome` finally has rows, the calibration gate's `missedOpportunities` for
   veto-fired slots is the number this change exists to reduce; `wastedTrips` is the number it
   must not raise by more. Recorded here so the eventual check is pre-registered, not post-hoc.

## 6. Sequencing

**Updated 2026-08-17:** the corridor cuts have all landed and reported; the measurement program is
complete. This change now executes in a **single supervised session together with
`blanket-confirmation-plan.md`** — both are `PromptBuilder` changes sharing one eval-harness
re-baseline, one prompt-regression diff review, and one release, so two passes would double the
most expensive steps (the user-owned assertion reviews) for no benefit. The blanket change is
gated on its precision cut (see its plan); if that number is still outstanding when the session
runs, this demotion proceeds alone rather than waiting — its evidence is complete and
independent.
