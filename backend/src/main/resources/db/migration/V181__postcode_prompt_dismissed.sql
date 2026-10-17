-- V181: the one-time sign-in postcode prompt's only stored state (login-registration plan §2 #3).
--
-- An account with no home postcode is asked for one once, when the app opens; "Not now" records the
-- instant here so the question is not asked again. The prompt is due when the account has no
-- postcode AND no dismissal, which the server computes (UserSettingsService) and serves as
-- postcodePromptDue on GET /api/user/settings, so a client never re-derives it.
--
-- Nullable, no default, no backfill, deliberately — the V152 coming_up_last_seen_at pattern. NULL
-- means "never dismissed", which is exactly the spec's "existing users without a postcode see it
-- once"; a backfill of now would be a false claim that every existing account declined a question
-- it was never asked. TIMESTAMP WITH TIME ZONE like its sibling: the instant is the durable truth.

ALTER TABLE app_user ADD COLUMN postcode_prompt_dismissed_at TIMESTAMP WITH TIME ZONE;
