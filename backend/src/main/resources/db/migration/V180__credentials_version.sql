-- Token invalidation (login-registration plan §2 #13).
--
-- credentials_version is a monotonic counter bumped by one, in the same statement that stamps
-- credentials_changed_at, by every credential change (password change or reset, admin temporary
-- password, activation, disable). The access token carries it as the `cv` claim and the JWT filter
-- requires it to EQUAL the row's current value, so a change signs every older access token out at its
-- next request. An epoch-millisecond stamp could collide for two serialised changes in one millisecond;
-- a counter cannot.
--
-- credentials_changed_at is kept beside it as the human-readable stamp and as the input of the filter's
-- rule for tokens issued before this migration (they carry no `cv`).
--
-- No backfill: a null stamp and version 0 mean "never changed since these columns existed", so every
-- token already issued stays valid until it expires.
ALTER TABLE app_user ADD COLUMN credentials_changed_at TIMESTAMPTZ;
ALTER TABLE app_user ADD COLUMN credentials_version BIGINT NOT NULL DEFAULT 0;
