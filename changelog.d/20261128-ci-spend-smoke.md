### Added — the daily real-API smoke's spend on Runs › Cost trends

The `Real API Smoke` workflow now records the tokens its one Batch request spends and uploads them as a small
`prompt-regression-usage-smoke-<attempt>` artifact, and the hourly `ci_spend_poll` job reads, prices (at the Batch
rate, half price) and stores it as a third CI suite, `smoke`. Cost trends and the Day view show it beside the sky and
ask prompt tests, still apart from the app's own figures, and the page now calls the group "CI tests" (the chart chip,
tooltip and table, and "CI test runs" on the Day view, where a scheduled run with no PR reads "Daily smoke"). A day on
which the batch was still processing, or never submitted, shows "usage unknown" rather than £0. The
`photocast.ci-spend.workflow-file` setting is gone: each suite's workflow file is fixed in code, and a stale setting is
ignored. A `workflow_dispatch` run started on a pull request's branch no longer takes that PR's number.
