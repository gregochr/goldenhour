### Added — Marketing email opt-out in Settings

Settings has a new Emails section, for every role, with one checkbox ("Send me occasional emails about new features and photography tips") so a reader can withdraw marketing consent as easily as they gave it. `GET /api/user/settings` now returns `marketingEmailOptIn`, and an account created by an admin or the local seeder no longer defaults to consenting. Existing accounts are unchanged.
