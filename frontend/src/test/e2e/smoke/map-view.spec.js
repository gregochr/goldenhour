import { test, expect, loginAndWaitForForecast } from './fixture.js';

/**
 * The map view (`docs/engineering/one-screen-plan.md` O1) as a reader meets it: a Glance card opens it over the
 * screen, and the close button, Back and Escape all put the reader back on the same card with the page where it was; its
 * address survives a refresh; a window that has passed opens with a note; and Back still works after an address-opened
 * view (the layer registers on the first gesture).
 */

/** Waits for the history engine to settle at `depth`, the signal that a traversal is safe. */
async function settled(page, depth) {
  await expect
    .poll(async () => {
      const value = await page.evaluate(() => document.documentElement.getAttribute('data-back-depth'));
      return value === null && depth === 0 ? '0' : value;
    }, { message: `data-back-depth settles at ${depth}` })
    .toBe(String(depth));
}

/**
 * The nth Glance card (never the first: it may be the window about to pass) and its inner button. A pointer lands on the
 * CARD (the button is `pointer-events: none`) and the card hands focus to the button, which is where focus must return.
 */
async function cardAt(page, index = 2) {
  await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
  const card = page.getByTestId('wf-heat-card').nth(index);
  return { card, button: card.getByTestId('wf-heat-card-button') };
}

