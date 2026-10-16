### Added — a bounded fallback when Turnstile cannot load, on sign-in and change-password

Sign-in and the first-login password change used to answer 400 "CAPTCHA verification failed" to a
browser where the Turnstile script never loaded, although the sign-in page promised "we'll verify your
sign-in another way" — nothing did. `TurnstileGate` now decides: a token that verifies proceeds as
before; a token that fails is still a 400 and is never a fallback; a request with no token at all is
admitted only inside three in-memory bounds (`AuthAttemptLimiter`, Caffeine, fixed windows from the first
attempt), checked outermost first — an hourly ceiling per endpoint (sign-in 60, change-password 20), the
client address (20 per 15 minutes, read as `CF-Connecting-IP`, else the first `X-Forwarded-For` hop, else
the connection address) and the identifier (5 per 15 minutes) — plus the account's own five attempts per
15 minutes once the username resolves to a user. Any bound reached answers 429 "Too many attempts. Please
wait a few minutes and try again." Registration deliberately has no fallback: it cannot tell a blocked
browser from a bot that omits the token, and each sign-up counts against the early-access cap. With no
Turnstile secret configured (local development) nothing changes and no counter is touched. Counters
live in memory, so a restart forgets them.
