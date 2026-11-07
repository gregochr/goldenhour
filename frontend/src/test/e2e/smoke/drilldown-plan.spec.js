import {
  test, expect, loginAndWaitForForecast, openTab, fetchForecast, installDialogDepthObserver, dialogDepth,
} from './fixture.js';
import { waitForDetail, servedRow, sheetRows, expectExpandedRow } from './sheet-helpers.js';

/**
 * Drilling into one forecast from the Plan matrix: heat card, window sheet, a place, its location sheet: two
 * dialogs deep and never three. The place is Bamburgh Beach, the fixture's one rising-tide coast (its next high
 * water is 45 minutes after the light), so the sheet's "Rising tide" fact is asserted here and its absence on an
 * inland place. Escape closes the TOP layer first, and the close buttons work.
 */
test.describe('Forecast drill-down from the Plan matrix', () => {
  /** Opens the Plan, a window sheet that is not the first card, and returns its dialog. */
  async function openWindowSheet(page) {
    await openTab(page, 'plan');
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
    // Never the first card: it may be the window that is about to pass.
    await page.getByTestId('wf-heat-card').nth(2).click();
    const windowSheet = page.getByTestId('window-sheet');
    await expect(windowSheet).toBeVisible();
    await expect.poll(() => windowSheet.getByTestId('window-spot').count()).toBeGreaterThanOrEqual(6);
    return windowSheet;
  }

  test('heat card, window sheet, place, location sheet, and Escape one layer at a time', async ({ page }) => {
    test.setTimeout(60_000);
    await installDialogDepthObserver(page);
    await loginAndWaitForForecast(page);
    const forecast = await fetchForecast(page);
    const windowSheet = await openWindowSheet(page);

    // The primary route: a place in the window sheet. The location sheet opens OVER it: two dialogs.
    const bamburgh = windowSheet.getByTestId('window-spot').filter({ hasText: 'Bamburgh Beach' });
    await expect(bamburgh).toHaveCount(1);
    const firstDetail = waitForDetail(page);
    await bamburgh.click();
    const sheet = page.getByTestId('location-sheet');
    await expect(sheet).toBeVisible();
    await expect(page.getByTestId('location-sheet-title')).toHaveText('Bamburgh Beach');
    expect((await firstDetail).status()).toBe(200);
    await expect(windowSheet).toBeVisible();
    expect((await dialogDepth(page)).nowDialogs, 'window sheet under the location sheet').toBe(2);

    const rows = await sheetRows(sheet);
    const opened = rows.filter((row) => row.expanded);
    expect(opened).toHaveLength(1);
    await expectExpandedRow(sheet, opened[0].key, servedRow(forecast, 'Bamburgh Beach', opened[0].key), {
      risingTide: true,
    });

    // Escape: the location sheet (the top layer) closes first, then the window sheet.
    await page.keyboard.press('Escape');
    await expect(sheet).toHaveCount(0);
    await expect(windowSheet).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(windowSheet).toHaveCount(0);

    // The depth the observer saw over the whole journey: two at most, and only one claiming aria-modal.
    const depth = await dialogDepth(page);
    expect(depth.maxDialogs, 'concurrent dialogs').toBeLessThanOrEqual(2);
    expect(depth.maxModal, 'concurrent aria-modal elements').toBeLessThanOrEqual(1);
    expect(depth.nowDialogs).toBe(0);
  });

  test('an inland place shows no rising tide; both close buttons work in order', async ({ page }) => {
    await installDialogDepthObserver(page);
    await loginAndWaitForForecast(page);
    const forecast = await fetchForecast(page);
    const windowSheet = await openWindowSheet(page);

    const place = windowSheet.getByTestId('window-spot').filter({ hasText: 'Ingleborough' });
    await expect(place).toHaveCount(1);
    const detail = waitForDetail(page);
    await place.click();
    const sheet = page.getByTestId('location-sheet');
    await expect(sheet).toBeVisible();
    expect((await detail).status()).toBe(200);
    const opened = (await sheetRows(sheet)).filter((row) => row.expanded);
    expect(opened).toHaveLength(1);
    await expectExpandedRow(sheet, opened[0].key, servedRow(forecast, 'Ingleborough', opened[0].key), {
      risingTide: false,
    });

    await page.getByTestId('location-sheet-close').click();
    await expect(sheet).toHaveCount(0);
    await expect(windowSheet).toBeVisible();
    await page.getByTestId('window-sheet-close').click();
    await expect(windowSheet).toHaveCount(0);

    const depth = await dialogDepth(page);
    expect(depth.maxDialogs).toBeLessThanOrEqual(2);
    expect(depth.maxModal).toBeLessThanOrEqual(1);
  });
});
