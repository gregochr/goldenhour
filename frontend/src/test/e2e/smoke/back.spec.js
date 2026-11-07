import {
  test, expect, loginAndWaitForForecast, openTab, tokenOf, stubGuideFrame, openMapTab, visibleChips, CHIP_NAME,
  installDialogDepthObserver, dialogDepth,
} from './fixture.js';

/**
 * The browser Back button closes the top dialog (docs/engineering/back-closes-dialogs-plan.md, PR 2a: Settings,
 * the guide, the Ask sheet, a confirm dialog over an Operations panel; PR 2b: the Plan-shell dialogs).
 *
 * <p>The rules every test here follows:
 *  - **Settle first.** Playwright's `goBack` is index-based, so a Back issued while the store still has a
 *    traversal or a reconcile outstanding double-steps. Before every `goBack` / `goForward` / reload the spec
 *    waits for `<html data-back-depth>` to read the number it expects (it reads `busy` while anything is
 *    outstanding).
 *  - **The property is "no dead press", not "stays in the app".** A baseline `navigation.currentEntry.index` is
 *    taken before anything opens; closing by the x, Escape or Back must return exactly to it, with no marker
 *    left on the entry. `goBack` from the baseline would leave the origin, and that is correct.
 *  - **Nothing here writes server state** and no spec presses a confirm: the Operations case only cancels.
 *
 * <p>Chromium's `goBack` ignores skippable-entry rules and always grants user activation, so the activation
 * gate and the history-manipulation intervention are the manual device pass in the plan (a release blocker).
 */

/** Waits for the store to settle at `depth` (a closed page is 0), the signal that a traversal is safe. */
async function settled(page, depth) {
  await expect
    .poll(
      async () => {
        const value = await page.evaluate(() => document.documentElement.getAttribute('data-back-depth'));
        // A page that never registered a layer has no attribute at all: that is idle, depth 0.
        return value === null && depth === 0 ? '0' : value;
      },
      { message: `data-back-depth settles at ${depth}` },
    )
    .toBe(String(depth));
}

/** `history.scrollRestoration`: 'manual' only while a layer entry is up, 'auto' again once the store is idle. */
function scrollRestoration(page) {
  return page.evaluate(() => window.history.scrollRestoration);
}

/** The Navigation API's index of the entry the page is on: the baseline for "no dead press". */
function entryIndex(page) {
  return page.evaluate(() => window.navigation.currentEntry.index);
}

/** Our marker on the current history entry, or null. */
function marker(page) {
  return page.evaluate(() => window.history.state?.phLayer ?? null);
}

/** Asserts the page sits on `index` with no marker, once the store has settled at depth 0. */
async function expectBaseline(page, index) {
  await settled(page, 0);
  expect(await entryIndex(page), 'back on the baseline entry').toBe(index);
  expect(await marker(page), 'no marker left behind').toBeNull();
}

const settingsDialog = (page) => page.getByTestId('settings-modal');
const cog = (page) => page.getByTestId('window-first-settings');

/** Opens Settings from the cog and waits for the dialog and the store. */
async function openSettings(page, depth = 1) {
  await cog(page).click();
  await expect(settingsDialog(page)).toBeVisible();
  await expect(page.getByTestId('settings-body')).toBeVisible();
  await settled(page, depth);
}

