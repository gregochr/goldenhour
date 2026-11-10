import {
  test, expect, loginAndWaitForForecast, openMapTab, visibleChips, chipStars, settledChipStars, fetchForecast, starsOf,
  activeWindowId, CHIP_NAME,
} from './fixture.js';

/**
 * The Map tab on the seeded fixture: Leaflet draws, the basemap is requested, every visible chip carries the
 * star the server served, and a chip opens a callout that agrees with it. Stars are read from the served
 * `/api/forecast` (the oracle), never re-derived; chips are the visible ones by role, polled, never read once.
 */
test.describe('Map tab', () => {
  test.beforeEach(async ({ page }) => {
    await loginAndWaitForForecast(page);
    await openMapTab(page);
  });

  test('Leaflet draws and the basemap tiles are requested', async ({ page, tiles }) => {
    const container = page.locator('.leaflet-container').first();
    await expect(container).toBeVisible();
    const box = await container.boundingBox();
    expect(box.width).toBeGreaterThan(200);
    expect(box.height).toBeGreaterThan(200);
    await expect(page.locator('.leaflet-tile-pane')).toHaveCount(1);
    await expect.poll(() => tiles.count, { message: 'basemap tile requests (stubbed)' }).toBeGreaterThan(0);
  });

  test('every visible chip carries the star the server served for the active window', async ({ page }) => {
    const active = await activeWindowId(page);
    const oracle = starsOf(await fetchForecast(page), active);
    expect(oracle.size, `served stars for ${active}`).toBeGreaterThan(0);

    await expect.poll(async () => {
      const chips = await chipStars(page);
      return chips.filter((chip) => oracle.get(chip.name) !== chip.star).map((chip) => `${chip.name} ${chip.star}`);
    }, { message: 'visible chips whose star differs from /api/forecast' }).toEqual([]);
    expect((await chipStars(page)).length).toBeGreaterThan(0);
  });

  test('a chip opens its callout with the served star; the close button and Escape both close it', async ({ page }) => {
    const active = await activeWindowId(page);
    const oracle = starsOf(await fetchForecast(page), active);
    const callout = page.getByTestId('map-callout');
    await expect(callout).toHaveCount(0);

    // First chip: open, check, close with the button.
    const first = visibleChips(page).first();
    const [, firstName] = CHIP_NAME.exec(await first.getAttribute('aria-label'));
    await first.click();
    await expect(callout).toBeVisible();
    await expect(callout).toContainText(firstName);
    await expect(page.getByTestId('map-callout-score')).toHaveText(new RegExp(`^${oracle.get(firstName)}★ `));
    await page.getByTestId('map-callout-close').click();
    await expect(callout).toHaveCount(0);

    // A different chip: open, then Escape. The first callout is closed before the second chip is pressed.
    const chips = await settledChipStars(page);
    const other = chips.find((chip) => chip.name !== firstName);
    expect(other, 'a second, different visible chip').toBeDefined();
    await page.getByRole('button', { name: new RegExp(`^${other.name}, \\d star`) }).click();
    await expect(callout).toBeVisible();
    await expect(page.getByTestId('map-callout-score')).toHaveText(new RegExp(`^${oracle.get(other.name)}★ `));
    await page.keyboard.press('Escape');
    await expect(callout).toHaveCount(0);
  });
});
