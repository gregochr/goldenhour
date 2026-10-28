# Password hardening plan (security review 2026-10-28, item 4)

One PR. Three changes:
- change-password must re-verify the current password;
- the `passwordChangeRequired` flag is enforced by the server, not only the client;
- the published default admin credential (`admin` / `golden2026`) can never sign in outside the local
  H2 profile.

Revised after two adversarial plan reviews (backend, and frontend/ops). Their findings are folded in
below and marked *(review)*.

## Why

The 2026-10-28 full-repo security review found three problems:
- **Seeded admin.** `V10__create_app_user.sql` seeds `admin` / `golden2026` as ADMIN, and Flyway runs it
  in production. `V12` defaults `password_change_required` to `FALSE` and nothing forces a rotation. The
  password is published in `README.md`, `DEVOPS.md` and `CLAUDE.md`.
- **Change-password.** `POST /api/auth/change-password` needs only a valid access token and
  `newPassword`. A briefly stolen access token can therefore become a permanent takeover: the change
  bumps the credential version and revokes every refresh token, so the real owner is signed out
  everywhere.
- **The flag.** `passwordChangeRequired` is honoured only by `App.jsx`. The server ignores it.

## 1. Change-password re-verifies the current password

### Backend
- **Read the current password.** `AuthController.changePassword` reads `currentPassword`. If it is
  missing or blank, return **400** `{"error":"Current password is required"}`.
- **Check order** in `AuthenticationService.changePassword`:
  1. load the user;
  2. **always** `attemptLimiter.tryAcquire("change-password:user:" + user.getId(), 5, <15 min>)`. Use the
     existing `AuthAttemptLimiter` API and the window constant login uses. *(review)* `TurnstileGate`
     bounds only the tokenless fallback and the limiter has no failure hook, so the check must run on
     every attempt.
  3. `passwordEncoder.matches(currentPassword, user.getPassword())`;
  4. encode and apply as today.
- **Results:**
  - Limiter refusal → `TooManyAttemptsException` → **429**. The controller must catch it; it does not
    today.
  - Mismatch → a new `CurrentPasswordMismatchException`, or reuse an existing 400-mapped type →
    **400** `{"error":"Current password is incorrect"}`. *(review)* It must be a 400, **not 401**. On a 401
    the axios interceptor refreshes and retries the request. That checks the password twice and re-sends
    an already-spent Turnstile token, so the user sees the wrong error.
  - `newPassword` equal to `currentPassword` → 400 `{"error":"New password must differ from the
    current one"}`.
- **No exception for flagged accounts.** A flagged user has just signed in with the temporary password.

### Frontend
Files: `ChangePasswordPage.jsx`, `AuthContext.changePassword`, `authApi.changePassword`.
- **Signature:** `(currentPassword, newPassword, turnstileToken)` throughout.
- **Do not** set `SKIP_AUTH_REFRESH`; an expired access token on this page still needs the refresh.
- **New field:** "Current password" above "New password":
  - `id="cp-current"` with `<label htmlFor>`, `autoComplete="current-password"`, testid
    `cp-current-password`;
  - `eyeLabel="current password"`, and add `eyeLabel="new password"` / `"password confirmation"` to the
    other two fields (pattern: `ResetPasswordPage.jsx:112,127`);
  - **not** part of the `checks` checklist. Gate submit on it separately.
- **Copy:** "Enter the temporary password you signed in with, then choose a new one."
- **Tests:**
  - update `ChangePasswordPage.test.jsx:141,249` and `AuthContext.test.jsx:56,475`;
  - assert the two password values are not swapped;
  - a wrong-current 400 shows its error text and makes exactly one POST.

## 2. The server enforces `passwordChangeRequired`

### Backend
- **Separate filter.** A new small `PasswordChangeRequiredFilter`, registered in `SecurityConfig` with
  `addFilterAfter(…, JwtAuthenticationFilter.class)`. *(review)* This keeps the authentication filter
  single-purpose.
- **Carrying the flag.** `JwtAuthenticationFilter` adds `passwordChangeRequired` (from the loaded DB
  row) to `JwtAuthenticationDetails`. The new filter reads it from the authenticated principal's details.
  There is no second DB read, and no token claim is used.
- **When it applies:** the request is authenticated, the flag is true, the path starts with `/api/`, and
  the path is **not** under `/api/auth/` or `/api/waitlist`. *(review)*
  - Those two are `permitAll`, and the client attaches Bearer to every call.
  - Blocking them would break reset-password, set-password and logout for a flagged user who is signed
    in.
- **Response:** **403** `application/json`
  `{"error":"Password change required","code":"PASSWORD_CHANGE_REQUIRED"}`, then stop the chain. This
  follows the `{error, code}` shape of `AskAccessDeniedHandler`.
- **Paths outside `/api/`** (actuator, static) are unaffected. The filter must skip async and error
  dispatches, matching the JWT filter.
- **Tests** for the filter:
  - flagged user + `/api/briefing` → 403 with that exact body;
  - flagged user + `/api/auth/change-password`, `/api/auth/logout`, `/api/auth/reset-password`,
    `/api/waitlist` → passes;
  - unflagged → passes;
  - unauthenticated → passes, so prod-health's expected 401 on `/api/briefing` is unchanged.
