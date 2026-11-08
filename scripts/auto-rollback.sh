#!/bin/bash
# ============================================================================
# Automatic, IMAGE-ONLY rollback of a release that failed after `docker compose up`.
#
# Run by the deploy job (.github/workflows/deploy.yml) when the post-deploy checks
# say the release is broken AND the repository variable SMOKE_ENFORCE is "true".
# (docs/engineering/engineering-gaps-plan.md, Phase 4.)
#
# WHAT IT DOES
#   1. Reads ${BACKUP_DIR}/pre-release/<version>.json, written by pre-release-backup.sh
#      before the pull, through the same field reader as rollback.sh
#      (scripts/lib/release-manifest.sh).
#   2. Restores the OUTGOING nginx.conf and docker-compose.yml that manifest saved
#      (R6: configuration rolls back with the images). If either was not saved, it
#      rolls back the images only and says so.
#   3. Pins the two previous image DIGESTS in docker-compose.override.yml, the same
#      file and format rollback.sh writes, and runs
#      `docker compose up -d --no-deps --force-recreate goldenhour-backend goldenhour-frontend`.
#      --force-recreate because nginx.conf is a bind mount Compose cannot see change.
#   4. Re-runs prod-health.sh --deploy --classify, then post-deploy-smoke.sh
#      --rollback-mode, against the rolled-back build.
#
# WHAT IT NEVER DOES
#   It never touches the database: no pg_restore, no psql, no pg_dump. A rolled-back
#   release has still APPLIED its migrations. Flyway runs with validate-on-migrate
#   false, so an older image starts against a newer schema and usually reads it fine,
#   but it may fail on writes. The banner says so whenever the schema moved. Restoring
#   data stays a human decision: scripts/rollback.sh <version> (it DISCARDS everything
#   written since the release).
#
# EXIT CODES
#   0  --dry-run only: the plan was printed and nothing was run
#   1  ROLLED BACK. The previous images are pinned and verified. A rolled-back
#      release is still a failed release, so the deploy job still fails.
#   2  MANUAL ACTION NEEDED (or the rollback failed): no manifest or digests, a
#      pin that is already there, the previous build did not come up healthy, or
#      the deadline ran out. A loud banner says which and what to do. When the
#      old build would not come up the pin is LEFT IN PLACE so the next deploy
#      cannot silently go out; when only the smoke check failed against the old
#      build, the new release is brought back up as it was and the pin removed.
#
# THE PIN IS NAMED (R11). The override file's header names this script and the
# version, and the deploy job's guard prints that header. Removing the pin without
# a new deploy lets a bare `docker compose up` resolve :latest, the BAD release.
#
# Usage: ./auto-rollback.sh <version> [--dry-run]
#   BACKUP_DIR                  default $HOME/goldenhour-backups
#   COMPOSE_DIR                 default $HOME/goldenhour
#   GIT_REPO_DIR                repository holding the tag, to read the migrations the
#                               release carried (default $COMPOSE_DIR)
#   ROLLBACK_DEADLINE_SECONDS   one overall deadline, default 900. The banner is printed
#                               even when it is reached, and so on SIGTERM.
# ============================================================================

# cleanup() runs from the EXIT trap, which ShellCheck does not follow.
# shellcheck disable=SC2329
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source-path=SCRIPTDIR source=lib/release-manifest.sh
source "${SCRIPT_DIR}/lib/release-manifest.sh"

BACKUP_DIR="${BACKUP_DIR:-${HOME}/goldenhour-backups}"
RELEASE_DIR="${BACKUP_DIR}/pre-release"
COMPOSE_DIR="${COMPOSE_DIR:-${HOME}/goldenhour}"
GIT_REPO_DIR="${GIT_REPO_DIR:-${COMPOSE_DIR}}"
OVERRIDE_FILE="${COMPOSE_DIR}/docker-compose.override.yml"
DEADLINE_SECONDS="${ROLLBACK_DEADLINE_SECONDS:-900}"
BACKEND_SERVICE="goldenhour-backend"
FRONTEND_SERVICE="goldenhour-frontend"
MIGRATION_DIR="backend/src/main/resources/db/migration"

