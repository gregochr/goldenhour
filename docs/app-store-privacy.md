# PhotoCast — privacy inventory for the App Store (and the public privacy page)

**Written 2026-10-17 UTC, after in-app account deletion existed, from the code on `main` at `979d8275` (login/registration
series R5b) plus R7.** It describes what the *server and the web client in this repository* do. No native app code is in this
repository: the iOS app, when it exists, calls the same API, so this is the inventory its App Store privacy questionnaire is
answered from. It is also the source for `landing/privacy.html` (which is redeployed with `./release-landing.sh` — `landing/README.md`).

**How to use it.** Every row names the table and column, or the class, that does the thing, so a reviewer can check a claim by
opening the file. Where a fact is *not* provable from this repository it says **not verified** — nothing here is a promise the
code does not keep. If the code changes, change this file in the same commit: a privacy statement that drifts from the code is
worse than none. Plan: `docs/engineering/login-registration-plan.md` §2 #11.

**What is not in this repository and was not checked:** the *running* production configuration (the host's config is
hand-edited; this inventory reads the committed `application-prod.yml`), whether Ask PhotoCast is switched on in production
(`photocast.ask.enabled` is **false** by default in code), the mail provider's own retention, Cloudflare's, and nginx's
access-log retention inside the frontend container.

---

## 1. The short version

- **Tracking: none.** `frontend/package.json` has no analytics, advertising or crash-reporting package; the backend is
  stateless (`SecurityConfig`: sessions and CSRF disabled), so our server sets no cookie, and the app keeps its session in
  `localStorage` (below). (Cloudflare's Turnstile frame is Cloudflare's own.) Nothing is sold or shared for advertising.
