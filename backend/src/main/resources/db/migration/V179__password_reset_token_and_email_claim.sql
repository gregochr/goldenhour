-- Password reset links and normalised-email reservations (login-registration plan §2 #1, §5 #5, §5 #6).
--
-- password_reset_token: the emailed reset link's proof. A SIBLING of email_verification_token, not rows
-- in it: a verification token is deleted on use and proves an address, a reset token is kept with
-- used_at and proves a request, and the admin's resend path deletes "unverified" tokens by user.
--   token_hash  SHA-256 hex of the raw token (the raw token is never stored), UNIQUE
--   expires_at  one hour after issue
--   used_at     NULL until the link is redeemed; set by a compare-and-set (UPDATE ... WHERE used_at IS
--               NULL) so two concurrent redemptions of one token cannot both succeed
-- ON DELETE CASCADE on user_id: deleting a user must not be blocked by, or leave behind, their links.
CREATE TABLE password_reset_token (
    id         BIGSERIAL    PRIMARY KEY,
    token_hash VARCHAR(64)  NOT NULL,
    user_id    BIGINT       NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ  NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_password_reset_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_password_reset_token_user_created ON password_reset_token (user_id, created_at);

-- app_user_email_claim: a RESERVATION of a lower-cased email address, owned by no one account and with NO
-- cascade. app_user.email is unique but case-SENSITIVE (V33), so Foo@x and foo@x can both be registered;
-- a UNIQUE INDEX ON LOWER(email) would fail this migration if two legacy rows already differ only by case.
-- Every write of an address claims its lower-cased key in the same transaction as the account write
-- (EmailClaims); the primary key refuses a second claimant of a brand-new key race-free, and a key whose
-- only users are gone is an orphan that the next claimant reuses.
-- The backfill gives a legacy case-duplicate pair ONE shared reservation (DISTINCT), which the sign-in
-- lookup already tolerates: two active matches are no match.
CREATE TABLE app_user_email_claim (
    email_lower VARCHAR(255) NOT NULL,
    claimed_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_app_user_email_claim PRIMARY KEY (email_lower)
);

INSERT INTO app_user_email_claim (email_lower, claimed_at)
SELECT DISTINCT LOWER(email), now()
FROM app_user
WHERE email IS NOT NULL;
