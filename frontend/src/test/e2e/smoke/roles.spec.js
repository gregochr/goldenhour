import {
  test, expect, loginAndWaitForForecast, openMapTab, openTab, visibleChips, fetchForecast, starsOf, activeWindowId,
  stepIntoPast, clickMiddleChip, apiGet, USERS, CHIP_NAME, OUTSIDE_CHIP,
} from './fixture.js';

/**
 * Role gating (docs/engineering/engineering-gaps-plan.md, Phase 3a.2): the seeded `lite` and `pro` readers see
 * what production shows them, with `photocast.sub-regions.enforce=true` as in production. `lite` holds picks
 * for two of the three regions (`E2eRoleUsers`), so the third (Yorkshire Dales) is outside its picks. Every
 * claim about a role's data is checked against the SERVED response for that role (the oracle), never against a
 * re-derived rule.
 *
 * Read-only: these specs write nothing, and they use the role accounts, so signing out elsewhere cannot touch
 * them. The one role spec that writes (the Pro reader rating a past window) is `roles-write.spec.js`.
 */

// A taller viewport than the default 1280x720: a Map callout opens beside its chip and is not scrollable, so with a
// chip low on a 720px map the rating block's stars can fall below the fold ("Element is outside of the viewport",
// found by --repeat-each). At 1000px every callout fits whichever chip the spec clicks.
test.use({ viewport: { width: 1280, height: 1000 } });

/** The Pro door's popover, opened: a worded upsell, with a Pro pill and no link and no "upgrade" wording. */
async function expectWordedUpsell(page, popoverId) {
  const popover = page.getByTestId(popoverId);
  await expect(popover).toBeVisible();
  await expect(popover.locator('.wf-lens-pro').first()).toBeVisible();
  await expect(popover.locator('a'), 'an upsell is never a link').toHaveCount(0);
  await expect(popover).not.toContainText(/upgrade/i);
  expect((await popover.innerText()).trim().length, 'the popover carries words').toBeGreaterThan(20);
}

/** Reads a served JSON body as a different reader, signing in through `page.request` (never seen by hygiene). */
async function servedAs(page, role, path) {
  const { username, password } = USERS[role];
  const login = await page.request.post('/api/auth/login', {
    data: { username, password, turnstileToken: 'e2e-token' },
  });
  expect(login.status(), `${role} login`).toBe(200);
  const { accessToken } = await login.json();
  const response = await page.request.get(path, { headers: { Authorization: `Bearer ${accessToken}` } });
  expect(response.status(), `GET ${path} as ${role}`).toBe(200);
  return response.json();
}

/** Every slot of a served briefing. */
function slotsOf(briefing) {
  return briefing.days.flatMap((day) => day.eventSummaries)
    .flatMap((summary) => summary.regions).flatMap((region) => region.slots);
}

/** Every window of a served briefing. */
function windowsOf(briefing) {
  return briefing.days.flatMap((day) => day.eventSummaries).map((summary) => summary.window).filter(Boolean);
}

test.describe('Every non-admin reader', () => {
  for (const role of ['lite', 'pro']) {
    test(`${role}: no Operations tab, and an admin endpoint is refused with 403`, async ({ page }) => {
      await loginAndWaitForForecast(page, role);
      await expect(page.getByTestId('window-first-tab-plan')).toBeVisible();
      await expect(page.getByTestId('window-first-tab-operations')).toHaveCount(0);

      // Through page.request, so the 403 is the assertion and never a hygiene finding.
      const refused = await apiGet(page, '/api/admin/scheduler/jobs');
      expect(refused.status()).toBe(403);

      // The control: the same call as the admin is 200, so the 403 above is the role and not a wrong URL.
      const admin = await servedAs(page, 'admin', '/api/admin/scheduler/jobs');
      expect(admin).toEqual([]);
    });
  }
});

