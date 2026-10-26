-- Admin invitations from the waitlist (audit-followups-plan W). The raw token is mailed and never stored; only its
-- SHA-256 hex (64 chars) is kept. UNIQUE allows many NULLs, so only invited rows carry a hash.
ALTER TABLE waitlist_email ADD COLUMN invite_token_hash VARCHAR(64);
ALTER TABLE waitlist_email ADD COLUMN invited_at TIMESTAMP;
ALTER TABLE waitlist_email ADD COLUMN invite_expires_at TIMESTAMP;
ALTER TABLE waitlist_email ADD COLUMN invite_used_at TIMESTAMP;
ALTER TABLE waitlist_email ADD CONSTRAINT uq_waitlist_email_invite_token_hash UNIQUE (invite_token_hash);
