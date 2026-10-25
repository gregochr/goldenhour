#!/usr/bin/env bash
# Behavioural check that an nginx config keeps query strings out of its access log.
#
#   scripts/nginx-log-canary.sh <nginx.conf> <api|static> <host-port> [security-headers.conf]
#   scripts/nginx-log-canary.sh --check-logs <stdout-file> <stderr-file>
#
# Runs the image that ships (nginx:alpine, not the 1.27 tag `nginx -t` uses) with the given config,
# sends requests whose query strings carry CANARY markers, and fails unless the container's access
# log (stdout) shows the paths and the Referer PATH but no marker, and its error log (stderr) says
# nothing about a missing asset. Exit code alone is the verdict.
#
# What each failure looks like, so a broken config cannot pass:
#   - access_log reverted to the default `combined`: stdout holds `/verify?token=CANARY1`      -> CANARY found
#   - a map that stops capturing, or a format that logs nothing: the Referer path is not there -> missing
#   - a map that blanks the whole value: same                                                  -> missing
#   - a config that does not parse: the container exits and the first request fails           -> curl fails
#   - `log_not_found off` removed from the asset location: stderr names missing.js            -> stderr hit
# The error log of /api/status/stream (api mode, 502 on purpose: nothing listens upstream) legitimately
# carries the request line; that is plan step 1's stated residual, so only missing.js is asserted there.
set -euo pipefail

# check_logs <stdout-file> <stderr-file>: the assertions, separate from docker so they can be tried on
# fixtures.
check_logs() {
  local out="$1" err="$2" ok=0
  if grep -q 'CANARY' "$out"; then
    echo "FAIL: the access log contains a CANARY marker (a query string was logged)" >&2
    ok=1
  fi
  if ! grep -qF '"GET /verify HTTP/' "$out"; then
    echo 'FAIL: the access log has no "GET /verify HTTP/" line (the request path must survive)' >&2
    ok=1
  fi
  if ! grep -qF '"http://localhost/verify"' "$out"; then
    echo 'FAIL: the access log has no "http://localhost/verify" referer (the referer path must survive)' >&2
    ok=1
  fi
  if grep -q 'missing\.js' "$err"; then
    echo "FAIL: the error log names missing.js (log_not_found off is not in effect)" >&2
    ok=1
  fi
  return "$ok"
}

if [ "${1:-}" = "--check-logs" ]; then
  check_logs "$2" "$3"
  exit $?
fi

CONF="$1"
MODE="$2"
PORT="$3"
SEC_HEADERS="${4:-}"

MOUNTS=(-v "$CONF:/etc/nginx/nginx.conf:ro")
if [ -n "$SEC_HEADERS" ]; then
  MOUNTS+=(-v "$SEC_HEADERS:/etc/nginx/security-headers.conf:ro")
fi

WORK="$(mktemp -d)"
CID=""
cleanup() {
  if [ -n "$CID" ]; then
    docker rm -f "$CID" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

CID="$(docker run -d -p "127.0.0.1:$PORT:80" "${MOUNTS[@]}" nginx:alpine)"
BASE="http://127.0.0.1:$PORT"

# Wait (up to ~10 s) for nginx to answer; a config that fails to parse never does.
ready=0
for _ in $(seq 1 20); do
  if curl -s -o /dev/null --max-time 2 "$BASE/"; then
    ready=1
    break
  fi
  sleep 0.5
done
if [ "$ready" -ne 1 ]; then
  echo "FAIL: nginx did not answer on $BASE; container log follows" >&2
  docker logs "$CID" >&2 || true
  exit 1
fi

curl -sS -o /dev/null --max-time 10 "$BASE/verify?token=CANARY1"
curl -sS -o /dev/null --max-time 10 "$BASE/reset?token=CANARY2"
if [ "$MODE" = "api" ]; then
  # 502 is expected and fine: there is no upstream in the container.
  curl -sS -o /dev/null --max-time 10 "$BASE/api/status/stream?token=CANARY3"
fi
curl -sS -o /dev/null --max-time 10 -H 'Referer: http://localhost/verify?token=CANARY4' "$BASE/"
curl -sS -o /dev/null --max-time 10 "$BASE/assets/missing.js"

# The log reaches `docker logs` asynchronously: wait (up to ~10 s) for the LAST request to appear.
seen=0
for _ in $(seq 1 20); do
  docker logs "$CID" 2>/dev/null >"$WORK/out.log" || true
  if grep -q 'missing\.js' "$WORK/out.log"; then
    seen=1
    break
  fi
  sleep 0.5
done
if [ "$seen" -ne 1 ]; then
  echo "FAIL: the access log never showed the last request" >&2
  cat "$WORK/out.log" >&2
  exit 1
fi
docker logs "$CID" 2>"$WORK/err.log" >/dev/null || true

if ! check_logs "$WORK/out.log" "$WORK/err.log"; then
  echo "--- access log (stdout) ---" >&2
  cat "$WORK/out.log" >&2
  echo "--- error log (stderr) ---" >&2
  cat "$WORK/err.log" >&2
  exit 1
fi
echo "OK: no query string reached the access log, and the paths survived"