test.describe('Back closes the top dialog: Settings', () => {
  test('goBack closes Settings and focus returns to the cog', async ({ page }) => {
    await loginAndWaitForForecast(page);
    const base = await entryIndex(page);
    const url = page.url();
    await openSettings(page);
    expect(await entryIndex(page)).toBe(base + 1);
    expect((await marker(page))?.depth).toBe(1);
    expect(page.url(), 'the URL never changes').toBe(url);

    await page.goBack();

    await expect(settingsDialog(page)).toHaveCount(0);
    await expect(cog(page)).toBeFocused();
    await expectBaseline(page, base);
    expect(page.url()).toBe(url);
  });

  test('closing with the x or Escape leaves no dead press: the entry and the marker go with it', async ({ page }) => {
    await loginAndWaitForForecast(page);
    const base = await entryIndex(page);

    await openSettings(page);
    await settingsDialog(page).getByRole('button', { name: 'Close' }).click();
    await expect(settingsDialog(page)).toHaveCount(0);
    await expectBaseline(page, base);

    await openSettings(page);
    await page.keyboard.press('Escape');
    await expect(settingsDialog(page)).toHaveCount(0);
    await expectBaseline(page, base);
  });

  test('goForward after a Back reopens nothing', async ({ page }) => {
    await loginAndWaitForForecast(page);
    const base = await entryIndex(page);
    await openSettings(page);
    await page.goBack();
    await expect(settingsDialog(page)).toHaveCount(0);
    await settled(page, 0);

    await page.goForward();

    // The stale entry is bounced straight back: nothing reopens and the page ends on the baseline.
    await expectBaseline(page, base);
    await expect(settingsDialog(page)).toHaveCount(0);
  });

  test('reload with Settings open: nothing reopens, the marker is stripped, and there is no page error', async ({ page }) => {
    await loginAndWaitForForecast(page);
    await openSettings(page);

    await page.reload();

    await expect(page.getByTestId('window-first-shell')).toBeVisible();
    await expect(page.getByTestId('window-first-tab-map')).toBeVisible();
    await expect(settingsDialog(page)).toHaveCount(0);
    await expect.poll(() => marker(page)).toBeNull();
    await settled(page, 0);
    // The boot heal: the entry reloaded carried 'manual' from the layer that was open.
    expect(await scrollRestoration(page)).toBe('auto');

    // A Back from here is a harmless dead press (the entry below is the app's own base); nothing reopens.
    await page.goBack();
    await expect(page.getByTestId('window-first-shell')).toBeVisible();
    await expect(settingsDialog(page)).toHaveCount(0);
  });
});

