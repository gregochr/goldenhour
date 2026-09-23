import { describe, it, expect, vi } from 'vitest';
import { PHONE_OPENING_QUERY, resolveInitialTab } from '../utils/initialTab.js';

/**
 * The device's opening tab (default-tab-by-device-plan.md §4.1) — a pure viewport read, decided
 * once per mount and never re-evaluated on resize or rotation. What breaks if these fail: a wrong
 * answer here either strands a phone reader on the map's information-dense first paint, or leaves
 * a desktop/iPad reader on Plan every time forever, with no way for either to notice why.
 */
describe('resolveInitialTab', () => {
  // A fake `win` records the exact query it was asked, rather than a plain boolean stub — so a
  // later edit to the landscape arm (or an accidental narrowing back to the old mobile-only query)
  // is a deliberate test change, not a silent drift nothing here would catch.
  const fakeWindow = (matches) => {
    const calls = [];
    return {
      win: {
        matchMedia: (query) => {
          calls.push(query);
          return { matches };
        },
      },
      calls,
    };
  };

  it('opens on plan when the phone query matches (portrait or landscape phone)', () => {
    const { win, calls } = fakeWindow(true);
    expect(resolveInitialTab(win)).toBe('plan');
    expect(calls).toEqual([PHONE_OPENING_QUERY]);
  });

  it('opens on map when the phone query does not match (tablet or desktop)', () => {
    const { win, calls } = fakeWindow(false);
    expect(resolveInitialTab(win)).toBe('map');
    expect(calls).toEqual([PHONE_OPENING_QUERY]);
  });

  it('opens on plan when the window has no matchMedia at all (SSR, very old engines)', () => {
    expect(resolveInitialTab({})).toBe('plan');
  });

  it('opens on plan when the window itself is missing', () => {
    expect(resolveInitialTab(null)).toBe('plan');
  });

  it('asserts the exact query string, so a later edit to the landscape arm is deliberate', () => {
    const spy = vi.fn(() => ({ matches: false }));
    resolveInitialTab({ matchMedia: spy });
    expect(spy).toHaveBeenCalledWith('(max-width: 639px), (pointer: coarse) and (max-height: 499px)');
  });
});
