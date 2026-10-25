# Tokens out of the web-server log — plan

Status: plan, 2026-10-25. Source: the privacy audit of the public site
(`docs/engineering/landing-editorial-audit-2026-10.md` §1.1–1.3, on branch
`claude/photocast-editorial-improvements-23472e` until that merges). One PR per step, each built
by Sonnet, each through CI and Codex 👍 before merge (CLAUDE.md, *How every code change is made*).

## 1. The problem, as the code has it today

| Credential | Lifetime | Where it travels | Who logs it |
|---|---|---|---|
| Access JWT | 24 h, not revoked at logout | `?token=` on `/api/status/stream`, `/api/forecast/run/notifications` (every signed-in page load, every reconnect) and `/api/forecast/run/{id}/progress` (admin) | nginx access log (`combined` logs `$request`, which has the query); nginx **error** log on any upstream error of a stream (`request: "GET …?token=…"`) |
| Verification token | 24 h; also the credential for `POST /api/auth/set-password` | `/verify?token=` in the email | nginx access log; again in `$http_referer` of every same-origin request the page makes (`Referrer-Policy: strict-origin-when-cross-origin` sends the full URL same-origin) |
| Reset token | 1 h | `/reset?token=` | as above |

Facts the steps rely on (checked 2026-10-25):

- `JwtAuthenticationFilter.extractToken` reads `?token=` for the three SSE paths (`isSsePath`).
  **`StatusController.extractLoginTime` (≈ line 320) has its own `getParameter("token")` fallback**;
  the audit did not list it. Both go in step 4.
- `frontend/src/utils/createEventSource.js` is the only place a stream is opened. Callers:
  `useHealthStatus`, `useRunNotifications` → `runProgressApi.js` (two functions), and
  `MarkerPopupContent`. Its public contract (handlers per event name, `onError`, `closeOn`,
  `getToken`, `reconnectOnVisible`, 5 s retry on CLOSED, returns a cleanup) is pinned by
  `createEventSource.test.js` (29 cases). The caller tests (`useHealthStatus`, `runProgressApi`,
  `RunProgressPanelRetryHttp`, `RunOutcomeSurfaces`) `vi.mock` the module, so they are unaffected;
  `App.test.jsx` and `AppSettingsRoutes.test.jsx` only mention it in comments. Today an accidental
  real stream in a test throws because jsdom has no `EventSource` (`App.test.jsx` ≈ 192,
  `frontend-test-standards.md` ≈ 328); Node's global `fetch` does exist under Vitest.
- Both nginx configs declare `access_log /var/log/nginx/access.log;` with no format. In
  `nginx:alpine` that file is a symlink to stdout and `error.log` to stderr, so both land in the
  container's Docker log. `goldenhour-frontend` in `docker-compose.yml` has **no `logging:`
  limit** (db and backend have 10 MB × 3); `landing/docker-compose.yml` has none either.
- **Delivery of the app's nginx/compose is automatic.** `deploy.yml` writes `nginx.conf` and
  `docker-compose.yml` from the release tag on every release (`git show "$TAG":nginx.conf`), so
  the host's hand-edited copies are overwritten by each release; steps 1 and 2 reach production
  with the next tag. The landing site ships separately via `./release-landing.sh` (its
  `nginx.conf` is baked into the image; its compose file is checked out with `landing/`).
- CI already runs `nginx -t` on the root `nginx.conf` in `nginx:1.27-alpine`
  (`ci.yml`, "nginx -t (nginx.conf syntax)"), and `frontend/src/test/nginxConf.test.js` pins its
  directives. Nothing checks `landing/nginx.conf`.
- `UserEmailService.appLink` is the only builder of `?token=` links (verification, reminder,
  reset templates all take the built URL as a Thymeleaf variable).
