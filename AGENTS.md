# AGENTS.md

Monorepo: Spring Boot 4 backend (`backend/`) + React 19/Vite frontend
(`frontend/`) for a sunrise/sunset colour-forecasting app.

- Reviewing or changing Java? Read `backend/AGENTS.md` first — it lists the
  deliberate decisions that look like bugs, and the ones that were bugs and
  must not come back.
- Frontend CI is lint → Vitest → `npm audit --audit-level=high` → build; the
  audit step is the one nothing local runs by default.
- Conventional commits (`feat:`, `fix:`, `chore:`, …). Never commit
  `application.yml` (only `application-example.yml` is committed).

## Code Review Rules

Report prioritized P0/P1 findings with `file:line` references. Style is gated
by Checkstyle/ESLint in CI — don't spend findings on it. When a construct looks
wrong but is listed as deliberate in `backend/AGENTS.md` or `CLAUDE.md`, it
needs cited evidence to be reported as a defect.
