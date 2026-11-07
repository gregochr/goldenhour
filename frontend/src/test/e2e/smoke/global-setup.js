import { USERS } from './fixture.js';

/**
 * Runs once, after both servers are up and before any spec.
 *
 * Per-USER server state would otherwise be order-dependent, because two workers run in parallel and the
 * accounts are shared. Each is written once here (idempotent), so every spec sees the same app:
 *
 *  - the one-time "set your home postcode" prompt, which holds the whole app inert on a first login (it would
 *    show to whichever spec logged in first and to none after). The prompt itself is covered by Vitest. The
 *    ADMIN's is dismissed here; `lite` and `pro` are inserted with it already dismissed
 *    (`E2eRoleUsers`, `postcodePromptDismissedAt`), so there is nothing to write for them;
 *  - the Coming up "seen" mark, for ALL THREE accounts, which would otherwise flip the tab's unseen badge under
 *    whichever spec happened to be the first to open that tab. Specs never assert the badge.
 *
 * `lite` has no first-choice sub-region prompt to dismiss: the seeder gives it its picks, so its standing is
 * `OPEN` (the prompt shows only at `FIRST_CHOICE`).
 */
export default async function globalSetup(config) {
  const base = config.projects[0].use.baseURL;
  for (const [role, { username, password }] of Object.entries(USERS)) {
    const login = await fetch(`${base}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, turnstileToken: 'e2e-token' }),
    });
    if (!login.ok) throw new Error(`global setup: ${role} login through the preview proxy returned ${login.status}`);
    const { accessToken } = await login.json();
    const headers = { Authorization: `Bearer ${accessToken}` };
    const paths = role === 'admin' ? ['postcode-prompt-dismissed', 'coming-up-seen'] : ['coming-up-seen'];
    for (const path of paths) {
      const response = await fetch(`${base}/api/user/settings/${path}`, { method: 'PUT', headers });
      if (!response.ok) throw new Error(`global setup: ${role} PUT ${path} returned ${response.status}`);
    }
  }
}
