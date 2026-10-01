#!/bin/bash
# ============================================================================
# Production health check — every morning, and at the end of every deploy.
#
# WHY THIS EXISTS
#
# On 2026-10-01 the owner logged into dockermacmini by hand, saw Ubuntu's
# "26 updates can be applied" banner, applied them, got "*** System restart
# required ***", rebooted, and then checked production came back by running
# four commands by eye: `docker compose ps`, `systemctl status cloudflared`,
# `tailscale status`, and `curl https://app.photocast.online/api/briefing`.
# Those 26 updates had sat unapplied until someone happened to log in and
# notice a banner — nothing had been watching for that. This script is the
# four-command check, made to run itself: every morning unattended, and again
# right after a deploy, because "the containers came up" and "production is
# actually reachable from the internet, through the tunnel, with a sane auth
# answer" are different claims and only the second one is the one that
# matters.
#
# TWO MODES, ONE SCRIPT
#
#   --deploy  runs checks 1-4 only: the service/tunnel/network/app checks a
#             deploy can act on immediately. A release must never be blocked
#             by the host wanting a reboot — that is an owner decision, not
#             a bug — so the reboot and apt-update checks are deliberately
#             left out of this mode.
#   --daily   (the default) runs all six, including the reboot and pending-
#             update checks. OWNER DECISION (2026-10-01): a pending reboot
#             or pending apt updates FAIL the morning run. A warning on an
#             otherwise-green run is invisible — that is exactly how the 26
#             updates sat unnoticed — so this is a hard failure, loud enough
#             to open a GitHub issue (see .github/workflows/prod-health.yml).
#
# WHAT IS DELIBERATELY NOT CLAIMED HERE
#
#   - This does not run `apt update` itself (no sudo on this host for an
#     unattended job), so the pending-updates count is only as fresh as
#     update-notifier's own daily `apt update` run. A package that became
#     vulnerable an hour ago may not show up until tomorrow's cache refresh.
#   - This does not reboot the host. Sudo on dockermacmini needs a password
#     interactively, so a pending-reboot FAIL is a prompt for a human, not
#     an action this script can complete on its own.
#   - A passing `--deploy` run says the new containers are healthy and the
#     app answers from outside; it says nothing about whether the release
#     behaves correctly, only that it is up.
#   - `docker compose ps --format json` is read here against the Compose v2
#     (Go CLI) field names `Name`/`Service`/`State`/`Health`, one JSON object
#     per line (NDJSON) on a current Compose — verified on dockermacmini
#     2026-10-01, services "Up About a minute (healthy)" / "Up About a
#     minute". An older Compose emits a single JSON array instead; the jq
#     filter below (`if type=="array" then .[] else . end`) handles both
#     shapes without needing to know which one a given host runs.
#
# Usage: ./prod-health.sh [--deploy|--daily]
#   COMPOSE_DIR     Directory holding docker-compose.yml (default: $HOME/goldenhour)
#   HEALTH_TIMEOUT  Seconds to poll compose service health (default: 180)
#   APP_URL         URL that must answer 401 (default: https://app.photocast.online/api/briefing)
# ============================================================================

set -euo pipefail

COMPOSE_DIR="${COMPOSE_DIR:-${HOME}/goldenhour}"
HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-180}"
APP_URL="${APP_URL:-https://app.photocast.online/api/briefing}"

MODE="daily"
case "${1:-}" in
    --deploy) MODE="deploy" ;;
    --daily|"") MODE="daily" ;;
    *)
        echo "Usage: $0 [--deploy|--daily]" >&2
        exit 2
        ;;
esac

FAILURES=0

report_ok() {
    printf 'ok  %s: %s\n' "$1" "$2"
}

report_fail() {
    FAILURES=$((FAILURES + 1))
    printf 'FAIL %s: %s\n' "$1" "$2"
    if [ -n "${GITHUB_ACTIONS:-}" ]; then
        printf '::error::%s: %s\n' "$1" "$2"
    fi
}

