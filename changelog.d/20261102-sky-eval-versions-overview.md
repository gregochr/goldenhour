### Added — Sky Eval: runs record their version, and a served overview and quick-check endpoint

Every Sky Eval run now records the commit it ran on. The production image is built without `.git`, so the
commit column has always been empty there; `deploy.yml` now passes the commit sha, time and subject line as
build args, `backend/Dockerfile` sets them as environment variables, and `GitInfoService` reads them when
`git.properties` is absent (new migration V216 adds `git_commit_message` and `fixture_names` to
`sky_rating_eval_run`). Two admin endpoints back the page redesign: `POST /api/admin/sky-rating-eval/check`
starts one manual run per chosen model in parallel, over all fixtures or a chosen subset, and
`GET /api/admin/sky-rating-eval/overview` serves everything the page shows (versions, sessions, lane and model
figures, the before/after verdict and per-fixture movement) computed on the server. The existing endpoints are
unchanged. No page change yet.