VERSION=""
DRY_RUN=false
for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=true ;;
        -*) echo "Usage: $0 <version> [--dry-run]" >&2; exit 2 ;;
        *) if [ -z "$VERSION" ]; then VERSION="$arg"; else echo "Usage: $0 <version> [--dry-run]" >&2; exit 2; fi ;;
    esac
done
[ -n "$VERSION" ] || { echo "Usage: $0 <version> [--dry-run]" >&2; exit 2; }
case "$VERSION" in
    *[!A-Za-z0-9._-]*) echo "ERROR: '${VERSION}' is not a plain release tag." >&2; exit 2 ;;
esac
case "$DEADLINE_SECONDS" in
    ''|*[!0-9]*) echo "ERROR: ROLLBACK_DEADLINE_SECONDS must be a whole number." >&2; exit 2 ;;
esac

START_AT="$(date +%s)"
DEADLINE_AT=$((START_AT + DEADLINE_SECONDS))
MANIFEST="${RELEASE_DIR}/${VERSION}.json"
WORK=""
FINISHED=false
STAGE="starting"

log() { echo "[auto-rollback] $*"; }

banner() {
    local title="$1"
    shift
    echo ""
    echo "────────────────────────────────────────────────────────────────────────"
    echo "$title"
    echo "────────────────────────────────────────────────────────────────────────"
    local line
    for line in "$@"; do
        printf '  %s\n' "$line"
    done
    echo "────────────────────────────────────────────────────────────────────────"
    echo ""
    if [ -n "${GITHUB_ACTIONS:-}" ]; then
        printf '::error::auto-rollback %s: %s\n' "$VERSION" "$title"
    fi
}

cleanup() {
    local rc=$?
    [ -z "${CHILD_PID:-}" ] || kill "$CHILD_PID" 2>/dev/null || true
    if [ "$FINISHED" != true ]; then
        banner "MANUAL ACTION NEEDED — the automatic rollback of ${VERSION} did not finish (stopped during: ${STAGE})" \
            "Production may be in a half-rolled-back state. Look at it before anything else:" \
            "    cd ${COMPOSE_DIR} && docker compose ps" \
            "If ${OVERRIDE_FILE} exists, production is PINNED to the previous build." \
            "The database was NOT touched. For a full restore, including data: ./scripts/rollback.sh ${VERSION}"
        [ -z "$WORK" ] || rm -rf "$WORK"
        exit 2
    fi
    [ -z "$WORK" ] || rm -rf "$WORK"
    exit "$rc"
}
trap cleanup EXIT
trap 'STAGE="${STAGE} (terminated by a signal)"; exit 2' TERM INT HUP

# Runs a command within what is left of the overall deadline. 124 means it ran out. It runs the
# command in the BACKGROUND and waits, because the shell defers a trap until a foreground command
# ends: with a foreground child a SIGTERM (the SSH action's command_timeout) would wait for it, and
# the banner would come too late to be seen.
DEADLINE_HIT=false
CHILD_PID=""
# A floor on what bounded() allows, for the one command that must run even after the deadline (the restore-forward
# `up`: abandoning it half way would leave the host neither rolled back nor forward).
BOUND_MIN=0
bounded() {
    local left=$((DEADLINE_AT - $(date +%s)))
    [ "$left" -ge "$BOUND_MIN" ] || left="$BOUND_MIN"
    if [ "$left" -le 0 ]; then
        DEADLINE_HIT=true
        return 124
    fi
    local rc=0
    if command -v timeout >/dev/null 2>&1; then
        timeout --kill-after=10 "$left" "$@" &
    else
        "$@" &
    fi
    CHILD_PID=$!
    wait "$CHILD_PID" || rc=$?
    CHILD_PID=""
    if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
        DEADLINE_HIT=true
    fi
    return "$rc"
}

# --- 1. The manifest (R12: missing manifest or digests is exit 2) ---------------
STAGE="reading the manifest"
if [ ! -f "$MANIFEST" ]; then
    FINISHED=true
    banner "MANUAL ACTION NEEDED — no rollback point for ${VERSION}: automatic rollback NOT attempted" \
        "No manifest at ${MANIFEST}." \
        "Nothing was changed. The failed release is still running." \
        "Decide by hand: fix forward, or  ./scripts/rollback.sh   (lists the rollback points that exist)."
    exit 2
fi

BACKEND_IMAGE="$(field backend)"
FRONTEND_IMAGE="$(field frontend)"
SCHEMA_BEFORE="$(field schema_version)"
TAKEN_AT="$(field taken_at)"
NGINX_SAVED_NAME="$(field nginx_conf)"
COMPOSE_SAVED_NAME="$(field compose_file)"

# These go into a YAML file that Compose will obey, so they must look like what they are.
DIGEST_PATTERN='^[A-Za-z0-9./_:-]+@sha256:[0-9a-f]{64}$'
for image in "$BACKEND_IMAGE" "$FRONTEND_IMAGE"; do
    if [ -z "$image" ] || ! printf '%s' "$image" | grep -Eq "$DIGEST_PATTERN"; then
        FINISHED=true
        banner "MANUAL ACTION NEEDED — the manifest for ${VERSION} has no usable image digests: automatic rollback NOT attempted" \
            "backend='${BACKEND_IMAGE}' frontend='${FRONTEND_IMAGE}' in ${MANIFEST}" \
            "A manifest pinning nothing is worse than leaving the failed release up for a human." \
            "Nothing was changed. The failed release is still running."
        exit 2
    fi
done
[ -n "$SCHEMA_BEFORE" ] || SCHEMA_BEFORE="not recorded"

NGINX_SAVED=""
COMPOSE_SAVED=""
[ -z "$NGINX_SAVED_NAME" ] || [ ! -f "${RELEASE_DIR}/${NGINX_SAVED_NAME}" ] || NGINX_SAVED="${RELEASE_DIR}/${NGINX_SAVED_NAME}"
[ -z "$COMPOSE_SAVED_NAME" ] || [ ! -f "${RELEASE_DIR}/${COMPOSE_SAVED_NAME}" ] || COMPOSE_SAVED="${RELEASE_DIR}/${COMPOSE_SAVED_NAME}"

# Which files WILL be (planned) and then WERE restored, each on its own: the banner names exactly those.
NGINX_RESTORED=0
COMPOSE_RESTORED=0
[ -z "$NGINX_SAVED" ] || NGINX_RESTORED=1
[ -z "$COMPOSE_SAVED" ] || COMPOSE_RESTORED=1
CONFIG_NOTE=""
describe_config() {
    if [ "$NGINX_RESTORED" -eq 1 ] && [ "$COMPOSE_RESTORED" -eq 1 ]; then
        CONFIG_NOTE="nginx.conf and docker-compose.yml restored to the outgoing release's copies"
    elif [ "$NGINX_RESTORED" -eq 1 ]; then
        CONFIG_NOTE="PARTIAL — restored: nginx.conf. NOT restored: docker-compose.yml (not saved, or not copyable), so the NEW release's copy is still in place"
    elif [ "$COMPOSE_RESTORED" -eq 1 ]; then
        CONFIG_NOTE="PARTIAL — restored: docker-compose.yml. NOT restored: nginx.conf (not saved, or not copyable), so the NEW release's copy is still in place"
    else
        CONFIG_NOTE="IMAGES ONLY — the outgoing nginx.conf/docker-compose.yml were not saved (a manifest from before this was added, or a file was missing), so the NEW release's configuration is still in place"
    fi
}
describe_config

# --- The migrations the failed release carried (R10) -----------------------------
# Read from the tag, never from the database: this script does not touch it.
schema_report() {
    local leading tag_max files
    leading="$(printf '%s' "$SCHEMA_BEFORE" | sed -n 's/^\([0-9][0-9]*\).*/\1/p')"
    files="$(git -C "$GIT_REPO_DIR" ls-tree --name-only "${VERSION}" "${MIGRATION_DIR}/" 2>/dev/null || true)"
    tag_max="$(printf '%s\n' "$files" | sed -n 's|.*/V\([0-9][0-9]*\)\(\.[0-9]*\)*__.*|\1|p' | sort -n | tail -1)"
    if [ -z "$leading" ] || [ -z "$tag_max" ]; then
        SCHEMA_CHANGED=unknown
        SCHEMA_LINE="Schema: could not compare (before the release: ${SCHEMA_BEFORE}; migrations in ${VERSION}: ${tag_max:-unreadable}). Assume the release applied migrations."
    elif [ "$tag_max" -gt "$leading" ]; then
        SCHEMA_CHANGED=yes
        SCHEMA_LINE="SCHEMA CHANGED: the database was at ${SCHEMA_BEFORE} before ${VERSION}; the release carries migrations up to V${tag_max}. They HAVE been applied and the database was NOT restored: the old code may read fine yet fail on writes."
    else
        SCHEMA_CHANGED=no
        SCHEMA_LINE="Schema: the database was at ${SCHEMA_BEFORE} before ${VERSION}; the release carries no newer migration (V${tag_max}). Database NOT restored, and none was needed."
    fi
}
SCHEMA_CHANGED=unknown
SCHEMA_LINE=""
schema_report

# --- Plan ---------------------------------------------------------------------------
plan() {
    echo "Automatic image-only rollback of ${VERSION}"
    echo "  manifest:   ${MANIFEST} (taken ${TAKEN_AT})"
    echo "  backend  -> ${BACKEND_IMAGE}"
    echo "  frontend -> ${FRONTEND_IMAGE}"
    echo "  config:     ${CONFIG_NOTE}"
    echo "  pin file:   ${OVERRIDE_FILE}"
    echo "  then:       docker compose up -d --no-deps --force-recreate ${BACKEND_SERVICE} ${FRONTEND_SERVICE}"
    echo "              prod-health.sh --deploy --classify, then post-deploy-smoke.sh --rollback-mode"
    echo "  ${SCHEMA_LINE}"
    echo "  The database is never touched."
}

if [ "$DRY_RUN" = true ]; then
    FINISHED=true
    plan
    echo "(--dry-run: nothing was run)"
    exit 0
fi

if [ -e "$OVERRIDE_FILE" ]; then
    FINISHED=true
    banner "MANUAL ACTION NEEDED — ${OVERRIDE_FILE} already exists: automatic rollback NOT attempted" \
        "Production is already pinned by something else. Its first lines:" \
        "$(head -3 "$OVERRIDE_FILE" | tr '\n' ' ')" \
        "Nothing was changed."
    exit 2
fi

plan
WORK="$(mktemp -d)"

# --- 2. Restore configuration, pin, and start the previous images (R6) ------------
STAGE="restoring configuration"
# What is in place now is the failed release's. Keep it, to put back if the old build is no better.
for f in nginx.conf docker-compose.yml; do
    [ ! -f "${COMPOSE_DIR}/${f}" ] || cp "${COMPOSE_DIR}/${f}" "${WORK}/new.${f}"
done
if [ "$NGINX_RESTORED" -eq 1 ] && ! cp "$NGINX_SAVED" "${COMPOSE_DIR}/nginx.conf"; then
    NGINX_RESTORED=0
    log "WARNING: could not restore nginx.conf."
fi
if [ "$COMPOSE_RESTORED" -eq 1 ] && ! cp "$COMPOSE_SAVED" "${COMPOSE_DIR}/docker-compose.yml"; then
    COMPOSE_RESTORED=0
    log "WARNING: could not restore docker-compose.yml."
fi
describe_config

