import { describe, it, expect } from 'vitest';
import {
  LOCATION_SORT_ACCESSORS, attentionSummary, coordinatesText, lightWords, locationRowLabel, regionFromHash,
  tideTokensText, tideWords,
} from '../utils/locationAdmin.js';

// Edge pins for the Locations tab's view-model: absent inputs to the light and tide words, the row label's joining,
// coordinates with a missing half, which places the attention alert names, and the region link's anchors.

/** A location with sensible defaults, overridable. */
function loc(overrides = {}) {
  return { name: 'Place', enabled: true, consecutiveFailures: 0, disabledReason: null, locationType: ['LANDSCAPE'], ...overrides };
}

describe('lightWords', () => {
  it('reads a location with none stored (empty, null or not a list) as both sunrise and sunset', () => {
    expect(lightWords([])).toBe('Sunrise, Sunset');
    expect(lightWords(null)).toBe('Sunrise, Sunset');
    expect(lightWords(undefined)).toBe('Sunrise, Sunset');
  });

  it('lists only the light held, in the catalogue order', () => {
    expect(lightWords(['SUNSET'])).toBe('Sunset');
    expect(lightWords(['ALLDAY', 'SUNRISE'])).toBe('Sunrise, All day');
  });
});

describe('tideWords and tideTokensText', () => {
  it('read none (null, not a list or empty) as "Not tidal" and as no tokens', () => {
    expect(tideWords(null)).toBe('Not tidal');
    expect(tideWords('HIGH')).toBe('Not tidal');
    expect(tideWords([])).toBe('Not tidal');
    expect(tideTokensText(null)).toBe('');
    expect(tideTokensText('HIGH')).toBe('');
  });

  it('list only the tides held, in High, Mid, Low order', () => {
    expect(tideWords(['LOW', 'HIGH'])).toBe('High, Low');
    expect(tideTokensText(['LOW', 'MID'])).toBe('M L');
  });
});

describe('locationRowLabel', () => {
  it('drops the parts a location does not have instead of leaving gaps', () => {
    expect(locationRowLabel(loc({ name: 'Bamburgh' }))).toBe('Bamburgh, Landscape, Active');
  });

  it('includes the sub-region and the mist flag when they are there', () => {
    expect(locationRowLabel(loc({ name: 'Bamburgh', subRegionName: 'Coast', mistProne: true })))
      .toBe('Bamburgh, Coast, Landscape, mist-prone, Active');
  });

  it('does not call a location mist-prone unless the flag is exactly true', () => {
    expect(locationRowLabel(loc({ name: 'Bamburgh', mistProne: false }))).toBe('Bamburgh, Landscape, Active');
    expect(locationRowLabel(loc({ name: 'Bamburgh', mistProne: 'yes' }))).toBe('Bamburgh, Landscape, Active');
  });
});

describe('LOCATION_SORT_ACCESSORS', () => {
  it('sorts by Bortle class, null for none, zero kept', () => {
    expect(LOCATION_SORT_ACCESSORS.bortle(loc({ bortleClass: 3 }))).toBe(3);
    expect(LOCATION_SORT_ACCESSORS.bortle(loc({ bortleClass: 0 }))).toBe(0);
    expect(LOCATION_SORT_ACCESSORS.bortle(loc({ bortleClass: null }))).toBeNull();
    expect(LOCATION_SORT_ACCESSORS.bortle(loc())).toBeNull();
  });
});

describe('coordinatesText', () => {
  it('gives null when either coordinate is missing', () => {
    expect(coordinatesText(null, 5)).toBeNull();
    expect(coordinatesText(55, null)).toBeNull();
    expect(coordinatesText(undefined, 5)).toBeNull();
    expect(coordinatesText(55, undefined)).toBeNull();
    expect(coordinatesText(null, null)).toBeNull();
  });

  it('shows three decimals, zero included', () => {
    expect(coordinatesText(55.39, -1.6132)).toBe('55.390, -1.613');
    expect(coordinatesText(0, 0)).toBe('0.000, 0.000');
  });
});

describe('attentionSummary names the right places', () => {
  it('names an enabled failing place, never one switched off, even when the off one sorts first', () => {
    const result = attentionSummary([
      loc({ name: 'Aaa off after failures', enabled: false, disabledReason: 'failures', consecutiveFailures: 3 }),
      loc({ name: 'Bbb off by hand', enabled: false, consecutiveFailures: 2 }),
      loc({ name: 'Ccc failing', consecutiveFailures: 1 }),
      loc({ name: 'Ddd healthy' }),
    ]);
    expect(result.count).toBe(3);
    expect(result.failing).toEqual({ name: 'Ccc failing', text: 'has failed 1 run in a row.' });
    expect(result.turnedOff).toEqual({
      name: 'Aaa off after failures', text: 'was turned off after 3 failed runs.',
    });
  });

  it('names no failing place when only switched-off places need attention', () => {
    const result = attentionSummary([
      loc({ name: 'Aaa', enabled: false, disabledReason: 'failures', consecutiveFailures: 0 }),
      loc({ name: 'Bbb', enabled: false, consecutiveFailures: 2 }),
    ]);
    expect(result.failing).toBeNull();
    expect(result.turnedOff).toEqual({ name: 'Aaa', text: 'was turned off after failed runs.' });
  });

  it('names nothing in either sentence for a place off by hand that still carries failures', () => {
    const result = attentionSummary([loc({ name: 'Bbb', enabled: false, consecutiveFailures: 2 })]);
    expect(result).toMatchObject({ count: 1, headline: '1 location needs attention.', failing: null, turnedOff: null });
  });

  it('picks the alphabetically first by base letters, so case does not reorder two equal names', () => {
    const result = attentionSummary([
      loc({ name: 'Alpha', consecutiveFailures: 2 }),
      loc({ name: 'alpha', consecutiveFailures: 5 }),
    ]);
    expect(result.failing.name).toBe('Alpha');
    expect(result.failing.text).toBe('has failed 2 runs in a row.');
  });

  it('ignores accents too when ordering names', () => {
    const result = attentionSummary([
      loc({ name: 'Ézé', consecutiveFailures: 1 }),
      loc({ name: 'Eze', consecutiveFailures: 1 }),
    ]);
    expect(result.failing.name).toBe('Ézé');
  });
});

describe('regionFromHash', () => {
  it('reads the region of a Locations link', () => {
    expect(regionFromHash('#manage/locations?region=3')).toBe('3');
    expect(regionFromHash('#manage/locations?region=12')).toBe('12');
  });

  it.each([
    ['a non-numeric region', '#manage/locations?region=abc'],
    ['digits with a prefix', '#manage/locations?region=x5'],
    ['digits with a suffix', '#manage/locations?region=5x'],
    ['an empty region', '#manage/locations?region='],
    ['no region key', '#manage/locations?other=3'],
    ['another tab carrying a region', '#manage/runs?region=3'],
    ['another area carrying a region', '#plan/locations?region=3'],
    ['no query', '#manage/locations'],
  ])('is null for %s', (_why, hash) => {
    expect(regionFromHash(hash)).toBeNull();
  });
});
