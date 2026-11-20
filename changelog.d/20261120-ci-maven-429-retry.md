### Changed — CI retries Maven runs that fail on a transient repository transfer error

`scripts/mvn-retry.sh` wraps the `./mvnw` calls in `ci.yml` (the backend build and the E2E smoke job's
fixture-seeder, checkstyle and jar steps) and `a11y.yml`. When Maven fails and its log shows a
transfer or resolution error caused by an HTTP 429 or 5xx, a timeout or a connection reset (as in PR
1890, where Maven Central rate-limited a runner), it re-runs with a linear backoff, up to three
attempts. A compilation error, a test failure, a 404 or any other failure is never re-run. Covered by
`scripts/test/mvn-retry.test.sh`.
