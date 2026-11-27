### Changed — the Playwright browser cache key follows package.json

The E2E smoke and accessibility workflows now read the `@playwright/test` version from `frontend/package.json` to key the browser cache, and fail with a clear error if it is not an exact version. The key used to be a hard-coded literal that was missed on a Playwright bump, so CI restored the old browser, skipped the install and every E2E test failed.
