### Added — model-disagreement sizing study scripts

`scripts/model-disagreement/` is a read-only Python study (standard library only, no production code, no
migration, no Claude spend) that sizes whether "other models see cloud" predicts the mid and high cloud ERA5
shows when `best_match` stored none, the 31 Oct 2026 Angel of the North shape. `md_probe.py` checks what each
model serves, `md_sample.py` deduplicates the P1 export to units, draws a month-proportional sample, prints the
expected fetch days against the daily budget and a power table, and stops until `--go`. `md_fetch.py` fetches one
single-model request per model into P1's cache, and `md_size.py` writes the pass / fail / inconclusive report
with the alignment control, the lead-fair population, the null comparators and the baselines. It reuses
`scripts/far-cloud-p1/` without editing it; the owner runs the network steps from the new `README.md`.
