import { test, expect, loginAndWaitForForecast } from './fixture.js';

test.describe('Phone width', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('the tab bar renders its three tabs with the Map door beside it, and Plan is the opening screen', async ({ page }) => {
    await loginAndWaitForForecast(page);
    await expect(page.getByTestId('window-first-tabs')).toBeVisible();
    // The Plan tab's label differs by device, so it is matched loosely; the others are anchored. The Map is a door
    // beside the list (a button, not a tab), carrying a glyph prefix.
    await expect(page.getByRole('tab')).toHaveText([/./, /^Coming up$/, /^Operations$/]);
    await expect(page.getByTestId('window-first-tab-map')).toHaveText(/Map$/);
    // On a phone the opening tab is Plan (the Map-first preference is a desktop one).
    await expect(page.getByTestId('window-first-tab-plan')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('window-first-pane')).toBeVisible();
  });
});
