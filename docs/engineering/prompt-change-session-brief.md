# Session brief: the two prompt changes (veto demotion + blanket rewording)

Kickoff brief for the supervised prompt-change session. Written 2026-08-17 by the session that
ran the measurement program; the user starts the implementing session (Opus, ultracode) with a
prompt pointing here.

## Mission

Implement **both** prompt changes as one change set in `PromptBuilder`, per their plans:

1. `docs/engineering/veto-demotion-plan.md` — the combined-signal veto's absolute 1–2★ ceiling
   becomes a bounded penalty (cap 3, fiery −20..−30, evidence-nullification clauses and summary
   gag removed). Unconditional — NOT F2's rejected exemption-on-clear-horizon.
2. `docs/engineering/blanket-confirmation-plan.md` — the EXTENSIVE BLANKET rule and its
   data-block label lose their escalatory force (far reading corroborates, never confirms), and
   the THIN STRIP text/label discrepancy is resolved so prose and `isThinStrip` agree. The
   pre-registered decision rule has already fired (53.6% ≥ 25%) — the change ships as drafted.

Both plans live **on this branch** (`docs/era5-measured-results`). From a worktree cut from main
they are invisible on disk — read them with:

```
git show docs/era5-measured-results:docs/engineering/veto-demotion-plan.md
git show docs/era5-measured-results:docs/engineering/blanket-confirmation-plan.md
git show docs/era5-measured-results:docs/engineering/cloud-approach-veto-fix.md   # §9 = evidence
```

⚠️ When spawning review or implementation agents, **inline the relevant plan text into their
prompts** — an agent in its own worktree cannot read files from your checkout, and a compliance
lens with no spec returns zero findings and looks clean (this has happened in this repo).

## Decisions already made — do not re-litigate

Every number is final and audit-survived (five recuts, §9 of the veto doc, including the closing
"triage cut" subsection that corrected the earlier headlines). In particular: the veto's
anti-selection claim is WITHDRAWN (+3.4pp over 545 promptable firings is the true figure — the
demotion's case is "absolute ceiling on a barely-discriminating signal", not "anti-selection");
the blanket label is 53.6% false among promptable calls; the ideal-scenario guard and cone
min/max changes were considered and closed UNBUILT. Do not resurrect them.

**Out of scope, hard:** `WeatherTriageEvaluator` (the triage-corridor question has its sizing —
4,228 above-cut open-corridor stand-downs — but is a separate, separately-gated future change);
the near-gate >60% ceiling itself; the strip override thresholds; the upwind-alone rules; all
verification-side code.

## The two hard stop-points (user-owned; the session STOPS, it does not proceed)

1. **Sky-rating eval harness re-baseline.** Run the gated pass^k band+bucketing harness (find it
   in the repo — it exists, with an all-green Sonnet baseline; needs `ANTHROPIC_API_KEY`). Every
   fixture that moves band must exercise one of the two changed rules and move in the softening
   direction. Present the band-movement list to the user BEFORE accepting any new baseline.
2. **Prompt regression tests** (`./mvnw test -Pprompt-regression`, needs `ANTHROPIC_API_KEY`).
   `PromptBuilderCoptHillSimulationTest` and the veto-shape assertions in `PromptRegressionTest`
   WILL fail — that is expected and is the stop, not a bug to fix. Surface the diffs and STOP.
   Per CLAUDE.md, regression assertions are edited ONLY by the user. Never re-baseline them.

## Mechanics and conventions (violations have all actually happened here)

- Work in a worktree; do NOT switch the main checkout's branch — at least one other session is
  active on this machine.
- Gate every build on exit codes, never grepped output (`>/tmp/x.log 2>&1; echo "exit: $?"`).
  Ladder: `compile -q` → targeted tests → `checkstyle:check` → the CI-equivalent
  `clean verify -Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false`.
- Adversarial review of the diff BEFORE commit (project cadence): prosecutor lenses → per-charge
  refutation agents (default REFUTED without citable evidence) → synthesis. Review agents are
  READ-ONLY; anything that must mutate gets its own worktree. Never `git checkout --` as cleanup.
- Post-change instrument (state it in the PR body): the verification report's
  `stripMissed`/`vetoFired` populations are unchanged by construction (they key on persisted
  columns), so their *rating distributions* before vs after deploy are the before/after pair —
  1–2★ share should fall toward the new caps; anything above 3★ in the veto population means the
  wording failed to hold.
- Conventional commit, CHANGELOG under `[Unreleased]`, never push, never tag.

## Definition of done

Both rules reworded per their plans; prose and label agree everywhere touched; build ladder
green; adversarial review findings fixed or refuted; STOP with the eval-harness band-movement
list and the regression diffs presented to the user for their two sign-offs. The commit happens
only after those sign-offs.
