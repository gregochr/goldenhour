import { test, expect, loginAndWaitForForecast, openTab } from './fixture.js';

/**
 * The four tabs on the SEEDED fixture. The Map tab exists only once a forecast has arrived, so every test
 * waits for it before reading the tab bar, and every test clicks its tab explicitly: the opening tab on a
 * desktop is a race (Map is preferred only if /api/forecast lands within 1.5 s), so it is never asserted.
 * The empty states stay covered by Vitest; there is no second, empty backend.
 */
test.describe('Tabs on the seeded fixture', () => {
  test.beforeEach(async ({ page }) => {
    await loginAndWaitForForecast(page);
  });

  test('the tab bar offers Plan, Coming up, Map and Operations, in that order', async ({ page }) => {
    await expect(page.getByRole('tab')).toHaveText([/At a Glance/, /Coming up/, /Map/, /Operations/]);
  });

  test('the Plan matrix (At a Glance) renders its heat cards, not an empty state', async ({ page }) => {
    await openTab(page, 'plan');
    await expect(page.getByTestId('window-first-pane')).toBeVisible();
    await expect(page.getByTestId('window-first-pane-empty')).toHaveCount(0);
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
  });

  test('Coming up renders the almanac list, with no error state', async ({ page }) => {
    await openTab(page, 'coming-up');
    await expect(page.getByTestId('window-first-coming-up')).toBeVisible();
    await expect(page.getByTestId('coming-up-list').first()).toBeVisible();
    await expect(page.getByTestId('coming-up-error')).toHaveCount(0);
  });

  test('the Map tab draws a Leaflet map', async ({ page }) => {
    await openTab(page, 'map');
    await expect(page.locator('.leaflet-container').first()).toBeVisible();
  });

  test('Operations renders for the admin', async ({ page }) => {
    await openTab(page, 'operations');
    await expect(page.getByTestId('window-first-panel-operations')).toBeVisible();
  });
});
