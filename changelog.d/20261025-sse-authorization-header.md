### Fixed — event streams authenticate with the Authorization header

The status stream, run notifications and run progress used to carry the access token in the URL
(`?token=`), where nginx wrote it to its access and error logs. `createEventSource` now streams with
`fetch` and sends `Authorization: Bearer <token>`; the token is never a query parameter. Its contract
is unchanged (per-event handlers, `onError`, `closeOn`, a fresh token on every reconnect,
`reconnectOnVisible`, a 5 s retry), and `onError` now receives `{ status }` when the server answered,
so a 401 is visible. The backend still accepts both forms; the query form goes in a later release.
