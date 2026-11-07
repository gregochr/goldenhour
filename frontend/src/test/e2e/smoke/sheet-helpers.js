import { expect } from './fixture.js';

/** The detail endpoint the location sheet's expanded row fetches: `/api/forecast/{id}`. */
export const DETAIL_URL = /\/api\/forecast\/\d+$/;

/**
 * Registers the waiter for a forecast-detail response. Call it BEFORE the click that causes the request: the
 * sheet opens with one row already expanded, so its fetch is made by the very click that opens the sheet.
 *
 * @param {import('@playwright/test').Page} page
 * @param {number} [id] the forecast id to wait for; any detail response when omitted
 */
export function waitForDetail(page, id) {
  return page.waitForResponse((response) => (id == null
    ? DETAIL_URL.test(response.url())
    : response.url().endsWith(`/api/forecast/${id}`)) && response.request().method() === 'GET');
}

/**
 * The served row for one place in one window, the oracle for everything the sheet shows.
 *
 * @param {Array<object>} forecast `/api/forecast` rows
 * @param {string} name the place
 * @param {string} windowKey the sheet's `data-window`, `YYYY-MM-DD:SUNRISE|SUNSET`
 * @returns {object}
 */
export function servedRow(forecast, name, windowKey) {
  const [date, type] = windowKey.split(':');
  const row = forecast.find((r) => r.locationName === name && r.targetDate === date && r.targetType === type);
  expect(row, `a served row for ${name} ${windowKey}`).toBeTruthy();
  return row;
}

/**
 * The sheet's rows as `{key, expanded}`, in order.
 *
 * @param {import('@playwright/test').Locator} sheet the `location-sheet` dialog
 * @returns {Promise<Array<{key: string, expanded: boolean}>>}
 */
export function sheetRows(sheet) {
  return sheet.getByTestId('location-sheet-row').evaluateAll((els) => els.map((el) => ({
    key: el.getAttribute('data-window'),
    expanded: el.querySelector('[data-testid="location-sheet-row-toggle"]')?.getAttribute('aria-expanded') === 'true',
  })));
}

/**
 * Asserts one EXPANDED row of the location sheet shows what the server served for it: its star, the comfort
 * rows (the seeded 9 °C), and the "Forecast generated" footer, with the facts' skeleton gone (the detail has
 * loaded). A caller that expects the rising-tide fact names it.
 *
 * @param {import('@playwright/test').Locator} sheet the `location-sheet` dialog
 * @param {string} key the row's window key
 * @param {object} served the oracle row from {@link servedRow}
 * @param {{risingTide?: boolean}} [options]
 */
export async function expectExpandedRow(sheet, key, served, { risingTide } = {}) {
  const row = sheet.locator(`[data-testid="location-sheet-row"][data-window="${key}"]`);
  await expect(row.getByTestId('location-sheet-row-toggle')).toHaveAttribute('aria-expanded', 'true');
  await expect(row.getByTestId('location-sheet-rating')).toContainText(`${served.rating}★`);
  await expect(row.getByTestId('location-sheet-facts-skeleton')).toHaveCount(0);
  const facts = row.getByTestId('location-sheet-facts');
  await expect(facts).toBeVisible();
  await expect(row.getByTestId('location-sheet-comfort')).toContainText(`${Math.round(served.temperatureCelsius)}°C`);
  await expect(row.getByTestId('location-sheet-generated')).toContainText('Forecast generated:');
  if (risingTide === true) {
    await expect(row.getByTestId('location-sheet-fact-rising-tide')).toContainText(/Rising tide .* \(\d+ min (after|before) sun(rise|set)\)/);
  } else if (risingTide === false) {
    await expect(row.getByTestId('location-sheet-fact-rising-tide')).toHaveCount(0);
  }
}
