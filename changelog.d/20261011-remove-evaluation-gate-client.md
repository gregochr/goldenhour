### Removed — the client's readers of the inert `evaluationGate`

Since the tide gate lift (2026-09-18) the pipeline has served no `evaluationGate`, yet the Map callout, the location
sheet and the tide index still read it and carried a "Not scored" arm, a gate row and their CSS. All of that is gone:
`buildEvaluationGateIndex`, the `evaluationGate` and `gated` index fields, the sheet row's `gate`, the callout and
sheet gate lines and `.wf-loc-gate` / `.wf-callout-gate`. An unscored window reads exactly as it did in production
("Not scored yet", "Loading…" or the retry line); no visible change.
