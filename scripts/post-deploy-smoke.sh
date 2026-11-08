#!/bin/bash
# ============================================================================
# Post-deploy smoke check — "does the release BEHAVE", not just "is it up".
#
# prod-health.sh --deploy says the containers are healthy and the app answers
# from outside; it says so itself that it "says nothing about whether the release
# behaves correctly". This is the probe that does, run on the production host
# straight after it. Every probe goes through the PUBLIC URL (APP_URL_BASE), so it
# covers Cloudflare, the tunnel, nginx and the backend together.
# (docs/engineering/engineering-gaps-plan.md, Phase 4.)
#
# EXIT CODES (the contract deploy.yml and auto-rollback.sh branch on; only 1 can
# ever cause a rollback, and only when the repository variable SMOKE_ENFORCE=true):
#   0  every probe passed
#   1  the release is broken: a probe got a real answer from the origin that is
#      wrong (no CSP, health not UP, 204 briefing, a missing asset, 5xx, ...)
#   3  inconclusive or infrastructure: ANY Cloudflare edge response (a cf-mitigated
#      or cf-error-details header, "error code: 1xxx", "Attention Required"), a
#      52x/530, no response at all or a curl error (a truncated body included), a
#      missing tool, or the deadline ran out before a probe could start.
#      NEVER a reason to roll back.
#   4  the smoke check itself is misconfigured: the smoke login answered 400, 401,
#      403 or 429, the account still owes a first-login password change, the
#      password is unset while SMOKE_LOGIN_REQUIRED=true, the password holds
#      characters the SSH forwarding can mangle, or bad arguments.
#      NEVER a reason to roll back.
# When probes disagree the order is 1, then 4, then 3: a real break is never hidden
# behind a misconfiguration or an edge answer elsewhere. (A login that 5xx'd and
# then, on retry, was refused is itself a 1: the release broke login.)
#
# THE PROBES
#   1. shell          GET /?smoke=<n>: 200, text/html, a Content-Security-Policy
#                     header, a /assets/index-*.js reference, EVERY referenced
#                     /assets/*.js|css answers 200, and the index bundle is cached
#                     hard (Cache-Control immutable, OR max-age of at least a day,
#                     since Cloudflare may rewrite the header; which one is printed)
#   2. backend health GET /actuator/health/container: 200 and "status":"UP"
#                     (--rollback-mode: falls back to /actuator/health on a 404,
#                     for images from before the db-only container group)
#   3. auth boundary  GET /api/briefing with no token: 401
#   4. running build  GET /actuator/info: git.commit.id must match the commit of
#                     $RELEASE_VERSION. Skipped in --rollback-mode; a missing field
#                     or an unreadable tag is a WARNING, never a failure
#   5. logged in      only when SMOKE_DEPLOY_PASSWORD is set: POST /api/auth/login
#                     (no Turnstile token; the gate's bounded fallback admits it),
#                     GET /api/briefing (200; FAIL on a 204, on no days, on no slot
#                     carrying locationName (regioned or unregioned), or on any
#                     "gated" marker, which a PRO reader must never see; zero
#                     served ratings is only a WARNING, a quiet day is legitimate),
#                     GET /api/user/settings (200), then POST /api/auth/logout
#                     (also from an EXIT trap, so no refresh tokens pile up)
#
# When the password is unset the logged-in probes are SKIPPED WITH A WARNING
# (::warning:: and a $GITHUB_STEP_SUMMARY line), never failed, unless
# SMOKE_LOGIN_REQUIRED=true turns the skip into exit 4.
#
# RETRIES. Each probe retries for up to SMOKE_RETRY_SECONDS because a fresh backend
# warms up, EXCEPT where retrying would be harmful or pointless: a Cloudflare
# challenge, and a login 400/401/403/429 (never retry a login that was refused;
# that is how an account gets locked out). The whole script stops starting new
# probes at SMOKE_DEADLINE_SECONDS.
#
# SECRETS. Nothing secret is ever printed or put in argv, and there is no `set -x`.
# The login body is built by jq from the environment (not from argv) into a mode-600
# file that is deleted as soon as it is sent; the bearer header lives in a mode-600
# file passed with `curl -H @file`; the working directory is mode 700 and removed on exit.
#
# Needs only bash, curl (7.55+ for -H @file), jq and, for probe 4, git.
#
# Usage: ./post-deploy-smoke.sh [--rollback-mode]
#   APP_URL_BASE            default https://app.photocast.online
#   SMOKE_DEPLOY_USERNAME   default smoke-deploy
#   SMOKE_DEPLOY_PASSWORD   the smoke account's password (unset: login probes skipped)
#   SMOKE_LOGIN_REQUIRED    "true": an unset password is exit 4 instead of a skip
#   SMOKE_RETRY_SECONDS     per-probe retry window, default 60
#   SMOKE_RETRY_INTERVAL    seconds between attempts, default 5
#   SMOKE_DEADLINE_SECONDS  overall deadline, default 120
#   RELEASE_VERSION         the tag being deployed (probe 4)
#   SMOKE_GIT_DIR           repository holding that tag (default $COMPOSE_DIR, else ~/goldenhour)
# ============================================================================

