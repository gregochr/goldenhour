import { test as base, expect } from '@playwright/test';

/**
 * The smoke suite's shared fixture (docs/engineering/engineering-gaps-plan.md, Phases 2 and 3a.1).
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
 *
 * The backend carries the SEEDED FIXTURE (`E2eLocalFixtureSeeder`): 3 regions x 6 places, rated for
 * T-3..T+4, with a BEST pick and no network call. The helpers below read the served `/api/forecast` as the
 * ORACLE for every star a spec asserts (never a re-derived number), and no spec leans on the first window,
 * so a run that crosses a sunrise or sunset still passes.
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

const TILE_PREFIX = 'https://server.arcgisonline.com/';

function isAllowed(finding, extra) {
  return [...ALLOW_LIST, ...extra].some((entry) => entry.kind === finding.kind && entry.match.test(finding.text));
}

/**
 * The seeded accounts (`LocalAdminSeeder` for the admin, `E2eRoleUsers` for the other two; Phase 3a.2). The
 * passwords are fixed here and in the seeder; a drift fails the login in global setup.
 */
export const USERS = {
  admin: { username: 'admin', password: 'golden2026' },
  lite: { username: 'lite', password: 'E2eLite#Pass1' },
  pro: { username: 'pro', password: 'E2ePro#Pass1' },
};

/**
 * Signs in as one of the seeded accounts through the PREVIEW origin (so the dev proxy is exercised and no
 * backend port is hard-coded), then seeds the keys the app boots from.
 *
 * @param {import('@playwright/test').Page} page the page to sign in
 * @param {'admin'|'lite'|'pro'} [role] which seeded account
 */
export async function loginAs(page, role = 'admin') {
  const { username: name, password } = USERS[role];
  const response = await page.request.post('/api/auth/login', {
    data: { username: name, password, turnstileToken: 'e2e-token' },
  });
  expect(response.status(), `${role} login through the preview proxy`).toBe(200);
  const { accessToken, refreshToken, refreshExpiresAt, username, role: serverRole } = await response.json();
  await page.goto('/');
  await page.evaluate(
    ({ token, refresh, refreshExpires, user, userRole }) => {
      localStorage.setItem('goldenhour_token', token);
      localStorage.setItem('goldenhour_refresh', refresh);
      localStorage.setItem('goldenhour_role', userRole);
      localStorage.setItem('goldenhour_username', user);
      localStorage.setItem('goldenhour_refresh_expires', refreshExpires);
    },
    { token: accessToken, refresh: refreshToken, refreshExpires: refreshExpiresAt, user: username, userRole: serverRole },
  );
  await page.reload();
}

/**
 * Signs in as the local admin (the default for every spec outside `roles.spec.js`).
 *
 * @param {import('@playwright/test').Page} page the page to sign in
 */
export function loginAsAdmin(page) {
  return loginAs(page, 'admin');
}

