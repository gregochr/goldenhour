### Docs — correct the landing release steps: a full `git pull` on the host is unsafe

`landing/README.md` was written from the repository alone and prescribed
`git pull && docker compose up -d --build`. Deploying for real showed that to be wrong in a
way that matters.

The production checkout at `~/goldenhour` is roughly **1,235 commits behind** `origin/main`
and carries four hand-edited, uncommitted production config files — `docker-compose.yml`,
`nginx.conf`, `application-prod.yml` and `scripts/backup-postgres.sh`. A `git pull origin
main` would attempt to merge 1,235 commits across exactly those paths. The host's own
`deploy-landing.sh` does precisely that under `set -e`, so a conflict aborts it part-way and
leaves live configuration in an unfinished merge. This is the same staleness that
`.github/workflows/deploy.yml` already works around by reading files out of the release tag
rather than trusting the checkout.

The documented procedure is now `git fetch` plus `git checkout FETCH_HEAD -- landing/`, which
updates only this directory, never moves `HEAD`, and cannot touch those four files. It also
records tagging the running image before `--no-cache` orphans it, since that image is the only
rollback available, and a per-path status check where a 404 on `photocast.css` is the signal
that the `COPY` line went missing again.
