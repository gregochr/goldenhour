### Testing — the nginx smoke stack runs on the host network

The `E2E — Playwright smoke` check (and the advisory a11y workflow) intermittently failed on the first test after the
nginx container started, with `net::ERR_NETWORK_CHANGED` on `/registerSW.js` and `/assets/*.js`. `docker run` on the
default bridge attaches a veth to `docker0` just after the container answers, and Chromium aborts in-flight requests
when the host's interfaces change. The container now runs with `--network host`, so no interface is created or changed.
In `E2E_WEB=nginx` mode the web port is therefore 80 (any other `E2E_WEB_PORT` is refused) and the backend allows the
`http://127.0.0.1` CORS origin. Default `vite preview` mode on 4173 is unchanged.
