### Fixed — verification and password-reset links carry their token in the fragment

The emailed verification and reset links are now `/verify#token=…` and `/reset#token=…`. A URL
fragment is never sent to the server, so the one-time token no longer appears in the nginx access
log, Cloudflare's request line or a `Referer`. The app still reads the older `?token=` form so links
already in inboxes keep working for their lifetime (24 h verify, 1 h reset); removing that fallback
is a follow-up once a release carrying this change has been live for more than 24 h.
