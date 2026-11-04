### Added — frontend unit-test coverage floor in CI

The frontend CI job now runs Vitest with v8 coverage (`npm run test:coverage`) and fails a PR that
drops global coverage below the level measured on 2026-11-04: 97% lines, 96% statements, 96%
functions, 93% branches. The floor lives in `test.coverage.thresholds` in `frontend/vite.config.js`
and is only ever raised. `npm test` is unchanged. Dependabot now bumps `vitest` and `@vitest/*`
together, and the landing page's "how it's built" copy says the app's tests have a coverage minimum.
