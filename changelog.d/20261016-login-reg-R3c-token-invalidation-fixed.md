### Fixed — the response to a replayed refresh token rolled back with the refusal; a rotation could deadlock a credential change

When a revoked (or concurrently consumed) refresh token was presented, `refresh` revoked the user's whole token
family and then threw its 401 from the same transaction, so Spring rolled the revocation back with the
exception and the replacement token stayed usable. `refresh` is now a non-transactional orchestrator over a
transactional rotation that returns an outcome; the family revocation runs in its own transaction after the
rotation has ended and commits before the refusal is thrown. A refresh token that a credential change revoked is
now told apart from a replayed one (its issue instant is older than the account's last credential change) and is
refused without revoking the family: otherwise another device presenting its dead token after you changed your
password would sign out the device you just changed it on. The rotation also takes the account row lock before
the compare-and-set on the refresh row, the order every credential path uses; the reverse deadlocked against a
concurrent password change. Token issuance re-reads the stored password hash, credential version and enabled
flag under the same lock, so a login whose BCrypt check straddled a password change or a disable issues nothing
instead of a token for the new credentials or a refresh row after the revocation.
