import { test as base, expect } from '@playwright/test';

/**
 * The smoke suite's shared fixture (docs/engineering/engineering-gaps-plan.md, Phase 2).
 *
 * Every test gets, automatically:
 *  - third parties FULFILLED, never aborted (an aborted request logs {@code net::ERR_FAILED} as a console
 *    error): the ArcGIS basemap tiles with a real 1x1 PNG, and the Turnstile script with a stub that
 *    verifies at once (the widget otherwise waits 10 s before the submit button enables);
 *  - hygiene recording: console messages of type {@code error}, {@code pageerror} events, responses with
 *    status >= 400 from the app's own origin, and every off-origin request that is not one of the two
 *    stubs above. Each test ends by asserting there were none, apart from the named {@link ALLOW_LIST}.
 *
 * {@code E2E_HYGIENE=record} (local discovery only) prints the findings instead of asserting them.
 */

/** A 1x1 transparent PNG, so the tile layer decodes a real image. */
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

const TURNSTILE_STUB = `window.turnstile = {
  render: function (el, o) { o.callback('e2e-token'); return 'w1'; },
  remove: function () {},
};`;

/**
 * The named allow-list: findings that are known, with the reason each is tolerated and a pointer to its
 * fix. Each entry matches on {@code kind} ('console' | 'pageerror' | 'response' | 'offorigin') and a
 * {@code match} RegExp tested against the finding's text. An entry with no follow-up has no business here.
 */
export const ALLOW_LIST = [];

const RECORD_ONLY = process.env.E2E_HYGIENE === 'record';

function isAllowed(finding, extra) {
  return [...ALLOW_LIST, ...extra].some((entry) => entry.kind === finding.kind && entry.match.test(finding.text));
}

/**
 * Signs in as the local admin through the PREVIEW origin (so the dev proxy is exercised and no backend
 * port is hard-coded), then seeds the keys the app boots from.
 *
 * @param {import('@playwright/test').Page} page the page to sign in
 */
export async function loginAsAdmin(page) {
  const response = await page.request.post('/api/auth/login', {
    data: { username: 'admin', password: 'golden2026', turnstileToken: 'e2e-token' },
  });
  expect(response.status(), 'admin login through the preview proxy').toBe(200);
  const { accessToken, refreshToken, refreshExpiresAt, username } = await response.json();
  await page.goto('/');
  await page.evaluate(
    ({ token, refresh, refreshExpires, user }) => {
      localStorage.setItem('goldenhour_token', token);
      localStorage.setItem('goldenhour_refresh', refresh);
      localStorage.setItem('goldenhour_role', 'ADMIN');
      localStorage.setItem('goldenhour_username', user);
      localStorage.setItem('goldenhour_refresh_expires', refreshExpires);
    },
    { token: accessToken, refresh: refreshToken, refreshExpires: refreshExpiresAt, user: username },
  );
  await page.reload();
}

export const test = base.extend({
  hygiene: [
    async ({ page, baseURL }, use, testInfo) => {
      const origin = new URL(baseURL).origin;
      const findings = [];
      // Per-test expectations: a test that provokes an error on purpose (a wrong password) names it
      // here, with the reason, so the allowance cannot leak into any other test.
      const extra = [];
      findings.expect = (entry) => extra.push(entry);
      const tilePrefix = 'https://server.arcgisonline.com/';
      const turnstileUrl = 'https://challenges.cloudflare.com/turnstile/v0/api.js';

      await page.route(`${tilePrefix}**`, (route) =>
        route.fulfill({ status: 200, contentType: 'image/png', body: PNG_1X1 }),
      );
      await page.route(turnstileUrl, (route) =>
        route.fulfill({ status: 200, contentType: 'application/javascript', body: TURNSTILE_STUB }),
      );

      page.on('console', (msg) => {
        if (msg.type() === 'error') {
          findings.push({ kind: 'console', text: `${msg.text()} @ ${msg.location().url}` });
        }
      });
      page.on('pageerror', (err) => findings.push({ kind: 'pageerror', text: String(err) }));
      page.on('response', (res) => {
        const url = res.url();
        if (url.startsWith(origin) && res.status() >= 400) {
          findings.push({ kind: 'response', text: `${res.status()} ${res.request().method()} ${url}` });
        }
      });
      page.on('request', (req) => {
        const url = req.url();
        if (/^(data|blob|about):/.test(url) || url.startsWith(origin)) return;
        if (url.startsWith(tilePrefix) || url === turnstileUrl) return;
        findings.push({ kind: 'offorigin', text: `${req.method()} ${url}` });
      });

      await use(findings);

      const unexpected = findings.filter((f) => !isAllowed(f, extra));
      if (RECORD_ONLY) {
        for (const f of unexpected) {
          console.log(`[hygiene] ${testInfo.title} :: ${f.kind} :: ${f.text}`);
        }
        return;
      }
      expect(
        unexpected.map((f) => `${f.kind}: ${f.text}`),
        'console errors, page errors, non-2xx responses and unexpected off-origin requests',
      ).toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };
