import { test, expect, loginAndWaitForForecast, openTab } from './fixture.js';

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
 * <p>It runs in the smoke gate on the SEEDED fixture (`E2eLocalFixtureSeeder`), which rates every place in
 * every window, so no window falls into the "Not scored" branch and the sweep has chips in all of them. The
 * old "restart the backend and POST /api/briefing/run" setup is gone: the seed replaces it. A sample point
 * that lands outside the viewport reads as an overlap (`elementFromPoint` returns null there), so it fails.
 */

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
function isFieldStable(page) {
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
 * Waits for `WindowRowFieldMap`'s two-pass chip placement to finish moving before anything reads a position.
 * Every candidate chip first renders off-screen at `left: -9999px` (`visibility: hidden`) to be measured, then
 * the greedy placer commits final positions in a layout effect — reading positions mid-measurement is a RACE,
 * not a product defect. The fonts are awaited first (a chip's width is its name in a font the browser may still
 * be swapping in), then the field must read stable TWICE in a row; both are retrying `expect.poll`s, never a
 * bounded loop.
 */
async function waitForFieldToSettle(page) {
  await page.evaluate(() => document.fonts.ready);
  await expect.poll(() => isFieldStable(page), { message: 'field placement settling' }).toBe(true);
  await expect.poll(() => isFieldStable(page), { message: 'field placement settled' }).toBe(true);
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
      test.setTimeout(120_000);
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await loginAndWaitForForecast(page);
      await openTab(page, 'plan');

      await expect(page.getByTestId('wf-heat-card').first()).toBeVisible();
      await page.evaluate(() => document.fonts.ready);
      await expect.poll(() => page.getByTestId('wf-heat-card').count()).toBeGreaterThanOrEqual(6);
      const cardCount = await page.getByTestId('wf-heat-card').count();

      const rows = [];

      for (let i = 0; i < cardCount; i += 1) {
        const card = page.getByTestId('wf-heat-card').nth(i);
        const cardLabel = (await card.getAttribute('aria-label')) || `card ${i}`;
        await card.click();
        const sheet = page.getByTestId('window-sheet');
        await expect(sheet).toBeVisible();

        // Playwright's click leaves the pointer parked at the card's centre. When the opened sheet puts a spot
        // card under that point, its hover peek (`wf-peek`, fixed, z-index 60) legitimately overlays the field:
        // that is the reader's own hover, not a placement defect, and this spec is about what the field draws on
        // its own. Park the pointer off the sheet and wait for the peek to close before sampling.
        await page.mouse.move(0, 0);
        await expect(page.getByTestId('wf-peek')).toHaveCount(0);

        // The seeded fixture rates every place in every window the matrix serves, except the ones the
        // briefing has already let pass: a window with no field draws no door button and has nothing to sweep.
        const openButton = sheet.getByTestId('wf-row-map-open');
        if (await openButton.count() === 0) {
          rows.push({
            window: cardLabel, chipsSampled: 0, chipsHit: 0, overlapFound: false, note: 'no button (unscored window)',
          });
          await page.getByTestId('window-sheet-close').click();
          await expect(sheet).toBeHidden();
          continue;
        }
        await expect(openButton).toBeVisible();
        await waitForFieldToSettle(page);

        const chips = sheet.getByTestId('wf-row-map-chip');
        const chipCount = await chips.count();

        const buttonBox = await openButton.boundingBox();
        let overlapFound = false;
        const coveredBy = new Set();
        const chipNames = [];
        let totalSampled = 0;
        let totalHits = 0;

        for (let c = 0; c < chipCount; c += 1) {
          const chip = chips.nth(c);
          const chipBox = await chip.boundingBox();
          if (buttonBox && chipBox && !disjoint(buttonBox, chipBox)) {
            overlapFound = true;
            coveredBy.add('wf-row-map-open');
            chipNames.push((await chip.innerText()).trim());
          }

          const chipHandle = await chip.elementHandle();
          const { sampled, hits, overlaps } = await sweepChip(page, chipHandle);
          totalSampled += sampled;
          totalHits += hits;
          if (overlaps.length > 0) {
            overlapFound = true;
            overlaps.forEach((o) => coveredBy.add(o.testid ?? o.tag ?? 'nothing (outside the viewport)'));
            chipNames.push((await chip.innerText()).trim());
          }
        }

        rows.push({
          window: cardLabel, chipsSampled: chipCount, chipsHit: totalHits, samplePoints: totalSampled, overlapFound,
        });

        // Fails loudly, per-window, rather than only in the aggregate table — a defect in window 4
        // of 6 must not be masked by five clean windows averaging it away.
        expect(
          overlapFound,
          `window "${cardLabel}" — a chip is covered (by: ${[...coveredBy].join(', ')}; chips: ${
            [...new Set(chipNames)].join(' | ').replace(/\s+/g, ' ')})`,
        ).toBe(false);

        await page.getByTestId('window-sheet-close').click();
        await expect(sheet).toBeHidden();
      }

      // The sweep must have looked at something: a run in which every window fell into the "no button"
      // branch would pass vacuously.
      const swept = rows.filter((row) => row.chipsSampled > 0);
      expect(swept.length, `windows with chips swept: ${JSON.stringify(rows)}`).toBeGreaterThanOrEqual(5);
      console.log(`[door1-obstacles ${viewport.name}]`, JSON.stringify(rows, null, 2));
    });
  }
});