# The check_* functions are called by name through run_probe, which ShellCheck cannot follow.
# shellcheck disable=SC2329
set -uo pipefail
umask 077

ROLLBACK_MODE=0
for arg in "$@"; do
    case "$arg" in
        --rollback-mode) ROLLBACK_MODE=1 ;;
        -h|--help) sed -n '2,/^# =====/p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "Usage: $0 [--rollback-mode]" >&2; exit 4 ;;
    esac
done

BASE="${APP_URL_BASE:-https://app.photocast.online}"
BASE="${BASE%/}"
UA="photocast-deploy-smoke/1"
RETRY_SECONDS="${SMOKE_RETRY_SECONDS:-60}"
RETRY_INTERVAL="${SMOKE_RETRY_INTERVAL:-5}"
DEADLINE_SECONDS="${SMOKE_DEADLINE_SECONDS:-120}"
# jq reads both from the environment, so neither reaches an argv.
export SMOKE_DEPLOY_USERNAME="${SMOKE_DEPLOY_USERNAME:-smoke-deploy}"
PASSWORD_SET=0
[ -n "${SMOKE_DEPLOY_PASSWORD:-}" ] && PASSWORD_SET=1
export SMOKE_DEPLOY_PASSWORD="${SMOKE_DEPLOY_PASSWORD:-}"
LOGIN_REQUIRED="${SMOKE_LOGIN_REQUIRED:-}"
RELEASE_VERSION="${RELEASE_VERSION:-}"
GIT_DIR_HINT="${SMOKE_GIT_DIR:-${COMPOSE_DIR:-${HOME}/goldenhour}}"

for numeric in "$RETRY_SECONDS" "$RETRY_INTERVAL" "$DEADLINE_SECONDS"; do
    case "$numeric" in
        ''|*[!0-9]*)
            echo "post-deploy-smoke: SMOKE_RETRY_SECONDS, SMOKE_RETRY_INTERVAL and SMOKE_DEADLINE_SECONDS must be whole numbers." >&2
            exit 4 ;;
    esac
done

# appleboy/ssh-action forwards `envs:` as shell exports, so a quote, backslash, dollar sign or backtick in the
# secret can arrive altered. Refuse the value before it is used; it is never printed.
#
# SMOKE_PASSWORD_CHARSET_CHECK=off exists for ONE caller: the Playwright project, which runs this script directly
# (no SSH forwarding) against the seeded `pro` account whose fixed password contains a '#'. Never set it in deploy.
if [ "$PASSWORD_SET" -eq 1 ] && [ "${SMOKE_PASSWORD_CHARSET_CHECK:-on}" != off ]; then
    case "$SMOKE_DEPLOY_PASSWORD" in
        *[!A-Za-z0-9._-]*)
            echo "FAIL smoke password: SMOKE_DEPLOY_PASSWORD contains characters the SSH forwarding can mangle (use only A-Z a-z 0-9 . _ -) [smoke misconfigured]"
            [ -z "${GITHUB_ACTIONS:-}" ] || echo "::error::post-deploy-smoke: SMOKE_DEPLOY_PASSWORD contains characters the SSH forwarding can mangle (use only A-Z a-z 0-9 . _ -)"
            exit 4 ;;
    esac
