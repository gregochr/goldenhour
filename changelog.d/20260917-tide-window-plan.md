### Docs — Map tab "tide on the window": port plan and session prompts

`docs/engineering/tide-window-plan.md` is the port plan for the owner's `design_handoff_tide_window`
bundle (vendored verbatim at `docs/design/tide-window/`, with a `VENDORING.md` saying what was and was
not copied), and `docs/engineering/tide-window-prompts.md` is one paste-ready kickoff prompt per phase
for fresh Sonnet sessions. Eight phases (T1–T8): two backend (the per-slot tide facts, the strip's
window facts), one data-plumbing, then the chip, the callout and sheet block, the strip, the phone
layout, and the sweep.

The plan's §1 records where the codebase already answers the spec's open questions, and three of
those corrections change what gets built:

- the location's "want" already exists as a multi-select `TideType` (HIGH/MID/LOW) edited on the
  Locations screen, so the spec's one new column, API field and editor (`OPEN 1`) are not built;
- this app's tide axis is **time-based** (`classifyTideState`, the gate, `TideVisitor`), not the
  spec's level-based `1 − |level − target|`, and `TideSurfaceAgreementTest` exists to stop a second
  definition — so the chip's tiers read the served `tideAligned` and the level is a display fact;
- a tide-mismatched coastal slot is not rated low, it is **withheld from Claude** and served unrated
  (`BriefingGatingPolicy`, `evaluationGate`), which with the map's default "hide unknown" is the
  exact mechanism behind the spec's "the coast disappears". The plan keeps the gate and the score
  untouched this series (§5 #1) and draws the gated location dimmed without a star; lifting the gate
  is an owner decision with a measurement query attached (§6 Q1).

No code changes.
