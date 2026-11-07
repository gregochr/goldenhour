import { test, expect, loginAndWaitForForecast } from './fixture.js';

test.describe('Phone width', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('the tab bar renders all four tabs, and Plan is the opening tab', async ({ page }) => {
    await loginAndWaitForForecast(page);
    await expect(page.getByTestId('window-first-tabs')).toBeVisible();
    // The Plan tab's label differs by device, so it is matched loosely; the others are anchored (the Map tab carries a glyph prefix).
    await expect(page.getByRole('tab')).toHaveText([/./, /^Coming up$/, /Map$/, /^Operations$/]);
    // On a phone the opening tab is Plan (the Map-first preference is a desktop one).
    await expect(page.getByTestId('window-first-tab-plan')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('window-first-pane')).toBeVisible();
  });
});
