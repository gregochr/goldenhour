#!/usr/bin/env bash
# Starts the smoke suite's web server in NGINX mode (E2E_WEB=nginx): the image that ships (nginx:alpine) serving the
# built frontend/dist through the PRODUCTION config, the repo-root nginx.conf, mounted unedited, exactly as
# docker-compose.yml mounts it. So the suite runs against the real CSP, the real security headers, the real caching
# rules, the real SPA fallback and the real /api/ proxy.
#
# The container runs on the HOST network (`--network host`, no `-p`). Never switch back to a published port on the
# default bridge: `docker run` there attaches a veth to docker0 (carrier up, IPv6 link-local, DAD) a moment AFTER the
# container answers, and Chromium's NetworkChangeNotifier watches the host's interfaces and aborts in-flight requests
# on a change (net::ERR_NETWORK_CHANGED on the first test of a run). Host networking creates no interface at all.
#
# Consequences: nginx listens on the host's port 80 (`listen 80` in the unedited config), so the web port in this mode
# IS 80 and any other E2E_WEB_PORT is refused; the config's upstream `goldenhour-backend:8082` resolves to 127.0.0.1
# through `--add-host`, so the backend must listen on 8082 (any other E2E_API_PORT is refused here too).
#
# Build first:  cd frontend && npm run build
# Playwright starts this from playwright.smoke.config.js (webServer) and stops it with SIGTERM (gracefulShutdown);
# see docs/engineering/reference/dev-and-testing.md, "The E2E smoke gate".
#
# Lifecycle: `docker run` is a background job that this script `wait`s on (never `exec`), so the trap below runs on
# EXIT, INT and TERM and removes the container. A container left by a hard kill is removed at the next start.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST="$HERE/../dist"
CONF="${E2E_NGINX_CONF:-$HERE/../../nginx.conf}"
LOG_DIR="${E2E_LOG_DIR:-$HERE/../test-results/e2e-logs}"
API_PORT="${E2E_API_PORT:-8082}"
WEB_PORT="${E2E_WEB_PORT:-80}"
NAME="goldenhour-e2e-nginx"
IMAGE="nginx:alpine"

if [ "$API_PORT" != "8082" ]; then
  echo "E2E_WEB=nginx needs E2E_API_PORT=8082 (nginx.conf's upstream is goldenhour-backend:8082 and the config is mounted unedited); got $API_PORT." >&2
  exit 1
fi
if [ "$WEB_PORT" != "80" ]; then
  echo "E2E_WEB=nginx needs E2E_WEB_PORT=80 (the container uses the host network and nginx.conf says listen 80, mounted unedited); got $WEB_PORT." >&2
  exit 1
fi
if [ ! -f "$DIST/index.html" ]; then
  echo "No built frontend at $DIST/index.html. Build it: cd frontend && npm run build" >&2
  exit 1
fi
if [ ! -f "$CONF" ]; then
  echo "No nginx config at $CONF." >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "The Docker daemon is not reachable; E2E_WEB=nginx needs it (unset E2E_WEB to use vite preview)." >&2
  exit 1
fi

# Port 80 must be free: with host networking nginx binds it directly. 0x50 = 80; state 0A = LISTEN.
if awk 'NR > 1 && $4 == "0A" { split($2, a, ":"); if (a[2] == "0050") found = 1 } END { exit !found }' \
  /proc/net/tcp /proc/net/tcp6 2>/dev/null; then
  echo "Port 80 is already in use on this host; E2E_WEB=nginx needs it (the container uses the host network)." >&2
  exit 1
fi

mkdir -p "$LOG_DIR"
DIST="$(cd "$DIST" && pwd)"
CONF="$(cd "$(dirname "$CONF")" && pwd)/$(basename "$CONF")"
# The config actually mounted. playwright.smoke.config.js sets the same variable for the workers, so the nginx spec
# reads the file that is mounted (a CI-only edited copy would stay consistent).
export E2E_NGINX_CONF="$CONF"

docker rm -f "$NAME" >/dev/null 2>&1 || true
cleanup() {
  trap - EXIT INT TERM
  docker rm -f "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

# nginx's access and error logs are symlinks to stdout and stderr in this image, so one file carries both.
docker run --rm --name "$NAME" \
  --network host \
  --add-host=goldenhour-backend:127.0.0.1 \
  -v "$DIST:/usr/share/nginx/html:ro" \
  -v "$CONF:/etc/nginx/nginx.conf:ro" \
  "$IMAGE" >"$LOG_DIR/nginx.log" 2>&1 &
wait $!
