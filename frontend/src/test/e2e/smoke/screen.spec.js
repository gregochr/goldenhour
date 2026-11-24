import {
  test, expect, loginAndWaitForForecast, openFurtherAhead, openMapView, openMenu, openOperations,
} from './fixture.js';

/**
 * The one screen (`docs/engineering/one-screen-plan.md` O3), on the SEEDED fixture: there is no tab bar, every device
 * opens on This week, the ☰ menu is the masthead's one control and holds Operations for an admin only, and Operations is a
 * full-screen view driven by the `#manage` address. The empty states stay covered by Vitest; there is no second, empty
 * backend.
 */
test.describe('The screen has no tabs', () => {
  test.beforeEach(async ({ page }) => {
    await loginAndWaitForForecast(page);
  });

  test('there is no tab list and no Map button: the screen opens on This week, never on the map view', async ({ page }) => {
    await expect(page.getByRole('tablist')).toHaveCount(0);
    await expect(page.getByRole('tab')).toHaveCount(0);
    await expect(page.getByTestId('window-first-tabs')).toHaveCount(0);
    await expect(page.getByTestId('this-week-heading')).toBeVisible();
    // Every device, the desktop included, opens on the screen: the view opens only from a door.
    await expect(page.getByTestId('map-screen')).toBeHidden();
  });

  test('This week renders its heat cards, not an empty state', async ({ page }) => {
    await expect(page.getByTestId('window-first-pane')).toBeVisible();
    await expect(page.getByTestId('window-first-pane-empty')).toHaveCount(0);
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
  });

  test('Further ahead renders the almanac list at the bottom of the screen, with no error state', async ({ page }) => {
    await openFurtherAhead(page);
    await expect(page.getByTestId('further-ahead')).toBeVisible();
    await expect(page.getByTestId('coming-up-list').first()).toBeVisible();
    await expect(page.getByTestId('coming-up-error')).toHaveCount(0);
  });

  test('a card opens the map view, which draws a Leaflet map', async ({ page }) => {
    await openMapView(page);
    await expect(page.locator('.leaflet-container').first()).toBeVisible();
  });
});