export const test = base.extend({
  // How many basemap tile requests the page has made (each is fulfilled by the stub below). A spec polls it
  // with expect.poll rather than waitForRequest, which could arm after the request had already gone.
  tiles: async ({ page }, use) => {
    const counter = { count: 0 };
    page.on('request', (req) => {
      if (req.url().startsWith(TILE_PREFIX)) counter.count += 1;
    });
    // Playwright's fixture callback is named `use`, which the hooks rule mistakes for React's.
    // eslint-disable-next-line react-hooks/rules-of-hooks
    await use(counter);
  },
  hygiene: [
    async ({ page, baseURL, tiles }, use, testInfo) => {
      void tiles; // the tile counter listens from the very start of every test
      const origin = new URL(baseURL).origin;
      const findings = [];
      // Per-test expectations: a test that provokes an error on purpose (a wrong password) names it
      // here, with the reason, so the allowance cannot leak into any other test.
      const extra = [];
      findings.expect = (entry) => extra.push(entry);
      const turnstileUrl = 'https://challenges.cloudflare.com/turnstile/v0/api.js';

      await page.route(`${TILE_PREFIX}**`, (route) =>
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
        if (url.startsWith(TILE_PREFIX) || url === turnstileUrl) return;
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

// ---------------------------------------------------------------------------------------------------------
// Helpers shared by the seeded journeys
// ---------------------------------------------------------------------------------------------------------

/** A chip's accessible name is "{name}, {n} star" plus an optional ", ..." clause. Seeded names carry no ", ". */
export const CHIP_NAME = /^(.+?), (\d) star(?:,|$)/;

/** A Lite reader's chip for a place outside their picks: "{name}, outside your sub-regions" (no star). */
export const OUTSIDE_CHIP = /^(.+?), outside your sub-regions$/;

/** A solar window row id: `solar:YYYY-MM-DD:SUNRISE|SUNSET`. */
export const SOLAR_ID = /^solar:(\d{4}-\d\d-\d\d):(SUNRISE|SUNSET)$/;

/**
 * Installs the dialog-depth observer BEFORE the page loads. A polling check would miss a transient third
 * dialog; a MutationObserver sees every DOM change. It records the maximum concurrent `.app-safe-modal`
 * dialogs and the maximum concurrent `aria-modal="true"` elements, and the current counts.
 *
 * @param {import('@playwright/test').Page} page
 */
export async function installDialogDepthObserver(page) {
  await page.addInitScript(() => {
    const depth = { maxDialogs: 0, maxModal: 0, nowDialogs: 0, nowModal: 0 };
    window.__dialogDepth = depth;
    const measure = () => {
      depth.nowDialogs = document.querySelectorAll('.app-safe-modal[role="dialog"]').length;
      depth.nowModal = document.querySelectorAll('[aria-modal="true"]').length;
      depth.maxDialogs = Math.max(depth.maxDialogs, depth.nowDialogs);
      depth.maxModal = Math.max(depth.maxModal, depth.nowModal);
    };
    new MutationObserver(measure).observe(document, {
      subtree: true, childList: true, attributes: true, attributeFilter: ['aria-modal', 'role', 'class'],
    });
  });
}

/**
 * The dialog counts the observer has recorded so far.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<{maxDialogs: number, maxModal: number, nowDialogs: number, nowModal: number}>}
 */
export function dialogDepth(page) {
  return page.evaluate(() => ({ ...window.__dialogDepth }));
}

/**
 * The signed-in reader's bearer token, from the keys the login seeds.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<string>}
 */
export function tokenOf(page) {
  return page.evaluate(() => localStorage.getItem('goldenhour_token'));
}

/**
 * The served forecast: `/api/forecast` (T-2..T+4), or one older day (`?date=`). Always the oracle for a star.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} [date] an ISO date for the older-day read
 * @returns {Promise<Array<object>>}
 */
export async function fetchForecast(page, date) {
  const token = await tokenOf(page);
  const path = date ? `/api/forecast?date=${date}` : '/api/forecast';
  const response = await page.request.get(path, { headers: { Authorization: `Bearer ${token}` } });
  expect(response.status(), `GET ${path}`).toBe(200);
  return response.json();
}

/**
 * The served rows of one window, from a forecast read.
 *
 * @param {Array<object>} rows `fetchForecast`'s rows
 * @param {string} evId a `solar:DATE:TYPE` id
 * @returns {Array<object>}
 */
export function rowsOf(rows, evId) {
  const [, date, type] = SOLAR_ID.exec(evId);
  return rows.filter((row) => row.targetDate === date && row.targetType === type);
}

/**
 * The served stars of one window, by place name.
 *
 * @param {Array<object>} rows `fetchForecast`'s rows
 * @param {string} evId a `solar:DATE:TYPE` id
 * @returns {Map<string, number>}
 */
export function starsOf(rows, evId) {
  const stars = new Map();
  for (const row of rowsOf(rows, evId)) {
    if (row.rating != null) stars.set(row.locationName, row.rating);
  }
  return stars;
}

/**
 * Selects a tab by its id and waits for it to read as selected.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} id 'plan' | 'coming-up' | 'map' | 'operations'
 */
export async function openTab(page, id) {
  const tab = page.getByTestId(`window-first-tab-${id}`);
  await tab.click();
  await expect(tab).toHaveAttribute('aria-selected', 'true');
}

/**
 * Signs in, waits for the shell, and waits for the Map tab (it is offered only once the forecast has arrived).
 *
 * @param {import('@playwright/test').Page} page
 * @param {'admin'|'lite'|'pro'} [role] which seeded account
 */
export async function loginAndWaitForForecast(page, role = 'admin') {
  await loginAs(page, role);
  await expect(page.getByTestId('window-first-shell')).toBeVisible();
  await expect(page.getByTestId('window-first-tab-map')).toBeVisible();
}

/** The chips a reader can see now (hidden and culled chips stay in the DOM but not in the role tree). */
export function visibleChips(page) {
  return page.getByRole('button', { name: CHIP_NAME });
}

/**
 * Opens the Map tab and waits until its label layer has measured and placed its chips (`data-placed`), the
 * fonts have loaded and at least one chip is visible, so no spec reads a position mid-placement.
 *
 * @param {import('@playwright/test').Page} page
 */
export async function openMapTab(page) {
  await openTab(page, 'map');
  await expect(page.locator('[data-testid="map-labels"][data-placed="true"]')).toHaveCount(1);
  await page.evaluate(() => document.fonts.ready);
  await expect.poll(() => visibleChips(page).count()).toBeGreaterThan(0);
}

/**
 * Every visible chip as `{name, star}`.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<Array<{name: string, star: number}>>}
 */
export async function chipStars(page) {
  const labels = await visibleChips(page).evaluateAll((els) => els.map((el) => el.getAttribute('aria-label')));
  return labels.map((label) => {
    const [, name, star] = CHIP_NAME.exec(label);
    return { name, star: Number(star) };
  });
}

/**
 * The window rows from the pill's menu (desktop): ids in order and which is active. Opens the menu once and
 * closes it with Escape.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<Array<{id: string, active: boolean}>>}
 */
export async function readWindowRows(page) {
  await page.getByTestId('wf-win-pill').click();
  const menu = page.getByTestId('wf-win-menu');
  await expect(menu).toBeVisible();
  const rows = await page.locator('[data-testid="wf-win-row"][data-ev-id]').evaluateAll((els) => els.map((el) => ({
    id: el.getAttribute('data-ev-id'),
    active: el.getAttribute('aria-selected') === 'true',
  })));
  await page.keyboard.press('Escape');
  await expect(menu).toBeHidden();
  return rows;
}

/**
 * The active window's id, read from the menu.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<string|undefined>}
 */
export async function activeWindowId(page) {
  const rows = await readWindowRows(page);
  return rows.find((row) => row.active)?.id;
}

/**
 * Steps the Map's window control back until a past window is active (the pill reads "Past"). Each step waits for
 * the pill to change, so no press is lost to a re-render.
 *
 * @param {import('@playwright/test').Page} page
 */
export async function stepIntoPast(page) {
  const pill = page.getByTestId('wf-win-pill');
  for (let i = 0; i < 12 && (await page.getByTestId('wf-win-past').count()) === 0; i += 1) {
    const before = await pill.innerText();
    await page.getByTestId('wf-win-prev').click();
    await expect(pill).not.toHaveText(before);
  }
  await expect(page.getByTestId('wf-win-past')).toBeVisible();
}

/**
 * A served JSON body, read with the signed-in reader's own token through `page.request`, which the page's
 * hygiene recorder never sees (so a refusal can be asserted without being a finding).
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} path an API path
 * @returns {Promise<import('@playwright/test').APIResponse>}
 */
export async function apiGet(page, path) {
  return page.request.get(path, { headers: { Authorization: `Bearer ${await tokenOf(page)}` } });
}

export { expect };