STAGE="writing the pin"
cat > "${OVERRIDE_FILE}.partial" <<OVERRIDE
# Written by scripts/auto-rollback.sh (the AUTOMATIC image-only rollback) for release ${VERSION}
# at $(date -u +%Y-%m-%dT%H:%M:%SZ). Production is pinned to the build that preceded ${VERSION}.
# The database was NOT restored. DELETE THIS FILE only together with a new deploy: a bare
# 'docker compose up' without it resolves :latest, which is the BAD release.
services:
  ${BACKEND_SERVICE}:
    image: ${BACKEND_IMAGE}
  ${FRONTEND_SERVICE}:
    image: ${FRONTEND_IMAGE}
OVERRIDE
mv "${OVERRIDE_FILE}.partial" "$OVERRIDE_FILE"

STAGE="starting the previous images"
cd "$COMPOSE_DIR" || { echo "ERROR: cannot cd to ${COMPOSE_DIR}" >&2; exit 2; }
log "starting the previous images"
UP_RC=0
bounded docker compose up -d --no-deps --force-recreate "$BACKEND_SERVICE" "$FRONTEND_SERVICE" || UP_RC=$?

# --- 3. Verify the rolled-back build -------------------------------------------------
HEALTH_RC=99
SMOKE_RC=99
if [ "$UP_RC" -eq 0 ]; then
    STAGE="verifying the rolled-back build (prod-health)"
    export COMPOSE_DIR
    HEALTH_RC=0
    bounded bash "${SCRIPT_DIR}/prod-health.sh" --deploy --classify || HEALTH_RC=$?
    if [ "$HEALTH_RC" -eq 0 ]; then
        STAGE="verifying the rolled-back build (smoke)"
        SMOKE_RC=0
        bounded bash "${SCRIPT_DIR}/post-deploy-smoke.sh" --rollback-mode || SMOKE_RC=$?
    fi
fi

# --- 4. Outcomes ------------------------------------------------------------------------
if [ "$UP_RC" -eq 0 ] && [ "$HEALTH_RC" -eq 0 ] && { [ "$SMOKE_RC" -eq 0 ] || [ "$SMOKE_RC" -eq 3 ] || [ "$SMOKE_RC" -eq 4 ]; }; then
    STAGE="done"
    FINISHED=true
    SMOKE_LINE="smoke check against the previous build: passed"
    if [ "$SMOKE_RC" -ne 0 ]; then
        SMOKE_LINE="smoke check against the previous build: INCONCLUSIVE (exit ${SMOKE_RC}) — containers are healthy but the probes could not confirm the behaviour; look at it"
    fi
    banner "ROLLED BACK — ${VERSION} was broken; production is on the previous build" \
        "Running:   ${BACKEND_IMAGE}" \
        "           ${FRONTEND_IMAGE}" \
        "Config:    ${CONFIG_NOTE}" \
        "Verified:  prod-health passed; ${SMOKE_LINE}" \
        "DATABASE NOT RESTORED. Migrations the release applied stay applied." \
        "${SCHEMA_LINE}" \
        "Production is PINNED by ${OVERRIDE_FILE}." \
        "To roll forward: fix the problem, release a new tag, and let that deploy go out" \
        "(deploy.yml refuses to run while the pin exists, so remove it first:  rm ${OVERRIDE_FILE})." \
        "Removing the pin WITHOUT a new deploy lets a bare 'docker compose up' bring back :latest, which is the bad release." \
        "To also restore the data from before the release (DISCARDS everything written since): ./scripts/rollback.sh ${VERSION}"
    exit 1
fi

