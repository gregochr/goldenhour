import {
  test, expect, loginAndWaitForForecast, openTab, openFurtherAhead, openMapTab,
} from './fixture.js';

/**
 * The tabs on the SEEDED fixture, and the Map door beside them. The Map door exists only once a forecast has arrived,
 * so every test waits for it before reading the tab bar. The map is a VIEW now (one-screen-plan.md D1), not a tab: its
 * button sits outside the tablist. A desktop may open the view on its own (the Map landing is preferred only if
 * /api/forecast lands within 1.5 s), so the view being open is never assumed; each test closes it if it is.
 * The empty states stay covered by Vitest; there is no second, empty backend.
 */
test.describe('Tabs on the seeded fixture', () => {
  test.beforeEach(async ({ page }) => {
    await loginAndWaitForForecast(page);
    // The desktop Map landing may have opened the view over the screen: close it so the tabs are reachable.
    const view = page.getByTestId('map-screen');
    if (await view.isVisible()) {
      await page.getByTestId('map-screen-close').click();
      await expect(view).toBeHidden();
    }
  });

  test('the tab bar offers Plan, Coming up and Operations, and the Map is a door beside it, not a tab', async ({ page }) => {
    await expect(page.getByRole('tab')).toHaveText([/At a Glance/, /Coming up/, /Operations/]);
    await expect(page.getByRole('tablist').getByTestId('window-first-tab-map')).toHaveCount(0);
    await expect(page.getByTestId('window-first-tab-map')).toHaveText(/Map$/);
  });

  test('the Plan matrix (At a Glance) renders its heat cards, not an empty state', async ({ page }) => {
    await openTab(page, 'plan');
    await expect(page.getByTestId('window-first-pane')).toBeVisible();
    await expect(page.getByTestId('window-first-pane-empty')).toHaveCount(0);
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
  });

  test('Further ahead renders the almanac list at the bottom of the screen, with no error state', async ({ page }) => {
    await openFurtherAhead(page);
    await expect(page.getByTestId('further-ahead')).toBeVisible();
    await expect(page.getByTestId('coming-up-list').first()).toBeVisible();
    await expect(page.getByTestId('coming-up-error')).toHaveCount(0);
  });

  test('the Coming up tab is a door onto Further ahead: it selects nothing, and the Plan tab stays selected', async ({ page }) => {
    await page.getByTestId('window-first-tab-coming-up').click();
    await expect(page.getByTestId('window-first-tab-coming-up')).toHaveAttribute('aria-selected', 'false');
    await expect(page.getByTestId('window-first-tab-plan')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('further-ahead-heading')).toBeFocused();
  });

  test('the Map door opens the map view, which draws a Leaflet map', async ({ page }) => {
    await openMapTab(page);
    await expect(page.locator('.leaflet-container').first()).toBeVisible();
  });

  test('Operations renders for the admin', async ({ page }) => {
    await openTab(page, 'operations');
    await expect(page.getByTestId('window-first-panel-operations')).toBeVisible();
  });
});
