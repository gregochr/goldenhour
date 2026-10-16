### Changed — nginx: geolocation for the page itself, the Apple association file, and a syntax check in CI

`nginx.conf` now sends `Permissions-Policy: geolocation=(self), …` on every response that sets it
(it read `geolocation=()`, under which `navigator.geolocation` rejects in the document, so the
registration and sign-in prompt's "Use my location" could never work), and serves
`/.well-known/apple-app-site-association` as `application/json` from an exact-match location that
repeats the security headers. The file itself (`frontend/public/.well-known/`) names the four
`/verify` and `/reset` link shapes with the literal `TEAMID.BUNDLEID` placeholder until the owner
has the Apple team and bundle ids. The frontend CI job gains an `nginx -t` step (pinned
`nginx:1.27-alpine`, the compose-only upstream stubbed to `127.0.0.1:8082`), because until now
nothing checked the file's syntax before a deploy recreated the container with it. Two Vitest files
pin the AASA content and the nginx directives. An installed PWA picks the header up with its next
`index.html` revision.
