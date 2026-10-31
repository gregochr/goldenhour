-- Consent to marketing email is an active opt-in (UK GDPR / PECR): an account created without an
-- explicit choice (admin-created, locally seeded) has NOT consented. Change the column DEFAULT only.
-- Existing rows are deliberately untouched (no UPDATE): what to do about accounts that hold the old
-- default of TRUE is an owner decision, still pending.
ALTER TABLE app_user ALTER COLUMN marketing_email_opt_in SET DEFAULT FALSE;
