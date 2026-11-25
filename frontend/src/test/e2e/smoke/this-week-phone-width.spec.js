import { test, expect, loginAndWaitForForecast } from './fixture.js';

/**
 * This week's bar, the one-time hint and a folded quiet day must never widen a phone's layout viewport (screen-spec P3).
 * CI once measured a 379px layout at 360 and 375 wide because the bar's spoken text (absolutely positioned, `.sr-only`)
 * escaped the best button's ellipsis clip and sat past the edge under a wider font. This stresses the same thing on any
 * machine: a long place name, two folded days, and text made wider than any real fallback font.
 */
for (const W of [360, 375]) {
  test.describe(`This week at ${W}`, () => {
    test.use({ viewport: { width: W, height: 812 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });

    test('the bar, the hint and a fold stay inside the viewport with a long place name and wide text', async ({ page }) => {
      await page.route(/\/api\/briefing(\?.*)?$/, async (route) => {
        const res = await route.fetch();
        const json = await res.json();
        json.days.forEach((day, index) => day.eventSummaries.forEach((es) => {
          if (es.window?.pick?.locationName) es.window.pick.locationName = 'Extraordinarily Long Place Name Beach And Dunes';
          if (es.window && index !== 1) es.window.bestRating = 2;
        }));
        await route.fulfill({ response: res, json });
      });
      await loginAndWaitForForecast(page, 'pro');
      await page.addStyleTag({ content: '[data-testid="window-first-pane"] * { letter-spacing: 0.9px !important; }' });
      await expect(page.getByTestId('this-week-best')).toBeVisible();
      await expect(page.getByTestId('your-places-hint')).toBeVisible();
      await expect(page.getByTestId('wf-quiet-fold').first()).toBeVisible();

      expect(await page.evaluate(() => window.innerWidth), 'the layout viewport did not widen').toBe(W);
      expect(await page.evaluate(() => document.documentElement.scrollWidth), 'no horizontal scroll').toBeLessThanOrEqual(W);
      for (const id of ['this-week-bar', 'your-places-hint']) {
        const box = await page.getByTestId(id).boundingBox();
        expect(box.x + box.width, `${id} right edge`).toBeLessThanOrEqual(W + 0.5);
      }
      const fold = await page.getByTestId('wf-quiet-fold').first().boundingBox();
      expect(fold.x + fold.width, 'fold right edge').toBeLessThanOrEqual(W + 0.5);
    });
  });
}
