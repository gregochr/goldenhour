### Security — event-stream URLs no longer accept an access token

`JwtAuthenticationFilter` now reads the access token from the `Authorization` header only, and
`StatusController` no longer reads a `token` query parameter. A `?token=` on `/api/status/stream`,
`/api/forecast/run/notifications` or `/api/forecast/run/{id}/progress` does not authenticate (401), so
no credential is left in any request line. Release note: a tab still running a bundle from before the
header-streaming change loses its streams (status shows DOWN, retrying every 5 s against a 401) until
it reloads; the PWA's `autoUpdate` swaps the bundle on the next load.
