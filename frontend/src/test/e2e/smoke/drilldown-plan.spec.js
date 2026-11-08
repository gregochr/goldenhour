import { devices } from '@playwright/test';
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

  /**
   * The heat card's control is a stretched button inside a `div` card (docs/engineering/a11y-heat-card-plan.md): the
   * keyboard and assistive tech use the button, a pointer lands on the card. These two tests pin both routes in a
   * real browser, where `pointer-events` and focus restoration are real.
   */
  test('keyboard: Tab onto a card, Enter opens its window, closing it returns focus to the same button', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openTab(page, 'plan');
    const buttons = page.getByTestId('wf-heat-card-button');
    await expect.poll(() => buttons.count()).toBeGreaterThanOrEqual(6);

    // Start on one card's button and Tab until the next card's button takes focus: one stop per card.
    await buttons.nth(1).focus();
    const stops = [];
    const everyStop = [];
    for (let i = 0; i < 8 && stops.length < 1; i += 1) {
      await page.keyboard.press('Tab');
      const hit = await page.evaluate(() => {
        const el = document.activeElement;
        return { testId: el?.getAttribute('data-testid') ?? null, name: el?.getAttribute('aria-labelledby') ?? null };
      });
      everyStop.push(hit.testId);
      if (hit.testId === 'wf-heat-card-button') stops.push(hit.name);
    }
    expect(stops, 'Tab reached the next card\'s button').toHaveLength(1);
    // One stop per card: the card `div` is never a tab stop, and the very next stop after card 1 is card 2's button.
    expect(everyStop, 'no tab stop is the card itself').not.toContain('wf-heat-card');
    expect(stops[0], 'the stop after card 1 is card 2\'s button').toBe(await buttons.nth(2).getAttribute('aria-labelledby'));
    const focusedButton = page.locator('[data-testid="wf-heat-card-button"]:focus');
    await expect(focusedButton).toHaveAttribute('aria-labelledby', stops[0]);

    await page.keyboard.press('Enter');
    const windowSheet = page.getByTestId('window-sheet');
    await expect(windowSheet).toBeVisible();

    await page.keyboard.press('Escape');
    await expect(windowSheet).toHaveCount(0);
    await expect(focusedButton, 'focus returns to the button that opened the window').toHaveAttribute('aria-labelledby', stops[0]);

    // Space opens it too.
    await page.keyboard.press(' ');
    await expect(windowSheet).toBeVisible();
    await page.getByTestId('window-sheet-close').click();
    await expect(windowSheet).toHaveCount(0);
  });

  test('pointer: the legend\'s overhang opens its window, and the hit button never takes a pointer', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openTab(page, 'plan');
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);

    // `pointer-events: none` on `.wf-hc-hit`: the element under the pointer at a card's centre, and under the
    // histogram with its `title` tooltip, is the card's own content, never the button. Were the button to take
    // the pointer, those tooltips and the card's `cursor` would stop working. (Scrolled into view first:
    // `elementFromPoint` answers null for a point off the viewport.)
    const under = await page.evaluate(() => {
      const card = document.querySelector('[data-testid="wf-heat-legend"]').closest('[data-testid="wf-heat-card"]');
      const hist = card.querySelector('[data-testid="wf-heat-spread"]');
      hist.scrollIntoView({ block: 'center' });
      const r = hist.getBoundingClientRect();
      const inHist = document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
      const middle = card.getBoundingClientRect();
      const inMiddle = document.elementFromPoint(middle.x + middle.width / 2, middle.y + middle.height / 2);
      const isHit = (el) => !!el?.closest('.wf-hc-hit');
      return {
        histogramHit: isHit(inHist),
        centreHit: isHit(inMiddle),
        inHistogram: !!inHist && hist.contains(inHist),
        titled: !!inHist?.closest('[title]'),
        centreIsCard: !!inMiddle && card.contains(inMiddle),
        cursor: inMiddle ? getComputedStyle(inMiddle).cursor : null,
      };
    });
    expect(under.histogramHit, 'the histogram is under the pointer, not the hit button').toBe(false);
    expect(under.centreHit, 'the card\'s centre is under the pointer, not the hit button').toBe(false);
    expect(under.inHistogram, 'the point probed is the histogram').toBe(true);
    expect(under.centreIsCard, 'the point probed is inside the card').toBe(true);
    expect(under.titled, 'the histogram\'s title tooltip is reachable').toBe(true);
    expect(under.cursor, 'the card keeps its pointer cursor').toBe('pointer');

    // The pick legend rides the card's top border and overhangs it by ~8px: a click on its TOP EDGE is above the
    // card's own box, so only the legend's span can receive it, and it must still open the window.
    await page.evaluate(() => {
      document.querySelector('[data-testid="wf-heat-legend"]').scrollIntoView({ block: 'center' });
    });
    const legend = page.getByTestId('wf-heat-legend').first();
    await expect(legend).toBeVisible();
    const box = await legend.boundingBox();
    expect(box, 'the legend is laid out').not.toBeNull();
    const probe = { x: box.x + box.width / 2, y: box.y + 1 };
    const legendCard = page.getByTestId('wf-heat-card').filter({ has: page.getByTestId('wf-heat-legend') }).first();
    const cardBox = await legendCard.boundingBox();
    expect(probe.y, 'the probe is ABOVE the card\'s own box, so only the legend can receive it').toBeLessThan(cardBox.y);
    const atTop = await page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.getAttribute('data-testid') ?? null, probe);
    expect(atTop, 'the legend\'s top edge lands on the legend').toBe('wf-heat-legend');

    await page.mouse.click(probe.x, probe.y);
    const windowSheet = page.getByTestId('window-sheet');
    await expect(windowSheet).toBeVisible();
    // The click left focus on the button (the opener the sheet returns it to), not on <body>.
    await page.getByTestId('window-sheet-close').click();
    await expect(windowSheet).toHaveCount(0);
    await expect(legendCard.getByTestId('wf-heat-card-button'), 'focus is on the button of the card that was clicked').toBeFocused();
  });
});

