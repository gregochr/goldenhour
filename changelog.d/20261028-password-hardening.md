### Fixed — change-password re-verifies the current password, the first-login gate is enforced by the server, and the published default admin cannot sign in outside local

`POST /api/auth/change-password` now requires `currentPassword`: a briefly stolen access token can no longer
become a permanent takeover. A missing or wrong current password is a 400 (never a 401, which the client would
refresh and retry), a new password equal to the current one is a 400, and the attempt is counted against a
per-account budget of five per quarter hour on every attempt (429 when spent). The change-password page asks
for the current (temporary) password above the new one.

`passwordChangeRequired` is no longer honoured only by the client: a new `PasswordChangeRequiredFilter` answers
403 `PASSWORD_CHANGE_REQUIRED` on every `/api/` call except `/api/auth/**` and `/api/waitlist` while the flag is
set, and the axios interceptor turns that 403 into an event that routes a stale client to the change-password
page.

The seeded `admin` / `golden2026` account (`V10`, published in the README) is now neutralised at startup on
every profile except `local` by `DefaultCredentialGuard` (credential version bumped, refresh tokens revoked,
flag set), and `UserService`'s empty-table seed moved to a `local`-only `LocalAdminSeeder`. A login against a
stored hash that is not BCrypt-shaped spends a dummy BCrypt compare so its timing matches. Recovery steps are in
`DEVOPS.md`.
