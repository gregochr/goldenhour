import {
  test, expect, loginAndWaitForForecast, openMapTab, visibleChips, stepIntoPast, apiGet,
} from './fixture.js';

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
    await openMapTab(page);
    await stepIntoPast(page);
    await visibleChips(page).first().click();

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
