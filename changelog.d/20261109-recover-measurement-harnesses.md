### Docs — recover three measurement harnesses into scripts/measurements

The P4a map-frame driver, the output-token split (export SQL and counter) and the Q5 pick-region
repeat harness existed only as uncommitted files in stale worktrees and are now committed under
`scripts/measurements/`, with READMEs and pointers from the plans that need them. The pick-region
harness carries a fix: equal per-window regional means now tie-break on scored coverage before
region name, as `PlanWindowProjector` does, with a new `verify.sh` fixture E that fails on the old
tie-break. The recorded Q5 figures (47.6% repeat, silence in 8 of 10) predate that fix and are
flagged for re-measurement.
