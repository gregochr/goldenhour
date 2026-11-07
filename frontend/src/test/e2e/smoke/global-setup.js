/**
 * Runs once, after both servers are up and before any spec.
 *
 * Two pieces of per-USER server state would otherwise be order-dependent, because every spec shares the one
 * local admin and two workers run in parallel. Both are written once here (idempotent), so every spec sees
 * the same app:
 *
 *  - the one-time "set your home postcode" prompt, which holds the whole app inert on a first login (it would
 *    show to whichever spec logged in first and to none after). The prompt itself is covered by Vitest;
 *  - the Coming up "seen" mark, which would otherwise flip the tab's unseen badge under whichever spec
 *    happened to be the first to open that tab. Specs never assert the badge.
 */
export default async function globalSetup(config) {
  const base = config.projects[0].use.baseURL;
  const login = await fetch(`${base}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'golden2026', turnstileToken: 'e2e-token' }),
  });
  if (!login.ok) throw new Error(`global setup: admin login through the preview proxy returned ${login.status}`);
  const { accessToken } = await login.json();
  const headers = { Authorization: `Bearer ${accessToken}` };
  for (const path of ['postcode-prompt-dismissed', 'coming-up-seen']) {
    const response = await fetch(`${base}/api/user/settings/${path}`, { method: 'PUT', headers });
    if (!response.ok) throw new Error(`global setup: PUT ${path} returned ${response.status}`);
  }
}