- `AuthGate` (`App.jsx`) reads the token once into lazy state (`urlToken()`), choosing reset by
  `pathname === '/reset'`, verification otherwise (including the legacy `/?token=`). The URL is
  cleaned with `replaceState` only on completion / back-to-login (App.jsx, RegisterPage ≈ 190,
  ResetPasswordPage 64/73).
- The PWA registers with `registerType: 'autoUpdate'` and Workbox has no route for `/api`, so a
  page `fetch` to a stream goes straight to the network.
- The app already uses the fragment: `ManageView.jsx` (`parseHash`) and `App.jsx` ≈ 749 read and
  write `#manage/<tab>`.
- Spring's own request logs use `getRequestURI()` (no query) **at INFO**, prod's level
  (`application-prod.yml` ≈ 436). At DEBUG, Spring Security's `FilterChainProxy` and
  `DispatcherServlet` print the query; the host's gitignored `application.yml` / `backend/.env`
  could set that, and Logback keeps 30 days in `/home/gregochr/goldenhour-data/logs`. No Tomcat
  access log. Code is out of scope; the host check is in §3.
- nginx's **error** log lines carry `request: "…"` (with query) and `referrer: "…"`. A 404 under
  `/assets/` (no `try_files`, `log_not_found` on by default; stale chunks after a deploy) or an
  upstream error on `/api/auth/*` writes the page's full Referer — today the one-time link.
- Cloudflare terminates TLS, so it sees full URLs, query included (edge, WAF events). Only steps 3
  and 4 take credentials out of the URL itself.
- Both images are `FROM nginx:alpine` (unpinned); CI's `nginx -t` uses `nginx:1.27-alpine`.
- Issuer tokens are `UUID.randomUUID()` (`JwtService` ≈ 102), URL- and fragment-safe.

## 2. Steps

### Step 1 — nginx logs path only, referer without query; bound the container log (PR 1)

`nginx.conf` (root) and `landing/nginx.conf`, in `http {}`:

```nginx
# Log the path, never the query: one-time links and (until step 4) stream tokens ride in it.
# $request_uri, not $uri: try_files rewrites $uri to /index.html before the log line is written.
map $request_uri $log_path {
  default           $request_uri;
  "~^(?<p>[^?]*)"   $p;
}
map $http_referer $log_referer {
  default           $http_referer;
  "~^(?<r>[^?#]*)"  $r;
}
log_format redacted '$remote_addr - $remote_user [$time_local] '
                    '"$request_method $log_path $server_protocol" $status $body_bytes_sent '
                    '"$log_referer" "$http_user_agent"';
access_log /var/log/nginx/access.log redacted;
```

(Capture names must differ between the two maps and from any other regex capture in the file;
the builder checks `nginx -t` accepts the named-capture form in 1.27, and falls back to `$1` if
not.)

- `docker-compose.yml`: `goldenhour-frontend` gets the same `logging:` block as the other two
  services (`json-file`, `10m`, `3`). `landing/docker-compose.yml`: the same for `landing`.
- `location /assets/` gets `log_not_found off;` (the routine stale-chunk 404 then writes no error
  line, so no Referer).
- **Residual, stated in the PR:** nginx's error log cannot be formatted. Until step 2 an upstream
  error on a page's `/api/auth/*` call can still write the one-time link via `referrer:`; until 4b
  an upstream error on a stream (backend restart, `proxy_read_timeout`) writes the request line,
  `?token=` included.