test.describe('LITE reader', () => {
  test('Pro doors open a worded upsell, on the Plan tab and in the location sheet, and never a link', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page, 'lite');

    // The Plan tab's "Best bets" door, in the heat strip's key row.
    await openTab(page, 'plan');
    const bestBets = page.getByTestId('wf-heat-bestbets-upsell-trigger');
    await expect(bestBets).toBeVisible();
    await expect(bestBets.locator('.wf-lens-pro')).toBeVisible();
    await bestBets.click();
    await expectWordedUpsell(page, 'wf-heat-bestbets-upsell-popover');
    await page.getByTestId('wf-heat-bestbets-upsell-dismiss').click();
    await expect(page.getByTestId('wf-heat-bestbets-upsell-popover')).toHaveCount(0);

    // The location sheet of a place INSIDE the picks: "Scores and the why".
    await openMapTab(page);
    await visibleChips(page).first().click();
    await page.getByTestId('map-callout-reason').click();
    await expect(page.getByTestId('location-sheet')).toBeVisible();
    const scores = page.getByTestId('wf-scores-upsell-trigger');
    await expect(scores).toBeVisible();
    await expect(scores.locator('.wf-lens-pro')).toBeVisible();
    await scores.click();
    await expectWordedUpsell(page, 'wf-scores-upsell-popover');
    await page.getByTestId('wf-scores-upsell-dismiss').click();
    await expect(page.getByTestId('wf-scores-upsell-popover')).toHaveCount(0);
    // The sheet itself stays open under a dismissed door.
    await expect(page.getByTestId('location-sheet')).toBeVisible();
  });

  test('Ask shows the Pro lens, opens the upsell, and no /api/ask request is ever made', async ({ page }) => {
    const askRequests = [];
    page.on('request', (req) => {
      const { pathname } = new URL(req.url());
      // `/api/user/settings/ask` (200 for every role, so the client can tell the flag on from off) is not Ask.
      if (pathname.startsWith('/api/ask')) askRequests.push(`${req.method()} ${pathname}`);
    });
    await loginAndWaitForForecast(page, 'lite');

    const field = page.getByTestId('ask-field');
    await expect(field).toHaveAttribute('data-locked', 'true');
    await expect(field.locator('.wf-lens-pro')).toBeVisible();
    await expect(field).toHaveAttribute('aria-haspopup', 'dialog');

    await field.click();
    await expect(field).toHaveAttribute('aria-expanded', 'true');
    // Ask's own upsell popover (AskUpsell), not a ProUpsellDoor: its root carries the bare id.
    await expectWordedUpsell(page, 'wf-ask-upsell');
    // Nothing of Ask itself mounts, and the `/` key stays refused.
    await expect(page.getByTestId('ask-dock')).toHaveCount(0);
    await page.getByTestId('wf-ask-upsell-dismiss').click();
    await expect(page.getByTestId('wf-ask-upsell')).toHaveCount(0);
    await page.locator('body').press('/');
    await expect(page.getByTestId('ask-dock')).toHaveCount(0);
    await openMapTab(page);

    expect(askRequests, 'a LITE reader makes no /api/ask* request').toEqual([]);
  });

  test('"How was it?" is locked on a past window: Pro pill, dimmed stars, an answer in words, no write', async ({ page }) => {
    test.setTimeout(60_000);
    const writes = [];
    page.on('request', (req) => {
      if (req.method() !== 'GET' && new URL(req.url()).pathname.startsWith('/api/outcome')) {
        writes.push(`${req.method()} ${new URL(req.url()).pathname}`);
      }
    });
    await loginAndWaitForForecast(page, 'lite');
    await openMapTab(page);
    await stepIntoPast(page);
    await clickMiddleChip(page);

    const block = page.getByTestId('outcome-rating-block');
    await expect(block).toBeVisible();
    await expect(block).toHaveAttribute('data-state', 'lite');
    await expect(block.getByTestId('pro-pill')).toBeVisible();
    await expect(block.getByTestId('outcome-rating-star')).toHaveCount(5);
    // Every control is aria-disabled, never `disabled`, so a tap is accepted and answered.
    await expect(block.getByTestId('outcome-rating-star').first()).toHaveAttribute('aria-disabled', 'true');
    await expect(block.getByTestId('outcome-rating-note')).not.toContainText('Rating is part of Pro.');
    // `force`: Playwright reads aria-disabled as "not enabled" and would wait forever, but the tap is accepted by
    // design (the control is aria-disabled, never `disabled`, so a press answers in words).
    const star = block.getByTestId('outcome-rating-star').nth(3);
    const box = await star.boundingBox();
    const view = page.viewportSize();
    expect(box && box.x >= 0 && box.y >= 0 && box.x + box.width <= view.width && box.y + box.height <= view.height,
      `the star is inside the viewport (star ${JSON.stringify(box)}, viewport ${JSON.stringify(view)}, callout `
      + `${JSON.stringify(await page.getByTestId('map-callout').boundingBox())})`).toBe(true);
    await star.click({ force: true });
    await expect(block.getByTestId('outcome-rating-note')).toHaveText('Rating is part of Pro.');
    expect(writes, 'a LITE tap calls nothing').toEqual([]);
  });

  test('chips outside the picks are gated, and the callout and the location sheet say so', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page, 'lite');
    await openMapTab(page);

    // The oracle: what the server served this reader. Exactly one region's six places are gated.
    const forecast = await fetchForecast(page);
    const gatedNames = new Set(forecast.filter((row) => row.gated).map((row) => row.locationName));
    expect(gatedNames.size, 'the places outside the picks').toBe(6);
    expect(forecast.filter((row) => !row.gated).every((row) => row.rating != null), 'every pick is rated').toBe(true);
    expect(forecast.filter((row) => row.gated).every((row) => row.rating == null), 'no outside place is rated').toBe(true);

    // Chips: a gated chip is exactly a place the server gated, and the rated chips carry the served star.
    await expect.poll(() => page.getByRole('button', { name: OUTSIDE_CHIP }).count(),
      { message: 'an outside chip is visible' }).toBeGreaterThan(0);
    const outside = await page.getByRole('button', { name: OUTSIDE_CHIP })
      .evaluateAll((els) => els.map((el) => [el.getAttribute('aria-label'), el.getAttribute('data-gated')]));
    for (const [label, gated] of outside) {
      expect(gated, label).toBe('true');
      expect(gatedNames.has(OUTSIDE_CHIP.exec(label)[1]), `${label} is gated by the server`).toBe(true);
    }
    await expect(page.locator('[data-testid="map-label-chip-outside"]').first()).toBeAttached();
    const windowId = await activeWindowId(page);
    const stars = starsOf(forecast, windowId);
    const rated = await visibleChips(page).evaluateAll((els) => els.map((el) => [el.getAttribute('aria-label'),
      el.getAttribute('data-gated')]));
    expect(rated.length, 'rated chips are visible too').toBeGreaterThan(0);
    for (const [label, gated] of rated) {
      const [, name, star] = CHIP_NAME.exec(label);
      expect(gated, `${name} is not gated`).toBeNull();
      expect(gatedNames.has(name), `${name} is inside the picks`).toBe(false);
      expect(Number(star), `${name} carries its served star`).toBe(stars.get(name));
    }

    // Callout, then the sheet, on an outside place.
    await page.getByRole('button', { name: OUTSIDE_CHIP }).first().click();
    await expect(page.getByTestId('map-callout-score')).toHaveText('Outside your sub-regions');
    await page.getByTestId('map-callout-reason').click();
    const sheet = page.getByTestId('location-sheet');
    await expect(sheet).toBeVisible();
    await expect(sheet.getByTestId('location-sheet-outside')).toHaveText('Outside your sub-regions');
    await expect(sheet.getByTestId('location-sheet-outside-block')).toBeVisible();
    await expect(sheet.getByTestId('location-sheet-outside-text')).toContainText('has no ratings for you');
    // The door is a Pro door: a worded upsell, never a link.
    await sheet.getByTestId('wf-subregions-place-upsell-trigger').click();
    await expectWordedUpsell(page, 'wf-subregions-place-upsell-popover');
  });

  test('the server strips the scores, the summaries and the picks, and gates the places outside the picks', async ({ page }) => {
    await loginAndWaitForForecast(page, 'lite');
    const lite = {
      forecast: await fetchForecast(page),
      scores: await (await apiGet(page, '/api/briefing/evaluate/scores')).json(),
      briefing: await (await apiGet(page, '/api/briefing')).json(),
    };
    const pro = {
      forecast: await servedAs(page, 'pro', '/api/forecast'),
      scores: await servedAs(page, 'pro', '/api/briefing/evaluate/scores'),
      briefing: await servedAs(page, 'pro', '/api/briefing'),
    };

    // The Fiery Sky and Golden Hour scores and the written why: absent for LITE on every path, present for PRO.
    for (const [path, rows] of [['/api/forecast', lite.forecast], ['/api/briefing/evaluate/scores', lite.scores]]) {
      expect(rows.length, path).toBeGreaterThan(0);
      expect(rows.filter((r) => r.fierySkyPotential != null || r.goldenHourPotential != null), path).toEqual([]);
    }
    expect(lite.scores.filter((r) => r.summary), 'no written why for LITE').toEqual([]);
    for (const [path, rows] of [['/api/forecast', pro.forecast], ['/api/briefing/evaluate/scores', pro.scores]]) {
      expect(rows.every((r) => r.fierySkyPotential != null && r.goldenHourPotential != null), `PRO ${path}`).toBe(true);
    }
    expect(pro.scores.every((r) => r.summary), 'PRO has the written why').toBe(true);

    // Gated places: no rating, marked gated; the picks are rated. PRO has no gated place.
    const liteGated = lite.forecast.filter((r) => r.gated);
    expect(liteGated.length, 'LITE has gated rows').toBeGreaterThan(0);
    expect(liteGated.every((r) => r.rating == null)).toBe(true);
    expect(lite.forecast.filter((r) => !r.gated).every((r) => r.rating != null)).toBe(true);
    expect(pro.forecast.filter((r) => r.gated), 'PRO is unrestricted').toEqual([]);
    expect(pro.forecast.every((r) => r.rating != null)).toBe(true);
    expect(new Set(liteGated.map((r) => r.locationName)).size).toBe(6);

    // The briefing: slots follow the picks, the picks (best bets, window picks) are gone, PRO has them.
    const liteSlots = slotsOf(lite.briefing);
    expect(lite.briefing.allowedLocationIds.length, 'the picks, as location ids').toBe(12);
    expect(liteSlots.filter((s) => s.gated).every((s) => !lite.briefing.allowedLocationIds.includes(s.locationId)))
      .toBe(true);
    expect(liteSlots.filter((s) => !s.gated).every((s) => lite.briefing.allowedLocationIds.includes(s.locationId)))
      .toBe(true);
    expect(liteSlots.filter((s) => s.gated).length, 'some slots are gated').toBeGreaterThan(0);
    expect(liteSlots.filter((s) => s.fierySkyPotential != null || s.goldenHourPotential != null || s.claudeSummary),
      'no scores or summaries on a LITE slot').toEqual([]);
    expect(windowsOf(lite.briefing).filter((w) => w.pick), 'no window pick for LITE').toEqual([]);
    expect(lite.briefing.bestBets, 'no best bets for LITE').toEqual([]);
    expect(windowsOf(pro.briefing).filter((w) => w.pick).length, 'PRO has window picks').toBeGreaterThan(0);
    expect(slotsOf(pro.briefing).every((s) => !s.gated && s.fierySkyPotential != null && s.claudeSummary)).toBe(true);
    expect(pro.briefing.allowedLocationIds, 'PRO is not shaped by picks').toBeUndefined();
  });
});

