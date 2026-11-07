import crypto from 'node:crypto';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { test, expect, loginAndWaitForForecast, openTab, openMapTab, tokenOf } from './fixture.js';

/**
 * The production nginx config under a real browser (docs/engineering/engineering-gaps-plan.md, Phase 3b).
 *
 * With `E2E_WEB=nginx` (CI sets it) the built frontend is served by `nginx:alpine` through the repo-root
 * `nginx.conf`, mounted unedited, in front of the real backend. So the WHOLE suite is a CSP test (a blocked
 * script or style logs a console error, which the hygiene fixture fails on), and this file adds what only the
 * proxy can show: the headers on the wire, the caching rules, the SPA fallback and the SSE stream.
 *
 * Every expectation is READ FROM THE CONFIG FILE that the container mounted (`E2E_NGINX_CONF`, set by
 * playwright.smoke.config.js and the script), so the spec cannot drift from it, and each parse asserts it
 * matched: an empty match would otherwise pass vacuously.
 *
 * Without `E2E_WEB=nginx` every test here skips: a `vite preview` run sends none of these headers.
 * Not covered: `worker-src` (the suite blocks service workers).
 */
const IN_NGINX_MODE = process.env.E2E_WEB === 'nginx';
const SKIP_REASON = 'needs E2E_WEB=nginx: vite preview does not serve the production nginx.conf';

/** The seven names `location /` must send on every response (the add_header ... always lines). */
const SECURITY_HEADERS = [
  'X-Content-Type-Options',
  'X-Frame-Options',
  'Referrer-Policy',
  'Permissions-Policy',
  'Cross-Origin-Opener-Policy',
  'Content-Security-Policy',
  'Cross-Origin-Resource-Policy',
];

/** The built shell, read from disk: the SPA-fallback body and the nomodule hash are checked against it. */
const INDEX_HTML = fs.readFileSync(fileURLToPath(new URL('../../../../dist/index.html', import.meta.url)), 'utf8');

/** The first hashed bundle the shell loads, e.g. /assets/index-AbC123.js. */
const ASSET_PATH = /src="(\/assets\/[^"]+\.js)"/.exec(INDEX_HTML)?.[1];

/** The longest the first status event may take through the proxy. Measured at about 20 ms; a buffered stream waits for the 30 s heartbeat. */
const STATUS_STREAM_FIRST_EVENT_MS = 8_000;

/**
 * Parses what the spec expects out of the mounted config: the `$csp` string and the `add_header ... always`
 * lines of `location /`. Throws (so the spec fails loudly) if either does not match.
 *
 * @returns {{csp: string, headers: Map<string, string>}}
 */
function readConfig() {
  const confPath = process.env.E2E_NGINX_CONF;
  if (!confPath) throw new Error('E2E_NGINX_CONF is not set: the nginx webServer did not export the mounted config');
  const conf = fs.readFileSync(confPath, 'utf8');
  const cspMatch = /set \$csp "([^"]+)";/.exec(conf);
  expect(cspMatch, `a set $csp "..."; line in ${confPath}`).not.toBeNull();
  const csp = cspMatch[1];
  const block = /\n\s*location \/ \{([\s\S]*?)\n\s*\}/.exec(conf);
  expect(block, `the "location / {" block in ${confPath}`).not.toBeNull();
  const headers = new Map();
  for (const m of block[1].matchAll(/add_header\s+(\S+)\s+(?:"([^"]*)"|(\$csp))\s+always;/g)) {
    headers.set(m[1], m[3] ? csp : m[2]);
  }
  expect([...headers.keys()], 'the always-sent headers parsed from location /').toEqual(
    expect.arrayContaining(SECURITY_HEADERS),
  );
  return { csp, headers };
}

/** All values of one response header, by case-insensitive name (duplicate-aware, unlike `headers()`). */
async function valuesOf(response, name) {
  // `await` because a page navigation's Response answers asynchronously and an APIResponse synchronously.
  return (await response.headersArray()).filter((h) => h.name.toLowerCase() === name.toLowerCase()).map((h) => h.value);
}

/** Asserts each security header is present exactly once, with the value the config states. */
async function expectSecurityHeaders(response, expected, what) {
  for (const name of SECURITY_HEADERS) {
    expect(await valuesOf(response, name), `${what}: ${name}`).toEqual([expected.headers.get(name)]);
  }
}

