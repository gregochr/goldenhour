### Docs — login and registration enhancements: the port plan

`docs/engineering/login-registration-plan.md` plans the owner's "PhotoCast Registration" design
bundle (vendored verbatim at `docs/design/login-registration/`): a `HOME` postcode step in
registration and a one-time sign-in prompt for accounts with no postcode, both with "Use my
location"; the auth screens made to work on phones and to match the rest of the app; marketing
consent opt-in; "Confirm email" and the second Turnstile removed; sign-in by username or email, a
forgot-password flow and a backend fallback for a Turnstile that cannot load; in-app account
deletion; and the native-app prep (`apple-app-site-association`, clean `/verify` and `/reset`
links, a privacy inventory). §1 answers the bundle's five "check first" questions (no reset flow
exists; deletion is admin-only and `user_drive_time` has no cascade; set-password verifies
Turnstile; the login fallback has no backend at all; emails are unique but case-sensitive) and §3
cuts the work into nine Sonnet phases. No code.
