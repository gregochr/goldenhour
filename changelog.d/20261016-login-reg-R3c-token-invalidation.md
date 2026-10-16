### Added — a password change, reset, disable or deletion now ends the account's access tokens at their next request

Access tokens used to authenticate from their own claims alone, so a deleted or disabled account's token kept
working for up to 24 hours, a password reset signed other devices out only when their token expired, and a
demoted admin's old token kept admin rights. Each account now has a credential version (`credentials_version`,
with `credentials_changed_at` beside it; V180), a counter bumped by every credential change, and every access
token carries the account's id (`uid`) and the version it was issued at (`cv`). The JWT filter loads the
account once per request and authenticates a token only when the row exists and is enabled, the `uid` is the
row's id and the `cv` equals the row's current version; the authority comes from the ROW's role, so a demotion
takes effect at the next request. A token issued before this release (no `uid`/`cv`) is accepted by name for
its remaining lifetime unless it predates the account's last credential change. Changing a password through
`POST /api/auth/change-password` now answers with fresh tokens for the device that made the change (the same
body as `/login` plus `message`), so that device stays signed in while every other session is signed out; a
request from a session that was superseded while the new password was being hashed is refused with 401 "Your
session has changed. Sign in again." and changes nothing. One transactional writer
(`CredentialChangeWriter`) now applies every credential change — change-password, a redeemed reset link, an
admin's temporary password, activation, an admin's disable — so the password, the version bump, the first-login
flag, the enabled flag, the revocation of every refresh token and the deletion of the account's unused reset
links commit together or not at all.
