import { defineConfig, devices } from '@playwright/test';

// Configurable via PLAYWRIGHT_BASE_URL so a spec can point at a dev server on a non-default port
// (doors plan §3 D4 task 5 — the local verification recipe runs the backend and vite on ports
// other than 8083/5173 to stay clear of the pane supervisor's own launch.json instance). Defaults
// to the app's ordinary port so every other spec in this directory is unaffected.
const baseURL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5173';

export default defineConfig({
  testDir: './src/test/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
  use: {
    baseURL,
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: 'npm run dev',
    url: baseURL,
    // Unchanged from the file's own original semantics (`!process.env.CI`) — only `baseURL`/
    // `url` needed to become configurable for the local recipe's own port. Locally this is
    // already `true`, which is what lets the spec reuse a vite already pointed at the seeded
    // backend via `VITE_API_TARGET` rather than racing it with a second `npm run dev` bound to
    // the default port; hardcoding it unconditionally would have been a wider change than the
    // task needed, and CI never runs this config to notice either way.
    reuseExistingServer: !process.env.CI,
  },
});
