### Fixed — backup verification no longer leaves a Postgres volume behind every run

The daily backup-verification job started its scratch Postgres with no data volume, so the
image created an anonymous one (about 1 GB, a restored copy of production), and both the
pre-clean and the teardown used `docker rm -f` without `-v`. Thirty had built up on the
shared Docker host by 2026-11-11. The job now uses one labelled, named volume, removes it
in teardown, clears leftovers from earlier runs by its own label only, and warns if any
labelled volume remains.
