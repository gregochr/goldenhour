#!/bin/bash
# Deploy the static landing site (landing/, photocast.online) to the production host over SSH.
#
# The landing site is NOT part of the tagged release (.github/workflows/deploy.yml builds only the
# backend and frontend images), so this is its own script. It runs from the laptop, builds the
# container ON the host from landing/ at the chosen ref, and smoke-tests it there.
#
# Asks ONE question: "Deploy <sha> to <host>?" — after printing exactly what will change.
#
# Usage: ./release-landing.sh [--ref REF] [--host HOST] [--yes] [--dry-run] [--force]
#        ./release-landing.sh --rollback [TAG]
#   --ref REF     deploy origin/REF (default: main). Another branch deploys unmerged work.
#   --host HOST   ssh target (default: $LANDING_HOST, else gregochr@100.76.73.16)
#   --yes         skip the go-ahead question
#   --dry-run     preflight and show the diff, then stop before the question
#   --force       rebuild even when the host's files and deployed marker already match REF
#   --rollback    with no TAG: list the host's rollback images (newest first).
#                 with TAG: put that image back and re-run the smoke test.
#
# Safety:
#   - The host's ~/goldenhour is a STALE checkout carrying hand-edited, uncommitted production
#     config. This script NEVER pulls, merges, resets, stashes or switches branches there. The only
#     update is `git fetch origin REF` then `git checkout FETCH_HEAD -- landing/`; HEAD never moves.
#   - preflight reads the tree at the target SHA (not your working tree) and refuses to ship a page
#     that references an asset the Dockerfile does not COPY (that 404 has bitten before)
#   - the running image is tagged photocast-landing:rollback-<timestamp> before it is replaced
#   - a failed smoke test exits non-zero and prints the rollback command; it never rolls back itself
#   - the host's old, untracked ~/goldenhour/deploy-landing.sh runs `git pull`: delete it

set -euo pipefail

DEFAULT_HOST="gregochr@100.76.73.16"
HOST="${LANDING_HOST:-$DEFAULT_HOST}"
REF="main"
ASSUME_YES=0
DRY_RUN=0
FORCE=0
ROLLBACK=0
ROLLBACK_TAG=""
PREFLIGHT_ONLY=""
PUBLIC_URL="https://photocast.online"

if [[ -t 1 ]]; then
    RED=$'\033[31m'; YEL=$'\033[33m'; GRN=$'\033[32m'; RST=$'\033[0m'
else
    RED=""; YEL=""; GRN=""; RST=""
fi
die()  { printf '%sError: %s%s\n' "$RED" "$*" "$RST" >&2; exit 1; }
warn() { printf '%sWarning: %s%s\n' "$YEL" "$*" "$RST" >&2; }
ok()   { printf '%s%s%s\n' "$GRN" "$*" "$RST"; }

# 0. Arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --ref)      REF="${2:?--ref needs a branch or ref}"; shift 2 ;;
        --host)     HOST="${2:?--host needs user@host}"; shift 2 ;;
        --yes|-y)   ASSUME_YES=1; shift ;;
        --dry-run)  DRY_RUN=1; shift ;;
        --force)    FORCE=1; shift ;;
        --rollback) ROLLBACK=1; shift
                    if [[ $# -gt 0 && "$1" != -* ]]; then ROLLBACK_TAG="$1"; shift; fi ;;
        --preflight-only) PREFLIGHT_ONLY="${2:?--preflight-only needs a SHA}"; shift 2 ;;
        -h|--help)  sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
        *)          echo "Error: unknown option $1 (see ./release-landing.sh --help)" >&2; exit 1 ;;
    esac
done

cd "$(git rev-parse --show-toplevel)"

safe_word() { [[ "$1" =~ ^[A-Za-z0-9._/@:-]+$ ]]; }
safe_word "$HOST" || die "host '$HOST' has unexpected characters"
safe_word "$REF"  || die "ref '$REF' has unexpected characters"

ssh_host() { ssh -o BatchMode=yes -o ConnectTimeout=10 "$HOST" "$@"; }

