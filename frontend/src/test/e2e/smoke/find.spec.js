import {
  test, expect, loginAndWaitForForecast, openMapTab, openTab, visibleChips, CHIP_NAME,
} from './fixture.js';

/**
 * Find on every tab (find-and-favourites-plan.md F1), on the seeded fixture: the masthead button and ⌘K / Ctrl K,
 * and what a pick does on each tab. A place picked on the Map flies the map to it and opens its callout without
 * moving the reader's window; picked on Coming up it takes the reader to the Plan tab and opens its sheet there;
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

  test('the Find button and ⌘K open Find on Plan, Coming up and Map, and a second ⌘K closes it', async ({ page }) => {
    for (const id of ['plan', 'coming-up', 'map']) {
      await openTab(page, id);
      await page.getByTestId('window-first-search').click();
      await expect(FIND(page)).toBeVisible();
      await page.keyboard.press('Escape');
      await expect(FIND(page)).toHaveCount(0);

      await press(page);
      await expect(FIND(page)).toBeVisible();
      await press(page);
      await expect(FIND(page)).toHaveCount(0);
      // Closing from the key, with no trigger focused, puts focus back on the Find button.
      await expect(page.getByTestId('window-first-search')).toBeFocused();
    }
  });

  test('⚠️ on the Map a place flies there and opens its callout, keeping the reader\'s window', async ({ page }) => {
    await openMapTab(page);
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
    await expect(page.getByTestId('window-first-tab-map')).toHaveAttribute('aria-selected', 'true');
    await expect(pill).toHaveText(windowBefore);
  });

  test('⚠️ one Escape closes only Find over a selected callout', async ({ page }) => {
    await openMapTab(page);
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

  test('⚠️ on Coming up a place takes the reader to the Plan tab and opens its sheet there', async ({ page }) => {
    await openMapTab(page);
    const name = await aChipName(page);
    await openTab(page, 'coming-up');

    await press(page);
    await FIELD(page).fill(name);
    await page.getByRole('option', { name: new RegExp(name) }).first().click();

    await expect(page.getByTestId('window-first-tab-plan')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('location-sheet')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('location-sheet')).toHaveCount(0);
    // The pick moved the reader to the Plan tab and focus to its button (as `goToPlanWindow` does), so that is where
    // the sheet's close lands; the Find button is where a pick MADE ON the Plan tab returns (unit-tested).
    await expect(page.getByTestId('window-first-tab-plan')).toBeFocused();
  });

  test('Back closes Find on the Map', async ({ page }) => {
    await openMapTab(page);
    await press(page);
    await expect(FIND(page)).toBeVisible();

    await page.goBack();

    await expect(FIND(page)).toHaveCount(0);
  });
});
