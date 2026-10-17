### Added — the one-time sign-in postcode prompt's server side, and "Use my location"'s reverse lookup

`GET /api/user/settings` now carries `postcodePromptDismissedAt` and a server-computed
`postcodePromptDue` — true exactly when the account has no home postcode and has never dismissed the
prompt — on every response the settings service builds (V181 `app_user.postcode_prompt_dismissed_at`,
nullable, no backfill, so an existing account without a postcode is asked once). `PUT
/api/user/settings/postcode-prompt-dismissed` (no body, any role, idempotent) records "Not now" with
the server's clock through a column-scoped update and answers the settings with the flag false.
`POST /api/user/settings/home/reverse` `{latitude, longitude}` finds the nearest UK postcode within
postcodes.io's 2000 m maximum radius (its default of 100 m misses most rural homes): 400 for a missing,
non-finite or out-of-range value and for a point with no postcode near it, 502 when postcodes.io is
down, any role, never cached. No client uses either endpoint yet; the prompt and the button follow.
