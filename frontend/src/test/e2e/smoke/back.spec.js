import {
  test, expect, loginAndWaitForForecast, openOperations, tokenOf, stubGuideFrame, openMapView, visibleChips, CHIP_NAME,
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
/** Settings is a row of the ☰ menu; the menu is no layer, and it hands focus back to its own button. */
const menuButton = (page) => page.getByTestId('masthead-menu-button');
const settingsRow = (page) => page.getByTestId('window-first-settings');

/** Opens Settings from the menu and waits for the dialog and the store. */
async function openSettings(page, depth = 1) {
  await menuButton(page).click();
  await settingsRow(page).click();
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
    await expect(menuButton(page)).toBeFocused();
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
    await expect(page.getByTestId('masthead-menu-button')).toBeVisible();
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

    await openOperations(page);
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
  /** Opens the Plan and waits for the matrix. */
  async function openPlan(page) {
    await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
  }

  /** Opens a place's four-day sheet through Find, so one layer stands. */
  async function openLocationSheet(page, name) {
    await page.getByTestId('window-first-search').click();
    await page.getByTestId('plan-search-input').fill(name);
    await page.getByTestId('plan-search-row').filter({ hasText: name }).first().click();
    await expect(page.getByTestId('location-sheet')).toBeVisible();
    await settled(page, 1);
  }

  // one-screen-plan.md O1: a card opens the map VIEW, which is itself the base Back layer (it carries a `#map/...`
  // address on the layer); Back closes it and focus returns to the card. No arrival layer, no tab to return to.
  test('a Glance card opens the map view: the first goBack closes it and returns focus to the card, no dead press', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openPlan(page);
    const base = await entryIndex(page);
    const url = page.url();

    // Never the first card: it may be the window about to pass.
    // A pointer lands on the card, which hands focus to its inner button: that is where focus must return.
    const card = page.getByTestId('wf-heat-card').nth(2);
    const cardButton = card.getByTestId('wf-heat-card-button');
    await card.click();
    await expect(page.getByTestId('map-screen')).toBeVisible();
    await settled(page, 1);
    expect(await entryIndex(page), 'the view owns one entry').toBe(base + 1);
    expect(page.url(), 'the entry carries the view\'s address').toMatch(/#map\/\d{4}-\d{2}-\d{2}\/(sunrise|sunset)/);

    await page.goBack();

    await expect(page.getByTestId('map-screen')).toBeHidden();
    await expect(page.getByTestId('this-week-heading')).toBeVisible();
    await expect(cardButton).toBeFocused();
    await expectBaseline(page, base);
    expect(page.url(), 'closing returns the address to the bare path').toBe(url);
  });

  test('closing the view with its close button leaves no entry behind', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openPlan(page);
    const base = await entryIndex(page);
    await page.getByTestId('wf-heat-card').nth(2).click();
    await expect(page.getByTestId('map-screen')).toBeVisible();
    await settled(page, 1);

    await page.getByTestId('map-screen-close').click();

    await expect(page.getByTestId('map-screen')).toBeHidden();
    await expect(page.getByTestId('this-week-heading')).toBeVisible();
    await expectBaseline(page, base);
  });

  // Glance-to-map review H1: the Map's phone sheets portal to <body>, so hiding the pane on Back must close them.
  // The seeded fixture's one warning sits on a single window the card arrival does not choose, so the Regions sheet
  // stands in for the warning sheet (same mount, same `modal={false}`, no Back layer of its own).
  test.describe('on a phone', () => {
    test.use({ viewport: { width: 390, height: 844 } });

    test('Back after a card opens the view leaves no Map bottom sheet over This week', async ({ page }) => {
      test.setTimeout(60_000);
      await loginAndWaitForForecast(page);
      await openPlan(page);
      const base = await entryIndex(page);
      await page.getByTestId('wf-heat-card').nth(2).click();
      await expect(page.getByTestId('map-screen')).toBeVisible();
      await settled(page, 1);
      await page.getByTestId('wf-map-peek-btn-lay').click();
      await page.getByTestId('wf-map-peek-lay-regions').click();
      await expect(page.getByTestId('wf-jump-menu')).toBeVisible();
      await expect(page.getByTestId('bottom-sheet-root')).toHaveCount(1);

      await page.goBack();

      await expect(page.getByTestId('map-screen')).toBeHidden();
      await expect(page.getByTestId('this-week-heading')).toBeVisible();
      await expect(page.getByTestId('bottom-sheet-root')).toHaveCount(0);
      await expect(page.getByTestId('wf-jump-menu')).toHaveCount(0);
      await expectBaseline(page, base);
    });
  });

  test('a place from Find, its location sheet: goBack closes it and focus returns to Find', async ({ page }) => {
    test.setTimeout(60_000);
    await installDialogDepthObserver(page);
    await loginAndWaitForForecast(page);
    await openPlan(page);
    const base = await entryIndex(page);
    const url = page.url();
    await openLocationSheet(page, 'Bamburgh Beach');
    expect(await entryIndex(page)).toBe(base + 1);
    expect((await marker(page))?.depth).toBe(1);

    await page.goBack();

    await expect(page.getByTestId('location-sheet')).toHaveCount(0);
    await expect(page.getByTestId('window-first-search')).toBeFocused();
    await expectBaseline(page, base);
    expect(page.url(), 'the URL never changes').toBe(url);
    await expect(page.getByTestId('this-week-heading')).toBeVisible();

    const depth = await dialogDepth(page);
    expect(depth.maxModal, 'concurrent aria-modal elements').toBeLessThanOrEqual(1);
    expect(depth.nowDialogs).toBe(0);
  });

  test('Escape closes the sheet and takes its entry with it: no dead press', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openPlan(page);
    const base = await entryIndex(page);
    await openLocationSheet(page, 'Bamburgh Beach');

    await page.keyboard.press('Escape');

    await expect(page.getByTestId('location-sheet')).toHaveCount(0);
    await expectBaseline(page, base);
  });

  test('the sheet, then the map view: the sheet\'s entry is taken over by the view (one entry), and Back closes the view', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openPlan(page);
    const base = await entryIndex(page);
    await openLocationSheet(page, 'Bamburgh Beach');

    // The masthead sits under the modal scrim, so the real route is the sheet's own "show on map" door, which closes the
    // sheet and opens the view in one commit: the engine's same-depth swap, no `go(0)` and no second push.
    await page.getByTestId('location-sheet-map').click();
    await expect(page.getByTestId('map-screen')).toBeVisible();

    await expect(page.getByTestId('location-sheet')).toHaveCount(0);
    await settled(page, 1);
    expect(await entryIndex(page), 'one entry, taken over').toBe(base + 1);
    expect(page.url()).toMatch(/#map\//);

    await page.goBack();

    await expect(page.getByTestId('map-screen')).toBeHidden();
    await expectBaseline(page, base);
  });

  test('over the Map: callout, "Four days here", the sheet; goBack closes the sheet and keeps the callout', async ({ page }) => {
    test.setTimeout(60_000);
    await loginAndWaitForForecast(page);
    await openMapView(page);
    // The view is the base layer: one entry, and everything below stacks over it.
    await settled(page, 1);
    const base = await entryIndex(page);
    const chip = visibleChips(page).first();
    const [, name] = CHIP_NAME.exec(await chip.getAttribute('aria-label'));
    await chip.click();
    const callout = page.getByTestId('map-callout');
    await expect(callout).toBeVisible();
    await expect(callout).toContainText(name);
    // The callout is not a layer: opening it makes no entry.
    await settled(page, 1);
    expect(await entryIndex(page)).toBe(base);

    const reason = page.getByTestId('map-callout-reason');
    await reason.click();
    const sheet = page.getByTestId('location-sheet');
    await expect(sheet).toBeVisible();
    await settled(page, 2);

    await page.goBack();

    await expect(sheet).toHaveCount(0);
    await expect(callout).toBeVisible();
    await expect(callout).toContainText(name);
    await expect(reason).toBeFocused();
    // The sheet's entry went; the view's stays.
    await settled(page, 1);
    expect(await entryIndex(page)).toBe(base);
  });

  test('Plan search: goBack closes it', async ({ page }) => {
    await loginAndWaitForForecast(page);
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

// ---------------------------------------------------------------------------------------------------------
// First-run prompts (back-closes-dialogs-plan.md, PR 3)
// ---------------------------------------------------------------------------------------------------------

/**
 * Makes the server's own answer raise the postcode prompt, for THIS page only, and writes nothing to the server.
 *
 * <p>No seeded account can show either first-run prompt on its own: `E2eRoleUsers` inserts `lite` and `pro` with the
 * postcode prompt already dismissed (so no other spec is held inert) and gives `lite` its picks (so it is `OPEN`,
 * never `FIRST_CHOICE`), and the admin's dismissal is written once in `global-setup.js`. A prompt dismissed
 * server-side stays dismissed for the next `--repeat-each` run and for a parallel worker, so a real account could
 * show it once. Instead each test patches the REAL read (`route.fetch()`, then one field) and answers the dismissal
 * itself: the app, the engine and the browser's history are all real, the tests share no state, and a repeat sees
 * the prompt every time.
 *
 * @param {import('@playwright/test').Page} page
 */
async function raisePostcodePrompt(page) {
  let body = {};
  await page.route(/\/api\/user\/settings$/, async (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    const response = await route.fetch();
    body = { ...(await response.json()), postcodePromptDue: true };
    return route.fulfill({ response, json: body });
  });
  await page.route(/\/api\/user\/settings\/postcode-prompt-dismissed$/, (route) => route.fulfill({
    json: { ...body, postcodePromptDue: false },
  }));
}

/**
 * The sub-region sign-in prompt for a Lite reader: the real state with `status: 'FIRST_CHOICE'`, no picks and the
 * first `limit` sub-regions suggested. The save is answered with an `OPEN` state and never reaches the server.
 *
 * @param {import('@playwright/test').Page} page
 */
async function raiseSubRegionPrompt(page) {
  let saved = false;
  let state = {};
  await page.route(/\/api\/user\/settings\/sub-regions$/, async (route) => {
    const method = route.request().method();
    if (method === 'PUT') {
      saved = true;
      return route.fulfill({ json: { ...state, status: 'OPEN' } });
    }
    if (method !== 'GET' || saved) return route.fallback();
    const response = await route.fetch();
    const real = await response.json();
    state = real;
    const suggested = real.options.flatMap((region) => region.subRegions.map((s) => s.areaId)).slice(0, real.limit);
    return route.fulfill({
      response,
      json: { ...real, enforced: true, status: 'FIRST_CHOICE', picks: [], suggested, freeUntilIfChosenToday: '2099-01-01' },
    });
  });
}

/**
 * Withholds the ambient user activation, so "before the reader has interacted" is a state a spec can be in.
 *
 * <p>Playwright evaluates every `page.evaluate` (and `goBack`) WITH a user gesture, so under it
 * `navigator.userActivation.isActive` is already true when the prompt opens, and a real Chromium would never be.
 * This replaces `navigator.userActivation` with a stub that reports active only after a TRUSTED click or key
 * press reaches the page, the way a reader's first tap does. The history, the engine and the guard are all real;
 * only the browser's activation flag is modelled, and whether real Chromium honours the entry that follows is
 * the manual device pass (a release blocker in the plan).
 *
 * <p>Limits of the stub: its activation NEVER expires (a real one lapses after about 5 s); it grants on any trusted
 * `keydown`, Escape included; and it cannot tell a `click` from a `pointerdown` (it listens to both), so the rule
 * "never gate on pointerdown" is proved by the Vitest hook test, not here.
 *
 * @param {import('@playwright/test').Page} page
 */
async function withholdAmbientActivation(page) {
  await page.addInitScript(() => {
    let active = false;
    const grant = (event) => { if (event.isTrusted) active = true; };
    ['click', 'keyup', 'keydown', 'pointerdown'].forEach((type) => window.addEventListener(type, grant, true));
    Object.defineProperty(Navigator.prototype, 'userActivation', {
      configurable: true,
      get: () => ({ isActive: active, hasBeenActive: active }),
    });
  });
}

/** A Back from the baseline can only leave: the page ends up off the app's URL. */
async function expectLeftTheApp(page, appUrl) {
  await expect.poll(() => page.url(), 'Back from the baseline leaves the app').not.toBe(appUrl);
}

test.describe('Back closes the top dialog: First-run prompts', () => {
  test('postcode prompt: no entry before a click, Back absorbed after one, Not now releases it', async ({ page }) => {
    await withholdAmbientActivation(page);
    await raisePostcodePrompt(page);
    await loginAndWaitForForecast(page, 'pro');
    const prompt = page.getByTestId('postcode-prompt');
    await expect(prompt).toBeVisible();
    const url = page.url();
    const base = await entryIndex(page);

    // Before any click there is no entry: the guard waits for a user activation (Chromium would otherwise skip it).
    await settled(page, 0);
    expect(await entryIndex(page), 'no entry before the first click').toBe(base);
    expect(await marker(page)).toBeNull();

    // A click inside the prompt (its own heading: not an exit) is the activation that registers the guard.
    await prompt.getByRole('heading', { name: 'Set your home postcode' }).click();
    await settled(page, 1);
    expect(await entryIndex(page)).toBe(base + 1);

    await page.goBack();

    await expect(prompt).toBeVisible();
    expect(page.url(), 'the URL never changes').toBe(url);
    await settled(page, 1);
    expect(await entryIndex(page), 'the entry was pushed back').toBe(base + 1);

    // A second Back is absorbed as well. This proves the ENGINE re-arms its guard after each absorbed Back. It does
    // not prove real Chromium keeps it: Playwright does not apply Chromium's skippable-entry rule, and the re-push
    // from popstate has no activation of its own, so a quick second Back with no tap between can still leave on
    // Chrome (the manual Android pass records it).
    await page.goBack();
    await expect(prompt).toBeVisible();
    await settled(page, 1);

    await page.getByTestId('prompt-secondary').click();
    await expect(prompt).toHaveCount(0);
    await expectBaseline(page, base);

    // No dead press: with the prompt gone, one more Back leaves to whatever is below the app.
    await page.goBack();
    await expectLeftTheApp(page, url);
  });

  test('sub-region prompt: Back absorbed after a click, Continue releases it', async ({ page }) => {
    await withholdAmbientActivation(page);
    await raiseSubRegionPrompt(page);
    await loginAndWaitForForecast(page, 'lite');
    const prompt = page.getByTestId('sub-region-prompt');
    await expect(prompt).toBeVisible();
    const url = page.url();
    const base = await entryIndex(page);

    await settled(page, 0);
    expect(await entryIndex(page), 'no entry before the first click').toBe(base);

    await prompt.getByRole('heading', { name: 'Choose your sub-regions' }).click();
    await settled(page, 1);
    expect(await entryIndex(page)).toBe(base + 1);

    await page.goBack();

    await expect(prompt).toBeVisible();
    expect(page.url()).toBe(url);
    await settled(page, 1);
    expect(await entryIndex(page)).toBe(base + 1);

    await prompt.getByRole('button', { name: 'Continue' }).click();
    await expect(prompt).toHaveCount(0);
    await expectBaseline(page, base);

    await page.goBack();
    await expectLeftTheApp(page, url);
  });
});
