### Docs — the engineering page's mutation testing now includes the app's client logic

`landing/engineering.html` now lists the weekly Stryker run beside PIT in the checks table, says in
"Testing beyond coverage" that it covers the app's client logic, and corrects "Where it falls short":
mutation testing covers the server and the app's client logic, but not the app's screens. No score
threshold is claimed (Stryker's `thresholds.break` is unset). `docs/engineering/engineering-page-plan.md`
carries the same copy. The owner publishes the landing change with `./release-landing.sh`.

The published test count also moves from "more than 25,000" to "more than 33,000" on the engineering and
how-it-was-built pages (CI run 42349072217: 14,913 backend and 18,524 frontend tests).
