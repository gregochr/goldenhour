import { test, expect, loginAndWaitForForecast, openMenu } from './fixture.js';

test.describe('Phone width', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('there is no tab bar: the screen opens on This week and the ☰ menu is the masthead\'s one control', async ({ page }) => {
    await loginAndWaitForForecast(page);
    await expect(page.getByRole('tablist')).toHaveCount(0);
    await expect(page.getByTestId('window-first-pane')).toBeVisible();
    await expect(page.getByTestId('this-week-heading')).toBeVisible();
    await expect(page.getByTestId('masthead-menu-button')).toBeVisible();
    // The Ask bar is the phone's Ask entry; the menu holds Settings, the guide and Sign out.
    await openMenu(page);
    await expect(page.getByTestId('window-first-settings')).toBeVisible();
    await expect(page.getByTestId('window-first-signout')).toBeVisible();
  });
});
