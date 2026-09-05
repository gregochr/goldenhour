import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import BottomSheet from '../components/BottomSheet';
import Modal from '../components/shared/Modal';

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8');

/** Slices `index.css` from a rule's selector to the closing brace of its declaration block. */
const rule = (selector) => {
  const css = read('../index.css');
  const start = css.indexOf(`${selector} {`);
  expect(start, `${selector} must still be findable in index.css`).toBeGreaterThan(-1);
  const end = css.indexOf('}', start);
  expect(end, `${selector} must still have a closing brace`).toBeGreaterThan(start);
  return css.slice(start, end);
};

/**
 * Safe-area handling — the opt-in, the invariant that protects every other surface from it, and the
 * four elements that touch a viewport edge and so have to answer for the insets themselves.
 *
 * ⚠️ These are TEXT assertions against the stylesheet, not computed-style ones, and that is not
 * laziness. jsdom resolves neither `var()` nor `env()`, so `getComputedStyle` on any rule here
 * returns the empty string whether the declaration is right, wrong or absent — the same trap that
 * shipped a badge against an undeclared token (see `MastheadTickLine.test.jsx`). What the text CAN
 * prove is exactly what a reviewer would check by eye and what a refactor would silently undo.
 */
describe('safe areas', () => {
  describe('the opt-in', () => {
    it('index.html sets viewport-fit=cover, without which every rule below is inert', () => {
      // `env(safe-area-inset-*)` resolves to zero on every device until the document asks for the
      // full viewport. Delete this and the whole feature silently becomes a no-op — nothing else
      // in the suite would notice, which is why it is pinned here rather than assumed.
      const html = read('../../index.html');
      expect(html).toMatch(/<meta name="viewport"[^>]*viewport-fit=cover/);
    });
  });

  describe('⚠️ the zero-inset invariant', () => {
    it('every env(safe-area-*) in the stylesheet carries a 0px fallback', () => {
      // Two things at once. A bare `env(safe-area-inset-top)` is an INVALID declaration on a
      // browser that does not know the variable — the whole declaration is dropped, taking any
      // `top`/`padding` it was carrying with it — so the fallback is what keeps these rules from
      // being a downgrade elsewhere. And it is what makes each rule provably identical to the
      // geometry it replaced wherever no inset is reported, which is every surface this app runs
      // on today except a notched iOS device.
      const css = read('../index.css');
      const uses = [...css.matchAll(/env\(safe-area-inset-[a-z]+[^)]*\)/g)].map((m) => m[0]);

      expect(uses.length, 'the stylesheet must use env() somewhere, or this test proves nothing')
        .toBeGreaterThan(4);
      uses.forEach((use) => {
        expect(use, `${use} must fall back to 0px`).toMatch(/,\s*0px\)$/);
      });
    });

    it('the modal resolves its gutter and its inset with max(), never by summing them', () => {
      // Summing would move a dialog 16px further from an edge it was already clear of, on the
      // device class with the least room to give. `max(1rem, …)` is exactly the `p-4` this
      // replaced wherever the inset is smaller.
      const block = rule('.app-safe-modal');

      ['top', 'right', 'bottom', 'left'].forEach((side) => {
        expect(block).toMatch(
          new RegExp(`padding-${side}: max\\(1rem, env\\(safe-area-inset-${side}, 0px\\)\\)`),
        );
      });
      expect(block).not.toMatch(/calc\(/);
    });
  });

  describe('the four edge-anchored elements', () => {
    it('.app-safe insets all four sides for everything in normal flow', () => {
      const block = rule('.app-safe');

      ['top', 'right', 'bottom', 'left'].forEach((side) => {
        expect(block).toMatch(
          new RegExp(`padding-${side}: env\\(safe-area-inset-${side}, 0px\\)`),
        );
      });
    });

    it('⚠️ the sticky lens bar names the top inset itself, because the root cannot reach it', () => {
      // A sticky element sticks to its scrollport — the viewport, which `viewport-fit=cover` has
      // just extended under the status bar — not to any ancestor's padding box. `top: 0` here
      // would park the bar under the notch however much padding the root carries.
      const block = rule('.wf-lens');

      expect(block).toMatch(/position: sticky/);
      expect(block).toMatch(/top: env\(safe-area-inset-top, 0px\)/);
      expect(block, 'a bare `top: 0` is the regression this guards')
        .not.toMatch(/^\s*top: 0;/m);
    });

    it('⚠️ the sheet INSETS its sides and PADS its foot, which are not interchangeable', () => {
      // Its close button is `position: absolute; right: …`, whose containing block is this
      // element's padding box — so a `padding-right` leaves that button exactly where it was,
      // under a landscape sensor housing. Insetting the box moves the button with it. At the foot
      // the opposite holds: padding keeps the sheet's surface running to the bottom of the screen
      // while its content clears the home indicator.
      const block = rule('.app-safe-sheet');

      expect(block).toMatch(/left: env\(safe-area-inset-left, 0px\)/);
      expect(block).toMatch(/right: env\(safe-area-inset-right, 0px\)/);
      expect(block).toMatch(/padding-bottom: env\(safe-area-inset-bottom, 0px\)/);
      expect(block, 'padding on the sides would strand the close button')
        .not.toMatch(/padding-(left|right):/);
    });
  });

  describe('the classes reach the elements', () => {
    it('the bottom sheet carries app-safe-sheet and no longer pins its own left/right', () => {
      render(<BottomSheet open onClose={() => {}} label="Sheet"><p>Content</p></BottomSheet>);

      const sheet = screen.getByTestId('bottom-sheet');
      expect(sheet).toHaveClass('app-safe-sheet');
      expect(sheet).toHaveClass('fixed', 'bottom-0');
      // `left-0 right-0` would out-rank the class's own `left`/`right` by source order and undo it.
      expect(sheet.className).not.toMatch(/\bleft-0\b/);
      expect(sheet.className).not.toMatch(/\bright-0\b/);
    });

    it('the modal carries app-safe-modal in place of its p-4 gutter', () => {
      render(<Modal onClose={() => {}} label="Dialog" data-testid="d"><p>Content</p></Modal>);

      const dialog = screen.getByTestId('d');
      expect(dialog).toHaveClass('app-safe-modal');
      // Left in place, `p-4` would win on source order and the inset would never apply.
      expect(dialog.className).not.toMatch(/\bp-4\b/);
    });

    it('the app root carries app-safe on both of its layout arms', () => {
      // Rendering `App` needs the whole provider stack; the two class strings are asserted at
      // source instead, which is where the conditional actually lives.
      const app = read('../App.jsx');
      const root = app.match(/<div className=\{`app-safe \$\{isMapTabActive[^}]*\}`\}>/);

      expect(root, 'the App root must carry app-safe ahead of its conditional arms').not.toBeNull();
      expect(root[0]).toMatch(/h-\[100dvh\]/);
      expect(root[0]).toMatch(/min-h-screen/);
    });
  });
});
