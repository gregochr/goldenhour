import fs from 'node:fs';
import { test, expect, loginAndWaitForForecast, openMapView } from './fixture.js';

/**
 * Phone-width overflow check for the Glance-to-Map Map surfaces. At 360, 375 and 390 wide, with mobile and touch
 * emulation (Chromium emulation, NOT a real phone: scrollbar and 100vw quirks are invisible in headless Chromium),
 * every state a reader reaches on the Map must keep every shown element inside the viewport and add no inner
 * horizontal scroller or clipped overflow.
 *
 * <p>The document's own scrollWidth is NOT the criterion: the map view is a fixed `100dvh` box with `overflow: hidden`,
 * Leaflet's container clips, and the sheets are position:fixed portals whose overflow-y:auto makes overflow-x auto,
 * so each hides overflow from a document-level check. The helper measures boxes, scrollers and clipped content instead.
 *
 * <p>320 wide is already known to overflow (index.css, the `.wf-tabs` note), so it is only measured, never asserted,
 * and only when M1_REPORT is set. The seeded fixture has a Met Office warning only in a later spec; none is assumed here.
 * M1_SHOTS=<dir> also saves a screenshot per state (a probe aid).
 */
const WIDTHS = [360, 375, 390];
const REPORT_WIDTHS = process.env.M1_REPORT ? [320, ...WIDTHS] : WIDTHS;
const SHOT_DIR = process.env.M1_SHOTS || '';

/**
 * Runs in the page. `W` is the fixed emulated width, never innerWidth.
 *
 * @returns {{boxes: string[], scrollers: string[], clipped: string[], ellipsised: string[]}}
 */
function measureInPage(W) {
  const name = (el) => `${el.getAttribute('data-testid') || el.tagName.toLowerCase()}${el.className && typeof el.className === 'string' ? `.${el.className.trim().split(/\s+/)[0]}` : ''}`;
  const root = document.getElementById('root');
  // The map view is `[data-map-screen]` (its header holds the window control); the sheets are portalled out of #root.
  const inScope = (el) => el.closest('[data-map-screen]') || !root.contains(el);
  // Leaflet's map, tile, overlay, marker and shadow panes extend past the viewport by design; the label chips are
  // culled by the label layer itself. The control container stays in.
  const skip = (el) => el.closest('.leaflet-pane, [data-testid="map-labels"]');
  const out = { boxes: [], scrollers: [], clipped: [], ellipsised: [] };
  for (const el of document.body.querySelectorAll('*')) {
    if (!inScope(el) || skip(el) || !el.checkVisibility()) continue;
    const r = el.getBoundingClientRect();
    // Zero-size and 1px boxes are visually hidden text (`.sr-only` and the like); the Leaflet container clips its
    // own panes by design (its scroll extent is the pane layer, not content).
    if (r.width <= 1 || r.height <= 1 || el.matches('.leaflet-container')) continue;
    if (r.left < -0.5 || r.right > W + 0.5) out.boxes.push(`${name(el)} ${r.left.toFixed(1)}..${r.right.toFixed(1)}`);
    const cs = getComputedStyle(el);
    const isScroller = ['auto', 'scroll'].includes(cs.overflowX) || ['auto', 'scroll'].includes(cs.overflowY);
    const isClip = ['hidden', 'clip'].includes(cs.overflowX);
    if (el.scrollWidth > el.clientWidth + 1) {
      const line = `${name(el)} scroll ${el.scrollWidth} > client ${el.clientWidth}`;
      // `.wf-tabs` is the documented 320px safeguard (index.css, the `.wf-tabs` note).
      if (isScroller && !el.matches('.wf-tabs')) out.scrollers.push(line);
      else if (isClip && !isScroller) (cs.textOverflow === 'ellipsis' ? out.ellipsised : out.clipped).push(line);
    }
  }
  return out;
}

