### Security — the web-server access log no longer records query strings

nginx wrote the full request line to its access log, so every one-time link (`/verify?token=`,
`/reset?token=`) and every stream request (`/api/status/stream?token=`, which carries the signed-in
reader's access token) landed in the container log, and the Referer of each follow-up request
repeated the one-time link. Both configs (the app's `nginx.conf` and `landing/nginx.conf`) now log
through a `redacted` format that writes the path only and the Referer without its query or
fragment. A missing asset no longer writes an error-log line (which would record the page's Referer),
and the `goldenhour-frontend` and `landing` containers now carry the same 10 MB x 3 `json-file` log
limit as the database and backend, so the container log is bounded.

CI now runs `nginx:alpine` with each config, sends requests with canary tokens, and fails if one
reaches the access log; `landing/nginx.conf` also gets its first `nginx -t`. Residual, fixed by later
steps of the plan: nginx's error log cannot be reformatted, so an upstream error on a stream or an
`/api/auth/*` call can still write the request line or Referer there.
