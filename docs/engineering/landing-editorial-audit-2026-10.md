# Landing editorial audit — October 2026

Findings from the site-wide editorial pass over `landing/*.html` (2026-10-25). The editorial
changes themselves are in the same branch; this file records what the investigation found in the
code that the copy could not fix. Read-only investigation against the committed tree; production
runs hand-edited copies of `docker-compose.yml`, `nginx.conf`, `application-prod.yml` and
`scripts/backup-postgres.sh`, so anything marked *prod-dependent* must be confirmed on the host.

Classification: **CONFIRMED** (seen in code), **POTENTIAL** (a risk that needs a condition to
bite), **DOC** (copy and code disagree).

---

## 1. Security and privacy

### 1.1 Access JWT in SSE query strings — CONFIRMED

- `config/JwtAuthenticationFilter.java` `extractToken` / `isSsePath` accepts `?token=` on
  `/api/forecast/run/{id}/progress` (ADMIN), `/api/forecast/run/notifications` and
  `/api/status/stream` (any signed-in user).
- `frontend/src/utils/createEventSource.js` appends the 24 h access token from `localStorage`.
  Every signed-in page load opens two such streams (`App.jsx`), and every reconnect (5 s retry,
  visibility change) requests the URL again.
- Both `nginx.conf` files declare `access_log` with no `log_format`, so nginx's default
  `combined` format logs `$request` (path **and query string**), `$http_referer` and the user agent.
  In the `nginx:alpine` image that goes to stdout → Docker's container log; `goldenhour-frontend`
  in `docker-compose.yml` has no `logging:` limit (*prod-dependent*: host daemon defaults).
- Logout revokes only the refresh token, so a logged access token stays usable for up to 24 h.
- Admin tokens leak through the run-progress stream.
- Spring logs are clean: `RequestLoggingInterceptor`, `SecurityConfig` and `RewindFilter` log
  `getRequestURI()`, which excludes the query string. No Tomcat access log is configured.
- The Privacy Policy already discloses this. Disclosure is not mitigation.

### 1.2 One-time links in URLs and the Referer echo — CONFIRMED (logging) / POTENTIAL (takeover)

- Verification `/verify?token=` (24 h) and reset `/reset?token=` (1 h). Both are stored hashed
  and earlier links are invalidated (`VerificationTokenIssuer`, `PasswordResetIssuer`).
- `Referrer-Policy: strict-origin-when-cross-origin` sends the **full** URL on same-origin
  requests, so the page's own `/assets/*` and `/api/auth/*` calls repeat the token in `Referer`,
  and nginx logs it a second time. Cross-origin (Turnstile, Esri) gets the origin only.
- The verification token is also the credential for `POST /api/auth/set-password`. Anyone who
  reads a logged verification URL within 24 h, before the owner sets a password, could verify the
  pending account and set its password.
- The URL is cleaned with `history.replaceState` only on completion, not on mount.

### 1.3 Remediation plan for 1.1–1.2 (not implemented: it touches auth; one PR per step)

1. **nginx log redaction** (smallest, removes most of the exposure). Add a `log_format` that
   logs `$request_method $uri $server_protocol` instead of `$request`, and a `map` that strips
   `?…` from `$http_referer`. Apply it to both nginx configs, bound the frontend container log in
   prod compose, then rotate or purge the existing logs on the host.
2. **Referrer-Policy `strict-origin`** (or `no-referrer` on `/verify` and `/reset`), and call
   `replaceState` on mount after reading the token into state.
3. **Move one-time tokens into the fragment** (`/verify#token=`, `/reset#token=`): fragments
   never reach the server or `Referer`. This needs `UserEmailService.appLink` and the
   `App.jsx` token read to change, and should accept both forms during the transition.
4. **Take JWTs out of SSE URLs.** Either fetch-based SSE with `Authorization: Bearer` (e.g.
   `@microsoft/fetch-event-source`), or a `POST /api/stream-ticket` that mints a ≤ 60 s,
   single-use, path-scoped ticket. Then delete the query-param branch in
   `JwtAuthenticationFilter`.
