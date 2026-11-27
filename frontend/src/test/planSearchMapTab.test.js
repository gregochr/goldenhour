import { describe, it, expect } from 'vitest';
import { buildSearchGroups } from '../utils/planSearch.js';
import { findKeyHint, isFindKey, isTypingTarget } from '../utils/findKey.js';

/**
 * Find on the Map (find-and-favourites-plan.md F1): the rows are worded for what the map will do, and a region
 * is never refused for a reason that only an origin move has.
 */
const REGIONS = [
  {
    id: 1, name: 'Northumberland', baseName: 'Alnwick', baseLat: 55.4, baseLon: -1.7,
  },
  {
    id: 2, name: 'Lake District', baseName: null, baseLat: null, baseLon: null,
  },
  {
    id: 3, name: 'Peak District', baseName: 'Buxton', baseLat: 53.2, baseLon: -1.9, enabled: false,
  },
];
const SPOTS = [{ id: 10, name: 'Bamburgh Beach', regionName: 'Northumberland' }];
const WINDOWS = [{
  key: '2026-10-05:SUNSET', date: '2026-10-05', targetType: 'SUNSET', label: 'Tonight Sunset', dow: 'Mon', time: '17:41',
}];

const rows = (query, extra = {}) => buildSearchGroups(query, {
  regions: REGIONS, locations: SPOTS, windows: WINDOWS, ...extra,
}).flatMap((group) => group.rows);

describe('buildSearchGroups — context: map', () => {
  it('words a window as "Show", where Plan says "Open"', () => {
    expect(rows('', { context: 'map' })[0].action).toBe('Show');
    expect(rows('', { context: 'screen' })[0].action).toBe('Open');
    expect(rows('')[0].action).toBe('Open');
  });

  it('words a place as "Show on map", where Plan says "Next few days"', () => {
    expect(rows('bamburgh', { context: 'map' })[0].action).toBe('Show on map');
    expect(rows('bamburgh', { context: 'screen' })[0].action).toBe('Next few days');
  });

  it('⚠️ a region is a frame: pickable with no base town, and while it is the origin', () => {
    const [lake] = rows('lake', { context: 'map' });
    expect(lake).toMatchObject({
      kind: 'region', disabled: false, reason: null, action: 'Show', sub: 'Frame on the map',
    });
    const [north] = rows('northumberland', { context: 'map', originId: 1 });
    expect(north).toMatchObject({ disabled: false, action: 'Show' });
  });

  it('a region switched off has nothing to frame, so it is shown with its reason and not pickable', () => {
    const [peak] = rows('peak', { context: 'map' });
    expect(peak).toMatchObject({
      disabled: true, action: null, reason: 'This region is switched off', sub: 'Switched off',
    });
  });

  it('keeps Plan\'s own origin-based refusals exactly where they were', () => {
    const [lake] = rows('lake', { context: 'screen' });
    expect(lake).toMatchObject({ disabled: true, reason: 'This region has no base town to plan from' });
    const [north] = rows('northumberland', { context: 'screen', originId: 1 });
    expect(north).toMatchObject({ disabled: true, reason: "You're already planning from here" });
  });

  it('titles the region group for where Find is open', () => {
    const titleOf = (context) => buildSearchGroups('lake', { regions: REGIONS, context })
      .find((group) => group.id === 'regions').title;
    expect(titleOf('map')).toBe('Regions');
    expect(titleOf('screen')).toBe('Plan from here');
  });
});

describe('findKey', () => {
  const press = (init) => ({ key: 'k', ...init });

  it('takes K with Meta or Ctrl, in either case, and nothing else', () => {
    expect(isFindKey(press({ metaKey: true }))).toBe(true);
    expect(isFindKey(press({ ctrlKey: true }))).toBe(true);
    expect(isFindKey(press({ metaKey: true, key: 'K' }))).toBe(true);
    expect(isFindKey(press({}))).toBe(false);
    expect(isFindKey(press({ metaKey: true, shiftKey: true }))).toBe(false);
    expect(isFindKey(press({ ctrlKey: true, altKey: true }))).toBe(false);
    expect(isFindKey({ key: 'j', metaKey: true })).toBe(false);
    expect(isFindKey({ metaKey: true })).toBe(false);
    expect(isFindKey(null)).toBe(false);
  });

  it('reads a typing target: a field, a select, or anything editable', () => {
    expect(isTypingTarget({ tagName: 'INPUT' })).toBe(true);
    expect(isTypingTarget({ tagName: 'TEXTAREA' })).toBe(true);
    expect(isTypingTarget({ tagName: 'SELECT' })).toBe(true);
    expect(isTypingTarget({ tagName: 'DIV', isContentEditable: true })).toBe(true);
    expect(isTypingTarget({ tagName: 'BUTTON' })).toBe(false);
    expect(isTypingTarget(null)).toBe(false);
  });

  it('draws the Apple cap on an Apple platform and Ctrl K elsewhere', () => {
    const original = Object.getOwnPropertyDescriptor(window.navigator, 'platform');
    const set = (value) => Object.defineProperty(window.navigator, 'platform', { value, configurable: true });
    try {
      set('MacIntel');
      expect(findKeyHint()).toBe('⌘K');
      set('iPhone');
      expect(findKeyHint()).toBe('⌘K');
      set('Win32');
      expect(findKeyHint()).toBe('Ctrl K');
    } finally {
      if (original) Object.defineProperty(window.navigator, 'platform', original);
    }
  });
});