- Tests:
  - `nginxConf.test.js`: `access_log` names `redacted`; the `redacted` format contains none of
    `$request ` (the bare variable), `$request_uri`, `$args`, `$query_string`, `$http_referer`;
    it contains `$log_path` and `$log_referer`; both maps exist with a `[^?` capture. A sibling
    block (same file or `landingNginxConf.test.js`) pins the same for `landing/nginx.conf`.
  - A test that reads `docker-compose.yml` and `landing/docker-compose.yml` and asserts every
    service has `logging.options.max-size` (simple YAML parse if a parser is already a dev
    dependency; otherwise a block-scoped text match like `nginxConf.test.js`'s `block`).
  - **CI behaviour check** (extend the existing nginx job, gate on exit code): `docker run -d`
    `nginx:alpine` (the image that ships, not the 1.27 tag) with the checked config and a
    published port; `curl` `/verify?token=CANARY1`, `/reset?token=CANARY2`,
    `/api/status/stream?token=CANARY3` (502 is fine) and `/` with
    `Referer: http://localhost/verify?token=CANARY4`; poll `docker logs c 2>/dev/null` (stdout =
    access log; capture is asynchronous) until `/verify` appears, then assert it contains
    `"http://localhost/verify"` (the referer path survives, so a map that blanks everything fails)
    and no `CANARY`. A request for `/assets/missing.js` must add nothing to stderr
    (`log_not_found off`). Add `nginx -t` for `landing/nginx.conf` (mount `security-headers.conf`
    beside it) plus the same canary check against it.
- `changelog.d/` entry.

### Step 2 — `Referrer-Policy: strict-origin`, and strip the token before React renders (PR 2)

- `nginx.conf`: every `Referrer-Policy` line (there are three, one per location that repeats the
  set) becomes `strict-origin`. Same-origin requests then carry the origin only, so neither the
  bundle/asset requests nor the `/api/auth/*` calls repeat a one-time token. Landing is unchanged
  (no tokens there). `nginxConf.test.js` pins `strict-origin` on every response that sets the
  header and that none reads `strict-origin-when-cross-origin`. Nothing reads `Referer` (checked:
  backend and frontend; CSRF is disabled; Turnstile, Esri, Nominatim are cross-origin and get the
  origin either way). `frontend/index.html` also gets `<meta name="referrer"
  content="strict-origin">`, pinned by a test: a service worker that serves a precached
  `index.html` replays the old headers until it updates.
- New `frontend/src/utils/oneTimeLinkToken.js`: `takeLinkToken()` reads the token from the URL
  **once per page load** (module-level memo, so StrictMode's double initialiser and a remount get
  the same value), then calls `history.replaceState(history.state, '', <same path, query without
  token, no token fragment>)`. It is called from `main.jsx` **before** `createRoot(...).render`,
  so the URL is clean before any component effect runs (a child's effect runs before
  `AuthGate`'s, so an effect in the gate would be too late for RegisterPage's verify call).
  `AuthGate`'s lazy initialisers call `takeLinkToken()` themselves instead of `urlToken()` (it is
  idempotent through the memo), because `App.test.jsx` renders `<App />` without `main.jsx`; the
  reset/verify split by pathname is unchanged. Export a test-only reset of the memo, called in the
  gate tests' `beforeEach`. History is touched **only when a token was found**, and only the
  `token=` pair is removed, as text (never round-trip the fragment through `URLSearchParams`, which
  would turn `#manage/metrics` into `#manage%2Fmetrics=`); an emptied query or fragment drops its
  `?`/`#`.
- Behaviour change, deliberate and in the PR body: a reload after the link has opened no longer
  re-reads the token (it is gone from the address). A reset reader re-clicks the email link (still
  valid until used). For verification this matches today: a reload re-ran `verify-email`, which
  answers "already used".
- Tests (Vitest): the URL has no token after `takeLinkToken()` (query and, after step 3, hash);
  path is kept; other query params are kept; a second call returns the same token without
  touching history; a URL with no token (e.g. `#manage/metrics`) leaves the URL and history
  untouched; `/reset?token=` still opens the reset page and `/verify?token=` the verify step.
  `App.test.jsx` ≈ 1780 asserts `location.search === '?token=abc'` survives re-renders: that
  assertion flips, deliberately (the address is clean at first render; the token still survives
  re-renders in state). `ResetPasswordPage.test.jsx` seeds `?token=`; check it. Existing
  completion-time `replaceState` calls stay (harmless).
- `changelog.d/` entry.

