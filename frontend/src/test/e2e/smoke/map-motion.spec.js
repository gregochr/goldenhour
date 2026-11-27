/**
 * The card opens into the map (docs/engineering/screen-spec-plan.md section 6, `utils/mapMotion.js`), in the real
 * Chromium, which has `document.startViewTransition`.
 *
 * <p>What only a real browser can show, and what the jsdom unit tests cannot:
 *  - the morph really runs (a `::view-transition-group(wf-map-morph)` animation exists while it does), from a card
 *    thumbnail, and its names are all taken off afterwards;
 *  - the map is visible and sized once it has finished (the live Leaflet map was hidden with `visibility`, never
 *    `display`, so it measured a real box the whole time);
 *  - the Back entry still exists after an animated open (the update runs inside the transition's callback, a frame
 *    after the press, so the dialog-history push must still find its user activation): Back closes the view;
 *  - the close button and Escape morph back and focus lands on the card, never on the body.
 *
 * <p>No `page.addStyleTag` (the page's CSP is `style-src 'self'`); the spec observes through an init script.
 */
import { test, expect, loginAndWaitForForecast, visibleChips } from './fixture.js';

/** Records every view transition the page starts: its names while running, and how long it took to finish. */
async function recordTransitions(page) {
  await page.addInitScript(() => {
    window.__vt = [];
    const original = document.startViewTransition?.bind(document);
    if (!original) return;
    document.startViewTransition = (callback) => {
      const record = { start: performance.now(), readyAt: null, pseudo: [], finishedAt: null };
      window.__vt.push(record);
      const transition = original(callback);
      transition.ready.then(() => {
        record.readyAt = performance.now();
        record.pseudo = document.getAnimations()
          .map((a) => a.effect?.pseudoElement)
          .filter((name) => name && name.includes('wf-map-morph'));
      }).catch(() => {});
      transition.finished.then(() => { record.finishedAt = performance.now(); }).catch(() => {});
      return transition;
    };
  });
}

/** Presses the nth visible card (a pointer lands on the card itself). */
async function pressCard(page, index = 2) {
  await expect.poll(() => page.getByTestId('wf-heat-card').filter({ visible: true }).count()).toBeGreaterThanOrEqual(3);
  const card = page.getByTestId('wf-heat-card').filter({ visible: true }).nth(index);
  await card.scrollIntoViewIfNeeded();
  await card.click();
  return card;
}

/** The back-depth the dialog history reports once it is idle. */
async function settledDepth(page, depth) {
  await expect
    .poll(() => page.evaluate(() => document.documentElement.getAttribute('data-back-depth')))
    .toBe(String(depth));
}

/** The map is visible, drawn at a real size, and no morph class is left behind. */
async function expectMapDrawn(page) {
  await expect(page.getByTestId('map-screen')).toBeVisible();
  await expect(page.getByTestId('map-screen')).not.toHaveClass(/wf-map-arriving|wf-vt-frame/);
  await expect.poll(() => page.evaluate(() => {
    const el = document.querySelector('[data-testid="map-screen"] .leaflet-container');
    if (!el) return 0;
    const box = el.getBoundingClientRect();
    return Math.min(box.width, box.height);
  })).toBeGreaterThan(100);
  const visibility = await page.evaluate(() => getComputedStyle(document.querySelector('.wf-map-tab')).visibility);
  expect(visibility).toBe('visible');
  await expect(page.locator('[data-testid="map-labels"][data-placed="true"]')).toHaveCount(1);
  await expect.poll(() => visibleChips(page).count()).toBeGreaterThan(0);
  expect(await page.evaluate(() => document.querySelectorAll('.wf-vt-card, .wf-vt-frame').length)).toBe(0);
}