test.describe('The ☰ menu and Operations', () => {
  test('an admin\'s menu holds Settings, the guide, Operations and Sign out', async ({ page }) => {
    await loginAndWaitForForecast(page, 'admin');
    await openMenu(page);
    await expect(page.getByTestId('window-first-settings')).toBeVisible();
    await expect(page.getByTestId('masthead-menu-postcode')).toBeVisible();
    await expect(page.getByTestId('masthead-menu-guide')).toBeVisible();
    await expect(page.getByTestId('masthead-menu-operations')).toBeVisible();
    await expect(page.getByTestId('window-first-signout')).toBeVisible();
  });

  // Replaces the old tab-count assertion, which was vacuous once the tab was withheld: here the menu is OPEN and holds its
  // other rows, so an absent Operations row is a real statement. No pill, no greyed row, no upsell.
  for (const role of ['pro', 'lite']) {
    test(`${role}: the menu has no Operations row at all`, async ({ page }) => {
      await loginAndWaitForForecast(page, role);
      await openMenu(page);
      await expect(page.getByTestId('window-first-settings')).toBeVisible();
      await expect(page.getByTestId('window-first-signout')).toBeVisible();
      await expect(page.getByTestId('masthead-menu-operations')).toHaveCount(0);
      await expect(page.getByTestId('masthead-menu').getByText(/operations/i)).toHaveCount(0);
    });
  }

  test('Escape closes the menu and returns focus to its button', async ({ page }) => {
    await loginAndWaitForForecast(page, 'admin');
    await openMenu(page);
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('masthead-menu')).toHaveCount(0);
    await expect(page.getByTestId('masthead-menu-button')).toBeFocused();
  });

  test('Operations opens from the menu, hides the screen, and "‹ PhotoCast" closes it with focus on the menu button', async ({ page }) => {
    await loginAndWaitForForecast(page, 'admin');
    await openOperations(page);
    await expect(page.getByTestId('operations-title')).toBeFocused();
    expect(page.url()).toMatch(/#manage\/runs/);
    // Nothing behind it is reachable: the screen and its masthead are hidden, not just covered.
    await expect(page.getByTestId('window-first-shell-col')).toBeHidden();
    await expect(page.getByTestId('runs-view')).toBeVisible();

    await page.getByTestId('operations-back').click();

    await expect(page.getByTestId('operations-screen')).toHaveCount(0);
    await expect(page.getByTestId('window-first-shell-col')).toBeVisible();
    await expect(page.getByTestId('masthead-menu-button')).toBeFocused();
    expect(page.url()).not.toMatch(/#manage/);

    // Back from the bare entry returns into Operations (the close was a forward step), and Back again leaves it.
    await page.goBack();
    await expect(page.getByTestId('operations-screen')).toBeVisible();
    await page.goBack();
    await expect(page.getByTestId('operations-screen')).toHaveCount(0);
    expect(page.url()).not.toMatch(/#manage/);
  });

  test('Back inside Operations steps out of a sub-view, and Back past the first #manage entry closes it', async ({ page }) => {
    await loginAndWaitForForecast(page, 'admin');
    await openOperations(page);
    await page.getByTestId('manage-tab-users').click();
    await expect(page.getByTestId('user-list')).toBeVisible();
    await expect(page).toHaveURL(/#manage\/users$/);

    await page.goBack();
    await expect(page).toHaveURL(/#manage\/runs$/);
    await expect(page.getByTestId('operations-screen')).toBeVisible();

    await page.goBack();
    await expect(page.getByTestId('operations-screen')).toHaveCount(0);
    await expect(page.getByTestId('window-first-shell-col')).toBeVisible();
    expect(page.url()).not.toMatch(/#manage/);
  });

  test('an admin\'s #manage/runs link opens Operations on load', async ({ page }) => {
    await loginAndWaitForForecast(page, 'admin');
    await page.evaluate(() => window.history.replaceState(null, '', '/#manage/runs'));
    await page.reload();

    await expect(page.getByTestId('operations-screen')).toBeVisible();
    await expect(page.getByTestId('runs-view')).toBeVisible();
    await expect(page.getByTestId('window-first-shell-col')).toBeHidden();
  });

  for (const role of ['pro', 'lite']) {
    test(`${role}: a #manage link is cleared and Operations never opens or loads`, async ({ page }) => {
      const requested = [];
      page.on('request', (request) => requested.push(request.url()));
      await loginAndWaitForForecast(page, role);
      await page.evaluate(() => window.history.replaceState(null, '', '/#manage/runs'));
      await page.reload();

      await expect(page.getByTestId('window-first-shell')).toBeVisible();
      await expect(page.getByTestId('this-week-heading')).toBeVisible();
      await expect(page.getByTestId('operations-screen')).toHaveCount(0);
      await expect.poll(() => page.url()).not.toMatch(/#manage/);
      // The view's lazy chunk is never fetched for a reader who may not use it.
      expect(requested.filter((url) => /ManageView/.test(url))).toEqual([]);

      // A hash typed while the page is up is cleared the same way.
      await page.evaluate(() => { window.location.hash = 'manage/runs'; });
      await expect.poll(() => page.url()).not.toMatch(/#manage/);
      await expect(page.getByTestId('operations-screen')).toHaveCount(0);
    });
  }
});

test.describe('The masthead never overflows with the Ask dock open (the field collapses in its own container)', () => {
  for (const [width, dock] of [[640, false], [768, false], [1024, true], [1180, true]]) {
    test(`admin at ${width}px${dock ? ' with the dock open' : ''}: no horizontal scroll and the ☰ button is on screen`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await loginAndWaitForForecast(page, 'admin');
      if (dock) {
        await page.getByTestId('ask-field').click();
        await expect(page.getByTestId('ask-dock')).toBeVisible();
      }
      const m = await page.evaluate(() => {
        const box = document.querySelector('[data-testid="masthead-menu-button"]').getBoundingClientRect();
        return { scroll: document.documentElement.scrollWidth, inner: window.innerWidth, right: box.right, left: box.left };
      });
      expect(m.scroll, 'no horizontal page scroll').toBeLessThanOrEqual(m.inner);
      expect(m.left).toBeGreaterThanOrEqual(0);
      expect(m.right).toBeLessThanOrEqual(m.inner);
    });
  }
});
