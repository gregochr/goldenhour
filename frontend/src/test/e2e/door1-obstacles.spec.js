import { test, expect } from '@playwright/test';

/**
 * The increment's own check 5, scripted (`plan-to-map-doors-plan.md` §3 D4 task 5, §7 check 5) —
 * "No overlay control covers a field label: sample `elementFromPoint` across each chip's width in
 * every window, not just the one on screen." jsdom cannot lay anything out, so this is the one
 * check no Vitest suite can make; `WindowRowFieldMap.test.jsx`'s own "Door 1" describe block pins
 * the placement ARITHMETIC with stubbed rects, and this spec proves the arithmetic matches what a
 * real browser actually paints and hit-tests.
 *
 * <p>The increment's defect appeared in 4 of 6 windows in the prototype — "one window proves
 * nothing" is the plan's own words — so this sweeps every window the matrix serves, not just the
 * first popup a reader happens to open.
 *
 * <p>Needs a seeded local stack: ratings on ≥ 6 windows (`docs/engineering/heat-field-plan.md`
 * §7.3), a backend restart after inserting (the briefing enrichment reads an in-memory map
 * populated only at startup) and a fresh `POST /api/briefing/run`. `BACKEND`/`baseURL` are both
 * env-overridable so this can run against the local verification recipe's own ports rather than
 * the app's defaults (8083/5173), which the pane supervisor's own launch.json instance already
 * occupies during a review session.
 */

const BACKEND = process.env.PLAYWRIGHT_BACKEND_URL || 'http://127.0.0.1:8083';

async function loginAsAdmin(page) {
  const response = await page.request.post(`${BACKEND}/api/auth/login`, {
    data: { username: 'admin', password: 'golden2026' },
  });
  const { accessToken, refreshToken, refreshExpiresAt, username } = await response.json();
  await page.evaluate(({ token, refresh, refreshExpires, user }) => {
    localStorage.setItem('goldenhour_token', token);
    localStorage.setItem('goldenhour_refresh', refresh);
    localStorage.setItem('goldenhour_role', 'ADMIN');
    localStorage.setItem('goldenhour_username', user);
    localStorage.setItem('goldenhour_refresh_expires', refreshExpires);
  }, { token: accessToken, refresh: refreshToken, refreshExpires: refreshExpiresAt, user: username });
}

/**
 * Samples `document.elementFromPoint` every 2px across one chip's width at its own vertical
 * centre — the increment's own check 5, verbatim. The hit must be the chip itself or a descendant
 * of it (the chip's own `<i>` marker / `<b>` name / `<em>` rating spans), never the door button
 * sitting on top of it.
 *
 * @returns {{sampled: number, hits: number, overlaps: Array<object>}}
 */
async function sweepChip(page, chipHandle) {
  return page.evaluate((chip) => {
    const rect = chip.getBoundingClientRect();
    const cy = rect.top + rect.height / 2;
    let sampled = 0;
    let hits = 0;
    const overlaps = [];
    // Inset 1px from each edge: `.wf-mchip` has a rounded border-radius, so the LITERAL outer
    // boundary pixel anti-aliases against whatever sits behind it (the canvas) regardless of any
    // obstacle seed — sampling it is a sub-pixel rounding artifact of the chip's own corner, not a
    // claim about the door button. A reader cannot click that pixel either.
    for (let x = rect.left + 1; x <= rect.right - 1; x += 2) {
      sampled += 1;
      const el = document.elementFromPoint(x, cy);
      const isChipOrDescendant = el != null && (el === chip || chip.contains(el));
      if (isChipOrDescendant) {
        hits += 1;
      } else {
        overlaps.push({
          x, y: cy, tag: el ? el.tagName : null, testid: el ? el.dataset?.testid : null,
        });
      }
    }
    return { sampled, hits, overlaps };
  }, chipHandle);
}

/** Whether the door button's and every chip's rects are unchanged across two animation frames. */
async function isFieldStable(page) {
  return page.evaluate(() => new Promise((resolve) => {
    const snapshot = () => {
      const rectOf = (el) => {
        const r = el.getBoundingClientRect();
        return `${r.left},${r.top},${r.width},${r.height}`;
      };
      const btn = document.querySelector('[data-testid="wf-row-map-open"]');
      const chips = [...document.querySelectorAll('[data-testid="wf-row-map-chip"]')];
      return [btn ? rectOf(btn) : ''].concat(chips.map(rectOf)).join('|');
    };
    const before = snapshot();
    requestAnimationFrame(() => requestAnimationFrame(() => resolve(snapshot() === before)));
  }));
}

/**
 * Waits for `WindowRowFieldMap`'s two-pass chip placement to finish moving before anything reads
 * a position. Every candidate chip first renders off-screen at `left: -9999px`
 * (`visibility: hidden`) to be measured, then the greedy placer commits final positions in a
 * layout effect — reading positions mid-measurement is a RACE, not a product defect (confirmed by
 * hand: the same window, once settled, sweeps clean). Polls for two consecutive stable reads
 * rather than one, because a single settle check can still land between two nudge attempts on a
 * narrow (phone) frame.
 */
