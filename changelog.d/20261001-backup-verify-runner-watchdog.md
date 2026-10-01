### Fixed — backup verification can no longer stop silently when the Actions runner is down

`backup-verify.yml`'s `verify` job runs on `self-hosted`, which is dockermacmini — the production
host. If the Actions runner service on that box fails to come back after a reboot (it does not
restart itself, as `codeql.yml` already documents for the same runner), a job targeting it does not
fail, it queues forever, and `notify-failure` (`needs: verify`, `if: failure()`) never fires because
a queued job never reaches `failure()`. The nightly restore test would then stop running with nothing
louder than "queued" in the Actions UI — the same silent-failure shape this workflow was built to
catch for the backup script itself, one layer further out.

A new `runner-watchdog` job runs off-box on `ubuntu-latest`, mirroring the identical watchdog added
to Production Health's `external` job in today's companion PR: it polls the run's own job list via
`gh api` for up to 10 minutes and fails if the `verify` job is still `queued`, so it reports even when
dockermacmini is completely unreachable. It gets its own notifier, `notify-watchdog-failure`, rather
than being folded into `notify-failure` via `needs: [verify, runner-watchdog]` — `needs` waits for
every listed job to reach a terminal state, so a queued `verify` would hold the watchdog's own issue
back for hours, the exact defect being fixed.

Two related fixes land alongside it. `verify` now carries `timeout-minutes: 30`, several times the
slowest honest run, so a hung `docker` call can no longer leave the job `in_progress` forever — the
watchdog accepts `in_progress` as proof the runner is alive, so without a timeout `notify-failure`
could still never fire. And `notify-failure`'s condition moved from `if: failure()` to
`if: ${{ always() && needs.verify.result != 'success' }}`, because a job killed by `timeout-minutes`
can report `cancelled` rather than `failure`, which `failure()` alone would miss.
