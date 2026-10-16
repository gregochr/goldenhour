# Login and registration enhancements — postcode capture, auth fixes, native prep (2026-10-16)

**Source**: the design bundle vendored at `docs/design/login-registration/` — `README.md` (the
specification, eighteen tasks in four groups) and `Postcode Capture.html` (eight static frames).
`docs/design/login-registration/VENDORING.md` records what was copied. Read the spec before any
phase; **this document is the port plan**: what the codebase already has (and where the spec guessed
wrong), what is genuinely new, how the work cuts into single-session phases for **Sonnet** sessions,
and every place the plan and the spec disagree on purpose. §5a records the plan-time adversarial
review (four lenses, 60 charges) and what each changed.

**The one-sentence version.** Registration gains a `HOME` postcode step and sign-in a one-time
prompt for accounts with no postcode (both with "Use my location"); the auth screens are made to
work on phones and to look like the rest of the app; marketing consent becomes opt-in; "Confirm
email" and the second Turnstile go; sign-in accepts an email, offers a real forgot-password flow and
a bounded backend fallback when Turnstile cannot load; a password reset or an account deletion now
ends every session at once; Settings gains account deletion; and the server is readied for the iOS
app (`apple-app-site-association`, clean `/verify` and `/reset` links, a privacy inventory).

