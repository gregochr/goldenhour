### Docs — the login and registration series is written up, and the privacy documents say what the code does

New `docs/app-store-privacy.md` inventories every kind of personal data the server and web client hold (email, username,
the optional home postcode and its coordinates, ratings, Ask questions, usage), which third parties receive what (postcodes.io,
OpenRouteService, Cloudflare Turnstile, the mail provider, Esri, Anthropic), what in-app account deletion removes and what it
keeps (the Ask question log survives unlinked for up to 90 days; server logs for 30), and the retention periods that exist in
code. `landing/privacy.html` is brought into line — it named SendGrid, omitted OpenRouteService and said nothing about deleting
an account — and `landing/faq.html` stops describing the postcode as a drive-time feature alone. The landing site is redeployed
by hand (`landing/README.md`), so neither page is live until then. `CLAUDE.md` (Auth, endpoints, roles, nine settings columns,
V179–V181, the integration-test count, the nginx check and the AASA path) and `docs/engineering/login-registration-plan.md`
(the status table, the build phases' deliberate disagreements, the Codex review log and the owner questions answered by what
shipped) are updated from the merged PRs.
