### Changed — patched glob and minimatch for frontend build and lint tooling

The frontend `package.json` `overrides` now pin `glob` 14.0.3 under `workbox-build` (used by
`vite-plugin-pwa`) and `minimatch` 11.0.4 under `eslint` and `@eslint/config-array`. This clears
GHSA-3g3m-2fjr-6pw4 and GHSA-g3hv-75wg-m249 in dev and build tooling, which is never shipped to the
browser. The other `minimatch` copies (3.x, 5.x) are outside the affected range and are unchanged.
