### Changed — release.sh ends with the post-deploy health check's own verdict

Since #960 the `Deploy to Production` job runs `scripts/prod-health.sh --deploy` over SSH right
after `docker compose up`, but its one-line-per-check output and `prod-health: N checks, M failed`
summary only ever lived in the job log — `release.sh` itself still ended on its own `✅ deployed`
line with no mention of whether the check passed. `release.sh`'s step 9 now fetches the Deploy
job's log after the run completes (retrying briefly, since the log can lag the run by a few
seconds) and prints the extracted `ok `/`FAIL `/`prod-health:` lines as the very last thing on
screen, under a `Post-deploy health check (on dockermacmini):` heading — on both the success and
the failure path, since the health check is often the most useful diagnosis when the deploy itself
fails. A check nobody reads is not a check; this is the owner's own description of what was
missing. If the log can't be read (an older tag whose `deploy.yml` predates the check, a renamed
job, a transient `gh` failure), one line says so with the run URL — this never changes `release.sh`'s
own exit status, since the release has already succeeded or failed on its own terms by that point.
