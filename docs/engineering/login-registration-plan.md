# Login and registration enhancements — postcode capture, auth fixes, native prep (2026-10-16)

**Source**: the design bundle vendored at `docs/design/login-registration/` — `README.md` (the
specification, eighteen tasks in four groups) and `Postcode Capture.html` (eight static frames).
`docs/design/login-registration/VENDORING.md` records what was copied. Read the spec before any
phase; **this document is the port plan**: what the codebase already has (and where the spec guessed
wrong), what is genuinely new, how the work cuts into single-session phases for **Sonnet** sessions,
and every place the plan and the spec disagree on purpose.

**The one-sentence version.** Registration gains a `HOME` postcode step and sign-in a one-time
prompt for accounts with no postcode (both with "Use my location"); the auth screens are made to
work on phones and to look like the rest of the app; marketing consent becomes opt-in; "Confirm
email" and the second Turnstile go; sign-in accepts an email, offers a real forgot-password flow and
a backend fallback when Turnstile cannot load; Settings gains account deletion; and the server is
readied for the iOS app (`apple-app-site-association`, clean `/verify` and `/reset` links, a privacy
inventory).

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (prosecutor lenses + one refuter per charge, all read-only, run in the FOREGROUND so their
results return to the session that launched them) → fix survivors → the §7 checks → changelog.d
entry → commit → push → PR → CI and Codex green → merge. ⚠️ **The owner authorised push, PR and merge
for every phase of this series on 2026-10-16** ("Push and Open a PR. Merge once CI is green and Codex
is happy. Then move on to the next stage"); CLAUDE.md's standing "never push" rule is lifted for
exactly this series and nothing else — never tag, never run `release.sh` ("I'll release when we're
feature complete"). Backend gate before any push (JDK 23 on this Mac, see `no-jdk-25-on-this-mac`
in memory; `$SCRATCH` is the session scratchpad, never bare `/tmp`):

```bash
cd backend && export JAVA_HOME=/Users/chrisgregory/Library/Java/JavaVirtualMachines/openjdk-23.0.2/Contents/Home MAVEN_OPTS=--enable-preview && ./mvnw clean verify --batch-mode --no-transfer-progress -Djava.version=23 -Dmaven.compiler.enablePreview=true -Dtest='!**/integration/**,!EvaluationModelTest' -DfailIfNoSpecifiedTests=false >"$SCRATCH/v.log" 2>&1; echo "exit: $?"
```

Gate on the echoed exit code, never a grep. Frontend gate: `cd frontend && npm run lint && npm test
&& npm audit --audit-level=high && npm run build`. Paste the relevant section of THIS plan and the
spec into every review agent's prompt — an agent cannot open a path it was not given.

**Check open PRs first.** `gh pr list --state open` and grep the titles for *login*, *register*,
*auth*, *postcode*, *settings*, *turnstile*. Checked 2026-10-16 at plan time: three open PRs (#1132
a jsdom bump, #843 and #815 docs), none touching any file this plan names.

---

## §0 Status

| phase | PR | merged | notes |
|---|---|---|---|
| R0 plan | — | — | this document, the vendored bundle, `changelog.d/20261016-login-registration-plan.md` |
| R1 auth visual foundation | | | |
| R2 Turnstile gate + registration form | | | |
| R3a sign-in backend: identifier, forgot/reset, `/verify` link | | | |
| R3b sign-in frontend: forgot/reset pages, label, link | | | |
| R4a postcode backend: prompt column, login flag, reverse lookup | | | |
| R4b postcode frontend: `PostcodeCapture`, `HOME` step, prompt page, Settings | | | |
| R5 account deletion | | | |
| R6 native prep: AASA, nginx, privacy docs | | | |
| R7 docs sweep | | | |

Each phase appends its row **in the same commit** as its code.

---

## §1 What the codebase already has — corrections to the spec

The spec's "check these first" list, answered, plus what else the survey turned up. Numbered so the
phases can cite them.

1. **No user-facing password reset exists.** `PUT /api/users/{id}/reset-password` is the ADMIN's
   temp-password reset (`UserService.resetPassword` → `PasswordResetResult` →
   `UserEmailService.sendPasswordResetEmail`, template `password-reset-email.html`, which mails a
   TEMPORARY PASSWORD, not a link). `LoginPage.jsx` has no forgot link and nothing in `frontend/src`
   mentions one. The flow is built in R3a/R3b. The new template is `password-reset-link-email.html`;
   the old one and its admin path are untouched.
2. **Account deletion exists for admins only.** `DELETE /api/users/{id}` →
   `UserService.deleteUser(id, currentUsername)` (refuses self-delete, then
   `userRepository.delete(user)`, then `sendAccountDeletedEmail`, template
   `account-deleted-email.html`). There is no self-service endpoint and no Settings control. ⚠️ **The
   FK cascades are incomplete**: `refresh_token`, `email_verification_token`, `ask_usage` and
   `outcome_rating` cascade, `ask_log` sets null — but `user_drive_time.user_id` (V67) has **no
   `ON DELETE` clause at all**, so a whole-entity delete of a user who has drive times violates the FK.
   R5 deletes the drive-time rows explicitly inside the same transaction, through one shared
   `purgeUserRows` step the admin path calls too, and pins it with a test that gives the user drive
   times first. No push-subscription table exists (Web Push is a planned feature), so there is
   nothing else to purge; no other table is keyed on `username`.
3. **Set-password validates Turnstile server-side.** `AuthController.setPassword` calls
   `turnstileService.verify(body.get("turnstileToken"))` and answers 400 "CAPTCHA verification
   failed" on a miss. R2 removes it (front and back) and puts a token-keyed attempt limit in its
   place (§2 #4).
4. **Login with Turnstile unavailable has NO backend handling — the frontend's promise is empty.**
   `TurnstileService.verify` returns `true` only when `turnstile.secret-key` is blank (local dev);
   with a key configured a null or blank token returns `false`, so in production a sign-in from a
   browser where the script failed to load is a 400 — the `LoginPage` line "Security check unavailable
   — we'll verify your sign-in another way" promises something nothing does. Task 9's "mirror the
   login behaviour, front and back" therefore has nothing to mirror on the back. R2 builds the
   fallback once, as `TurnstileGate` (§2 #4), and applies it to login, register, forgot-password and
   change-password; `LoginPage.test.jsx`'s "submits with an empty token and lets the backend respond"
   stays true and finally means something.
5. **Emails are unique but case-SENSITIVE.** V33 created `idx_app_user_email` as a plain
   `UNIQUE INDEX ON app_user(email)` (nulls allowed for the seeded admin). `AppUserRepository
   .findByEmail` is exact-match; `RegistrationService.findPendingUserByEmail` loads EVERY user and
   filters with `equalsIgnoreCase` (a full-table scan per resend). The email sign-in needs a
   case-insensitive query (`LOWER(u.email) = LOWER(:email)`) that returns a list and treats two or
   more matches as no match (two rows differing only in case are possible under this index), and
   R3a replaces the pending-user scan with the same query. **No `citext`/functional-index migration
   in this series** (§5 #6).
6. **The `app_user` settings columns are `updatable = false` and written by scoped updates.**
   `postcode_prompt_dismissed_at` copies `coming_up_last_seen_at` exactly: nullable column, `updatable
   = false` field, `AppUserRepository.updatePostcodePromptDismissedAtByUsername` (the
   `updateComingUpLastSeenAtByUsername` shape), `UserSettingsService.markPostcodePromptDismissed`
   (the `markComingUpSeen` shape, re-reading the row for the response), `PUT
   /api/user/settings/postcode-prompt-dismissed` with no body.
7. **The registration `SUCCESS` step is unreachable today.** `handleSetPassword` calls
   `completeRegistration(data)` and THEN `setStep(SUCCESS)`; `completeRegistration` sets the token,
   `AuthGate` re-renders, `!token` is false, and the gate returns `<RewindGate/>` — `RegisterPage`
   unmounts in that same commit and its 2 s reload timer is cleared on unmount. New users land in
   the app straight from Set password; nobody has seen "Welcome to PhotoCast!". A `HOME` step after
   the token exists needs the gate to keep `RegisterPage` mounted: `AuthContext.registrationPending`
   (§2 #2). The spec's "the `SUCCESS` step and its 2s reload stay" is honoured as a 2 s Welcome that
   ends the pending state — no reload (§4 #3).
8. **`nginx.conf` disables geolocation for the page itself.** Both the `/` and `/assets/` blocks
   send `Permissions-Policy: geolocation=(), camera=(), …` — `navigator.geolocation` rejects in the
   document under that header, so "Use my location" is dead until the directive reads
   `geolocation=(self)`. `nginx.conf` is bind-mounted into the frontend container
   (`docker-compose.yml`: `./nginx.conf:/etc/nginx/nginx.conf:ro`), so the change ships with the
   deploy, not with an image. R6 owns every `nginx.conf` edit in this series (the header and the
   AASA location), so the two phases that touch infrastructure never edit the same file.
9. **Email links are bare `?token=` on the app origin.** `UserEmailService.sendVerificationEmail`
   builds `frontendBaseUrl + "?token=" + rawToken` (`photocast.frontend-base-url`, production
   `https://app.photocast.online`); `App.jsx`'s `AuthGate` reads `?token=` from ANY path and replaces
   the URL with `window.location.pathname` once consumed. `/verify?token=` already reaches the app
   (nginx `try_files … /index.html`; the PWA's `navigateFallback: '/index.html'` with a denylist of
   `/api` and `/actuator` only). R3a changes the link to `/verify?token=`; the gate keeps accepting
   the legacy bare form for links sent in the 24 h before the deploy and replaces the consumed URL
   with `/`, not the pathname (`/verify` with no token would otherwise survive a consumed link).
   `/reset?token=` is a NEW route the gate must tell apart by `pathname` (§2 #1).
10. **Safe areas are already a system.** `index.css` declares `--safe-t/-r/-b/-l/-v/-h` once on
    `:root` with `0px` fallbacks and an `.app-safe` rule; `index.html` already carries
    `viewport-fit=cover`; `safeAreas.test.jsx` pins that every `env(safe-area-*)` in the stylesheet
    carries a fallback and that the six insets are declared ONCE. The auth wrappers therefore read
    the vars (`pt-[max(3rem,var(--safe-t))]`-shaped arbitrary values or a `.auth-shell` rule in the
    components layer), never a raw `env()`. Task 7's "the app's top bar and bottom-anchored controls"
    is already covered by that system; R1 touches the auth wrappers only (§4 #6).
11. **Postcode lookup errors.** `PostcodeLookupException extends RuntimeException` (not
    `IllegalArgumentException`, the only one `GlobalExceptionHandler` maps to 400), and
    `UserSettingsController.lookupPostcode` does not catch it — so an invalid postcode may answer 500
    today; `UserSettingsModal` maps ANY failure to "Invalid postcode", which hides it. R4a's first
    step measures the status with a controller test and, if it is not 400, adds the one
    `@ExceptionHandler` line so both the forward and the reverse lookup answer 400 `{error}`
    deterministically (the modal's own mapping is unchanged either way).
12. **Rate limits.** The only auth-side limit is `RegistrationService.resendVerification`'s DB count
    of tokens per user (3 in 5 min → `IllegalStateException` → 429). `POST /home/lookup` has none
    (task 3's "if that has a limit" is answered: it does not, and neither does the reverse). The Ask
    stack has an in-memory per-user limiter (`service/ask/AskRateLimiter`, Caffeine-backed; Caffeine
    is already a dependency) — the model for §2 #4's limiter, not reused (it is Ask-shaped).
13. **`ChangePasswordPage` is the same Turnstile dead end.** It mounts a `TurnstileWidget` with no
    `onLoadFail`, and `/change-password` verifies the token server-side — the first-login password
    gate is unreachable from a blocked browser. R2 gives it the same fallback as the other three forms.
14. **The password checklist is duplicated** (`CheckItem` in `RegisterPage.jsx` and
    `ChangePasswordPage.jsx`, identical). R1 extracts `PasswordChecklist` and `PasswordInput` (the
    field with the show/hide eye) once; R3b's reset page is their third consumer.
15. **Marketing opt-in defaults to true on BOTH sides.** `RegisterPage` `useState(true)`, and
    `AuthController.register` parses `!"false".equalsIgnoreCase(body.get("marketingEmailOptIn"))`
    — an absent field opts the account IN. The flip is both: the client default and the server's
    reading of an absent value (`"true".equalsIgnoreCase(...)`), because the iOS client will call
    this endpoint too and an omitted field must never be consent. `AppUserEntity.marketingEmailOptIn
    = true` is the entity default for `createPendingUser`'s explicit argument and stays.
16. **The login response is built in one place.** `AuthController.buildAuthResponse(AuthTokens)` is
    shared by `/login` and `/set-password`; `AuthTokens` is a record filled by
    `AuthenticationService.issueTokensFor(user)`. `postcodePromptDue` (§2 #3) is one new record
    component and one `response.put`, nothing more.
17. **`App.test.jsx` and `AppSettingsRoutes.test.jsx` mock `getSettings` to `{}` / a no-home
    fixture.** A sign-in prompt keyed on a settings READ would fire in all 86 of those tests; a
    prompt keyed on the LOGIN RESPONSE fires in none of them (they start with a token in storage,
    never through `login()`). That is the second reason §2 #3 rides the login response.
18. **`UserSettingsModal` is 847 lines with its own focus-landing state machine.** Its postcode
    field is not swapped for the shared component; R4b adds only the input attributes, the `.field`
    class (R1), the "Use my location" link below the field and the task-14 copy (§4 #5).
19. **The JWT filter resolves the user per request** (`JwtAuthenticationFilter`:
    `userRepository.findByUsername(username).ifPresent(...)`), so a deleted account's still-valid
    access token authenticates nothing and the next request is a 401 — R5 pins that rather than
    inventing a denylist.
20. **`landing/privacy.html` is stale for this work.** It lists the postcode as "if you choose to set
    a home location for drive time calculations — Pro feature"; since the masthead light rule the
    postcode serves every account, and "Use my location" will send coordinates to postcodes.io. R6
    rewrites those lines and adds in-app deletion.
21. **Two origins.** The landing site is `photocast.online` (its own nginx, `landing/`); the app is
    `app.photocast.online` (root `nginx.conf`). `/verify`, `/reset` and therefore the AASA file live
    on the APP origin; the Terms and Privacy links in `RegisterPage` point at the landing origin and
    stay.
22. **`TurnstileWidget` already has `onLoadFail`** (10 s script timeout and a render failure both
    call it); `LoginPage` is its only consumer. R2 wires the other three forms to it.

---

## §2 What is genuinely new

### #1 Password reset (R3a backend, R3b frontend)

**Table** `password_reset_token` (next free `V` — re-read `ls backend/src/main/resources/db/migration
| sort -V | tail -1` at phase time; V178 is the latest at plan time): `id` identity PK, `token_hash
VARCHAR(64) NOT NULL UNIQUE`, `user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE`,
`expires_at TIMESTAMP NOT NULL`, `used_at TIMESTAMP NULL`, `created_at TIMESTAMP NOT NULL`, an index
on `(user_id, created_at)`. The V33 shape with `used_at` in place of `verified`. Entity
`PasswordResetTokenEntity`, repository with `findByTokenHash`, `countByUserIdAndCreatedAtAfter`,
`deleteByUserIdAndUsedAtIsNull`.

**`POST /api/auth/forgot-password`** `{email, turnstileToken?}` → **always** 200
`{"message": "If that email has an account, we've sent a reset link."}` for a syntactically valid
email (400 `{error}` for a blank or invalid one, the `register` shape); through `TurnstileGate`
(§2 #4; the fallback key is the lower-cased email). `PasswordResetService.requestReset(email)`:
find the user case-insensitively (§1 #5); none, disabled, or pending (empty password) → log at
DEBUG, return; `countByUserIdAndCreatedAtAfter(now − 5 min) >= 3` → log at INFO, **return 200
silently** (never 429: a 429 says the account exists); else delete that user's unused tokens, mint
`jwtService.generateRefreshToken()`, store its hash with `expires_at = now + 1 h`, send
`sendPasswordResetLinkEmail(email, username, rawToken)` whose link is `frontendBaseUrl +
"/reset?token=" + rawToken` (template `password-reset-link-email.html`, the verification email's
layout, copy in §2 #12).

**`POST /api/auth/reset-password`** `{token, password}` → 400 `{error}` for a missing field, a
complexity failure (`validatePasswordComplexity`'s own sentence), an unknown token ("This reset link
is invalid"), a used one ("This reset link has already been used") or an expired one ("This reset
link has expired"); through the token-keyed attempt limit (§2 #4, key `reset:` + the token hash's
first 16 hex chars, 10 per 15 min). On success, in one transaction: set the password, clear
`passwordChangeRequired`, stamp `used_at`, `refreshTokenRepository.revokeAllActiveByUserId(userId)`
(every other device signs out), then `issueTokensFor(user)` and answer `buildAuthResponse` — the
reader is signed in on the device that reset, exactly as set-password does. Never Turnstile (the
emailed token is the proof, task 11's own reasoning).

**Client** (R3b): `ForgotPasswordPage` (email field, Turnstile with the load-fail fallback, "Send
reset link", the always-200 sentence shown as the done state, "Back to sign in") reached from the
"Forgot password?" link; `ResetPasswordPage` mounted by `AuthGate` when `window.location.pathname ===
'/reset'` and `?token=` is present (`PasswordInput` × 2 + `PasswordChecklist`, "Set new password" →
`completeRegistration(data)`-shaped sign-in through a new `AuthContext.signInWithTokens(data)` that
both call, then `history.replaceState({}, '', '/')`); an invalid/used/expired token shows the
server's sentence and a "Request a new link" button that goes to `ForgotPasswordPage`.
`authApi.js` gains `requestPasswordReset(email, turnstileToken)` and `resetPassword(token, password)`.

### #2 `registrationPending` and the `HOME` step (R4b, with the context change in R3b)

`AuthContext` gains an in-memory boolean `registrationPending` (never storage), set `true` by
`completeRegistration(data)` and cleared by a new `finishRegistration()`. `AuthGate`'s first test
becomes `if (!token || registrationPending)`, and inside it the verify branch becomes `if
(verifyToken || registrationPending)` so the SAME `<RegisterPage>` element (same type, same tree
position) stays mounted while the token already exists — React preserves the instance and its
`step` state; a `verifyToken` prop that goes null after mount is harmless (`handleVerify` runs only
when the prop is truthy). `RegisterPage`: `STEPS` gains `HOME` between `SET_PASSWORD` and
`SUCCESS`; `handleSetPassword` → `completeRegistration(data)` → `setStep(HOME)`; `HOME` renders
`PostcodeCapture` (§2 #6) with the task-1 copy; Save and Skip both → `SUCCESS`; `SUCCESS` keeps its
copy and its 2 s timer, whose body is `finishRegistration()` (and the URL cleanup) instead of
`window.location.reload()`. The lookup and save calls go through the ordinary `apiClient`, which
reads the token `completeRegistration` has just stored. A page reload during `HOME` lands in the app
with no postcode (the pending flag is memory) — the masthead's "set a postcode" nudge and, at the
next sign-in, the prompt both still cover it; stated, accepted.

### #3 The one-time sign-in prompt (R4a backend, R4b frontend)

**Column** `app_user.postcode_prompt_dismissed_at TIMESTAMPTZ NULL` (next free `V`; no backfill —
null is "never dismissed", the spec's "existing users without a postcode see it once"). Entity field
`Instant postcodePromptDismissedAt`, `updatable = false`, written only by
`AppUserRepository.updatePostcodePromptDismissedAtByUsername(username, at)` (§1 #6).
`UserSettingsResponse` gains `postcodePromptDismissedAt` (nullable; the spec asks for it on the
settings read, and it costs nothing). **`PUT /api/user/settings/postcode-prompt-dismissed`**, no
body, `isAuthenticated()`, answers the settings response like `/coming-up-seen`; added to
`HttpCachingConfigTest.personalDataPathsAreNeverFiltered` (a PUT is never filtered, pinned anyway, the
`/map-colours` precedent). Idempotent: a second dismissal overwrites the instant.

**The login flag.** `AuthTokens` gains `boolean postcodePromptDue`; `issueTokensFor` fills it as
`user.getHomePostcode() == null && user.getPostcodePromptDismissedAt() == null`;
`buildAuthResponse` puts `postcodePromptDue`. Carried by `/login` and `/set-password` (and
`/reset-password`), never by `/refresh`. **The client keeps it in memory only**: `AuthContext.login`
sets `postcodePromptDue` from the response (default false), `completeRegistration` and
`signInWithTokens` leave it false (registration has `HOME`; a reset is not a fresh look at the
account), `logout`/session-expiry clear it, `clearPostcodePrompt()` clears it. `AuthGate` order:
no token → auth pages; `mustChangePassword` → `ChangePasswordPage`; `postcodePromptDue` →
`PostcodePromptPage`; else `RewindGate`. The prompt is therefore never over the app shell, never
over the Settings dialog, never a dialog-stack member (CLAUDE.md's Plan-tab modal invariants are
untouched), and never counted by `AppSettingsRoutes.test.jsx`.

**`PostcodePromptPage`** = `AuthShell` + `PostcodeCapture` with the task-2 copy; Save →
`saveHome(...)` then `clearPostcodePrompt()`; "Not now" → `markPostcodePromptDismissed()` (fire,
await, ignore a failure — the app must open either way; a failed dismissal means one more prompt at
a later sign-in, the safe direction) then `clearPostcodePrompt()`. A reload while the prompt is up
goes to the app (the flag is memory) and the prompt returns at the next sign-in — consistent with
"shown once after login" and with the dismissal being the only durable state.

### #4 `TurnstileGate` and `AuthAttemptLimiter` (R2)

`service/AuthAttemptLimiter`: Caffeine `expireAfterWrite(15 min)`, `maximumSize(100_000)`, keys
are `String`, values `AtomicInteger`; `boolean tryAcquire(String key, int limit)` increments and
answers `count <= limit`; injectable `Ticker` for tests. Keys are `"<endpoint>:" + the lower-cased
identifier` (login: the identifier typed; register and forgot: the email; change-password: the
authenticated username; set-password and reset-password: the token hash's first 16 hex chars). ⚠️
**Never the client IP**: behind `cloudflared` → nginx, the backend's `remoteAddr` is the tunnel's
own address for every reader and nothing today reads `CF-Connecting-IP`, so an IP key would lock
everyone out together the moment Turnstile was down (§5 #4 records the trade).

`service/TurnstileGate`: `Outcome admit(String token, String fallbackKey)` with
`Outcome { OK, CAPTCHA_FAILED, RATE_LIMITED }`: `TurnstileService.isConfigured()` false → `OK`
(local dev, today's behaviour); token non-blank → `verify(token)` ? `OK` : `CAPTCHA_FAILED`; token
blank → `limiter.tryAcquire("fallback:" + key, 5)` ? `OK` : `RATE_LIMITED`. Controllers map
`CAPTCHA_FAILED` → 400 with today's sentence, `RATE_LIMITED` → 429 `{"error": "Too many attempts.
Please wait a few minutes and try again."}`. Applied to `/login`, `/register`, `/change-password`,
`/forgot-password` (R3a). `/set-password` loses Turnstile entirely and always takes
`limiter.tryAcquire("set-password:" + tokenKey, 10)` → 429 the same sentence. `TurnstileService`
gains `isConfigured()`; `verify`'s own behaviour is unchanged. A legitimate reader on a blocked
browser who mistypes a password five times waits fifteen minutes; the copy says so. **Nothing about
a token that VERIFIES changes** — the fallback exists only for the no-token path, and a token that
fails verification is still a 400, never a fallback.

Frontend (R2): `RegisterPage` (both the register form and set-password), `ChangePasswordPage`
take `LoginPage`'s exact pattern — `turnstileLoadFailed` state, `onLoadFail`, the widget unmounted
and the amber line "Security check unavailable — we'll verify your sign-in another way." shown
(verbatim on every form, the one sentence `LoginPage` already owns), the submit enabled, an empty
token sent. Set-password's widget, state and token are deleted outright (task 11). A 429 renders the
server's sentence in the form's error slot.

### #5 Reverse lookup (R4a)

`PostcodesIoClient.reverseLookup(double lat, double lon)` → `GET
https://api.postcodes.io/postcodes?lon={lon}&lat={lat}&limit=1`; the response is `{status, result:
[Result] | null}`; an empty/null result or any transport failure throws `PostcodeLookupException`
exactly as `lookup` does; a hit maps through the same `buildPlaceName` to the same
`PostcodeLookupResult`. **`POST /api/user/settings/home/reverse`** `{latitude, longitude}`,
`isAuthenticated()`: 400 `{error}` for a missing or out-of-range value (lat −90..90, lon −180..180,
finite), 400 `{error: "No UK postcode found for that location"}` from the exception (§1 #11),
otherwise the result; never ETag'd (added to the caching test's personal list). `settingsApi.js`
gains `reverseLookupPostcode(latitude, longitude)`. No rate limit (§1 #12).

### #6 `PostcodeCapture` and `useGeolocationPostcode` (R4b)

`components/auth/PostcodeCapture.jsx`, props `{heading, sub, primaryLabel, secondaryLabel,
onSaved(result), onSkip, autoFocus}` — the field + "Look up" / "Looking up…" (Enter runs the lookup;
the task-4 attributes `autoComplete="postal-code" autoCapitalize="characters" autoCorrect="off"
spellCheck={false} inputMode="text" enterKeyHint="search"`), the confirmation row (`✓ {placeName}`
with `{postcode}` beneath in `font-mono text-[11px]`, a **Change** text button restoring the field
with its last value), the error "Invalid postcode" (`text-plex-error`, the field's border swapped
to the error token), the "Use my location" text button (hidden when `navigator.geolocation` is
undefined), the Pro line (`PRO` tag + "Also gives you drive times from home to every location." —
every role, no link), the primary button disabled until a lookup result exists, the secondary text
button. Save calls `saveHome(result.postcode, result.latitude, result.longitude, null)` — the
null radius keeps the column NULL (`UserSettingsModal`'s own rule) — then `onSaved(response)`; a
failed save keeps the confirmation row and shows "Couldn't save. Try again." (§5 #9). Autofocus
only when `autoFocus && window.matchMedia('(pointer: fine)').matches` (task 8; `matchMedia` absent
in a test → false).

`hooks/useGeolocationPostcode.js`: `{locate, locating, error}`; `locate()` calls
`navigator.geolocation.getCurrentPosition(ok, fail, {enableHighAccuracy: false, timeout: 10000,
maximumAge: 600000})`, then `reverseLookupPostcode(lat, lon)`; the error strings, verbatim:
`PERMISSION_DENIED` → "Location access is off. Enter your postcode instead."; a 400 from the
reverse endpoint → "We couldn't find a UK postcode for your location."; `TIMEOUT`,
`POSITION_UNAVAILABLE`, any other failure of either step → "Couldn't get your location. Enter your
postcode instead." A result lands in the same confirmation row as a typed lookup.

Consumers: `RegisterPage` `HOME` (R4b), `PostcodePromptPage` (R4b), and `UserSettingsModal` takes
the hook and the link, the attributes and the autofocus rule — not the component (§1 #18, §4 #5).

### #7 Account deletion (R5)

**`DELETE /api/user/account`** `{password}`, `isAuthenticated()`, new `AccountController`
(`/api/user/account` — not under `/settings`, it is not a setting): 400 for a missing password; 403
`{error: "Password is incorrect"}` on a mismatch, counted on `AuthAttemptLimiter` (key `delete:` +
username, 5 per 15 min → 429); 409 `{error: "Admin accounts are deleted by another admin"}` for
`ROLE_ADMIN` (the existing self-delete refusal, extended: it keeps the sole admin from locking the
instance out). On success, `UserService.deleteOwnAccount(username)` in one transaction:
`purgeUserRows(userId)` (explicit `user_drive_time` delete — §1 #2 — then anything else without a
cascade a test discovers), `userRepository.delete(user)`; after commit `sendAccountDeletedEmail`.
The admin path `deleteUser` calls the same `purgeUserRows`. 204 No Content. The access token dies
with the row (§1 #19). `CLAUDE.md`'s "deleting a user must not be blocked" (`ask_usage` cascade)
already states the direction.

**Client**: a **Danger zone** section at the foot of `UserSettingsModal` — `btn-secondary` "Delete
account" → `ConfirmDialog` (`destructive`, title "Delete your account?", message "This deletes your
account, your home postcode and drive times, your Ask history and your ratings. It cannot be
undone.", a `PasswordInput` as `children`, confirm "Delete account" disabled until the password is
non-empty) → `deleteAccount(password)` (`userApi.js` or a new `accountApi.js`:
`apiClient.delete('/api/user/account', {data: {password}})`) → on 204 `logout()` (its `/logout`
call finds no refresh token and answers 200 — harmless) → the login page. A 403 shows the server's
sentence inside the dialog; a 429 likewise; the dialog stays open.

### #8 `apple-app-site-association` (R6)

`frontend/public/.well-known/apple-app-site-association` — JSON, no extension:

```json
{
  "applinks": { "details": [ { "appIDs": ["TEAMID.BUNDLEID"], "components": [
    { "/": "/verify", "?": { "token": "*" } },
    { "/": "/verify/*" },
    { "/": "/reset", "?": { "token": "*" } },
    { "/": "/reset/*" } ] } ] },
  "webcredentials": { "apps": ["TEAMID.BUNDLEID"] }
}
```

`TEAMID.BUNDLEID` is the literal placeholder until the owner has both (§6 Q2). nginx: an exact
`location = /.well-known/apple-app-site-association { default_type application/json; add_header
Cache-Control "no-cache"; + the nosniff/frame/referrer/permissions/COOP/CSP headers the `/` block
repeats (the file's note says a location that sets ANY header inherits none) }` before `location /`.
R6 proves Vite copies the dot-directory into `dist/` (`npm run build && test -f
dist/.well-known/apple-app-site-association`) and that the PWA precache ignores it (its
`globPatterns` match extensions only — read `vite.config.js` and say so in the PR).

### #9 Visual foundation (R1)

`index.css` `@theme`: `--color-plex-error: #F07A62` (measured ≥ 4.5:1 on `#221A15` — R1 records the
figure) and `--color-plex-ok: #8AAE72` (a separate token from `--color-verdict-go`, same value, with
the `static` marker and a comment in the file's own voice: verdict colours mean forecast verdicts).
`@layer components`: `.field` = `w-full bg-plex-bg border border-plex-border rounded-lg px-3 py-2
text-base sm:text-sm text-plex-text placeholder:text-plex-text-muted focus:outline-hidden
focus:ring-1 focus:ring-plex-gold` and `.field-error` swapping the border to `border-plex-error`;
`.auth-shell` = the page wrapper: `min-h-dvh bg-plex-bg flex justify-center px-4 items-start pt-12
sm:items-center sm:pt-0` plus padding from `--safe-t/-b/-l/-r` (§1 #10). Components under
`components/auth/`: `AuthShell` (wrapper + `BrandLockup variant="auth"` + `max-w-sm` column),
`AuthHeading` (`font-serif font-semibold text-[22px] leading-tight tracking-[-0.015em]
text-plex-text`, optional mono kicker `font-mono text-[10px] uppercase tracking-[0.2em]
text-plex-text-muted`), `PasswordInput` (field + eye button, `autoComplete` prop),
`PasswordChecklist` (✓ in `text-plex-ok` for met, ○ in `text-plex-text-secondary` for unmet).
`LoginPage`, `RegisterPage` (every step), `ChangePasswordPage` and `UserSettingsModal` adopt
`.field`, the tokens (every `red-400`/`red-700`/`amber-400`/`green-400`/`green-500` in those four
files; the role badges stay), `text-plex-text-secondary` for 12px hints, left alignment on the
session-expired, Check your email, Verify, Waitlist and Success cards; the session-expired line moves
inside the card above the heading. Task 14's Settings copy change rides here. **R1 changes no
behaviour and no test id** — every existing test passes with at most class assertions updated.

### #10 Sign-in by username or email (R3a backend, R3b frontend)

`AuthenticationService.login(identifier, password)`: `identifier.contains("@")` →
`userRepository.findAllByEmailIgnoreCase(identifier.trim())` (`LOWER(u.email) = LOWER(:email)`,
a `List`); exactly one → that user; zero or several → the same generic failure; otherwise
`findByUsername`. The request field stays `username` on the wire (the iOS client and every test
keep working); the log line says `identifier`. The error stays "Invalid username or password". The
client: label "Username or email", `autoComplete="username"` kept (password managers fill either),
`data-testid="login-username"` kept.

### #11 Privacy inventory (R6)

`docs/app-store-privacy.md`: a table per App Store privacy "data type" — Email address and
Username (account, linked), Coarse location (home postcode and its lat/lon; "Use my location" sends
device coordinates to postcodes.io once, nothing is stored but the resolved postcode; linked; app
functionality), Marketing consent (linked), Usage data (`last_active_at`, `ask_log` without
question text, job and API logs; linked; analytics/app functionality), User content (outcome ratings;
linked) — with the table each lives in, who sees it, and how deletion removes it (§2 #7's purge).
`landing/privacy.html`: the postcode line rewritten (every account, light times; drive times Pro),
a "Use my location" sentence, in-app account deletion named.

### #12 Copy table (verbatim; every string a test may assert)

| where | string |
|---|---|
| HOME heading / sub | **Where's home?** / *Your postcode sets the sunrise and sunset times shown at the top of the app.* |
| Field label / placeholder / button / busy | **Home postcode** / *Enter UK postcode* / **Look up** / *Looking up…* |
| Confirmation / change | `✓ {placeName}`, `{postcode}` beneath / **Change** |
| Error | *Invalid postcode* |
| Pro line | `PRO` *Also gives you drive times from home to every location.* |
| HOME buttons | **Save and continue** / **Skip for now** |
| Prompt heading / sub | **Set your home postcode** / *You haven't saved one yet. It sets your light times, and you can change it any time in Settings.* |
| Prompt buttons | **Save** / **Not now** |
| Use my location | **Use my location**; errors per §2 #6 |
| Save failed | *Couldn't save. Try again.* |
| Register hints | *We'll send a link to confirm it.* (under Email) / *3–30 letters, numbers, - or _* (under Username) / *Agree to the terms to continue* (under the disabled button, 12px, `text-plex-text-secondary`, centred) |
| Turnstile fallback | *Security check unavailable — we'll verify your sign-in another way.* |
| Rate limited | *Too many attempts. Please wait a few minutes and try again.* |
| Sign-in label / link | **Username or email** / **Forgot password?** (right of the Password label, 12px, `text-plex-gold`) |
| Forgot heading / sub / button / done | **Reset your password** / *Enter your email and we'll send you a link.* / **Send reset link** / *If that email has an account, we've sent a reset link.* |
| Reset heading / sub / button | **Set a new password** / *Choose a new password for your account.* / **Set new password** |
| Reset link errors | *This reset link is invalid* / *This reset link has already been used* / *This reset link has expired* + button **Request a new link** |
| Reset email | subject *Reset your PhotoCast password*; body: *Someone asked to reset the password for {username}. If that was you, use the link below within the next hour. If it wasn't, you can ignore this email — your password hasn't changed.* |
| Settings copy (task 14) | *Your postcode sets your light times. Pro accounts also get drive times from home.* |
| Delete account | **Delete account** / *Delete your account?* / message per §2 #7 / *Password is incorrect* / *Admin accounts are deleted by another admin* |
| Success | **Welcome to PhotoCast!** / *Redirecting…* (unchanged) |

---

## §3 Phases

One Sonnet session each, one branch each (`claude/login-reg-r<N>-<slug>`), one PR each.
**Dependencies and parallelism**: R1 ∥ R6 (disjoint: R1 is `frontend/src` + `index.css`, R6 is
`nginx.conf`, `frontend/public`, `docs/`, `landing/`); R2 needs R1 merged (it restructures the
RegisterPage R1 restyles); R3a needs R2 merged (`TurnstileGate`); R3b needs R3a merged; R4a needs
R3a merged (both edit `AuthController`/`AuthTokens`) and may run ∥ R3b; R4b needs R3b and R4a
merged; R5 needs R4b merged (both edit `UserSettingsModal`); R7 needs everything. Never more than
two phases open at once, and never two that touch one source file. Each phase re-verifies every
file:line it touches against `origin/main` before editing, reads
`docs/engineering/test-improvement-standards.md` (backend) or
`docs/engineering/frontend-test-standards.md` (frontend), writes its
`changelog.d/<its own date YYYYMMDD>-login-reg-r<N>-<slug>.md` (`### Added` for new behaviour,
`### Changed` for an existing one, `### Fixed` for a dead end it closes, `### Docs` for R6's docs and
R7; **one file per `###` block**) and appends its phase-log row to §0 **in the same commit**.
Every phase that touches a screen a browser can reach WITHOUT signing in (R1, R2, R3b: the login,
register, forgot and reset pages) screenshots it at 390×844 and 1280×800 through the preview tools
(or headless Chromium — `browser-pane-headless-fallback` in memory) and attaches both to the PR; a
phase that needs a signed-in page (R4b's prompt, R5's dialog) signs in as `admin` / `golden2026` on
the local H2 backend (`./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local`, port
8083; a fresh H2 admin has no postcode, so the prompt shows at that sign-in).

### R1 — auth visual foundation (frontend only)

Tasks 5, 6, 7 (auth wrappers), 13, 14. Files: `frontend/src/index.css` (tokens, `.field`,
`.field-error`, `.auth-shell`); new `components/auth/AuthShell.jsx`, `AuthHeading.jsx`,
`PasswordInput.jsx`, `PasswordChecklist.jsx` (+ tests for each); `LoginPage.jsx`,
`RegisterPage.jsx`, `ChangePasswordPage.jsx`, `UserSettingsModal.jsx`; tests `LoginPage.test.jsx`,
`RegisterPage.test.jsx`, `UserSettingsModal.test.jsx`, `safeAreas.test.jsx` (extend: the auth
shell reads the vars), a new `authTokens.test.js` (the two tokens exist in the built CSS — the
`--color-plex-panel` pruning incident in `index.css`'s own comments is the reason; `askCss.test.js`
is the model for reading the stylesheet), `frontend-test-standards.md` read first.

1. Tokens and classes per §2 #9; measure `#F07A62` on `#221A15` and `#181210` with a contrast
   function in the test, assert ≥ 4.5.
2. The four shared components; `PasswordChecklist` takes `checks: [{label, ok}]`; `PasswordInput`
   takes `id, name, value, onChange, autoComplete, disabled, testId` and owns the eye button with
   its existing `aria-label`s.
3. `LoginPage`: `AuthShell`, `AuthHeading` "Sign in", `.field` on both inputs (`text-base sm:text-sm`
   comes with it), `PasswordInput`, the session-expired line moved inside the card above the heading
   and left-aligned (`data-testid` kept), tokens.
4. `RegisterPage`: the same on every step; the five centred cards left-aligned; the checklist from
   the shared component; hints to `text-plex-text-secondary`; tokens. **No field order, copy or
   state change here** — R2 does those.
5. `ChangePasswordPage`: the same.
6. `UserSettingsModal`: `.field` on the postcode input, tokens for its status colours (`settings-
   lookup-error` and the drive-time lines), the task-14 sentence. Nothing else.
7. Tests: existing suites green with class assertions updated; new component tests; the shell's
   padding assertions; screenshots (§3 preamble) of Sign in and Create account at both sizes.

### R2 — `TurnstileGate` and the registration form

Tasks 9, 10, 11. Backend files: new `service/AuthAttemptLimiter.java`, `service/TurnstileGate.java`
(+ `AuthAttemptLimiterTest` with an injected `Ticker`, `TurnstileGateTest`),
`service/TurnstileService.java` (`isConfigured()`), `controller/AuthController.java` (login,
register, change-password through the gate; set-password's Turnstile removed and the token-keyed
limit added; the marketing default — §1 #15), `AuthControllerTest`, `AbstractControllerTest` (the
new beans). Frontend: `RegisterPage.jsx` (confirm-email field/state/`emailsMatch`/error deleted;
email before username with the two hints; "Agree to the terms to continue" under the disabled
button, only while terms are unticked; `marketingOptIn` default `false`; `turnstileLoadFailed` on
the register form; set-password's widget, token and reset key deleted), `ChangePasswordPage.jsx`
(the fallback), `authApi.js` (`setPasswordForNewUser(token, password)` — two arguments),
`RegisterPage.test.jsx` (new step order, no confirm email, unticked marketing by default, single
Turnstile, the fallback on both forms, the disabled reason appears and disappears),
`ChangePasswordPage` test.

1. Limiter and gate per §2 #4, with tests: a sixth call within fifteen minutes refused, the first
   call after the window admitted, keys independent, `isConfigured()` false → `OK` with no limiter
   call, a non-blank failing token → `CAPTCHA_FAILED` with no limiter call.
2. Controller: each endpoint's three outcomes pinned (200/400/429) with `TurnstileService` mocked
   `isConfigured() = true`; set-password with NO `turnstileToken` succeeds and the eleventh attempt
   on one token answers 429; register with the field absent stores `marketingEmailOptIn = false`.
3. Frontend per the file list; `LoginPage` untouched.
4. Screenshots of Create account (empty, terms unticked with the reason line) and Set password
   (no Turnstile) at both sizes.

### R3a — sign-in backend: identifier, forgot and reset, `/verify` link

Tasks 12 (backend), 17 (verification link). Files: the migration (§2 #1), `entity/
PasswordResetTokenEntity.java`, `repository/PasswordResetTokenRepository.java`, `AppUserRepository`
(`findAllByEmailIgnoreCase`), `service/PasswordResetService.java` (+ test),
`service/AuthenticationService.java` (`login` by identifier, `resetPassword` is the service's;
`RegistrationService.findPendingUserByEmail` moved onto the new query), `notification/
UserEmailService.java` (`sendPasswordResetLinkEmail`; `sendVerificationEmail`'s link →
`/verify?token=`), `templates/password-reset-link-email.html`, `controller/AuthController.java`
(`/forgot-password`, `/reset-password`), tests (`AuthenticationServiceTest`: email login one match /
two matches / none / username fallback; `PasswordResetServiceTest`: unknown, disabled and pending
emails send nothing; the fourth request in five minutes sends nothing and throws nothing; a used,
expired and unknown token each refuse with its sentence; success revokes every active refresh
token and stamps `used_at`; `AuthControllerTest`: both endpoints' statuses, forgot through the gate,
reset through the token limit, forgot ALWAYS 200 for an unknown email; `UserEmailServiceTest`: both
links' paths; `integration/PasswordResetTokenMigrationTest` CI-only), `HttpCachingConfigTest`
(nothing to add — `/api/auth/**` is never filtered; pin the two new paths anyway).

### R3b — sign-in frontend: forgot and reset pages, the label, the link, the routes

Files: new `components/auth/ForgotPasswordPage.jsx`, `ResetPasswordPage.jsx` (+ tests),
`LoginPage.jsx` (label, link, `onForgotPassword` prop), `App.jsx` (`AuthGate`: `showForgot` state;
`/reset` by pathname with `?token=`; the legacy `?token=` on any other path still verify mode; the
consumed URL replaced with `/`), `AuthContext.jsx` (`signInWithTokens(data)` — the body
`completeRegistration` has today, which now delegates to it and additionally sets
`registrationPending`; `finishRegistration()`; `registrationPending` exposed — §2 #2, built here so
R4b's gate change is one line), `authApi.js`, tests (`LoginPage.test.jsx`: identifier label, the
link calls the prop, the fallback still enables submit; `App.test.jsx`: `/reset?token=x` mounts the
reset page, `/verify?token=x` and `/?token=x` both mount verify mode, a consumed token leaves
`location.pathname === '/'`; `AuthContext.test.jsx`: `registrationPending` set by
`completeRegistration`, cleared by `finishRegistration`, `signInWithTokens` leaves it false).
Screenshots of Sign in (with the link), Forgot (empty and done), Reset (checklist) at both sizes.

### R4a — postcode backend: the prompt column, the login flag, the reverse lookup

Tasks 2 (backend), 3 (backend). Files: the migration (§2 #3), `AppUserEntity`, `AppUserRepository`,
`UserSettingsResponse`, `UserSettingsService` (`markPostcodePromptDismissed`),
`UserSettingsController` (`PUT /postcode-prompt-dismissed`, `POST /home/reverse`),
`model/AuthTokens.java` (+ `postcodePromptDue`), `AuthenticationService.issueTokensFor`,
`AuthController.buildAuthResponse`, `client/PostcodesIoClient.java` (`reverseLookup`),
`GlobalExceptionHandler` (§1 #11, if needed), tests (`UserSettingsControllerTest`: the PUT answers
the response with the instant set, the reverse's three statuses, LITE allowed on both;
`UserSettingsServiceTest`; `PostcodesIoClientTest` with a `MockRestServiceServer` or the file's
existing shape: a hit, an empty `result: []`, a `result: null`, a 500; `AuthenticationServiceTest`:
`postcodePromptDue` true with neither, false with a postcode, false with a dismissal;
`AuthControllerTest`: the login body carries it; `HttpCachingConfigTest`: the two paths;
`integration/PostcodePromptDismissedMigrationTest` CI-only). `UserSettingsRaceSequenceTest`
unchanged — the write is a scoped update like its siblings.

### R4b — postcode frontend: `PostcodeCapture`, the `HOME` step, the prompt page, Settings

Tasks 1, 2 (frontend), 3 (frontend), 4, 8. Files: new `components/auth/PostcodeCapture.jsx`,
`PostcodePromptPage.jsx`, `hooks/useGeolocationPostcode.js` (+ tests for each), `RegisterPage.jsx`
(`HOME`), `App.jsx` (the two-line gate change of §2 #2 and the prompt branch of §2 #3),
`AuthContext.jsx` (`postcodePromptDue`, `clearPostcodePrompt`), `settingsApi.js`
(`reverseLookupPostcode`, `markPostcodePromptDismissed`), `UserSettingsModal.jsx` (attributes, the
link, the autofocus rule — `(pointer: fine)` added to its existing focus landing for the `postcode`
field ONLY when the request did not come from a keyboard route; read its focus comments first and
leave the landings alone if the rule would fight them, saying so in the PR), tests
(`RegisterPage.test.jsx`: lookup → confirmation → Change restores the field; Skip → SUCCESS with no
save; Save → `saveHome(postcode, lat, lon, null)` exactly; the primary disabled until a result; the
HOME step is reached after set-password; `PostcodePromptPage.test.jsx`: shown by `AuthGate` when the
login response said due, hidden after Not now (`markPostcodePromptDismissed` called once, gate moves
on even when it rejects), hidden after Save; `useGeolocationPostcode.test.js`: the three error
strings against `PERMISSION_DENIED`/`TIMEOUT`/`POSITION_UNAVAILABLE` codes and a 400 from the
endpoint, the link absent with no `navigator.geolocation`; `App.test.jsx`: the gate order — a user
with `mustChangePassword` sees Change password BEFORE the prompt; `AppSettingsRoutes.test.jsx`
unchanged and still green). Screenshots: HOME empty / found / invalid and the prompt, at both sizes
(the first three without signing in — the step is reachable in a test harness only; render them
through the storybook-less route the other phases used, or a Vitest DOM snapshot plus the signed-in
prompt screenshot — state which).

### R5 — account deletion

Task 15. Files: `controller/AccountController.java` (new), `service/UserService.java`
(`deleteOwnAccount`, `purgeUserRows`, `deleteUser` through it), `repository/
UserDriveTimeRepository` (`deleteByUserId` if absent), tests (`AccountControllerTest`: 204, 400,
403 + the limiter's 429, 409 for an admin, LITE and PRO both allowed; `UserServiceTest`: a user WITH
drive times, ask usage and a rating is deleted and the email sent after commit; the admin path with
drive times no longer fails; a `@DataJpaTest` that proves the FK: without the purge the delete
throws); `UserSettingsModal.jsx` (Danger zone), `accountApi.js`, `UserSettingsModal.test.jsx` (the
dialog, the password requirement, a 403 kept inside the dialog, 204 → `logout`), `AuthContext` only
if `logout` needs a no-network variant (it does not: §2 #7). Screenshot of the dialog.

### R6 — native prep: AASA, nginx, privacy docs

Tasks 16, 18, and §1 #8's header. Files: `nginx.conf` (the AASA location; `geolocation=(self)` in
both header lines), `frontend/public/.well-known/apple-app-site-association`, `vite.config.js`
(only if the PWA glob would precache it — it should not), `docs/app-store-privacy.md`,
`landing/privacy.html`, a test under `frontend/src/test/` that reads `nginx.conf` and asserts the
permissions directive and the location block exist (the file-reading pattern `safeAreas.test.jsx`
uses on `index.html`), and one that parses the AASA file as JSON and asserts the four paths. The PR
body records the `dist/.well-known` proof. No code paths change.

### R7 — docs sweep

CLAUDE.md: the **Auth** line under *What's Built* (forgot/reset, identifier login, the Turnstile
fallback and limiter, the `HOME` step and the prompt, deletion); *API Endpoints* (`/forgot-password`,
`/reset-password`, `/home/reverse`, `/postcode-prompt-dismissed`, `DELETE /api/user/account`, the
AASA path); *Roles* (deletion open to every role but admin; the prompt); *User settings* (the ninth
`updatable = false` column); the migrations table (two rows); *Role gating — UI pattern* (a line:
the Pro line on the postcode screens is text with no link, App Store rule). `docs/engineering/
login-registration-plan.md` §0 closed out and §6 answered by what shipped. Memory file
`login-registration-series.md` + `MEMORY.md` line (the planning session writes memory; R7 the repo
docs).

---

## §4 Disagreements with the spec, on purpose

1. **Task 9 "front and back" — the back is built, not mirrored.** §1 #4: there was no backend
   behaviour to mirror. The fallback is one gate for every Turnstile-verified endpoint, with a limit
   keyed on the identifier, not the IP (§5 #4).
2. **The prompt rides the login response, not the settings read.** Task 2 says "if `homePostcode ==
   null` and the prompt hasn't been dismissed, show frame 4" — true, computed by the server at
   sign-in and carried as `postcodePromptDue`. The dismissal is server-side exactly as asked; only
   WHERE the client learns it changed (§1 #17, §2 #3). A reader who signs in on a second device
   before dismissing sees it there too — the spec's own "carries across devices".
3. **No reload after registration.** The `SUCCESS` step stays with its 2 s wait, but the wait ends
   in `finishRegistration()` rather than `window.location.reload()` (§1 #7: the reload never ran).
   The app mounts through the same `AuthGate` path a sign-in takes; nothing in the authenticated tree
   assumes a fresh document.
4. **Task 11's "username can be pre-filled from the email" (the HTML note) is not built.** A
   derived username invites `john.smith@` → `john.smith`, which the username rule rejects (dots).
5. **Settings keeps its own postcode field.** Task 3 wants "Use my location" in Settings and task 13
   wants one input style: both delivered as the hook, the link, the class and the attributes, not by
   replacing the field with `PostcodeCapture` (§1 #18: the modal's focus landings are a documented
   state machine with 110 tests).
6. **Task 7 is scoped to the auth wrappers.** The app's own safe-area system exists and is pinned
   (§1 #10); re-touching the top bar and bottom controls would be a second owner of the same rule.
7. **The forgot-password request has a Turnstile.** The spec does not mention one; without it the
   endpoint is an unauthenticated email-sender behind only a per-user DB count. It takes the same
   gate and fallback as sign-in, so a blocked browser still gets through.
8. **A rate-limited forgot request answers 200, not 429.** Task 12 asks for "the same rate limit as
   resend (3 per 5 min)"; resend answers 429, but a 429 here says "this email has an account".
9. **Reset signs the reader in.** Task 12 says the `/reset` page "reuses the Set password form" —
   and that form signs in on success, so the reset does too, and revokes every OTHER session as asked.
10. **An admin cannot delete their own account in-app** (409). Task 15 is unconditional; the
    existing admin rule and the sole-admin lockout decide it (§5 #7).
11. **The AASA components name `/verify` and `/reset` exactly plus a wildcard child**, not `/verify*`
    — Apple's `components` matcher treats `*` per segment, and the query form is listed separately
    so a bare `/verify` with no token is still claimed.
12. **The `PRO` tag is `ProPill`** where its size permits (the existing component: mono, uppercase,
    bordered) — one Pro marker app-wide rather than a second hand-built tag; the spec's 10px/0.12em
    geometry is matched by a `size` prop if `ProPill`'s differs, not by a parallel class.
13. **`inputMode="text"` is kept as written even though it is the default** — it documents the
    "never numeric" rule at the one place a future edit would make that mistake.

---

## §5 Decisions taken at plan time (defaults every phase builds on)

1. **Phase order R1 → R2 → R3a → R3b → R4a → R4b → R5, with R6 ∥ R1 and R4a ∥ R3b.** The restyle
   first so every later phase writes against the final classes; backend before its frontend so a
   frontend phase mocks nothing that does not exist on main.
2. **The dismissal is a timestamp, not a boolean** — the spec's own `postcode_prompt_dismissed_at`;
   it says when, which a support question will want.
3. **The prompt state is server-side + in-memory**, never `localStorage` (the HTML note's
   alternative): a second account on the same browser must never inherit a dismissal, the same rule
   that keeps every Ask and settings value out of module scope.
4. **The fallback limiter is keyed on the identifier, 5 per 15 minutes, in memory.** Not IP (§2 #4);
   not durable (a restart forgets counts — a rate limit on a single-instance app, the same acceptance
   `UserSettingsService.pendingDriveTimeAttempts` records). Stated residual: a distributed attacker
   can try five passwords per identifier per fifteen minutes with no token; today, with Turnstile
   down, they can try none and neither can the account's owner. The owner can revisit (§6 Q1).
5. **Reset tokens are a sibling table, not rows in `email_verification_token`.** A verification
   token is deleted on use and proves an address; a reset token is kept with `used_at` and proves a
   request — different lifecycles, and the admin's resend path deletes "unverified" tokens by user.
6. **No email-uniqueness migration.** Making the index case-insensitive would need a data check for
   existing case-duplicates CI cannot see; the login query tolerates the duplicate instead. Noted as a
   follow-up, not built.
7. **An admin's self-deletion is refused (409).** `UserService.deleteUser` already refuses an admin
   deleting themself; a sole admin deleting their account in-app would orphan the instance. Another
   admin deletes an admin, as today.
8. **`DELETE` with a JSON body**, as the spec names it; axios sends `data` on a DELETE and nginx and
   Spring both pass it. If a review shows a proxy stripping it, the fallback is `POST
   /api/user/account/delete` — decided then, not now.
9. **A failed `saveHome` in `PostcodeCapture` keeps the confirmation row and says "Couldn't save.
   Try again."** — the modal's own behaviour ("Save failed — leave lookup result visible for retry").
10. **Autofocus rule**: `(pointer: fine)` only, on the postcode field of the three new screens; the
    login and register forms keep today's behaviour (no autofocus) — the spec names the postcode
    field alone.
11. **The Pro line shows for every role**, including PRO and ADMIN (the spec's rule; the HTML note's
    role-specific wording is not built).
12. **`/forgot-password` validates the email's shape only**; it never says whether the address is
    known, in status, body or timing it can control (the work is the same for a known and an unknown
    address except the send, which runs after the response is decided).

---

## §6 Owner questions — none blocks a phase

- **Q1** Is the identifier-keyed fallback limit (5 per 15 min with no Turnstile token) acceptable,
  or should a missing token simply be refused as today (which keeps the dead end)? Default: the limit.
- **Q2** TEAMID and bundle id for the AASA file. Default: the literal `TEAMID.BUNDLEID` placeholder;
  the file is harmless until Apple reads a matching app.
- **Q3** Should the admin self-delete refusal stand (409), or should an admin who is not the last
  admin be allowed? Default: refuse.
- **Q4** `password-reset-link-email.html`'s sender and subject line — defaults in §2 #12.
- **Q5** Should the landing privacy page name a retention period for `ask_log` (90 days) and job
  logs? Default: name the 90 days, since CLAUDE.md states it.

---

## §7 Verification — what each phase checks

- **R1**: the two tokens resolve to non-empty values in the built CSS (`npm run build`, grep
  `dist/assets/*.css` for `--color-plex-error`); contrast ≥ 4.5 asserted; screenshots at 390 and
  1280 of Sign in and Create account show the card top-aligned at 390 with 48px above it and
  centred at 1280; no horizontal scroll at 320.
- **R2**: with `turnstile.secret-key` set in a test profile, `/login` with no token answers 200 up
  to five times and 429 on the sixth within the window; `/set-password` needs no token; Create
  account shows "Agree to the terms to continue" until the box is ticked.
- **R3a**: the full forgot → email → reset → signed-in → old refresh token refused sequence in one
  controller test with a captured link; `findAllByEmailIgnoreCase` returns both rows for
  `A@x` / `a@x` in a `@DataJpaTest`.
- **R3b**: `/reset?token=` renders the reset page with no backend call until submit;
  `/verify?token=` and `/?token=` both verify; a consumed link leaves `/`.
- **R4a**: the login body of a user with no postcode and no dismissal carries
  `postcodePromptDue: true`; after the PUT it carries `false`; the reverse endpoint on
  `54.776, -1.575` answers Durham in a WireMock/`MockRestServiceServer` test (never the live API).
- **R4b**: a local sign-in as a fresh `admin` shows the prompt; Not now opens the app and a second
  sign-in does not show it; Save shows the masthead light rule with no "set a postcode" nudge;
  Chrome's device toolbar at 390 with the keyboard open keeps the buttons visible (screenshot).
- **R5**: a PRO account with drive times is deleted (H2) and the next request with its old token is
  a 401; the admin's user list no longer shows it.
- **R6**: `curl -sI http://localhost:<nginx>/.well-known/apple-app-site-association` (the frontend
  image built locally, or a `docker run nginx` with the conf and the dist mounted — if neither is
  possible on this machine, say so and rely on the config test) shows `Content-Type:
  application/json` and `Permissions-Policy: geolocation=(self)…` on `/`.
- **Owner's browser check (after release)**: register a new account on a phone end to end (email
  first, no confirm, consent unticked, one Turnstile, `HOME` with Use my location, Welcome, the
  masthead light rule set); sign in on a second device with an account that has no postcode and
  see the prompt once; Forgot password from the phone's mail client (the `/reset` link opens the
  app); delete a throwaway account.

---

## §8 Process

Each phase is a **fresh Sonnet session in its own worktree**, launched by the planning session with
this plan's §3 phase text, the relevant §1/§2/§4/§5 entries and the spec pasted in (the plan is on
main once R0 merges; an agent's worktree is cut from `origin/main`, so a dependent phase must not
start until its predecessor's PR has MERGED). The session: builds → runs the gate → runs its review
lenses in the foreground → fixes → checks (§7) → writes its changelog.d entry and its phase-log
row → commits (conventional commit, the attribution trailer) → pushes its branch → opens the PR
(`gh pr create`, body ending with the generated-with line) → watches CI and Codex → fixes anything
either raises, pushing follow-up commits and asking `@codex review` after each → merges with
`gh pr merge --squash --match-head-commit "$(gh pr view <N> --json headRefOid -q .headRefOid)"`
only when CI is green AND Codex has answered for the HEAD sha. ⚠️ **Codex clean** is the "Codex
Review Summary" comment's row reading `✅ Completed` on the head's short sha **with no Codex review
posted against that sha** — the row names the head sha while still 🔄 Running, so the status word
matters; a 👍 reaction on the PR body is the same answer; 👀 is in progress, never clean; a
force-push does not re-trigger it. The planning session's watcher applies exactly that rule. The
planning session verifies each merge on `origin/main` before launching the next phase. The owner is
told only when a phase is genuinely blocked (a Codex finding the session cannot resolve with cited
evidence, a CI failure outside the diff) — never for a decision §5 already takes. ⚠️ Review agents
never write to the working tree (CLAUDE.md "UI Work — Review Cadence"); a reviewer that must mutate
gets `isolation: 'worktree'`.
