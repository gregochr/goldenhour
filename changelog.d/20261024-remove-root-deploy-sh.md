### Removed — the unused root deploy.sh

The root `deploy.sh` ran a full `git pull origin main` on the production host, whose checkout is stale and hand-edited, so it aborted on conflicts with uncommitted production config. Nothing called it: backend and frontend deploys go through the tagged release, and the landing site through `./release-landing.sh`.

The host's own copy, `~/goldenhour/deploy.sh`, should be deleted by hand.
