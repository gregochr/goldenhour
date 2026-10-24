# Ask PhotoCast becomes Pro and Admin only — plan

> Owner ask, 2026-10-24: "make Ask a PRO and ADMIN feature only."
> Status: **plan, not built.** Two build phases, each one Sonnet-built PR: **F1 (client lock) first, then
> B1 (backend 403)** (§6). The LITE treatment copies the drive filter's lock (§2).

## 1. Where we are

Ask is open to every role today, and the daily allowance is the only gate: LITE gets 3 typed questions,
PRO and ADMIN get 30 (`AskProperties.limitFor`). The 2026-10-05 assessment in
`reference/roles-and-gating.md` ("Ask PhotoCast: visible and usable by every role") records the reasoning.
That reasoning still holds, since Ask carries no data a LITE reader cannot already see. What changes is
the product decision: Ask is now a Pro feature in its own right, and not just a larger allowance.

| Surface | Today, for LITE |
|---|---|
| `POST /api/ask` | 200 within 3 a day; `AskAdmissionInterceptor` counts it before the controller |
| `GET /api/ask/ready` | 200, ETag-revalidated, user-independent |
| `GET /api/user/settings/ask` | 200 `{enabled, used, limit: 3, left, typedAvailable}` |
| `/api/admin/ask/**` | 403 (`@PreAuthorize` ADMIN), and stays that way |
| Client | every entry live: phone `AskBar`, phone Map `MapPeekAsk`, tablet `AskField` → `AskSheet`, desktop/wide `AskField` → `AskDock` plus the `/` key; `AskEmptyState` prints "N of M own questions left today · Pro 30 a day" |

The client's availability (`context/AskContext.jsx:449`) is `off | down | pending | on`. `isPro`
(`WindowFirstBriefingContext.jsx:681`) only drives the allowance copy and the aurora join today. No
surface branches on the role.

## 2. The target

- **The backend refuses LITE.** `POST /api/ask` and `GET /api/ask/ready` return **403** for `LITE_USER`
  with the flag on or off. The refusal happens before the body is read, before the rate limiter counts
  anything, and before the flag interceptor runs. This follows the admin routes' rule ("the 404 is only
  ever shown to someone who could have used the endpoint", `AskFlagInterceptor`).
- **The settings read stays open to every role, and its shape does not change.** For a LITE caller with
  the flag on it returns `enabled: true` with zeros and `typedAvailable: false`, so the client can still
  tell the flag being on from it being off. There is no new `entitled` field: the client derives the lock
  from the role, as the drive filter does (below). With the flag off, `off()` is unchanged.
- **The client shows LITE a locked Ask entry, gated exactly as the drive filter is** (the lens bar's
  `Drive from home` row, `WindowFirstLensBar.jsx:216–251`, and its twin in `WindowSpotSheet.jsx:259–287`):
  - **Where the lock comes from.** It is read from the role in a context
    (`useReachLens(todayStr, role === 'LITE_USER', …)` at `WindowFirstBriefingContext.jsx:556`), and only
    the derived boolean crosses into components. Ask uses the `isPro` the context already exposes
    (`:681`), which is the same allowed boolean (`WindowFirstShell.jsx:170`, plan §5c).
  - **How it is drawn.**
    - The controls get a real `disabled` ("pointer-events does not stop a keyboard").
    - A wrapper takes `.wf-lens-locked` (opacity .45 and `pointer-events: none`, `index.css:3686–3696`).
    - A `<span className="wf-lens-pro">Pro</span>` pill sits as a sibling **outside** the greyed
      wrapper. Inside it, the pill measured 3.68:1 against AA's 4.5:1; outside it measures 12.59:1.
    - No upsell words, and no link.
  - **Where the action is refused.** In the hook itself (`useReachLens.js:88–94`: a locked lens pins its
    value and `selectTier` refuses), not only in the button. So the Ask lock refuses in the open
    callbacks too.
  - **Rewind.** Nothing is fetched while locked; Rewind is unchanged.

  **Owner Q1** (§7) asks whether LITE should see nothing instead.

