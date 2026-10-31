### Security — bump follow-redirects past GHSA-mrwp-f3q8-3g9p

`follow-redirects` 1.16.1 (transitive, via axios) carried a high-severity advisory (GHSA-mrwp-f3q8-3g9p, affecting 1.16.0 and 1.16.1) that failed the frontend `npm audit` CI step. The lockfile entry is now 1.16.2; no other package moved.
