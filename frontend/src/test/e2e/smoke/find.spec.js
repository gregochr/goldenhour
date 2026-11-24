import {
  test, expect, loginAndWaitForForecast, openMapView, closeMapView, openFurtherAhead, visibleChips, CHIP_NAME,
} from './fixture.js';

/**
 * Find on the screen and the map view (find-and-favourites-plan.md F1), on the seeded fixture: the masthead button and
 * ⌘K / Ctrl K, and what a pick does on each. A place picked on the Map flies the map to it and opens its callout without
 * moving the reader's window; picked with Further ahead on screen it opens its sheet there, as on the rest of the screen;
 * one Escape closes only Find over a selected callout; a second ⌘K closes it.
 *
 * <p>The place is read from the page (a visible chip), never assumed, so the spec does not mirror the fixture's
 * roster; the window is read off the pill and compared before and after.
 */
const FIND = (page) => page.getByTestId('plan-search-panel');
const FIELD = (page) => page.getByTestId('plan-search-input');

/** ⌘K on a Mac, Ctrl K elsewhere: Playwright's `ControlOrMeta` is the platform's own modifier. */
const press = (page) => page.keyboard.press('ControlOrMeta+K');

/** A visible chip's place name, read off its accessible name. */
async function aChipName(page) {
  const [, name] = CHIP_NAME.exec(await visibleChips(page).first().getAttribute('aria-label'));
  return name;
}

test.describe('Find everywhere', () => {
  test.beforeEach(async ({ page }) => {
    await loginAndWaitForForecast(page);
  });

  test('the Find button and ⌘K open Find on the screen, down at Further ahead, and on the Map, and a second ⌘K closes it', async ({ page }) => {
    for (const id of ['plan', 'further-ahead', 'map']) {
      // The Map is a view: its Find button is the header's, and the masthead's is inert behind it.
      if (id === 'map') await openMapView(page);
      else if (id === 'further-ahead') await openFurtherAhead(page);
      // 'plan' is the screen as it opened: nothing to press.
      const findButton = page.getByTestId(id === 'map' ? 'map-screen-find' : 'window-first-search');
      await findButton.click();
      await expect(FIND(page)).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(FIND(page)).toHaveCount(0);

      await press(page);
      await expect(FIND(page)).toBeVisible();
      await press(page);
      await expect(FIND(page)).toHaveCount(0);
      // Closing from the key, with no trigger focused, puts focus back on the Find button.
      await expect(findButton).toBeFocused();
      if (id === 'map') await closeMapView(page);
    }
  });

  test('⚠️ on the Map a place flies there and opens its callout, keeping the reader\'s window', async ({ page }) => {
    await openMapView(page);
    const pill = page.getByTestId('wf-win-pill');
    const windowBefore = await pill.textContent();
    const name = await aChipName(page);

    await press(page);
    await FIELD(page).fill(name);
    await page.getByRole('option', { name: new RegExp(name) }).first().click();

    await expect(FIND(page)).toHaveCount(0);
    const callout = page.getByTestId('map-callout');
    await expect(callout).toBeVisible();
    await expect(callout).toContainText(name);
    await expect(page.getByTestId('map-screen')).toBeVisible();
    await expect(pill).toHaveText(windowBefore);
  });

  test('⚠️ one Escape closes only Find over a selected callout', async ({ page }) => {
    await openMapView(page);
    const name = await aChipName(page);
    await visibleChips(page).first().click();
    const callout = page.getByTestId('map-callout');
    await expect(callout).toContainText(name);

    await press(page);
    await expect(FIND(page)).toBeVisible();
    await page.keyboard.press('Escape');

    await expect(FIND(page)).toHaveCount(0);
    await expect(callout).toBeVisible();
    // The control: with Find gone, the next Escape is the Map's own and closes the callout.
    await page.keyboard.press('Escape');
    await expect(callout).toHaveCount(0);
  });

  test('⚠️ with Further ahead on screen a place opens its sheet there, on the same screen', async ({ page }) => {
    await openMapView(page);
    const name = await aChipName(page);
    await closeMapView(page);
    await openFurtherAhead(page);
    // Scrolled to the bottom of the screen the tick line is far above the viewport; the panel still opens in view.
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBeGreaterThan(0);

    await press(page);
    await FIELD(page).fill(name);
    await expect(page.getByTestId('plan-search-panel')).toBeInViewport();
    await page.getByRole('option', { name: new RegExp(name) }).first().click();

    await expect(page.getByTestId('map-screen')).toBeHidden();
    await expect(page.getByTestId('location-sheet')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('location-sheet')).toHaveCount(0);
  });

  test('Back closes Find on the Map', async ({ page }) => {
    await openMapView(page);
    await press(page);
    await expect(FIND(page)).toBeVisible();

    await page.goBack();

    await expect(FIND(page)).toHaveCount(0);
  });
});