fi

for tool in curl jq; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "post-deploy-smoke: ${tool} is not installed on this host — cannot run (inconclusive)." >&2
        exit 3
    }
done

MIN_PROBE_SECONDS=10
now() { date +%s; }
START_AT="$(now)"
DEADLINE_AT=$((START_AT + DEADLINE_SECONDS))

WORK="$(mktemp -d)" || exit 3
HDR="${WORK}/headers"
BODY="${WORK}/body"
ERRF="${WORK}/curl-err"
AUTHFILE="${WORK}/auth-header"
LOGIN_BODY="${WORK}/login-body"
REQBODY="${WORK}/request-body"
: >"$HDR"; : >"$BODY"; : >"$ERRF"

PROBES=0
FAILED=0
HAS1=0
HAS3=0
HAS4=0
SKIPPED_DEADLINE=0
LOGIN_ATTEMPTS=0
LOGIN_SAW_5XX=0
CURL_RC=0
ABORT=0
LOGGED_OUT=0
LOGIN_OK=0
HTTP_CODE=000
R_RC=0
R_MSG=""
R_FINAL=0
R_WARN=""
FORCE_MAXT=""

cleanup() {
    do_logout quiet
    rm -rf "$WORK"
}
trap cleanup EXIT

# --- Output ---------------------------------------------------------------------
annotate() {
    if [ -n "${GITHUB_ACTIONS:-}" ]; then
        printf '::%s::%s\n' "$1" "$2"
    fi
}

report_probe() {
    local name="$1" rc="$2" msg="$3"
    PROBES=$((PROBES + 1))
    if [ "$rc" -eq 0 ]; then
        printf 'ok  %s: %s\n' "$name" "$msg"
        return
    fi
    FAILED=$((FAILED + 1))
    local label
    case "$rc" in
        1) label="release broken"; HAS1=1 ;;
        4) label="smoke misconfigured"; HAS4=1 ;;
        *) label="inconclusive"; HAS3=1 ;;
    esac
    printf 'FAIL %s: %s [%s]\n' "$name" "$msg" "$label"
    annotate error "post-deploy-smoke ${name}: ${msg} [${label}]"
}

warn() {
    printf 'warn %s: %s\n' "$1" "$2"
    annotate warning "post-deploy-smoke ${1}: ${2}"
}

# --- HTTP -------------------------------------------------------------------------
# http METHOD PATH [curl args...]. Sets HTTP_CODE (000 when there was no response),
# leaves the response headers in $HDR and the body in $BODY. Never call it in a pipeline or
# $(...): it sets globals, and a subshell would lose them.
http() {
    local method="$1" path="$2"
    shift 2
    # Never clamped below 10 s by the overall deadline: a request cut short would look like a broken release.
    # run_probe stops STARTING probes when less than MIN_PROBE_SECONDS remain instead.
    local mt=20
    [ -n "$FORCE_MAXT" ] && mt="$FORCE_MAXT"
    : >"$HDR"; : >"$BODY"; : >"$ERRF"
    local args=(-sS --compressed -o "$BODY" -D "$HDR" -w '%{http_code}' --max-time "$mt" -A "$UA")
    [ "$method" = GET ] || args+=(-X "$method")
    CURL_RC=0
    HTTP_CODE="$(curl "${args[@]}" "$@" "${BASE}${path}" 2>"$ERRF")" || CURL_RC=$?
    case "$HTTP_CODE" in
        [0-9][0-9][0-9]) ;;
        *) HTTP_CODE=000 ;;
    esac
    # Any curl failure (a timeout, a reset, a truncated or undecodable body) is transport, whatever status line
    # arrived first: a half-read page says nothing about the release.
    if [ "$CURL_RC" -ne 0 ]; then
        HTTP_CODE=000
    fi
}

