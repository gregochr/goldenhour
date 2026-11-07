### Added — a Playwright smoke suite that blocks merging

CI has a new required check, "E2E — Playwright smoke". It boots the real backend (local profile, a fresh H2
database, a fake Anthropic key, Ask on its stub) and the built frontend, and drives Chromium through the login
form, logout, the Plan matrix, Coming up, Operations, Ask PhotoCast's box, a phone-width tab bar and the
"no scheduled jobs on a fresh database" invariant. Every test also fails on a console error, a page error, a
non-2xx response from the app or an unexpected off-origin request. Run it locally with
`cd frontend && npm run test:e2e:smoke` (recipe in `docs/engineering/reference/dev-and-testing.md`).
