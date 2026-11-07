/**
 * A Map pane whose lazy chunk lands while the reader is already on the Plan tab is constructed inside a hidden
 * (display:none) panel: Leaflet measures 0x0, MapContainer's opening fit clamps to maxZoom 16, and the heat
 * controller used to adopt that as its baseline. The chunk is held until after the Plan click so the race
 * is deterministic rather than a coin flip.
 */
import { test, expect, loginAndWaitForForecast, openTab, openMapTab, visibleChips } from './fixture.js';

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

test('Plan then Map: a Map pane that mounted hidden still opens on the planning area', async ({ page }) => {
  // Hold the Map pane's chunk until the reader is on the Plan tab, so the pane always mounts inside the
  // hidden panel — the race the bug needs, made deterministic.
  let release;
  const gate = new Promise((r) => { release = r; });
  let hits = 0;
  await page.route('**/assets/WindowFirstMapPane-*.js', async (route) => { hits += 1; await gate; await route.continue(); });
  await loginAndWaitForForecast(page, 'admin');
  await openTab(page, 'plan');
  // The chunk was really held: a renamed chunk would otherwise let this spec pass without the race it exists for.
  await expect.poll(() => hits).toBeGreaterThan(0);
  release();
  // The pane has mounted (hidden) under the Plan tab before the reader comes back.
  await expect(page.locator('[data-testid="window-first-panel-map"] .leaflet-container')).toHaveCount(1);
  await page.waitForTimeout(500);
  await openMapTab(page);
  await expect.poll(() => mapZoom(page)).toBeLessThan(10);
  expect(await visibleChips(page).count()).toBeGreaterThanOrEqual(5);
});
