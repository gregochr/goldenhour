# Brief for Claude Code: a forced sample sets its verdict but cannot take a pick

Owner decision, 2026-10-22. Narrows the 2026-09-29 force-evaluation exemption.

## The problem

Plan tab, viewed Thu 22 Oct 2026. Sat 24 sunrise wears the strip's BEST BET on 6 rated places of
83 in reach (`Worth it 4.0★`). Today 22 sunrise beside it, rated across almost the whole north at
`Worth it 3.7★`, is only ALSO GOOD.

Suspected cause, **not yet confirmed against production**:
`BriefingRegion.verdictEligible()` returns `sampleSufficient || forcedSample`. One force-evaluated
rating in a region exempts it from `VerdictSampleGate` for verdict, pick eligibility and ranking
together.

## Step 0: confirm

Pull the served briefing for Sat 24 Oct sunrise and check Northumberland & Tyneside (and any other
region in that window): expect `sampleSufficient: false`, `forcedSample: true`. If that is not what
production says, stop and report back. The fix below targets this cause only.

## The change

Split the one test in two on `BriefingRegion`:

- `verdictEligible()`: unchanged, `sampleSufficient || forcedSample`. A forced sample still sets
  `displayVerdict`, stars and confidence. Force-evaluation keeps doing what it was built for.
- `pickEligible()` (new): `Boolean.TRUE.equals(sampleSufficient)` only. Required to be named
  BEST BET or ALSO GOOD, to be named in `DailyBriefingResponse.bestBets`, and to be written to
  `pipeline_run_pick`.

Then move every **pick** decision onto `pickEligible()`. From the javadoc the call sites are
`PlanWindowProjector` (Plan tab ranking) and `BriefingBestBetAdvisor` (via `BriefingRollupBuilder`
/ `BestBetRanker`). Grep every `verdictEligible()` caller and decide per site whether it is a
verdict question or a pick question. Do not guess; list them in the PR.

Ranking: a forced-but-insufficient region may still be **ordered** among windows on its average
(so the strip reads sensibly), but it must never be the one that takes a pick. If ordering and
picking share one code path, the pick must skip to the next `pickEligible()` window, and a
window with none takes no pick at all. Two picks on a strip is the cap, not a quota.

## What must not change

- Verdict words, stars, `meanRating`, `bestRating`, confidence for any region.
- `sampleSufficient`, `forcedSample` and `VerdictSampleGate` thresholds.
- Null on either flag still reads as not eligible (the safe direction).
- How force-evaluation runs, or which far-out ratings it spends calls on.

## Tests

- Forced-only region, insufficient sample: verdict set from its mean; not a pick; absent from
  `bestBets` and `pipeline_run_pick`.
- The Thu 22 Oct case as a fixture: Sat 24 sunrise (6 forced-or-mixed ratings, 4.0) against Today
  22 sunrise (well rated, 3.7). Today takes BEST BET; Sat takes no pick and keeps `Worth it 4.0★`.
- Sufficient region that also has a forced slot: still pick-eligible.
- Legacy payload (both flags null): not pick-eligible, not verdict-eligible (unchanged).

## Docs

Update the `forcedSample` field javadoc and the CLAUDE.md row that records the 2026-09-29
exemption: the exemption now covers the verdict only, not picks (2026-10-22).

## Related

The thin-sample heat field design (`README.md` and `Thin Sample Field.html` in this folder) assumes this change in its
"after" artboards. It is separate work and can ship in either order.