check_ssh() {
    if ! ssh_host true </dev/null 2>/dev/null; then
        die "cannot ssh to $HOST (BatchMode, 10 s timeout). Is Tailscale up and your key loaded? Try: ssh $HOST"
    fi
}

# Every local file the pages and stylesheet reference, one per line (relative to landing/).
list_referenced() {
    local sha="$1" f
    {
        for f in $(git ls-tree --name-only "$sha" landing/ | sed 's#^landing/##' | grep '\.html$' || true); do
            git show "$sha:landing/$f" | grep -oE '(src|href)="[^"]*"' | sed -E 's/^(src|href)="//; s/"$//'
        done
        git show "$sha:landing/photocast.css" | grep -oE 'url\([^)]*\)' | sed -E "s/^url\(//; s/\)\$//; s/[\"']//g"
    } | sed -E 's/[#?].*$//; s#^\./##' \
      | grep -vE '^$|^https?:|^//|^mailto:|^tel:|^data:' | sort -u
}

# Fail if a referenced file is missing at SHA or has no COPY line in the Dockerfile at SHA.
preflight_assets() {
    local sha="$1" name bad=0 dockerfile
    dockerfile=$(git show "$sha:landing/Dockerfile") || die "no landing/Dockerfile at $sha"
    for name in $(list_referenced "$sha"); do
        if ! safe_word "$name"; then
            printf '  %s: unexpected characters in name\n' "$name"; bad=1; continue
        fi
        if ! git cat-file -e "$sha:landing/$name" 2>/dev/null; then
            printf '  %s: referenced but not in landing/ at %s\n' "$name" "${sha:0:8}"; bad=1
        fi
        if ! grep -qE "^COPY ${name//./\\.} " <<< "$dockerfile"; then
            printf '  %s: no "COPY %s " line in landing/Dockerfile\n' "$name" "$name"; bad=1
        fi
    done
    if [[ $bad -ne 0 ]]; then
        echo "Preflight failed: the assets above would ship as a 404. Fix the Dockerfile (or the reference)."
        return 1
    fi
    echo "Preflight ok: every referenced asset exists and has a COPY line ($(list_referenced "$sha" | wc -l | tr -d ' ') files)."
}

# Everything curl'd in the smoke test: the root, each html page, each referenced asset.
smoke_paths() {
    local sha="$1"
    {
        echo "/"
        list_referenced "$sha" | sed 's#^#/#'
        git ls-tree --name-only "$sha" landing/ | sed 's#^landing/#/#' | grep '\.html$' || true
    } | sort -u
}

bell() {
    printf '\a'
    if command -v osascript >/dev/null 2>&1; then
        osascript -e "display notification \"$1\" with title \"release-landing\"" >/dev/null 2>&1 || true
    fi
}

confirm() {
    local prompt="$1" reply
    [[ $ASSUME_YES -eq 1 ]] && return 0
    if ! read -r -p "$prompt [y/N] " reply; then return 1; fi
    [[ "$reply" == "y" || "$reply" == "Y" ]]
}

