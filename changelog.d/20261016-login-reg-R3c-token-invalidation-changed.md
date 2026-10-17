### Changed — admin disable, email edit, resend and create now void what they should; credential columns are written only by scoped updates

An admin's disable now ends every session at once (version bump, refresh tokens revoked, unused reset links and
every verification link deleted) and re-enabling revives none of it. An admin's email edit now locks the
account row, claims the new address in the reservation table (a case variant of another account's address is
a 409 "Email already registered"), releases the old reservation if no account still uses it, and deletes the
account's unused reset links and verification links, so a link mailed to the old mailbox cannot be used after
the move; for a pending registration the response says to resend verification. Both resend-verification routes
(the reader's and the admin's) now go through one issuer (the admin's route answers 400 "Account is not awaiting
email verification" for an account that is no longer pending, where it used to send a link to anyone) that locks the account row and mails the STORED
address, so a resend that raced an email edit can no longer mail the old mailbox. The admin's temporary-password
reset is mailed to the address read under the lock after the change. Admin user creation and email edit now
refuse a non-ASCII address, as registration does (the reservation key is lower-cased in Java, the database's
`LOWER()` may fold such characters differently). Registration's activation hashes the password before the one
transaction that consumes the token and activates the account, re-checking under the row lock that the account
is still pending, so two verification links cannot overwrite each other's password. The `password`, `enabled`,
`passwordChangeRequired` and `role` columns, and the two new credential columns, are no longer written by a
whole-entity `save()`: login's and the JWT filter's last-active stamps, the marketing opt-in, the admin role
and enabled changes are now column-scoped updates, so a stale copy of the row can no longer write an old hash,
flag or role back over a change that committed meanwhile.