**Cadence per phase** (CLAUDE.md "UI Work — Review Cadence"): build → tests → adversarial review of
the diff (prosecutor lenses + one refuter per charge, all read-only, run in the FOREGROUND so their
results return to the session that launched them) → fix survivors → the §7 checks → changelog.d
entry → commit → push → PR → CI and Codex green → merge. ⚠️ **The owner authorised push, PR and merge
for every phase of this series on 2026-10-16** ("Push and Open a PR. Merge once CI is green and Codex
is happy. Then move on to the next stage"); CLAUDE.md's standing "never push" rule is lifted for
exactly this series and nothing else — never tag, never run `release.sh` ("I'll release when we're
feature complete"). Backend gate before any push (JDK 23 on this Mac, see `no-jdk-25-on-this-mac`
in memory; set `SCRATCH` to the session's scratchpad directory first — unset, the redirect fails and
Maven never runs):

```bash
cd backend && export JAVA_HOME=/Users/chrisgregory/Library/Java/JavaVirtualMachines/openjdk-23.0.2/Contents/Home MAVEN_OPTS=--enable-preview && ./mvnw clean verify --batch-mode --no-transfer-progress -Djava.version=23 -Dmaven.compiler.enablePreview=true -Dtest='!**/integration/**,!EvaluationModelTest' -DfailIfNoSpecifiedTests=false >"$SCRATCH/v.log" 2>&1; echo "exit: $?"
```

Gate on the echoed exit code, never a grep; say in the PR body that the local gate ran on JDK 23
with `EvaluationModelTest` excluded and that CI's Backend job is the proof. Frontend gate, CI's own
four steps: `cd frontend && npm run lint && npm test -- --reporter=dot && ../scripts/npm-audit.sh
frontend high && npm run build` (run the audit script from the repo root: `./scripts/npm-audit.sh
frontend high`). "CI green" means every check on the PR: *Backend — Build, Test & Coverage*,
*Frontend — Lint, Test & Build*, ShellCheck and CodeQL (CodeQL's `java/log-injection` flags any new
log line carrying an email or identifier that does not go through `util/LogSanitizer`). Paste the
relevant section of THIS plan and the spec into every review agent's prompt — an agent cannot open a
path it was not given.

**Check open PRs first.** `gh pr list --state open` and grep the titles for *login*, *register*,
*auth*, *postcode*, *settings*, *turnstile*. Checked 2026-10-16 at plan time: three open PRs (#1132
a jsdom bump, #843 and #815 docs), none touching any file this plan names.

---

## §0 Status

| phase | PR | merged | notes |
|---|---|---|---|
| R0 plan | | | this document, the vendored bundle, `changelog.d/20261016-login-registration-plan.md` |
| R1 auth visual foundation (frontend, not Settings) | | | |
| R2a Turnstile gate, attempt limiter, set-password without Turnstile (backend) | | | |
| R2b registration form, Turnstile fallback on the forms (frontend) | | | |
| R3a sign-in backend: identifier, forgot/reset, `/verify` link | | | |
| R3b sign-in frontend: forgot/reset pages, label, link, `registrationPending` | | | |
| R3c token invalidation: `credentials_changed_at` and the filter | | | |
| R4a postcode backend: prompt column + flag, reverse lookup | | | |
| R4b postcode frontend: `PostcodeCapture`, `HOME`, the prompt overlay | | | |
| R4c Settings: `.field`, tokens, copy, Use my location, attributes | | | |
| R5a account deletion (backend) | | | |
| R5b account deletion (Settings danger zone) | | | |
| R6 native prep: AASA, nginx, `nginx -t` in CI | | | |
| R7 docs sweep + privacy docs | | | |

⚠️ **No build phase edits this file.** Two phases run in parallel and their §0 rows are adjacent
lines, which git treats as a conflict; the planning session tracks status in its memory and R7 fills
this table from the merged PRs. A phase that finds a plan statement wrong says so, with file:line,
in its final report; the planning session folds it into R7 or into the next phase's brief.

---

## §1 What the codebase already has — corrections to the spec

The spec's "check these first" list, answered, plus what else the survey and the plan review turned
up. Numbered so the phases can cite them.

1. **No user-facing password reset exists.** `PUT /api/users/{id}/reset-password` is the ADMIN's
   temp-password reset (`UserService.resetPassword` → `PasswordResetResult` →
   `UserEmailService.sendPasswordResetEmail`, template `password-reset-email.html`, which mails a
   TEMPORARY PASSWORD, not a link). `LoginPage.jsx` has no forgot link and nothing in `frontend/src`
   mentions one. The flow is built in R3a/R3b. The new template is `password-reset-link-email.html`;
   the old one and its admin path are untouched.
2. **Account deletion exists for admins only.** `DELETE /api/users/{id}` →
   `UserService.deleteUser(id, currentUsername)` (refuses self-delete, then
   `userRepository.delete(user)`, then `sendAccountDeletedEmail` — an `@Async` send made INSIDE the
   transaction, so a delete that fails at commit has already mailed "your account was removed").
   There is no self-service endpoint and no Settings control. ⚠️ **The FK cascades are
   incomplete**: `refresh_token`, `email_verification_token`, `ask_usage` and `outcome_rating`
   cascade, `ask_log` sets null — but `user_drive_time.user_id` (V67) has **no `ON DELETE` clause at
   all**, so a whole-entity delete of a user who has drive times violates the FK in Postgres. ⚠️ **H2
   cannot show this**: the test profile is `ddl-auto: create-drop` and `UserDriveTimeEntity` maps
   `userId` as a plain `Long` (no `@ManyToOne`), so the H2 schema has no FK and a negative test there
   passes with or without the fix. R5a deletes the drive-time rows explicitly
   (`UserDriveTimeRepository.deleteAllByUserId` already exists) through one `purgeUserRows` step both
   delete paths call, moves BOTH paths' email after commit, and proves the FK in a CI-only
   `integration/` test on Postgres + Flyway. No push-subscription table exists (Web Push is a
   planned feature) and no other table is keyed on `username`, so there is nothing else to purge.
3. **Set-password validates Turnstile server-side.** `AuthController.setPassword` calls
   `turnstileService.verify(body.get("turnstileToken"))` and answers 400 "CAPTCHA verification
   failed" on a miss. R2a removes it and puts a token-keyed attempt limit in its place (§2 #4). The
   token itself is a 122-bit random UUID, the complexity check runs before the lookup, and
   `/verify-email` has never had a Turnstile — removing this one opens nothing.
4. **Login with Turnstile unavailable has NO backend handling — the frontend's promise is empty.**
   `TurnstileService.verify` returns `true` only when `turnstile.secret-key` is blank (local dev);
   with a key configured a null or blank token returns `false`, so in production a sign-in from a
   browser where the script failed to load is a 400 — the `LoginPage` line "Security check unavailable
   — we'll verify your sign-in another way" promises something nothing does. Task 9's "mirror the
   login behaviour, front and back" therefore has nothing to mirror on the back. R2a builds a BOUNDED
   fallback once, as `TurnstileGate` (§2 #4), for login, change-password and forgot-password — and
   deliberately NOT for registration (§4 #1, §5 #4). ⚠️ Scope note: the frontend fallback covers
   only `onLoadFail`; `TurnstileWidget` routes the challenge's `error-callback` to `onExpire`, so a
   script that loads but whose challenge errors still leaves the submit disabled — a narrower dead
   end this series does not close (recorded, not fixed).
5. **Emails are unique but case-SENSITIVE, and the existing matching is unsafe.** V33 created
   `idx_app_user_email` as a plain `UNIQUE INDEX ON app_user(email)` (nulls allowed for the seeded
   admin). `AppUserRepository.findByEmail` and `existsByEmail` are exact-match (so `Victim@x` can be
   registered beside `victim@x`); `RegistrationService.findPendingUserByEmail` loads EVERY user and
   filters with Java `equalsIgnoreCase` — a full-table scan per resend that also folds `ı`→`I`, and
   then mails the TYPED address, so a confusable IDN address (`bob@gmaıl.com`) can receive the token
   for someone else's pending registration. `EmailValidator` accepts any non-whitespace Unicode. The
   rules every email match in this series follows (`util/EmailIdentifier.resolve`, §2 #10), in order:
   the identifier is trimmed; **first an EXACT match** (`findByEmail`, no folding of any kind — the
   comparison that is safe for every address, and the only one a non-ASCII address gets); then, only
   when the trimmed identifier is pure ASCII and the exact match found nothing, the case-insensitive
   path — an explicit JPQL `LOWER(u.email) = :email` with the parameter lower-cased in Java with
   `Locale.ROOT` (never a derived `IgnoreCase` method, which emits `UPPER()` and lets Postgres fold
   `ı`→`I`); only ACTIVE rows count (enabled, non-empty password) for sign-in and reset, the pending
   predicate for resend; two or more matches are no match; and mail always goes to the STORED
   `user.getEmail()`, never to the request's text. An account that already registered a non-ASCII
   address (today's `EmailValidator` accepts any non-whitespace Unicode) therefore keeps signing in,
   resending and recovering by typing it exactly as registered (Codex, R0 round 3); going forward
   `/register` refuses a non-ASCII address with "Please use an email address without accented or
   non-Latin characters" (§4 #21, §6 Q7), while admin create is unchanged. R3a
   moves the pending-user scan onto the same rule and makes register and admin create refuse a
   case-insensitive duplicate. **No `citext`/functional-index migration in this series** (§5 #6).
6. **The `app_user` settings columns are `updatable = false` and written by scoped updates.**
   `postcode_prompt_dismissed_at` copies `coming_up_last_seen_at` exactly: nullable column, `updatable
   = false` field, `AppUserRepository.updatePostcodePromptDismissedAtByUsername` (the
   `updateComingUpLastSeenAtByUsername` shape), `UserSettingsService.markPostcodePromptDismissed`
   (the `markComingUpSeen` shape, re-reading the row for the response), `PUT
   /api/user/settings/postcode-prompt-dismissed` with no body.
7. **The registration `SUCCESS` step is unreachable today.** `handleSetPassword` calls
   `completeRegistration(data)` and then `setStep(SUCCESS)` in one batched update; `completeRegistration`
   sets the token, `AuthGate` re-renders, `!token` is false, and the gate returns `<RewindGate/>` —
   `RegisterPage` unmounts in that same commit, so the `SUCCESS` effect (and its 2 s reload timer)
   never commits at all. New users land in the app straight from Set password; nobody has seen
   "Welcome to PhotoCast!". A `HOME` step after the token exists needs the gate to keep `RegisterPage`
   mounted: `AuthContext.registrationPending` (§2 #2). The spec's "the `SUCCESS` step and its 2s
   reload stay" is honoured as a 2 s Welcome that ends the pending state — no reload (§4 #3).
8. **`nginx.conf` disables geolocation for the page itself.** Both the `/` and `/assets/` blocks
   send `Permissions-Policy: geolocation=(), camera=(), …` — `navigator.geolocation` rejects in the
   document under that header, so "Use my location" is dead until the directive reads
   `geolocation=(self)`. `nginx.conf` is bind-mounted into the frontend container
   (`docker-compose.yml`: `./nginx.conf:/etc/nginx/nginx.conf:ro`) and `deploy.yml` writes it from
   the tag and recreates the container, so the change ships with the deploy. ⚠️ **Nothing checks the
   file's syntax before production** — no CI step runs `nginx -t`, this machine has neither nginx nor
   Docker, and the container has `restart: always`, so a stray brace takes `app.photocast.online`
   down until a rollback. R6 owns every `nginx.conf` edit in this series (the header and the AASA
   location) and adds the CI step (§2 #8). An installed PWA picks the new header up only when its
   precached `index.html` revision changes — any release with a JS change does that.
9. **Email links are bare `?token=` on the app origin.** `UserEmailService.sendVerificationEmail`
   builds `frontendBaseUrl + "?token=" + rawToken` (`app.frontend-base-url`, production
   `https://app.photocast.online`); `App.jsx`'s `AuthGate` reads `?token=` from ANY path and replaces
   the URL with `window.location.pathname` once consumed. `/verify?token=` already reaches the app
   (nginx `try_files … /index.html`; the PWA's `navigateFallback: '/index.html'` with a denylist of
   `/api` and `/actuator` only). R3a changes the link to `/verify?token=`; the gate keeps accepting
   the legacy bare form for links sent in the 24 h before the deploy and replaces the consumed URL
   with `/`, not the pathname. `/reset?token=` is a NEW route the gate tells apart by `pathname`, and
   it takes precedence over an existing session (§2 #1, §4 #15).
10. **Safe areas are already a system.** `index.css` declares `--safe-t/-r/-b/-l/-v/-h` once on
    `:root` with `0px` fallbacks and `.app-safe`/`.app-safe-modal` rules (the modal one resolves its
    gutter with `max()`); `index.html` already carries `viewport-fit=cover`; `safeAreas.test.jsx` pins
    that every `env(safe-area-*)` in the stylesheet carries a fallback and that the six insets are
    declared ONCE. The auth wrapper therefore reads the vars through `max()`, never a raw `env()`
    (§2 #9). Task 7's "the app's top bar and bottom-anchored controls" is already covered by that
    system; R1 touches the auth wrapper only (§4 #6).
11. **Postcode lookup errors.** `PostcodeLookupException extends RuntimeException` (not
    `IllegalArgumentException`, the only one `GlobalExceptionHandler` maps to 400), and
    `UserSettingsController.lookupPostcode` does not catch it — so an invalid postcode may answer 500
    today; `UserSettingsModal` maps ANY failure to "Invalid postcode", which hides it. The client
    also throws that ONE exception type for an upstream failure and for "no such postcode". R4a's
    first step measures the status with a controller test; it then splits the client's failure into
    `PostcodeLookupException` (not found → 400 `{error}`) and `PostcodeLookupUnavailableException`
    (transport/5xx → 502 `{"error": "Postcode lookup is unavailable right now"}`), mapped in
    `GlobalExceptionHandler`, **never echoing the upstream message** (the modal's own "Invalid
    postcode" mapping is unchanged either way).
12. **Rate limits.** The only auth-side limit is `RegistrationService.resendVerification`'s DB count
    of tokens per user (3 in 5 min → 429) — ⚠️ **and it can never trip**: the method counts, then
    DELETES every unverified token for the user, then inserts one, so the count is at most 1 whatever
    the call rate (`RegistrationServiceTest` only ever mocks the count to 3). `POST /home/lookup` has
    none. The Ask stack has an in-memory per-user sliding-window limiter (`service/ask/AskRateLimiter`,
    a `ConcurrentHashMap`, not Caffeine) — a model in shape, not reused. R2a's `AuthAttemptLimiter`
    (§2 #4) is the one limiter this series adds, and R3a puts BOTH resend and forgot on it (the DB
    count stays as a harmless second check).
13. **`ChangePasswordPage` is the same Turnstile dead end.** It mounts a `TurnstileWidget` with no
    `onLoadFail`, and `/change-password` verifies the token server-side — the first-login password
    gate is unreachable from a blocked browser. R2a/R2b give it the bounded fallback.
14. **The password checklist is duplicated** (`CheckItem` in `RegisterPage.jsx` and
    `ChangePasswordPage.jsx`, identical). R1 extracts `PasswordChecklist` and `PasswordInput` (the
    field with the show/hide eye) once; R3b's reset page is their third consumer.
15. **Marketing opt-in defaults to true on BOTH sides.** `RegisterPage` `useState(true)`, and
    `AuthController.register` parses `!"false".equalsIgnoreCase(body.get("marketingEmailOptIn"))`
    — an absent field opts the account IN. The flip is both: the client default (R2b) and the server's
    reading of an absent value (`"true".equalsIgnoreCase(...)`, R2a), because the iOS client will call
    this endpoint too and an omitted field must never be consent.
16. **`AuthTokens`/`buildAuthResponse` need no change for the prompt.** The first cut carried a
    `postcodePromptDue` flag on the login response; the review showed `AuthController.refresh` extends
    the session by 30 days on every refresh, so an existing user never signs in again and would never
    be prompted. The flag now rides `GET /api/user/settings` (§2 #3), which `useReaderSettings` already
    reads once at every mount of the authenticated app — one source, no extra request.
17. **`App.test.jsx` (60 tests) and `AppSettingsRoutes.test.jsx` (19) mock `getSettings` to `{}` or
    a no-home fixture and never go through `login()`.** The prompt therefore keys on a STRICT
    `settings.postcodePromptDue === true` (a server-computed boolean), so a mock without the field
    never prompts and no existing test changes; the field is added to the fixtures of exactly the new
    tests that want the overlay.
18. **`UserSettingsModal` is 847 lines with its own focus-landing state machine** (110 tests in its
    file). Its postcode field is not swapped for the shared component, and its explicit
    `focusField === 'postcode'` landing is NOT gated on the pointer rule — it is the signpost the
    masthead nudge and the map's ⌂ open the dialog FOR, the test setup stubs `matchMedia` to `false`,
    and three existing tests assert that focus. R4c adds only the `.field` class, the status tokens,
    the task-14 copy, the task-4 input attributes and the "Use my location" link below the field (§4
    #5). `UserSettingsModal.test.jsx` asserts the old task-14 sentence and changes with it.
19. **The JWT filter authenticates from the token's claims ALONE.** `JwtAuthenticationFilter` sets
    the `SecurityContext` from the JWT's subject and role before it ever touches the database; its
    `findByUsername(...).ifPresent` afterwards only stamps `lastActiveAt` (at most hourly) and gates
    nothing. So a deleted account's access token keeps authenticating for up to 24 h (production
    expiry), a disabled account's likewise, a password reset "signs other devices out" only when their
    access token expires, and a username freed by a self-service deletion could be re-registered while
    the old token, with its old ROLE claim, still answers for that name. R3c closes all of it with one
    rule in the filter (§2 #13): no authentication unless the row exists, is enabled, the token's `uid`
    is the row's id and its `cv` claim equals the account's `credentials_version` counter. The filter already pays that one
    query per request; the check reorders it, it does not add one.
20. **`landing/privacy.html` is stale for this work.** Line 36 lists the postcode as "if you choose to
    set a home location for drive time calculations — Pro feature" and line 51's processor row says
    postcodes.io receives "UK postcodes … for drive times"; since the masthead light rule the postcode
    serves every account, and "Use my location" will send device coordinates. The landing site is
    deployed by hand (`landing/README.md`: not part of the tagged release), so R7 — after deletion
    exists — rewrites those lines and the owner redeploys landing after the release (§7).
21. **Two origins.** The landing site is `photocast.online` (its own nginx, `landing/`); the app is
    `app.photocast.online` (root `nginx.conf`). `/verify`, `/reset` and therefore the AASA file live
    on the APP origin; the Terms and Privacy links in `RegisterPage` point at the landing origin and
    stay.
22. **`TurnstileWidget` already has `onLoadFail`** (10 s script timeout and a render failure both
    call it); `LoginPage` is its only consumer. R2b wires the register and change-password forms to it.
23. **The client IP IS available, with a caveat.** The backend's `remoteAddr` is the nginx
    container; nginx sets `X-Forwarded-For` (`$proxy_add_x_forwarded_for`) and forwards the client's
    own headers, `CF-Connecting-IP` included. But `docker-compose.yml` publishes 80 and 8082 on the
    host, so a caller who reaches the host directly can forge both. The address is therefore an
    ADDITIONAL bound in §2 #4 (never the only one), read as `CF-Connecting-IP`, else the first
    `X-Forwarded-For` hop, else `remoteAddr`.
24. **`AbstractControllerTest` is one shared `@SpringBootTest` context** with `TurnstileService`,
    `AppUserRepository`, `RefreshTokenRepository`, `RegistrationService` and `UserEmailService` as
    `@MockitoBean`s. A REAL in-memory limiter in that context keeps counts across test classes and
    makes results order-dependent; an unstubbed mocked `isConfigured()` answers false and makes
    every gate test vacuous. §2 #4 and the R2a/R3a test plans are written for that: the gate and the
    limiter are mocked in the base class and unit-tested on their own, and the one end-to-end reset
    sequence runs in its own `@SpringBootTest` against real H2 repositories.
25. **`ProPill` is not a tag.** `components/shared/ProPill.jsx` renders sentence-case "Pro" in sans
    11px (an inline `style`), `bg-plex-surface-light rounded-md`, no border, no `size` prop, and is
    shared by Ask and the rating lock. The spec's `PRO` tag (mono 10px, 0.12em, uppercase, 1px
    `border-light`, 3px radius, 1px 5px padding) is a new `components/auth/ProTag.jsx` built from
    Tailwind classes alone (§2 #6); `ProPill` is untouched.
26. **`ConfirmDialog` cannot disable its confirm button and `UserSettingsModal`'s `Modal` takes no
    `stacked`** — a `ConfirmDialog` opened from Settings would put two `aria-modal` dialogs on screen,
    the exact thing CLAUDE.md's Plan-tab invariant forbids. R5b's confirmation is therefore an INLINE
    region inside the settings dialog, not a second dialog (§2 #7, §4 #17).
27. **The sign-in prompt's three remaining sources of a `getSettings` read are the two existing ones.**
    `useReaderSettings` reads on mount; `UserSettingsModal` reads on open; `App.test.jsx` counts those
    calls (`toHaveBeenCalledTimes`). R4b adds NO read: the overlay consumes `useReaderSettings`'
    answer and the dismissal PUT's response goes through the hook's existing `homeSaved`-style
    answer path (`startSettingsRead`), so the counts stand.

---

## §2 What is genuinely new

### #1 Password reset (R3a backend, R3b frontend)

**Table** `password_reset_token` (next free `V` — re-read `ls backend/src/main/resources/db/migration
| sort -V | tail -1` at phase time; V178 is the latest at plan time): `id` identity PK, `token_hash
VARCHAR(64) NOT NULL UNIQUE`, `user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE`,
`expires_at TIMESTAMPTZ NOT NULL`, `used_at TIMESTAMPTZ NULL`, `created_at TIMESTAMPTZ NOT NULL`,
an index on `(user_id, created_at)`. The V33 shape with `used_at` in place of `verified` and
instants, not naive timestamps (`Instant` on the entity). Entity `PasswordResetTokenEntity`,
repository with `findByTokenHash`, `deleteByUserIdAndUsedAtIsNull`, and the compare-and-set
`@Modifying int markUsed(id, now)` = `UPDATE … SET usedAt = :now WHERE id = :id AND usedAt IS NULL`
(the `revokeIfActive` precedent — two concurrent redemptions of one token must not both succeed).

**`POST /api/auth/forgot-password`** `{email, turnstileToken?}` → **always** 200
`{"message": "If that email has an account, we've sent a reset link."}` for a syntactically valid
email (400 `{error}` for a blank or invalid one, the `register` shape); through `TurnstileGate`
(§2 #4; the fallback key is the normalised email). `PasswordResetService.requestReset(email)`:
resolve the user by the §1 #5 rule (ASCII, trimmed, `Locale.ROOT` lower-case, active rows only,
exactly one); none → log at DEBUG through `LogSanitizer`, return; then
`limiter.tryAcquire("forgot:user:" + userId, 3)` over 15 minutes → refused → log at INFO, **return
200 silently** (never 429: a 429 says the account exists); else delete that user's unused tokens,
mint `jwtService.generateRefreshToken()`, store its hash with `expires_at = now + 1 h`, send
`sendPasswordResetLinkEmail(user.getEmail(), username, rawToken)` — the STORED address — whose link
is `frontendBaseUrl + "/reset?token=" + rawToken` (template `password-reset-link-email.html`, the
verification email's layout, copy in §2 #12). The response is decided before the send runs, so a
known and an unknown address take the same path up to it. Stated exposure, not hidden: register
already answers 409 "Email already registered", so email existence is not a secret this endpoint
could keep; what it keeps is the link.

**`POST /api/auth/reset-password`** `{token, password}` → 400 `{error}` for a missing field, a
complexity failure (`validatePasswordComplexity`'s own sentence), an unknown token ("This reset link
is invalid"), a used one ("This reset link has already been used"), an expired one ("This reset
link has expired"), or a token whose user is disabled or pending ("This reset link is invalid" — a
reset must not sign a banned account in); through the three set-password-shaped bounds of §2 #4 (`reset:token:` + the token hash's first 16
hex chars 10 per 15 min, `reset:client:<addr>` 20 per 15 min, `reset:global` 200 per hour). On success: encode the password
BEFORE opening any transaction (`AuthenticationService`'s own javadoc forbids BCrypt inside one),
then ONE proxied transactional operation — in R3a a `PasswordResetService`-owned
`@Transactional` collaborator `PasswordResetRedeemer.redeem(tokenId, userId, encodedPassword, now)`
(its own bean, never a self-invoked method) that locks the user row, **re-checks on the locked row
that the account is still enabled and not pending** (the pre-hash check is stale by then — an admin
who disabled the account during BCrypt must win, or the captured link sets a password that becomes
live on re-enable; Codex, R0 round 19; refused → "This reset link is invalid", the token left
unconsumed), then `markUsed`s the token by compare-and-set (0 rows → "already been used"), sets the
password, clears `passwordChangeRequired` and `revokeAllActiveByUserId(userId)`; in R3c that operation becomes
`CredentialChangeWriter.consumeResetAndApply(tokenId, userId, encodedPassword, change, now)` (the
sibling of the verification one in §2 #13) so the stamp joins the same transaction — a failure
between consuming the token and changing the password can neither burn a valid token nor leave a
changed password with a reusable token (Codex, R0 round 8); after it, `issueTokensFor(redeemed, encodedPassword)` and `buildAuthResponse`, where `redeemed` is
the row the redeemer RE-READS and returns after its writes — never the entity resolved before
hashing, whose `passwordChangeRequired` could still read true for an account carrying an
admin-issued temporary password and route the reader straight into the change-password gate the
reset just cleared (Codex, R0 round 18; `PasswordResetServiceTest` pins `passwordChangeRequired:
false` in the response for such an account) — the reader is signed in on the device that reset, as
set-password does. **Every OTHER device is signed out at its next request once R3c lands**;
until then, when its access token expires (≤ 24 h) — R3a's PR body says which. Never Turnstile (the
emailed token is the proof, task 11's own reasoning).

**Client** (R3b): `ForgotPasswordPage` (email field, Turnstile with the load-fail fallback, "Send
reset link", the always-200 sentence shown as the done state, "Back to sign in") reached from the
"Forgot password?" link; `ResetPasswordPage` mounted by `AuthGate` when `window.location.pathname ===
'/reset'` and `?token=` is present — **before** the token test, so a reader already signed in on that
browser still gets the reset page (the common case: the link is opened on the phone the account is
signed in on; §4 #15). ⚠️ **The reset token is its own state, `resetToken`, read from the URL only
when the pathname is `/reset`, and the gate's existing cleanup effect — which clears `verifyToken`
and the query string whenever a stored token AND a URL token coexist — is left to the VERIFICATION
token alone** (Codex, R0 round 19: with one shared state the page would mount once and be unmounted
by that effect before the reader could submit); the reset page clears `resetToken` and replaces the
URL itself, on success or on "Request a new link". `App.test.jsx` pins that the signed-in reset page
survives a re-render and submits — with `PasswordInput` × 2 + `PasswordChecklist`, "Set new password" →
`signInWithTokens(data)` then `history.replaceState({}, '', '/')`; an invalid/used/expired token
shows the server's sentence and a "Request a new link" button that goes to `ForgotPasswordPage`.
`authApi.js` gains `requestPasswordReset(email, turnstileToken)` and `resetPassword(token, password)`.

### #2 `registrationPending` and the `HOME` step (R3b builds the context flag, R4b the step)

`AuthContext` gains an in-memory boolean `registrationPending` (never storage), set `true` by
`completeRegistration(data)`, cleared by a new `finishRegistration()` AND by `logout()` and the
session-expired handler (a session-expired event during `HOME` must not leave the gate on
`RegisterPage` with no token). `completeRegistration` delegates to a new `signInWithTokens(data)`
(its present body) and adds the flag; the reset page calls `signInWithTokens` alone. `AuthGate`'s
first test becomes `if (!token || registrationPending)`, and inside it the verify branch becomes `if
(verifyToken || registrationPending)` so the SAME `<RegisterPage>` element (same type, same tree
position, no key) stays mounted while the token already exists — React preserves the instance and
its `step` state; a `verifyToken` prop that goes null after mount is harmless (`handleVerify` runs
only when the prop is truthy, and `SET_PASSWORD` is reached only through it, so registration always
starts with the prop set). `RegisterPage` (R4b): `STEPS` gains `HOME` between `SET_PASSWORD` and
`SUCCESS`; `handleSetPassword` → `completeRegistration(data)` → `setStep(HOME)`; `HOME` renders
`PostcodeCapture` (§2 #6) with the task-1 copy, matching frames 1–3; Save → `SUCCESS`; **Skip → `markPostcodePromptDismissed()` (awaited, a failure ignored) AND
`AuthContext.suppressPostcodePrompt()` → `SUCCESS`** — "Skip for now" IS the reader's dismissal, and
without it the app's first settings read would compute `postcodePromptDue: true` and raise the prompt
overlay over the Welcome the reader just skipped past (Codex, R0 round 2; the spec's own frame-2
note says a skip "goes straight to Welcome" and leaves the masthead's "set a postcode" link to cover
it, which is exactly what the dismissal does). `suppressPostcodePrompt()` sets an in-memory
`postcodePromptSuppressed` on `AuthContext` (cleared by `logout` and session expiry, never stored)
that the overlay's mount condition reads, so a PUT that FAILS cannot bring the prompt back over the
Welcome or the app this page session — the dismissal is retried at the reader's next mount, the safe
direction (Codex, R0 round 7); the overlay's own Not now sets it too, for the same reason;
`SUCCESS` keeps its copy and its 2 s timer, whose body is `finishRegistration()` (and the URL
cleanup) instead of `window.location.reload()`. The lookup and save calls go through the ordinary
`apiClient`, which reads the token `completeRegistration` has just stored. A page reload during
`HOME` lands in the app with no postcode (the pending flag is memory) — the masthead's "set a
postcode" nudge and the prompt overlay (§2 #3, which the settings read then raises) both cover it.
⚠️ **The mutation pin** (R4b, `App.test.jsx`): render `/verify?token=x` through the real
`AuthProvider` + `AuthGate` with `verifyEmail` and `setPasswordForNewUser` mocked, walk Set password,
assert the `HOME` heading is on screen and no app shell is mounted, press Skip, see `success-step`,
advance 2 s with fake timers, see the app; the test must FAIL with `|| registrationPending` removed
from either branch (`RegisterPage.test.jsx` renders the page without the gate, so it cannot catch
this).

### #3 The one-time sign-in prompt (R4a backend, R4b frontend)

**Column** `app_user.postcode_prompt_dismissed_at TIMESTAMPTZ NULL` (next free `V`; no backfill —
null is "never dismissed", the spec's "existing users without a postcode see it once"). Entity field
`Instant postcodePromptDismissedAt`, `updatable = false`, written only by
`AppUserRepository.updatePostcodePromptDismissedAtByUsername(username, at)` (§1 #6).
`UserSettingsResponse` gains `postcodePromptDismissedAt` (nullable) **and `boolean
postcodePromptDue`** = `homePostcode == null && postcodePromptDismissedAt == null`, computed in
`UserSettingsService.getSettings` (and on every path that builds the response). **`PUT
/api/user/settings/postcode-prompt-dismissed`**, no body, `isAuthenticated()`, answers the settings
response like `/coming-up-seen`; added to `HttpCachingConfigTest.personalDataPathsAreNeverFiltered`.
Idempotent: a second dismissal overwrites the instant. `JsonDateFormatContractTest` constructs
`UserSettingsResponse` and must pin the new `Instant`.

**The client source is the mount read, nothing else.** `useReaderSettings` exposes
`postcodePromptDue` (`settings?.postcodePromptDue === true`, STRICT — §1 #17) from its existing
mount read and lets every later answer (`startSettingsRead`, `homeSaved`) update it. `AppInner`
mounts `PostcodePromptOverlay` when `postcodePromptDue && !promptDone && !promptSuppressed &&
!modalPresent && getRewindTo() == null` (`promptDone` a local `useState`, set by Save and Not now;
`promptSuppressed` is `AuthContext`'s in-memory flag below; `modalPresent` is a new
`hooks/useModalPresent.js` — `true` while any FOREIGN `[role="dialog"][aria-modal="true"]:not([data-
postcode-prompt])` exists in the document, kept current by a `MutationObserver` on `document.body`
(`childList`, `subtree`, `attributeFilter: ['aria-modal']`) and read once at mount; the overlay's own
dialog node carries `data-postcode-prompt` and is excluded by that selector, otherwise its own mount
would flip the flag and unmount it in a loop (Codex, R0 round 10 — a test mounts the overlay and
asserts it is still there after the observer has run); once mounted it stays until Save or Not now
(nothing under an inert app can open a dialog); a rewound admin is never prompted — the
rewind is a read of the past, not a sign-in). A slow first read lets the reader open Settings, the
map overlay, a Plan dialog or the Ask sheet first, and a late `postcodePromptDue` would otherwise
put the overlay over an open `aria-modal` dialog and make it inert underneath — two modal claims, the
exact thing CLAUDE.md's one-modal invariant forbids (Codex, R0 rounds 7 and 9; the DOM test is the
only one that sees every dialog, including those the shell owns and `AppInner` cannot read). The
overlay therefore waits until the last dialog closes; a postcode saved in Settings meanwhile ends the
question anyway. `App.test.jsx` pins: Settings open + a due answer → no overlay, and the overlay
appears when it closes; the same for the map overlay (`mapOverlay`); and the overlay never mounts
while its own predecessor is still in the DOM. **An overlay, not a gate**: the app is already mounted
when the answer arrives (one request after mount; at a fresh sign-in that is before the briefing has
painted), so the prompt is a `role="dialog" aria-modal="true" aria-labelledby` page portalled to
`document.body` (`createPortal`, a sibling of `#root`, so it is NOT inside the subtree it makes
inert), `fixed inset-0 z-[1400] overflow-y-auto bg-plex-bg` (above `MapView`'s `z-[1200]`;
R4b screenshots it over the Map tab), laid out like the auth pages (`AuthShell`'s column and
lockup); it sets `inert` on the app container with the SAME layout-effect shape
`WindowFirstShell` uses for the Ask sheet (walk up from a ref to the child of `<body>`, never
`<body>` itself, never if something else already holds it, remove in cleanup — copy the twelve
lines with a comment naming the original; jsdom has no `inert`, tests assert the attribute). Focus
moves into the dialog on mount (the heading, `tabIndex={-1}`, or the field under the pointer rule);
Escape does nothing — Save and Not now are the two exits. Nothing under it can open a dialog, so the
Plan-tab modal invariants and `AppSettingsRoutes.test.jsx` are untouched; `App.test.jsx` pins that
while the overlay shows, the app container carries `inert` (the spec's "check it doesn't open while
the Settings dialog is open", answered structurally and by test).

**`PostcodePromptOverlay`** = `PostcodeCapture` with the task-2 copy (frame 4). Save:
`PostcodeCapture` saves (§2 #6) and calls `onSaved(updated)` → the overlay hands the response to
`useReaderSettings.homeSaved` (the masthead light rule updates without a reload) and sets
`promptDone`. Not now → `markPostcodePromptDismissed()` (await; on failure log nothing, proceed — the
app must open either way, and a failed dismissal means one more prompt at a later mount, the safe
direction), the response through `startSettingsRead`'s answer path, then `promptDone`. A reload while
the prompt is up raises it again (the dismissal is the only durable state) — consistent with the
spec's "shown once" being "until dismissed or a postcode exists".

### #4 `TurnstileGate` and `AuthAttemptLimiter` (R2a)

`service/AuthAttemptLimiter`: TWO Caffeine caches, `expireAfterWrite(15 min)` and
`expireAfterWrite(1 h)` (`Window.QUARTER_HOUR` / `Window.HOUR`), each `maximumSize(100_000)`, keys
`String`, values `AtomicInteger` obtained with `get(key, k -> new AtomicInteger())` and incremented
in place — so the entry's write time is the FIRST attempt and the window is fixed from it: a locked
key recovers one window after its first attempt, never sliding; `boolean tryAcquire(String key, int
limit, Window window)` answers `incrementAndGet() <= limit`; an injectable `Ticker`; `void clear()`
for tests. ⚠️ The per-endpoint `global:` keys live in the HOURLY cache (Codex, R0 review: one
15-minute cache for every key would have reset the "60 an hour" ceiling four times an hour);
every other key is in the quarter-hour one, and the test pins that a global key is still refused
at minute 16 and admitted at minute 61. ⚠️
**Keys are built from exactly the value the lookup uses**: `trim()` then `toLowerCase(Locale.ROOT)`
for an identifier or email (" a@x" and "a@x" must share a bucket); for login ALSO the resolved
user id when one resolves (`login:user:<id>`), so the username and the email of one account share a
budget; `client:` + the forwarded address (§1 #23); and a per-endpoint `global:` key.

`service/TurnstileGate`: `Outcome admit(String token, String identityKey, String clientKey,
Endpoint endpoint)` with `Outcome { OK_VERIFIED, OK_FALLBACK, CAPTCHA_FAILED, RATE_LIMITED }` (the
two `OK`s because login needs to know it is on the fallback path — below): `TurnstileService
.isConfigured()` false → `OK_VERIFIED` (local dev, today's behaviour; the limiter is not consulted);
token non-blank → `verify(token)` ? `OK_VERIFIED` : `CAPTCHA_FAILED` (a token that FAILS is never a
fallback); token blank → the fallback, `OK_FALLBACK` only when ALL of these admit, **checked in THIS order,
outermost first, stopping at the first refusal**: `fallback:<endpoint>:global` (an hourly ceiling per
endpoint: login 60, change-password 20, forgot 10), then `fallback:<endpoint>:client:<addr>` (20 per
15 min), then `fallback:<endpoint>:id:<key>` (5 per 15 min) — the identifier is the one key the
caller chooses, so it is touched only by a request the non-rotatable bounds have already admitted,
and a refused request creates no entry at all (otherwise rotating identifiers against an exhausted
client bound would go on filling the quarter-hour cache and evicting other accounts' counters;
Codex, R0 round 14). The same order holds for set-password and reset-password (global → client →
token) and the tests pin it: a refused global bound leaves the client and identifier/token caches
empty. ⚠️ **The per-ACCOUNT login budget is acquired AFTER the user resolves, inside
`AuthenticationService.login(identifier, password, fallback)`** (Codex, R0 round 10: the gate runs
before resolution and sees only the identifier, so a username and an email of one account would be
two five-attempt buckets): on the fallback path only, once the §2 #10 lookup has answered and BEFORE
the password check, `limiter.tryAcquire("fallback:login:user:" + userId, 5, QUARTER_HOUR)` — refused
→ a `TooManyAttemptsException` the controller maps to the same 429; when no user resolves the
identifier bucket has already counted the attempt and nothing further is acquired. Stated,
accepted: alternating two identifiers of ONE account yields five attempts where two identifiers of
no account yield ten, a weak oracle on "these two names are one account", bought for the real bound. Controllers map
`CAPTCHA_FAILED` → 400 with today's sentence, `RATE_LIMITED` → 429 `{"error": "Too many attempts.
Please wait a few minutes and try again."}`. Applied to `/login`, `/change-password` and
`/forgot-password` (R3a). ⚠️ **`/register` gets NO fallback** (§4 #1, §5 #4): the fallback cannot
tell a blocked browser from a bot that omits the token, registration's key would be an email the
caller chooses, and each pending row counts against `registration.max-users` — a trickle of
tokenless sign-ups could close registration for everyone and mail verification links to arbitrary
addresses. Registration keeps today's rule (no token → 400) and the client says so honestly (§2 #12
"Security check couldn't load"). `/set-password` loses Turnstile entirely and always takes THREE bounds in the same outermost-first
order, any one refused → 429 the same sentence: `set-password:global` (200 per hour), then
`set-password:client:<addr>` (20 per 15 min), then `set-password:token:<tokenKey>` (10 per 15 min) — a token-keyed bucket alone can be
rotated with a fresh random token on every request, which would throttle nothing and churn the
caches' entries out from under the real per-account limits (Codex, R0 round 13); `/reset-password`
takes the identical three (`reset:…`). A 122-bit token is not guessable; the bounds exist for the
cache and the database, not the token. `TurnstileService`
gains `isConfigured()`; `verify`'s own behaviour is unchanged. A legitimate reader on a blocked
browser who mistypes a password five times waits fifteen minutes; the copy says so. **Stated
residuals** (§5 #4): with no token at all an attacker gets at most 60 login guesses an hour across
every identifier and client, 5 against any one account; today they get none — and neither does the
account's owner from a blocked browser.

**Testing** (§1 #24): `AuthAttemptLimiterTest` (a sixth call within the window refused, the first
call after 15 minutes from the FIRST attempt admitted, keys independent, `clear()`),
`TurnstileGateTest` with mocks (unconfigured → OK and no limiter call; a failing token → `CAPTCHA_FAILED`
and no limiter call; blank → global, then client, then identifier, the first refusal wins and the
later keys are never touched). In
`AbstractControllerTest` BOTH `TurnstileGate` and `AuthAttemptLimiter` are `@MockitoBean`s; each
`AuthControllerTest` case stubs the gate's `Outcome` (default `OK`) and the limiter's answer, so the
controller tests pin the status mapping (200/400/429 per endpoint; set-password with NO token
succeeds and a refused limiter answers 429; register with the field absent stores
`marketingEmailOptIn = false`) and never depend on order.

Frontend (R2b): `ChangePasswordPage` takes `LoginPage`'s exact pattern — `turnstileLoadFailed`
state, `onLoadFail`, the widget unmounted, the line "Security check unavailable — we'll verify your
sign-in another way." in `text-plex-text-secondary`, the submit enabled, an empty token sent.
`RegisterPage`'s register form takes the same state but a DIFFERENT line and keeps the submit
disabled: "Security check couldn't load. Try another browser, or turn off your ad-blocker for this
page." Set-password's widget, state and token are deleted outright (task 11). A 429 renders the
server's sentence in the form's error slot.

### #5 Reverse lookup (R4a)

`PostcodesIoClient.reverseLookup(double lat, double lon)` → `GET
https://api.postcodes.io/postcodes?lon={lon}&lat={lat}&limit=1&radius=2000` (the API's default
radius is 100 m, which misses most rural homes; 2000 m is its maximum); the response is `{status,
result: [Result] | null}`; an empty or null result throws `PostcodeLookupException`, a transport
failure or 5xx throws `PostcodeLookupUnavailableException` (§1 #11), a hit maps through the same
`buildPlaceName` to the same `PostcodeLookupResult`. **`POST /api/user/settings/home/reverse`**
`{latitude, longitude}`, `isAuthenticated()`: 400 `{error}` for a missing, non-finite or
out-of-range value (lat −90..90, lon −180..180), 400 `{error: "No UK postcode found for that
location"}` from the not-found exception, 502 from the unavailable one, otherwise the result; never
ETag'd (added to the caching test's personal list). `settingsApi.js` gains
`reverseLookupPostcode(latitude, longitude)`. No rate limit (§1 #12). ⚠️ **The contract test is the
one that can see the URL**: `client/contract/PostcodesIoClientContractTest` (its javadoc names the
fail-open caveat — every exception is wrapped, so `server.verify()` is required) gains a case that
expects EXACTLY `https://api.postcodes.io/postcodes?lon=-1.575&lat=54.776&limit=1&radius=2000`
(a lat/lon swap or a wrong path is the silent-wrong mode) and ends with `server.verify()`; the
unit test covers a hit, `result: []`, `result: null` and a 500.

### #6 `PostcodeCapture`, `ProTag` and `useGeolocationPostcode` (R4b)

`components/auth/PostcodeCapture.jsx`, props `{heading, sub, primaryLabel, secondaryLabel,
onSaved(response), onSkip, autoFocus, testIdPrefix}` — matching frames 1–4 in `.field` geometry
(the frames draw the OLD input style; task 13 wins, §4 #14):

- The heading and sub block: `AuthHeading` (an `<h2>`, §2 #9) then the sub `text-xs
  text-plex-text-secondary leading-normal mt-1`.
- The field row: label "Home postcode" (`block text-xs text-plex-text-secondary mb-1`), then a
  `flex gap-2` row of the input (`field flex-1`, placeholder *Enter UK postcode*, the task-4
  attributes `autoComplete="postal-code" autoCapitalize="characters" autoCorrect="off"
  spellCheck={false} inputMode="text" enterKeyHint="search"`; Enter runs the lookup) and the
  **Look up** button — `btn-secondary` (the frames'), *Looking up…* while busy, `disabled` while the
  field is blank or a lookup is in flight.
- Under the field: the error line *Invalid postcode* (`text-xs text-plex-error mt-1`; the input takes
  `field-error`) and, on its own line, the **Use my location** text button (`text-xs text-plex-gold
  hover:underline`; *Locating…* while busy; hidden when `navigator.geolocation` is undefined; its own
  error replaces the error line).
- After a lookup the field row is replaced by the confirmation row: a `field`-styled box
  (`flex items-center justify-between`) holding `✓` in `text-plex-ok` + `{placeName}` in
  `text-sm text-plex-text`, with `{postcode}` beneath in `font-mono text-[11px] tracking-[0.06em]
  text-plex-text-muted mt-0.5`, and a **Change** text button (`text-xs text-plex-text-secondary`)
  that restores the field with its last value.
- The Pro line: `flex items-baseline gap-2 text-xs text-plex-text-secondary` — `ProTag` then *Also
  gives you drive times from home to every location.* — every role, no link.
- The actions: a full-width `btn-primary` (`primaryLabel`, `disabled` until a lookup result exists
  or while saving) above a centred text button (`secondaryLabel`, `text-xs text-plex-text-secondary
  hover:text-plex-text` — secondary, not muted: 12px muted fails 4.5:1 by the spec's own argument,
  §4 #14), `gap-2.5`.

Save calls `saveHome(result.postcode, result.latitude, result.longitude, null)` — the null radius
keeps the column NULL (`UserSettingsModal`'s own rule) — then `onSaved(response)`; **consumers never
save**, `onSaved` is a notification. A failed save keeps the confirmation row and shows *Couldn't save.
Try again.* (§5 #9). Autofocus only when `autoFocus && window.matchMedia('(pointer: fine)').matches`
(`test/setup.js` stubs `matchMedia` to `matches: false`; a test wanting `true` overrides
`globalThis.matchMedia` and restores it).

`components/auth/ProTag.jsx` (§1 #25): `<span className="font-mono text-[10px] uppercase
tracking-[0.12em] text-plex-text border border-plex-border-light rounded-[3px] px-[5px] py-px"
data-testid="pro-tag">Pro</span>` — the text is "Pro" and CSS upper-cases it, so tests assert
`/pro/i`; PropTypes-free (no props), Tailwind only.

`hooks/useGeolocationPostcode.js`: `{locate, locating, error}`; `locate()` calls
`navigator.geolocation.getCurrentPosition(ok, fail, {enableHighAccuracy: false, timeout: 10000,
maximumAge: 600000})`, then `reverseLookupPostcode(lat, lon)`; the error strings, verbatim, keyed on
the NUMERIC codes (jsdom has neither `navigator.geolocation` nor `GeolocationPositionError`, so
mocks pass `{code: N}`): `1` (permission denied) → "Location access is off. Enter your postcode
instead."; a 400 from the reverse endpoint → "We couldn't find a UK postcode for your location.";
`2`, `3`, a 502, any other failure of either step → "Couldn't get your location. Enter your
postcode instead." A result lands in the same confirmation row as a typed lookup.

Consumers: `RegisterPage` `HOME` and `PostcodePromptOverlay` (R4b) take the component;
`UserSettingsModal` (R4c) takes the hook and the link, the attributes and the class — not the
component and not the pointer rule (§1 #18, §4 #5).

### #7 Account deletion (R5a backend, R5b frontend)

**`DELETE /api/user/account`** `{password}`, `isAuthenticated()`, new `AccountController`
(`/api/user/account` — not under `/settings`, it is not a setting; `AbstractControllerTest` gains its
dependencies): 400 for a missing password; 403 `{error: "Password is incorrect"}` on a mismatch,
counted on `AuthAttemptLimiter` (key `delete:` + username, 5 per 15 min → 429); 409 `{error: "Admin
accounts are deleted by another admin"}` for `ROLE_ADMIN` (the existing self-delete refusal,
extended: it keeps the sole admin from locking the instance out). On success,
`UserService.deleteOwnAccount(username, verifiedHash, expectedVersion)`: in one transaction, FIRST
lock the user row (`userRepository.findByUsernameForUpdate`, the `SELECT … FOR UPDATE` `saveHome`
uses — parent before children, the ordering `UserDriveTimeWriter`'s javadoc records, so a drive-time
calculation that holds the row finishes before the purge begins and cannot re-insert between the
purge and the delete; Codex, R0 review), **re-check under that lock that the stored hash is still
the one the controller verified, that `credentials_version` still equals the request's
authenticated `cv`** (the filter's details, §2 #13; the version counter, never the timestamp) **and
that the locked row's role is still not ADMIN** (an admin who promoted the account during BCrypt
must win — a role change does not move the version, and the sole administrator must never be
deletable this way; Codex, R0 round 23; → the same 409 "Admin accounts are deleted by another
admin") — otherwise `StaleCredentialsException` → 401
"Your session has changed. Sign in again." and nothing is purged (a stolen session that finished
its BCrypt just before the owner's password change commits must not delete the account afterwards;
Codex, R0 round 13; a legacy token with no `cv` is accepted only on a never-changed row), then
`purgeUserRows(userId)` (`userDriveTimeRepository.deleteAllByUserId` — §1 #2
— then anything the integration test below shows has no cascade), then `userRepository.delete(user)`,
a flush, and the conditional release of the account's email claim (§5 #6);
the admin path takes the same lock by id (`findByIdForUpdate`, added) and the same release; the confirmation email is sent AFTER commit
for BOTH paths (a `TransactionSynchronization.afterCommit`, or the send moved out of the
transactional method into its caller) — the admin path `deleteUser` calls the same `purgeUserRows`
and the same after-commit send. 204 No Content. The access token dies with the row once R3c's filter
rule is in (R5a's PR states it). ⚠️ **The FK is proven only in CI**: `integration/
AccountDeletionIntegrationTest extends IntegrationTestBase` (Postgres + Flyway) deletes a user
holding drive times, Ask usage, a rating and an `ask_log` row, asserts the delete FAILS without the
purge and succeeds with it, and that the `ask_log` row survives with a null user — R5a is "pending CI"
like a migration phase. `UserServiceTest` (Mockito) pins the call order and the after-commit send.

**Client** (R5b): a **Danger zone** section at the foot of `UserSettingsModal` (`<h3>` in the
section-heading style) — `btn-secondary` "Delete account" → an INLINE confirmation region inside the
settings dialog (`role="group" aria-labelledby`; §1 #26, §4 #17): the sentence "This deletes your
account, your home postcode and drive times, your Ask history and your ratings. It cannot be
undone.", a `PasswordInput` (`autoComplete="current-password"`, focus moved to it when the region
opens), a destructive "Delete account" button (`disabled` until the password is non-empty and while
the request is in flight) and a "Cancel" text button that collapses the region and returns focus to
the opener → `deleteAccount(password)` (`accountApi.js`: `apiClient.delete('/api/user/account',
{data: {password}})`) → on 204 `logout()` (its `/logout` call finds no refresh token and answers
200 — harmless) → the login page. A 403 or 429 shows the server's sentence inside the region (`role=
"alert"`); the region stays open.

### #8 `apple-app-site-association` and the nginx check (R6)

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
repeats (the file's note says a location that sets ANY header inherits none) }` (order against
`location /` is irrelevant — an exact match wins), and `geolocation=(self)` in both
`Permissions-Policy` lines. R6 proves Vite copies the dot-directory into `dist/` (`npm run build &&
test -f dist/.well-known/apple-app-site-association`; there is no `publicDir` override and
`.dockerignore` excludes only `node_modules`, `dist` and `.env*`) and that the PWA precache ignores
it (its `globPatterns` name extensions and `assets/index-*.js`; an extensionless file matches none —
read `vite.config.js` and say so in the PR). ⚠️ **`nginx -t` in CI**: a new step in
`.github/workflows/ci.yml`'s frontend job (Actions runners have Docker; the ShellCheck job already
uses `docker run`): copy `nginx.conf` with `goldenhour-backend:8082` replaced by `127.0.0.1:8082`
(an unresolvable upstream fails `-t` on its own) and run `docker run --rm -v "$PWD/<copy>:/etc/nginx/
nginx.conf:ro" nginx:1.27-alpine nginx -t`; the step must fail on a deliberately broken copy once,
in the PR, to prove it bites. Two Vitest files read the committed files: one parses the AASA as JSON
and asserts the four paths and the two placeholders; one reads `nginx.conf` and asserts the
permissions directive in both blocks and the exact location block (the file-reading pattern
`safeAreas.test.jsx` uses on `index.html`).

### #9 Visual foundation (R1)

`index.css`: `--color-plex-error: #F07A62` and `--color-plex-ok: #8AAE72` declared **inside the
existing `@theme static` block** (the file's own pruning guard; a separate token from
`--color-verdict-go` with the same value, commented in the file's voice: verdict colours mean
forecast verdicts). Measured at plan time: `#F07A62` is 6.25:1 on `#221A15` and 6.77:1 on
`#181210`; `#8AAE72` is 6.83:1 on `#221A15`. `@layer components`: `.field` = `w-full bg-plex-bg
border border-plex-border rounded-lg px-3 py-2 text-base sm:text-sm text-plex-text
placeholder:text-plex-text-muted focus:outline-hidden focus:ring-1 focus:ring-plex-gold` and
`.field-error` swapping the border to `border-plex-error`; ⚠️ **`.field` REPLACES each input's
class string** — Tailwind 4's utilities layer outranks `@layer components`, so a `text-sm` or
`bg-plex-surface-light` left beside `field` wins and the iOS-zoom fix silently fails; only layout
utilities stay (`pr-10`, `flex-1`), and a test asserts no auth or Settings input carries `text-sm`,
`bg-plex-surface-light` or a bare `rounded`. `.auth-shell` = the page wrapper: `min-h-dvh bg-plex-bg
flex justify-center items-start sm:items-center` with ONE padding rule each side — `padding-top:
max(3rem, var(--safe-t))` below 640px and `var(--safe-t)` from 640px, `padding-bottom:
max(1.5rem, var(--safe-b))`, `padding-left/right: max(1rem, var(--safe-l/r))` — the `.app-safe-modal`
precedent, never a raw `env()`. Components under `components/auth/`: `AuthShell` (wrapper +
`BrandLockup variant="auth"` in `mb-8` + the `max-w-sm` column), `AuthHeading` (an **`<h2>`** —
`BrandLockup` already renders the page's `<h1>` — `font-serif font-semibold text-[22px] leading-tight
tracking-[-0.015em] text-plex-text`; **no kicker on any auth screen** — the spec says "optionally"
and one answer across the series beats five), `PasswordInput` (field + eye button, `autoComplete`
prop, the existing `aria-label`s), `PasswordChecklist` (✓ in `text-plex-ok` for met, ○ in
`text-plex-text-secondary` for unmet). `LoginPage`, `RegisterPage` (every step) and
`ChangePasswordPage` adopt `.field`, the tokens (every `red-400`/`red-700`/`green-400`/`green-500`
→ `plex-error`/`plex-ok`; **both `amber-400` lines — the session-expired line and the Turnstile
fallback line — become `text-plex-text-secondary`**, informational ink, never a verdict colour),
`text-plex-text-secondary` for 12px hints, left alignment on the session-expired, Check your email,
Verify, Waitlist and Success cards; the session-expired line moves inside the card above the heading.
`MarkerPopupContent.jsx`'s two `<a href="/upgrade">Upgrade to Pro</a>` anchors (lines 764 and 1078;
`/upgrade` is not a route and the App Store forbids a link to a web purchase) become plain text
"Pro accounts get …" (task 14's audit; a stated, second-time break of the frozen overlay, §4 #16);
the tooltips that merely SAY "Upgrade to Pro" are text and stay. **R1 changes no behaviour and no
test id** — the existing suites pass with class and copy assertions updated (`ChangePasswordPage`
has a test file too). `UserSettingsModal` is R4c's.

### #10 Sign-in by username or email (R3a backend, R3b frontend)

`AuthenticationService.login(identifier, password)`: `identifier.contains("@")` → the §1 #5 email
rule through `EmailIdentifier.resolve` (exact match first; then, for a pure-ASCII identifier, the
`LOWER()` query; active rows; exactly one → that user); **no unique match → fall back to
`findByUsername(identifier)`**, because admin-created usernames are not shape-checked and one could
carry an `@`; a BCrypt comparison runs against a fixed dummy hash when no user resolves, so the
email path's timing does not say whether the address exists. The request field stays `username` on
the wire (the iOS client and every test keep working); the log line says `identifier`, sanitised.
The error stays "Invalid username or password". The client: label "Username or email",
`autoComplete="username"` kept (password managers fill either), `data-testid="login-username"` kept.
`RegistrationService.findPendingUserByEmail` moves onto the same query with the pending predicate;
`UserService.createPendingUser` and admin `createUser` refuse a case-insensitive duplicate with the
existing "Email already registered" 409 — enforced by the `app_user_email_claim` unique key written
in the same transaction (§5 #6), never by a read-then-write check alone. ⚠️ **`createPendingUser`'s
replacement of an abandoned pending row locks it first** (Codex, R0 round 25): the row is read with
`findByIdForUpdate` and deleted only by a conditional `DELETE … WHERE id = :id AND enabled = false
AND password = ''` whose row count must be one (0 → the account was activated meanwhile → the 409
"Email already registered"), so a retry racing `consumeVerificationAndApply` can never erase an
account that has just been activated; `UserServiceTest` pins the zero-row branch.

### #11 Privacy inventory (R7, after deletion exists — §1 #20)

`docs/app-store-privacy.md`: a table per App Store privacy "data type" — Email address and
Username (account, linked), Coarse location (home postcode and its lat/lon; "Use my location" sends
device coordinates to postcodes.io once, nothing is stored but the resolved postcode; linked; app
functionality), Marketing consent (linked), Usage data (`last_active_at`; `ask_log`'s outcome rows, kept 90 days; job and API logs; linked;
analytics/app functionality), User content (outcome ratings, linked; **and Ask questions**: V167
stores the NORMALISED question text — `normalised_question` — for the `CLAUDE_OK` and `CLAUDE_CANT`
outcomes, linked to the account by `user_id` until deletion, which sets the link null and leaves the
text unlinked until the 90-day prune (`ask_log_cleanup`) removes the row — Codex, R0 round 11) — with the table each lives in, who sees it, and how deletion removes it
(§2 #7's purge). `landing/privacy.html`: line 36's postcode line rewritten (every account, light
times; drive times Pro), line 51's processor row ("UK postcodes, or device coordinates when you
choose Use my location"), a sentence naming in-app account deletion, the 90-day retention named.
The owner redeploys landing after the release (§7).

### #12 Copy table (verbatim; every string a test may assert)

| where | string |
|---|---|
| HOME heading / sub | **Where's home?** / *Your postcode sets the sunrise and sunset times shown at the top of the app.* |
| Field label / placeholder / button / busy | **Home postcode** / *Enter UK postcode* / **Look up** / *Looking up…* |
| Confirmation / change | `✓ {placeName}`, `{postcode}` beneath / **Change** |
| Error | *Invalid postcode* |
| Pro line | `Pro` tag (upper-cased by CSS) *Also gives you drive times from home to every location.* |
| HOME buttons | **Save and continue** / **Skip for now** |
| Prompt heading / sub | **Set your home postcode** / *You haven't saved one yet. It sets your light times, and you can change it any time in Settings.* |
| Prompt buttons | **Save** / **Not now** |
| Use my location | **Use my location** / *Locating…*; errors per §2 #6 |
| Save failed | *Couldn't save. Try again.* |
| Register hints | *We'll send a link to confirm it.* (under Email) / *3–30 letters, numbers, - or _* (under Username) / *Agree to the terms to continue* (under the disabled button, 12px, `text-plex-text-secondary`, centred, `-mt-2`) |
| Turnstile fallback (login, change-password, forgot) | *Security check unavailable — we'll verify your sign-in another way.* |
| Turnstile fallback (register) | *Security check couldn't load. Try another browser, or turn off your ad-blocker for this page.* |
| Rate limited | *Too many attempts. Please wait a few minutes and try again.* |
| Sign-in label / link | **Username or email** / **Forgot password?** (the label row is `flex justify-between items-baseline`; the link 12px `text-plex-gold`) |
| Forgot heading / sub / button / done / back | **Reset your password** / *Enter your email and we'll send you a link.* / **Send reset link** / *If that email has an account, we've sent a reset link.* / **Back to sign in** |
| Reset heading / sub / button | **Set a new password** / *Choose a new password for your account.* / **Set new password** |
| Reset link errors | *This reset link is invalid* / *This reset link has already been used* / *This reset link has expired* + button **Request a new link** |
| Reset email | subject *Reset your PhotoCast password*; body: *Someone asked to reset the password for {username}. If that was you, use the link below within the next hour. If it wasn't, you can ignore this email — your password hasn't changed.* |
| Settings copy (task 14) | *Your postcode sets your light times. Pro accounts also get drive times from home.* |
| Delete account | section **Danger zone** / button **Delete account** / *This deletes your account, your home postcode and drive times, your Ask history and your ratings. It cannot be undone.* / **Cancel** / *Password is incorrect* / *Admin accounts are deleted by another admin* |
| Reverse lookup | *No UK postcode found for that location* (400) / *Postcode lookup is unavailable right now* (502) |
| Success | **Welcome to PhotoCast!** / *Redirecting…* (unchanged) |

### #13 Token invalidation (R3c)

`app_user.credentials_changed_at TIMESTAMPTZ NULL` **and `app_user.credentials_version BIGINT NOT
NULL DEFAULT 0`** (one migration, next free `V`, no backfill: a null stamp and version 0 mean "never
changed since these columns existed", so every existing token stays valid), both `updatable =
false`,
written by `AppUserRepository.stampCredentialsChangedAt(id, now)` from `AuthenticationService
.changePassword`, `PasswordResetService` (the R3a `TODO(R3c)`), `UserService.resetPassword` (the
admin temp password) and `UserService.activateUser`. ⚠️ **Hash first, then one transactional
helper** (Codex, R0 round 3): `changePassword` today deliberately runs BCrypt in a
NON-transactional method (its javadoc: never hold a pooled connection through the ~100 ms hash);
each stamp site keeps that shape — encode outside, then call a `@Transactional`
`CredentialChangeWriter.apply(userId, encodedPassword, change, now)` (one new class) where `change`
is a small record carrying the site's WHOLE account-state change — `passwordChangeRequired`
(cleared by change-password and both resets' redemption, SET by the admin temp-password reset) and
`enable` (true for `activateUser`, which also writes the activation it does today) — so the
password, the stamp, the flag and enabled state, and the refresh-token revocation commit together
and a failure after the password write can neither leave the old sessions valid nor leave an
activated user disabled (Codex, R0 round 4). ⚠️ **The writer also takes the caller's EXPECTED
credential version and refuses under the lock when the row's has moved** (Codex, R0 round 12):
`change-password` admits the bearer before BCrypt, so two requests authenticated under the same old
`cv` can both reach the writer, and a stolen session finishing its hash AFTER the legitimate change
commits would otherwise overwrite the new password, revoke the legitimate replacement tokens and get
fresh ones. The filter puts the token's `cv` on the `Authentication`'s details; `changePassword`
passes it as `expectedVersion`, and `apply` answers a `StaleCredentialsException` (→ 401 "Your
session has changed. Sign in again.") when `credentials_version` differs from it. A legacy
token with no `cv` passes `null`, accepted only while the row's stamp is null (an account whose
credentials have never changed), otherwise the same 401 — the ≤ 24 h window again. The reset,
activation and admin paths pass no expectation (their authority is the token or the admin role, and
the reset/verification token's compare-and-set is their guard). ⚠️ **Every credential change also
invalidates the account's outstanding reset links in the same transaction** (Codex, R0 round 12):
`apply` deletes the user's `password_reset_token` rows with `used_at IS NULL`, so a link requested or
captured before a password change or an admin reset cannot overwrite the newer credential within its
hour; redemption consumes the presented token by compare-and-set and the same delete removes its
siblings. **An EMAIL change voids them too** (Codex, R0 round 15): the admin `PUT
/api/users/{id}/email` (`UserService.setEmail`) becomes a scoped, transactional update that locks the
user row, **claims the new lower-cased address and conditionally releases the old one** (the insert's
unique key refuses a case variant of another account's address with the 409 "Email already
registered", race-free; the old key is released only if no account still uses it; §5 #6; Codex, R0
rounds 19, 22 and 23), **deletes the account's `email_verification_token` rows too** (a verification
link is keyed by user alone and never re-checks the address, so one sent to the old or compromised
mailbox could otherwise activate a PENDING account after the admin moved it — Codex, R0 round 25; a
pending account then needs the admin's existing resend, which the response's message says), deletes the account's unused
`password_reset_token` rows and writes the address in one transaction, so a link mailed to the old or compromised mailbox cannot overwrite the password within
its hour; and `PasswordResetService.requestReset` inserts its token only under the same user-row lock
(the writer's lock order, parent first), so a request racing the email change either commits before
it and is deleted by it, or after it and mails the NEW address — never a token that survives into the
old mailbox. ⚠️ **The locked row is re-read, never the entity resolved earlier** (Codex, R0 round
17): `requestReset` resolves only the user ID outside any transaction, then a separate proxied
`PasswordResetIssuer.issue(userId)` (`@Transactional`, its own fresh persistence context because the
caller holds none) takes `findByIdForUpdate`, reads the address and the pending/enabled state off
THAT row, inserts the token and returns the address to mail — a JPA context that already held the
entity would hand back the stale one without refreshing it. `UserServiceTest` pins the delete;
`PasswordResetServiceTest` pins the lock and the re-read: an email change committed between the
resolve and the issue mails the NEW address and leaves no token for the old one.
⚠️ **Registration's activation entry point is
restructured for the same reason** (Codex, R0 round 5): `RegistrationService.setPasswordAndActivate`
is `@Transactional` today and calls `UserService.activateUser` inside it, so hashing in
`activateUser` would still run inside the caller's open transaction and the writer would merely join
it. It becomes a non-transactional method that reads and checks the token (hash, verified, not
expired), encodes the password, and then calls ONE `@Transactional` method on the SEPARATE writer bean
(`CredentialChangeWriter.consumeVerificationAndApply(tokenId, userId, encodedPassword, change,
now)` — never a method on `RegistrationService` itself, which would be the same self-invocation
trap the issuer avoids; Codex, R0 round 6) that locks the user row, **re-checks under that lock that the account is still pending**
(disabled AND empty password — today's `activateUser` test, kept; otherwise "already used": a
reader who verified two links and activated with one must not overwrite the live password with the
other — Codex, R0 round 7), deletes EVERY verification token of that user (not only the one
presented; `deleteByIdIfPresent` on the presented one returning 0 → "already used", so two
concurrent redemptions cannot both activate) and applies the credential change in the same
transaction — token consumption and the credential change stay atomic, the hash stays outside. **The writer locks the user row first**
(`findByIdForUpdate`, the parent-before-children order). ⚠️ **Token issuance is serialised against
it** (Codex, R0 round 4): a login that verified the OLD hash just before the writer commits would
otherwise issue an access token carrying the NEW `cv` (read after the stamp) and insert a refresh
row after the revoke. Issuance therefore moves out of `AuthenticationService` into its own proxied bean,
`service/TokenIssuer` (`@Transactional issue(userId, verifiedPasswordHash)`), because
`AuthenticationService.login` reaches `issueTokensFor` by SELF-INVOCATION, which bypasses
`@Transactional` — the method's own Javadoc says so — and a pessimistic lock in an unproxied call
would either throw for want of a transaction or fail to cover the refresh-row insert (Codex, R0
round 5). `TokenIssuer.issue` takes the SAME row lock (`findByIdForUpdate`), re-reads the stored
hash and refuses with the generic failure if it is not the one the caller verified (`login` passes
the hash it matched; set-password, reset and activation pass the hash they just wrote; `refresh`
passes the stored one it read under the lock) — so either the issue commits wholly before the
writer, whose revoke then catches the new refresh row and whose stamp makes the issued `cv`
stale, or wholly after it, and sees the new hash. `AuthenticationService.issueTokensFor` becomes a thin
delegate kept only for its callers' signatures (`AuthController.setPassword`,
`PasswordResetService`, `refresh`), each of which now passes the hash; `TokenIssuer` is the one
place refresh rows are inserted, so the lock covers every issuance. ⚠️ **One lock order
everywhere: the `app_user` row FIRST, then `refresh_token` / `user_drive_time` / the token tables**
(Codex, R0 round 6; `UserDriveTimeWriter`'s own rule). `AuthenticationService.refresh` therefore
takes `findByIdForUpdate` on the user BEFORE its `revokeIfActive` compare-and-set (today it locks
the refresh row first and would deadlock against the writer, which holds the user row and then
revokes), and the issuer's own `findByIdForUpdate` inside that same transaction is a re-lock of a
row already held. ⚠️ **The theft response must COMMIT before the refusal is thrown** (Codex, R0
round 20 — a pre-existing bug the plan would otherwise carry): today `refresh` calls
`revokeFamily` (`revokeAllActiveByUserId`) on a replayed-or-raced token and then throws
`InvalidCredentialsException` from the SAME `@Transactional` method, so Spring rolls the family
revocation back with the runtime exception and the replacement token stays usable. R3c moves it to
a proxied `RefreshFamilyRevoker.revokeAll(userId, reason)` marked `REQUIRES_NEW`, called BEFORE the
throw, with a `@SpringBootTest` on H2 that replays a revoked token and asserts the family is revoked
after the 401; `TokenIssuerTest`'s two-thread case runs the writer and a refresh concurrently
and asserts no deadlock and one loser. `JwtAuthenticationFilter`: after
`validateToken`, load the user ONCE (the query it already makes for `lastActiveAt`); set the
`SecurityContext` only when the row exists, `enabled` is true, and **the token's credential version
matches the row** — and **build the authorities from the LOADED ROW's role, never from the token's
role claim** (Codex, R0 round 20: an admin demotion through `UserService.setRole` changes only the
row, so a claim-built authority would keep an old ADMIN token authorised for admin endpoints until it
expired; the claim stays in the token for logging and the iOS client, the row decides; the filter
test pins a demoted account's token answering 403 on an admin route and 200 on a reader route): `JwtService.generateAccessToken` adds a `cv` claim = the account's
`credentials_version` — a monotonic counter the writer increments by one in the same statement that
stamps `credentials_changed_at` (`SET credentials_version = credentials_version + 1, …`; Codex, R0
round 16: an epoch-millisecond stamp can collide for two serialized changes in one millisecond,
which would leave the first change's access token valid after the second) — read under the issuer's
row lock, and the filter rejects a token whose `cv` is not exactly the loaded row's current value (Codex, R0
rounds 2 and 9: an `iat` comparison is whole seconds against a microsecond stamp — truncating the
stamp rejected the replacement tokens, and accepting equality let a token minted in the same second
BEFORE the change live out its 24 hours; an equality on the stamp's own value has neither problem).
A token with NO `cv` claim (issued before R3c deploys, ≤ 24 h old) falls back to the second-truncated
`iat` rule, `credentialsChangedAt == null || !iat.isBefore(stamp.truncatedTo(SECONDS))`, for that
window only — stated, accepted. The filter test pins: matching `cv` → accepted; a stale `cv` → 401; a
legacy token without `cv` before and after a stamp; otherwise leave the context empty and let the entry point answer 401, and
log one INFO line through `LogSanitizer`. The `lastActiveAt` stamp keeps its hourly rule on the
same loaded entity **but becomes a column-scoped update** (`AppUserRepository.touchLastActiveAt(id,
now)`), as does the one in `AuthenticationService.login` — both are whole-entity `save()`s today, and
the login one runs on a user loaded BEFORE BCrypt, so it could write the detached entity's OLD
password hash back over a credential change that committed during the hash, after which the
issuer's re-check would see the hash login verified and issue valid tokens (Codex, R0 round 7). The
same `updatable = false`/scoped-update lesson CLAUDE.md records for the settings columns; `password`,
`enabled` and `passwordChangeRequired` become `updatable = false` and are written only through
scoped updates: the writer's, and in `UserService` a new `AppUserRepository.updateEnabled(id,
enabled)` for the admin toggle (`UserService.setEnabled` → `PUT /api/users/{id}/enabled` mutates the
entity and `save()`s today, so without this the toggle would answer 200 and change nothing — Codex,
R0 round 11; `UserControllerTest`/`UserServiceTest` pin that the toggle still disables and re-enables
through the scoped update, and a stale-entity `save()` moves none of the three). `createUser` and
`createPendingUser` are inserts and unaffected. **`role` joins them** (Codex, R0 round 21):
`updatable = false`, written only by a new `AppUserRepository.updateRole(id, role)` behind
`UserService.setRole`, because `AuthController.updateMarketingEmails` still loads a detached entity
and `save()`s it — a request that loaded an ADMIN before a demotion committed would merge the stale
role back, and a filter that trusts the row would then keep the old token admin. That save becomes a
scoped `updateMarketingEmailOptIn(id, optIn)` as well (the last whole-entity `save()` of an `app_user`
row outside creation); `UserServiceTest` pins the demotion through the scoped update, and a
`@DataJpaTest` pins that a stale entity's `save()` moves neither `role` nor the three credential
columns while a scoped update does. ⚠️ **Refresh tokens carry no issue instant** (`refresh_token` has no
`created_at`), so the stamp alone would leave a pre-change refresh token able to mint a fresh access
token through `/refresh` (Codex, R0 review). Every stamp site therefore ALSO calls
`refreshTokenRepository.revokeAllActiveByUserId(userId)` in the same transaction — `changePassword`,
the admin `resetPassword`, `activateUser` (a no-op for a fresh account) and the self-service reset
(which already does) — and **`/change-password` then issues and returns fresh tokens for the caller**
(`issueTokensFor` after the revoke, the response `buildAuthResponse` plus the existing `message`), so
the device that changed the password stays signed in while every other device's next refresh is
refused. The client side is one conditional line in `AuthContext.changePassword` — `if
(data?.accessToken) signInWithTokens(data)` — built in R3b, safe to ship before R3c because it does
nothing for today's `{message}` response. Tests: `JwtAuthenticationFilterTest` — a token for a deleted user, a disabled
user, a token issued before the stamp, one issued after it, one with a null stamp; a `@DataJpaTest`
for the scoped update; `integration/CredentialsChangedAtMigrationTest` (CI-only). No same-second residual remains for tokens carrying `cv`; the legacy-token window above is the
only one, and it closes 24 hours after R3c deploys. ⚠️ **The token
is bound to the account's id, not only its name** (Codex, R0 round 8): a username freed by a
deletion and recreated by an admin (`UserService.createUser`, enabled at once) or re-registered would
otherwise resolve an unexpired old token to the NEW row, whose stamp is null. `JwtService
.generateAccessToken` adds a `uid` claim (the account id); the filter requires `uid` to equal the
loaded row's id and rejects any mismatch; a token with NO `uid` (issued before R3c deploys, ≤ 24 h
old) is accepted by name alone for that window — stated, accepted, and `credentials_changed_at` is
ALSO stamped at creation (`createUser`, `createPendingUser`, and activation already does) as belt
and braces, so even a legacy token meets a non-null stamp on a recreated row. The filter test adds:
a `uid` for another id → 401; a legacy token without `uid` → accepted. After R3c, "a password reset signs every other
device out" and "a deleted account's token is dead" are true at the next request.

---

## §3 Phases

One Sonnet session each, one branch each (`claude/login-reg-<id>-<slug>` — the series convention
of the last four plans, a deliberate deviation from CLAUDE.md's `feature/` prefix), one PR each.
**Waves** (never more than two phases open at once; a phase starts only when every phase it depends
on has MERGED to `origin/main`):

| wave | phases | why they may share a wave |
|---|---|---|
| 1 | R1 (frontend) ∥ R2a (backend) | disjoint trees |
| 2 | R2b (frontend; needs R1, R2a) ∥ R3a (backend; needs R2a) | disjoint trees |
| 3 | R3b (frontend; needs R3a, R2b) ∥ R3c (backend; needs R3a) | disjoint trees |
| 4 | R4a (backend; needs R3c) ∥ R6 (infra/CI; needs nothing) | disjoint files |
| 5 | R4b (frontend; needs R3b, R4a) ∥ R5a (backend; needs R3c, R2a) | disjoint trees |
| 6 | R4c (frontend Settings; needs R4b, R1) | alone — it owns `UserSettingsModal` |
| 7 | R5b (frontend Settings; needs R4c, R5a) | alone |
| 8 | R7 (docs; needs everything) | alone |

Each phase re-verifies every file:line it touches against `origin/main` before editing, reads
`docs/engineering/test-improvement-standards.md` (backend) or
`docs/engineering/frontend-test-standards.md` (frontend), and writes its changelog entries as
`changelog.d/<its own date YYYYMMDD>-login-reg-<id>-<slug>.md`, **one file per `###` block** —
a phase with an `### Added`, a `### Changed` and a `### Removed` writes three files with distinct
slugs (`-added`, `-changed`, `-removed`). Every new component carries a `data-testid` and PropTypes;
every new public Java type, method and constructor carries Javadoc; no inline `style`. **No phase
edits this plan or CLAUDE.md** (R7 does both). Screenshots: a phase whose screens need no sign-in
(R1, R2b, R3b: login, register, forgot, reset; and the register `SET_PASSWORD`/`HOME` steps and the
prompt overlay through ROUTE STUBS) renders them in headless Chromium over the Vite dev server with
`page.route` stubs for `/api/auth/verify-email`, `/api/auth/set-password`, `/api/auth/login` and
`/api/user/settings` (memory `browser-pane-headless-fallback`), at 390×844, 390×500 (the keyboard
proxy) and 1280×800, keeps the PNGs in its scratchpad (never committed, `gh` cannot attach images)
and describes what each shows, with the measured figures, in the PR body. ⚠️ **No phase signs in to
a running backend as anyone** — a session cannot sign in on the owner's behalf; the signed-in flows
(a real prompt at sign-in, a real deletion) are the owner's browser check in §7 and are reported as
"tested, not seen".

### R1 — auth visual foundation (frontend; not Settings)

Tasks 5, 6, 7 (auth wrapper), 13 (auth screens), 14 (the Upgrade audit). Files: `frontend/src/
index.css` (the two tokens in `@theme static`, `.field`, `.field-error`, `.auth-shell`); new
`components/auth/AuthShell.jsx`, `AuthHeading.jsx`, `PasswordInput.jsx`, `PasswordChecklist.jsx`
(+ a test each); `LoginPage.jsx`, `RegisterPage.jsx`, `ChangePasswordPage.jsx`,
`MarkerPopupContent.jsx` (the two anchors); tests `LoginPage.test.jsx`, `RegisterPage.test.jsx`,
`ChangePasswordPage.test.jsx`, `MarkerPopupContent`'s test if one asserts the anchors,
`safeAreas.test.jsx` (extend: `.auth-shell` reads the vars through `max()`), a new
`authTokens.test.js` (reads `src/index.css`, asserts both tokens sit inside the `@theme static` block
— the declaration pin; the built-CSS grep is §7's manual proof — and asserts the contrast figures
with a contrast function), a new `fieldClass.test.jsx` (no auth input carries `text-sm`,
`bg-plex-surface-light` or a bare `rounded`).

1. Tokens and classes per §2 #9.
2. The four shared components; `PasswordChecklist` takes `checks: [{label, ok}]`; `PasswordInput`
   takes `id, name, value, onChange, autoComplete, disabled, testId` and owns the eye button.
3. `LoginPage`: `AuthShell`, `AuthHeading` "Sign in", `.field` on both inputs, `PasswordInput`, the
   session-expired line moved inside the card above the heading, left-aligned, in
   `text-plex-text-secondary` (`data-testid` kept); the Turnstile fallback line likewise.
4. `RegisterPage`: the same on every step; the five centred cards left-aligned; the checklist from
   the shared component; hints to `text-plex-text-secondary`; tokens. **No field order, copy or
   state change here** — R2b does those.
5. `ChangePasswordPage`: the same.
6. `MarkerPopupContent`: the two anchors to text (§2 #9).
7. Tests green; screenshots of Sign in and Create account at the three sizes (the lockup sits under
   the 48px top padding at 390; the column is centred vertically at 1280; no horizontal scroll at 320).

### R2a — `TurnstileGate`, `AuthAttemptLimiter`, set-password without Turnstile (backend)

Tasks 9 (backend), 10 (server default), 11 (set-password). Files: new `service/AuthAttemptLimiter
.java`, `service/TurnstileGate.java`, `service/ClientAddress.java` (the §1 #23 header rule, one
static method) (+ `AuthAttemptLimiterTest`, `TurnstileGateTest`, `ClientAddressTest`),
`service/TurnstileService.java` (`isConfigured()`, + `TurnstileServiceTest`),
`controller/AuthController.java` (login and change-password through the gate; set-password's
Turnstile removed and the token-keyed limit added; register's absent-field default; register itself
NOT on the gate), `AuthControllerTest` (every existing Turnstile case re-pinned through the mocked
gate; the new 429s), `AbstractControllerTest` (the two new `@MockitoBean`s), `application-example.yml`
(nothing: the limits are constants with Javadoc, not properties — a bad value cannot fail startup).
Backward-compatible with the running frontend: an old client's extra `turnstileToken` on set-password
is ignored.

### R2b — registration form and the Turnstile fallback (frontend)

Tasks 9 (frontend), 10, 11. Files: `RegisterPage.jsx` (confirm-email field/state/`emailsMatch`/error
deleted; email before username with the two hints; "Agree to the terms to continue" under the
disabled button only while terms are unticked; `marketingOptIn` default `false`; the register form's
`turnstileLoadFailed` with the REGISTER sentence and the submit still disabled; set-password's
widget, token and reset key deleted; frame 6), `ChangePasswordPage.jsx` (the fallback, the LOGIN
sentence), `authApi.js` (`setPasswordForNewUser(token, password)` — two arguments),
`RegisterPage.test.jsx` (field order, no confirm email, marketing unticked by default, one Turnstile,
the register fallback keeps submit disabled and shows its sentence, set-password needs no token, the
disabled reason appears and disappears), `ChangePasswordPage.test.jsx` (the fallback enables submit
and sends an empty token). Screenshots: Create account (empty; terms unticked with the reason line),
Set password (no Turnstile) at the three sizes.

### R3a — sign-in backend: identifier, forgot and reset, `/verify` link

Tasks 12 (backend), 17. Files: the migration (§2 #1 and the claim table of §5 #6 — one `V`, or two consecutive),
`entity/PasswordResetTokenEntity.java`, `repository/PasswordResetTokenRepository.java`,
`entity/AppUserEmailClaimEntity.java`, `repository/AppUserEmailClaimRepository.java` (+ its
`@DataJpaTest`), `AppUserRepository` (`findActiveByEmailLower`), `util/EmailIdentifier.java` (ASCII
check + normalisation, one home),
`service/PasswordResetService.java` (+ test), `service/AuthenticationService.java` (`login` by
identifier with the dummy-hash comparison), `service/RegistrationService.java` (the pending lookup
on the new query; resend on the limiter) and `RegistrationServiceTest` (a REAL four-request
sequence through a real limiter: the fourth sends nothing), `service/UserService.java` (the email claim
inserted with every create, the 409 on violation), `notification/UserEmailService.java` (`sendPasswordResetLinkEmail`;
`sendVerificationEmail`'s link → `/verify?token=`), `templates/password-reset-link-email.html`,
`controller/AuthController.java` (`/forgot-password` through the mocked gate, `/reset-password`
through the mocked limiter), tests: `AuthenticationServiceTest` (email: one exact match; one case-insensitive match; two
case-insensitive matches → username fallback; none → username fallback; a disabled match ignored; a
non-ASCII identifier matched exactly and never folded; the dummy-hash path runs), `PasswordResetServiceTest` (unknown, disabled and
pending emails send nothing; the fourth request in the window sends nothing and throws nothing; a
used, expired and unknown token each refuse with its sentence; a disabled user's token refuses;
success marks used by CAS, revokes every active refresh token and encodes before the transaction;
the mail goes to the STORED address when the request's casing differs), `AuthControllerTest` (both
endpoints' statuses; forgot ALWAYS 200 for an unknown email; the body shapes), `UserEmailServiceTest`
(both links' paths), a new `PasswordResetFlowTest` — its own `@SpringBootTest` on H2 with REAL
repositories and the mail service mocked: forgot → the captured link's token → reset → the response
signs in → the earlier refresh token is refused by `/refresh` — `integration/
PasswordResetTokenMigrationTest` (CI-only). Every new log line through `LogSanitizer`.

### R3b — sign-in frontend: forgot and reset pages, the label, the link, the routes

Files: new `components/auth/ForgotPasswordPage.jsx`, `ResetPasswordPage.jsx` (+ tests; frame 8 for
the label row), `LoginPage.jsx` (label, link, `onForgotPassword` prop), `App.jsx` (`AuthGate`:
`showForgot` state; `/reset` by pathname with `?token=` BEFORE the token test; the legacy `?token=`
on any other path still verify mode; the consumed URL replaced with `/`), `AuthContext.jsx`
(`signInWithTokens(data)`, `completeRegistration` delegating to it and setting `registrationPending`,
`finishRegistration()`, the flag cleared by `logout` and the session-expired handler — §2 #2;
`changePassword` storing fresh tokens when the response carries them — §2 #13),
`authApi.js`, tests (`LoginPage.test.jsx`: identifier label, the link calls the prop, the fallback
still enables submit; `App.test.jsx`: `/reset?token=x` mounts the reset page with no token stored
AND with one stored, `/verify?token=x` and `/?token=x` both mount verify mode, a consumed token
leaves `location.pathname === '/'`; `AuthContext.test.jsx`: `registrationPending` set by
`completeRegistration`, cleared by `finishRegistration`, by `logout` and by the session-expired event;
`signInWithTokens` leaves it false). Screenshots: Sign in (with the link), Forgot (empty and done),
Reset (checklist) at the three sizes.

### R3c — token invalidation (backend)

§2 #13. Files: the migration, `AppUserEntity`, `AppUserRepository` (`stampCredentialsChangedAt`),
`config/JwtAuthenticationFilter.java` (the row/`uid`/`cv` rule; the token's `cv` on the
`Authentication` details), `controller/AuthController.java` (`/change-password` passes the caller's
`cv`; the 401 for a stale one), `service/AuthenticationService.java` (`changePassword`
stamps, revokes every active refresh token and returns fresh tokens; `issueTokensFor` delegating to
the issuer with the hash), new `service/TokenIssuer.java` and `service/CredentialChangeWriter.java`,
`service/RegistrationService.java` (`setPasswordAndActivate` split, `consumeAndActivate`),
`EmailVerificationTokenRepository` (`deleteByIdIfPresent`), `controller/AuthController.java`
(`/change-password` answers `buildAuthResponse` + `message`; set-password passes the hash), `service/PasswordResetService.java`
(the `TODO(R3c)`), `service/UserService.java` (`resetPassword` and `activateUser` stamp and revoke), `JwtService` (the `uid` claim, `extractUserId`), `UserService` (`createUser`/`createPendingUser`
stamp at creation; `setEnabled` through `updateEnabled`; `setEmail` locks the row and voids unused
reset tokens), `service/PasswordResetService.java` and a new `service/PasswordResetIssuer.java`
(`requestReset` resolves the id; the issuer locks, re-reads and inserts in its own transaction),
`UserControllerTest` and `UserServiceTest` (the admin toggle still works; the email change deletes the
tokens; the demotion lands through `updateRole`), `controller/AuthController.java`
(`updateMarketingEmails` through the scoped update), `AuthenticationService.refresh` (the user lock first), tests: `JwtAuthenticationFilterTest` (the five cases), `AppUserRepositoryTest`
(`@DataJpaTest`, the scoped update), `CredentialChangeWriterTest` (`@DataJpaTest`: each site's
change record lands atomically; a forced failure after the password write rolls back the stamp,
the flag and the revocation; a stale `expectedVersion` is refused and writes nothing; a null
expectation is accepted only on a never-changed row; unused reset tokens are deleted and a used one
kept), `TokenIssuerTest` (`@DataJpaTest` over the real repositories: refuses
when the stored hash moved under it; the row lock is taken — a `@Transactional(NOT_SUPPORTED)` test
with two threads, one holding the row, proves the second issue waits), `AuthenticationServiceTest`
(the hash each caller passes; `login` calls the issuer, never its own method),
`RegistrationServiceTest` (`setPasswordAndActivate` encodes before `consumeAndActivate`; a second
redemption of one token answers "already used"), the three services' tests (the stamp call), `integration/
CredentialsChangedAtMigrationTest` (CI-only). No frontend change: a 401 already fires the session-
expired path.

### R4a — postcode backend: the prompt column and flag, the reverse lookup

Tasks 2 (backend), 3 (backend). Files: the migration (§2 #3), `AppUserEntity`, `AppUserRepository`,
`UserSettingsResponse` (`postcodePromptDismissedAt`, `postcodePromptDue`), `UserSettingsService`
(`markPostcodePromptDismissed`; the flag on every response build), `UserSettingsController` (`PUT
/postcode-prompt-dismissed`, `POST /home/reverse`), `client/PostcodesIoClient.java` (`reverseLookup`,
the two exception types), `client/PostcodeLookupUnavailableException.java`, `GlobalExceptionHandler`
(400 and 502, no echo), tests (`UserSettingsControllerTest`: the lookup's measured status before and
after; the PUT answers the response with the instant set and `postcodePromptDue` false; the reverse's
four statuses; LITE allowed on both; `UserSettingsServiceTest`: `postcodePromptDue` true with neither,
false with a postcode, false with a dismissal; `PostcodesIoClientTest` and
`PostcodesIoClientContractTest` per §2 #5; `HttpCachingConfigTest`: the two paths;
`JsonDateFormatContractTest`: the new `Instant`; `integration/PostcodePromptDismissedMigrationTest`
CI-only). `UserSettingsRaceSequenceTest` unchanged — the write is a scoped update like its siblings.

### R4b — postcode frontend: `PostcodeCapture`, the `HOME` step, the prompt overlay

Tasks 1, 2 (frontend), 3 (frontend), 4 and 8 (the new screens). Files: new `components/auth/
PostcodeCapture.jsx`, `ProTag.jsx`, `PostcodePromptOverlay.jsx`, `hooks/useGeolocationPostcode.js`
(+ a test each), `RegisterPage.jsx` (`HOME`, frames 1–3), `App.jsx` (the gate's two `||
registrationPending`; `AppInner` mounting the overlay), `hooks/useReaderSettings.js`
(`postcodePromptDue`), `settingsApi.js` (`reverseLookupPostcode`, `markPostcodePromptDismissed`),
tests (`RegisterPage.test.jsx`: lookup → confirmation → Change restores the field with its value;
Skip → `markPostcodePromptDismissed` once, no save, then `SUCCESS`; Save → `saveHome(postcode, lat, lon, null)` exactly, once; the primary
disabled until a result; `PostcodeCapture.test.jsx`: every copy string, the attributes, the
`field-error` class on error, the pointer rule both ways, the failed-save line;
`PostcodePromptOverlay.test.jsx` + `App.test.jsx`: shown when the mount read answers
`postcodePromptDue: true`, NOT shown for `{}` or `false` (the strict pin), the app container carries
`inert` while shown and loses it after, Not now calls `markPostcodePromptDismissed` once and the
overlay goes even when it rejects, Save hands the response to the settings record and the masthead's
place name changes, the overlay is absent under a rewind, the mutation pin of §2 #2;
`useGeolocationPostcode.test.js`: the three strings against codes 1/2/3, a 400 and a 502, the link
absent with no `navigator.geolocation`; `useReaderSettings.test.jsx`: the flag's strictness and its
update through `startSettingsRead`). Screenshots through route stubs: HOME empty / found / invalid,
the overlay over the Map tab and over the Plan tab, at the three sizes.

### R4c — Settings: the field, the tokens, the copy, Use my location, the attributes

Tasks 3 (Settings), 4 (Settings), 13 (Settings), 14. Files: `UserSettingsModal.jsx` (`.field` on the
postcode input, the status tokens for `settings-lookup-error` and the drive-time lines, the task-14
sentence, the task-4 attributes, the "Use my location" link under the field through
`useGeolocationPostcode` landing in the modal's own `lookupResult`; **the focus landings untouched**,
§1 #18), `UserSettingsModal.test.jsx` (the old sentence → the new; the link; the attributes; the
landings still green), `fieldClass.test.jsx` (extended to the Settings input). Screenshot: not
possible without a session; "tested, not seen".

### R5a — account deletion (backend)

Task 15 (backend). Files: `controller/AccountController.java` (new), `service/UserService.java`
(`deleteOwnAccount`, `purgeUserRows`, `deleteUser` through both, the row lock first, the after-commit
send), `AppUserRepository` (`findByIdForUpdate`),
`AbstractControllerTest` (the controller's dependencies), tests (`AccountControllerTest`: 204, 400,
403 + the limiter's 429, 409 for an admin, 401 for a stale credential version, LITE and PRO both
allowed; `UserServiceTest`: purge before
delete, the email after commit on both paths, an admin refused; `integration/
AccountDeletionIntegrationTest` CI-only, §2 #7). "Pending CI" in the PR body.

### R5b — account deletion (Settings danger zone)

Task 15 (frontend). Files: `UserSettingsModal.jsx` (the section and the inline region, §2 #7),
`api/accountApi.js` (+ test), `UserSettingsModal.test.jsx` (the region opens and takes focus, the
button needs a password, a 403 and a 429 stay inside the region, 204 → `logout`, Cancel returns
focus to the opener, no second `aria-modal` on the page). Screenshot through a route stub for
`/api/user/settings` only if the modal can be opened without a session (it cannot — the shell needs
one) → "tested, not seen".

### R6 — native prep: AASA, nginx, `nginx -t` in CI

Task 16 and §1 #8's header. Files: `nginx.conf`, `frontend/public/.well-known/
apple-app-site-association`, `.github/workflows/ci.yml` (the `nginx -t` step), two Vitest files
(§2 #8), `vite.config.js` only if the PWA glob would precache the file (it should not — say so). The
PR body records the `dist/.well-known` proof and the one deliberately-broken CI run. No code paths
change; the privacy documents are R7's (§1 #20).

### R7 — docs sweep and the privacy documents

`docs/app-store-privacy.md` and `landing/privacy.html` (§2 #11). CLAUDE.md: the **Auth** line under
*What's Built* (today "Self-registration with email verification + Turnstile CAPTCHA | First-login
password change gate | Session expiry warnings | Marketing email opt-in" → forgot/reset, identifier
login, the bounded Turnstile fallback and the attempt limiter, registration's honest no-fallback,
no confirm-email, no set-password Turnstile, marketing opt-IN with an absent field meaning no, the
`HOME` step and the prompt overlay, token invalidation on password change/reset/deletion, self-service
deletion); *API Endpoints* (`/forgot-password`, `/reset-password`, `/home/reverse`,
`/postcode-prompt-dismissed`, `DELETE /api/user/account`, the AASA path; `DELETE /api/users/{id}`
now purges drive times); *Roles* (deletion open to every role but admin; the prompt); *User
settings* (the ninth and tenth `updatable = false` columns); the migrations table (four rows); the
`IntegrationTestBase` count (fifteen → nineteen, re-counted); *Role gating — UI pattern* (the Pro
line is text with no link, App Store rule); *Deployment* (the `nginx -t` CI step; landing is
redeployed by hand). This plan's §0 filled from the merged PRs and §6 answered by what shipped. The
planning session writes the memory file (`login-registration-series.md` + the `MEMORY.md` line).

---

## §4 Disagreements with the spec, on purpose

1. **Task 9 "front and back" — the back is built, bounded, and NOT for registration.** §1 #4: there
   was no backend behaviour to mirror. Login, change-password and forgot take one gate with
   identifier, client and global limits (§2 #4); registration keeps today's refusal and an honest
   sentence, because a tokenless registration path lets a bot fill `registration.max-users` with
   pending rows and mail verification links to any address (§5 #4, §6 Q1).
2. **The prompt rides the settings read, not the login response, and is an overlay.** Task 2's
   "after a successful login … before the app shell": the flag is computed by the server and read at
   every mount, because a refresh keeps an existing user signed in for ever (§1 #16); the prompt
   paints over the already-mounted app within one request of mount and makes it inert (§2 #3). A
   reader who opens the app on a second device before dismissing sees it there too — the spec's own
   "carries across devices".
3. **No reload after registration.** The `SUCCESS` step stays with its 2 s wait, but the wait ends
   in `finishRegistration()` rather than `window.location.reload()` (§1 #7: the reload never ran).
   `login()` already mounts the app with no reload; registration takes the same path.
4. **Task 11's "username can be pre-filled from the email" (the HTML note) is not built.** A
   derived username invites `john.smith@` → `john.smith`, which the username rule rejects (dots).
5. **Settings keeps its own postcode field and its focus landings.** Task 3 wants "Use my location"
   in Settings, task 13 one input style, and task 8 no phone autofocus: the first two are delivered
   as the hook, the link, the class and the attributes, not by replacing the field with
   `PostcodeCapture`; the third is NOT applied to the modal, whose `focusField === 'postcode'`
   landing exists so the nudge and the ⌂ land the reader on the field they asked for (§1 #18) — the
   pointer rule applies to the two new screens only (§5 #10).
6. **Task 7 is scoped to the auth wrapper.** The app's own safe-area system exists and is pinned
   (§1 #10); re-touching the top bar and bottom controls would be a second owner of the same rule.
7. **The forgot-password request has a Turnstile.** The spec does not mention one; without it the
   endpoint is an unauthenticated email-sender. It takes the same gate and bounded fallback as
   sign-in.
8. **A rate-limited forgot request answers 200, not 429**, and the limit is the in-memory limiter,
   not a DB count. Task 12 asks for "the same rate limit as resend (3 per 5 min)"; resend's DB count
   can never trip (§1 #12) and a 429 here would say "this email has an account". Both go on the
   limiter (3 per 15 min per user), resend keeping its 429.
9. **Reset signs the reader in** and, after R3c, signs every other device out at its next request.
   Task 12 says the `/reset` page "reuses the Set password form" — and that form signs in on success.
10. **An admin cannot delete their own account in-app** (409). Task 15 is unconditional; the
    existing admin rule and the sole-admin lockout decide it (§5 #7).
11. **The AASA components name `/verify` and `/reset` exactly plus a wildcard child**, not `/verify*`
    — Apple's `components` matcher treats `*` per segment, and the query form is listed separately
    so a bare `/verify` with no token is still claimed.
12. **The `PRO` tag is a new `ProTag`, not `ProPill`** (§1 #25): the spec's geometry cannot be reached
    from the shared pill without changing it for Ask and the rating lock, and the pill's "Pro" is
    kept as the tag's text with CSS upper-casing.
13. **`inputMode="text"` is kept as written even though it is the default** — it documents the
    "never numeric" rule at the one place a future edit would make that mistake.
14. **Task 13 overrides frames 1–4 on four points** the bundle's own Fidelity note does not list:
    the frames draw 14px sans headings (task 13: Newsreader 22px), `#f87171`/`#b91c1c` error reds
    (the `plex-error` token), a `--go` ✓ (`plex-ok`, never the verdict colour) and muted 12px hints
    and text buttons (`text-plex-text-secondary`, the spec's own 4.5:1 argument).
15. **`/reset?token=` opens the reset page even on a signed-in browser.** The spec does not say; the
    link is most often opened on the phone the account is signed in on, and dropping it silently
    (today's `/verify` behaviour, which stays) would make the emailed link do nothing.
16. **The frozen Plan-tab overlay is edited a third time**, to turn two `/upgrade` anchors into
    text (task 14's audit found them; `/upgrade` is not a route). Copy only; the overlay's layout is
    untouched.
21. **`/register` refuses a non-ASCII email address going forward** (today it accepts any
    non-whitespace Unicode). A confusable address is the one input the reset flow cannot make safe
    by comparison alone (§1 #5); existing accounts keep an exact-match path. An owner call to revisit
    (§6 Q7).
17. **The delete confirmation is inline, not a `ConfirmDialog`.** Task 15 says "behind a confirm
    dialog"; a second `aria-modal` over the settings dialog breaks CLAUDE.md's one-modal invariant
    and `ConfirmDialog` cannot disable its confirm button (§1 #26). The region asks for the password
    and takes focus, which is what the dialog was for.
18. **"Skip for now" records the dismissal** (task 1 says only that Skip goes to `SUCCESS`): a skip
    that did not would be followed by the prompt overlay on the next render (§2 #2).
19. **No postcode prompt after a password reset** on the resetting device until the next mount:
    `signInWithTokens` carries no flag, and the mount read raises the overlay a moment later anyway —
    so in practice the prompt still shows; recorded because the first cut said otherwise.
20. **`radius=2000`** on the reverse lookup (the spec names no radius; the API's 100 m default
    misses rural homes, §2 #5).

---

## §5 Decisions taken at plan time (defaults every phase builds on)

1. **Eight waves, two phases at most, backend before its frontend** (§3): the restyle first so every
   later phase writes against the final classes; a frontend phase mocks nothing that is not on main.
2. **The dismissal is a timestamp, not a boolean** — the spec's own `postcode_prompt_dismissed_at`;
   it says when, which a support question will want.
3. **The prompt state is server-side + one in-memory `promptDone` per mount**, never `localStorage`
   (the HTML note's alternative): a second account on the same browser must never inherit a
   dismissal, the same rule that keeps every Ask and settings value out of module scope.
4. **The fallback is bounded three ways and absent on registration.** Per identifier (5/15 min, keyed
   on the normalised value the lookup uses, plus the resolved user id), per forwarded client address
   (20/15 min; spoofable by a caller who reaches the host directly, so never the only bound), and a
   global hourly ceiling per endpoint (login 60, change-password 20, forgot 10). In memory (a
   restart forgets counts — a rate limit on a single-instance app, the acceptance
   `UserSettingsService.pendingDriveTimeAttempts` records). Residuals, stated: a distributed attacker
   with no token gets at most 60 login guesses an hour in total and 5 against any one account; today
   they get none, and neither does the account's owner from a blocked browser. Registration gets no
   fallback because its key would be attacker-chosen and each admission is a pending row against the
   sign-up cap (§4 #1). The owner can revisit (§6 Q1).
5. **Reset tokens are a sibling table, not rows in `email_verification_token`.** A verification
   token is deleted on use and proves an address; a reset token is kept with `used_at` and proves a
   request — different lifecycles, and the admin's resend path deletes "unverified" tokens by user.
6. **Normalised email uniqueness is enforced by the database through a CLAIM table, not by a
   functional index and not by application checks alone.** A `UNIQUE INDEX ON LOWER(email)` would
   fail at production startup if two legacy rows already differ only by case (data CI cannot see),
   and an application-level check alone races: two concurrent registrations of `Foo@x` and `foo@x`,
   or two admin email edits, can both see no duplicate and both commit under READ COMMITTED (Codex, R0
   round 22). R3a's migration therefore adds `app_user_email_claim (email_lower VARCHAR(255) PRIMARY
   KEY, claimed_at TIMESTAMPTZ NOT NULL)` — **a reservation of the normalised address, owned by no
   one account and with NO cascade**, backfilled with `INSERT … SELECT DISTINCT LOWER(email), now()
   FROM app_user WHERE email IS NOT NULL` (a legacy case-duplicate pair shares ONE reservation, which
   the lookup rule already tolerates: two active matches → no match). Every write of an address —
   `createUser`, `createPendingUser`, `setEmail` — claims the new key in the SAME transaction as the
   account write through one `EmailClaims.claim(emailLower, forUserId)` that NEVER recovers from a
   constraint error in place (Postgres aborts the transaction on one, and Spring marks it
   rollback-only — Codex, R0 round 25): FIRST `SELECT … FROM app_user_email_claim WHERE email_lower =
   :k FOR UPDATE`; a row found → under that lock, `EXISTS (SELECT 1 FROM app_user WHERE LOWER(email)
   = :k AND id <> :forUserId)` (`forUserId` null for a new account — the account being edited never
   counts as its own occupant, so an admin changing only the CASING of an address that account alone
   holds succeeds; Codex, R0 round 26): none → the reservation is an orphan, or the account's own, and
   is reused silently (so `createPendingUser`'s supported retry — which
   deletes an abandoned pending row for the same email earlier in the same transaction and recreates
   it — succeeds against the pending row's own surviving reservation; Codex, R0 round 24), some → the
   409 "Email already registered"; no row found → `INSERT`, and the one way that insert can still
   violate the key is a genuinely concurrent first claim of a brand-new address, where the loser's
   409 is the right answer and the aborted transaction is its own rollback — **translated OUTSIDE the
   transaction**: the three callers' transactional methods let the `DataIntegrityViolationException`
   propagate, and the controllers (`AuthController.register`, `UserController` create and email
   change) catch it around the service call and answer the same 409 "Email already registered"
   (today they catch `IllegalArgumentException` alone and `GlobalExceptionHandler` would turn the
   violation into a 500; Codex, R0 round 26; `AuthControllerTest`/`UserControllerTest` pin the
   mapping with a mocked service throwing it) (`RegistrationServiceTest`
   pins the retry of one normalised email, the 409 against a live account, and that no exception is
   caught on the orphan path), and every path that
   stops using an address — `setEmail`, `deleteOwnAccount`, the admin `deleteUser` — ends with a
   **conditional release in that same transaction, after the account write has flushed**:
   `DELETE FROM app_user_email_claim WHERE email_lower = :k AND NOT EXISTS (SELECT 1 FROM app_user
   WHERE LOWER(email) = :k)`, so the key stays reserved while ANY account still uses it (Codex, R0
   round 23: a claim tied to one arbitrary member of a legacy pair would be freed by that member's
   deletion or email change while the other still used the address, and a case variant could then be
   claimed over it). H2 supports the subquery; `LOWER()` on the ASCII-only addresses this rule admits
   is collation-independent. Portable to H2 (a unique constraint, no advisory lock), race-free, and the ASCII
   `Locale.ROOT` lower-casing is done in Java so the claim key never depends on a database collation.
   `AppUserEmailClaimRepositoryTest` (`@DataJpaTest`) pins the violation and the conditional release
   (one of two accounts sharing a key deleted → the key stays; the last one → it goes);
   `integration/EmailClaimMigrationTest` (CI-only) pins the backfill over a seeded case-duplicate
   pair to one row.
7. **An admin's self-deletion is refused (409).** `UserService.deleteUser` already refuses an admin
   deleting themself; a sole admin deleting their account in-app would orphan the instance. Another
   admin deletes an admin, as today.
8. **`DELETE` with a JSON body**, as the spec names it; axios sends `data` on a DELETE, CORS allows
   DELETE, Spring binds `@RequestBody` on it and nginx passes it.
9. **A failed `saveHome` in `PostcodeCapture` keeps the confirmation row and says "Couldn't save.
   Try again."** — the modal keeps its own result on a failed save too (it shows no sentence; this
   sentence is new).
10. **Autofocus rule**: `(pointer: fine)` only, on the postcode field of the TWO new screens (`HOME`
    and the prompt); the login and register forms keep today's behaviour (no autofocus); the Settings
    landing is untouched (§4 #5).
11. **The Pro line shows for every role**, including PRO and ADMIN (the spec's rule; the HTML note's
    role-specific wording is not built).
12. **`/forgot-password` validates the email's shape only** and decides its response before the send;
    email existence is already disclosed by register's 409, so what the endpoint protects is the link,
    not the address.
13. **Token invalidation is a `cv` credential-version claim (the account's monotonic
    `credentials_version` counter, `0` on a never-changed row) that the filter requires to EQUAL
    the row's current value**, with `credentials_changed_at` kept beside it as the human-readable
    stamp and the legacy-token rule's input, plus
    a `uid` claim bound to the account id — two claims, one column, no denylist, in a filter that
    already loads the row; an `iat` comparison survives only for tokens issued before R3c deploys and
    dies with them within 24 hours (§2 #13; Codex, R0 rounds 2, 9 and 10 — the `iat` rule could
    neither tolerate nor exclude the same second).
14. **Controller tests mock the gate and the limiter; the limiter, the gate and the one end-to-end
    reset sequence get their own classes** (§1 #24).
15. **Screenshots are never committed**; the PR body carries the measured figures and the owner's
    browser check is the visual proof of the signed-in flows (§3 preamble).

## §5a What the plan-time review changed

Four read-only prosecutor lenses (backend; frontend/CSS/tests; spec fidelity and copy; conventions,
process and sizing), 60 charges, 56 upheld after refutation. The P1s and what they did to the plan:
the JWT filter authenticates from claims alone, so a deleted account's token kept working and a
reset signed nobody else out → §2 #13 and R3c; the resend rate limit the reset flow copied can never
trip → the limiter (§1 #12, §2 #1); an identifier-keyed Turnstile fallback let a bot skip the CAPTCHA
on every endpoint and fill the registration cap → the three bounds and no fallback on registration
(§2 #4); the prompt flag on the login response never reached an existing user → the settings read and
the overlay (§2 #3); a `UPPER()`-based email match plus mailing the typed address could send a token
to a confusable address → the ASCII/`LOWER`/stored-address rule (§1 #5); the `HOME` step's gate
change had no test that could fail → the mutation pin (§2 #2); the FK proof could not fail on H2 →
the CI-only integration test (§1 #2); `nginx.conf` had no syntax check before production → the CI
step (§2 #8). The P2s: `ProPill` is not a tag (`ProTag`); `ConfirmDialog` would stack two modals
(the inline region); `.field` had to replace, not join, the utilities; the `amber-400` replacement
was unnamed (`text-plex-text-secondary`); the Settings landing must not take the pointer rule; the
screenshots and the "sign in as admin" instruction were not achievable by a session (route stubs and
"tested, not seen"); R2 and R4b were two sessions' work each (R2a/R2b, R4b/R4c, R5a/R5b); phases
editing §0 in parallel would conflict (no phase edits the plan); the shared controller context cannot
host a real limiter (mocked in the base class); the privacy documents described deletion before it
existed (moved to R7); `AuthHeading` must be an `<h2>`; the reverse lookup needed `radius=2000`, a
split of "not found" from "unavailable" and a URL-pinning contract test; the frames and task 13
disagree on four points (§4 #14). Codex's review of the plan PR (#1197) added fifty-one P1s over twenty-six rounds, folded in: the
claim's occupancy check excludes the account being edited; the concurrent-claim violation is
translated to 409 in the controllers, outside the rolled-back transaction (§5 #6); an
email change voids a pending account's verification links; the pending replacement is a locked,
conditional delete; the claim checks for an orphan under `FOR UPDATE` before inserting rather than
catching a constraint error (§5 #6, §2 #10, §2 #13); an
orphaned email reservation is reusable, so a pending registration's retry does not collide with its
own claim (§5 #6);
the email claim is a reservation released only when no account uses the address; deletion re-checks
the locked row's role (§5 #6, §2 #7);
normalised email uniqueness moves into the database as a claim table written with every address
(§5 #6);
`role` becomes non-updatable with a scoped `updateRole`, and the marketing opt-in save stops being a
whole-entity merge (§2 #13); the
refresh theft response commits in its own transaction before the refusal; the filter builds
authorities from the row's role, not the claim (§2 #13); the
reset URL token is its own gate state outside the verification cleanup effect; the redeemer
re-checks enabled/pending under the lock; deletion compares `credentials_version`; the admin email
change refuses a case-insensitive duplicate (§2 #1, #7, #13); the
reset response is built from the row as redeemed, not the pre-hash entity (§2 #1); reset
issuance re-reads the locked row in a fresh persistence context rather than trusting the entity
resolved before the lock (§2 #13); the
`cv` claim is a monotonic `credentials_version` counter, never a millisecond stamp that two changes
could share (§2 #13); an
admin email change voids the account's unused reset links under the row lock, and reset issuance
takes the same lock (§2 #13); the
bounds are checked outermost first so a refused request never creates an attacker-chosen entry
(§2 #4); deletion
re-checks the verified hash and the credential version under the row lock; set-password and
reset-password carry client and global bounds a rotated token cannot escape (§2 #1, #4, #7); the
writer refuses a change-password whose authenticated credential version has moved; every credential
change invalidates the account's unused reset links (§2 #13); the
admin enable toggle gets a scoped `updateEnabled` before `enabled` goes non-updatable; the privacy
inventory discloses the normalised Ask question text V167 retains (§2 #11, #13); the modal
observer excludes the prompt's own node (it would have looped); the per-account login budget is
acquired after the user resolves; §5 #13 restated for the `cv` claim (§2 #3, #4, §5 #13); the
filter compares a `cv` credential-version claim for equality instead of `iat`, which could neither
tolerate nor exclude the same second; the prompt defers behind ANY open `aria-modal` dialog via a
DOM observer, not only Settings (§2 #3, #13); the access
token carries a `uid` claim and creation stamps `credentials_changed_at`, so a recreated username
cannot inherit a deleted account's token; the reset token is consumed inside the writer's
transaction (§2 #1, #13);
activation re-checks the pending state under the lock and deletes every verification token; the
`lastActiveAt` stamps become scoped updates and the three credential columns `updatable = false`;
the overlay never mounts over an open Settings dialog; a failed dismissal after Skip is covered by an
in-memory suppression (§2 #2, #3, #13); the
consume-and-activate helper lives on the separate writer bean, not on `RegistrationService`;
`refresh` locks the user row before its refresh-token compare-and-set, one lock order app-wide
(§2 #13); issuance moves
to a proxied `TokenIssuer` bean because `login` self-invokes and would bypass `@Transactional`;
registration's activation hashes before a transactional consume-and-apply helper (§2 #13); token issuance
is serialised against the credential writer by the user-row lock and a hash re-check (§2 #13); the
writer carries each site's enabled/flag state so it commits with the password (§2 #13); a strict
ASCII rule would have stranded accounts that already hold a non-ASCII address (exact match first,
§1 #5); every credential change hashes outside and writes inside one transactional helper (§2 #13); the `iat`
comparison must truncate the stamp to seconds or reject the tokens issued right after it (§2 #13);
"Skip for now" must record the dismissal or the overlay follows at once (§2 #2, §4 #18); refresh tokens issued before a
credential change must die too (every stamp site revokes them; `/change-password` re-issues for the
caller — §2 #13); the global fallback ceilings need their own hourly window (§2 #4); the deletion
locks the user row before purging drive times (§2 #7). Refuted and kept: the `AuthGate`
instance-preservation mechanism,
the two-origins claim, the nginx header inheritance and bind-mount claims, Vite's copying of
`public/.well-known`, the cascade inventory, the DELETE body, the migration numbering, the lifting
of "never push".

---

## §6 Owner questions — none blocks a phase

- **Q1** The Turnstile fallback: is the bounded no-token path on login, change-password and forgot
  (§5 #4's limits and residuals) acceptable, and is "no fallback on registration" the right side of
  the trade? Default: as written.
- **Q2** TEAMID and bundle id for the AASA file. Default: the literal `TEAMID.BUNDLEID` placeholder;
  the file is harmless until Apple reads a matching app.
- **Q3** Should the admin self-delete refusal stand (409), or should an admin who is not the last
  admin be allowed? Default: refuse.
- **Q4** `password-reset-link-email.html`'s sender and subject line — defaults in §2 #12.
- **Q5** Should the landing privacy page name a retention period for `ask_log` (90 days) and job
  logs? Default: name the 90 days, since CLAUDE.md states it.
- **Q7** Registration now refuses non-ASCII email addresses (§4 #21). Keep that rule, or accept
  them and live with exact-match-only recovery for those accounts? Default: refuse.
- **Q6** Should Codex going silent (quota exhausted — it happened twice in the last series) hold a
  phase, or may the planning session merge on green CI plus its own review after 12 hours? Default:
  hold and tell the owner (§8).

---

## §7 Verification — what each phase checks

- **R1**: the two tokens resolve in the built CSS (`npm run build`, grep `dist/assets/*.css` for
  `--color-plex-error`); contrast ≥ 4.5 asserted; the `fieldClass` test; screenshots at 390×844,
  390×500 and 1280×800 of Sign in and Create account; no horizontal scroll at 320.
- **R2a**: `TurnstileGateTest` proves the fallback order and the register exclusion; the controller
  tests prove set-password needs no token.
- **R2b**: Create account shows "Agree to the terms to continue" until the box is ticked; a stubbed
  `onLoadFail` keeps the register submit disabled and enables change-password's.
- **R3a**: `PasswordResetFlowTest` runs forgot → link → reset → signed in → the old refresh token
  refused; `findActiveByEmailLower` returns both rows for `A@x` / `a@x` in a `@DataJpaTest` and the
  service treats that as no match; the real four-request resend sequence.
- **R3b**: `/reset?token=` renders the reset page with no backend call until submit, signed in or
  not; `/verify?token=` and `/?token=` both verify; a consumed link leaves `/`.
- **R3c**: the filter test's five cases; a `@SpringBootTest` request with a token for a user deleted
  after issue answers 401.
- **R4a**: the settings body of a user with no postcode and no dismissal carries
  `postcodePromptDue: true`; after the PUT it carries `false`; the reverse endpoint on
  `54.776, -1.575` answers Durham in the contract test with the exact URL (never the live API).
- **R4b**: the overlay's `inert` pin; the strict-flag pin; the `HOME` mutation pin; screenshots of
  the overlay over the Map and Plan tabs at the three sizes through route stubs.
- **R4c / R5b**: unit tests only; "tested, not seen".
- **R5a**: the integration test fails without the purge; the controller's six statuses.
- **R6**: the CI `nginx -t` step fails once on a deliberately broken copy and passes on the real
  file; `test -f dist/.well-known/apple-app-site-association`.
- **Owner's browser check (after release)**: register a new account on a phone end to end (email
  first, no confirm, consent unticked, one Turnstile, `HOME` with Use my location, Welcome, the
  masthead light rule set); open the app on a second device with an account that has no postcode and
  see the prompt once; Forgot password from the phone's mail client (the `/reset` link opens the
  app signed in or not; the other device is signed out at its next tap); delete a throwaway account;
  `curl -sI https://app.photocast.online/.well-known/apple-app-site-association` shows
  `Content-Type: application/json` and `/` shows `Permissions-Policy: geolocation=(self)…`;
  redeploy `landing/` by hand (`landing/README.md`) so the privacy page matches.

---

## §8 Process

Each phase is a **fresh Sonnet session in its own worktree**, launched by the planning session with
this plan's §3 phase text, the relevant §1/§2/§4/§5 entries and the spec pasted in (the plan is on
main once R0 merges; an agent's worktree is cut from `origin/main`, so a dependent phase must not
start until its predecessor's PR has MERGED). The session: builds → runs the gate → runs its review
lenses in the foreground → fixes → checks (§7) → writes its changelog.d entry → commits (conventional
commit, the attribution trailer) → pushes its branch → opens the PR (`gh pr create`, body ending
with the generated-with line) → watches CI and Codex → fixes anything either raises, pushing
follow-up commits and asking `@codex review` after each → merges with
`gh pr merge --squash --delete-branch --match-head-commit "$(gh pr view <N> --json headRefOid -q
.headRefOid)"` only when CI is green AND Codex has answered for the HEAD sha. ⚠️ **Codex clean, for
the CURRENT round**: the "Codex Review Summary" comment's row reading `✅ Completed` on the head's
short sha **with no Codex review or inline comment whose `original_commit_id` is that sha** — the
row names the head sha while still 🔄 Running, so the status word matters; a 👍 reaction on the PR
body counts only when its `created_at` is after the head commit's date (a round-1 👍 persists after a
round-2 `@codex review`); 👀 is in progress, never clean; a force-push does not re-trigger it. Codex
silent for 12 hours or a quota message → the phase holds and the owner is told (§6 Q6). The
planning session's watcher applies exactly that rule and verifies each merge on `origin/main` before
launching the next phase. The owner is told only when a phase is genuinely blocked (a Codex finding
the session cannot resolve with cited evidence, a CI failure outside the diff) — never for a
decision §5 already takes. ⚠️ Review agents never write to the working tree (CLAUDE.md "UI Work —
Review Cadence"); a reviewer that must mutate gets `isolation: 'worktree'`.