test.describe('The map view over the screen', () => {
  test.beforeEach(async ({ page }) => {
    await loginAndWaitForForecast(page);
  });

  const closers = {
    'the close button': (page) => page.getByTestId('map-screen-close').click(),
    'Back': (page) => page.goBack(),
    'Escape': (page) => page.keyboard.press('Escape'),
  };

  for (const [name, close] of Object.entries(closers)) {
    test(`a card opens the view and ${name} closes it: same scroll, focus back on the card`, async ({ page }) => {
      test.setTimeout(60_000);
      const { card, button } = await cardAt(page);
      await card.scrollIntoViewIfNeeded();
      const scrollBefore = await page.evaluate(() => window.scrollY);

      await card.click();
      await expect(page.getByTestId('map-screen')).toBeVisible();
      await settled(page, 1);
      // The screen behind it is inert and the page cannot scroll under the view.
      await expect(page.getByTestId('window-first-shell-col')).toHaveAttribute('inert', '');
      expect(await page.evaluate(() => getComputedStyle(document.documentElement).overflow)).toBe('hidden');
      // Initial focus is the view's title, never the Leaflet container.
      await expect(page.getByTestId('map-screen-title')).toBeFocused();

      await close(page);

      await expect(page.getByTestId('map-screen')).toBeHidden();
      await settled(page, 0);
      expect(await page.evaluate(() => window.scrollY), 'the page did not move').toBe(scrollBefore);
      expect(await page.evaluate(() => getComputedStyle(document.documentElement).overflow)).not.toBe('hidden');
      await expect(button).toBeFocused();
      await expect(page.getByTestId('window-first-shell-col')).not.toHaveAttribute('inert', '');
    });
  }

  test('the view is a labelled region, not a dialog, and nothing above the pane claims aria-modal', async ({ page }) => {
    await (await cardAt(page)).card.click();
    const view = page.getByTestId('map-screen');
    await expect(view).toBeVisible();
    await expect(view).toHaveAttribute('role', 'region');
    await expect(view).not.toHaveAttribute('aria-modal', /.*/);
    expect(await page.evaluate(() => {
      const pane = document.querySelector('.wf-map-tab');
      for (let node = pane; node; node = node.parentElement) {
        if (node.hasAttribute('aria-modal')) return false;
      }
      return document.querySelectorAll('[aria-modal="true"]').length;
    })).toBe(0);
    // The one window control lives in the header, and there is only one.
    await expect(page.getByTestId('map-screen-window-slot').getByTestId('wf-win-control')).toHaveCount(1);
    await expect(page.getByTestId('wf-win-control')).toHaveCount(1);
  });

  test('the address carries the window, survives a refresh, and is the bare path once the view closes', async ({ page }) => {
    test.setTimeout(60_000);
    await (await cardAt(page)).card.click();
    await expect(page.getByTestId('map-screen')).toBeVisible();
    await settled(page, 1);
    const hash = await page.evaluate(() => window.location.hash);
    expect(hash).toMatch(/^#map\/\d{4}-\d{2}-\d{2}\/(sunrise|sunset)/);
    // The title is the window's own once the pane has published it (the view's default is "Map").
    await expect(page.getByTestId('map-screen-title')).not.toHaveText('Map');
    const title = await page.getByTestId('map-screen-title').innerText();

    await page.reload();

    await expect(page.getByTestId('map-screen')).toBeVisible();
    // Read once and rewritten to the bare path.
    expect(await page.evaluate(() => window.location.hash)).toBe('');
    await expect(page.getByTestId('map-screen-title')).toHaveText(title);
  });

  test('a window that has passed opens the view on the map\'s own window with a one-line note', async ({ page }) => {
    await page.evaluate(() => { window.location.hash = '#map/2000-01-01/sunrise'; });
    await page.reload();

    await expect(page.getByTestId('map-screen')).toBeVisible();
    await expect(page.getByTestId('screen-note')).toHaveText(/That sunrise has passed/);
    await page.getByTestId('screen-note-dismiss').click();
    await expect(page.getByTestId('screen-note')).toHaveCount(0);
    // Pressing the note's button was the first gesture, so the layer registered and the view now owns an entry and the
    // address of the window it landed on (the map's own window, since the asked one had passed).
    expect(await page.evaluate(() => window.location.hash)).toMatch(/^#map\/\d{4}-\d{2}-\d{2}\//);
  });

  test('Back after an address-opened view: the first gesture registers the layer, and Back closes the view', async ({ page }) => {
    test.setTimeout(60_000);
    await (await cardAt(page)).card.click();
    await expect(page.getByTestId('map-screen')).toBeVisible();
    await settled(page, 1);
    const hash = await page.evaluate(() => window.location.hash);
    await page.evaluate((h) => { window.location.hash = h; }, hash);
    await page.reload();
    await expect(page.getByTestId('map-screen')).toBeVisible();
    // No gesture yet: the layer is not registered, so Back would be the browser's.
    await settled(page, 0);

    // A first gesture inside the view registers the layer and pushes its entry.
    await page.getByTestId('map-screen-title').click();
    await settled(page, 1);

    await page.goBack();

    await expect(page.getByTestId('map-screen')).toBeHidden();
    await settled(page, 0);
    await expect(page.getByTestId('window-first-shell')).toBeVisible();
  });

  test('the arrow keys on the header step the window, and Escape works with focus on the page itself', async ({ page }) => {
    test.setTimeout(60_000);
    await (await cardAt(page)).card.click();
    await expect(page.getByTestId('map-screen')).toBeVisible();
    await expect(page.getByTestId('map-screen-title')).not.toHaveText('Map');
    const first = await page.getByTestId('map-screen-title').innerText();

    // The title holds focus on open: → moves to the next window, ← back to this one.
    await page.keyboard.press('ArrowRight');
    await expect(page.getByTestId('map-screen-title')).not.toHaveText(first);
    await page.keyboard.press('ArrowLeft');
    await expect(page.getByTestId('map-screen-title')).toHaveText(first);

    // Focus on <body> (the page behind is inert, so a click there lands nowhere): Escape still closes the view.
    await page.evaluate(() => document.activeElement?.blur());
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('map-screen')).toBeHidden();
  });

  test('the pinned session notice never covers the view\'s header: it starts below it, and the close button works', async ({ page }) => {
    test.setTimeout(60_000);
    await page.evaluate(() => {
      localStorage.setItem('goldenhour_refresh_expires', new Date(Date.now() + 2 * 86400000).toISOString());
    });
    await page.reload();
    await expect(page.getByTestId('session-expiry-banner')).toBeVisible();
    await (await cardAt(page)).card.click();
    await expect(page.getByTestId('map-screen')).toBeVisible();

    // The notice stays interactive over the view, and the header sits below it.
    await expect(page.getByTestId('session-refresh-button')).toBeVisible();
    const banner = await page.getByTestId('session-banner-slot').boundingBox();
    const headerTop = (await page.getByTestId('map-screen-header').boundingBox()).y;
    expect(headerTop).toBeGreaterThanOrEqual(banner.y + banner.height - 1);
    await page.getByTestId('map-screen-close').click();
    await expect(page.getByTestId('map-screen')).toBeHidden();
  });

  test('Find opens over the view, and Back closes Find first and the view last', async ({ page }) => {
    test.setTimeout(60_000);
    await (await cardAt(page)).card.click();
    await expect(page.getByTestId('map-screen')).toBeVisible();
    await settled(page, 1);

    await page.getByTestId('map-screen-find').click();
    await expect(page.getByTestId('plan-search-panel')).toBeVisible();
    await settled(page, 2);
    // The view's address stands while Find is over it.
    expect(await page.evaluate(() => window.location.hash)).toMatch(/^#map\//);

    await page.goBack();
    await expect(page.getByTestId('plan-search-panel')).toHaveCount(0);
    await expect(page.getByTestId('map-screen')).toBeVisible();
    await settled(page, 1);

    await page.goBack();
    await expect(page.getByTestId('map-screen')).toBeHidden();
    await settled(page, 0);
  });
});

test.describe('The Map landing (kept until the tab model goes)', () => {
  test('a desktop that has not been touched opens the view on its own once the forecast lands', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page, 'admin', { settleLanding: false });

    await expect(page.getByTestId('map-screen')).toBeVisible();
    // Activation-gated: the entry is pushed on the first gesture (or at once if the sign-in click is still live).
    await page.getByTestId('map-screen-title').click();
    await settled(page, 1);
    await page.getByTestId('map-screen-close').click();
    await expect(page.getByTestId('map-screen')).toBeHidden();
    await settled(page, 0);
  });
});
