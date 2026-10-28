### Fixed — sign-in is refused after ten failed attempts per account on every login path

A login carrying a verified Turnstile token, and any login while Turnstile is not configured, had no
per-account bound at all; only the tokenless fallback took the account's five-attempt budget. Login now
counts FAILED sign-ins per account (a wrong password; a correct one, even on a disabled account, and a
successful sign-in never count) and answers 429 once an account has had ten in a quarter hour, before any
password is compared. The fallback keeps its own five-attempt budget on top. Counters are in memory, so a
backend restart clears a lockout; a locked-out admin can also wait out the window (`DEVOPS.md`).
