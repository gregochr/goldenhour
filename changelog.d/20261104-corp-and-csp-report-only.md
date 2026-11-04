### Changed — Cross-Origin-Resource-Policy and a stricter CSP in report-only mode

API responses and every app-shell response from nginx now send
`Cross-Origin-Resource-Policy: same-origin`; nothing the app serves is loaded by another origin.
Cross-Origin-Embedder-Policy is deliberately not set, because the login page embeds a third-party
challenge iframe and the map loads third-party tiles. nginx also sends a second header,
`Content-Security-Policy-Report-Only`, carrying the current policy without `'unsafe-inline'` in
`style-src`, so any stylesheet violations show in the browser console without breaking a page; the
enforced policy is unchanged. The weekly ZAP gate summary now prints each alert's `alertRef`
(for example `[90004 / 90004-2]`) so exceptions can be keyed to the exact sub-alert.
