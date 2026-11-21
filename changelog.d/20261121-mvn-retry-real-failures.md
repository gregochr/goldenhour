### Fix — mvn-retry no longer re-runs a real coverage, checkstyle or SpotBugs failure

`scripts/mvn-retry.sh` now judges a build transient only from `[ERROR]` lines, so a recovered `[WARNING] Could not transfer metadata ... Connection reset` can no longer qualify a later jacoco, checkstyle or SpotBugs failure for up to three full `clean verify` runs. Those failures are also named as real failures.
