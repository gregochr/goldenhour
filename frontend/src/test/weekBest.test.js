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
    });
  });

  it('words a sunset as sunset', () => {
    expect(weekBestOf([card({ ...BEST, targetType: 'SUNSET', pickRating: 1 })], null).event).toBe('sunset');
  });

  it('when two cards carry the Best flag (a stale cache), the EARLIEST wins: the cards are chronological and nothing ranks', () => {
    const later = card({ ...BEST, date: '2026-11-29', pickLocationName: 'Later Bay', pickRating: 5 });
    const earlier = card({ ...BEST, date: '2026-11-26', dow: 'Thu', pickLocationName: 'Earlier Bay', pickRating: 2 });

    expect(weekBestOf([earlier, later], null).place).toBe('Earlier Bay');
    expect(weekBestOf([later, earlier], null).place).toBe('Later Bay');
  });

  it('prints no stars when the pick place has no served slot rating', () => {
    const none = weekBestOf([card({ ...BEST, pickRating: null })], null);

    expect(none.rating).toBeNull();
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
