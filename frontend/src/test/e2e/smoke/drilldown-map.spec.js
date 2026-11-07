import {
  test, expect, loginAndWaitForForecast, openMapTab, visibleChips, fetchForecast,
  installDialogDepthObserver, dialogDepth, CHIP_NAME,
} from './fixture.js';
import { waitForDetail, servedRow, sheetRows, expectExpandedRow } from './sheet-helpers.js';

/**
 * Drilling into one forecast from the Map: chip, callout, "Four days here", the location sheet, and a second
 * row's lazily fetched detail. Every figure is checked against the served `/api/forecast` row; the dialogs are
 * counted by an observer installed before the page loads, so a transient third dialog cannot be missed.
 */
test.describe('Forecast drill-down from the Map', () => {
  test('chip, callout, location sheet, row detail, and the Escape order back out', async ({ page }) => {
    test.setTimeout(60_000);
    await installDialogDepthObserver(page);
    await loginAndWaitForForecast(page);
    await openMapTab(page);
    const forecast = await fetchForecast(page);

    // Chip -> callout. The callout names the place and agrees with its served star.
    const chip = visibleChips(page).first();
    const [, name] = CHIP_NAME.exec(await chip.getAttribute('aria-label'));
    await chip.click();
    const callout = page.getByTestId('map-callout');
    await expect(callout).toBeVisible();
    await expect(callout).toContainText(name);
    // The reason line carries the seeded summary and opens the four-day sheet ("Four days here"). The
    // four-days variant is the LITE outside-picks one; an admin gets the reason.
    const reason = page.getByTestId('map-callout-reason');
    await expect(reason).toBeVisible();
    await expect(reason).toContainText('Seeded E2E fixture');
    await expect(reason).toContainText('Four days here');

    // The sheet opens with one row already expanded, so that row's detail is fetched by THIS click: the
    // waiter is registered first.
    const firstDetail = waitForDetail(page);
    await reason.click();
    const sheet = page.getByTestId('location-sheet');
    await expect(sheet).toBeVisible();
    await expect(page.getByTestId('location-sheet-title')).toHaveText(name);
    expect((await firstDetail).status()).toBe(200);

    const rows = await sheetRows(sheet);
    expect(rows.length, 'the sheet lists the next several windows').toBeGreaterThanOrEqual(4);
    const opened = rows.filter((row) => row.expanded);
    expect(opened, 'exactly one row opens expanded').toHaveLength(1);
    await expectExpandedRow(sheet, opened[0].key, servedRow(forecast, name, opened[0].key));

    // A second row: closed, its forecast id taken from the oracle, its detail fetched on the press.
    const second = rows.find((row) => !row.expanded);
    const secondServed = servedRow(forecast, name, second.key);
    const secondRow = sheet.locator(`[data-testid="location-sheet-row"][data-window="${second.key}"]`);
    await expect(secondRow.getByTestId('location-sheet-row-toggle')).toHaveAttribute('aria-expanded', 'false');
    const secondDetail = waitForDetail(page, secondServed.id);
    await secondRow.getByTestId('location-sheet-row-toggle').click();
    expect((await secondDetail).status()).toBe(200);
    await expectExpandedRow(sheet, second.key, secondServed);

    // Escape order: the sheet closes first, focus returns to the callout's reason line, and the next Escape
    // clears the selection.
    await page.keyboard.press('Escape');
    await expect(sheet).toHaveCount(0);
    await expect(callout).toBeVisible();
    await expect(reason).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(callout).toHaveCount(0);

    // Nothing in the whole journey stacked more than the sheet over the map.
    const depth = await dialogDepth(page);
    expect(depth.maxDialogs, 'concurrent dialogs').toBeLessThanOrEqual(2);
    expect(depth.maxModal, 'concurrent aria-modal elements').toBeLessThanOrEqual(1);
    expect(depth.nowDialogs).toBe(0);
  });

  test('the sheet closes with its own close button too', async ({ page }) => {
    await loginAndWaitForForecast(page);
    await openMapTab(page);
    await visibleChips(page).first().click();
    await page.getByTestId('map-callout-reason').click();
    const sheet = page.getByTestId('location-sheet');
    await expect(sheet).toBeVisible();
    await page.getByTestId('location-sheet-close').click();
    await expect(sheet).toHaveCount(0);
  });
});
