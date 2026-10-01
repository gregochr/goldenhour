### Fixed — the OWASP scan now keeps its NVD database between runs, including failing ones

The weekly `OWASP Dependency Check` workflow has re-downloaded the full NVD database from scratch
on every run since at least 2026-09-13, because `actions/cache@v4`'s post-step skips the save when
the job has already failed — and a failing scan is this job's *normal* case, not an error to avoid
(`failBuildOnCVSS=7` is a gate, armed on purpose). `gh cache list --key owasp-db` has never returned
a single entry. The one run that worked (2026-09-27, run 36316518451) downloaded clean only because
the NVD API happened to answer at ~3,000 records/s that day; on 2026-10-01 it answered at ~45
records/s and the job was cancelled by its own 60-minute timeout at 160,000 of 400,203 records. The
cache step is now split into `actions/cache/restore@v4` before the scan and
`actions/cache/save@v4` with `if: always()` after it, so a refreshed DB is saved whether or not the
scan passes, and `timeout-minutes` is raised from 60 to 240 to survive a slow NVD day until the
cache has had a chance to warm up. Refs #702.