# Remote smoke test, shared by deploy and rollback. smoke <expected-css-sha256 or ""> <path>...
REMOTE_SMOKE='
smoke() {
    local want="$1"; shift
    local p code i got fail=0
    for p in "$@"; do
        code=000
        for i in $(seq 1 15); do
            code=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8085$p" || true)
            [ "$code" = "200" ] && break
            sleep 1
        done
        printf "  %-28s %s\n" "$p" "$code"
        [ "$code" = "200" ] || fail=1
    done
    if [ -n "$want" ]; then
        got=$(curl -s http://localhost:8085/photocast.css | sha256sum | cut -d" " -f1)
        if [ "$got" = "$want" ]; then
            echo "  photocast.css sha256         matches"
        else
            echo "  photocast.css sha256         MISMATCH (served $got, expected $want)"
            fail=1
        fi
    fi
    return $fail
}
'

# ---------------------------------------------------------------- hidden test hook
if [[ -n "$PREFLIGHT_ONLY" ]]; then
    preflight_assets "$PREFLIGHT_ONLY"
    exit $?
fi

# ---------------------------------------------------------------- rollback
if [[ $ROLLBACK -eq 1 ]]; then
    check_ssh
    if [[ -z "$ROLLBACK_TAG" ]]; then
        echo "Rollback images on $HOST (newest first):"
        ssh_host "docker images photocast-landing --format '{{.Tag}}' | grep '^rollback-' | sort -r" </dev/null \
            || echo "  (none)"
        echo "Roll back with: ./release-landing.sh --rollback <tag>"
        exit 0
    fi
    ROLLBACK_TAG="${ROLLBACK_TAG#photocast-landing:}"
    safe_word "$ROLLBACK_TAG" || die "tag '$ROLLBACK_TAG' has unexpected characters"
    ssh_host "docker image inspect photocast-landing:$ROLLBACK_TAG >/dev/null" </dev/null \
        || die "no image photocast-landing:$ROLLBACK_TAG on $HOST (list them: ./release-landing.sh --rollback)"
    confirm "Roll $HOST back to photocast-landing:$ROLLBACK_TAG?" || { echo "Cancelled."; exit 1; }
    {
        echo "set -euo pipefail"
        echo "$REMOTE_SMOKE"
        cat <<'REMOTE'
tag="$1"
docker tag "photocast-landing:$tag" landing-landing:latest
cd ~/goldenhour/landing
docker compose up -d --no-build --force-recreate </dev/null
echo "Smoke test:"
smoke "" / /photocast.css
REMOTE
    } | ssh_host bash -s -- "$ROLLBACK_TAG" || die "rollback smoke test failed on $HOST"
    ok "Rolled back to photocast-landing:$ROLLBACK_TAG."
    echo "Note: this restores the IMAGE only. The host's landing/ files are still the deployed version;"
    echo "the next deploy (or a manual checkout) will bring the files back in line."
    bell "rolled back"
    exit 0
fi

# ---------------------------------------------------------------- 1. local preflight
echo "1. Local preflight"
git fetch origin --quiet || die "git fetch origin failed"
SHA=$(git rev-parse --verify "origin/$REF^{commit}" 2>/dev/null) || die "origin/$REF not found"
SHORT="${SHA:0:8}"
echo "  deploying origin/$REF = $SHORT: $(git log -1 --format=%s "$SHA")"
if [[ "$REF" != "main" ]]; then
    warn "origin/$REF is NOT main: this deploys UNMERGED work to production."
fi
preflight_assets "$SHA" || exit 1

# ---------------------------------------------------------------- 2. what will change
echo
echo "2. Host: $HOST"
check_ssh
REMOTE_INFO=$(ssh_host bash -s -- "$REF" <<'REMOTE'
set -euo pipefail
cd ~/goldenhour
git fetch -q origin "$1"
echo "FETCHED=$(git rev-parse FETCH_HEAD)"
echo "PREV=$( (cat ~/.photocast-landing-deployed 2>/dev/null || true) | awk '{print $1}')"
echo "---"
git diff --stat FETCH_HEAD -- landing/
REMOTE
) || die "could not fetch origin/$REF on the host"
FETCHED=$(sed -n 's/^FETCHED=//p' <<< "$REMOTE_INFO")
PREV=$(sed -n 's/^PREV=//p' <<< "$REMOTE_INFO")
DIFFSTAT=$(sed '1,/^---$/d' <<< "$REMOTE_INFO")
[[ "$FETCHED" == "$SHA" ]] || die "host fetched $FETCHED but local origin/$REF is $SHA (the branch moved; re-run)"
if [[ -n "$PREV" ]]; then
    echo "  previously deployed: ${PREV:0:8}"
    if git cat-file -e "$PREV^{commit}" 2>/dev/null; then
        echo "  landing/ commits since then:"
        git log --oneline "$PREV..$SHA" -- landing/ | sed 's/^/    /'
    fi
else
    echo "  previously deployed: unknown (no ~/.photocast-landing-deployed marker yet)"
fi
if [[ -z "$DIFFSTAT" ]]; then
    if [[ "$PREV" == "$SHA" && $FORCE -eq 0 ]]; then
        echo "  host files match and the marker says $SHORT is deployed: nothing to deploy (--force to rebuild anyway)."
        exit 0
    elif [[ "$PREV" == "$SHA" ]]; then
        echo "  host landing/ already matches; --force given."
    else
        MARKER="${PREV:0:8}"
        echo "  host files already match but the marker says ${MARKER:-unknown} is deployed; rebuilding."
    fi
else
    echo "  host working tree vs $SHORT:"
    printf '%s\n' "$DIFFSTAT" | sed 's/^/    /'
fi

# ---------------------------------------------------------------- 3. go-ahead
if [[ $DRY_RUN -eq 1 ]]; then
    echo
    echo "Dry run: stopping before the go-ahead."
    exit 0
fi
echo
confirm "Deploy $SHORT to $HOST?" || { echo "Cancelled."; exit 1; }

# ---------------------------------------------------------------- 4. remote deploy
CSS_SHA=$(git show "$SHA:landing/photocast.css" | shasum -a 256 | cut -d' ' -f1)
SMOKE_LIST=$(smoke_paths "$SHA" | tr '\n' ' ')
LOG="${TMPDIR:-/tmp}/release-landing.$$.log"
echo
echo "4. Deploying"
set +e
{
    echo "set -euo pipefail"
    echo "$REMOTE_SMOKE"
    cat <<'REMOTE'
sha="$1"; ref="$2"; css="$3"; shift 3
tag="rollback-$(date +%Y%m%d-%H%M%S)"
if docker inspect photocast-landing >/dev/null 2>&1; then
    docker tag "$(docker inspect -f '{{.Image}}' photocast-landing)" "photocast-landing:$tag"
    echo "ROLLBACK_TAG=$tag"
else
    echo "WARNING: container photocast-landing not found; no rollback image tagged"
fi
cd ~/goldenhour
git fetch -q origin "$ref"
[ "$(git rev-parse FETCH_HEAD)" = "$sha" ] || { echo "FETCH_HEAD moved off $sha; aborting before any change"; exit 3; }
git checkout FETCH_HEAD -- landing/
cd landing
docker compose build --no-cache </dev/null
docker compose up -d --force-recreate </dev/null
echo "Smoke test:"
if ! smoke "$css" "$@"; then
    echo "SMOKE_FAILED"
    exit 4
fi
printf '%s %s\n' "$sha" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > ~/.photocast-landing-deployed
echo "DEPLOYED"
REMOTE
} | ssh_host bash -s -- "$SHA" "$REF" "$CSS_SHA" $SMOKE_LIST | tee "$LOG"
RC=${PIPESTATUS[1]}
set -e
TAG=$(sed -n 's/^ROLLBACK_TAG=//p' "$LOG" | tail -1)
rm -f "$LOG"
if [[ $RC -ne 0 ]]; then
    bell "deploy FAILED"
    printf '%sDeploy failed on %s (exit %s).%s\n' "$RED" "$HOST" "$RC" "$RST" >&2
    if [[ -n "$TAG" ]]; then
        echo "Roll back with:  ./release-landing.sh --rollback $TAG --host $HOST" >&2
    else
        echo "No rollback image was tagged (container was missing)." >&2
    fi
    exit 1
fi

# ---------------------------------------------------------------- 5. public check
echo
echo "5. Public check"
PUBLIC_BAD=0
for p in / /photocast.css; do
    code=$(curl -s -o /dev/null -m 15 -w '%{http_code}' "$PUBLIC_URL$p" || true)
    printf '  %-16s %s\n' "$p" "$code"
    [[ "$code" == "200" ]] || PUBLIC_BAD=1
done
if [[ $PUBLIC_BAD -ne 0 ]]; then
    warn "public URL not returning 200: check the Cloudflare Tunnel on the host (the host itself passed its smoke test)"
fi

echo
ok "Landing deployed: $SHORT (origin/$REF) on $HOST."
if [[ -n "$TAG" ]]; then
    echo "Rollback image: photocast-landing:$TAG  (./release-landing.sh --rollback $TAG)"
fi
bell "landing deployed"
