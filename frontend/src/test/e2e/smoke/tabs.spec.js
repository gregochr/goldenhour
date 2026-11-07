import { test, expect, loginAsAdmin } from './fixture.js';

test.describe('Tabs on an empty database', () => {
  test.beforeEach(async ({ page }) => {
    await loginAsAdmin(page);
    await expect(page.getByTestId('window-first-shell')).toBeVisible();
  });

  test('the Plan matrix (At a Glance) renders its panel and empty state', async ({ page }) => {
    await expect(page.getByTestId('window-first-tab-plan')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('window-first-pane')).toBeVisible();
    await expect(page.getByTestId('window-first-pane-empty')).toHaveText('No forecast to show.');
  });

  test('Coming up renders its container and empty state', async ({ page }) => {
    await page.getByTestId('window-first-tab-coming-up').click();
    await expect(page.getByTestId('window-first-coming-up')).toBeVisible();
    // The feed holds dates fixed in advance (not forecast-driven), so an empty database may still list
    // some, depending on today's date. Either the list or the "no dates" note is a rendered feed; a
    // loading or error state is not.
    await expect(page.getByTestId('coming-up-list').first().or(page.getByTestId('coming-up-empty'))).toBeVisible();
    await expect(page.getByTestId('coming-up-error')).toHaveCount(0);
  });

  // The plan expected the Map to draw on an empty database. It cannot: App.jsx offers the Map tab only
  // when a forecast or a real aurora alert exists (`hasForecastData || auroraTabOn`), so the tab bar here
  // is exactly the three below. The Leaflet and tile assertions move to Phase 3, with seeded data.
  test('the tab bar offers Plan, Coming up and Operations, and no Map tab without a forecast', async ({ page }) => {
    await expect(page.getByRole('tab')).toHaveText([/./, 'Coming up', 'Operations']);
    await expect(page.getByTestId('window-first-tab-map')).toHaveCount(0);
  });

  test('Operations renders for the admin', async ({ page }) => {
    await page.getByTestId('window-first-tab-operations').click();
    await expect(page.getByTestId('window-first-panel-operations')).toBeVisible();
  });
});
