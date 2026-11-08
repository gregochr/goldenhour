### Added — post-deploy smoke check and an image-only automatic rollback (report-only until the owner turns it on)

Straight after `docker compose up`, the deploy job now runs `scripts/post-deploy-smoke.sh` through the public URL
(shell, every hashed asset, the CSP header, backend health, the 401 boundary and, once a `smoke-deploy` PRO account
exists, a login that must serve a rated briefing), after `prod-health.sh --deploy --classify`, which now says whether a
failure is the release (a backend or frontend container) or the host (database, tunnel, Tailscale). A failure fails the
deploy job and opens the usual issue. With the repository variable `SMOKE_ENFORCE=true` a release the checks call
broken is also rolled back by `scripts/auto-rollback.sh`: it restores the outgoing `nginx.conf` and
`docker-compose.yml`, pins the previous image digests, and re-checks. It never touches the database; restoring data
stays `scripts/rollback.sh`. Exit codes everywhere: 0 pass, 1 release broken, 3 inconclusive, 4 smoke misconfigured,
and only 1 can roll back. The deploy job also runs one at a time, extracts the tag's whole `scripts/` directory, takes
its pre-release backup before it writes the new configuration, never overwrites an existing rollback point, and records
the running images only on success. New shell tests (`scripts/test/`) and a `deploy-smoke` Playwright project cover it.