for (const W of REPORT_WIDTHS) {
  const H = W === 390 ? 844 : 812;
  test.describe(`Map at ${W}`, () => {
    test.use({ viewport: { width: W, height: H }, isMobile: true, hasTouch: true, deviceScaleFactor: 3 });

    test('Map states stay inside the phone viewport', async ({ page }) => {
      test.setTimeout(45_000);
      const asserted = WIDTHS.includes(W);
      const results = [];
      if (SHOT_DIR) fs.mkdirSync(SHOT_DIR, { recursive: true });

      async function check(state) {
        const m = await page.evaluate(measureInPage, W);
        results.push({ state, ...m });
        if (SHOT_DIR) await page.screenshot({ path: `${SHOT_DIR}/${state}-${W}.png` });
      }

      await loginAndWaitForForecast(page);
      expect(await page.evaluate(() => window.innerWidth), 'emulated width').toBe(W);
      expect(await page.evaluate(() => window.visualViewport.scale), 'page scale').toBe(1);
      // Phones open on the screen: open the map view from a card first (there is no Map button). The first card is the next window still to come: the one the old Map button opened, and the one with a served pick.
      await openMapView(page, 0, { plain: true });
      await check('map-default');

      // The Best bet reason (the pill shows once the card's lens note is cleared: `plain` above).
      const why = page.getByTestId('map-best-bet-why');
      await expect(why).toBeVisible();
      await why.click();
      await expect(page.getByTestId('map-best-bet-detail')).toBeVisible();
      await check('best-bet');
      await why.click();

      async function openLayers() {
        if (!(await page.getByTestId('wf-map-peek-lay-regions').isVisible())) {
          await page.getByTestId('wf-map-peek-btn-lay').click();
        }
        await expect(page.getByTestId('wf-map-peek-lay-regions')).toBeVisible();
      }
      await openLayers();
      await check('layers');
      await page.getByTestId('wf-map-peek-lay-regions').click();
      await expect(page.getByTestId('wf-jump-menu')).toBeVisible();
      await check('regions');
      await page.keyboard.press('Escape');
      await expect(page.getByTestId('wf-jump-menu')).toHaveCount(0);
      await openLayers();
      await page.getByTestId('wf-map-peek-lay-filters').click();
      await expect(page.getByTestId('wf-filters-panel')).toBeVisible();
      await check('filters');
      await page.keyboard.press('Escape');
      await expect(page.getByTestId('wf-filters-panel')).toHaveCount(0);

      // The windows, the List door and the callout need a selected Plan card (never the first: it may be passing).
      // The view closes first (the screen is inert under it), and a card opens it again on its own window.
      await page.getByTestId('map-screen-close').click();
      await expect(page.getByTestId('map-screen')).toBeHidden();
      await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
      await page.locator('[data-testid="wf-heat-card"][data-control="true"]').nth(2).click();
      await expect(page.getByTestId('map-screen')).toBeVisible();
      await page.evaluate(() => document.fonts.ready);
      await check('map-from-card');

      if (!(await page.getByTestId('wf-map-peek-windows').isVisible())) {
        await page.getByTestId('wf-map-peek-btn-win').click();
      }
      await expect(page.getByTestId('wf-map-peek-windows')).toBeVisible();
      await check('windows');

      await page.getByTestId('wf-map-peek-list').click();
      await expect(page.getByTestId('window-spot-sheet')).toBeVisible();
      await check('list-sheet');
      await page.getByTestId('window-spot').first().click();
      await expect(page.getByTestId('map-callout')).toBeVisible();
      await check('callout');

      console.log(`M1 ${W}: ${JSON.stringify(results.map(({ state, ...m }) => ({ state, ...m })), null, 1)}`);
      if (!asserted) return;
      for (const r of results) {
        expect.soft(r.boxes, `${r.state}: elements outside 0..${W}`).toEqual([]);
        expect.soft(r.scrollers, `${r.state}: inner horizontal scrollers`).toEqual([]);
        expect.soft(r.clipped, `${r.state}: clipped overflow`).toEqual([]);
      }
    });
  });
}
