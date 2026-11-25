import { test, expect, loginAndWaitForForecast, openMapView, closeMapView } from './fixture.js';

/**
 * Screen spec P4 in a real browser: the phone lens collapses past This week and the page does not move, the masthead is one
 * line, and the map view's header carries the window's light. jsdom has no layout, so the heights here are the only place
 * "no jump" and "one line" are actually measured.
 */

/** Gives the seeded reader a home for this page only (the seed has none), so the masthead is in its steady, one-line state. */
async function withHome(page) {
  await page.route(/\/api\/user\/settings$/, async (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    const response = await route.fetch();
    return route.fulfill({ response, json: { ...(await response.json()), homePostcode: 'NE66 1NG', homePlaceName: 'Alnwick' } });
  });
  await page.route(/\/api\/user\/settings\/light$/, (route) => route.fulfill({
    json: {
      label: 'Home · NE66 1NG', shortLabel: 'NE66 1NG', civilDawn: '07:11', sunrise: '07:54', sunset: '15:52', civilDusk: '16:34',
      stops: [{ key: 'NIGHT_START', position: 0 }, { key: 'CIVIL_DAWN', position: 0.3 }, { key: 'SUNRISE', position: 0.33 }, { key: 'SUNSET', position: 0.66 }, { key: 'CIVIL_DUSK', position: 0.7 }, { key: 'NIGHT_END', position: 1 }],
    },
  }));
}

/** Two frames, so an effect the last scroll or focus change triggered (an observer's callback, a commit) has run. */
async function settle(page) {
  await page.evaluate(() => new Promise((resolve) => {
    requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
  }));
}

/** Fails the test (skips, with the reason) if the seeded page is too short to scroll past This week. */
async function requireRoomToScroll(page) {
  const room = await page.evaluate(() => {
    const section = document.getElementById('wf-this-week');
    return document.documentElement.scrollHeight - window.innerHeight - (section.getBoundingClientRect().bottom + window.scrollY);
  });
  test.skip(room < 120, `the seeded forecast leaves only ${Math.round(room)}px below This week: not enough page to scroll past it`);
}

/** Scrolls (in one jump, the way a fling or the Further ahead jump does) so the end of This week is just above the viewport. */
async function scrollPastWeek(page) {
  await page.evaluate(() => {
    const section = document.getElementById('wf-this-week');
    window.scrollTo(0, window.scrollY + section.getBoundingClientRect().bottom + 40);
  });
}

