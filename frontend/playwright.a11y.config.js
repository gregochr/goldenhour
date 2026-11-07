import { defineConfig, devices } from '@playwright/test';
import smoke from './playwright.smoke.config.js';

/**
 * The advisory accessibility scan (docs/engineering/engineering-gaps-plan.md, Phase 3c): `@axe-core/playwright` over
 * each main tab and the open dialogs, at desktop and phone widths, on the same seeded stack as the smoke gate.
 *
 * It imports the smoke config and overrides ONLY `testDir`, `outputDir`, `reporter` and `projects`, so the web servers
 * (including the `E2E_WEB=nginx` mode), `globalSetup`, workers, `retries: 0` and the service-worker block are the
 * smoke gate's own. `playwright.smoke.config.js` is not edited and its `testDir` never sees these specs.
 *
 *   cd frontend && E2E_WEB=nginx E2E_API_PORT=8082 npm run test:e2e:a11y     (CI; needs Docker)
 *   cd frontend && npm run test:e2e:a11y                                      (local; vite preview, API on 8083)
 */

// LOCAL ONLY, as in the smoke config: a preinstalled Chromium that does not match the pinned Playwright.
const localChromium = process.env.E2E_CHROMIUM_PATH
  ? { launchOptions: { executablePath: process.env.E2E_CHROMIUM_PATH } }
  : {};

export default defineConfig({
  ...smoke,
  testDir: './src/test/e2e/a11y',
  // Not `test-results/a11y`: Playwright clears its outputDir at the start of a run, and that directory holds the
  // per-scan records (and not `test-results/e2e-logs`, which the servers write into).
  outputDir: 'test-results/a11y-artifacts',
  reporter: process.env.CI
    ? [['list'], ['html', { open: 'never' }]]
    : [['list']],
  projects: [
    // `reducedMotion: 'reduce'` keeps transitions from being caught mid-flight (determinism). `bypassCSP` is never set:
    // the 3b nginx CSP must stay in force while axe injects (the spec asserts it).
    // Both are read-only and sign in as the admin, so the smoke gate's sign-out and write hazards do not apply.
    {
      name: 'a11y-desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 }, reducedMotion: 'reduce', ...localChromium },
    },
    // A Pixel 7 is Chromium, so no second browser is installed. Its 412 px width is under the app's phone
    // breakpoint (`useIsMobile`, 639 px), so the phone-only surfaces (the Map's peek sheet) render.
    {
      name: 'a11y-phone',
      use: { ...devices['Pixel 7'], reducedMotion: 'reduce', ...localChromium },
    },
  ],
});