# The value of a response header from the last response (case-insensitive).
hdr() { grep -i "^$1:" "$HDR" | tail -1 | sed 's/^[^:]*: *//' | tr -d '\r'; }

# A response that came from Cloudflare's edge rather than our origin: a challenge or mitigation header of any
# value, an error-details header, or (only on an error status, so a JS bundle that happens to contain the
# text cannot trip it) one of its error pages. Never a verdict on the release.
is_edge() {
    grep -qiE '^(cf-mitigated|cf-error-details):' "$HDR" && return 0
    case "$HTTP_CODE" in
        2??|3??|000) return 1 ;;
    esac
    grep -qiE 'error code: 1[0-9]{3}|Attention Required|cf-chl|challenge-platform|Just a moment' "$BODY" && return 0
    return 1
}

# Transport-level answers that say nothing about the release. Returns 0 (and sets the
# R_ globals) when the last response is one of them, so a probe does `transport_problem && return 0`.
transport_problem() {
    if is_edge; then
        R_RC=3; R_FINAL=1; ABORT=1
        R_MSG="${HTTP_CODE} Cloudflare edge response (cf-mitigated, cf-error-details or an edge error page) — the edge, not the release; inconclusive"
        return 0
    fi
    case "$HTTP_CODE" in
        000)
            R_RC=3
            R_MSG="no response from ${BASE} (curl exit ${CURL_RC}: $(head -c 160 "$ERRF" | tr -d '\r\n'))"
            return 0 ;;
        52[0-9]|530)
            R_RC=3
            R_MSG="Cloudflare ${HTTP_CODE}: the tunnel or origin is unreachable — infrastructure"
            return 0 ;;
    esac
    return 1
}

# Retry wrapper: run_probe NAME FUNCTION. The function sets R_RC (0, 1, 3, 4), R_MSG, R_FINAL
# (do not retry) and R_WARN; the LAST attempt decides.
run_probe() {
    local name="$1" fn="$2" limit left
    # A probe needs a full request's worth of time (10 s), so none is started closer to the deadline than that.
    if [ "$(now)" -ge $((DEADLINE_AT - MIN_PROBE_SECONDS)) ]; then
        SKIPPED_DEADLINE=1
        report_probe "$name" 3 "not run: less than ${MIN_PROBE_SECONDS}s left of the ${DEADLINE_SECONDS}s overall deadline"
        return
    fi
    limit=$(($(now) + RETRY_SECONDS))
    [ "$limit" -gt "$DEADLINE_AT" ] && limit="$DEADLINE_AT"
    while :; do
        R_RC=0; R_MSG=""; R_FINAL=0; R_WARN=""
        "$fn"
        [ "$R_RC" -eq 0 ] && break
        [ "$R_FINAL" -eq 1 ] && break
        left=$((limit - $(now)))
        [ "$left" -le 0 ] && break
        [ "$left" -gt "$RETRY_INTERVAL" ] && left="$RETRY_INTERVAL"
        sleep "$left"
    done
    report_probe "$name" "$R_RC" "$R_MSG"
    [ -z "$R_WARN" ] || warn "$name" "$R_WARN"
}

