### Changed — npm audit gate gains a narrow, expiring allowlist

`scripts/npm-audit.sh` now reads `scripts/npm-audit-allowlist.txt` (`GHSA-id | expires YYYY-MM-DD | reason`). A finding passes only when every vulnerability at or above the level is explained solely by live allowlisted advisories; any other advisory, an expired entry or a transport error still fails, and a pass prints a `::warning::`. The first entry tolerates GHSA-fc5q-39xc-vjpf (picomatch ReDoS, no patched release, build-time tooling only) until 2026-12-17. Shell tests added.
