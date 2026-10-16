### Added — "forgot my password": an emailed one-hour reset link, redeemed for a new password

`POST /api/auth/forgot-password` takes `{email, turnstileToken?}` through the same Turnstile gate and bounded
fallback as sign-in, and answers the identical 200 "If that email has an account, we've sent a reset link."
for every well-formed address — known, unknown, rate-limited or failing inside — because any difference
would say the account exists. A known, active account (enabled, holding a password, exactly one match for
the address) is issued a link under its row lock: its earlier unused links are deleted, a token is stored
as a SHA-256 hash in the new `password_reset_token` table (V179) and expires in one hour, and the mail
(`password-reset-link-email`, subject "Reset your PhotoCast password") goes to the account's STORED address,
never the request's text. Each account gets three requests per 15 minutes; a fourth is dropped silently.
`POST /api/auth/reset-password` takes `{token, password}`, runs the password complexity check, and then, in
one transaction after the password has been hashed, locks the account, refuses a disabled or pending one,
consumes the token by compare-and-set, sets the password, clears the first-login gate and revokes every
active refresh token; the caller is signed in on the device that reset, with the same body as `/login`.
An unknown, used or expired link is a 400 with its own sentence; three bounds (a global hourly ceiling, the
client address, the token) answer 429 like set-password. Other devices' access tokens still run out at
their 24 hours until token invalidation (R3c) lands. The older admin temporary-password reset is untouched.
