# Plan: stop the far-solar reading confirming a blanket

**Status: PROPOSED, GATED on the blanket-precision cut. Not a handover task.** Like the veto
demotion, this moves prompt-regression assertions (user-owned) and shares its two stop-points;
per the demotion plan's §6, both changes execute in one supervised session.

## 1. The evidence (veto doc §9, strip split, 2026-08-17)

`stripMissed` = 3,366 evaluations — **11.6% of everything scored** — where the forecast put the
solar gate deep in the blocked band (mean 92%), the forecast far reading claimed ~89% cloud, and
the observed 226 km corridor was really ~21% clear. Because both forecast arms sat ≥50%, the data
block printed `[EXTENSIVE BLANKET — full penalty applies]`: the far reading did not merely fail
to soften — it actively confirmed a blanket that was not there.

The error is **bimodal**, which is the load-bearing fact: where the forecast far reading showed a
drop (`stripSeen`), it was nearly exact (`meanFarError` +2.9); where it showed a blanket over a
clear corridor, it was wrong by ~68pp. So the reading is trustworthy exactly when it *softens*
and untrustworthy in a large share of the cases where it *hardens*. Threshold retunes (a 3pp
forecast drop clears no threshold) and constant bias corrections (there is no constant) are both
dead — see §9.

## 2. The missing number, and the gate

What §9 cannot say is the blanket label's **precision**: of all forecast blanket calls (near ≥50
AND far ≥50, mirroring the label logic at `PromptBuilder` ~:489), what fraction sat over a
corridor that was really blanketed vs really open? The strip split conditioned on *observed*
farClearer skies, so it counts the label's failures without its successes.

`blanket-precision-cut-plan.md` (fourth recut, handover-shaped) measures it: `fcstBlanket` bucket
split by observed corridor state. Decision rule, pre-registered before the number is seen:

- **False-blanket rate ≥ ~25%** → the change below ships as drafted: the blanket label loses its
  escalatory force entirely.
- **False-blanket rate well under ~10%** → the label is mostly right; the change narrows to
  softening its *language* (from "full penalty applies" to corroboration) without touching the
  rating bands, and the 11.6% is accepted as the price of the 90% until `actual_outcome` data
  exists to referee the trade properly.
- **Between** → user's call at the stop-point, with the number on the table.

## 3. The change (as drafted, subject to §2)

`PromptBuilder`, two sites:

- **The EXTENSIVE BLANKET rule text** (~:143-144: "solar horizon low cloud ≥50% AND
  beyond-horizon low cloud also ≥50%. Full blocking penalty applies — rating 1-2, fiery_sky
  5-20") — reworded so the far reading corroborates rather than escalates: the blocked ceiling
  already applies from the near gate alone; a far reading ≥50% adds "the corridor beyond offers
  no relief" *as likelihood, not certainty*, and when substantial mid/high canvas is present the
  rating may reach 3 rather than being pinned to 1-2 by the label.
- **The `[EXTENSIVE BLANKET — full penalty applies]` data-block label** (~:489-492) — softened to
  match (e.g. `[FAR CORRIDOR ALSO CLOUDY]`), so the label and the rule cannot argue.

Explicitly in scope at the same time: resolving the **text/label discrepancy** recorded in the
strip-split work — the prose defines THIN STRIP with "far ≤30%" while `isThinStrip` tests only
the ≥30pp drop. Whichever way the user decides it, prose and code must leave this change agreeing.

Explicitly NOT in scope: the near-gate blocked ceiling itself (>60% = 1-2 with no canvas), the
strip override's thresholds, and the veto rules (the demotion plan owns those).

## 4. Verification

Identical structure to the demotion plan §5, and executed together with it: compile/checkstyle →
eval harness re-baseline (**STOP-POINT 1**: band-movement list to the user; blanket-affected
fixtures must move only in the softening direction) → prompt regression run (**STOP-POINT 2**:
diffs surfaced, assertions never edited by the model) → post-deploy, the verification report's
`stripMissed` population is the instrument: its *rating distribution* should lift off the 1–2
floor while `stripSeen`'s stays put — the split was built to be exactly this before/after pair.

## 5. Files touched

`PromptBuilder.java` (the two sites above) · `docs/engineering/cloud-approach-veto-fix.md`
(status lines when it lands) · `CHANGELOG.md`. No verification-side, sampling, entity, or
frontend changes.