## 3. B1 — backend (one PR, Sonnet)

### 3.1 The gate

1. **`SecurityConfig`**: add `.requestMatchers("/api/ask", "/api/ask/**").hasAnyRole("PRO_USER", "ADMIN")`
   **above** the `/api/**` → `authenticated()` line. It runs in the security filter chain, which sits ahead
   of three things:
   - `AskBodyLimitFilter` (order 0);
   - `AskFlagInterceptor`, so a LITE caller gets 403 even with the flag off;
   - `AskAdmissionInterceptor`, so a LITE request is never counted or resolved.

   `@PreAuthorize` alone would be too late, because it runs inside the controller, after the admission
   interceptor has already counted the request. ⚠️ `/api/user/settings/ask` does not match `/api/ask/**`;
   pin that with a test.
2. **`AskController`**: add `@PreAuthorize("hasAnyRole('PRO_USER','ADMIN')")` at class level as a second
   layer and as the visible marker the project uses elsewhere. Rewrite the class javadoc, which currently
   says "no role gate … a gate would deny nothing".
3. **`AskFlagInterceptor`**: no code change. Its javadoc should now say that the public routes are
   role-gated ahead of it by the security chain, so its "only the admin routes stand down" sentence stays
   true.
3a. **One refusal shape, `NOT_ENTITLED`** (review M1). `SecurityConfig` has no `accessDeniedHandler`
    (`:65–72`). A chain 403 is therefore Boot's `/error` body,
    `{timestamp, status, error: "Forbidden", path}`, which breaks `AskController`'s "every refusal is
    `{error, code}`" (`:67–68`). Worse, `askApi.js:47–54` would show the literal "Forbidden" as the
    sentence.
    - Add `AskErrorCode.NOT_ENTITLED` (HTTP 403, sentence "Ask PhotoCast is part of Pro."). The enum is
      not persisted, so `LocalH2EnumWidener` is not involved.
    - Register
      `.exceptionHandling(ex -> ex.defaultAccessDeniedHandlerFor(askDeniedHandler, PathPatternRequestMatcher.withDefaults().matcher("/api/ask/**")))`.
      The handler writes `new AskRefusal(NOT_ENTITLED).body()` as JSON. Every other path keeps the
      default handler. Pin both.
3b. **A service guard for the routes that skip the allowance** (review H1). `limitFor(LITE) = 0` alone
    does **not** refuse LITE. `AskService.ask` answers at three steps before `reserve()` (`:288`), and
    `AskTypedSeamsTest:232–234` and `:288–291` pin that those three answer with no allowance left:
    - step 3, the pre-filter can't-answer (`:236–240`);
    - step 5, the Ready intent match (`:261–271`);
    - step 6, the typed-cache hit (`:275–281`).

    So the **first statement of `admit()` and of `ask()`** is
    `if (!AskUserContext.mayUseAsk(user.getRole())) throw new AskRefusal(NOT_ENTITLED)`. The guard in
    `admit` runs before the rate limiter counts anything. Pin it for each of the three free paths, plus
    the engine path, and assert that no `ask_usage` row is created (`AskUsageStore.ensureRow`, `:79`).

### 3.2 Allowance and settings

4. **`AskSettingsResponse`**: the record keeps its shape (no new field; §2). Update its javadoc so that
   `limit` reads "0 for a role that may not use Ask".
5. **`AskService.settings`**: if the flag is on and the role is not PRO/ADMIN, return
   `new AskSettingsResponse(true, 0, 0, 0, false)` without reading `usageStore`. Keep one predicate for
   "may use Ask", for example `AskUserContext.mayUseAsk(UserRole)` placed next to `mayViewAurora`, and use
   it here and in step 6.
