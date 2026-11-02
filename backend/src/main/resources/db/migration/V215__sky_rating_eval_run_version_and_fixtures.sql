-- V215: Sky Eval runs record the version they ran on, and which fixtures they scored.
--
-- git_commit_message is the subject line of the commit the running build was made from (the redesigned
-- Sky Eval page names a version by its sha and message). Historic rows stay NULL and read as "unknown
-- version" (their git_commit_hash is the empty string, because the production image has no .git).
--
-- fixture_names is the comma-separated fixture names a manual quick check scored; NULL means every
-- fixture (the weekly run, and every row before this migration).
ALTER TABLE sky_rating_eval_run ADD COLUMN git_commit_message VARCHAR(500);
ALTER TABLE sky_rating_eval_run ADD COLUMN fixture_names TEXT;