for (const W of [360, 375, 390]) {
  test.describe(`The phone masthead and lens at ${W}`, () => {
    test.use({ viewport: { width: W, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });

    test('the masthead is one line: brand, Find and the menu, with no times and no origin chip', async ({ page }) => {
      await withHome(page);
      await loginAndWaitForForecast(page, 'pro');
      const mast = await page.getByTestId('window-first-masthead').boundingBox();
      expect(mast.height, 'the whole masthead block, rule included').toBeLessThanOrEqual(52);
      // And nothing sits above it: the banner block's empty margin is not drawn, so the lens starts right under the line.
      expect(mast.y, 'no empty banner margin above the masthead').toBeLessThanOrEqual(1);
      expect((await page.getByTestId('window-first-lens').boundingBox()).y, 'the lens starts under the masthead').toBeLessThanOrEqual(53);
      await expect(page.getByTestId('window-first-search')).toBeVisible();
      await expect(page.getByTestId('window-first-search')).toHaveAccessibleName('Find');
      await expect(page.getByTestId('masthead-menu-button')).toBeVisible();
      await expect(page.getByTestId('masthead-phone-times')).toHaveCount(0);
      await expect(page.getByTestId('window-first-origin-chip')).toHaveCount(0);
      await expect(page.getByTestId('window-first-tickline')).toHaveCount(0);
      // Find is one tap away, and opens the search dialog.
      await page.getByTestId('window-first-search').click();
      await expect(page.getByRole('dialog')).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth), 'no horizontal scroll').toBeLessThanOrEqual(W);
    });

    test('with no postcode saved the masthead adds ONE slim nudge line, and Find is still in the brand row', async ({ page }) => {
      await loginAndWaitForForecast(page, 'pro');
      const nudge = page.getByTestId('masthead-set-postcode');
      await expect(nudge).toBeVisible();
      expect((await page.getByTestId('window-first-tickline').boundingBox()).height, 'a slim line').toBeLessThanOrEqual(40);
      await expect(page.getByTestId('window-first-mast-row').getByTestId('window-first-search')).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth), 'no horizontal scroll').toBeLessThanOrEqual(W);
    });

    test('scrolling past This week collapses the lens to one line, a tap opens it, the top resets it, and nothing jumps', async ({ page }) => {
      test.setTimeout(60_000);
      await loginAndWaitForForecast(page, 'pro');
      const lens = page.getByTestId('window-first-lens');
      const summary = page.getByTestId('window-first-lens-summary');
      await expect(summary).toBeHidden();
      const fullHeight = (await lens.boundingBox()).height;
      const documentHeight = await page.evaluate(() => document.documentElement.scrollHeight);
      // The SECTION, not its heading: the heading is sticky, so its page position moves with the scroll.
      const furtherTop = () => page.evaluate(() => Math.round(
        document.getElementById('wf-further-ahead').getBoundingClientRect().top + window.scrollY,
      ));
      const furtherBefore = await furtherTop();

      await requireRoomToScroll(page);
      await scrollPastWeek(page);
      await expect(summary).toBeVisible();
      await expect(summary).toHaveAttribute('aria-expanded', 'false');
      await expect(summary).toHaveAccessibleName(/^Drive .* spots?/);
      const collapsed = await lens.boundingBox();
      expect(collapsed.height, 'one line').toBeLessThanOrEqual(40);
      // The document is exactly as tall as it was, and what sits below the lens has not moved in the page.
      expect(await page.evaluate(() => document.documentElement.scrollHeight)).toBe(documentHeight);
      expect(await furtherTop()).toBe(furtherBefore);
      // The bars publish the collapsed height, so the sticky heading sits right under the line.
      const barH = await page.getByTestId('window-first-shell').evaluate((el) => el.style.getPropertyValue('--wf-bar-h'));
      expect(Number.parseFloat(barH)).toBeCloseTo(collapsed.height, 0);

      // A tap opens it in place, the same button, expanded, and it stays open through a further scroll.
      await summary.click();
      await expect(summary).toHaveAttribute('aria-expanded', 'true');
      expect((await lens.boundingBox()).height).toBeGreaterThan(fullHeight - 2);
      await page.evaluate(() => window.scrollBy(0, 120));
      await expect(summary).toHaveAttribute('aria-expanded', 'true');
      // The controls are reachable again.
      await expect(page.getByTestId('window-first-lens-tiers')).toBeVisible();

      // Back at the top it resets, and a reset never moves focus: the tapped button still holds it, so it stays drawn.
      await page.evaluate(() => window.scrollTo(0, 0));
      await expect(summary).toBeFocused();
      await expect(summary).toBeVisible();
      // Once focus has moved on, the resting lens is back, and scrolling past the week collapses it once more.
      await summary.blur();
      await expect(summary).toBeHidden();
      expect(Math.abs((await lens.boundingBox()).height - fullHeight)).toBeLessThanOrEqual(1);
      await scrollPastWeek(page);
      await expect(summary).toHaveAttribute('aria-expanded', 'false');
    });

    test('the lens never collapses under a focused chip, and collapses on the blur', async ({ page }) => {
      await loginAndWaitForForecast(page, 'pro');
      const chip = page.getByTestId('window-first-lens-tiers-option').first();
      await requireRoomToScroll(page);
      await chip.focus();
      await scrollPastWeek(page);
      await settle(page);
      await expect(page.getByTestId('window-first-lens-summary')).toBeHidden();
      await expect(page.getByTestId('window-first-lens')).not.toHaveClass(/wf-lens-collapsed/);
      await chip.blur();
      await expect(page.getByTestId('window-first-lens-summary')).toBeVisible();
    });

    test('a TAPPED chip (Android focuses it and a touch scroll never blurs it) does not hold the lens open', async ({ page }) => {
      await loginAndWaitForForecast(page, 'pro');
      await requireRoomToScroll(page);
      // A real pointer press: focus lands on the chip without :focus-visible.
      await page.getByTestId('window-first-lens-floors-option').first().click();
      await scrollPastWeek(page);
      await settle(page);
      await expect(page.getByTestId('window-first-lens-summary')).toBeVisible();
      await expect(page.getByTestId('window-first-lens')).toHaveClass(/wf-lens-collapsed/);
      // Focus was moved into the persistent summary, never left in the hidden body or on <body>.
      await expect(page.getByTestId('window-first-lens-summary')).toBeFocused();
    });

    test('the real Further ahead jump collapses the lens, and the heading lands visible below the collapsed line', async ({ page }) => {
      await loginAndWaitForForecast(page, 'pro');
      await requireRoomToScroll(page);
      await page.getByTestId('further-ahead-jump').click();
      await expect(page.getByTestId('further-ahead-heading')).toBeFocused();
      await settle(page);
      await expect(page.getByTestId('window-first-lens-summary')).toBeVisible();
      await expect(page.getByTestId('window-first-lens')).toHaveClass(/wf-lens-collapsed/);
      const lens = await page.getByTestId('window-first-lens').boundingBox();
      const heading = await page.getByTestId('further-ahead-heading').boundingBox();
      expect(heading.y, 'the heading is not hidden behind the collapsed lens').toBeGreaterThanOrEqual(lens.y + lens.height - 1);
      expect(heading.y + heading.height).toBeLessThanOrEqual(844);
    });
  });
}