6. **`AskProperties`**: remove `limit-lite` (field, setter, bounds, and the javadoc in `limitFor`).
   `limitFor(role)` returns `limitPro` for PRO/ADMIN and **0** for any other role, including `null`.
   `engineCeilingFor` is then 0 as well, which is a plain multiply. The refusal itself is step 3b's guard,
   not this 0.
   ⚠️ A production `application.yml` may still set `limit-lite`, and it must not fail startup. Spring
   ignores unknown keys by default, and the tree has no `@Validated` and no `ignoreUnknownFields = false`
   (`AskProperties.java:28–31`). Pin it with **`ApplicationContextRunner`**:
   `withUserConfiguration(AskProperties.class).withPropertyValues("photocast.ask.limit-lite=3")`, then
   `hasNotFailed()`. `AskPropertiesTest`'s raw `Binder` (`:220–223`) ignores unknown keys whatever the
   annotation says, so a test written that way proves nothing (review M2).
7. **`application-example.yml`**: delete the `limit-lite` line. Change the `limit-pro` comment to "typed
   questions a day, PRO_USER and ADMIN (Ask is Pro-only)", and drop the sentence about the client's
   "Pro: 30 a day" constant (F1 removes that copy).

### 3.3 Left alone on purpose

- `AskUserContext.canSeeAurora` and the LITE branches of the night tool and the thread re-join stay.
  - They are no longer reachable over HTTP by a LITE caller.
  - A user-less Ready conversation still relies on them.
  - Removing them is a separate tidy-up, not part of this gate.
  - ⚠️ **Their tests must not die silently.** `AskServiceTest.liteVersionAndSolarAnswer` (`:1409`) and
    `auroraSegmentIsNoOracleForLite` (`:1460`) reach the LITE aurora branches through `ask()`, which
    step 3b now refuses. Move that coverage down to the unit that owns each branch: `AskThreadContext`,
    `AskTools`, the validator, or `AskReadyFreshness`. Build the LITE `AskUserContext` directly there.
    Do not delete the tests. List each one moved in the PR.
- Ready precompute is user-less and unaffected. `AskDryRunService` builds an ADMIN context and never calls
  `limitFor`.
- `ask_usage` and `ask_log` rows from LITE users stay, and no migration is needed. `AskMetricsService` has
  no role breakdown, so nothing changes there.
- `AskNightRegressionTest` (prompt regression) uses a LITE context against the engine directly. It is
  unaffected, and its assertions are never touched.

### 3.4 The ETag

`/api/ask/ready` has an ETag (`HttpCachingConfig`, filter at `HIGHEST_PRECEDENCE`, so it wraps the
security chain). `ShallowEtagHeaderFilter` only gives a 2xx response an ETag, so a 403 can never become a
304. **Pin this** in the full-context style of `AskControllerTest.etagRevalidation` (`:215–233`):
- send a LITE GET with the `If-None-Match` value from a PRO 200;
- expect 403, not 304, and `header().doesNotExist("ETag")`.

A shared browser that switches from a PRO login to a LITE login must not replay the PRO body. The 403
still carries `Cache-Control: private, no-cache`, because the filter sets it before the chain
(`HttpCachingConfig.java:179`). That is harmless.

### 3.5 Tests (backend, `docs/engineering/test-improvement-standards.md`)

The breakage inventory below is the reviewer's, with line numbers (review H2). Sonnet must re-grep
`LITE_USER`, `setLimitLite`, `getLimitLite` and `limitFor` across `backend/src/test` before starting,
and treat this list as a floor, not a ceiling.

- **`AskControllerTest`.**
  - `everyRoleMayRead` (`:95`) becomes: PRO and ADMIN get 200; LITE gets 403 with the `NOT_ENTITLED`
    body and `verifyNoInteractions(readyServing)`.
  - `wireShape` (`:126`) and `safetyNoteIsOnTheWireWhenPresent` (`:159`) move from LITE to PRO.
- **`AskTypedControllerTest`.**
  - `everyRole` (`:124`) splits: LITE gets 403 `NOT_ENTITLED` on POST with `verifyNoInteractions(askService)`
    (so `admit` never ran), and 200 on settings.
  - The other LITE `@WithMockUser` cases move to PRO.
  - `settingsShape` (`:545–556`) becomes PRO, with limit 30. Add a LITE settings case:
    `(true, 0, 0, 0, false)`.
- **`AskFlagOffPrecedenceTest`.** `liteCallerSeesTheRoleCheckBeforeTheFlag` (`:109`) changes for both
  public routes from 404 to 403. Rename it and rewrite its description. Add the flag-on LITE control for
  the public routes. `settingsReadIsAlwaysAnswered` (`:139`) is unchanged.
- **`AskAdmissionTest`.** It is `standaloneSetup` (`:89–94`), with no security chain and no
  `@PreAuthorize` proxy, so **no chain test goes here** (review H3); that test is the
  `AskTypedControllerTest` case above. Flip the fixture to PRO and add a unit case: a LITE `admit()`
  throws `NOT_ENTITLED` before the limiter counts.
- **`AskServiceTest`.**
  - The fixture's default role is LITE (`:90`); make it PRO.
  - The ~12 `setLimitLite` calls (`:268, 286, 313, 463, 675, 826, 993, 1336, 1508, 1527`) won't compile.
    Use `setLimitPro`.
  - `roleAllowances` `@CsvSource "LITE_USER,3"` (`:960`) becomes `LITE_USER,0`.
  - `dailyLimit`'s 9 = 3×3 (`:973–984`) is re-derived for PRO.
  - `settingsOn` (`:1317`) becomes PRO, and gets a LITE twin.
  - Add the step 3b guard tests here.
  - The two LITE aurora tests move (§3.3).
- **`AskTypedSeamsTest`.** `setLimitLite(3)` (`:93`) goes. Bob (`:110–111`) becomes PRO, with the seam
  numbers re-derived. Add one LITE seam per free path (Ready match, cache hit, pre-filter), each expecting
  `NOT_ENTITLED`.
- **`AskPropertiesTest`.**
  - Remove the `Bound("limit-lite", …)` entry (`:38`) and `getLimitLite()` (`:63`).
  - `limitFor(LITE)` and `limitFor(null)` are now 0 (`:114`, `:117`).
  - Re-derive `engineCeilingFor` (`:125–131`).
  - Add the `ApplicationContextRunner` pin from step 6.
- **The settings matrix.** It lives in `AskTypedControllerTest` (`:543–583`) and `AskServiceTest`, **not**
  `UserSettingsControllerTest`, which has no Ask case. LITE also expects `usageStore` untouched:

  | Role | Flag | Expected |
  |---|---|---|
  | LITE | on | `enabled: true`, zeros, `typedAvailable: false` |
  | PRO | on | limit 30 |
  | ADMIN | on | limit 30 |
  | any | off | `off()` |

- The ETag pin from §3.4.
- The `/api/user/settings/ask` matcher pin from step 1: LITE gets 200. (`CredentialInvalidationFlowTest:63`
  also uses this route as its "any reader route", which is one more reason it stays open.)
- The step 3a handler pin: a LITE `/api/ask/ready` 403 has the `{error, code: "NOT_ENTITLED"}` body, and
  a 403 elsewhere (for example a LITE `/api/admin/ask/metrics`) keeps the default body.

### 3.6 Docs in the same PR

- `reference/api-endpoints.md` §Ask: the header "(Bearer / ADMIN)" and the role column. Add LITE 403 to
  the error table, and record that the flag-off 404 is never shown to LITE.
- `reference/roles-and-gating.md`: rewrite lines 25–27 and the 2026-10-05 bullet (line 62) as the
  2026-10-24 decision, keeping one sentence of history.
