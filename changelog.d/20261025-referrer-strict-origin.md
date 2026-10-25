### Fixed — origin-only referrers, and one-time link tokens leave the address before the app renders

`Referrer-Policy` is now `strict-origin` on every response (and as a `<meta name="referrer">` in
`index.html`, for a precached page that replays old headers), so a same-origin request no longer
repeats the full page URL. A new `takeLinkToken()` reads the `token` of an emailed verification or
reset link once per page load, before React renders, and rewrites the address without it, keeping
the path, other query parameters and the rest of the fragment. It already reads `#token=` ahead of
`?token=`. A reload after the link has opened no longer re-reads the token: a reset reader
re-clicks the email link.
