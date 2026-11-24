import fs from 'node:fs';
import path from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import {
  test, expect, loginAndWaitForForecast, openMenu, openOperations, openFurtherAhead, openMapView, clickMiddleChip,
} from '../smoke/fixture.js';

/**
 * The advisory accessibility scan (docs/engineering/engineering-gaps-plan.md, Phase 3c). One test per scan, so one
 * failure never hides another, run at desktop and phone width (the two projects in `playwright.a11y.config.js`) on the
 * seeded fixture, signed in as the admin (read-only), except two Lite scans, which sign in as `lite` (also read-only: a
 * Lite reader's doors never call the endpoint): the locked lens, and Further ahead's "Watch this date".
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
  // The Lite reader's greyed reach control on the lens bar: its caption ("Drive from home") must stay at full strength,
  // since the lock dims the tier buttons only. Signed in as `lite`, read-only.
  test('This week as a Lite reader, with the lens locked', async ({ page }, testInfo) => {
    await loginAndWaitForForecast(page, 'lite');
    await expect(page.getByTestId('window-first-lens-upsell')).toBeVisible();
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
    await scan(page, testInfo, 'plan-lite-lens');
  });

  // The Lite reader's "Watch this date" (one-screen-plan.md O2b, D16): the control with its Pro pill in an open Further
  // ahead entry, then the date-worded upsell it opens. Read-only (a Lite reader's door never calls the endpoint), and
  // signed in as `lite` rather than the admin the rest of the suite uses.
  test('Further ahead as a Lite reader, with the watch toggle and its upsell', async ({ page }, testInfo) => {
    await loginAndWaitForForecast(page, 'lite');
    await openFurtherAhead(page);
    const openAll = page.getByTestId('coming-up-toggle-all');
    await expect(openAll).toBeVisible();
    if ((await openAll.textContent()).trim() === 'Open all') await openAll.click();
    const door = page.getByTestId('coming-up-watch-toggle-locked').first();
    await expect(door).toBeVisible();
    await expect(door.locator('.wf-lens-pro')).toBeVisible();
    await scan(page, testInfo, 'further-ahead-lite-watch');
    await door.click();
    await expect(page.getByTestId('coming-up-watch-upsell-panel')).toBeVisible();
    await expect(page.getByTestId('coming-up-watch-upsell-panel')).toContainText('Watch a date');
    await scan(page, testInfo, 'further-ahead-lite-watch-upsell');
  });

  test('sign-in page', async ({ page }, testInfo) => {
    await page.goto('/');
    await expect(page.getByTestId('login-submit')).toBeVisible();
    await scan(page, testInfo, 'sign-in');
  });

  test.describe('signed in', () => {
    test.beforeEach(async ({ page }) => {
      await loginAndWaitForForecast(page);
    });

    test('This week', async ({ page }, testInfo) => {
      await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
      await scan(page, testInfo, 'plan');
    });

    // Further ahead is the bottom section of the screen (one-screen-plan.md O2), reached by the jump. The scan covers the
    // whole page, so it also covers the two sticky section headings, the jump and the chips' pressed states.
    test('Further ahead', async ({ page }, testInfo) => {
      await openFurtherAhead(page);
      await expect(page.getByRole('region', { name: /Further ahead/ })).toBeVisible();
      await expect(page.getByTestId('coming-up-list').first()).toBeVisible();
      await scan(page, testInfo, 'further-ahead');
    });

    // The Map is a view over the screen (one-screen-plan.md O1): a labelled region with the screen behind it inert, not a
    // tab. The scan covers the whole page, so it also proves the inert screen, the header's window control and the
    // region's own label raise nothing.
    test('Map view', async ({ page }, testInfo) => {
      await openMapView(page);
      await expect(page.getByRole('region', { name: /./ }).and(page.getByTestId('map-screen'))).toBeVisible();
      await scan(page, testInfo, 'map');
    });

    test('Map view with Find open over it', async ({ page }, testInfo) => {
      await openMapView(page);
      await page.getByTestId('map-screen-find').click();
      await expect(page.getByTestId('plan-search-panel')).toBeVisible();
      await scan(page, testInfo, 'map-find');
    });

    // The ☰ menu is the one masthead control at every width, so its open state is scanned on its own.
    test('Menu open', async ({ page }, testInfo) => {
      await openMenu(page);
      await expect(page.getByTestId('masthead-menu-operations')).toBeVisible();
      await scan(page, testInfo, 'menu');
    });

    // Reached through the ☰ menu, as a reader reaches it (one-screen-plan.md D9); the scan is of the view itself.
    test('Operations', async ({ page }, testInfo) => {
      await openOperations(page);
      await expect(page.getByTestId('operations-title')).toBeFocused();
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
      await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
      await page.getByTestId('window-first-search').click();
      await page.getByTestId('plan-search-input').fill('northumb');
      await expect(page.getByTestId('plan-search-row').filter({ hasText: 'Northumberland Shore' })).toBeVisible();
      await scan(page, testInfo, 'find-areas');
    });

    test('area sheet', async ({ page }, testInfo) => {
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

    test('Map view callout (the peek sheet on a phone)', async ({ page }, testInfo) => {
      await openMapView(page);
      await clickMiddleChip(page);
      await expect(page.getByTestId('map-callout')).toBeVisible();
      await expect(page.getByTestId('map-callout-reason')).toBeVisible();
      await scan(page, testInfo, 'map-callout');
    });
  });
});