- `reference/configuration.md`: remove `limit-lite`.
- `reference/ask-photocast.md` intro and *typed guards*: add the role gate as the first guard, ahead of
  the rate limit.
- `CLAUDE.md` overview line: "Ask PhotoCast (Pro and Admin; a flag-gated …)". One phrase, nothing more.
- `changelog.d/20261024-ask-pro-only-backend.md`, `### Changed — Ask PhotoCast is Pro and Admin only (API)`.

### 3.7 Gates

The build ladder ends with the full `clean verify` that excludes integration tests, and the gate is
Maven's exit code. Also run the frontend's four commands, because frontend tests regex backend source
(memory: `frontend-tests-regex-backend-source`). Grep `readFileSync` in `frontend/src/test` for
`AskSettingsResponse`, `limitLite`, `SecurityConfig` and `AskController` first.

## 4. F1 — client (one PR, Sonnet)

### 4.1 State

1. **`useAskAllowance`**: unchanged. LITE still reads `GET /api/user/settings/ask`, which returns 200 and
   tells the client whether the flag is on.
2. **`AskContext`**: add availability **`locked`**. It is derived from the `isPro` the provider already
   reads (`AskContext.jsx:237`), the drive filter's role-derived boolean, applied the same way:
   - **`locked` only where Ask would otherwise be `on` (or `down`), never during `pending`.**
     `AskContext.jsx:155–157` forbids the flash: "pending … show nothing — a flash on a flag-off server is
     the failure". A LITE reader on a flag-off server must see nothing, then nothing.
   - `off` still wins, so a LITE reader with the flag off sees no Ask at all, as today.
   - `typedDisabled` is true.
   - The role never reaches a component, only the derived state.
   - Add `'locked'` to the availability typedef (`:155`).
   - `AskInputRow.jsx:61` already has `const locked = ask.typedDisabled`, meaning "typed off". Rename it
     to `readyOnly`, so the two meanings never meet.
3. **Refuse in the openers, not only the button** (the `useReachLens` rule). Each opener returns while
   locked: `openAskSheet` (`WindowFirstShell.jsx:1479`), `openAskDock` (`:1677`) and the peek's
   `openAskSection` (`MapView.jsx:5840`). The review verified these are the only three. There is no
   URL or hash restore, and every other route lives inside an already-open conversation.
   - `askTyped` refuses while locked.
   - `AskConversation.jsx:162` passes `enabled: generatedAt !== null && availability !== 'locked'` to
     `useAskReady`, which has no context of its own. That is defence only: `AskConversation` cannot mount
     while locked.
4. **A stale role.** The server reads the role from the database row on every request
   (`JwtAuthenticationFilter.java:45, 113`), so a demotion is enforced at once. The client's role is
   localStorage `goldenhour_role`, written at sign-in (`AuthContext.jsx:40, 67, 126`) and not updated by
   the token refresh (`:181–187`). It stays as it was until the next sign-in, in both directions. A
   promoted LITE reader also stays locked until they sign in again; record that in the reference doc.
   A demoted session gets B1's 403 `NOT_ENTITLED`:
   - Add `NOT_ENTITLED` to `REFUSAL_SENTENCE` (`AskContext.jsx:97–103`), so it reads as a refusal with no
     "Try again" loop, not as `FAILED`.
   - F1 lands before B1, so the code is mapped before the server sends it.
   - No new availability state is needed.
   - Add one test showing that a POST 403 `NOT_ENTITLED` shows the sentence and offers no retry.

### 4.2 Surfaces

Every band changes, by design: this is a role gate, not phone-only work. Desktop and tablet change only
for LITE, so PRO and ADMIN must be provably unchanged in every band.