test.describe('The lens on desktop and tablet is unchanged', () => {
  for (const [name, viewport] of [['desktop', { width: 1280, height: 800 }], ['tablet', { width: 768, height: 1024 }]]) {
    test(`${name}: scrolling to the bottom never collapses it and draws no summary`, async ({ page }) => {
      await page.setViewportSize(viewport);
      await loginAndWaitForForecast(page, 'pro');
      const before = (await page.getByTestId('window-first-lens').boundingBox()).height;
      await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
      await expect(page.getByTestId('window-first-lens-summary')).toHaveCount(0);
      await expect(page.getByTestId('window-first-lens-spacer')).toHaveCount(0);
      expect(Math.abs((await page.getByTestId('window-first-lens').boundingBox()).height - before)).toBeLessThanOrEqual(1);
    });
  }

  test('desktop keeps the origin chip and today\'s times, labelled "today"', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await withHome(page);
    await loginAndWaitForForecast(page, 'pro');
    await expect(page.getByTestId('window-first-origin-chip')).toBeVisible();
    await expect(page.getByTestId('window-first-search')).toHaveClass(/wf-tick-search/);
    await expect(page.getByTestId('masthead-light-today')).toBeVisible();
  });
});

test.describe('The 640-809px band keeps the tick row on one line', () => {
  for (const width of [640, 700, 768, 809]) {
    test(`${width}px: the sr-only today label does not wrap the times`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await withHome(page);
      await loginAndWaitForForecast(page, 'pro');
      const tick = await page.getByTestId('window-first-tickline').boundingBox();
      expect(tick.height, 'one line (45px), not two (63px)').toBeLessThanOrEqual(50);
      // Read, not drawn: a 1px box, still in the row's spoken answer.
      const today = page.getByTestId('masthead-light-today');
      await expect(today).toHaveText('today');
      expect((await today.boundingBox())?.width ?? 0).toBeLessThanOrEqual(2);
    });
  }

  test('810px and up: the label is drawn', async ({ page }) => {
    await page.setViewportSize({ width: 810, height: 900 });
    await withHome(page);
    await loginAndWaitForForecast(page, 'pro');
    expect((await page.getByTestId('window-first-tickline').boundingBox()).height).toBeLessThanOrEqual(50);
    expect((await page.getByTestId('masthead-light-today').boundingBox()).width).toBeGreaterThan(20);
  });
});

test.describe('The map view header carries the light', () => {
  test('a window opened from a card names its blue and golden hour at a place, from the served score rows', async ({ page }) => {
    test.setTimeout(90_000);
    await page.setViewportSize({ width: 1280, height: 800 });
    await loginAndWaitForForecast(page, 'pro');
    // Not "the third card": which windows are served, and which have a score row with a light time, depends on the date the
    // suite runs. Open each visible card in turn and keep the first whose header names its light.
    const count = await page.getByTestId('wf-heat-card').filter({ visible: true }).count();
    let found = false;
    for (let index = 0; index < Math.min(count, 8) && !found; index += 1) {
      await openMapView(page, index);
      found = await page.getByTestId('map-screen-light-golden').isVisible();
      if (!found) await closeMapView(page);
    }
    test.skip(!found, 'no visible window in the seeded forecast has a served light row');
    const facts = page.getByTestId('map-screen-facts');
    await expect(page.getByTestId('map-screen-light-golden')).toHaveText(/^golden \d\d:\d\d.\d\d:\d\d/);
    await expect(page.getByTestId('map-screen-light-blue')).toHaveText(/^blue \d\d:\d\d.\d\d:\d\d/);
    // Spoken: the place, and a comma between the facts.
    await expect(page.getByTestId('map-screen-light-golden').locator('.sr-only')).toHaveText(/^ at \S/);
    // The golden hour is in the golden colour; the blue hour is not.
    const colours = await facts.evaluate((el) => ({
      golden: getComputedStyle(el.querySelector('[data-testid="map-screen-light-golden"]')).color,
      blue: getComputedStyle(el.querySelector('[data-testid="map-screen-light-blue"]')).color,
    }));
    expect(colours.golden).toBe('rgb(224, 165, 66)');
    expect(colours.blue).not.toBe(colours.golden);
    await closeMapView(page);
  });
});
