/**
 * Runs once, after both servers are up and before any spec.
 *
 * A first login is greeted by the one-time "set your home postcode" prompt, which holds the whole app
 * inert. It is per-USER server state, so with two parallel workers sharing the one local admin it would
 * show to whichever spec logged in first and to none after, which is order-dependent. Dismissing it once
 * here (idempotent) makes every spec see the same app. The prompt itself is covered by Vitest.
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
  const dismissed = await fetch(`${base}/api/user/settings/postcode-prompt-dismissed`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!dismissed.ok) throw new Error(`global setup: postcode prompt dismissal returned ${dismissed.status}`);
}
