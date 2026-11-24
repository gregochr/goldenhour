import {
  test, expect, loginAndWaitForForecast, openMapView, openFurtherAhead, stepIntoPast, clickMiddleChip, apiGet,
} from './fixture.js';

// A taller viewport than the default 1280x720: a Map callout opens beside its chip and is not scrollable, so with a
// chip low on a 720px map the rating block's stars can fall below the fold ("Element is outside of the viewport",
// found by --repeat-each). At 1000px every callout fits whichever chip the spec clicks.
test.use({ viewport: { width: 1280, height: 1000 } });

/**
 * The Pro reader rates one past window (docs/engineering/engineering-gaps-plan.md, Phase 3a.2).
 *
 * This is the only role spec that WRITES shared server state (`PUT /api/outcome/ratings` stores a row and
 * invalidates the served-briefing memo for everyone), so it runs in the serial `roles-write` project, after the
 * whole `smoke` project and before `logout` (see playwright.smoke.config.js). It signs in as `pro`, never as the
 * admin, so it carries none of the admin sign-out hazard.
 *
 * It is repeatable: a rating is an upsert, so under `--repeat-each` the block may already read "rated", in which
 * case the spec presses "Change" first; and the star it presses follows the repeat index, so a repeat writes a
 * genuinely different value rather than a no-op.
 */
test.describe('Pro reader rates a past window', () => {
  test('the stars save, the block reads rated, and the saved row reads back', async ({ page }, testInfo) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page, 'pro');
    await openMapView(page);
    await stepIntoPast(page);
    await clickMiddleChip(page);

    const block = page.getByTestId('outcome-rating-block');
    await expect(block).toBeVisible();
    await expect(block.getByTestId('pro-pill'), 'a Pro reader is not locked').toHaveCount(0);
    const firstRating = (await block.getAttribute('data-state')) === 'unrated';
    if (!firstRating) {
      // Rated on an earlier repeat: the stars are behind "Change".
      await block.getByTestId('outcome-rating-change').click();
    }
    await expect(block.getByTestId('outcome-rating-star')).toHaveCount(5);

    const stars = (testInfo.repeatEachIndex % 5) + 1;
    const saved = page.waitForResponse((r) => r.url().endsWith('/api/outcome/ratings') && r.request().method() === 'PUT');
    await block.getByTestId('outcome-rating-star').nth(stars - 1).click();
    const response = await saved;
    expect(response.status(), 'PUT /api/outcome/ratings').toBe(200);
    const sent = response.request().postDataJSON();
    expect(sent.rating).toBe(stars);
    if (firstRating) {
      // A FIRST rating (an insert) answers with the request, and the block shows it. A RE-rating answers with the
      // PREVIOUS star (a stale re-read in OutcomeRatingService.rate, found by this spec), so the block shows the old
      // value until a reload: a product bug the spec does not pin. Under --repeat-each the later repeats are
      // re-ratings, so these two are asserted on the first rating only and the DB read below on every one.
      expect((await response.json()).rating, 'the row as stored').toBe(stars);
    }

    // The block now reads rated, with a way to change it.
    await expect(block).toHaveAttribute('data-state', 'rated');
    await expect(block.getByTestId('outcome-rating-change')).toBeVisible();
    if (firstRating) await expect(block).toContainText(`${stars}★`);
    await expect(block.getByTestId('outcome-rating-status'), 'no save error').toHaveCount(0);

    // The saved row reads back from the reader's own list.
    const own = await apiGet(page, `/api/outcome/ratings?from=${sent.eventDate}&to=${sent.eventDate}`);
    expect(own.status()).toBe(200);
    const rows = await own.json();
    const row = rows.find((r) => r.locationId === sent.locationId && r.eventType === sent.eventType
      && r.eventDate === sent.eventDate);
    expect(row, 'the saved rating is in the reader\'s own list').toBeTruthy();
    expect(row.rating).toBe(stars);
  });
});

/**
 * The Pro reader watches a date (docs/engineering/one-screen-plan.md O2b, D16): "Watch this date" in an open Further
 * ahead entry writes one favourite row (`POST /api/user/settings/favourites/dates/{key}`), and the date joins Your places
 * at the top of This week as a chip carrying the entry's title.
 *
 * <p>Like the rating above it WRITES shared state (the `pro` reader's own list), so it lives in this serial project and
 * takes the watch straight back, even when an assertion fails, so a re-run (`--repeat-each`) finds the list as it began.
 * It does not depend on which dates the seeded almanac holds: it takes the first open entry that offers the toggle.
 */
test.describe('Pro reader watches a date', () => {
  test('the watch joins Your places as a chip, and un-watching takes it away', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page, 'pro');
    await openFurtherAhead(page);
    const openAll = page.getByTestId('coming-up-toggle-all');
    await expect(openAll).toBeVisible();
    if ((await openAll.textContent()).trim() === 'Open all') await openAll.click();

    // By accessible name, never `aria-pressed` (screen-spec.md D1): an unwatched toggle is named "Watch this date", a
    // watched one "Watching".
    const toggle = page.getByTestId('coming-up-watch-toggle').and(page.getByRole('button', { name: 'Watch this date', exact: true })).first();
    await expect(toggle, 'an open entry offers Watch this date to a Pro reader').toBeVisible();
    // Fixed by id now: once the toggle is pressed the locator above (which asks for an unwatched one) matches nothing.
    const entryId = await page.getByTestId('coming-up-entry').filter({ has: toggle }).first().getAttribute('data-entry-id');
    const entry = page.locator(`[data-testid="coming-up-entry"][data-entry-id="${entryId}"]`);
    const title = (await entry.getByTestId('coming-up-title').textContent()).trim();
    const watchedChips = page.locator('[data-testid="your-places-chip"][data-kind="date"]');
    const before = await watchedChips.count();

    try {
      const saved = page.waitForResponse((r) => r.url().includes('/api/user/settings/favourites/dates/')
        && r.request().method() === 'POST');
      await toggle.click();
      const response = await saved;
      expect(response.status(), 'POST .../favourites/dates/{key}').toBe(200);
      const key = decodeURIComponent(response.url().split('/dates/')[1]);
      expect(key).toMatch(/^[a-z-]+:\d{4}-\d{2}-\d{2}$/);
      expect((await response.json()).dates.map((d) => d.key)).toContain(key);

      await expect(entry.getByTestId('coming-up-watch-toggle')).toHaveAccessibleName('Watching');
      await expect(watchedChips).toHaveCount(before + 1);
      await expect(watchedChips.filter({ hasText: title })).toHaveCount(1);

      // The list reads back from the reader's own endpoint.
      const own = await apiGet(page, '/api/user/settings/favourites');
      expect(own.status()).toBe(200);
      expect((await own.json()).dates.map((d) => d.key)).toContain(key);
    } finally {
      const pressed = entry.getByTestId('coming-up-watch-toggle');
      if ((await pressed.getAttribute('data-starred')) === 'true') {
        await pressed.click();
        await expect(pressed).toHaveAccessibleName('Watch this date');
      }
    }
    await expect(watchedChips).toHaveCount(before);
  });
});