async function waitForFieldToSettle(page) {
  for (let attempt = 0; attempt < 15; attempt += 1) {
    const stableOnce = await isFieldStable(page);
    if (stableOnce) {
      const stableTwice = await isFieldStable(page);
      if (stableTwice) return;
    }
  }
  throw new Error('field placement never settled');
}

/**
 * Whether two boxes are disjoint (no area of overlap). Takes Playwright's `boundingBox()` shape
 * (`{x, y, width, height}`), not a DOMRect's — the two are easy to conflate and a `{left, top,
 * right, bottom}`-shaped comparator fed an `{x, y, width, height}` box compares `undefined` to
 * `undefined` on every side, which is never true, so every pair reads as "overlapping" no matter
 * where either box actually sits. Converts explicitly rather than trusting call sites to agree.
 */
function disjoint(a, b) {
  const ar = {
    left: a.x, top: a.y, right: a.x + a.width, bottom: a.y + a.height,
  };
  const br = {
    left: b.x, top: b.y, right: b.x + b.width, bottom: b.y + b.height,
  };
  return ar.right <= br.left || br.right <= ar.left || ar.bottom <= br.top || br.bottom <= ar.top;
}

test.describe('Door 1 — the field never draws the button over a chip (doors plan §3 D4, §7 check 5)', () => {
  for (const viewport of [
    { name: '1280x800', width: 1280, height: 800 },
    { name: '390x844', width: 390, height: 844 },
  ]) {
    test(`sweeps every matrix window at ${viewport.name}`, async ({ page }) => {
      test.setTimeout(120000);
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.goto('/');
      await loginAsAdmin(page);
      await page.goto('/');

      await page.waitForSelector('[data-testid="wf-heat-card"]', { timeout: 15000 });
      // A chip's width is its location's name in a font the browser may still be swapping in —
      // `WindowRowFieldMap`'s own doc comment on the two-pass measurement. `waitForFieldToSettle`
      // catches the LAYOUT race; it cannot catch a font finishing its swap after two stable RAF
      // reads, which is a font race, not a layout one — this is the one popup most likely to hit
      // it, since it is the first paint of any text in this font at all.
      await page.evaluate(() => document.fonts.ready);
      const cardCount = await page.getByTestId('wf-heat-card').count();
      expect(cardCount).toBeGreaterThanOrEqual(6);

      const rows = [];

      for (let i = 0; i < cardCount; i += 1) {
        const card = page.getByTestId('wf-heat-card').nth(i);
        const cardLabel = (await card.getAttribute('aria-label')) || `card ${i}`;
        await card.click();
        const sheet = page.getByTestId('window-sheet');
        await expect(sheet).toBeVisible({ timeout: 10000 });

        const openButton = sheet.getByTestId('wf-row-map-open');
        const hasButton = await openButton.count() > 0;
        if (!hasButton) {
          // A window whose payload rates nothing draws the "Not scored" plate instead of the
          // field's chips — no button, and nothing to sweep. Recorded, not asserted against: the
          // plan's own seeding recipe targets "ratings on ≥ 6 windows" precisely so this branch is
          // rare, but an honest sweep does not manufacture chips a real payload withheld.
          rows.push({
            window: cardLabel, chipsSampled: 0, chipsHit: 0, overlapFound: false, note: 'no button (unscored window)',
          });
          await page.getByTestId('window-sheet-close').click();
          await expect(sheet).toBeHidden({ timeout: 5000 });
          continue;
        }
        await expect(openButton).toBeVisible();

        // See `waitForFieldToSettle`'s own doc comment: reading positions mid-measurement is a
        // race that reads as "the button overlaps everything", never a real defect.
        await waitForFieldToSettle(page);

        const chips = sheet.getByTestId('wf-row-map-chip');
        const chipCount = await chips.count();

        const buttonBox = await openButton.boundingBox();
        let overlapFound = false;
        let totalSampled = 0;
        let totalHits = 0;

        for (let c = 0; c < chipCount; c += 1) {
          const chip = chips.nth(c);
          const chipBox = await chip.boundingBox();
          if (buttonBox && chipBox && !disjoint(buttonBox, chipBox)) overlapFound = true;

          const chipHandle = await chip.elementHandle();
          const { sampled, hits, overlaps } = await sweepChip(page, chipHandle);
          totalSampled += sampled;
          totalHits += hits;
          if (overlaps.length > 0) overlapFound = true;
        }

        rows.push({
          window: cardLabel, chipsSampled: chipCount, chipsHit: totalHits, samplePoints: totalSampled, overlapFound,
        });

        // Fails loudly, per-window, rather than only in the aggregate table — a defect in window 4
        // of 6 must not be masked by five clean windows averaging it away.
        expect(overlapFound, `window "${cardLabel}" — the door button overlaps a chip`).toBe(false);

        await page.getByTestId('window-sheet-close').click();
        await expect(sheet).toBeHidden({ timeout: 5000 });
      }

      console.log(`[door1-obstacles ${viewport.name}]`, JSON.stringify(rows, null, 2));
    });
  }
});
