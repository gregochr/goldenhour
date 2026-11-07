### Fixed — dev dependencies: clear the qs and minimatch advisories under Stryker

Added `overrides` in `frontend/package.json` so `@stryker-mutator/core` uses `minimatch` 11.0.4 (already pinned for eslint) and `typed-rest-client` uses `qs` 6.16.1. This clears the moderate npm audit advisories. Dev-only: nothing shipped changes.