# --- 1. Compose services --------------------------------------------------
#
# goldenhour-db and goldenhour-backend both carry a healthcheck in
# docker-compose.yml and must report Health == "healthy". goldenhour-frontend
# has no healthcheck defined, so "running" is the strongest claim available
# for it. Polls because the deploy caller runs this seconds after
# `docker compose up`, and the backend's own healthcheck has a 30s
# start_period plus retries — about a minute before it reports healthy after
# a restart.
check_compose_services() {
    local name="compose services"

    if ! command -v jq >/dev/null 2>&1; then
        report_fail "$name" "jq is not installed — cannot parse 'docker compose ps --format json'"
        return
    fi

    local deadline
    deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))

    while true; do
        local raw
        if ! raw="$(cd "$COMPOSE_DIR" && docker compose ps --format json 2>&1)"; then
            if [ "$(date +%s)" -ge "$deadline" ]; then
                report_fail "$name" "'docker compose ps' failed in ${COMPOSE_DIR}: ${raw}"
                return
            fi
            sleep 5
            continue
        fi

        # Normalise both known shapes: NDJSON (one object per line, current
        # Compose) and a single top-level JSON array (older Compose). Each
        # top-level value jq reads from NDJSON is already an object, so the
        # "else ." branch passes it through unchanged; a single array value
        # is unpacked by ".[]".
        local normalized
        normalized="$(printf '%s' "$raw" | jq -c 'if type=="array" then .[] else . end' 2>/dev/null || true)"

        local db_health backend_health frontend_state
        db_health="$(printf '%s\n' "$normalized" | jq -r 'select(.Service=="goldenhour-db") | .Health' 2>/dev/null | tail -1 || true)"
        backend_health="$(printf '%s\n' "$normalized" | jq -r 'select(.Service=="goldenhour-backend") | .Health' 2>/dev/null | tail -1 || true)"
        frontend_state="$(printf '%s\n' "$normalized" | jq -r 'select(.Service=="goldenhour-frontend") | .State' 2>/dev/null | tail -1 || true)"

        if [ "$db_health" = "healthy" ] && [ "$backend_health" = "healthy" ] && [ "$frontend_state" = "running" ]; then
            report_ok "$name" "goldenhour-db=healthy goldenhour-backend=healthy goldenhour-frontend=running"
            return
        fi

        if [ "$(date +%s)" -ge "$deadline" ]; then
            report_fail "$name" "timed out after ${HEALTH_TIMEOUT}s (db=${db_health:-?} backend=${backend_health:-?} frontend=${frontend_state:-?})"
            echo "---- docker compose ps (${COMPOSE_DIR}) ----"
            (cd "$COMPOSE_DIR" && docker compose ps) || true
            echo "---------------------------------------------"
            return
        fi

        sleep 5
    done
}

# --- 2. cloudflared --------------------------------------------------------
#
# One tunnel serves eight sites, photocast.online included, so a failure
# here is worded to make that blast radius obvious rather than reading like
# a one-site problem.
check_cloudflared() {
    local name="cloudflared"
    local state
    state="$(systemctl is-active cloudflared 2>&1 || true)"

    if [ "$state" = "active" ]; then
        local since
        since="$(systemctl show cloudflared --property=ActiveEnterTimestamp --value 2>/dev/null || true)"
        if [ -n "$since" ]; then
            report_ok "$name" "active since ${since}"
        else
            report_ok "$name" "active"
        fi
    else
        report_fail "$name" "systemctl is-active reports '${state}', not 'active' — this one tunnel serves eight sites, photocast.online included, so this is not a single-site outage"
    fi
}

