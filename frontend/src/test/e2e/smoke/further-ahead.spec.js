import { test, expect, loginAndWaitForForecast } from './fixture.js';

/**
 * Further ahead, the bottom section of the screen (docs/engineering/one-screen-plan.md O2), on the seeded fixture:
 * the "Further ahead ↓" jump at the top of This week lands on its heading, and the filter chip the reader chose is
 * remembered across a reload (`localStorage['furtherAheadFilter']`, the owner's tides habit).
 *
 * <p>The screen is read as a reader meets it: both sections are labelled regions under sticky headings, and nothing
 * here depends on which dates the fixture happens to hold, because the seeded almanac may list none under a chip.
 */
test.describe('Further ahead', () => {
  test.beforeEach(async ({ page }) => {
    await loginAndWaitForForecast(page);
    await expect(page.getByTestId('further-ahead-jump')).toBeVisible();
  });

  test('the jump scrolls the section into view and focuses its heading', async ({ page }) => {
    const heading = page.getByTestId('further-ahead-heading');

    await page.getByTestId('further-ahead-jump').click();

    await expect(heading).toBeInViewport();
    await expect(heading).toBeFocused();
    await expect(page.getByRole('region', { name: /Further ahead/ })).toBeVisible();
    // The heading sticks under the lens bar: it is not hidden behind it.
    const top = (await heading.boundingBox()).y;
    const lensBottom = (await page.getByTestId('window-first-lens').boundingBox()).y
      + (await page.getByTestId('window-first-lens').boundingBox()).height;
    expect(top).toBeGreaterThanOrEqual(lensBottom - 1);
  });

  test('both sections are labelled regions with their own headings, This week above Further ahead', async ({ page }) => {
    await expect(page.getByRole('region', { name: /This week/ })).toBeVisible();
    await expect(page.getByRole('heading', { level: 2, name: 'This week · forecast' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 2, name: 'Further ahead · dates known in advance' })).toHaveCount(1);
    const week = await page.getByTestId('this-week').boundingBox();
    const ahead = await page.getByTestId('further-ahead').boundingBox();
    expect(ahead.y).toBeGreaterThan(week.y);
  });

  test('the chosen filter is remembered across a reload', async ({ page }) => {
    await page.getByTestId('further-ahead-jump').click();
    const chip = (id) => page.locator(`[data-testid="coming-up-chip"][data-chip="${id}"]`);
    await expect(chip('all')).toHaveAttribute('aria-pressed', 'true');

    await chip('coastal').click();
    await expect(chip('coastal')).toHaveAttribute('aria-pressed', 'true');
    expect(await page.evaluate(() => localStorage.getItem('furtherAheadFilter'))).toBe('coastal');

    await page.reload();
    await expect(page.getByTestId('further-ahead-jump')).toBeVisible();
    await page.getByTestId('further-ahead-jump').click();

    await expect(chip('coastal')).toHaveAttribute('aria-pressed', 'true');
    await expect(chip('all')).toHaveAttribute('aria-pressed', 'false');
  });

  test('a stored value that is not a chip falls back to All', async ({ page }) => {
    await page.evaluate(() => localStorage.setItem('furtherAheadFilter', 'tides-only'));
    await page.reload();
    await expect(page.getByTestId('further-ahead-jump')).toBeVisible();
    await page.getByTestId('further-ahead-jump').click();

    await expect(page.locator('[data-testid="coming-up-chip"][data-chip="all"]')).toHaveAttribute('aria-pressed', 'true');
  });
});
