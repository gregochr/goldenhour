# Golden Hour — Developer Scratchpad

## Ports

| Service | Port |
|---------|------|
| Backend (Spring Boot) | 8082 |
| Frontend (Vite dev) | 5173 |

Frontend proxy target defaults to `http://localhost:8082` in `vite.config.js`.

## Kill Processes

```bash
# Kill backend (Spring Boot — local dev on 8082, Docker prod on 8082)
lsof -ti:8082 | xargs kill -9

# Kill frontend (Vite dev server on port 5173)
lsof -ti:5173 | xargs kill -9

# Kill both
lsof -ti:8082 | xargs kill -9; lsof -ti:5173 | xargs kill -9
```

## Start Services

```bash
# Start backend (from backend directory, with local H2 profile — port 8082)
cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Start frontend (from frontend directory)
cd frontend && npm run dev
```

## Run Tests

```bash
# Backend: all tests + coverage check
cd backend && ./mvnw clean verify

# Backend: tests only (no coverage)
cd backend && ./mvnw test

# Frontend: unit tests (Vitest)
cd frontend && npm test

# Frontend: E2E tests (Playwright) — requires backend + frontend running
cd frontend && npm run test:e2e

# Frontend: E2E with UI (opens headed browser)
cd frontend && npx playwright test --headed

# All tests (run sequentially in separate terminals)
# Terminal 1: backend tests
cd backend && ./mvnw clean verify
# Terminal 2: frontend tests
cd frontend && npm test
# Terminal 3: E2E tests (after starting backend + frontend in separate terminals)
cd frontend && npm run test:e2e
```

## Database

```bash
# Reset local H2 database (delete files)
rm backend/data/goldenhour.mv.db backend/data/goldenhour.lock.db

# Access H2 console while backend is running (local dev)
# URL: http://localhost:8082/h2-console
# JDBC URL: jdbc:h2:file:./data/goldenhour
# User: sa
# Password: (empty)
```

## API Testing

```bash
# Login and get tokens
curl -s -X POST http://127.0.0.1:8082/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"golden2026"}' | python3 -m json.tool

# Get forecast (requires token from above)
TOKEN=eyJ... # paste from login response
curl -s http://127.0.0.1:8082/api/forecast \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Trigger forecast run
curl -s -X POST http://127.0.0.1:8082/api/forecast/run \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool

# Get all locations
curl -s http://127.0.0.1:8082/api/locations \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

## Linting & Code Quality

```bash
# Backend: checkstyle + SpotBugs
cd backend && ./mvnw clean verify

# Frontend: ESLint check
cd frontend && npm run lint

# Frontend: ESLint fix (auto-correct)
cd frontend && npm run lint -- --fix
```

## Build & Preview

```bash
# Frontend: build production bundle
cd frontend && npm run build

# Frontend: serve production build locally (port 4173)
cd frontend && npm run preview
```

## Git Workflow

```bash
# View recent commits
git log --oneline -10

# Amend last commit (before pushing)
git commit --amend --no-edit

# Reset unstaged changes
git checkout .

# Reset local commits (keep changes, undo commits)
git reset --soft HEAD~1

# Hard reset to remote (discard all local changes)
git reset --hard origin/main
```

## Useful URLs (when services are running)

- Frontend: http://localhost:5173
- Backend API: http://127.0.0.1:8082
- H2 Console: http://localhost:8082/h2-console
- Spring Actuator Health: http://localhost:8082/actuator/health
- Playwright HTML Report: `frontend/test-results/` (after running E2E tests)
- **Production frontend**: https://app.photocast.online
- **Production API**: https://api.photocast.online

## Docker (Production)

```bash
# Build and start both containers
docker compose build --no-cache
docker compose up -d

# Check status
docker compose ps
docker logs goldenhour-backend --tail 50
docker logs goldenhour-frontend --tail 20

# Restart after code changes
docker compose build --no-cache && docker compose up -d

# Health check
curl http://localhost:8082/actuator/health
```

Data is persisted at `/Users/gregochr/goldenhour-data/goldenhour.mv.db`.

## Cloudflare Tunnel

```bash
# Check the tunnel service is running
sudo launchctl list | grep cloudflare   # should show a PID

# View tunnel logs
tail -f /Library/Logs/com.cloudflare.cloudflared.out.log
tail -f /Library/Logs/com.cloudflare.cloudflared.err.log

# Restart the tunnel service
sudo launchctl stop com.cloudflare.cloudflared
sudo launchctl start com.cloudflare.cloudflared

# Tunnel details
# Name: photocast
# ID:   714c39a4-880f-44d7-a6e9-0a339b5224ac
# Config: ~/.cloudflared/config.yml
```

## Quick Restart Both Services

```bash
# In one command (foreground backend, background frontend)
lsof -ti:8082 | xargs kill -9 2>/dev/null; \
lsof -ti:5173 | xargs kill -9 2>/dev/null; \
sleep 1 && \
(cd frontend && npm run dev > /tmp/frontend.log 2>&1 &) && \
sleep 2 && \
(cd backend && export ANTHROPIC_API_KEY=sk-ant-...; ./mvnw spring-boot:run -Dspring-boot.run.profiles=local)
```

## Default Credentials

- **Username**: `admin`
- **Password**: `golden2026`
- **JWT Access Token Expiry**: 24 hours
- **JWT Refresh Token Expiry**: 30 days

## Environment Variables

```bash
# Required for backend
export ANTHROPIC_API_KEY=sk-ant-...

# Optional (defaults in application-local.yml)
export JWT_SECRET=bG9jYWwtZGV2LXNlY3JldC1rZXktZm9yLXRlc3Rpbmctb25seQ==
```
