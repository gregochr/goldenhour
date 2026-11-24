/**
 * The 0x0 race (one-screen-plan.md D10). A Leaflet map constructed inside a hidden (display:none) box measures 0x0:
 * MapContainer's opening fit clamps to maxZoom 16 and the heat controller used to adopt that as its baseline. The map
 * view avoids it by construction: the pane mounts on the FIRST open, visible from its first commit, and when closed it
 * stays mounted behind the `hidden` attribute (so a reopen is a reveal of a box with a real, stored size).
 *
 * <p>The chunk is held until after a Glance card has been pressed, so the first open happens while the chunk is still
 * loading (the race the old tab model lost: the pane's chunk landing into a panel that was not yet shown). The held
 * request also proves the idle preload really targets that chunk: a renamed chunk would let the spec pass without the
 * race it exists for.
 */
import { test, expect, loginAndWaitForForecast, closeMapView, visibleChips } from './fixture.js';

/** The basemap zoom the map is showing: the `z` of the front tile level plus its CSS scale. */
async function mapZoom(page) {
  return page.evaluate(() => {
    const levels = [...document.querySelectorAll('.leaflet-tile-container')]
      .filter((l) => l.querySelector('img'))
      .sort((a, b) => Number(b.style.zIndex || 0) - Number(a.style.zIndex || 0));
    const front = levels[0];
    if (!front) return null;
    const z = Number(front.querySelector('img').src.match(/\/tile\/(\d+)\//)[1]);
    const m = (front.style.transform || '').match(/scale\(([\d.]+)\)/);
    return z + Math.log2(m ? Number(m[1]) : 1);
  });
}

/** Presses the nth Glance card (never the first: it may be the window about to pass); a pointer lands on the card. */
async function pressCard(page, index) {
  await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
  await page.getByTestId('wf-heat-card').nth(index).click();
}

/** The two assertions every case ends on: the map framed the planning area, and the chips are drawn. */
async function expectFramed(page) {
  await expect(page.locator('[data-testid="map-labels"][data-placed="true"]')).toHaveCount(1);
  await expect.poll(() => mapZoom(page)).toBeLessThan(10);
  await expect.poll(() => visibleChips(page).count()).toBeGreaterThanOrEqual(5);
}

/** Holds the map chunk until `release()`, counting the requests so the hold is proven to have bitten. */
async function holdMapChunk(page) {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const hits = { count: 0 };
  await page.route('**/assets/WindowFirstMapPane-*.js', async (route) => {
    hits.count += 1;
    await gate;
    await route.continue();
  });
  return { release, hits };
}

test.describe('The map view mounts visible, whatever the chunk is doing', () => {
  test('the first open while the chunk is still held: the pane mounts visible and frames the planning area', async ({ page }) => {
    test.setTimeout(90_000);
    const { release, hits } = await holdMapChunk(page);
    await loginAndWaitForForecast(page, 'admin');
    // The idle preload asks for the chunk after first paint; it is held.
    await expect.poll(() => hits.count).toBeGreaterThan(0);

    await pressCard(page, 2);
    await expect(page.getByTestId('map-screen')).toBeVisible();
    // The pane is not here yet: the view shows its fallback while the chunk is held.
    await expect(page.locator('.leaflet-container')).toHaveCount(0);
    release();

    await expect(page.locator('[data-testid="map-screen"] .leaflet-container')).toHaveCount(1);
    await expectFramed(page);
  });

  test('close, then reopen on another window: the mounted pane is revealed at its real size', async ({ page }) => {
    test.setTimeout(90_000);
    await loginAndWaitForForecast(page, 'admin');
    await pressCard(page, 2);
    await expectFramed(page);
    const paneNode = await page.locator('[data-testid="window-first-map-pane"]').elementHandle();

    await closeMapView(page);
    // Closed: the view is hidden by the attribute and the pane is still in the document (never unmounted).
    await expect(page.getByTestId('map-screen')).toHaveAttribute('hidden', '');
    await expect(page.locator('.leaflet-container')).toHaveCount(1);

    await pressCard(page, 3);
    await expect(page.getByTestId('map-screen')).toBeVisible();
    await expectFramed(page);
    expect(
      await page.evaluate((node) => node.isConnected, paneNode),
      'the same pane, not a remount',
    ).toBe(true);
  });

  // The reader closes the view before the lazy pane has landed (a slow chunk, or a quick reader): the pane then mounts
  // behind `hidden`, `panelShown` is still its initial true for a tick, and the door's camera must not fit against the
  // 0x0 box (it clamped to zoom 12 and the reopened view showed an empty field with no chips: CI's nginx smoke).
  test('close before the held chunk lands, then reopen from the same card: the camera still frames the planning area', async ({ page }) => {
    test.setTimeout(90_000);
    const { release, hits } = await holdMapChunk(page);
    await loginAndWaitForForecast(page, 'admin');
    await expect.poll(() => hits.count).toBeGreaterThan(0);

    await pressCard(page, 2);
    await expect(page.getByTestId('map-screen')).toBeVisible();
    await expect(page.locator('.leaflet-container')).toHaveCount(0);
    await closeMapView(page);
    release();
    // The pane lands and mounts while the view is hidden.
    await expect(page.locator('.leaflet-container')).toHaveCount(1);

    // There is no Map button any more: the reader reopens through a card, the same door they opened with.
    await pressCard(page, 2);
    await expect(page.getByTestId('map-screen')).toBeVisible();
    await expectFramed(page);
  });

  // The slow-4G acceptance (Chromium only: CDP network emulation).
  test('the first open under slow 4G throttling frames the planning area too', async ({ page, browserName }) => {
    test.skip(browserName !== 'chromium', 'CDP network throttling is Chromium-only');
    test.setTimeout(120_000);
    await loginAndWaitForForecast(page, 'admin');
    const cdp = await page.context().newCDPSession(page);
    await cdp.send('Network.enable');
    await cdp.send('Network.emulateNetworkConditions', {
      offline: false, latency: 150, downloadThroughput: (1.6 * 1024 * 1024) / 8, uploadThroughput: (750 * 1024) / 8,
    });

    await pressCard(page, 2);

    await expect(page.getByTestId('map-screen')).toBeVisible();
    await expect(page.locator('[data-testid="map-screen"] .leaflet-container')).toHaveCount(1, { timeout: 60_000 });
    await expectFramed(page);
  });
});