### Step 3 — one-time tokens in the fragment, both forms accepted (PR 3)

**Built** (`fix/one-time-token-fragment`): `appLink` mails `#token=`; the `?token=` read stays for links already in inboxes.

- `UserEmailService.appLink`: `base + path + "#token=" + rawToken`. The token is URL-safe
  (builder confirms the issuers' alphabet; if not, encode it). `UserEmailServiceTest` (and any
  test asserting `?token=` in a sent mail) updated to the fragment form.
- `takeLinkToken()` reads `#token=` first (`new URLSearchParams(location.hash.slice(1))`), then
  falls back to `?token=` — links mailed before the release keep working for their lifetime (24 h
  verify, 1 h reset). The strip removes whichever form was present.
- Fragments never reach our server or a `Referer`. They do still reach URL-rewriting scanners and
  click trackers (Safe Links, ESP tracking), which encode the whole href into their own URL, and
  they stay in the browser's own history list, as the query did; `replaceState` cleans the session
  entry only.
- The visible example URL in `verification-email.html`, `verification-reminder-email.html` and
  `password-reset-link-email.html` is the same variable, so it changes with `appLink`; the builder
  checks no template or test hard-codes `?token=`.
- Tests: hash form on `/verify` and `/reset`; query form still works; both present → hash wins;
  `#token=` with other fragment keys preserves them.
- The `?token=` fallback is **not** removed in this series: removing it in the same release would
  break links in flight. Follow-up for the owner, after the release that ships step 3 has been out
  > 24 h.
- `changelog.d/` entry.

### Step 4 — streams authenticate with the `Authorization` header (PR 4a), then the query branch goes (PR 4b)

**Choice: fetch-based SSE inside `createEventSource`, no new dependency.** Against a
`POST /api/stream-ticket`: no server-side ticket store, no extra round trip per reconnect, no
credential of any kind in a URL, and the client can see a 401. Against
`@microsoft/fetch-event-source`: its own retry and visibility behaviour would fight the contract
`createEventSource` already pins, and it has had no release since 2021. The parser is small and
specified (WHATWG "event stream interpretation").

PR 4a (frontend only; backend still accepts both, so any release order is safe):

- `createEventSource` keeps its signature and observable contract. `params` stay as query
  params; `token` is no longer one. Connect = `fetch(url, { headers: { Accept:
  'text/event-stream', Authorization: 'Bearer ' + token }, cache: 'no-store', signal })`, header
  omitted when there is no token. An `AbortController` per connection replaces `source.close()`
  (on reconnect, `closeOn`, cleanup).
- State emulates EventSource's `readyState` for `handleVisible`: CONNECTING → OPEN on an OK
  response; CLOSED on non-2xx, a body that ends, or a fetch rejection. Every transition to
  CLOSED that was not a deliberate close calls `onError` and `scheduleReconnect(5 s)` — the same
  as today's CLOSED path. (Native EventSource reconnects by itself after EOF; ours now does it
  through the same timer, so there is one retry path.) `onError` receives `{ status }` when there
  was a response; existing callers ignore the argument. An `AbortError` from our own abort never
  calls `onError`.
- Parser: `TextDecoder` with `{ stream: true }`; lines split on CRLF, CR or LF, with a CR at a
  chunk end held until the next chunk; blank line dispatches if the data buffer is non-empty
  (default event name `message`), with the trailing LF removed; `:` lines are comments (Spring
  sends none today but heartbeats may); `field: value` strips one leading space; `data` appends
  value + LF; `event` sets the name; `id` and `retry` are ignored. Handlers and the JSON parse /
  `closeOn` behaviour are unchanged.
- `connect()` sets the state to CONNECTING **synchronously**, before `await fetch`, so a
  `visibilitychange` and a `focus` arriving together open one stream. Each connection has a
  generation id; every async continuation (response, `read()` chunk, rejection, EOF) checks it, so
  a superseded stream never dispatches, calls `onError` or schedules a reconnect. After a
  `closeOn` event, the rest of the same chunk is not dispatched (native `close()` stops delivery).