for (const [label, viewport] of [['desktop', { width: 1280, height: 800 }], ['phone 390', { width: 390, height: 844 }]]) {
  test.describe(`the card opens into the map (${label})`, () => {
    test.use({ viewport });

    test('opens from a card thumbnail with a morph, and the map ends visible and sized', async ({ page, browserName }) => {
      test.skip(browserName !== 'chromium', 'View Transitions are exercised in Chromium');
      test.setTimeout(90_000);
      await recordTransitions(page);
      await loginAndWaitForForecast(page, 'admin');

      const card = await pressCard(page);
      await expect(page.getByTestId('map-screen')).toBeVisible();
      await expectMapDrawn(page);

      const records = await page.evaluate(() => window.__vt);
      expect(records.length, 'one view transition for one open').toBe(1);
      expect(records[0].pseudo.some((name) => name.includes('::view-transition-group(wf-map-morph)')), 'the morph group animated').toBe(true);
      expect(records[0].finishedAt, 'the transition finished').not.toBeNull();
      // The animation itself (from the moment the browser started it) is the morph's 230 ms, not the 600 ms safety timeout.
      const animationMs = records[0].finishedAt - records[0].readyAt;
      expect(animationMs, `the morph ran ${Math.round(animationMs)} ms`).toBeGreaterThan(180);
      // Upper bound is loose on purpose: a cold first open mounts the lazy pane on the main thread while the morph runs,
      // and `finished` resolves there. The 600 ms safety timer starts only after the update, so it is not what ends this.
      expect(animationMs).toBeLessThan(900);
      console.log(`open: press to finished ${Math.round(records[0].finishedAt - records[0].start)} ms, animation ${Math.round(animationMs)} ms`);
      // Focus went to the title (outside the pane) and is not on <body>.
      expect(await page.evaluate(() => document.activeElement?.tagName)).not.toBe('BODY');
      expect(card).toBeTruthy();
    });

    test('the close button morphs back into the card and focus lands on the card', async ({ page, browserName }) => {
      test.skip(browserName !== 'chromium', 'View Transitions are exercised in Chromium');
      test.setTimeout(90_000);
      await recordTransitions(page);
      await loginAndWaitForForecast(page, 'admin');
      const card = await pressCard(page);
      await expectMapDrawn(page);

      await page.getByTestId('map-screen-close').click();
      await expect(page.getByTestId('map-screen')).toBeHidden();
      await expect(card.locator('.wf-hc-hit')).toBeFocused();
      const records = await page.evaluate(() => window.__vt);
      expect(records.length, 'an open and a close').toBe(2);
      expect(records[1].pseudo.some((name) => name.includes('wf-map-morph')), 'the close morphed').toBe(true);
      await expect.poll(() => page.evaluate(() => (
        document.querySelectorAll('.wf-vt-card, .wf-vt-frame, .wf-map-arriving').length
          + document.documentElement.classList.contains('wf-vt-closing')
      ))).toBe(0);
      await settledDepth(page, 0);

      // Reopening finds the same mounted pane, drawn at its real size.
      await pressCard(page, 3);
      await expectMapDrawn(page);
    });

    test('Escape morphs back too', async ({ page, browserName }) => {
      test.skip(browserName !== 'chromium', 'View Transitions are exercised in Chromium');
      test.setTimeout(90_000);
      await recordTransitions(page);
      await loginAndWaitForForecast(page, 'admin');
      const card = await pressCard(page);
      await expectMapDrawn(page);

      await page.getByTestId('map-screen-title').focus();
      await page.keyboard.press('Escape');
      await expect(page.getByTestId('map-screen')).toBeHidden();
      await expect(card.locator('.wf-hc-hit')).toBeFocused();
      expect((await page.evaluate(() => window.__vt)).length).toBe(2);
    });

    test('Back after an animated open closes the view: the history entry was pushed under activation', async ({ page, browserName }) => {
      test.skip(browserName !== 'chromium', 'View Transitions are exercised in Chromium');
      test.setTimeout(90_000);
      await recordTransitions(page);
      await loginAndWaitForForecast(page, 'admin');
      const baseline = await page.evaluate(() => window.navigation.currentEntry.index);

      const card = await pressCard(page);
      await expectMapDrawn(page);
      await settledDepth(page, 1);
      // The proof that the push happened: the entry is ours, one above the baseline.
      expect(await page.evaluate(() => window.navigation.currentEntry.index)).toBe(baseline + 1);
      expect(await page.evaluate(() => window.history.state?.phLayer ?? null)).not.toBeNull();
      expect((await page.evaluate(() => window.__vt)).length, 'the open really was a morph').toBe(1);

      await page.goBack();
      await expect(page.getByTestId('map-screen')).toBeHidden();
      await settledDepth(page, 0);
      expect(await page.evaluate(() => window.navigation.currentEntry.index)).toBe(baseline);
      await expect(card.locator('.wf-hc-hit')).toBeFocused();
      // Back is instant: no second transition.
      expect((await page.evaluate(() => window.__vt)).length).toBe(1);
    });
  });
}

test.describe('reduced motion', () => {
  test.use({ viewport: { width: 1280, height: 800 }, reducedMotion: 'reduce' });

  test('the morph becomes a fade and the map still ends visible and sized', async ({ page, browserName }) => {
    test.skip(browserName !== 'chromium', 'View Transitions are exercised in Chromium');
    test.setTimeout(90_000);
    await recordTransitions(page);
    await loginAndWaitForForecast(page, 'admin');
    await pressCard(page);
    await expectMapDrawn(page);
    const records = await page.evaluate(() => window.__vt);
    expect(records[0].pseudo.some((name) => name.includes('::view-transition-group(wf-map-morph)')), 'the group no longer moves').toBe(false);
    expect(records.some((r) => r.pseudo.some((name) => name.includes('::view-transition-old(wf-map-morph)')
      || name.includes('::view-transition-new(wf-map-morph)'))), 'old and new fade').toBe(true);
  });
});
