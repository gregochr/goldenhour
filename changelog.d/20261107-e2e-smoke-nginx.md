### Testing — the required Playwright smoke now runs through the production nginx config

The `E2E — Playwright smoke` job serves the built frontend through the repo-root `nginx.conf` in an
`nginx:alpine` container (mounted unedited, as `docker-compose.yml` does), in front of the real backend on 8082,
instead of `vite preview`. Every existing journey is now also a test of the strict CSP, and a new
`nginx.spec.js` asserts the security headers, the caching rules, the SPA fallback, the missing-asset 404, the
nomodule script's CSP hash and the SSE status stream through the proxy, all read from the mounted config file.
`E2E_WEB=nginx` selects the mode (CI sets it); local runs keep `vite preview` and need no Docker.