# --- 1. The shell -----------------------------------------------------------------
CACHE_DETAIL=""
check_shell() {
    http GET "/?smoke=$(now)-$$" -H 'Accept: text/html'
    transport_problem && return 0
    if [ "$HTTP_CODE" != 200 ]; then
        R_RC=1; R_MSG="GET / -> ${HTTP_CODE}, not 200"; return 0
    fi
    local ctype
    ctype="$(hdr content-type)"
    case "$ctype" in
        *text/html*) ;;
        *) R_RC=1; R_MSG="GET / content-type is '${ctype}', not text/html"; return 0 ;;
    esac
    if [ -z "$(hdr content-security-policy)" ]; then
        R_RC=1; R_MSG="GET / carries no Content-Security-Policy header (nginx.conf not applied?)"; return 0
    fi
    local assets index asset
    assets="$(grep -oE '/assets/[A-Za-z0-9._~-]+\.(js|css)' "$BODY" | sort -u)"
    index="$(printf '%s\n' "$assets" | grep -E '^/assets/index-[^/]+\.js$' | head -1)"
    if [ -z "$index" ]; then
        R_RC=1; R_MSG="the shell references no /assets/index-*.js"; return 0
    fi
    local count=0
    while IFS= read -r asset; do
        [ -n "$asset" ] || continue
        http GET "$asset"
        transport_problem && return 0
        if [ "$HTTP_CODE" != 200 ]; then
            R_RC=1; R_MSG="${asset} -> ${HTTP_CODE}, not 200 (broken frontend image?)"; return 0
        fi
        count=$((count + 1))
        if [ "$asset" = "$index" ]; then
            local cache maxage
            cache="$(grep -i '^cache-control:' "$HDR" | tr -d '\r' | tr '[:upper:]' '[:lower:]')"
            maxage="$(printf '%s\n' "$cache" | grep -oE 'max-age=[0-9]+' | head -1 | cut -d= -f2)"
            if printf '%s\n' "$cache" | grep -q 'immutable'; then
                CACHE_DETAIL="immutable"
            elif [ -n "$maxage" ] && [ "$maxage" -ge 86400 ]; then
                CACHE_DETAIL="max-age=${maxage}"
            else
                R_RC=1; R_MSG="${asset} Cache-Control is '${cache:-absent}': neither immutable nor max-age of a day or more"
                return 0
            fi
        fi
    done <<<"$assets"
    R_MSG="GET / 200 html with CSP; ${count} asset(s) answer 200; ${index##*/} cached ${CACHE_DETAIL}"
}

# --- 2. Backend health ------------------------------------------------------------
check_health() {
    local path=/actuator/health/container
    http GET "$path"
    transport_problem && return 0
    if [ "$HTTP_CODE" = 404 ] && [ "$ROLLBACK_MODE" = 1 ]; then
        path=/actuator/health
        http GET "$path"
        transport_problem && return 0
        # The aggregate includes the third-party probes (Open-Meteo, tides, Anthropic), so DOWN here says
        # nothing about the old build: inconclusive, never a failure.
        if [ "$HTTP_CODE" != 200 ] || ! jq -e '.status == "UP"' "$BODY" >/dev/null 2>&1; then
            R_RC=3; R_MSG="GET ${path} -> ${HTTP_CODE} (the aggregate, used because the previous image has no container group; it includes third-party probes) — inconclusive"
            return 0
        fi
    fi
    if [ "$HTTP_CODE" != 200 ]; then
        R_RC=1; R_MSG="GET ${path} -> ${HTTP_CODE}, not 200"; return 0
    fi
    if ! jq -e '.status == "UP"' "$BODY" >/dev/null 2>&1; then
        R_RC=1; R_MSG="GET ${path} answered 200 but its status is not UP"; return 0
    fi
    R_MSG="GET ${path} -> 200 UP"
}

# --- 3. Auth boundary ---------------------------------------------------------------
check_auth_boundary() {
    http GET /api/briefing
    transport_problem && return 0
    if [ "$HTTP_CODE" != 401 ]; then
        R_RC=1; R_MSG="GET /api/briefing with no token -> ${HTTP_CODE}, not 401"; return 0
    fi
    R_MSG="GET /api/briefing with no token -> 401"
}

# --- 4. The new build is the one running ----------------------------------------------
check_running_build() {
    local expected id
    expected="$(git -C "$GIT_DIR_HINT" rev-list -n1 "$RELEASE_VERSION" 2>/dev/null || true)"
    if [ -z "$expected" ]; then
        R_WARN="cannot resolve ${RELEASE_VERSION} in ${GIT_DIR_HINT}; running-build check skipped"
        R_MSG="skipped (tag not resolvable)"; return 0
    fi
    http GET /actuator/info
    transport_problem && return 0
    id=""
    if [ "$HTTP_CODE" = 200 ]; then
        id="$(jq -r '(.git.commit.id // empty) | if type == "object" then (.full // .abbrev // empty) else . end' "$BODY" 2>/dev/null || true)"
    fi
    if [ -z "$id" ] || [ "${#id}" -lt 7 ]; then
        R_WARN="/actuator/info (${HTTP_CODE}) carries no git.commit.id; cannot confirm the new build is the one running"
        R_MSG="skipped (no git.commit.id served)"; return 0
    fi
    case "$expected" in
        "$id"*) R_MSG="/actuator/info git.commit.id ${id} matches ${RELEASE_VERSION}" ;;
        *)
            case "$id" in
                "$expected"*) R_MSG="/actuator/info git.commit.id ${id} matches ${RELEASE_VERSION}" ;;
                *) R_RC=1; R_MSG="/actuator/info git.commit.id is ${id}, but ${RELEASE_VERSION} is ${expected} — the old build is still running" ;;
            esac ;;
    esac
}

# --- 5. Logged in -------------------------------------------------------------------
# The refusals that mean OUR setup is wrong, not the release: exit 4, never retried.
password_change_required_body() {
    grep -q 'PASSWORD_CHANGE_REQUIRED' "$BODY" 2>/dev/null
}

check_login() {
    # A file, not a pipe: `jq | http` would run http in a subshell and lose HTTP_CODE. The file is
    # mode 600 in a mode 700 directory and is removed the moment the request has been sent.
    jq -n '{username: $ENV.SMOKE_DEPLOY_USERNAME, password: $ENV.SMOKE_DEPLOY_PASSWORD}' >"$REQBODY"
    LOGIN_ATTEMPTS=$((LOGIN_ATTEMPTS + 1))
    http POST /api/auth/login -H 'Content-Type: application/json' --data "@${REQBODY}"
    rm -f "$REQBODY"
    # At most 3 attempts however long the retry window is: each one counts against the account's failed-sign-in
    # budget, and the limiter is what a hammering retry loop would trip.
    [ "$LOGIN_ATTEMPTS" -lt 3 ] || R_FINAL=1
    transport_problem && return 0
    case "$HTTP_CODE" in
        200)
            local token
            token="$(jq -r '.accessToken // empty' "$BODY" 2>/dev/null || true)"
            if [ -z "$token" ]; then
                R_RC=1; R_MSG="POST /api/auth/login -> 200 but with no accessToken"; return 0
            fi
            cp "$BODY" "$LOGIN_BODY"
            printf 'Authorization: Bearer %s\n' "$token" >"$AUTHFILE"
            token=""
            if [ "$(jq -r '.passwordChangeRequired // false' "$LOGIN_BODY" 2>/dev/null)" = true ]; then
                R_RC=4; R_FINAL=1
                R_MSG="login succeeded but ${SMOKE_DEPLOY_USERNAME} still has passwordChangeRequired=true: sign in once in a browser, change the temporary password, then update SMOKE_DEPLOY_PASSWORD"
                return 0
            fi
            LOGIN_OK=1
            R_MSG="POST /api/auth/login -> 200" ;;
        400|401|403|429)
            R_FINAL=1
            if [ "$LOGIN_SAW_5XX" -eq 1 ]; then
                # Login already failed with a 5xx on this run, so what looks like a refusal now is the release
                # breaking login (a half-started backend rejecting everyone), not a misconfigured account.
                R_RC=1; R_MSG="POST /api/auth/login -> ${HTTP_CODE} after an earlier 5xx on this run: the release broke login"
                return 0
            fi
            R_RC=4
            case "$HTTP_CODE" in
                401) R_MSG="POST /api/auth/login -> 401: SMOKE_DEPLOY_PASSWORD is wrong, or ${SMOKE_DEPLOY_USERNAME} does not exist or is disabled" ;;
                429) R_MSG="POST /api/auth/login -> 429: the login rate limit is spent (not retried)" ;;
                *)   R_MSG="POST /api/auth/login -> ${HTTP_CODE}: the smoke login was refused (not retried)" ;;
            esac ;;
        5??) LOGIN_SAW_5XX=1; R_RC=1; R_MSG="POST /api/auth/login -> ${HTTP_CODE}" ;;
        *)   R_RC=1; R_MSG="POST /api/auth/login -> ${HTTP_CODE}, not 200" ;;
    esac
}

