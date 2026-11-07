import { test, expect, loginAsAdmin } from './fixture.js';

test.describe('Phone width', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('the tab bar renders the tabs', async ({ page }) => {
    await loginAsAdmin(page);
    await expect(page.getByTestId('window-first-tabs')).toBeVisible();
    await expect(page.getByTestId('window-first-tab-plan')).toBeVisible();
    await expect(page.getByTestId('window-first-tab-coming-up')).toBeVisible();
    await expect(page.getByTestId('window-first-tab-operations')).toBeVisible();
    // No Map tab without a forecast (see tabs.spec.js), so the phone bar has three tabs.
    await expect(page.getByRole('tab')).toHaveCount(3);
  });
});