if [ "$UP_RC" -eq 0 ] && [ "$HEALTH_RC" -eq 0 ] && [ "$SMOKE_RC" -eq 1 ]; then
    # The previous build is up and healthy but fails the smoke check too: the rollback bought nothing, and
    # the pin only mixes the old images with a newer schema. Put the release back exactly as it was.
    STAGE="restoring the release (the previous build failed the smoke check too)"
    # The pin stays on disk until the forward `up` has SUCCEEDED, and `-f docker-compose.yml` makes Compose
    # ignore it meanwhile (naming a file disables the auto-loaded override). Deleting it first would leave the
    # host with no pin, the old containers running and nothing started if the `up` then failed.
    for f in nginx.conf docker-compose.yml; do
        [ ! -f "${WORK}/new.${f}" ] || cp "${WORK}/new.${f}" "${COMPOSE_DIR}/${f}"
    done
    FORWARD_RC=0
    # At least 120 s even if the overall deadline is spent: half a roll-forward is the worst state available.
    BOUND_MIN=120
    bounded docker compose -f docker-compose.yml up -d --no-deps --force-recreate "$BACKEND_SERVICE" "$FRONTEND_SERVICE" || FORWARD_RC=$?
    BOUND_MIN=0
    FINISHED=true
    if [ "$FORWARD_RC" -eq 0 ]; then
        rm -f "$OVERRIDE_FILE"
        banner "ROLLBACK FAILED — the release is back up as it was (the previous build fails the smoke check too)" \
            "The pin was removed and ${VERSION}'s configuration and images restored." \
            "The previous build was healthy but failed the same probes, so rolling back changes nothing:" \
            "the fault is probably not in the images. Investigate production directly." \
            "${SCHEMA_LINE}"
    else
        # Put the host back as the rollback left it: the old configuration beside the pin, so the three agree.
        [ "$NGINX_RESTORED" -ne 1 ] || cp "$NGINX_SAVED" "${COMPOSE_DIR}/nginx.conf"
        [ "$COMPOSE_RESTORED" -ne 1 ] || cp "$COMPOSE_SAVED" "${COMPOSE_DIR}/docker-compose.yml"
        banner "MANUAL ACTION NEEDED — the rollback failed AND bringing ${VERSION} back up failed (exit ${FORWARD_RC})" \
            "The PIN IS STILL IN PLACE (${OVERRIDE_FILE}) and the previous configuration was put back, so the host agrees with itself." \
            "Containers may be half-recreated. Look now:  cd ${COMPOSE_DIR} && docker compose ps" \
            "Config:    ${CONFIG_NOTE}" \
            "${SCHEMA_LINE}"
    fi
    exit 2
fi

# The previous build would not come up (or could not be verified): leave the pin so the next deploy cannot go out silently.
FINISHED=true
DETAIL="docker compose up exited ${UP_RC}"
[ "$UP_RC" -ne 0 ] || DETAIL="prod-health.sh --deploy --classify exited ${HEALTH_RC} on the previous build"
[ "$HEALTH_RC" -ne 0 ] || DETAIL="post-deploy-smoke.sh --rollback-mode exited ${SMOKE_RC} on the previous build"
[ "$DEADLINE_HIT" != true ] || DETAIL="${DETAIL}; the ${DEADLINE_SECONDS}s deadline was reached"
if [ "$HEALTH_RC" -eq 1 ] && [ "$SCHEMA_CHANGED" != no ]; then
    ADVICE="The old image failed its own in-container health check after a schema change: it probably cannot read the newer schema. Only now is the full restore worth considering (it DISCARDS data written since the release):  ./scripts/rollback.sh ${VERSION}"
else
    ADVICE="Read the container logs first:  cd ${COMPOSE_DIR} && docker compose ps && docker compose logs --tail 100 ${BACKEND_SERVICE}. A database restore (./scripts/rollback.sh ${VERSION}) is NOT indicated by this failure alone."
fi
banner "MANUAL ACTION NEEDED — the previous build of ${VERSION} did not come up healthy; the rollback is NOT verified" \
    "${DETAIL}" \
    "The pin is LEFT IN PLACE: ${OVERRIDE_FILE} (so the next deploy cannot silently go out)." \
    "Config:    ${CONFIG_NOTE}" \
    "${SCHEMA_LINE}" \
    "DATABASE NOT RESTORED." \
    "${ADVICE}"
exit 2