- **Account data** is an email address, a username and a one-way (BCrypt) password hash. Marketing email is opt-in and, in
  the code today, is only a stored flag: nothing in the application sends marketing email. ⚠️ **The stored flag is only a
  consent for accounts that registered themselves.** The column defaults to TRUE (V35, `AppUserEntity`'s `@Builder.Default`),
  and accounts an administrator creates (`UserService.createUser`, which never sets it), the seeded admin and every account
  that existed before V35 hold TRUE with no recorded consent. Nothing reads the flag to send mail today; before any sender is
  built, the owner must decide what those TRUE values mean.
- **Home location** is optional: a UK postcode and the coordinates it resolves to. It is the only location the server stores.
  "Use my location" sends the device's coordinates to our server **once** to find the nearest postcode; **the device
  coordinates are not stored in the database** — only the resolved postcode's own coordinates are, if the reader saves it.
  One caveat (§4): if the postcodes.io request fails at network level, the error text the server logs (30 days) can include
  the request URL (Spring's I/O error text normally names it; **not reproduced here**), and so those coordinates (`PostcodesIoClient`, the WARN lines in `lookup` and `reverseLookup`; the code
  comment claiming "no coordinates in the log" holds for the message the code writes, not for the exception text it appends).
- **Account deletion is in the app** (Settings → Danger zone, `DELETE /api/user/account`, password required). It removes the
  account and almost everything keyed to it. What it does **not** remove is listed in §4 — read that before you describe
  deletion to anyone as "removes all data".

---

## 2. What is collected, where it lives, and the App Store category

"Linked" means the stored data carries the account (`user_id`, username or email). Apple's category names are used for the
questionnaire; where Apple's own definition decides the answer and this repository cannot, the row says so.

| Apple data type | What exactly | Stored in | Linked? | Purpose | Kept until |
|---|---|---|---|---|---|
| **Contact Info → Email Address** | the address typed at registration, or set by an administrator | `app_user.email`; a lower-cased reservation of it in `app_user_email_claim.email_lower` (owned by no account, no foreign key) | yes | account, verification and password-reset mail; deletion confirmation; marketing **only** if the reader opted in | account deleted (the reservation is released when no other account uses the address) |
| **Identifiers → User ID** | the username (3–30 letters, digits, `-`, `_`) and the numeric account id (carried in the access token as `uid`) | `app_user.username`, `app_user.id` | yes | account, sign-in | account deleted |
| **Location → (see note)** | an optional UK home postcode, its latitude/longitude, a local radius in miles | `app_user.home_postcode`, `home_latitude`, `home_longitude`, `local_radius_miles`; drive times derived from it in `user_drive_time` | yes | sunrise/sunset light times for the reader's place; drive times and the "within reach" lens; "close to home" in Ask | changed or account deleted |
| **User Content → Other User Content** | outcome ratings the reader gives to a past sunrise or sunset (a location, a date, an event, 1–5 stars or "didn't see it", plus which forecast they had been shown); the question text a reader types into Ask PhotoCast | `outcome_rating`; `ask_log.normalised_question` (only for the two outcomes the engine answered, `CLAUDE_OK` and `CLAUDE_CANT`) | ratings: yes; Ask question: linked while the account exists, **unlinked after deletion** (§4) | the calibration of forecasts; Ask | ratings: account deleted; Ask question rows: 90 days |
| **User Content → Other User Content (legacy)** | observations recorded through the OLD outcome API, `POST /api/outcome` (any authenticated role; no screen in the app since 2026-02-27): location name and coordinates, date, sunrise/sunset, whether the reader went out, a 1–5 rating, two 0–100 observation scores and a FREE-TEXT note | `actual_outcome` — **no user column, no foreign key to `app_user`** | **no** — a row cannot be traced to the account that wrote it | the calibration gate's original input (`ForecastCalibrationService`) | **indefinitely** — no cleanup job, and account deletion cannot reach a row it cannot attribute. **Readable by every authenticated account** (`GET /api/outcome`, `GET /api/outcome/all` return the notes). Production holds no row as of 2026-10-02 (CLAUDE.md, read-only check) — not re-verified here. Owner decision: retire or gate the route, or accept and disclose (disclosed on the landing page) |
| **Usage Data → Product Interaction** | when the account last used the app (`last_active_at`, written at most once an hour); how many Ask questions it has asked today; one outcome row per answered Ask request (scope, view, outcome, duration — no text except as above) | `app_user.last_active_at`; `ask_usage`; `ask_log` | `last_active_at` and `ask_usage`: yes; `ask_log`: linked while the account exists | service operation and cost control | `ask_usage` and `last_active_at`: account deleted; `ask_log`: 90 days |
| **Other** (not an Apple type) — consent records | whether the reader opted in to marketing email; when they accepted the Terms and which version | `app_user.marketing_email_opt_in`, `terms_accepted_at`, `terms_version` | yes | consent record | account deleted |

**Who can see it.** The reader sees their own data. Administrators (the Operations tab) can list accounts — username, email,
role, enabled state, last-active time, marketing flag (`GET /api/users`) — read the waitlist (`GET /api/admin/waitlist`), read
every reader's rating of a slot (`GET /api/outcome/ratings/all`); whoever operates the host can read the logs, backups and database. A reader's
home postcode and coordinates are served only to that reader (`/api/user/settings*` is never cached or shared). Ask metrics
(`GET /api/admin/ask/metrics`) never return a question, raw or normalised. Other readers see none of it — with one exception: the legacy `actual_outcome` rows above, which any authenticated account can read in full, notes included. **Other `app_user`
columns:** `role`, `created_at`, `enabled`, the map colour and tide preferences, `coming_up_last_seen_at` (when the Coming up
tab was last opened), `postcode_prompt_dismissed_at` (when the sign-in postcode prompt was dismissed) and the credential counter
`credentials_version` / `credentials_changed_at` — account administration and preferences, linked, kept until deletion.
Usernames are 3–30 letters, digits, `-`, `_` for self-registration (`AuthController`'s `USERNAME_PATTERN`); an administrator
can create others.

**Note on the Location row.** The coordinates the app saves are the postcode's own, taken from the postcodes.io result
(`home_latitude`/`home_longitude` are `Double` columns — more than three decimal places), not the device's position. Whether
Apple counts that as *Precise Location* or *Coarse Location* is Apple's definition to apply (a postcode centroid names a
street, not a door); **this repository cannot decide it, and the owner should choose the more conservative answer unless Apple's
current definition clearly says otherwise.** "Physical Address" is the other category a reviewer may consider.

**Credentials (not an Apple data type, disclosed for completeness).** `app_user.password` is a BCrypt hash. Refresh tokens
(`refresh_token`) and the one-time verification and reset tokens (`email_verification_token`, `password_reset_token`) are stored
as SHA-256 hashes; the raw value exists in the mailed link and on the device. The links are `/verify#token=` and
`/reset#token=` on the app origin: the token sits in the URL fragment, which the browser never sends, so it reaches neither
Cloudflare nor the nginx log nor a `Referer`; it does remain in the browser's history list, and a mail provider's link
scanner or click tracker that rewrites the link carries the whole URL, fragment included (links mailed before this change
used `?token=` and expire within 24 h; nginx logs the path only since `token-log-redaction-plan.md` step 1); the event-stream paths also accept the access token as `?token=` (`JwtAuthenticationFilter`). On the device the app keeps its session in the
browser's `localStorage` (access and refresh tokens, role, username, a session-expiry date and the marketing flag —
`frontend/src/context/AuthContext.jsx`). Access tokens last 24 hours and refresh tokens 30 days
(`application-prod.yml`); **expired refresh tokens are deleted nightly** (`refresh_token_cleanup`, 03:00 UTC), but no job was
found that prunes expired verification or reset-link rows (they hold a hash, never an address).

