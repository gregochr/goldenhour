### Fixed — the release promotion no longer false-positives on a large CHANGELOG

`scripts/promote-changelog.sh` proves its rewrite added exactly its insertion block and removed
nothing by diffing the old and new `CHANGELOG.md`. It called plain `diff`, whose Apple/BSD
implementation uses a non-minimal heuristic on large files by default — inserting the v2.20.6
block (4283 lines into a 12k+ line file) produced a larger, non-minimal edit script (4286 added /
3 removed) instead of the true minimal one, and the guard refused a legitimate promotion. The
`diff` calls now pass `-d` to force a minimal diff; a no-op for GNU diff, which is already minimal
by default.
