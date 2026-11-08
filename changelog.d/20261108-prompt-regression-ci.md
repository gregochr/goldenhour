### Added — approval-gated prompt-regression runs on pull requests that change prompt code

A new workflow, `Prompt regression (approval-gated)` (`.github/workflows/prompt-regression.yml`), runs the real-Claude
prompt-regression suites on same-repository PRs that touch prompt or evaluation code. A `changes` job with no secrets reads the
three-dot diff and `scripts/prompt-regression-paths.sh` decides whether the sky suite (`SkyRatingRegressionTest`, the best-bet
aurora and token-count tests, on Sonnet 5.5) or the Ask suite needs to run; each paid job waits in the `prompt-regression`
GitHub environment until the owner approves it, uses a separate spend-capped key, and writes a per-class summary
(`scripts/prompt-regression-summary.py`) from the surefire reports. It can also be started by hand with a choice of suite and
of 1 or 8 runs per sky fixture. It is never a required check. The path logic, the summary parser and the workflow's safety
properties are pinned by new `scripts/test/` shell tests.