test.describe('PRO reader', () => {
  test('none of the LITE locks appear, on the Plan, the Map, the callout and the location sheet', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page, 'pro');

    // Ask: a live field, no Pro lens, no lock.
    const field = page.getByTestId('ask-field');
    await expect(field).toBeVisible();
    await expect(field).not.toHaveAttribute('data-locked', 'true');
    await expect(field.locator('.wf-lens-pro')).toHaveCount(0);
    await expect(page.getByTestId('ask-field-upsell')).toHaveCount(0);

    // The Plan tab first: no Best bets door.
    await openTab(page, 'plan');
    await expect(page.getByTestId('wf-heat-bestbets-upsell-door')).toHaveCount(0);

    // The Map: no gated chip, and every visible chip is rated.
    await openMapTab(page);
    await expect(page.getByTestId('map-label-chip-outside')).toHaveCount(0);
    await expect(page.locator('[data-testid="map-label-chip"][data-gated="true"]')).toHaveCount(0);
    await expect(page.getByRole('button', { name: OUTSIDE_CHIP })).toHaveCount(0);

    // The callout and the location sheet: no outside wording, no scores door.
    await visibleChips(page).first().click();
    await expect(page.getByTestId('map-callout')).toBeVisible();
    await expect(page.getByTestId('map-callout-score')).not.toContainText('Outside your sub-regions');
    await page.getByTestId('map-callout-reason').click();
    const sheet = page.getByTestId('location-sheet');
    await expect(sheet).toBeVisible();
    await expect(sheet.getByTestId('location-sheet-outside')).toHaveCount(0);
    await expect(sheet.getByTestId('location-sheet-outside-block')).toHaveCount(0);
    await expect(sheet.getByTestId('wf-scores-upsell-door')).toHaveCount(0);
    await expect(sheet.getByTestId('location-sheet-rating').first()).toBeVisible();
    await page.getByTestId('location-sheet-close').click();
    await expect(sheet).toHaveCount(0);
    // The open callout would sit over the chip the next step clicks.
    await page.getByTestId('map-callout-close').click();
    await expect(page.getByTestId('map-callout')).toHaveCount(0);

    // "How was it?" on a past window: live, with no Pro pill (the rating itself is written in roles-write.spec.js).
    await stepIntoPast(page);
    await clickMiddleChip(page);
    const block = page.getByTestId('outcome-rating-block');
    await expect(block).toBeVisible();
    await expect(block).toHaveAttribute('data-state', /^(unrated|rated|not-seen)$/);
    await expect(block.getByTestId('pro-pill')).toHaveCount(0);
  });
});