- **JWT filter test:** the details carry the flag from the row.

### Frontend *(review: a stale client flag would otherwise leave every screen in error states)*
- In the axios response interceptor (`frontend/src/api/axiosClient.js`), on
  `status === 403 && data?.code === 'PASSWORD_CHANGE_REQUIRED'`, dispatch
  `window.dispatchEvent(new Event('goldenhour:password-change-required'))`, then reject as usual.
- `AuthContext` listens for it, writes `MUST_CHANGE_KEY = 'true'` and calls
  `setMustChangePassword(true)`.
- **Tests:** one interceptor test and one AuthContext test.
- The interceptor runs before `auroraApi.js` / `nlcApi.js` swallow their 403s, so the event still fires.

## 3. The default admin credential cannot sign in outside local

### Startup guard instead of a hash-matching migration *(review)*
A SQL migration matching the V10 hash would miss two cases:
- a row seeded by `seedDefaultAdmin`, which uses a random salt. This matters because prod runs
  `baseline-on-migrate` and `out-of-order`;
- a renamed row.

It also could not bump the credential version or revoke sessions the way `CredentialChangeWriter` does.

**`DefaultCredentialGuard`** (`@Component @Profile("!local")`, on `ApplicationReadyEvent`):
- For each ADMIN user: if `passwordEncoder.matches("golden2026", hash)`, neutralise the row through
  `CredentialChangeWriter.apply(userId, "!disabled-default-admin", Change.adminTemporaryPassword(),
  <the expectation the admin temp-password path uses>)`. That one transaction:
  - sets a hash no password matches (it is not bcrypt-shaped, so `matches` returns false);
  - bumps `credentials_version`, so every live access token dies;
  - revokes every refresh token;
  - sets the flag.
- Then log WARN: "Default admin password was still in use for '<user>' — sign-in disabled; set a new
  password (DEVOPS.md)".
- **Never throws:** a failure is caught and logged at ERROR, and the app starts.
- **Profile** *(review: fail-closed)*: it runs under `prod`, `dev`, `test` and `integration-test`, and is
  skipped only under `local`. No integration test signs in as `admin` / `golden2026`; the builder
  re-checks this with a grep.
- **If the default constant is moved** to a shared place, `UserService` and the guard both use it.
- **Unit tests:**
  - match → the writer is called with those arguments and the WARN is logged;
  - no match → nothing happens;
  - a non-ADMIN row → not checked;
  - the writer throws → logged, not propagated.

### Timing *(review)*
For a stored hash that is not bcrypt-shaped (does not start with `$2`), `AuthenticationService` login
also runs `passwordEncoder.matches(password, <the existing dummy hash>)`. Response time then stays
uniform for this row. Add a unit test showing a non-bcrypt stored hash yields the generic
`InvalidCredentialsException`.

### `UserService.seedDefaultAdmin`
Extract it into a `@Profile("local")` seeder bean, which is positive and fail-closed *(review)*. It
keeps local H2 working with `admin` / `golden2026`. Test that the bean seeds an empty table and skips a
non-empty one.

### Recovery for the owner *(review)*
Recovery options, in order:
1. another ADMIN sets a temporary password (`PUT /api/users/{id}/reset-password`);
2. forgot-password, if the admin row has an email;
3. psql.

## Docs
- **`DEVOPS.md` ~§229:** the default credentials are local only. On any Postgres environment the guard
  disables `admin` / `golden2026` at startup. Give the recovery order, and a psql bootstrap that
  survives `$` in bcrypt *(review)*, following `zap.yml:169-176`:
  ```bash
  HASH=$(htpasswd -nbBC 10 '' 'NewPass!1' | tr -d ':\n')
  docker exec -i goldenhour-db psql -U goldenhour -d goldenhour -v hash="$HASH" <<'SQL'
  UPDATE app_user SET password = :'hash', password_change_required = TRUE WHERE username = 'admin';
  SQL
  ```
- **`DEVOPS.md:86-88`:** the curl login example is local only (port 8083), or replace it with a
  placeholder password.
- **`README.md:151-153`:** the default credentials are local (H2) only; drop "change it after first
  login".
- **`docs/engineering/reference/platform.md`** (Auth): one line each on the current-password check
  (400/429), server-side flag enforcement (403 + code, the exemptions, the client event) and the guard.
- **`docs/engineering/reference/api-endpoints.md`:** the change-password body and its statuses, and the
  403 every other `/api/` call returns while the account is flagged.
- **`changelog.d/20261028-password-hardening.md`** (`### Changed — …` or `### Fixed — …`).

## Not in scope
- The per-account login limit applying only on the tokenless path.
- `ClientAddress` trusting client-supplied IP headers. The docker-compose port change on the host
  mitigates it; the owner makes that change.

## Gates
- **Backend:** the `CLAUDE.md` build ladder through the full `clean verify` excluding integration tests.
- **Frontend:** `npm run lint && npm test && npm audit --audit-level=high && npm run build`.