**Waitlist.** When the early-access registration cap is full, the registration page offers a waitlist and the address typed is
stored in `waitlist_email` (`email`, `submitted_at`). It is not linked to any account, an administrator can list it
(`GET /api/admin/waitlist`), and **no code deletes a row** — removal is by request to privacy@photocast.online.

**Pending registrations.** A registration that is never verified leaves an `app_user` row (disabled, no password) holding the
username and address; no job was found that removes abandoned ones. Deleting such an account is an administrator action.

**IP addresses.** The server reads the caller's address (`CF-Connecting-IP`, else the first `X-Forwarded-For` hop, else the
socket address — `ClientAddress`) only to key the in-memory attempt limiter (`AuthAttemptLimiter`; entries expire within one
hour; a restart forgets them). It is **not** written to the application log and **not** stored in the database. The frontend
container's nginx writes an access log (`nginx.conf`: `access_log /var/log/nginx/access.log`) whose retention is not defined in
this repository — **not verified**. Cloudflare, which fronts `photocast.online` and `app.photocast.online` and terminates TLS, handles
the requests and responses in transit — IP address, request metadata **and content** (the password typed at sign-in, tokens,
postcodes, Ask questions) — as any TLS-terminating proxy does; its own retention is not verified.

**Classification flags (owner decisions, Apple's definitions decide).** The Ask question text is listed as *Other User Content*;
Apple could read it as *Search History*. Whether data sent off-device and not retained (the one-off device coordinates) counts
as "collected" is Apple's definition to apply — and the logging caveat in §1 means it may be retained for up to 30 days in an
error line. IP addresses and the usernames/emails in server logs (§4) are not mapped to an Apple type here: *Diagnostics* or
*Identifiers* are the candidates. "Tracking: none" is a conclusion about this repository (no advertising or analytics package,
no data broker); anything the native app adds is outside it.

**Not collected:** name, phone number, contacts, photos, device identifiers, advertising identifiers, payment details (no
payment integration exists), health data, browsing history, precise device location beyond the one-off "Use my location"
request.

---

## 3. Who else receives personal data

A "server" row means our server makes the request, so the third party sees **our server's** network address, not the
reader's. A "browser" row means the reader's browser talks to the third party directly.

| Recipient | What it receives | From | When | Source |
|---|---|---|---|---|
| **postcodes.io** (`api.postcodes.io`) | a postcode the reader types and looks up; the reader's device coordinates when they press "Use my location" (our server forwards them for a reverse lookup, radius 2 km, one result); **the reader's saved postcode, on every read of their settings** (`GET /api/user/settings` resolves the place name, uncached) | server | lookup, "Use my location", opening the app | `PostcodesIoClient`, `UserSettingsService.getSettings`/`resolveHomePlaceName` |
| **OpenRouteService** (`api.openrouteservice.org`) | the reader's saved **home coordinates** as the origin of a drive-time matrix to the forecast locations | server | when drive times are measured: the manual refresh (no role gate on the server; the button is Pro-only in the app), and the nightly job, which considers every enabled account with a home | `OpenRouteServiceClient`, `DriveDurationService`, `DriveTimeRefreshJob` |
| **Cloudflare Turnstile** (`challenges.cloudflare.com`) | the reader's browser loads the challenge from Cloudflare (IP address and browser signals, as any embedded challenge); our server then sends Cloudflare the site secret and the challenge token to verify it — **no address, no account data** | browser, then server | sign-in, registration, forgot-password, change-password | `TurnstileService`, CSP in `nginx.conf` |
| **The mail provider** (SMTP; `smtp.livemail.co.uk` in the committed `application-prod.yml`, sender `noreply@photocast.online` — the legal name of the provider and the running host's value are **not verified**) | the recipient's address and the message: username and a one-time link (verification, password reset), the "account removed" notice, and — for accounts an administrator creates or resets — a temporary password; **administrators' addresses also receive operational alerts** (a degraded pipeline, a location auto-disabled, the Ask spend cap, the forecast-cycle watchdog) when `notifications.admin-alerts.enabled` is on, as the committed production config sets it | server | registration, forgot-password, deletion, administrator actions, admin alerts | `UserEmailService`, `AdminAlertService` |
| **Esri map tiles** (`server.arcgisonline.com`) | the reader's IP address and the map tiles requested (so the area being looked at) | browser | when the map is shown | `MapView.jsx`, CSP in `nginx.conf` |
| **Anthropic (Claude)** | forecast scoring: location names and weather data only — no personal data. **Ask PhotoCast**, only while it is switched on: the question the reader types and, for a follow-up, the earlier questions and answer summaries of that session. No username, email, home postcode or home coordinates are in the prompt (the prompt tells the model it never needs to know where home is; "close to home" is resolved by our server) — **but when a question is about distance from home, the tool results sent to the model carry home-derived figures:** each place's drive minutes from home, or the "within N miles of home" radius, with the filtered set of places (`AskTools`). Anthropic's own retention is **not verified** | server | forecast runs; Ask | `AskPromptBuilder` (read for R7); the forecast prompts are as the existing policy states, not re-audited here |
| **Open-Meteo, WorldTides, NOAA SWPC, lightpollutionmap.info** | coordinates of the forecast locations — not the reader's | server | forecast runs | unchanged from the existing policy |
| **Nominatim** (`nominatim.openstreetmap.org`) | a place name typed by an **administrator** when adding a forecast location — not a reader flow | browser | admin Location management only | `forecastApi.geocodePlace` |
| **Google Fonts** (`fonts.googleapis.com`) | the visitor's IP address — **the marketing site only**; the app serves its fonts itself | browser | visiting `photocast.online` | `landing/*.html`; `frontend/index.html` (self-hosted) |

---

## 4. What account deletion removes — and what it keeps

`DELETE /api/user/account` (any role except administrators, who are deleted by another administrator) re-checks the password,
then in one transaction, after locking the user row:

**Removed**

- the account row — email, username, password hash, home postcode and coordinates, local radius, map preferences, consent
  flags, last-active time (`app_user`);
- the reader's drive times (`user_drive_time` — purged explicitly, the one table with no cascade);
- every session: all refresh tokens (`refresh_token`), and the access token stops working at its next request because the
  row it names is gone;
- one-time tokens (`email_verification_token`, `password_reset_token`);
- the reader's outcome ratings (`outcome_rating`) and Ask allowance counters (`ask_usage`);
- the email reservation (`app_user_email_claim`), unless another account still uses that address.

A confirmation email ("your account was removed") is then sent, after commit, to the address that was stored.

**Kept — state this honestly**

- **`ask_log` rows survive with the link removed.** Their `user_id` is set to null — no account link remains, though the
  free-text question could itself be identifying — and the row — including the **normalised text of any question the engine answered** — stays until the nightly prune removes it at
  **90 days** (`ask_log_cleanup`, `photocast.ask.log.retention-days`). `AccountDeletionIntegrationTest` asserts the row
  survives with a null user. (The in-app deletion sentence says "your Ask history" is deleted; the *link* is, the unlinked
  question text is not, for up to 90 days — flagged to the owner in the R7 PR.)
- **Application logs.** `goldenhour.log` is a daily-rolling file kept for **30 days** (50 MB per file, 1 GB cap —
  `logback-spring.xml`), and the console copy is also captured by Docker's `json-file` driver, rotated by size (10 MB × 3,
  `docker-compose.yml`), not by age. The lines include, as ordinary INFO/WARN output: usernames and email addresses at
  registration, on every mail sent (verification, reset, welcome, "account removed") and at a waitlist sign-up; a successful
  sign-in's username and role; the typed identifier of a failed sign-in; a password change's username; the username with the
  postcode and coordinates at the moment a home is saved; a failed postcode lookup's postcode (and, per §1, possibly the
  reverse lookup's coordinates); the home coordinates against the user id in every drive-time measurement
  (`DriveDurationService`, including the nightly job); and the deletion itself (id and username). Deletion does not rewrite
  logs; they age out. The `landing/` container has its own nginx access log.
- **Database backups.** `scripts/backup-postgres.sh` takes a whole-database `pg_dump` (keeps 7 daily and 4 weekly, with an
  optional off-box rsync copy) and `scripts/pre-release-backup.sh` writes a dump before each release to a `pre-release/`
  directory that is **deliberately never rotated**. A deleted account therefore remains in backups for up to about five weeks
  and in pre-release dumps until the owner removes them. **Not verified:** whether the production host runs these scripts,
  sets `OFFBOX_DEST`, or has pruned `pre-release/`.
- **In-memory copies:** the attempt limiter's counters (keyed by username or address, gone within an hour) and a personal
  typed-Ask cache entry (keyed by user id, 30 minutes) are not purged on deletion; they expire on their own.
- **Copies we do not control:** the mail provider's records of mail sent to the address, and Cloudflare's. **Not verified.**
- **The waitlist** (`waitlist_email`) and any **unverified pending registration** are not linked to a deleted account and are
  not touched.
- **Aggregates that never held the account:** forecast data, job and API-call logs (`api_call_log` has no user column).

Administrator deletion (`DELETE /api/users/{id}`) does the same purge and the same mail.

---

## 5. Retention periods that exist in code

| Data | Period | Mechanism |
|---|---|---|
| Access token | 24 hours | `application-prod.yml` `access-token-expiry-hours` |
| Refresh token | 30 days; expired rows deleted nightly | `refresh-token-expiry-days`; `refresh_token_cleanup` |
| Email verification link | 24 hours | `RegistrationService` / `VerificationTokenIssuer` |
| Password reset link | 1 hour, single use | `PasswordResetIssuer.LINK_LIFETIME` |
| Attempt-limiter counters | 15 minutes / 1 hour, in memory | `AuthAttemptLimiter` |
| Ask question log (`ask_log`) | 90 days | `ask_log_cleanup` (03:55 UTC) |
| Application log | 30 days, 1 GB cap (file); Docker's console copy is size-rotated, not time-bound | `logback-spring.xml`, `docker-compose.yml` |
| Database backups | 7 daily + 4 weekly; `pre-release/` dumps never rotated (production use **not verified**) | `scripts/backup-postgres.sh`, `scripts/pre-release-backup.sh` |
| Account data, ratings, drive times | until deletion | §4 |
| Dormant accounts | **no job deletes them** (the public policy says they "may be" deleted after 90 days of inactivity) | — |
| Waitlist, unverified registrations, used/expired verification and reset rows | no pruning job found | — |

---

## 6. Open items for the owner

1. **Apple location category** — see the note under §2.
2. **"Your Ask history" in the in-app deletion sentence** overstates what is deleted (§4): reword it, or delete the unlinked
   `ask_log` rows at account deletion. Not changed here (this phase writes documents).
3. **The public policy's mail processor** said "SendGrid"; nothing in the repository uses SendGrid and the committed config
   sends through an SMTP host. R7 changed the public page to name the SMTP service generically — confirm the provider's
   legal name.
4. **Dormant-account deletion** is a policy statement with no code behind it.
5. **The AASA file** carries the placeholder `TEAMID.BUNDLEID` until the owner supplies the real ids.
6. **Marketing consent for non-self-registered accounts** — see §1; decide before any marketing sender exists.
7. **A recommended code follow-up (not made here, R7 writes documents):** make `PostcodesIoClient` log the exception class,
   not its message, so a failed lookup can never put a postcode or the device's coordinates in the log; consider an nginx log
   format without the query string so one-time tokens stay out of the access log; consider rotating `pre-release/`.
8. **`landing/privacy.html` is not live until it is redeployed** (`./release-landing.sh`, see `landing/README.md`).
