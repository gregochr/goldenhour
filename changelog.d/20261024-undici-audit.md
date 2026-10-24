### Changed — frontend dev dependency undici bumped past the 8.12.1 advisories

The transitive dev dependency `undici` (pulled in by `jsdom`) moves from 8.10.2 to 8.12.2 in the
lockfile, clearing the high-severity EventSource, SRI and content-encoding advisories that were failing
`npm audit` in the frontend CI job. No runtime code is affected.
