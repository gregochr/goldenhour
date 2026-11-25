import { describe, it, expect } from 'vitest';
import { weekBestOf } from '../utils/weekBest.js';

/** A strip descriptor with the fields the week's best reads. */
function card(over = {}) {
  return {
    date: '2026-11-27',
    targetType: 'SUNRISE',
    dow: 'Fri',
    away: false,
    pickKind: null,
    pickRegion: null,
    pickLocationId: null,
    pickLocationName: null,
    pickRating: null,
    ...over,
  };
}

const BEST = card({
  pickKind: 'best', pickRegion: 'North East', pickLocationId: 12, pickLocationName: 'Crimdon Beach', pickRating: 4,
});

describe('weekBestOf (screen-spec.md D3)', () => {
  it('names the card whose served pick is the Best bet, by the flag and never by rating', () => {
    const cards = [
      card({ date: '2026-11-25', pickKind: 'also', pickLocationName: 'Elsewhere', pickRating: 5 }),
      BEST,
      card({ date: '2026-11-28', targetType: 'SUNSET', dow: 'Sat' }),
    ];

    expect(weekBestOf(cards, null)).toEqual({
      date: '2026-11-27',
      eventType: 'SUNRISE',
      locationId: 12,
      place: 'Crimdon Beach',
      rating: 4,
      dow: 'Fri',
      event: 'sunrise',
      spoken: 'Best this week: Friday sunrise, Crimdon Beach, 4 stars. Open on the map.',
    });
  });

  it('speaks a single star in the singular and a sunset as sunset', () => {
    const one = weekBestOf([card({ ...BEST, targetType: 'SUNSET', pickRating: 1 })], null);

    expect(one.event).toBe('sunset');
    expect(one.spoken).toBe('Best this week: Friday sunset, Crimdon Beach, 1 star. Open on the map.');
  });

  it('prints no stars when the pick place has no served slot rating', () => {
    const none = weekBestOf([card({ ...BEST, pickRating: null })], null);

    expect(none.rating).toBeNull();
    expect(none.spoken).toBe('Best this week: Friday sunrise, Crimdon Beach. Open on the map.');
  });

  it('falls back to the region when the pick names no place', () => {
    const region = weekBestOf([card({ ...BEST, pickLocationName: null, pickLocationId: null, pickRating: null })], null);

    expect(region.place).toBe('North East');
    expect(region.locationId).toBeNull();
  });

  it('has no best with no place and no region to name', () => {
    expect(weekBestOf([card({ pickKind: 'best' })], null)).toBeNull();
  });

  it('has no best when no card carries the Best flag (Lite, an away-scoped origin, none served)', () => {
    expect(weekBestOf([card(), card({ pickKind: 'also', pickLocationName: 'X' })], null)).toBeNull();
    expect(weekBestOf([], null)).toBeNull();
    expect(weekBestOf(undefined, null)).toBeNull();
  });

  it('ignores a best on an away card and one outside the days the screen shows', () => {
    expect(weekBestOf([card({ ...BEST, away: true })], null)).toBeNull();
    expect(weekBestOf([BEST], ['2026-11-25', '2026-11-26'])).toBeNull();
    expect(weekBestOf([BEST], ['2026-11-27'])).not.toBeNull();
  });

  it('ignores a window that is neither sunrise nor sunset', () => {
    expect(weekBestOf([card({ ...BEST, targetType: 'AURORA' })], null)).toBeNull();
  });
});
