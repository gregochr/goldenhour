#!/bin/bash
# ============================================================================
# Record the image digests of what is now running, immediately after a deploy.
#
# WHY THIS EXISTS
#
# pre-release-backup.sh needs the RepoDigest of the outgoing images so a
# rollback can pin exact builds rather than :latest. It reads them off the
# running containers' image — and on 2026-07-29 that read came back EMPTY,
# aborting the v2.17.3 deploy with:
#
#   ERROR: could not read image digests (backend='' frontend='ghcr.io/...')
#
# The trigger was a `docker compose pull` run between releases (to verify an
# unrelated registry-auth fix). The pull moved :latest, and the running
# container's image stopped reporting a RepoDigest.
#
# WHAT IS DELIBERATELY NOT CLAIMED HERE
#
# The exact condition under which Docker drops a RepoDigest is NOT asserted,
# because it could not be reproduced cleanly afterwards: this host runs the
# containerd image store (`docker info` reports the
# io.containerd.snapshotter.v1 driver), where the image ID and the repo digest
# are the same value, and a later inspection of the displaced image showed its
# RepoDigests still intact. So the mechanism is image-store-dependent and was
# observed once rather than characterised.
#
# This guards the SYMPTOM instead. Whatever the cause, an empty digest read
# aborts a release, and the digest is reliably knowable at exactly one moment:
# just after the deploy that started the container. Recording it then costs
# nothing and removes a class of failure without needing a theory of it.
#
# WHY THE LEDGER IS SAFE TO TRUST
#
# It records the image ID alongside the digest, and the fallback returns the
# digest ONLY when the running container's image ID still equals the recorded
# one. That makes the fallback a checkable claim about the image actually
# running rather than an assumption about the last one — and on a mismatch the
# backup aborts exactly as it does today, because a manifest pinning the wrong
# build is worse than no manifest.
#
# Usage: ./record-deployed-images.sh
# ============================================================================

set -euo pipefail

BACKEND_CONTAINER="${BACKEND_CONTAINER:-goldenhour-backend}"
FRONTEND_CONTAINER="${FRONTEND_CONTAINER:-goldenhour-frontend}"
LEDGER="${DEPLOYED_IMAGES_LEDGER:-${HOME}/goldenhour-data/deployed-images.json}"

log() { echo "[record-deployed-images] $*"; }

mkdir -p "$(dirname "$LEDGER")"

# Non-fatal throughout, deliberately. This runs AFTER the new images are live:
# the release has already succeeded by the time it is called, and failing here
# would report a healthy deploy as broken. A missing ledger only costs the NEXT
# release its fallback, and that release still has the primary read.
entry_for() {
    local container="$1" image_id digest
    image_id="$(docker inspect --format='{{.Image}}' "$container" 2>/dev/null || true)"
    if [ -z "$image_id" ]; then
        log "WARN: ${container} is not running — no digest recorded"
        return 0
    fi
    digest="$(docker inspect --format='{{if .RepoDigests}}{{index .RepoDigests 0}}{{end}}' \
        "$image_id" 2>/dev/null || true)"
    if [ -z "$digest" ]; then
        log "WARN: ${container} image ${image_id} carries no RepoDigest — nothing to record."
        log "      The next release falls back to reading the container directly."
        return 0
    fi
    printf '%s\t%s\t%s\n' "$container" "$image_id" "$digest"
}

RECORDS="$( { entry_for "$BACKEND_CONTAINER"; entry_for "$FRONTEND_CONTAINER"; } || true )"

if [ -z "$RECORDS" ]; then
    log "WARN: nothing to record — the next release falls back to reading the "
    log "      running containers directly, as it does today."
    exit 0
fi

if ! command -v python3 >/dev/null 2>&1; then
    log "WARN: python3 unavailable — skipping ledger write"
    exit 0
fi

printf '%s\n' "$RECORDS" | LEDGER="$LEDGER" python3 -c '
import json, sys, os, datetime
ledger = os.environ["LEDGER"]
entries = {}
for line in sys.stdin:
    line = line.rstrip("\n")
    if not line:
        continue
    container, image_id, digest = line.split("\t")
    entries[container] = {"imageId": image_id, "repoDigest": digest}
payload = {
    "recordedAt": datetime.datetime.now(datetime.timezone.utc)
        .strftime("%Y-%m-%dT%H:%M:%SZ"),
    "containers": entries,
}
tmp = ledger + ".partial"
with open(tmp, "w") as fh:
    json.dump(payload, fh, indent=2)
    fh.write("\n")
os.replace(tmp, ledger)
print(f"  recorded {len(entries)} container(s)")
' || {
    log "WARN: ledger write failed — the next release falls back to the direct read"
    exit 0
}

log "ledger written: ${LEDGER}"
