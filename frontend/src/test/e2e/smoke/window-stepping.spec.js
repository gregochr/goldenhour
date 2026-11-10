import {
  test, expect, loginAndWaitForForecast, openMapTab, chipStars, fetchForecast, starsOf, readWindowRows,
  SOLAR_ID,
} from './fixture.js';

/**
 * Stepping through the Map's windows, from the current one to the end of the forecast, back through the past,
 * and on to the oldest day the server will hand over (owner request, gaps plan Phase 3a.1).
 *
 * Nothing here leans on which window is current: the starting window is read from the menu, every expected
 * star comes from the served `/api/forecast` (or `?date=` for the two older days) keyed by the row's
 * `data-ev-id`, and the claim is a RELATIONSHIP (the pill changes; every chip present in both windows changes
 * its star, which the seeder's formula guarantees), never a fixed count. A run that crosses a sunrise or a
 * sunset therefore still passes.
 */

const pill = (page) => page.getByTestId('wf-win-pill');
const next = (page) => page.getByTestId('wf-win-next');
const prev = (page) => page.getByTestId('wf-win-prev');

/** Polls until every visible chip carries the oracle's star for `id`; returns those chips. */
async function expectChipsMatch(page, oracle, id) {
  await expect.poll(async () => {
    const chips = await chipStars(page);
    return chips.length > 0
      ? chips.filter((chip) => oracle.get(chip.name) !== chip.star).map((chip) => `${chip.name} ${chip.star}`)
      : ['no visible chips'];
  }, { message: `visible chips against /api/forecast for ${id}` }).toEqual([]);
  return chipStars(page);
}

/** Steps with a button and waits for the pill to change; returns the new active window id. */
async function stepWith(page, press) {
  const before = await pill(page).innerText();
  await press();
  await expect(pill(page)).not.toHaveText(before);
  const rows = await readWindowRows(page);
  return rows.find((row) => row.active)?.id;
}

/** Every chip present in both windows must have changed star; the intersection must not be empty. */
function expectChangedStars(before, after, label) {
  const afterByName = new Map(after.map((chip) => [chip.name, chip.star]));
  const shared = before.filter((chip) => afterByName.has(chip.name));
  expect(shared.length, `chips visible in both windows (${label})`).toBeGreaterThan(0);
  for (const chip of shared) {
    expect(afterByName.get(chip.name), `${chip.name} should change star (${label})`).not.toBe(chip.star);
  }
}

test.describe('Map window control: stepping through the windows', () => {
  test.beforeEach(async ({ page }) => {
    await loginAndWaitForForecast(page);
    await openMapTab(page);
  });

  test('forward to the end, back through the past, then loading the two older days down to the floor', async ({ page }) => {
    test.setTimeout(90_000);
    const forecast = await fetchForecast(page);
    const rows = await readWindowRows(page);
    const solar = rows.filter((row) => SOLAR_ID.test(row.id));
    expect(solar.length, 'the menu lists solar windows only on this fixture').toBe(rows.length);
    const startIndex = rows.findIndex((row) => row.active);
    expect(startIndex, 'an active window').toBeGreaterThanOrEqual(0);

    // ---- the list ends at T+3 ----------------------------------------------------------------------
    // The fixture serves forecast rows to T+4, but nothing rates T+4, so the Map must not list it: the
    // menu's last window is a day earlier than the served forecast's last sunrise/sunset date, and it is
    // the sunset of that day (whole days, like At a Glance).
    const lastServedDate = forecast
      .filter((row) => row.targetType === 'SUNRISE' || row.targetType === 'SUNSET')
      .map((row) => row.targetDate).sort().at(-1);
    expect(lastServedDate, 'the forecast carries sunrise/sunset rows').toBeTruthy();
    const lastListed = SOLAR_ID.exec(rows.at(-1).id);
    expect(lastListed[1] < lastServedDate, 'the menu ends before the last served forecast date').toBe(true);
    expect(lastListed[2], 'the last listed window is a sunset').toBe('SUNSET');
    expect(rows.every((row) => SOLAR_ID.exec(row.id)[1] <= lastListed[1])).toBe(true);

    // ---- forward, to the last window --------------------------------------------------------------
    let activeId = rows[startIndex].id;
    let chips = await expectChipsMatch(page, starsOf(forecast, activeId), activeId);
    for (let i = startIndex + 1; i < rows.length; i += 1) {
      await expect(next(page)).toBeEnabled();
      const id = await stepWith(page, () => next(page).click());
      expect(id, `the window after ${activeId}`).toBe(rows[i].id);
      const oracle = starsOf(forecast, id);
      // A window with no served rows (the menu shows a dash) has nothing to compare.
      if (oracle.size > 0) {
        const after = await expectChipsMatch(page, oracle, id);
        expectChangedStars(chips, after, `${activeId} to ${id}`);
        chips = after;
      }
      activeId = id;
    }
    await expect(next(page), 'the last window has no next, and nothing wraps').toBeDisabled();
    expect((await readWindowRows(page)).find((row) => row.active).id).toBe(rows.at(-1).id);

    // ---- back, through today, into the past ------------------------------------------------------
    let sawPast = false;
    for (let i = rows.length - 2; i >= 0; i -= 1) {
      const id = await stepWith(page, () => prev(page).click());
      expect(id).toBe(rows[i].id);
      const oracle = starsOf(forecast, id);
      if (oracle.size > 0) await expectChipsMatch(page, oracle, id);
      if (await page.getByTestId('wf-win-past').count()) sawPast = true;
    }
    expect(sawPast, 'a past window reads "Past" in the pill on some step back').toBe(true);

    // ---- keyboard, with the pill focused (focus drops to <body> when a stepper disables) ------------
    await pill(page).focus();
    const keyStart = (await readWindowRows(page)).find((row) => row.active).id;
    await pill(page).focus();
    const afterRight = await stepWith(page, () => page.keyboard.press('ArrowRight'));
    expect(afterRight, 'ArrowRight steps forward').toBe(rows[rows.findIndex((r) => r.id === keyStart) + 1].id);
    await pill(page).focus();
    const afterLeft = await stepWith(page, () => page.keyboard.press('ArrowLeft'));
    expect(afterLeft, 'ArrowLeft steps back').toBe(keyStart);

    // ---- the oldest loaded window: ‹ now LOADS the next older day, twice -------------------------------
    // The initial list covers T-2..T+3, so exactly two older days exist: T-3 (rows) and T-4 (empty on purpose).
    expect((await readWindowRows(page)).find((row) => row.active).id, 'at the oldest loaded window').toBe(rows[0].id);

    const loadOlderDay = async () => {
      await expect(prev(page)).toHaveAttribute('aria-label', /^Load /);
      const label = await prev(page).getAttribute('aria-label');
      const loaded = page.waitForResponse((r) => /\/api\/forecast\?date=\d{4}-\d\d-\d\d$/.test(r.url()));
      await prev(page).click();
      const response = await loaded;
      expect(response.status(), label).toBe(200);
      await expect(page.getByTestId('wf-win-loading')).toHaveCount(0);
      return { response, date: new URL(response.url()).searchParams.get('date') };
    };

    // T-3: has rows. The selection jumps to that day's sunset, and the pill says it is past.
    const first = await loadOlderDay();
    await expect(page.getByTestId('wf-win-past')).toBeVisible();
    const t3Rows = await first.response.json();
    expect(t3Rows.length, 'T-3 carries stored forecast rows').toBeGreaterThan(0);
    const t3Sunset = (await readWindowRows(page)).find((row) => row.active).id;
    expect(t3Sunset).toBe(`solar:${first.date}:SUNSET`);
    await expectChipsMatch(page, starsOf(t3Rows, t3Sunset), t3Sunset);

    // Step back to that day's sunrise, which is now the oldest window: ‹ loads T-4.
    expect(await stepWith(page, () => prev(page).click())).toBe(`solar:${first.date}:SUNRISE`);
    const second = await loadOlderDay();
    expect(await second.response.json(), 'T-4 is left empty on purpose').toEqual([]);

    // The floor: no older day to ask for. ‹ is disabled and the status line says why.
    await expect(prev(page)).toBeDisabled();
    await expect(page.getByTestId('wf-win-status')).toHaveText(/No stored forecast for /);
    await expect(prev(page)).toHaveAttribute('aria-describedby', 'wf-win-status');
  });
});

