import fs from 'node:fs';
import path from 'node:path';
import { defineConfig, devices } from '@playwright/test';

/**
 * The E2E smoke gate (docs/engineering/engineering-gaps-plan.md, Phases 2 and 3a.1): a small Playwright
 * suite that boots the real backend (local profile, fresh H2 carrying the SEEDED FIXTURE, fake Anthropic key,
 * a dead outbound proxy) and the built frontend (`vite preview`), and is a REQUIRED CI check
 * ("E2E — Playwright smoke").
 *
 * Both servers start from here, so the recipe is the same locally and in CI:
 *   1. cd backend && ./mvnw -Plocal-dev -Dmaven.test.skip=true package
 *   2. cd frontend && npm run build
 *   3. cd frontend && npm run test:e2e:smoke
 *
 * Reliability rules (the plan's list): chromium only, pinned; workers fixed at 2; retries 0; the
 * service worker blocked; traces kept on failure; no clock freezing in this phase.
 */

// The ports are fixed in CI. Two checkouts running the suite on one machine would otherwise collide (and, with
// `reuseExistingServer`, each silently drive the OTHER's servers), so a local run may move them:
// E2E_API_PORT and E2E_WEB_PORT (the backend script reads the same two).
const API_PORT = Number(process.env.E2E_API_PORT || 8083);
const WEB_PORT = Number(process.env.E2E_WEB_PORT || 4173);
const LOG_DIR = path.resolve(process.env.E2E_LOG_DIR || 'test-results/e2e-logs');
fs.mkdirSync(LOG_DIR, { recursive: true });

// LOCAL ONLY: lets a machine whose preinstalled Chromium does not match the pinned Playwright run the
// suite. CI never sets it; it installs its own browser with `npx playwright install chromium`.
const localChromium = process.env.E2E_CHROMIUM_PATH
  ? { launchOptions: { executablePath: process.env.E2E_CHROMIUM_PATH } }
  : {};

export default defineConfig({
  testDir: './src/test/e2e/smoke',
  // Separate from the server logs in test-results/e2e-logs, which Playwright must not clear.
  outputDir: 'test-results/artifacts',
  globalSetup: './src/test/e2e/smoke/global-setup.js',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: 2,
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }]]
    : [['list']],
  use: {
    baseURL: `http://127.0.0.1:${WEB_PORT}`,
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'smoke',
      testIgnore: ['**/logout.spec.js', '**/roles-write.spec.js'],
      use: { ...devices['Desktop Chrome'], ...localChromium },
    },
    // Why roles-write is its own project: it is the only spec that WRITES shared server state (the Pro reader rates
    // one past window; the write invalidates the served-briefing memo for everyone). So it starts only after the
    // whole `smoke` project has finished and runs on one worker. It signs in as the `pro` account, never the
    // admin, so it has no sign-out hazard and logout need not wait for it, but it still runs before logout so
    // that "sign-out runs last" stays one rule. (The read-only role specs, roles.spec.js, run in `smoke`.)
    {
      name: 'roles-write',
      testMatch: '**/roles-write.spec.js',
      dependencies: ['smoke'],
      workers: 1,
      use: { ...devices['Desktop Chrome'], ...localChromium },
    },
    // Why logout is its own project: signing out calls bumpCredentialsVersion, which is ACCOUNT-WIDE, and
    // JwtAuthenticationFilter then rejects every token whose `cv` no longer matches. Every authenticated
    // spec shares the one local admin, so a sign-out running beside another spec would give it a 401 (a
    // hygiene failure). So it starts only after the whole `smoke` project has finished, on one worker, which
    // also serialises its repeats under --repeat-each.
    {
      name: 'logout',
      testMatch: '**/logout.spec.js',
      dependencies: ['smoke', 'roles-write'],
      workers: 1,
      use: { ...devices['Desktop Chrome'], ...localChromium },
    },
  ],
  webServer: [
    {
      command: 'bash scripts/e2e-smoke-backend.sh',
      // The container group only: db, ping and (smoke script only) e2eFixture, which stays DOWN until the
      // seeder has written and self-checked the fixture. The bare /actuator/health aggregates the
      // Open-Meteo, tide and Anthropic probes, which make real outbound calls and would tie the gate to
      // third-party uptime.
      url: `http://127.0.0.1:${API_PORT}/actuator/health/container`,
      timeout: 180_000,
      // Never reuse a backend in CI. Locally it is the developer's choice, but a reused backend
      // is not a fresh database, and the seeder refuses any database that already holds locations.
      reuseExistingServer: !process.env.CI,
      env: { E2E_LOG_DIR: LOG_DIR, E2E_API_PORT: String(API_PORT), E2E_WEB_PORT: String(WEB_PORT) },
    },
    {
      command: `npx vite preview --host 127.0.0.1 --port ${WEB_PORT} --strictPort > ${LOG_DIR}/preview.log 2>&1`,
      url: `http://127.0.0.1:${WEB_PORT}`,
      timeout: 60_000,
      reuseExistingServer: !process.env.CI,
      // `vite preview` inherits server.proxy; without this the proxy falls back to 8082 and every
      // API call returns 502.
      env: { VITE_API_TARGET: `http://127.0.0.1:${API_PORT}` },
    },
  ],
});