test.describe('The production nginx config', () => {
  test.skip(!IN_NGINX_MODE, SKIP_REASON);

  test('mode guard: the document carries the CSP from nginx.conf (a fallback to vite preview fails here)', async ({ page }) => {
    const { csp } = readConfig();
    const response = await page.goto('/');
    expect(await valuesOf(response, 'Content-Security-Policy')).toEqual([csp]);
    expect((await valuesOf(response, 'Server'))[0], 'nginx answers, and server_tokens is off').toBe('nginx');
  });

  test('the seven security headers are on the shell, a hashed asset and a deep link, each once', async ({ page }) => {
    expect(ASSET_PATH, 'a hashed /assets/*.js bundle in dist/index.html').toBeTruthy();
    const expected = readConfig();
    for (const path of ['/', ASSET_PATH, '/plan/anything']) {
      const response = await page.request.get(path);
      expect(response.status(), `GET ${path}`).toBe(200);
      await expectSecurityHeaders(response, expected, path);
    }
  });

  test('caching: the shell and a deep link revalidate, hashed assets are public, immutable and expire far off', async ({ page }) => {
    for (const path of ['/', '/plan/anything']) {
      const response = await page.request.get(path);
      expect(await valuesOf(response, 'Cache-Control'), `${path} Cache-Control`).toEqual(['no-cache']);
    }
    const asset = await page.request.get(ASSET_PATH);
    // `expires 1y` adds a max-age Cache-Control beside the explicit one, so there are two headers.
    const cacheControl = (await valuesOf(asset, 'Cache-Control')).join(', ');
    expect(cacheControl).toContain('public');
    expect(cacheControl).toContain('immutable');
    const [expires] = await valuesOf(asset, 'Expires');
    expect(expires, 'an Expires header on a hashed asset').toBeTruthy();
    const days = (Date.parse(expires) - Date.now()) / 86_400_000;
    expect(days, 'Expires is about a year away').toBeGreaterThan(300);
  });

  test('SPA fallback: a deep link that is not a file answers 200 with the shell, and the app boots on it', async ({ page }) => {
    // nginx's try_files only: the app has no client router.
    const response = await page.goto('/plan/anything');
    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toContain('text/html');
    expect(await response.text()).toBe(INDEX_HTML);
    await expect(page.getByTestId('login-username')).toBeVisible();
  });

  test('a missing asset is a 404 that still carries the security headers', async ({ page }) => {
    const expected = readConfig();
    const response = await page.request.get('/assets/missing.js');
    expect(response.status()).toBe(404);
    await expectSecurityHeaders(response, expected, '/assets/missing.js');
  });

  test('the legacy-browser notice\'s inline script is allowed by the CSP hash in nginx.conf', async () => {
    // Chromium skips <script nomodule> before CSP is consulted, so a stale hash would log nothing in a browser.
    const { csp } = readConfig();
    const body = /<script nomodule>([\s\S]*?)<\/script>/.exec(INDEX_HTML);
    expect(body, 'a <script nomodule> block in dist/index.html').not.toBeNull();
    const hash = crypto.createHash('sha256').update(body[1]).digest('base64');
    expect(csp).toContain(`'sha256-${hash}'`);
  });

  test('the status stream delivers its first event through the proxy promptly (no buffering)', async ({ page }) => {
    await loginAndWaitForForecast(page);
    const token = await tokenOf(page);
    const result = await page.evaluate(async ({ bearer, limitMs }) => {
      const started = performance.now();
      const controller = new AbortController();
      const timer = setTimeout(() => controller.abort(), limitMs);
      try {
        const res = await fetch('/api/status/stream', {
          headers: { Authorization: `Bearer ${bearer}` },
          signal: controller.signal,
        });
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let seen = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) return { status: res.status, ended: true, seen };
          seen += decoder.decode(value, { stream: true });
          if (/^event: ?status$/m.test(seen)) {
            reader.cancel();
            return { status: res.status, contentType: res.headers.get('content-type'), ms: performance.now() - started };
          }
        }
      } catch (e) {
        return { status: 0, error: String(e), ms: performance.now() - started };
      } finally {
        clearTimeout(timer);
      }
    }, { bearer: token, limitMs: STATUS_STREAM_FIRST_EVENT_MS });
    expect(result.error, 'the stream timed out: nginx is holding its first event').toBeUndefined();
    expect(result.status).toBe(200);
    expect(result.contentType).toContain('text/event-stream');
    expect(result.ms).toBeLessThan(STATUS_STREAM_FIRST_EVENT_MS);
  });

  test('no inline <style> element appears on any main tab or in the window sheet (style-src is self only)', async ({ page }) => {
    const styleCount = () => page.evaluate(() => document.querySelectorAll('style').length);
    await loginAndWaitForForecast(page);
    expect(await styleCount(), 'on sign-in').toBe(0);
    await openTab(page, 'plan');
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
    expect(await styleCount(), 'on the Plan').toBe(0);
    await page.getByTestId('wf-heat-card').nth(2).click();
    await expect(page.getByTestId('window-sheet')).toBeVisible();
    expect(await styleCount(), 'with the window sheet open').toBe(0);
    await page.getByTestId('window-sheet-close').click();
    await expect(page.getByTestId('window-sheet')).toHaveCount(0);
    await openTab(page, 'coming-up');
    expect(await styleCount(), 'on Coming up').toBe(0);
    await openMapTab(page);
    expect(await styleCount(), 'on the Map').toBe(0);
    await openTab(page, 'operations');
    expect(await styleCount(), 'on Operations').toBe(0);
  });
});
