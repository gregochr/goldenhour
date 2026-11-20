import fs from 'node:fs';
import path from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import {
  test, expect, loginAndWaitForForecast, openTab, openMapTab, clickMiddleChip,
} from '../smoke/fixture.js';

/**
 * The advisory accessibility scan (docs/engineering/engineering-gaps-plan.md, Phase 3c). One test per scan, so one
 * failure never hides another, run at desktop and phone width (the two projects in `playwright.a11y.config.js`) on the
 * seeded fixture, signed in as the admin (read-only).
 *
 * The rules, from the plan:
 *  - the WHOLE page is scanned (for a dialog, with the dialog open: axe skips inert and hidden content anyway);
 *  - NO `disableRules` and NO `exclude`, ever: an existing violation is fixed in its own PR, never suppressed;
 *  - a test fails only on `serious` or `critical` violations; `moderate` and `minor` are reported only;
 *  - every scan writes `test-results/a11y/<project>-<scan>.json` (folded by `scripts/a11y-summary.mjs`) and attaches
 *    the full axe result.
 * The specs import `test` from the smoke fixture, so its tile and Turnstile stubs and the hygiene assertion apply too.
 */

const RECORD_DIR = path.resolve('test-results/a11y');
const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];
const BLOCKING = ['serious', 'critical'];

/**
 * Runs axe over the page, records the result and fails on serious or critical violations.
 *
 * @param {import('@playwright/test').Page} page the page, already in the state to scan
 * @param {import('@playwright/test').TestInfo} testInfo the running test
 * @param {string} scan the scan's name (part of the record's file name)
 */
async function scan(page, testInfo, scan_) {
  // Fonts shift text metrics, which moves contrast and target-size results: scan only once they are in.
  // Reduced motion is on, but wait out any animation still running (sheets slide in) so every scan sees the end state.
  await page.evaluate(async () => {
    await document.fonts.ready;
    await Promise.all(document.getAnimations()
      .filter((a) => a.effect.getComputedTiming().iterations !== Infinity)
      .map((a) => a.finished.catch(() => {})));
  });
  expect(testInfo.project.use.bypassCSP, 'bypassCSP would silently drop the nginx CSP coverage').toBeFalsy();
  const results = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
  const project = testInfo.project.name;
  const violations = results.violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    nodes: v.nodes.length,
    helpUrl: v.helpUrl,
    targets: v.nodes.map((n) => n.target.join(' ')),
  }));
  // `incomplete` is what axe could not decide (for example contrast over imagery: the basemap tiles are a stubbed 1x1
  // PNG here, so contrast of chips over real imagery is NOT covered). Counted separately, never a failure.
  const incomplete = results.incomplete.map((v) => ({ id: v.id, nodes: v.nodes.length }));
  fs.mkdirSync(RECORD_DIR, { recursive: true });
  fs.writeFileSync(
    path.join(RECORD_DIR, `${project}-${scan_}.json`),
    `${JSON.stringify({ project, scan: scan_, violations, incomplete }, null, 2)}\n`,
  );
  await testInfo.attach(`axe-${project}-${scan_}.json`, {
    body: JSON.stringify(results, null, 2),
    contentType: 'application/json',
  });
  const blocking = violations.filter((v) => BLOCKING.includes(v.impact));
  expect(
    blocking.map((v) => `${v.impact} ${v.id} (${v.nodes}): ${v.targets.slice(0, 5).join(' | ')}`),
    `serious or critical accessibility violations on ${scan_} (${project})`,
  ).toEqual([]);
}