# Shared handling for the two authenticated GETs below. Returns 0 when it has set R_ for a
# failure (the caller returns), 1 to go on and read a 200.
authed_get_failed() {
    local path="$1"
    http GET "$path" -H "@${AUTHFILE}"
    transport_problem && return 0
    if [ "$HTTP_CODE" = 403 ] && password_change_required_body; then
        R_RC=4; R_FINAL=1
        R_MSG="GET ${path} -> 403 PASSWORD_CHANGE_REQUIRED: ${SMOKE_DEPLOY_USERNAME} must change its temporary password"
        return 0
    fi
    if [ "$HTTP_CODE" = 204 ] && [ "$path" = /api/briefing ]; then
        R_RC=1; R_MSG="GET /api/briefing -> 204: the cached briefing is missing or failed to deserialise"
        return 0
    fi
    if [ "$HTTP_CODE" != 200 ]; then
        R_RC=1; R_MSG="GET ${path} with a fresh token -> ${HTTP_CODE}, not 200"
        return 0
    fi
    return 1
}

check_briefing() {
    authed_get_failed /api/briefing && return 0
    if ! jq -e 'type == "object" and has("generatedAt") and (.days | type == "array")' "$BODY" >/dev/null 2>&1; then
        R_RC=1; R_MSG="GET /api/briefing -> 200 but without the top-level keys generatedAt and days"; return 0
    fi
    # What proves the briefing was built and served is STRUCTURE, not scores: a stand-down or a stability skip can
    # legitimately leave every rating null, and rolling back a good release on a quiet day would be worse than
    # not checking. So the FAIL signals are: no days, no slot (regioned or unregioned) carrying a locationName,
    # and (not against the previous build, which may predate the marker) any "gated" marker, which a PRO
    # reader must never see. Zero ratings is a warning.
    local days slots gated ratings
    days="$(jq '.days | length' "$BODY" 2>/dev/null || echo 0)"
    slots="$(jq '[.days[]?.eventSummaries[]? | ((.regions[]?.slots[]?), (.unregioned[]?)) | select(.locationName != null)] | length' "$BODY" 2>/dev/null || echo 0)"
    gated="$(jq '[.. | objects | select(.gated == true)] | length' "$BODY" 2>/dev/null || echo 0)"
    ratings="$(jq '[.days[]?.eventSummaries[]? | ((.regions[]?.slots[]?), (.unregioned[]?)) | .claudeRating | select(. != null)] | length' "$BODY" 2>/dev/null || echo 0)"
    if [ "${days:-0}" -lt 1 ]; then
        R_RC=1; R_MSG="GET /api/briefing -> 200 but with no days"; return 0
    fi
    if [ "${slots:-0}" -lt 1 ]; then
        R_RC=1; R_MSG="GET /api/briefing -> 200 but no slot carries a locationName (regioned or unregioned)"; return 0
    fi
    if [ "$ROLLBACK_MODE" -eq 0 ] && [ "${gated:-0}" -gt 0 ]; then
        R_RC=1; R_MSG="GET /api/briefing -> 200 but ${gated} gated marker(s) are present: a PRO reader is being served the Lite shape"; return 0
    fi
    if [ "${ratings:-0}" -lt 1 ]; then
        R_WARN="the briefing carries no served rating (a quiet day can; not a failure)"
    fi
    R_MSG="GET /api/briefing -> 200, ${days} day(s), ${slots} slot(s), ${ratings:-0} served rating(s)"
}

