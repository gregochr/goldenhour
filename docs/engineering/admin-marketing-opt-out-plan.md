# Admin opt-out of a user's marketing emails — plan

## Goal
Operations → People → Users: an admin can turn a user's marketing-email consent OFF from the user panel.
Today the panel shows `Marketing: Yes/No` read-only (`UserReaderPanel.jsx`, "Preferences and legal"); only the
user can change it (Settings → `PUT /api/auth/marketing-emails`).

## Decision: opt-out only, never opt-in
Under UK GDPR / PECR, marketing consent must be the user's own act. An admin can withdraw it (for example the
user asked by email to stop), never give it. The endpoint takes no body and can only write `false`. The panel
offers "Opt out" only while the user is opted in; when No, it shows "No" and nothing to press.

## Backend
1. `UserService.optOutOfMarketing(Long id)`: calls the existing scoped
   `AppUserRepository.updateMarketingEmailOptIn(id, false)`; if it returns != 1, throw
   `IllegalArgumentException("User not found: " + id)`. Javadoc. (The column is `updatable = false`; never
   `setX()` + `save()`.)
2. `UserController`: `PUT /api/users/{id}/marketing-opt-out` (PUT like the class's other idempotent writes
   `/{id}/enabled|role|email`; the class-level `@PreAuthorize("hasRole('ADMIN')")` covers it). No request body.
   200 `{"marketingEmailOptIn": false}`; **400** `{"error": ...}` for an unknown id, from
   `catch (IllegalArgumentException)`, matching every other not-found in this class. Idempotent: an
   already-opted-out user still gets 200. Log at INFO the admin's username (`Principal principal`,
   `LogSanitizer.sanitize(principal.getName())`, as `AuthController` does) and the target id; never the email.
3. Tests: `UserServiceTest` (one row → no throw, verify exact args `(id, false)`; zero rows → throws);
   `UserControllerTest` (200 body, 400 body for unknown id, service called with the exact id; the request
   carries a principal; **LITE_USER → 403 and PRO_USER → 403**, following the per-endpoint 403 tests already in
   that class, with the service never called). Pin every stub argument; `any()` only inside `never()`.

## Frontend
4. `api/userApi.js`: `optOutUserMarketing(userId)` → `apiClient.put(`/api/users/${userId}/marketing-opt-out`)`,
   returns `response.data`; JSDoc like its neighbours; test in `userApi.test.js`.
5. `components/users/UserReaderPanel.jsx`, "Preferences and legal" → the `Marketing` Fact:
   - Opted in: "Yes" plus a small **Opt out** button (`textButtonClass`, `max-[639px]:min-h-11`,
     `data-testid={`marketing-opt-out-${user.id}`}`).
   - It opens the existing `useConfirmDialog`: title `Stop marketing emails to ${user.username}?`, message
     `Only they can opt back in, from their own Settings.`, confirmLabel `Opt out`, maxWidth `sm`. On confirm,
     mirror `handleDisable`: `closeDialog(); setActionError('')`, call the API, `await onUsersChanged()`,
     `onToast(`Opted ${user.username} out of marketing emails.`)`; on failure
     `setActionError(apiErrorMessage(err, `Failed to opt ${user.username} out of marketing emails.`))`.
   - Opted out: "No", no button.
   - Offered for an account awaiting verification too (Preferences are not behind `!awaiting`).
   - A failure shows in the panel's existing `actionError` banner in "Account actions" — accepted, same as the
     other panel actions.
   - Same on every width (not phone-only work). Tailwind only, no inline styles.
6. Tests (follow `docs/engineering/frontend-test-standards.md`): button only when opted in; confirm → API called
   with the id, list refetched, toast; cancel → no call; failure → error banner text; opted out → no button.

## Docs and changelog
7. `docs/engineering/reference/api-endpoints.md` (the `/api/users` line): add
   `PUT /api/users/{id}/marketing-opt-out` (ADMIN; no body; only ever sets false; 200, 400 unknown user).
   `docs/engineering/reference/platform.md` user-management paragraph: "Preferences and legal" carries
   **Opt out** while the user is opted in (an admin can withdraw consent, never give it).
8. `changelog.d/20261117-admin-marketing-opt-out.md`, `### Added — Admins can opt a user out of marketing emails`.

## Gates
Backend: compile, the two test classes, checkstyle, full verify with `-Dtest='!**/integration/**'`.
Frontend: lint, test:coverage, audit, build; diff-cover 90% of changed lines.
