import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import BottomSheet from '../components/BottomSheet';
import Modal from '../components/shared/Modal';

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

/**
 * Strips CSS comments, so a rule can be asserted without the file's prose standing in for it.
 *
 * <p>Borrowed from `WindowFirstShellSticky.test.jsx`, which learned it first, and load-bearing here
 * in BOTH directions: `.wf-mast`'s block quotes `calc(env(safe-area-inset-top, 0px) + 16px)` as the
 * anti-pattern it refuses, so prose could satisfy the population guard below on its own, and a
 * future comment quoting a bare `env(safe-area-inset-top)` as the mistake to avoid would fail the
 * suite for a declaration that does not exist.
 */
const decls = (text) => text.replace(/\/\*[\s\S]*?\*\//g, '');

/** Slices `index.css` from a rule's selector to the closing brace of its declaration block. */
const rule = (selector) => {
  const css = decls(read('../index.css'));
  const start = css.indexOf(`${selector} {`);
  expect(start, `${selector} must still be findable in index.css`).toBeGreaterThan(-1);
  const end = css.indexOf('}', start);
  expect(end, `${selector} must still have a closing brace`).toBeGreaterThan(start);
  return css.slice(start, end);
};

/**
 * Safe-area handling — the opt-in, the invariant that protects every other surface from it, the
 * elements that touch a viewport edge, and the two ways an inset gets silently cancelled.
 *
 * ⚠️ These are TEXT assertions against the stylesheet, not computed-style ones, and that is not
 * laziness. jsdom resolves neither `var()` nor `env()`, so `getComputedStyle` on any rule here
 * returns the empty string whether the declaration is right, wrong or absent — the same trap that
 * shipped a badge against an undeclared token (see `MastheadTickLine.test.jsx`). The geometric
 * claims quoted in the comments below were measured separately, in headless Chromium against the
 * BUILT stylesheet with the insets substituted for real device values.
 */
describe('safe areas', () => {
  describe('the opt-in', () => {
    it('index.html sets viewport-fit=cover, without which every rule below is inert', () => {
      // `env(safe-area-inset-*)` resolves to zero on every device until the document asks for the
      // full viewport. Delete this and the whole feature silently becomes a no-op — nothing else
      // in the suite would notice, which is why it is pinned here rather than assumed.
      expect(read('../../index.html')).toMatch(/<meta name="viewport"[^>]*viewport-fit=cover/);
    });
  });

  describe('⚠️ the zero-inset invariant', () => {
    it('every env(safe-area-*) in the stylesheet carries a 0px fallback', () => {
      // Two things at once. A bare `env(safe-area-inset-top)` is an INVALID declaration on a
      // browser that does not know the variable — the whole declaration is dropped, taking the
      // `top` or `padding` it was carrying with it — so the fallback is what keeps these rules from
      // being a downgrade elsewhere. And it is what makes each one provably identical to the
      // geometry it replaced wherever no inset is reported. Measured: at zero insets the app root
      // computes `0px` padding, the modal `16px` on all four sides (exactly the `p-4` it replaced),
      // and the phone popup `0px` — byte-identical to before this work.
      const css = decls(read('../index.css'));
      const uses = [...css.matchAll(/env\(safe-area-inset-[a-z]+[^)]*\)/g)].map((m) => m[0]);

      expect(uses.length, 'the stylesheet must use env() somewhere, or this proves nothing')
        .toBeGreaterThan(3);
      uses.forEach((use) => {
        expect(use, `${use} must fall back to 0px`).toMatch(/,\s*0px\)$/);
      });
    });

    it('the six insets are declared once on :root and read as vars everywhere else', () => {
      // One term per inset, for the reason `--wf-lens-reserve` exists: a layout term written out at
      // each site is a term that gets half-changed. `--safe-v`/`--safe-h` are the sums the dialog
      // caps need, because a `max-height` competes with BOTH insets on its axis, never one.
      const root = rule(':root');

      ['t', 'r', 'b', 'l'].forEach((k) => expect(root).toMatch(new RegExp(`--safe-${k}:`)));
      expect(root).toMatch(/--safe-v: calc\(env\(safe-area-inset-top, 0px\)/);
      expect(root).toMatch(/--safe-h: calc\(env\(safe-area-inset-left, 0px\)/);
    });

    it('the modal resolves its gutter and its inset with max(), never by summing them', () => {
      // Summing would move a dialog 16px further from an edge it was already clear of, on the
      // device class with the least room to give. `max(1rem, …)` is exactly the `p-4` this replaced
      // wherever the inset is smaller — measured at 16px on all four sides at a zero inset.
      const block = rule('.app-safe-modal');

      ['top', 'right', 'bottom', 'left'].forEach((side) => {
        expect(block).toMatch(
          new RegExp(`padding-${side}: max\\(1rem, var\\(--safe-${side[0]}\\)\\)`),
        );
      });
      expect(block).not.toMatch(/calc\(/);
    });
  });

  describe('the elements that touch a viewport edge', () => {
    it('.app-safe insets all four sides for everything in normal flow', () => {
      const block = rule('.app-safe');

      ['top', 'right', 'bottom', 'left'].forEach((side) => {
        expect(block).toMatch(new RegExp(`padding-${side}: var\\(--safe-${side[0]}\\)`));
      });
    });

    it('⚠️ the sticky lens bar names the top inset itself, because the root cannot reach it', () => {
      // A sticky element sticks to its scrollport — the viewport, which `viewport-fit=cover` has
      // just extended under the status bar — not to any ancestor's padding box. `top: 0` here would
      // park the bar under the notch however much padding the root carries.
      const block = rule('.wf-lens');

      expect(block).toMatch(/position: sticky/);
      expect(block).toMatch(/top: var\(--safe-t\)/);
    });

    it('⚠️ the rows that stack under the lens bar start from the inset, not from its height', () => {
      // `--wf-lens-h` is the bar's measured HEIGHT, not the line it rests on. Without `--safe-t`
      // first, a nonzero top inset would put the bar at [inset, inset+h] while these stuck at h-1
      // — inside the bar's own band, which is the overlap the anchoring fix removed.
      expect(rule('.wf-dhrow')).toMatch(/top: calc\(var\(--safe-t\) \+ var\(--wf-lens-h/);
      expect(rule('.wf-rail')).toMatch(/top: calc\(var\(--safe-t\) \+ var\(--wf-lens-h/);
    });

    it('⚠️ the focus reservation adds the inset, or a focused card lands behind the bar', () => {
      // `scroll-margin-top` is measured from the scrollport edge, which `viewport-fit=cover` moves
      // up by the inset. `useLensReserve` publishes a height and knows nothing about the offset, so
      // without this term a keyboard-focused card is obscured by exactly the inset — WCAG 2.2
      // SC 2.4.11 Focus Not Obscured (Minimum), the defect the reservation exists to prevent.
      const css = decls(read('../index.css'));
      const reserves = [...css.matchAll(/scroll-margin-top: [^;]+wf-lens-reserve[^;]*/g)]
        .map((m) => m[0]);

      expect(reserves.length, 'the reservation sites must still exist').toBeGreaterThan(1);
      reserves.forEach((r) => expect(r).toMatch(/var\(--safe-t\)/));
    });

    it('⚠️ the sheet INSETS its sides and PADS its foot, which are not interchangeable', () => {
      // The FOOT is the half that does work — `--safe-b` is live on any home-screen iPhone, and
      // padding keeps the sheet's surface running to the screen edge while its content clears the
      // indicator. The SIDES are defence, not a verified fix: all three BottomSheet mounts are
      // gated on `useIsMobile` (max-width 639px) and no iPhone reports 639px or fewer in landscape,
      // so those terms are always 0 wherever a sheet can mount. They are insets rather than padding
      // because that is the form that would be right if the breakpoint moved — the close button is
      // `position: absolute; right: …` against this element's padding box, so `padding-right` would
      // strand it where an inset moves it.
      const block = rule('.app-safe-sheet');

      expect(block).toMatch(/left: var\(--safe-l\)/);
      expect(block).toMatch(/right: var\(--safe-r\)/);
      expect(block).toMatch(/padding-bottom: var\(--safe-b\)/);
      expect(block, 'padding on the sides would strand the close button')
        .not.toMatch(/padding-(left|right):/);
    });
  });

  describe('⚠️ the two ways a safe inset gets silently cancelled', () => {
    // Both shipped in the first cut of this work and both were found by adversarial review rather
    // than by the suite. They are the reason this block exists.

    it('no dialog cap reconstructs the modal gutter without giving the inset back', () => {
      // `calc(100dvh - 32px)` is Modal's `p-4` counted twice, written as a literal in five places.
      // Once `.app-safe-modal` made that padding `max(1rem, inset)`, each cap ate the whole
      // safe-area padding and left the panel's foot exactly where it was — the feature was a no-op
      // for precisely the dialogs that reach their cap. `--safe-v`/`--safe-b` give it back.
      const sources = ['../index.css', '../components/WindowPickDialog.jsx',
        '../components/PlanSearch.jsx', '../components/BottomSheet.jsx'];

      sources.forEach((file) => {
        const text = file.endsWith('.css') ? decls(read(file)) : read(file);
        [...text.matchAll(/calc\((?:100dvh|60vh)[^)]*\)/g)].map((m) => m[0]).forEach((cap) => {
          expect(cap, `${file}: a viewport-height budget must subtract --safe-v or --safe-b`)
            .toMatch(/var\(--safe-[vb]\)/);
        });
      });
    });

    it('the phone popup drops the modal FRAME without dropping the safe inset', () => {
      // `padding: 0` here is deliberate — a phone popup that is full-screen apart from a 16px frame
      // reads as a card floating over a dead page. But being unlayered, 0,1,0 and thousands of
      // lines below `.app-safe-modal`, it won on source order and cancelled the safe padding
      // outright, on the one device class with an unsafe zone, for the Plan tab's flagship dialog
      // whose `.wf-wsh` is full-height. Measured at 390×844 with a 34px bottom inset: the popup's
      // foot now sits 34px clear where it was flush, and at a zero inset it is still exactly `0px`.
      expect(rule('[data-testid="window-sheet"]'), 'a bare `padding: 0` re-opens the defect')
        .toMatch(/padding: var\(--safe-t\) var\(--safe-r\) var\(--safe-b\) var\(--safe-l\)/);
    });
  });

  describe('⚠️ the Map tab bleeds, and that is two halves of one mechanism', () => {
    // Everywhere else the root's padding insets the page and nothing in flow has to know. The Map
    // tab opts out: the map is scenery, and letterboxing it cost 34px of picture under a 390×844
    // phone and 63px off EACH side of an 844×390 landscape one. So the frame is pulled back out
    // over the padding and the chrome takes the insets as its own terms.

    it('the map tab pulls back over the root padding on the three bled edges', () => {
      const block = rule('.wf-map-tab');

      expect(block).toMatch(/margin-left: calc\(-1 \* var\(--safe-l\)\)/);
      expect(block).toMatch(/margin-right: calc\(-1 \* var\(--safe-r\)\)/);
      expect(block).toMatch(/margin-bottom: calc\(-1 \* var\(--safe-b\)\)/);
      // Top is deliberately absent — nothing pulls the map up over the top inset, because that is
      // where the masthead is.
      expect(block, 'the map must not bleed upward past the masthead')
        .not.toMatch(/margin-top:/);
    });

    it('every chrome edge that now meets the screen carries its inset back', () => {
      // ⚠️ THE HALF THAT IS EASY TO DROP. The negative margins without these terms put the bottom
      // bar under the home indicator — which is exactly the defect the safe-area work existed to
      // fix, reintroduced by the change meant to improve the same surface. Measured at 390×844
      // with a 34px inset: the frame reaches y=844 and the bar's foot sits at y=802, 42px clear.
      const css = decls(read('../index.css'));
      const scoped = css.slice(css.indexOf('.wf-map-tab {'));

      [
        ['.wf-map-tab .wf-map-chrome-bl', /bottom: calc\(8px \+ var\(--safe-b\)\)/],
        ['.wf-map-tab .wf-map-counts-footer', /bottom: calc\(8px \+ var\(--safe-b\)\)/],
        ['.wf-map-tab .wf-map-chrome-tr', /right: calc\(8px \+ var\(--safe-r\)\)/],
        ['.wf-map-tab .wf-map-chrome-tl', /left: calc\(60px \+ var\(--safe-l\)\)/],
        ['.wf-map-tab .leaflet-bottom.leaflet-right', /padding-bottom: var\(--safe-b\)/],
      ].forEach(([selector, pattern]) => {
        const at = scoped.indexOf(`${selector} {`);
        expect(at, `${selector} must exist`).toBeGreaterThan(-1);
        expect(scoped.slice(at, scoped.indexOf('}', at)), `${selector} lost its inset term`)
          .toMatch(pattern);
      });
    });

    it('the bleed is scoped to the tab, so the frozen Plan-tab overlay cannot inherit it', () => {
      // `MapView` mounts on both surfaces and only the tab root carries `wf-map-tab`
      // (`overlayMode` renders none). The overlay sits inside a normally-inset page, so a bleed
      // reaching it would pull it out from under the padding that is doing its job. Scoping is
      // what makes that impossible without anyone having to audit which chrome the overlay
      // happens to render.
      const css = decls(read('../index.css'));
      const at = css.indexOf('.wf-map-tab {');
      const bleed = css.slice(at, css.indexOf('}', at));

      expect(bleed).toMatch(/margin-bottom/);
      expect(css.indexOf('.wf-map-tab {'), 'the bleed rule must exist').toBeGreaterThan(-1);
      // The negative margins appear on the scoped rule and nowhere else in the file.
      const allNegatives = [...css.matchAll(/margin-(?:left|right|bottom): calc\(-1 \* var\(--safe-[lrb]\)\)/g)];
      expect(allNegatives, 'exactly the three bled edges, on one rule').toHaveLength(3);
    });
  });

  describe('the classes reach the elements', () => {
    it('the bottom sheet carries app-safe-sheet and no longer pins its own left/right', () => {
      render(<BottomSheet open onClose={() => {}} label="Sheet"><p>Content</p></BottomSheet>);

      const sheet = screen.getByTestId('bottom-sheet');
      expect(sheet).toHaveClass('app-safe-sheet');
      expect(sheet).toHaveClass('fixed', 'bottom-0');
      // ⚠️ NOT because `left-0` would win — it would not. Measured against the built stylesheet
      // with insets substituted: `left` resolves to 47px with `left-0` still on the element,
      // because Tailwind v4 layers its utilities and these rules are unlayered. This pins the
      // HYGIENE instead: a declaration that can never apply is markup telling the next reader the
      // sheet is pinned to the viewport edges when it is not.
      expect(sheet.className).not.toMatch(/\bleft-0\b/);
      expect(sheet.className).not.toMatch(/\bright-0\b/);
    });

    it('the modal carries app-safe-modal in place of its p-4 gutter', () => {
      render(<Modal onClose={() => {}} label="Dialog" data-testid="d"><p>Content</p></Modal>);

      const dialog = screen.getByTestId('d');
      expect(dialog).toHaveClass('app-safe-modal');
      // Same hygiene rule and the same measurement: an element carrying both `app-safe-modal` and
      // `p-8` measures 16px, so a left-behind `p-4` would be inert rather than harmful. It is still
      // wrong to leave a padding utility on an element whose padding it does not set.
      expect(dialog.className).not.toMatch(/\bp-4\b/);
    });

    it('the app root carries app-safe on both of its layout arms', () => {
      // Asserted at source rather than by rendering `App`: the class sits on a conditional whose
      // two arms are the point, and a render can only ever show one of them.
      const root = read('../App.jsx').match(/className=\{`app-safe \$\{isMapTabActive[\s\S]{0,160}?`\}/);

      expect(root, 'the App root must carry app-safe ahead of its conditional arms').not.toBeNull();
      expect(root[0]).toMatch(/h-\[100dvh\]/);
      expect(root[0]).toMatch(/min-h-screen/);
    });

    it('MapOverlay resolves its own gutter against the insets, being inline-styled', () => {
      // The fifth full-viewport `fixed; inset: 0` layer, and the one no stylesheet rule can reach.
      // Its 24px gutter is smaller than every landscape side inset, so without this its panel sat
      // inside the sensor housing. Missed by the first cut of the audit.
      const src = read('../components/MapOverlay.jsx');

      expect(src).toMatch(/padding: 'max\(24px, var\(--safe-t\)\)/);
      expect(src).toMatch(/max\(24px, var\(--safe-b\)\)/);
    });
  });
});