test.describe('Accessibility scan (advisory)', () => {
  test('sign-in page', async ({ page }, testInfo) => {
    await page.goto('/');
    await expect(page.getByTestId('login-submit')).toBeVisible();
    await scan(page, testInfo, 'sign-in');
  });

  test.describe('signed in', () => {
    test.beforeEach(async ({ page }) => {
      await loginAndWaitForForecast(page);
    });

    test('Plan (At a Glance)', async ({ page }, testInfo) => {
      await openTab(page, 'plan');
      await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
      await scan(page, testInfo, 'plan');
    });

    test('Coming up', async ({ page }, testInfo) => {
      await openTab(page, 'coming-up');
      await expect(page.getByTestId('coming-up-list').first()).toBeVisible();
      await scan(page, testInfo, 'coming-up');
    });

    test('Map', async ({ page }, testInfo) => {
      await openMapTab(page);
      await scan(page, testInfo, 'map');
    });

    test('Operations', async ({ page }, testInfo) => {
      await openTab(page, 'operations');
      await expect(page.getByTestId('window-first-panel-operations')).toBeVisible();
      // The opening section (Runs) loads over the network: scan it once its day summary and timeline are in (the
      // "Updated HH:MM" line appears only after the load), never mid-load.
      await expect(page.getByTestId('runs-view')).toBeVisible();
      await expect(page.getByText(/^Updated \d\d:\d\d/)).toBeVisible();
      await expect(page.getByText('No runs on this day.')).toBeVisible();
      await scan(page, testInfo, 'operations');
    });

    // Find with an Areas group typed in, and the area sheet it opens (find-and-favourites-plan.md F2). The seeded
    // fixture holds three sub-regions, so "northumb" lists one under the Areas heading.
    test('Find, with areas', async ({ page }, testInfo) => {
      await openTab(page, 'plan');
      await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
      await page.getByTestId('window-first-search').click();
      await page.getByTestId('plan-search-input').fill('northumb');
      await expect(page.getByTestId('plan-search-row').filter({ hasText: 'Northumberland Shore' })).toBeVisible();
      await scan(page, testInfo, 'find-areas');
    });

    test('area sheet', async ({ page }, testInfo) => {
      await openTab(page, 'plan');
      await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
      await page.getByTestId('window-first-search').click();
      await page.getByTestId('plan-search-input').fill('northumb');
      await page.getByTestId('plan-search-row').filter({ hasText: 'Northumberland Shore' }).click();
      const areaSheet = page.getByTestId('area-sheet');
      await expect(areaSheet).toBeVisible();
      await expect.poll(() => areaSheet.getByTestId('area-sheet-spot').count()).toBeGreaterThanOrEqual(6);
      await scan(page, testInfo, 'area-sheet');
    });

    test('location sheet (from Find)', async ({ page }, testInfo) => {
      await openTab(page, 'plan');
      await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
      await page.getByTestId('window-first-search').click();
      await page.getByTestId('plan-search-input').fill('Bamburgh');
      await page.getByTestId('plan-search-row').filter({ hasText: 'Bamburgh Beach' }).first().click();
      await expect(page.getByTestId('location-sheet')).toBeVisible();
      // The open row's detail has loaded (its skeleton is gone), so the scan sees the facts, not a placeholder.
      await expect(page.getByTestId('location-sheet-facts')).toBeVisible();
      await expect(page.getByTestId('location-sheet-facts-skeleton')).toHaveCount(0);
      await scan(page, testInfo, 'location-sheet');
    });

    // Your places (find-and-favourites-plan.md F4): the toggle in the place sheet, then the undo toast it raises. The
    // toast scan WRITES one favourite and takes it straight back with Undo, so the seeded reader's list ends as it began
    // (the one exception to this suite's read-only rule, and the only way to show the toast).
    test('location sheet with the Your places toggle, and its undo toast', async ({ page }, testInfo) => {
      await openTab(page, 'plan');
      await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
      await page.getByTestId('window-first-search').click();
      await page.getByTestId('plan-search-input').fill('Bamburgh');
      await page.getByTestId('plan-search-row').filter({ hasText: 'Bamburgh Beach' }).first().click();
      const toggle = page.getByTestId('location-sheet-favourite');
      await expect(toggle).toBeVisible();
      await expect(toggle).toHaveAttribute('aria-pressed', 'false');
      await expect(page.getByTestId('location-sheet-facts-skeleton')).toHaveCount(0);
      await scan(page, testInfo, 'favourite-toggle');
      await toggle.click();
      await expect(toggle).toHaveAttribute('aria-pressed', 'true');
      await expect(page.getByTestId('undo-toast-status')).toContainText('Added Bamburgh Beach to your places.');
      await scan(page, testInfo, 'undo-toast');
      await page.getByTestId('undo-toast-undo').click();
      await expect(toggle).toHaveAttribute('aria-pressed', 'false');
    });

    test('Map callout (the peek sheet on a phone)', async ({ page }, testInfo) => {
      await openMapTab(page);
      await clickMiddleChip(page);
      await expect(page.getByTestId('map-callout')).toBeVisible();
      await expect(page.getByTestId('map-callout-reason')).toBeVisible();
      await scan(page, testInfo, 'map-callout');
    });
  });
});