- Native failure rules kept: a 2xx whose `Content-Type` is not `text/event-stream` (an HTML error
  or challenge page) is a failure; a 204 stops without reconnecting. The reconnect after EOF moves
  from the browser's ~3 s to 5 s; `useHealthStatus`'s `DOWN_GRACE_MS` (12 s) still covers it.
  Comments that become false are corrected (`useHealthStatus.js` ≈ 82 "EventSource cannot see a
  401", `useRunNotifications.js` ≈ 20 "EventSource will auto-reconnect").
- Test tripwire: `src/test/setup.js` makes `globalThis.fetch` throw a named error unless a test
  installs its own mock, so an accidental real stream still fails loudly instead of looping on a
  silent 5 s retry; `frontend-test-standards.md`'s note is updated. (The builder checks no existing
  test relies on the real `fetch`.)
- Transport parity, checked, stated in the PR: no `proxy_buffering` either way on `/api/`;
  `text/event-stream` is excluded from nginx `gzip_types` and from `server.compression`; CSP
  `connect-src 'self'`, HTTP/1.1 connection limits, Cloudflare and Workbox (only `/assets/` is
  routed) treat fetch and EventSource the same; `VITE_API_BASE_URL` is unset, so the header causes
  no CORS preflight.
- Tests (only `createEventSource.test.js` is rewritten, against a mocked `fetch` returning a
  `ReadableStream`; comments elsewhere that describe EventSource are updated): no `token=` in the
  requested URL; the header carries the freshest `getToken()` on each
  reconnect; events split across chunk boundaries (including mid-CRLF and mid-multibyte UTF-8);
  multi-line `data`; comments ignored; `closeOn` aborts and does not reconnect; 401 → `onError`
  with status 401 and a 5 s reconnect; EOF → reconnect; cleanup aborts and clears the timer and
  listeners; `reconnectOnVisible` reconnects only a CLOSED stream; focus + visibility together →
  one fetch; a late chunk from an aborted stream is not dispatched; two events in one chunk where
  the first is `closeOn` → the second is not dispatched; non-`text/event-stream` 2xx → `onError`;
  204 → no reconnect.
- Verify in the browser against the local backend (port 8083): the status stream and run
  notifications connect, the request has an `Authorization` header and no query token, events
  arrive, and a backend restart reconnects. Say plainly what was seen and what was only tested.

PR 4b (backend, after 4a is merged):

- `JwtAuthenticationFilter`: delete the query branch of `extractToken` and `isSsePath`; correct
  the `shouldNotFilterAsyncDispatch` Javadoc (it no longer depends on the query param; keep the
  skip). `StatusController.extractLoginTime`: delete the `getParameter("token")` fallback.
- Tests: `?token=<valid>` on each of the three SSE paths no longer authenticates (unauthenticated
  → 401 through the real filter chain, `@WebMvcTest` or the existing filter test); the header on
  the same paths does; `StatusControllerTest`'s query-param case becomes a header case.
- Release note in the PR: a tab still running a bundle from before 4a loses its streams (status
  shows DOWN) until it reloads; `autoUpdate` swaps the bundle on the next load. If 4a and 4b ship
  in the same release that is the whole cost, but each such tab keeps retrying every 5 s against a
  401 (an INFO line in `SecurityConfig` plus an nginx access line per retry) until it is reloaded;
  shipping 4b one release later avoids it.
- After 4b, the error-log residual of step 1 is gone too: no credential is in any request line.

### Step 5 — Privacy Policy wording (PR 5, landing)

`landing/privacy.html`, "Server logs": the web server's access log records the address requested
**without its query string** and the referring page without its query; one-time links are not in
it, and the sign-in token no longer travels in any address. Keep the IP, time, retention-on-host
wording, and say plainly that Cloudflare, which carries the traffic, sees full addresses. Based on
`main` at the time (the editorial branch also edits this file). Owner runs `./release-landing.sh`
for it **only after** the app release carrying 4b is live (4b may ship a release after 4a), or the
policy claims more than production does.

### Reference docs, in the PR whose behaviour they describe

`docs/engineering/reference/platform.md` ≈ 6 (link form, step 3),
`docs/engineering/reference/api-endpoints.md` ≈ 16–17 (stream auth, 4b),
`docs/app-store-privacy.md` ≈ 78–80 (steps 3/4b), and the access-log line in this plan's §3.

## 3. Owner host steps (not done by a session)

After the first release that carries step 1 (and again after 4b):

1. Confirm the deployed config, for `goldenhour-frontend` and (after the landing release)
   `photocast-landing`: `docker exec <name> nginx -T | grep -n 'log_format redacted\|access_log'`
   and `docker inspect -f '{{.HostConfig.LogConfig}}' <name>` (expect `json-file`, `max-size:10m`).
2. Purge what the old format wrote. The release recreates `goldenhour-frontend` (new image), which
   deletes its old Docker log; check none survives: find the root with
   `docker info -f '{{.DockerRootDir}}'`, then `sudo ls -la <root>/containers/*/` for a
   `*-json.log` belonging to no running container, and any rotated copies. If the host's
   `docker-compose.yml` ever mounted a log volume for nginx, check that path too.
3. The backend's own logs: `grep -rl 'token=' /home/gregochr/goldenhour-data/logs`, and check the
   host's `backend/src/main/resources/application*.yml` and `backend/.env` for a `logging.level`
   override to DEBUG (Spring Security and `DispatcherServlet` print the query at DEBUG).
4. Landing: `./release-landing.sh` after step 1 merges (log format + compose limit), and again for
   step 5 once the app release is live. The landing recreate drops its old log the same way.
5. Look for other copies: `/etc/docker/daemon.json` (a log driver shipping logs elsewhere?),
   `journalctl` if the daemon uses `journald`, any backup of `/var/lib/docker`, and whether
   `cloudflared` runs with `--loglevel debug` (it logs request URLs only at debug).
6. Treat any access token seen in an old log as expired after 24 h and any reset link after 1 h.
   A verification link in an old log is dead once its account has set a password; an account still
   pending from before the release can be re-sent a link (the earlier one is invalidated).

## 4. Out of scope, follow-ups

- Remove the `?token=` fallback (step 3) in a release after step 3 has been live > 24 h.
- Audit §1.3 #5: rotate the token at verify (or bind `set-password` to a short window after
  `verify-email`); revoke access tokens at logout.
- Reload after verification shows "already used" and the reader cannot reach set-password without
  a new link (pre-existing; unchanged by step 2).

## 5. Review gates per PR

Step 1: CI's nginx job is the proof (canary check). Step 2 and 4a touch the UI and auth: the full
UI review cadence (adversarial review of the diff before landing, browser check on 8083).
Step 3 and 4b touch auth on the backend: two reviewers (security, tests) before merge.

## 6. Plan review (2026-10-25)

Two adversarial reviewers (security/ops; frontend/SSE) checked every claim against the code. Folded
in: the error log's `referrer:` field and `log_not_found off`; the DEBUG-level Spring query logging
and the backend log host check; `extractLoginTime` (not `extractIssuedAt`); the existing `#manage/`
fragment, so the strip only removes the `token=` pair as text and only when a token is present;
`AuthGate` calling the memoised reader itself (tests render `<App />` without `main.jsx`); the
`App.test.jsx` assertion that flips; the fetch tripwire in `setup.js`; synchronous CONNECTING,
generation ids and `closeOn` mid-chunk; native failure rules (content type, 204); canary
mechanics and image; Cloudflare in the residuals and policy wording; step 5 gated on 4b; the
reference docs; the caller tests mock the module (no shared helper needed).