4. **One locked look, the drive filter's exactly**, on `AskBar` (phone), `AskField` (tablet and
   desktop/wide) and `MapPeekAsk`'s entry. Each takes a `locked` prop.
   - **The wrapper.** The entry control sits inside a wrapper that gets `wf-lens-locked`. Reuse that
     class, and do not add a new one: it is the single LITE-treatment rule.
   - **The control.** It keeps its **real `disabled`**, which `AskBar` and `AskField` already carry for
     `down` (`AskBar.jsx:33,43`, `AskField.jsx:49,63`), together with the wrapper's
     `pointer-events: none`.
   - **The pill.** `<span className="wf-lens-pro" data-testid="…-ask-upsell">Pro</span>` is a sibling
     **outside** the greyed wrapper, never inside it (the lens bar's 3.68:1 measurement). Use the
     `wf-lens-pro` span, as the drive row does, not `shared/ProPill`, so the two locked controls on one
     screen look the same.
   - **No upsell sentence and no link** (*Role gating — UI pattern*).
   - **The prompt.** The placeholder or prompt text is unchanged, so the reader can see what is locked.
   - **The accessible name.** Make sure the pill is not swallowed by an `aria-label` on the wrapper
     (memory: `aria-label-replaces-content`).
   - **The phone `AskBar` keeps its own box** (review H1). The `<button class="wf-ask-bar">` is itself
     the fixed bar (`index.css:14258–14275`: `position: fixed`, z-index 40, an opaque background).
     Wrapping it in `.wf-lens-locked` would make it see-through, and `pointer-events: none` would let a
     tap fall through to the Plan or Coming up card scrolled underneath, a ghost tap that can open a
     window popup. So:
     - `.wf-ask-bar` stays the opaque, hit-testable fixed box and keeps its real `disabled`;
     - `wf-lens-locked` goes on an **inner span** that wraps the bar's `k`, `q` and `go` spans;
     - the `wf-lens-pro` pill is a sibling of that span, inside the bar;
     - `askCss.test.js:109–128` (the bar's fixed geometry) must stay green unchanged.
     Measure the fit at 320px.
   - **The peek entry.** In `.wf-map-peek-ask-row` (a flex row with a 6px gap, `:14668`), the
     `wf-lens-locked` wrapper needs `flex: 1; min-width: 0`, because the entry's `flex: 1` (`:14677`) then
     applies inside it. The pill is the row's next sibling. `askPeekCss.test.js`'s pins are unaffected.
     `MapPeekAsk:203`'s `disabled={availability === 'down'}` extends to `locked`.
   - **Tablet.** The 260px `AskField` sits in `.wf-tabrow`, and the pill takes about 35px from the tab
     list. Measure at 640px.
   - **No double dim** (review M1). All three controls already dim themselves when disabled:
     - `.wf-ask-bar:disabled { opacity: .5 }` (`:14276`);
     - `.wf-askf:disabled` (`:14355`);
     - `.wf-map-peek-ask-entry:disabled` (`:14690`).

     Inside `.45` that composes to about .23. Neutralise it under the lock, for example
     `.wf-lens-locked .wf-askf:disabled { opacity: 1 }` and the same for the other two, so the locked
     entry reads at the drive row's .45. Pin each with a CSS test.
   - **Do not advertise what is refused** (review M4). While locked, drop the desktop field's
     `aria-keyshortcuts="/"`, the wide `<kbd>/</kbd>` hint (`:2383`), and any `aria-haspopup` or
     `aria-expanded`.
   - **The accessible name.** The pill sits outside the button, so it is not in the button's name. That
     is accepted: it is the lens bar's own precedent.
   - **Focus.** A `disabled` control that held focus drops focus to `<body>`. That cannot happen here: the
     client's role is fixed for the session (§4.1 step 4), so the entry is disabled from its first render.
5. **`WindowFirstShell`**
   - The explicit `on || down` lists `askEntry` (`:1434`), `askDockEntry` (`:1443`) and `askGhost`
     (`:1468`) add `locked`, so the entry draws.
   - **Fold `locked` into `askDisabled` (`:1462`)** (review H3). That one line:
     - gives `AskField` (`:2370`, `:2385`) and `AskBar` (`:2736`) their real `disabled`;
     - refuses the `/` key at `:1914`, **before** the listener's `preventDefault` (`:1922`).

     A guard only in `openAskDock` would swallow every `/` for a LITE desktop reader, including
     Firefox's quick-find. The opener guards (§4.1 step 3) stay as a second layer. `/` is refused and
     does not focus the field (owner Q2).
   - `askAvailable` (`:1650`, the Plan-matrix pick highlight) is already `on || down`, so it is false
     while locked with no change. Leave it alone.
6. **`WindowFirstMapPane` / `MapView`** (review M2)
   - `MapView` only receives `askOffered` (`WindowFirstMapPane.jsx:321`, passed at `:885`), so it cannot
     know about the lock. Add an `askLocked` prop through the pane to `MapView`.
   - `askOffered` adds `locked`, so the peek row draws.
   - `openAskSection` returns while `askLocked`. It is the only opener, called by both the entry and
     `MinimisedLine`.
   - `MapView:2329` and `:4742` need nothing. A locked reader can never have an expanded section or an
     Ask window. One test proves that the peek never expands while locked.
7. **`AskEmptyState`**: delete the LITE allowance branch and the ProPill. If `PRO_DAILY_LIMIT`
   (`utils/askModel.js:42`) then has no users, delete it too. A LITE reader never reaches the empty state
   now.

### 4.3 Tests (`docs/engineering/frontend-test-standards.md`)

- **Harness defaults.**
  - `test/askConversationHarness.jsx` (`role 'LITE_USER'`) and `test/askShellHarness.jsx`
    (`isPro: false, isLiteUser: true`) default to **PRO**, so the conversation suites keep testing a live
    Ask.
  - Fix the `isPro: false` + `PRO_USER` contexts in AskMapLinkage (`:120`, `:212`) and AskPeekChain
    (`:122`, `:214`). Leave the others alone:
    - AskPlanThis has no `PRO_USER` mock;
    - AskPeekStateTable does not mount `AskProvider`;
    - AskPeekPlanChain is fixed by the harness flip.
  - The harness flip is safe: none of `askConversationHarness`'s nine users exercises night or aurora
    picks, so nothing reaches the unmocked aurora API.
- **Delete or flip the LITE cases** that no longer describe reachable behaviour:
  - AskEmptyState `:117` and `:138`, but **keep** the PRO case at `:148`;
  - AskNight `:164`/`:178`;
  - AskWindowSpread `:59`/`:146`;
  - AskAnswer `:466`;
  - AskEventCard `:34`.

  For each one, state in the PR whether it was deleted or rewritten for PRO.
- **New `AskLocked.test.jsx`**, one block per band (phone bar, phone Map peek, tablet field, desktop/wide
  field plus `/`). Each block asserts:
  - the `wf-lens-pro` pill is drawn **outside** the `.wf-lens-locked` wrapper (assert
    `wrapper.contains(pill) === false`, as the lens bar test does: read `WindowFirstLensBar.test.jsx` and
    `WindowSpotSheet`'s locked tests and mirror them);
  - the control is really `disabled`;
  - **no request**: an axios spy sees no `/api/ask` and no `/api/ask/ready`;
  - no dialog or dock opens (the lazy-absence lesson: assert against a *rendered* control);
  - `/` does nothing: `defaultPrevented` is **false** (the memory note
    `refusal-default-prevented-observable`: the key must not be swallowed), and `document.activeElement`
    is unchanged;
  - the locked desktop field has no `aria-keyshortcuts` and no `<kbd>` hint;
  - the computed opacity of the control is the drive row's (no double dim);
  - phone bar: the bar's own box is not inside `.wf-lens-locked`, so it stays opaque and hit-testable.
- **The context.** A provider-level table over `{isPro, allowance enabled, loaded, failed}`, expecting
  `off`, `locked` or `on`/`pending`/`down`. It must include:
  - **LITE + pending draws nothing**;
  - `off` wins for LITE.
- **`AppAsk.test.jsx`**: one LITE `renderApp` with the locked entry, which proves the whole tree without
  harnesses.
- **PRO and ADMIN unchanged.** The existing PRO suites stay green with no assertion edits beyond the
  harness defaults.

### 4.4 Gates, review and docs

- The four frontend commands (lint, Vitest, `npm audit --audit-level=high`, build).
- **UI review cadence:** an adversarial review of the diff before landing, with about six lenses:
  - focus and keyboard;
  - accessibility and accname;
  - phone vs desktop;
  - role leakage (any request from LITE);
  - visual (forced colours, `.45` contrast of the pill);
  - regression for PRO.

  Reviewers are read-only. Browser check on the local backend (8083) as `admin` (PRO path) and as a
  LITE account. The memory note `cannot-sign-in-for-browser-checks` applies, so say plainly what was seen
  and what was only tested.
- `reference/ask-photocast.md` *Ask — the client*: add the `locked` state.
- `reference/roles-and-gating.md`: the UI bullet.
- `changelog.d/20261024-ask-pro-only-client.md`, `### Changed — Ask PhotoCast is locked for Lite readers`.

## 5. Sonnet briefs

Each phase gets its own Agent call with `model: "sonnet"` and `isolation: "worktree"`, branched from the
updated `main`. Each brief carries:

- the matching section of this plan, verbatim;
- the files named in it;
- the tests to write;
- the gates;
- the changelog file.

The planner reviews each diff read-only and sends fixes back to the same agent. After that the PR follows
CLAUDE.md steps 3–6: a ready PR, CI and Codex 👍 on the exact head commit, then a squash merge. **F1 is
built and merged first, then B1** (§6).

## 6. Order: F1 first, then B1

The client lock reads only the role, which it already has, and depends on nothing from B1. So **F1 merges
first and B1 second**, and no release can contain a broken state:

- **F1 alone.** LITE sees the locked entry, and the backend would still answer if called. Nothing calls
  it.
- **B1 alone.** This must not happen. The LITE client would draw a live field with zero questions left,
  and opening it would call `/api/ask/ready` and get a 403, which leaves an empty conversation.

So B1 branches from `main` after F1 has merged. Record the order in both PR bodies. §5 is amended to
match: build F1 first.

⚠️ **The PWA residual** (review L5). `vite-plugin-pwa` uses `registerType: 'autoUpdate'` and precaches
the bundle (`vite.config.js:29, 53–59`), so an installed PWA or an open tab runs the old bundle for one
session after a deploy. If F1 and B1 go out in the **same** release, a LITE PWA reader sees the "B1 alone"
state once:
- a live field;
- "No own questions left today · Pro 30 a day";
- "Typed questions are unavailable right now…" beside an empty Ready list.

Recommend that the owner **release F1 at least one release before B1**. Otherwise, accept a single
session of that state.

## 7. Owner questions (defaults are what gets built if unanswered)

| # | Question | Default |
|---|---|---|
| Q1 | Does LITE see a locked entry, or no Ask at all? | **Locked, the drive filter's treatment** (greyed, disabled, "Pro" pill outside). Hiding would make `locked` behave as `off` and is a smaller F1. |
| Q2 | `/` while locked | Refused (no-op), as for any disabled control. |
| Q3 | Anything for existing LITE users who used Ask (a notice)? | No. |
| Q4 | Release F1 one release ahead of B1 (§6 PWA residual)? | Yes. |
| Q5 | The Ready heading's "· free" (`AskEmptyState.jsx:41`) reads oddly once Ask is Pro-only. Drop it? | Keep it (it contrasts with typed questions, which still cost allowance); the owner may say otherwise. |
