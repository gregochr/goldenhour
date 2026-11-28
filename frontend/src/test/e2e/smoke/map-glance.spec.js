import {
  test, expect, loginAndWaitForForecast, openMapView, activeWindowId, SOLAR_ID, visibleChips,
  installDialogDepthObserver, dialogDepth,
} from './fixture.js';

/**
 * The Glance-to-Map doors on the seeded fixture (docs/engineering/glance-to-map-plan.md): a Plan card opens the Map on its
 * own window, the Map's List door opens the ranked list and a row of it lands on the place's callout, and the Best bet
 * pill opens and closes its reason panel.
 *
 * <p>No spec leans on the first window (it may be passing) and none asserts a fixed count or star. The window a card
 * opens is read back from the card's own accessible name and the pill's menu, never re-derived. The warning
 * row opens in place on the one Met Office warning the seeded fixture carries (`E2eWarningFixture`): a Yellow rain
 * warning over the Lake District's six places, valid only for the T+1 sunset window, so the spec opens the Map on
 * exactly that window by its `data-ev-id` and no other window shows a warning.
 */
/** Tomorrow's UK civil date as YYYY-MM-DD (the fixture's T+1), computed in Europe/London so no UTC day-lag shows. */
function nextUkDay() {
  const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/London' }).format(new Date());
  const next = new Date(`${today}T12:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 1);
  return next.toISOString().slice(0, 10);
}

test.describe('Glance to Map', () => {
  /** Opens the Plan tab and waits for the matrix. */
  async function openPlan(page) {
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
  }

  test('a Plan card opens the Map with that card\'s window selected', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openPlan(page);
    // Never the first card: it may be the window that is about to pass. A control card (not an away one) only.
    const card = page.locator('[data-testid="wf-heat-card"][data-control="true"]').nth(2);
    const button = card.getByTestId('wf-heat-card-button');
    const label = await page.evaluate(
      (id) => document.getElementById(id)?.textContent ?? '',
      await button.getAttribute('aria-labelledby'),
    );
    expect(label, 'the card says which window it opens').toMatch(/^Open .+ on the map/);
    await card.click();

    await expect(page.getByTestId('map-screen')).toBeVisible();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    const active = await activeWindowId(page);
    expect(active, 'a solar window is selected').toMatch(SOLAR_ID);
    // The pill names the selected window with the card's own two words, in its own order ("Tuesday sunrise" on the
    // card, "Sunrise Tuesday" on the pill).
    const [, day, event] = /^Open (\S+) (sunrise|sunset) on the map/i.exec(label);
    const pill = page.getByTestId('wf-win-pill');
    await expect(pill).toContainText(new RegExp(day, 'i'));
    await expect(pill).toContainText(new RegExp(event, 'i'));
  });

  test('Map to List to a row: the callout opens, the list closes, and Escape peels one layer', async ({ page }) => {
    test.setTimeout(60_000);
    await installDialogDepthObserver(page);
    await loginAndWaitForForecast(page);
    await openMapView(page);
    const callout = page.getByTestId('map-callout');
    await expect(callout).toHaveCount(0);

    const list = page.getByTestId('map-list-button');
    await expect(list).toBeVisible();
    await expect(list).not.toHaveAttribute('aria-disabled', 'true');
    await list.click();
    const sheet = page.getByTestId('window-spot-sheet');
    await expect(sheet).toBeVisible();
    await expect(page.getByTestId('window-spot').first()).toBeVisible();

    // One dialog deep: Escape closes the list and leaves the Map as it was.
    await page.keyboard.press('Escape');
    await expect(sheet).toHaveCount(0);
    await expect(callout).toHaveCount(0);

    await list.click();
    await expect(sheet).toBeVisible();
    const row = page.getByTestId('window-spot').first();
    const name = (await row.locator('span.text-plex-text').first().innerText()).trim();
    await row.click();

    await expect(sheet).toHaveCount(0);
    await expect(callout).toBeVisible();
    await expect(callout).toContainText(name);
    await expect(page.getByTestId('map-screen')).toBeVisible();

    const depth = await dialogDepth(page);
    expect(depth.maxDialogs, 'concurrent dialogs').toBeLessThanOrEqual(2);
    expect(depth.maxModal, 'concurrent aria-modal elements').toBeLessThanOrEqual(1);
    expect(depth.nowDialogs).toBe(0);
  });

  test('the Best bet reason opens and closes', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openPlan(page);
    // The pill draws only for a window whose card carries a served pick: open the Map on a card that has one.
    const cards = page.locator('[data-testid="wf-heat-card"][data-control="true"]');
    const total = await cards.count();
    const pill = page.getByTestId('map-best-bet-why');
    let found = false;
    for (let i = 2; i < total && !found; i += 1) {
      await cards.nth(i).click();
      await expect(page.getByTestId('map-screen')).toBeVisible();
      await expect(page.locator('[data-testid="map-labels"][data-placed="true"]')).toHaveCount(1);
      found = (await pill.count()) > 0;
      if (!found) {
        // Back to the screen for the next card: the view is closed with its button.
        await page.getByTestId('map-screen-close').click();
        await expect(page.getByTestId('map-screen')).toBeHidden();
      }
    }
    test.skip(!found, 'no window the fixture serves a pick for');

    await expect(pill).toHaveAttribute('aria-expanded', 'false');
    await pill.click();
    await expect(pill).toHaveAttribute('aria-expanded', 'true');
    const body = page.getByTestId('map-best-bet-body');
    await expect(body).toBeVisible();
    await expect(page.getByTestId('map-best-bet-headline')).not.toBeEmpty();

    await page.getByTestId('map-best-bet-close').click();
    await expect(body).toHaveCount(0);
    await expect(pill).toHaveAttribute('aria-expanded', 'false');

    await pill.click();
    await expect(body).toBeVisible();
    await pill.click();
    await expect(body).toHaveCount(0);
    expect(await visibleChips(page).count()).toBeGreaterThan(0);
  });

  test('the warning row opens and closes the warning in full, in place', async ({ page }) => {
    test.setTimeout(90_000);
    await loginAndWaitForForecast(page);
    await openMapView(page);

    // Open the Map on the T+1 sunset window (the one window the fixture's warning is valid for), never the first.
    const target = `solar:${nextUkDay()}:SUNSET`;
    await page.getByTestId('wf-win-pill').click();
    await expect(page.getByTestId('wf-win-menu')).toBeVisible();
    await page.locator(`[data-testid="wf-win-row"][data-ev-id="${target}"]`).click();
    await expect(page.getByTestId('wf-win-menu')).toBeHidden();
    expect(await activeWindowId(page), 'the Map is on the warned window').toBe(target);

    const chips = page.getByTestId('map-warning-chips');
    await expect(chips, 'the seeded warning shows as a row on the Map').toBeVisible();
    await expect(chips).toContainText(/rain/i);
    await expect(page.getByTestId('warning-stale'), 'the seeded feed is fresh').toHaveCount(0);
    await expect(page.getByTestId('wf-map-warnings').getByTestId('warning-attribution')).toBeVisible();

    const row = page.getByTestId('map-warning-row');
    await expect(row).toHaveCount(1);
    const region = page.getByTestId('map-warning-region');
    await expect(row).toHaveAttribute('aria-expanded', 'false');
    await expect(row).toHaveAccessibleName(/UK time/);
    await expect(region).toHaveCount(0);

    // Open in place, then close with the row itself.
    await row.click();
    await expect(row).toHaveAttribute('aria-expanded', 'true');
    await expect(row).toHaveAttribute('aria-controls', /.+/);
    await expect(region).toBeVisible();
    await expect(region.getByTestId('window-warning-headline'))
      .toHaveText('Heavy rain may cause travel disruption across the Lake District.');
    await expect(region.getByTestId('window-warning-expect'))
      .toContainText('Spray and standing water could make some fell roads and bridges difficult to use.');
    await expect(region.getByTestId('map-warning-source-link')).toBeVisible();
    await expect(page.getByTestId('wf-map-warnings').getByTestId('warning-attribution')).toBeVisible();
    await row.click();
    await expect(row).toHaveAttribute('aria-expanded', 'false');
    await expect(region).toHaveCount(0);

    // Open, then one Escape closes it (map-tab.md: before anything else), focus returns to the row and the row stays.
    await row.click();
    await expect(region).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(region).toHaveCount(0);
    await expect(row).toHaveAttribute('aria-expanded', 'false');
    await expect(row).toBeVisible();
    await expect(chips).toBeVisible();
    await expect(page.getByTestId('warning-stale')).toHaveCount(0);
  });
});
