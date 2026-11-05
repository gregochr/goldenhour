### Testing — Cover the remaining components; add a per-file coverage floor

Added Vitest tests (new `*Branches` files plus extensions) for the components, hooks and utils that sat under 95% lines or 90% branches, including TurnstileWidget, DaySummary, SlowerScatter, UserAddPanel, SchedulerTabs, AllModelsTable, VersionCompare, RunsLiveStrip and RegisterPage. No product source changed.
The Vitest floor now also requires every file in `src/**` to reach 95% lines and 90% branches on its own (two files with unreachable branches, TurnstileWidget at 81% and StartRunScope at 87%, carry their measured figure), and the global floor rises to 99 / 98 / 98 / 95.
