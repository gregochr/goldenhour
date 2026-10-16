### Fixed — the verification-resend limit could never trip; emails differing only by case could both register

`resendVerification` counted the user's recent tokens and then deleted every unverified one before inserting
a new one, so the count was never above one and the "3 in 5 minutes" limit never fired. Resend now also
takes three per pending account per 15 minutes from the in-memory attempt limiter (the database count stays
as a second check), and finds the pending account with an indexed lookup instead of loading every user and
comparing with Java `equalsIgnoreCase` — which folded `ı` to `I` and then mailed the TYPED address, so a
confusable address could receive another person's verification link; the mail now goes to the stored
address. Email uniqueness was case-sensitive (`Foo@x` and `foo@x` could both register): a new
`app_user_email_claim` reservation table (V179, backfilled from the existing addresses, one reservation for
a legacy case-duplicate pair) is claimed in the same transaction as every new account, so the database
refuses the second claimant. A retried sign-up for an abandoned pending registration now locks the old row
and deletes it only if it is still pending, so it can no longer erase an account that was activated in the
meantime.
