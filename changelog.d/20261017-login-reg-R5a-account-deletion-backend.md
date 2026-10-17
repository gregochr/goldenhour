### Added — `DELETE /api/user/account`: a signed-in reader can delete their own account (backend only)

`DELETE /api/user/account` with `{"password": "…"}` deletes the caller's own account, for every role. It answers
400 `Password is required` for a missing or empty password, 403 `Password is incorrect` for a mismatch, 409
`Admin accounts are deleted by another admin` for an administrator (the existing self-delete refusal, extended:
it keeps the sole admin from locking the instance out — another admin deletes an admin), 429 once five attempts
in fifteen minutes have been counted on `AuthAttemptLimiter` under `delete:<username>` (counted before the
password is compared), 401 `Your session has changed. Sign in again.` when the account's credentials moved while
the password was being checked, and 204 on success. The password is checked by BCrypt outside any transaction;
`UserService.deleteOwnAccount` then locks the user row FIRST, reads the account as a scalar projection under the
lock (never the entity the request's persistence context holds from before the hash — Open Session In View is
on), refuses unless the stored hash is still the one that was verified, the credential version still equals the
session's `cv` (or, for a token with none, is still 0) and the role is still not ADMIN, purges the account's
drive times, and deletes by a compare-and-set whose row count must be one — zero rolls the purge back. A stolen
session that finished its BCrypt just before the owner's password change committed therefore deletes nothing,
and an administrator who promoted the account meanwhile wins. The account's email claim is released if no other
account still uses the address, and the access token dies with the row (the JWT filter authenticates only live
rows). No Settings control yet: that is the next phase.

`AccountDeletionIntegrationTest` (CI-only, Postgres + Flyway) deletes a user holding a row in every table that
references `app_user` and shows that a delete without the purge is refused by the foreign key, that with it the
cascades fire, and that the `ask_log` row survives with a null user.
