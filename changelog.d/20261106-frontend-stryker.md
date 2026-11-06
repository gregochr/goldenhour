### Added — weekly frontend mutation testing (Stryker) on src/utils

A new weekly workflow, `stryker.yml`, runs Stryker mutation testing over the client logic in
`frontend/src/utils/`, the way `pitest.yml` does for the backend. It never blocks a pull request, and
it opens a failure report through `ci-failure-issue.yml` if the run fails. The incremental state is
carried between weeks as an artifact (not a cache, which GitHub would evict at the weekly boundary),
and a run is forced on the first scheduled run of each month and on request. No score floor is set
yet (`thresholds.break` is null): it comes from the first real measurement. `npm run test:mutation`
runs it locally.
