import { test, expect, loginAndWaitForForecast, openMapView, closeMapView } from './fixture.js';

/**
 * Screen spec D6 and P4 in a real browser: the lens belongs to This week (past it, at every width, it is hidden in place and
 * the page does not move), the phone masthead is one line, and the map view's header carries the window's light. jsdom has no
 * layout, so the heights here are the only place "no jump" and "one line" are actually measured.
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

/** What the lens and Further ahead measure right now: the page's height, where Further ahead sits, and the sticky bar. */
function lensProbe(page) {
  return page.evaluate(() => {
    const lens = document.querySelector('[data-testid="window-first-lens"]');
    const further = document.getElementById('wf-further-ahead');
    const bar = document.querySelector('[data-testid="further-ahead-bar"]');
    const shell = document.querySelector('[data-testid="window-first-shell"]');
    return {
      docHeight: document.scrollingElement.scrollHeight,
      furtherTop: Math.round(further.getBoundingClientRect().top + window.scrollY),
      furtherViewportTop: further.getBoundingClientRect().top,
      lensHeight: lens.getBoundingClientRect().height,
      aheadBarTop: bar.getBoundingClientRect().top,
      barVar: shell.style.getPropertyValue('--wf-bar-h'),
      aheadTopVar: shell.style.getPropertyValue('--wf-ahead-top'),
    };
  });
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

    test('scrolling past This week hides the lens in place, and nothing in the page moves', async ({ page }) => {
      test.setTimeout(60_000);
      await loginAndWaitForForecast(page, 'pro');
      await requireRoomToScroll(page);
      const lens = page.getByTestId('window-first-lens');
      await expect(lens).toHaveCSS('visibility', 'visible');
      const before = await lensProbe(page);
      await scrollPastWeek(page);
      await expect(lens).toHaveCSS('visibility', 'hidden');
      await expect(lens).toHaveAttribute('inert', '');
      const after = await lensProbe(page);
      // `visibility` keeps the sticky slot: the document is exactly as tall as it was and the section below has not moved.
      expect(after.docHeight).toBe(before.docHeight);
      expect(after.furtherTop).toBe(before.furtherTop);
      expect(after.lensHeight, 'the box is the same box').toBeCloseTo(before.lensHeight, 0);
      // This week reads the shared variables, which keep the FULL lens; Further ahead's bar sits at the top without it.
      expect(after.barVar).toBe(before.barVar);
      expect(after.aheadTopVar).toBe('0px');
      expect(Math.abs(after.aheadBarTop), 'Further ahead\'s bar sticks at top: 0').toBeLessThanOrEqual(1);
      // Back at the top the lens is there again, whole.
      await page.evaluate(() => window.scrollTo(0, 0));
      await expect(lens).toHaveCSS('visibility', 'visible');
      await expect(lens).not.toHaveAttribute('inert');
      expect(Math.abs((await lens.boundingBox()).height - before.lensHeight)).toBeLessThanOrEqual(1);
    });

    test('the lens stays drawn under a keyboard-focused chip, and goes on the blur', async ({ page }) => {
      await loginAndWaitForForecast(page, 'pro');
      const chip = page.getByTestId('window-first-lens-tiers-option').first();
      await requireRoomToScroll(page);
      await chip.focus();
      await scrollPastWeek(page);
      await settle(page);
      await expect(page.getByTestId('window-first-lens')).toHaveCSS('visibility', 'visible');
      await chip.blur();
      await expect(page.getByTestId('window-first-lens')).toHaveCSS('visibility', 'hidden');
    });

    test('a TAPPED chip (Android focuses it and a touch scroll never blurs it) does not hold the lens: focus moves to Further ahead', async ({ page }) => {
      await loginAndWaitForForecast(page, 'pro');
      await requireRoomToScroll(page);
      // A real pointer press: focus lands on the chip without :focus-visible.
      await page.getByTestId('window-first-lens-floors-option').first().click();
      await scrollPastWeek(page);
      await settle(page);
      await expect(page.getByTestId('window-first-lens')).toHaveCSS('visibility', 'hidden');
      // Focus was moved to Further ahead's heading, never left in the inert lens or on <body>.
      await expect(page.getByTestId('further-ahead-heading')).toBeFocused();
    });

    test('the real Further ahead jump lands with no lens and no gap: the section at the top, its bar and heading right there', async ({ page }) => {
      await loginAndWaitForForecast(page, 'pro');
      await requireRoomToScroll(page);
      await page.getByTestId('further-ahead-jump').click();
      await expect(page.getByTestId('further-ahead-heading')).toBeFocused();
      await settle(page);
      await expect(page.getByTestId('window-first-lens')).toHaveCSS('visibility', 'hidden');
      const probe = await lensProbe(page);
      expect(Math.abs(probe.aheadBarTop), 'the bar is at top: 0').toBeLessThanOrEqual(1);
      // No blank, lens-sized slot above the heading with the tail of This week showing in it.
      expect(Math.abs(probe.furtherViewportTop), 'the section starts at the top').toBeLessThanOrEqual(2);
      const heading = await page.getByTestId('further-ahead-heading').boundingBox();
      expect(heading.y, 'the heading is in the first line of the screen').toBeLessThanOrEqual(24);
    });

    test('the This week jump from Further ahead lands with its heading BELOW the lens, which has come back', async ({ page }) => {
      await loginAndWaitForForecast(page, 'pro');
      await requireRoomToScroll(page);
      await page.getByTestId('further-ahead-jump').click();
      await settle(page);
      await expect(page.getByTestId('window-first-lens')).toHaveCSS('visibility', 'hidden');
      // `focusSectionHeading(THIS_WEEK_HEADING_ID)`'s move, made directly: scroll the section to the top and focus its heading.
      await page.evaluate(() => {
        const heading = document.getElementById('wf-this-week-h');
        heading.closest('section').scrollIntoView({ block: 'start' });
        heading.focus({ preventScroll: true });
      });
      await settle(page);
      const lens = page.getByTestId('window-first-lens');
      await expect(lens).toHaveCSS('visibility', 'visible');
      const lensBox = await lens.boundingBox();
      const heading = await page.locator('#wf-this-week-h').boundingBox();
      expect(heading.y, 'WCAG 2.4.11: the focused heading is not under the lens').toBeGreaterThanOrEqual(lensBox.y + lensBox.height - 1);
    });
  });
}

for (const [name, viewport] of [['tablet', { width: 768, height: 1024 }], ['desktop', { width: 1280, height: 800 }]]) {
  test.describe(`The lens belongs to This week at ${name}`, () => {
    test.use({ viewport });

    test('is hidden in place past This week, and drawn again on the way back', async ({ page }) => {
      await loginAndWaitForForecast(page, 'pro');
      await requireRoomToScroll(page);
      const lens = page.getByTestId('window-first-lens');
      const before = await lensProbe(page);
      await scrollPastWeek(page);
      await expect(lens).toHaveCSS('visibility', 'hidden');
      const after = await lensProbe(page);
      expect(after.docHeight).toBe(before.docHeight);
      expect(after.furtherTop).toBe(before.furtherTop);
      expect(after.barVar).toBe(before.barVar);
      expect(Math.abs(after.aheadBarTop)).toBeLessThanOrEqual(1);
      await expect(page.getByTestId('window-first-lens-summary')).toHaveCount(0);
      await expect(page.getByTestId('window-first-lens-spacer')).toHaveCount(0);
      await page.evaluate(() => window.scrollTo(0, 0));
      await expect(lens).toHaveCSS('visibility', 'visible');
    });
  });
}

test.describe('The desktop masthead', () => {
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
