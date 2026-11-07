import { describe, it, expect } from 'vitest';
import {
  bigSeasChipItems,
  bigSeasChipTipLines,
  dropProtectedBigSeasChips,
  selectedBigSeasFirst,
  withFallbackChips,
  NO_BIG_SEAS_CHIPS,
} from '../utils/bigSeasChips.js';

function makeDay(over = {}) {
  return {
    date: '2026-10-24',
    windowStart: '2026-10-24T14:30:00',
    windowEnd: '2026-10-24T17:15:00',
    hwTime: '2026-10-24T15:58:00',
    alignedEvent: 'SUNSET',
    gapMinutes: 49,
    gapSide: 'BEFORE',
    structures: [
      {
        locationId: 1, label: 'Roker Pier', waveHeightM: 4.3, waveDirectionDeg: 22.5, wavePeriodS: 11,
        facesDeg: 45, hwTime: '2026-10-24T15:58:00', aligned: true,
      },
    ],
    ...over,
  };
}

function makeRoster() {
  return new Map([[1, { name: 'Roker', lat: 54.9, lon: -1.35, regionName: 'North East', driveMinutes: 45 }]]);
}

const SOLAR_SUNSET = { kind: 'solar', eventType: 'SUNSET', past: false };

describe('bigSeasChipItems edges', () => {
  it('returns the shared empty list for a day with no structures key', () => {
    const day = makeDay();
    delete day.structures;
    expect(bigSeasChipItems(day, makeRoster(), 8, SOLAR_SUNSET)).toBe(NO_BIG_SEAS_CHIPS);
  });

  it('returns the shared empty list when no structure can be placed', () => {
    const day = makeDay({ structures: [{ locationId: 99, label: 'Nowhere', waveHeightM: 3 }] });
    expect(bigSeasChipItems(day, makeRoster(), 8, SOLAR_SUNSET)).toBe(NO_BIG_SEAS_CHIPS);
  });

  it('copes with a missing roster', () => {
    expect(bigSeasChipItems(makeDay(), null, 8, SOLAR_SUNSET)).toBe(NO_BIG_SEAS_CHIPS);
    expect(bigSeasChipItems(makeDay(), undefined, 8, SOLAR_SUNSET)).toBe(NO_BIG_SEAS_CHIPS);
  });

  it('rings a structure only when it is the aligned one on the aligned event', () => {
    const day = makeDay();
    day.structures[0].aligned = false;
    const items = bigSeasChipItems(day, makeRoster(), 8, SOLAR_SUNSET);
    expect(items).toHaveLength(1);
    expect(items[0].ringed).toBe(false);
    delete day.structures[0].aligned;
    expect(bigSeasChipItems(day, makeRoster(), 8, SOLAR_SUNSET)[0].ringed).toBe(false);
    day.structures[0].aligned = true;
    expect(bigSeasChipItems(day, makeRoster(), 8, SOLAR_SUNSET)[0].ringed).toBe(true);
  });
});

describe('dropProtectedBigSeasChips edges', () => {
  it('returns the same array when nothing is protected', () => {
    const items = [{ name: 'A' }, { name: 'B' }];
    expect(dropProtectedBigSeasChips(items, { askNames: new Set(['Z']) })).toBe(items);
    expect(dropProtectedBigSeasChips(items)).toBe(items);
  });

  it('drops the protected names and keeps the rest in order', () => {
    const items = [{ name: 'A' }, { name: 'B' }, { name: 'C' }];
    expect(dropProtectedBigSeasChips(items, { askNames: new Set(['B']) })).toEqual([{ name: 'A' }, { name: 'C' }]);
  });
});