# --- 3. Tailscale ------------------------------------------------------------
#
# The Deploy workflow's SSH step dials this host over Tailscale at
# 100.76.73.16. An expired node key answers NeedsLogin instead of Running and
# broke the v2.22.0 deploy on 2026-09-25.
check_tailscale() {
    local name="tailscale"

    if ! command -v tailscale >/dev/null 2>&1; then
        report_fail "$name" "tailscale binary not found — it is required on this host, since the deploy job reaches it at 100.76.73.16 over Tailscale"
        return
    fi

    if ! command -v jq >/dev/null 2>&1; then
        report_fail "$name" "jq is not installed — cannot parse 'tailscale status --json'"
        return
    fi

    local state
    state="$(tailscale status --json 2>/dev/null | jq -r '.BackendState' 2>/dev/null || true)"

    if [ "$state" = "Running" ]; then
        report_ok "$name" "BackendState=Running"
    else
        report_fail "$name" "BackendState='${state:-unknown}', not 'Running' — the deploy job dials this host over Tailscale (100.76.73.16); an expired node key (NeedsLogin) broke the v2.22.0 deploy on 2026-09-25"
    fi
}

# --- 4. App answers from outside --------------------------------------------
#
# 401 unauthenticated is the alive signal: 200 would mean auth is broken,
# 502/530 means the tunnel or backend is down. api.photocast.online is never
# tested here — it points at a deleted tunnel and is permanently 530.
#
# Retried up to 3 times, 10s apart: a single curl through Cloudflare can hit a
# momentary 502/520 even when production is fine, and in --deploy mode a lone
# blip would fail the release and have release.sh ring its bell for nothing.
check_app_answers() {
    local name="app reachable"
    local attempts=3
    local code=""
    local i

    for ((i = 1; i <= attempts; i++)); do
        code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 "$APP_URL" 2>&1 || true)"
        if [ "$code" = "401" ]; then
            report_ok "$name" "${APP_URL} -> 401 (unauthenticated — the alive signal)"
            return
        fi
        if [ "$i" -lt "$attempts" ]; then
            sleep 10
        fi
    done

    report_fail "$name" "${APP_URL} -> '${code}', not 401, after ${attempts} attempts — 200 would mean auth is broken, 502/530 means the tunnel or backend is down"
}

# --- 5. Reboot required (daily only) ----------------------------------------
check_reboot_required() {
    local name="reboot required"

    if [ -f /var/run/reboot-required ]; then
        local pkgs=""
        if [ -f /var/run/reboot-required.pkgs ]; then
            pkgs=" Packages: $(tr '\n' ' ' < /var/run/reboot-required.pkgs)"
        fi
        report_fail "$name" "/var/run/reboot-required exists.${pkgs} Run 'sudo reboot' on dockermacmini at a quiet moment — this check cannot do it itself, since sudo there needs an interactive password"
    else
        report_ok "$name" "no reboot pending"
    fi
}

# --- 6. Pending apt updates (daily only) ------------------------------------
#
# Reads the cache update-notifier's own daily `apt update` maintains; this
# check does not run `apt update` itself (no sudo). The count can therefore
# lag reality by up to a day.
check_apt_updates() {
    local name="apt updates"
    local count
    count="$(apt list --upgradable 2>/dev/null | tail -n +2 | grep -c . || true)"
    count="${count:-0}"

    if [ "$count" -gt 0 ]; then
        local sample
        sample="$(apt list --upgradable 2>/dev/null | tail -n +2 | cut -d/ -f1 | head -10 | tr '\n' ' ')"
        report_fail "$name" "${count} package(s) upgradable, first 10: ${sample}(reads update-notifier's cached daily 'apt update' — this check does not run apt update itself)"
    else
        report_ok "$name" "no pending updates"
    fi
}

run_deploy_checks() {
    check_compose_services
    check_cloudflared
    check_tailscale
    check_app_answers
}

run_daily_checks() {
    run_deploy_checks
    check_reboot_required
    check_apt_updates
}

case "$MODE" in
    deploy)
        run_deploy_checks
        TOTAL_CHECKS=4
        ;;
    daily)
        run_daily_checks
        TOTAL_CHECKS=6
        ;;
esac

echo "prod-health: ${TOTAL_CHECKS} checks, ${FAILURES} failed (${MODE})"

if [ "$FAILURES" -gt 0 ]; then
    exit 1
fi

exit 0
