### Fixed — frontend `npm audit`: source-map-js 1.2.1 → 1.2.2

A new high-severity advisory (GHSA-68fv-2mgg-jv7q, event-loop denial of service through indexed
source-map section offsets) against the transitive dev dependency `source-map-js` was failing the
Frontend CI job's audit step on every pull request. The lockfile's three lines (version, resolved,
integrity) are edited by hand, never `npm audit fix`; `rm -rf node_modules && npm ci` leaves the
lockfile unchanged and the audit reports 0 vulnerabilities.
