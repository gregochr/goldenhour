import { test, expect, tokenOf, loginAndWaitForForecast } from './fixture.js';

/**
 * Your places with bookmarks must never widen a phone's layout viewport (screen-spec P5 review). Each chip carries
 * absolutely positioned spoken text (`.sr-only`); with the chip row (an `overflow-x: auto` scroller) statically
 * positioned that text escaped the scroller, sat past the right edge for a chip scrolled out of view, and an Android-like
 * viewport grew to fit it: three bookmarks turned 390 into 742 and every fixed bar (Ask) followed. The seeded fixture
 * has no bookmarks, so this one writes three through the API first.
 *
 * <p>It WRITES shared server state (the Pro reader's bookmarks, and the first one stamps the one-time hint away for good),
 * so it runs in the `roles-write` project, after the whole read-only suite, and removes the bookmarks it added.
 */
const PLACES = ['Craster Rocks', 'Druridge Bay', 'Bamburgh Beach'];

for (const W of [360, 375, 390]) {
  test.describe(`Your places at ${W}`, () => {
    test.use({ viewport: { width: W, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });

    test('three bookmarked places do not widen the layout viewport or scroll the page sideways', async ({ page }) => {
      test.setTimeout(60_000);
      await loginAndWaitForForecast(page, 'pro');
      const headers = { Authorization: `Bearer ${await tokenOf(page)}` };
      const locations = await (await page.request.get('/api/locations', { headers })).json();
      const ids = PLACES.map((name) => locations.find((l) => l.name === name)?.id).filter((id) => id != null);
      expect(ids, 'the three seeded places exist').toHaveLength(PLACES.length);
      try {
        for (const id of ids) {
          const res = await page.request.post(`/api/user/settings/favourites/places/${id}`, { headers });
          expect(res.status()).toBe(200);
        }
        await page.reload();
        await expect(page.getByTestId('your-places-chip')).toHaveCount(PLACES.length);
        // A chip sits past the viewport edge (the row scrolls), which is the case that used to widen the page.
        const chips = await page.getByTestId('your-places-chip').evaluateAll((nodes) => nodes.map((n) => n.getBoundingClientRect().right));
        expect(Math.max(...chips), 'at least one chip overflows the row, so the row really scrolls').toBeGreaterThan(W);

        expect(await page.evaluate(() => window.innerWidth), 'the layout viewport did not widen').toBe(W);
        expect(await page.evaluate(() => document.documentElement.scrollWidth), 'no horizontal scroll').toBeLessThanOrEqual(W);
        const bar = await page.getByTestId('ask-bar').boundingBox();
        expect(bar.x + bar.width, 'the fixed Ask bar stays inside the viewport').toBeLessThanOrEqual(W + 0.5);
      } finally {
        for (const id of ids) await page.request.delete(`/api/user/settings/favourites/places/${id}`, { headers });
      }
    });
  });
}