test.describe('Back closes the top dialog: a confirm over an Operations panel', () => {
  test('goBack cancels the confirm and the panel stays; the next goBack closes the panel', async ({ page }) => {
    await loginAndWaitForForecast(page);
    const users = await page.request.get('/api/users', { headers: { Authorization: `Bearer ${await tokenOf(page)}` } });
    expect(users.status()).toBe(200);
    const admin = (await users.json()).find((user) => user.username === 'admin');
    expect(admin, 'the seeded admin').toBeTruthy();

    await openTab(page, 'operations');
    // The Users list, then the panel: each hash write is its own history entry (useHashSelection), so Back
    // already steps out of the panel to the list. (From a non-manage hash it would not: that is ignored.)
    await page.getByTestId('manage-tab-users').click();
    await expect(page.getByTestId('user-list')).toBeVisible();
    await page.evaluate((id) => { window.location.hash = `manage/users/${id}`; }, admin.id);
    const panel = page.getByTestId('user-panel');
    await expect(panel).toBeVisible();
    const base = await entryIndex(page);

    await page.getByTestId(`reset-password-${admin.id}`).click();
    const confirm = page.getByTestId('confirm-dialog');
    await expect(confirm).toBeVisible();
    await settled(page, 1);
    expect(await entryIndex(page)).toBe(base + 1);

    await page.goBack();

    // Back cancelled the confirm (and never confirmed it: that would reset the shared admin's password).
    await expect(confirm).toHaveCount(0);
    await expect(panel).toBeVisible();
    await expect(page).toHaveURL(new RegExp(`#manage/users/${admin.id}$`));
    await expectBaseline(page, base);

    await page.goBack();

    // The hash entry took us out of the panel, back to the list the reader came from: exactly one entry down.
    await expect(panel).toHaveCount(0);
    await expect(page.getByTestId('user-list')).toBeVisible();
    await expect(page).toHaveURL(/#manage\/users$/);
    expect(await entryIndex(page)).toBe(base - 1);
  });
});

test.describe('Back closes the top dialog: the guide', () => {
  test('goBack closes the guide over Settings after an in-guide link; the x leaves no dead press', async ({ page, hygiene }) => {
    await stubGuideFrame(page, hygiene);
    await loginAndWaitForForecast(page);
    const base = await entryIndex(page);
    await openSettings(page);

    /** Opens the guide over Settings and follows a link inside it (which replaces, never adds, an entry). */
    const openGuideAndFollowALink = async () => {
      await page.getByTestId('settings-guide').click();
      const guide = page.getByTestId('guide-dialog');
      await expect(guide).toBeVisible();
      await settled(page, 2);
      await expect(page.getByTestId('guide-title')).toHaveText('Settings');
      const frame = page.frameLocator('[data-testid="guide-frame"]');
      await frame.locator('#next').click();
      await expect(page.getByTestId('guide-title')).toHaveText('Map');
      await settled(page, 2);
      expect(await entryIndex(page), 'an in-guide link adds no history entry').toBe(base + 2);
      // A #section link (the real guide.js: replaceState + scrollIntoView) adds none either, and is reported.
      await frame.locator('#sect').click();
      await expect(page.getByTestId('guide-new-tab')).toHaveAttribute('href', /#sec$/);
      await settled(page, 2);
      expect(await entryIndex(page), 'a #section link adds no history entry').toBe(base + 2);
      return guide;
    };

    const guide = await openGuideAndFollowALink();
    await page.goBack();
    await expect(guide).toHaveCount(0);
    await expect(settingsDialog(page)).toBeVisible();
    await settled(page, 1);
    expect(await entryIndex(page)).toBe(base + 1);

    // Close with the x instead: the guide's entry goes with it, so the next Back closes Settings and nothing else.
    await openGuideAndFollowALink();
    await page.getByTestId('guide-close').click();
    await expect(page.getByTestId('guide-dialog')).toHaveCount(0);
    await settled(page, 1);
    expect(await entryIndex(page), 'no dead press after the x').toBe(base + 1);
    expect((await marker(page))?.depth).toBe(1);

    await page.goBack();
    await expect(settingsDialog(page)).toHaveCount(0);
    await expectBaseline(page, base);
  });
});

test.describe('Back closes the top dialog: phone sheets', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  const askSheet = (page) => page.getByRole('dialog', { name: 'Ask PhotoCast' });
  const guideSheet = (page) => page.getByRole('dialog', { name: 'PhotoCast guide' });

  test('goBack closes the Ask sheet, and the guide sheet over it first', async ({ page, hygiene }) => {
    await stubGuideFrame(page, hygiene);
    await loginAndWaitForForecast(page);
    await openTab(page, 'plan');
    const base = await entryIndex(page);

    await page.getByTestId('ask-bar').click();
    await expect(askSheet(page)).toBeVisible();
    await settled(page, 1);
    await page.goBack();
    await expect(askSheet(page)).toHaveCount(0);
    await expectBaseline(page, base);

    await page.getByTestId('ask-bar').click();
    await expect(askSheet(page)).toBeVisible();
    await settled(page, 1);
    await page.getByTestId('ask-sheet-guide').click();
    await expect(guideSheet(page)).toBeVisible();
    await settled(page, 2);

    await page.goBack();
    await expect(guideSheet(page)).toHaveCount(0);
    await expect(askSheet(page)).toBeVisible();
    await settled(page, 1);

    await page.goBack();
    await expect(askSheet(page)).toHaveCount(0);
    await expectBaseline(page, base);
  });

  test('closing the Ask sheet with its x or Escape leaves no dead press', async ({ page }) => {
    await loginAndWaitForForecast(page);
    await openTab(page, 'plan');
    const base = await entryIndex(page);

    await page.getByTestId('ask-bar').click();
    await expect(askSheet(page)).toBeVisible();
    await settled(page, 1);
    await askSheet(page).getByTestId('bottom-sheet-close').click();
    await expect(askSheet(page)).toHaveCount(0);
    await expectBaseline(page, base);

    await page.getByTestId('ask-bar').click();
    await expect(askSheet(page)).toBeVisible();
    await settled(page, 1);
    await page.keyboard.press('Escape');
    await expect(askSheet(page)).toHaveCount(0);
    await expectBaseline(page, base);
  });

  test('the page behind keeps the scroll it was given while the sheet was up, after the x, Escape and goBack', async ({ page }) => {
    await loginAndWaitForForecast(page);
    await openTab(page, 'plan');
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
    const room = await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight);
    expect(room, 'the Plan page scrolls at 390 px').toBeGreaterThan(220);
    const target = Math.min(240, room);
    const scrollY = () => page.evaluate(() => Math.round(window.scrollY));
    await page.evaluate((y) => window.scrollTo(0, y), target);
    await expect.poll(scrollY).toBe(target);

    // The scroll is changed WHILE the sheet is up, so each close below must keep that value: with the browser's
    // own restoration left on, the traversal that closes the sheet would put the base entry's scroll back.
    let moved = target;
    const open = async () => {
      await page.getByTestId('ask-bar').click();
      await expect(askSheet(page)).toBeVisible();
      await settled(page, 1);
      moved -= 40;
      await page.evaluate((y) => window.scrollTo(0, y), moved);
      await expect.poll(scrollY).toBe(moved);
    };
    const expectKept = async (how) => {
      await expect(askSheet(page)).toHaveCount(0);
      await settled(page, 0);
      expect(await scrollY(), `after ${how}`).toBe(moved);
      expect(await scrollRestoration(page), `restoration is healed after ${how}`).toBe('auto');
    };

    await open();
    await askSheet(page).getByTestId('bottom-sheet-close').click();
    await expectKept('the x');

    await open();
    await page.keyboard.press('Escape');
    await expectKept('Escape');

    await open();
    await page.goBack();
    await expectKept('goBack');
  });
});

