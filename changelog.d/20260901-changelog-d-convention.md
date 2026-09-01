### Changed — changelog entries are per-PR files now

Every open PR used to append under `CHANGELOG.md`'s `[Unreleased]` heading — the same line of the
same file — which guaranteed a merge conflict between any two in-flight PRs regardless of what
code they touched. Meaningful commits now add a `changelog.d/YYYYMMDD-<slug>.md` file instead
(convention in `changelog.d/README.md`), and `release.sh`'s promotion step folds the pending
files verbatim under the new version heading via the new `scripts/promote-changelog.sh` — which
validates each entry opens with a `### ` heading, proves the rewrite added exactly the block it
built and removed nothing (the same discipline the old two-line insertion carried), and deletes
the folded files in the same auto-merged promotion PR. Entries still written directly under
`[Unreleased]` keep working and fold under the same heading — the convention change is what ends
the conflicts; the script change is what makes the fold safe.
