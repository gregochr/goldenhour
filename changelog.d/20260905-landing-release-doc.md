### Docs — write down how the landing site is actually released

`landing/` had no README, and its release procedure was recorded nowhere in the repo.
It is easy to assume a version tag ships it, because that is how everything else here
ships — but `.github/workflows/deploy.yml` builds exactly two images, backend and
frontend, and never reads that directory. The marketing site is built on the production
host from a standalone `docker-compose.yml`, so releasing it is a manual step that is
independent of the release tag in both directions.

`landing/README.md` now states that, gives the commands, and records the two traps: the
`--build` flag is required (Compose otherwise restarts the previous image and reports
success), and the `Dockerfile` copies assets one `COPY` line at a time, so a new file
that is not listed there is missing in production while looking correct locally.