test.describe('Map window control on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  test('the pill opens the peek sheet, whose rows choose a window; the steppers still step', async ({ page }) => {
    await loginAndWaitForForecast(page);
    await openMapTab(page);
    const forecast = await fetchForecast(page);
    const sheetButton = page.getByTestId('wf-map-peek-btn-win');
    const sheetRows = page.getByTestId('wf-map-peek-win-row');

    // On a phone the pill opens the peek sheet's Windows section, not the dropdown menu.
    await expect(sheetButton).toHaveAttribute('aria-expanded', 'false');
    await pill(page).click();
    await expect(sheetButton).toHaveAttribute('aria-expanded', 'true');
    await expect(page.getByTestId('wf-win-menu')).toHaveCount(0);
    // The sheet slides in over 0.28 s: web-first assertions wait it out.
    await expect(sheetRows.first()).toBeVisible();
    expect(await sheetRows.count()).toBeGreaterThanOrEqual(6);

    const ids = await sheetRows.evaluateAll((els) => els.map((el) => ({
      id: el.getAttribute('data-ev-id'), active: el.getAttribute('aria-selected') === 'true',
    })));
    const startIndex = ids.findIndex((row) => row.active);
    expect(startIndex, 'an active window').toBeGreaterThanOrEqual(0);
    // Two rows on from the current window (or two back, at the end of the list).
    const targetIndex = startIndex + 2 < ids.length ? startIndex + 2 : startIndex - 2;
    const target = ids[targetIndex].id;
    expect(target).toMatch(SOLAR_ID);

    const startLabel = await pill(page).innerText();
    await sheetRows.nth(targetIndex).click();
    await expect(pill(page)).not.toHaveText(startLabel);
    await expect(sheetRows.nth(targetIndex)).toHaveAttribute('aria-selected', 'true');
    // Close the sheet with the same button that opened it.
    await sheetButton.click();
    await expect(sheetButton).toHaveAttribute('aria-expanded', 'false');
    const chips = await expectChipsMatch(page, starsOf(forecast, target), target);
    expect(chips.length).toBeGreaterThan(0);

    // The steppers still step one window, and the chips follow.
    const label = await pill(page).innerText();
    const stepIndex = targetIndex + 1 < ids.length ? targetIndex + 1 : targetIndex - 1;
    await (stepIndex > targetIndex ? next(page) : prev(page)).click();
    await expect(pill(page)).not.toHaveText(label);
    const stepId = ids[stepIndex].id;
    if (starsOf(forecast, stepId).size > 0) {
      await expectChipsMatch(page, starsOf(forecast, stepId), stepId);
    }
  });
});