/**
 * Touch (a11y-heat-card-plan.md, review): on a coarse pointer the hit button takes the touch and everything else in
 * the card passes it through, so TalkBack and VoiceOver explore-by-touch (which follow layout hit testing) land on
 * the NAMED button; on a fine pointer the card's own spans take it, so their tooltips work.
 */
// `defaultBrowserType` cannot be set inside a describe.
const PIXEL_7 = { ...devices['Pixel 7'] };
delete PIXEL_7.defaultBrowserType;

/** Opens the Plan and returns the elements at four points of one card: its centre, canvas, time and verdict. */
async function elementsAtCardPoints(page) {
  await openTab(page, 'plan');
  await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
  return page.evaluate(() => {
    const card = document.querySelectorAll('[data-testid="wf-heat-card"]')[2];
    card.scrollIntoView({ block: 'center' });
    const centre = (el) => {
      const r = el.getBoundingClientRect();
      return document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2);
    };
    const kind = (el) => (el?.closest('.wf-hc-hit') ? 'hit' : el && card.contains(el) ? 'card-content' : 'outside');
    return {
      coarse: matchMedia('(pointer: coarse)').matches,
      points: ['card', 'wf-heat-canvas', 'wf-heat-time', 'wf-heat-verdict'].map((id) => {
        const target = id === 'card' ? card : card.querySelector(`[data-testid="${id}"]`);
        const hit = centre(target);
        return { id, kind: kind(hit), tag: hit?.tagName, titled: !!hit?.closest('[title]') };
      }),
    };
  });
}

test.describe('Heat card hit test on a coarse pointer (Pixel 7)', () => {
  test.use({ ...PIXEL_7 });

  test('every probed point of the card resolves to the named button, and a tap opens the window once', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    const seen = await elementsAtCardPoints(page);
    expect(seen.coarse, 'the Pixel 7 profile is a coarse pointer').toBe(true);
    seen.points.forEach((point) => expect(point.kind, `${point.id} resolves to the hit button`).toBe('hit'));

    await page.evaluate(() => {
      window.__clicks = 0;
      document.addEventListener('click', () => { window.__clicks += 1; }, true);
    });
    // A real touch at the verdict-time text's centre: the point sits on the card's spans, which let it through.
    const time = await page.getByTestId('wf-heat-card').nth(2).getByTestId('wf-heat-time').boundingBox();
    await page.touchscreen.tap(time.x + time.width / 2, time.y + time.height / 2);
    const windowSheet = page.getByTestId('window-sheet');
    await expect(windowSheet).toBeVisible();
    expect(await page.evaluate(() => window.__clicks), 'a tap is one click, so one open').toBe(1);
    await expect(page.getByTestId('window-sheet')).toHaveCount(1);
    await page.getByTestId('window-sheet-close').click();
    await expect(windowSheet).toHaveCount(0);
  });
});

test.describe('Heat card hit test on a fine pointer (desktop)', () => {
  test('the probed points resolve to the card\'s own content, so the tooltips work', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    const seen = await elementsAtCardPoints(page);
    expect(seen.coarse).toBe(false);
    seen.points.forEach((point) => expect(point.kind, `${point.id} resolves to the card's content`).toBe('card-content'));
  });
});