test.describe('Back closes the top dialog: the Plan surfaces', () => {
  /** Opens the Plan and a window popup that is not the first card (it may be the window about to pass). */
  async function openPopup(page) {
    await openTab(page, 'plan');
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
    const card = page.getByTestId('wf-heat-card').nth(2);
    await card.click();
    const popup = page.getByTestId('window-sheet');
    await expect(popup).toBeVisible();
    await expect.poll(() => popup.getByTestId('window-spot').count()).toBeGreaterThanOrEqual(6);
    return { card, popup };
  }

  /** Opens a place's four-day sheet from the popup, so two layers stand. */
  async function openLocationSheet(page, popup) {
    const spot = popup.getByTestId('window-spot').filter({ hasText: 'Bamburgh Beach' });
    await expect(spot).toHaveCount(1);
    await spot.click();
    await expect(page.getByTestId('location-sheet')).toBeVisible();
    await settled(page, 2);
    return spot;
  }

  test('popup, place, location sheet: the first goBack closes the sheet only, the second the popup', async ({ page }) => {
    test.setTimeout(60_000);
    await installDialogDepthObserver(page);
    await loginAndWaitForForecast(page);
    await openTab(page, 'plan');
    const base = await entryIndex(page);
    const url = page.url();
    const { card, popup } = await openPopup(page);
    await settled(page, 1);
    const spot = await openLocationSheet(page, popup);
    expect(await entryIndex(page)).toBe(base + 2);
    expect((await marker(page))?.depth).toBe(2);

    await page.goBack();

    await expect(page.getByTestId('location-sheet')).toHaveCount(0);
    await expect(popup).toBeVisible();
    await expect(spot).toBeFocused();
    await settled(page, 1);
    expect(await entryIndex(page)).toBe(base + 1);

    await page.goBack();

    await expect(popup).toHaveCount(0);
    await expect(card).toBeFocused();
    await expectBaseline(page, base);
    expect(page.url(), 'the URL never changes').toBe(url);
    await expect(page.getByTestId('window-first-tab-plan')).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByTestId('window-first-shell')).toBeVisible();

    const depth = await dialogDepth(page);
    expect(depth.maxDialogs, 'concurrent dialogs (a lower bound: the observer really saw both layers)').toBe(2);
    expect(depth.maxModal, 'concurrent aria-modal elements').toBeLessThanOrEqual(1);
    expect(depth.nowDialogs).toBe(0);
  });

  test('Escape closes the top layer, then goBack closes the next: no dead press and no double close', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openTab(page, 'plan');
    const base = await entryIndex(page);
    const { popup } = await openPopup(page);
    await settled(page, 1);
    await openLocationSheet(page, popup);

    await page.keyboard.press('Escape');

    await expect(page.getByTestId('location-sheet')).toHaveCount(0);
    await expect(popup).toBeVisible();
    await settled(page, 1);
    expect(await entryIndex(page), 'Escape took its entry with it').toBe(base + 1);

    await page.goBack();

    await expect(popup).toHaveCount(0);
    await expectBaseline(page, base);
  });

  test('two layers, then the Map tab: the entries are consumed and a stale Forward reopens nothing', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openTab(page, 'plan');
    const base = await entryIndex(page);
    const { popup } = await openPopup(page);
    await settled(page, 1);
    await openLocationSheet(page, popup);

    // The tab bar sits under the modal scrim, so the real route to `selectTab` with two layers up is the sheet's own
    // "show on map" door, which closes both and moves the tab.
    await page.getByTestId('location-sheet-map').click();
    await expect(page.getByTestId('window-first-tab-map')).toHaveAttribute('aria-selected', 'true');

    await expect(popup).toHaveCount(0);
    await expect(page.getByTestId('location-sheet')).toHaveCount(0);
    await expectBaseline(page, base);

    // The two stale entries still sit ahead of the baseline. Forward lands on one: it is bounced, nothing reopens, and
    // the page ends on the baseline again. (A goBack from the baseline would leave the origin, which is correct and
    // is not what this asserts.)
    expect(await page.evaluate(() => window.navigation.canGoForward), 'stale entries ahead, so the bounce is exercised').toBe(true);
    await page.goForward();

    await expectBaseline(page, base);
    await expect(page.getByTestId('window-sheet')).toHaveCount(0);
    await expect(page.getByTestId('location-sheet')).toHaveCount(0);
    await expect(page.getByTestId('window-first-tab-map')).toHaveAttribute('aria-selected', 'true');
  });

  test('over the Map: callout, "Four days here", the sheet; goBack closes the sheet and keeps the callout', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openMapTab(page);
    const base = await entryIndex(page);
    const chip = visibleChips(page).first();
    const [, name] = CHIP_NAME.exec(await chip.getAttribute('aria-label'));
    await chip.click();
    const callout = page.getByTestId('map-callout');
    await expect(callout).toBeVisible();
    await expect(callout).toContainText(name);
    // The callout is not a layer: opening it makes no entry.
    await settled(page, 0);
    expect(await entryIndex(page)).toBe(base);

    const reason = page.getByTestId('map-callout-reason');
    await reason.click();
    const sheet = page.getByTestId('location-sheet');
    await expect(sheet).toBeVisible();
    await settled(page, 1);

    await page.goBack();

    await expect(sheet).toHaveCount(0);
    await expect(callout).toBeVisible();
    await expect(callout).toContainText(name);
    await expect(reason).toBeFocused();
    await expectBaseline(page, base);
  });

  test('Plan search: goBack closes it', async ({ page }) => {
    await loginAndWaitForForecast(page);
    await openTab(page, 'plan');
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
    const base = await entryIndex(page);

    await page.getByTestId('window-first-search').click();
    const panel = page.getByTestId('plan-search-panel');
    await expect(panel).toBeVisible();
    await settled(page, 1);

    await page.goBack();

    await expect(panel).toHaveCount(0);
    await expectBaseline(page, base);
  });
});