5. Optional hardening: rotate the token at verify (or bind `set-password` to a short window after
   `verify-email`); a per-session revocation for access tokens on logout.
   **Status: done (T, #1445)** — verification rotates to a 30-minute setup token; logout bumps the credentials version.

### 1.4 Legacy outcome API readable and writable by every account — CONFIRMED

**Status: done (O1, #1443)** — the controller and API are removed; the `actual_outcome` table was dropped in V195 (O2).

- `controller/OutcomeController.java`: `GET /api/outcome`, `GET /api/outcome/all` and
  `POST /api/outcome` carry no `@PreAuthorize`. The only gate is `/api/**` authenticated, so a
  **LITE account can read every record, including other writers' free-text `notes`, and create
  records.**
- `actual_outcome` has no `user_id`. Account deletion cannot find these rows, and no job cleans
  them up, so they are kept indefinitely.
- Integrity: `actual_rating` feeds the admin calibration report (`findCalibrationPairs`), so any
  account can skew calibration.
- Exposure is latent: reference docs say the table has had zero rows, and no UI calls it since
  2026-02-27.
- The newer `/api/outcome/ratings*` is correctly scoped: own rows only, PRO/ADMIN writes, and
  `ON DELETE CASCADE`.
- **Recommendation:** gate the whole controller to ADMIN now (small, safe), then retire it per
  `v1-retirement-plan.md` §8. The Privacy Policy already describes this behaviour accurately.

### 1.5 Smaller findings

**Status: Waitlist items done (W, #1451)** — Turnstile, rate limits, no address in the log, admin invite and remove.

- **Waitlist** — POTENTIAL. `POST /api/waitlist` is `permitAll` with no Turnstile, rate limit or
  confirmation, so anyone can add a third party's address. The address is also logged at INFO;
  the Privacy Policy now says so.
- **Waitlist promise** — DOC. The register page says "we'll notify you when a spot opens up",
  but no waitlist mail exists. Removal is manual SQL.
- **Ask and home area** — POTENTIAL. No identifiers or home coordinates reach Claude. However,
  the set of location names returned for a "near home" filter lets the model infer the reader's
  rough home area. Worth one line in the policy if you want to be exhaustive.
- **Off-box backup copy** — DOC, *prod-dependent*. `backup-postgres.sh` has an optional off-box
  rsync, which the policy does not mention.
- **Landing has no CSP** — POTENTIAL. Cloudflare Web Analytics auto-injection, if ever enabled
  in the dashboard, would run on photocast.online. The app's CSP would block it.

---

## 2. Copy vs implementation

### 2.1 Pro features that the app shows to Lite — CONFIRMED, owner decision: keep the copy, gate later

The pricing list, FAQ and Terms §4 list these as Pro. The v2 UI shows them to every role:

| Feature | Why Lite sees it |
|---|---|
| Fiery Sky / Golden Hour scores | `GET /api/briefing/evaluate/scores` has no role gate. `WindowSpotPeek`, `WindowSpotStrip` and `LocationFourDaySheet` draw the scores without a role check |
| Per-location written explanation | Same endpoint (`summary`), same surfaces |
| Best-bet picks ("Best bet" / "Also good") | `window.pick` is rendered in `WindowFirstHeatStrip`, `WindowPickDialog` and `WindowSheetDialog` without a role check |
| Drive times | The drive-time endpoints and `DriveTimeRefreshJob` serve every account with a home. Only the reach filter and the Settings refresh button are Pro |

The owner chose (2026-10-25) to keep these as Pro and gate them in code. That is a separate
change, with the backend as the real enforcement (CLAUDE.md, Roles): a role-shaped `/scores`
payload, and pick / drive gating in the briefing shaper. Each needs a worded Lite upsell in the
house pattern. Until that ships, the site over-claims what Pro adds.
`roles-and-gating.md` ("LITE gets basic scores … blurred metrics") is also stale.

### 2.2 Other copy facts checked

**Status: dormant accounts — job built (D, #1448), seeded PAUSED and dry-run by default.**

- **> 200 locations** — supported by production snapshots in docs: 253–263 enabled, about 231–238
  of them sunrise/sunset places. The count lives in the prod DB.
- **Regions** — the app has four: Northumberland & Tyneside, North York Moors & Coast, Yorkshire
  Dales and Lake District. The site's geographic names (Teesdale, Tyne and Wear …) are fine as
  geography.
- **Horizon** — ratings run from today to T+3. T+2 and T+3 are rated only when the weather is
  `SETTLED`. Plan draws six events; the topic and location sheet run four days; Coming up covers
  90 days.
- **Three updates a day** — true only while the evening sunrise refresh row stays ACTIVE. It was
  seeded PAUSED (V184) and resumed in prod on 2026-10-20.
- **Aurora** — polling runs every 5 minutes around the clock: a daytime lookahead and a night
  poll.
- **Ask** — off by default (`photocast.ask.enabled`). The committed prod yml does not set it, so
  the prod setting lives on the host.
- **Billing** — no payment code exists. Pro is assigned by an admin.
- **Registration** — capped at 10 non-admin accounts (`RegistrationProperties.maxUsers`, no prod
  override in git). The site now mentions the waitlist.
- **Dormant accounts** — DOC. Terms §12 and the Privacy Policy say inactive accounts may be
  deleted after 90 days with a warning email. No such job exists, and `lastActiveAt` tracks any
  authenticated request, not logins. Left unchanged for legal review: either build the job or
  reword.
- **Unacknowledged sources** — DOC, owner to decide. NLCNET (ed-co.net, scraped every 10 minutes
  for noctilucent-cloud sightings) appears nowhere on the site. Its terms of use are unverified.
  Frankfurter (ECB exchange rates), used only for admin cost reports, needs no public credit.