check_settings() {
    authed_get_failed /api/user/settings && return 0
    R_MSG="GET /api/user/settings -> 200"
}

# Ends the smoke session: revokes the refresh token and bumps the account's credentials version.
# Also run from the EXIT trap (quiet), so a probe that dies half way still logs out.
do_logout() {
    [ "$LOGGED_OUT" -eq 0 ] || return 0
    [ -s "$LOGIN_BODY" ] && [ -s "$AUTHFILE" ] || return 0
    LOGGED_OUT=1
    FORCE_MAXT=10
    jq -c '{refreshToken: .refreshToken}' "$LOGIN_BODY" >"$REQBODY"
    http POST /api/auth/logout -H "@${AUTHFILE}" -H 'Content-Type: application/json' --data "@${REQBODY}"
    rm -f "$REQBODY"
    FORCE_MAXT=""
    if [ "$HTTP_CODE" = 200 ]; then
        [ "${1:-}" = quiet ] || printf 'ok  logout: POST /api/auth/logout -> 200\n'
    else
        [ "${1:-}" = quiet ] && return 0
        warn "logout" "POST /api/auth/logout -> ${HTTP_CODE}; the smoke session may still be open"
    fi
}

skip_login() {
    local msg="logged-in probes SKIPPED: SMOKE_DEPLOY_PASSWORD is not set, so login, the briefing shape and the served ratings are NOT being checked"
    if [ "$LOGIN_REQUIRED" = true ]; then
        report_probe "logged in" 4 "SMOKE_DEPLOY_PASSWORD is not set and SMOKE_LOGIN_REQUIRED=true"
        return
    fi
    warn "logged in" "$msg"
    if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
        printf '%s\n' "- :warning: post-deploy smoke: ${msg}." >>"$GITHUB_STEP_SUMMARY" 2>/dev/null || true
    fi
}

finish() {
    local rc=0 why="all probes passed"
    if [ "$HAS1" -eq 1 ]; then
        rc=1; why="the release is broken"
    elif [ "$HAS4" -eq 1 ]; then
        rc=4; why="the smoke check is misconfigured"
    elif [ "$HAS3" -eq 1 ] || [ "$SKIPPED_DEADLINE" -eq 1 ]; then
        rc=3; why="inconclusive or infrastructure"
    fi
    echo "post-deploy-smoke: ${PROBES} probes, ${FAILED} failed -> exit ${rc} (${why})"
    exit "$rc"
}

# --- Run --------------------------------------------------------------------------
echo "post-deploy-smoke: ${BASE}$([ "$ROLLBACK_MODE" = 1 ] && echo ' (rollback mode)')"

run_probe "shell" check_shell
[ "$ABORT" -eq 1 ] && finish
run_probe "backend health" check_health
[ "$ABORT" -eq 1 ] && finish
run_probe "auth boundary" check_auth_boundary
[ "$ABORT" -eq 1 ] && finish
if [ "$ROLLBACK_MODE" -eq 0 ] && [ -n "$RELEASE_VERSION" ]; then
    run_probe "running build" check_running_build
    [ "$ABORT" -eq 1 ] && finish
fi

if [ "$PASSWORD_SET" -eq 0 ]; then
    skip_login
else
    run_probe "login" check_login
    [ "$ABORT" -eq 1 ] && finish
    if [ "$LOGIN_OK" -eq 1 ]; then
        run_probe "briefing" check_briefing
        [ "$ABORT" -eq 1 ] && finish
        run_probe "user settings" check_settings
        [ "$ABORT" -eq 1 ] && finish
    fi
    do_logout
fi

finish
