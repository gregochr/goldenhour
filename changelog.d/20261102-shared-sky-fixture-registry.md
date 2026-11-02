### Changed — Prompt-regression and Sky Eval now share one list of observed days

The eight real observed days in `PromptRegressionTest` now come from the same registry as the weekly Sky-Rating
Eval, so adding a day once puts it in both suites. Each day carries its owner-observed regression bounds with it,
moved across unchanged and pinned number-for-number by a new `RegressionBoundsRegistryTest`. No bound changed.
Angel of the North stays a regression-only case, and the two hand-authored eval fixtures stay eval-only.
