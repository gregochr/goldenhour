import { describe, it, expect, afterEach } from 'vitest';
import { foreignModalOver, foreignModalOverPaneOf, MAP_PANE_SELECTOR } from '../utils/mapForeignModal.js';

/**
 * `utils/mapForeignModal.js` — the one predicate the Map tab's four Escape rules consult.
 *
 * <p>⚠️ **This file exists because a mutation survived.** Pointing `MAP_PANE_SELECTOR` at a class
 * that matches nothing left all 225 tests across the two panels and `MapViewHeat` green: with the
 * selector broken, `closest` returns null, the null branch stands down whenever ANY modal is open,
 * and every panel-level test plants its modal OUTSIDE the pane — so both readings agree there.
 *
 * <p>That mutant is behaviourally equivalent *today*, because nothing inside the pane carries
 * `aria-modal` (`FiltersPopover`, `MapLandingCard` and both drilldown panels each say so in their
 * own docs). The containment rule is therefore defensive — and defensive code that no test
 * distinguishes is code that gets deleted as dead. Pinned here, at the level where the distinction
 * is real, rather than faked at panel level where it is not.
 */
describe('foreignModalOver — containment, not "is any modal open"', () => {
  let planted = [];
  afterEach(() => { planted.forEach((n) => n.remove()); planted = []; });

  const modalIn = (parent) => {
    const n = document.createElement('div');
    n.setAttribute('role', 'dialog');
    n.setAttribute('aria-modal', 'true');
    parent.appendChild(n);
    planted.push(n);
    return n;
  };
  // ⚠️ The class is HARDCODED, not derived from `MAP_PANE_SELECTOR`. The first cut built it with
  // `MAP_PANE_SELECTOR.replace('.', '')` and the wrong-selector mutant survived anyway — the
  // fixture moved with the constant, so the pair was true by construction for any value, the same
  // shape as the lunar epoch assertions this project has already been bitten by. The literal below
  // and the assertion under it are what make the mutant fail.
  const pane = () => {
    const n = document.createElement('div');
    n.className = 'wf-map-tab';
    document.body.appendChild(n);
    planted.push(n);
    return n;
  };

  it('⚠️ names the map TAB\'s own root — the node MapView\'s mapPaneRef points at', () => {
    // `MapView` renders `className={overlayMode ? … : '… wf-map-tab'}` on the element carrying
    // `ref={mapPaneRef}` and `onKeyDown={handleMapPaneKeyDown}`. If these two drift apart, every
    // consumer silently falls into the null branch and stands down for any modal anywhere.
    expect(MAP_PANE_SELECTOR).toBe('.wf-map-tab');
  });

  it('is false when nothing is open at all', () => {
    expect(foreignModalOver(pane())).toBe(false);
  });

  it('is true for a modal OUTSIDE the pane — the four-day sheet, settings, search', () => {
    const p = pane();
    modalIn(document.body);

    expect(foreignModalOver(p)).toBe(true);
  });

  it('⚠️ is FALSE for a modal the pane renders inline — that is its own business', () => {
    const p = pane();
    modalIn(p);

    expect(foreignModalOver(p)).toBe(false);
  });

  it('stands down when the pane root is unknown — not locating your container is not evidence', () => {
    modalIn(document.body);

    expect(foreignModalOver(null)).toBe(true);
  });

  describe('foreignModalOverPaneOf — asked from inside, with no prop to forget', () => {
    it('⚠️ resolves the real pane, so an inline modal still reads as not-foreign', () => {
      // The case that kills the wrong-selector mutant: a broken `MAP_PANE_SELECTOR` resolves to
      // null here and the null branch would return true.
      const p = pane();
      const child = document.createElement('div');
      p.appendChild(child);
      modalIn(p);

      expect(foreignModalOverPaneOf(child)).toBe(false);
    });

    it('still sees a modal outside that pane', () => {
      const p = pane();
      const child = document.createElement('div');
      p.appendChild(child);
      modalIn(document.body);

      expect(foreignModalOverPaneOf(child)).toBe(true);
    });

    it('stands down for a detached node rather than assuming it is clear', () => {
      modalIn(document.body);

      expect(foreignModalOverPaneOf(document.createElement('div'))).toBe(true);
      expect(foreignModalOverPaneOf(null)).toBe(true);
    });
  });
});
