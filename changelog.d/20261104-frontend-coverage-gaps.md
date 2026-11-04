### Testing — Cover the API wrappers and admin simulation UIs; raise the coverage floor

Added Vitest tests for the axios wrapper modules that had none (user, models, metrics, batch, tide,
light) or little (forecast, scheduler, settings, area, region, runs, aurora, auth), and for the Aurora
simulate modal, the Hot Topic simulation panel, `useSortAndFilter` and `startRunOptions`. The CI
coverage floor rises to 98% lines, 97% statements, 97% functions and 93% branches.
