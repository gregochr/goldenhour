### Changed — OWASP Dependency Check runs on dependency PRs and keeps its NVD cache

The OWASP Dependency Check job in `security-scan.yml` now also runs on pull requests, so it
can be made a required check. It does real work only when a PR touches `backend/pom.xml`,
`backend/dependency-check-suppressions.xml`, the Maven wrapper or the workflow itself; any other
PR reports success immediately under the same check name. A PR scan never downloads the NVD
database: it runs with `-DautoUpdate=false` against the copy cached by the last `main` run and
fails with a clear message if none was restored. Only `main` saves the database, and it deletes
the superseded copies afterwards. The schedule moves from weekly to daily (05:30 UTC) so the
cache stays fresh and recently used.

CodeQL's per-commit JavaScript TRAP caches (~155 MiB each, one per push to `main`) are turned off
with `trap-caching: false`; they were filling the 10 GB Actions cache and evicting the NVD
database and the setup-java caches.