describe('selectedBigSeasFirst edges', () => {
  it('returns the very same array when nothing is selected', () => {
    const items = [{ name: 'A' }, { name: 'B' }];
    expect(selectedBigSeasFirst(items, null)).toBe(items);
    expect(selectedBigSeasFirst(items, undefined)).toBe(items);
  });

  it('returns the very same array for fewer than two items', () => {
    const one = [{ name: 'A' }];
    expect(selectedBigSeasFirst(one, 'A')).toBe(one);
    const none = [];
    expect(selectedBigSeasFirst(none, 'A')).toBe(none);
  });

  it('moves a selected last item to the front of two, leaving the input untouched', () => {
    const items = [{ name: 'A' }, { name: 'B' }];
    const sorted = selectedBigSeasFirst(items, 'B');
    expect(sorted.map((i) => i.name)).toEqual(['B', 'A']);
    expect(items.map((i) => i.name)).toEqual(['A', 'B']);
  });

  it('moves a selected last item to the front of three, keeping the others in order', () => {
    const items = [{ name: 'A' }, { name: 'B' }, { name: 'C' }];
    expect(selectedBigSeasFirst(items, 'C').map((i) => i.name)).toEqual(['C', 'A', 'B']);
    expect(selectedBigSeasFirst(items, 'B').map((i) => i.name)).toEqual(['B', 'A', 'C']);
    expect(selectedBigSeasFirst(items, 'A').map((i) => i.name)).toEqual(['A', 'B', 'C']);
  });
});

describe('withFallbackChips edges', () => {
  it('puts the fallbacks first when the selected key is not the first chip', () => {
    const chips = [{ key: 'b' }, { key: 'c' }];
    const fb = [{ key: 'f' }];
    expect(withFallbackChips(chips, fb, 'a').map((c) => c.key)).toEqual(['f', 'b', 'c']);
  });

  it('puts the fallbacks after the selected first chip', () => {
    const chips = [{ key: 'a' }, { key: 'c' }];
    const fb = [{ key: 'f' }];
    expect(withFallbackChips(chips, fb, 'a').map((c) => c.key)).toEqual(['a', 'f', 'c']);
  });

  it('puts the fallbacks first when there is no selection, even if a chip has no key', () => {
    const fb = [{ key: 'f' }];
    expect(withFallbackChips([{}, { key: 'c' }], fb, undefined)).toEqual([{ key: 'f' }, {}, { key: 'c' }]);
    expect(withFallbackChips([{ key: null }], fb, null)).toEqual([{ key: 'f' }, { key: null }]);
  });

  it('copes with an empty chip tier and a selected key', () => {
    const fb = [{ key: 'f' }];
    expect(withFallbackChips([], fb, 'a')).toEqual(fb);
  });

  it('returns the chips themselves when there are no fallbacks', () => {
    const chips = [{ key: 'a' }];
    expect(withFallbackChips(chips, [], 'a')).toBe(chips);
  });
});

describe('bigSeasChipTipLines edges', () => {
  function chipFor(dayOver, ringed = true) {
    const day = makeDay(dayOver);
    const base = bigSeasChipItems(day, makeRoster(), 8, SOLAR_SUNSET)[0];
    return { ...base, ringed };
  }

  it('prints the gap clause on the anchor structure', () => {
    expect(bigSeasChipTipLines(chipFor({}))).toEqual([
      'Roker',
      'Big seas · 15:30–18:15',
      'High water 16:58 · 49 min before sunset',
      'Waves 4.3 m from NNE, 11 s · faces NE, onshore',
      'North East · 45 min',
    ]);
  });

  it('prints no gap clause when the served gap minutes are missing', () => {
    expect(bigSeasChipTipLines(chipFor({ gapMinutes: null }))[2]).toBe('High water 16:58');
    expect(bigSeasChipTipLines(chipFor({ gapMinutes: undefined }))[2]).toBe('High water 16:58');
  });

  it('prints the after side and no where line without region or drive time', () => {
    const roster = new Map([[1, { name: 'Roker', lat: 54.9, lon: -1.35 }]]);
    const day = makeDay({ gapSide: 'AFTER' });
    const chip = bigSeasChipItems(day, roster, 8, SOLAR_SUNSET)[0];
    const lines = bigSeasChipTipLines(chip);
    expect(lines[2]).toBe('High water 16:58 · 49 min after sunset');
    expect(lines).toHaveLength(4);
  });

  it('prints only the drive time when the region is unknown', () => {
    const roster = new Map([[1, { name: 'Roker', lat: 54.9, lon: -1.35, driveMinutes: 90 }]]);
    const chip = bigSeasChipItems(makeDay(), roster, 8, SOLAR_SUNSET)[0];
    expect(bigSeasChipTipLines(chip).at(-1)).toBe('1h 30min');
  });
});
