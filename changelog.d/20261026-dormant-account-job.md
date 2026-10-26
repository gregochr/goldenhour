### Added — dormant-account sweep (paused, dry-run by default)

A new `dormant_account_sweep` job warns a Lite reader who has not used PhotoCast for 90 days, and deletes the
account 14 days later if they still have not signed in. A sign-in, a promotion to Pro or Admin, or a disable
after the warning cancels the deletion; Pro, Admin, disabled, pending and address-less accounts are never
touched. The warning is stamped (`app_user.dormancy_warning_sent_at`, V193) only after the mail sender accepted
it, the delete re-checks every condition under the row lock, and nothing is sent or deleted when no mail sender
is configured. The job is seeded PAUSED and `photocast.dormancy.dry-run` defaults to true, so even "Run now"
only logs would-warn and would-delete projections until the owner sets `dry-run: false`. Logs carry account ids
only, and "Run now" now logs the admin who triggered it.
