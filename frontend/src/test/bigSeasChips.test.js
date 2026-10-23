import { describe, it, expect } from 'vitest';
import {
  BIG_SEAS_CHIP_MAX_ZOOM, bigSeasChipAriaLabel, bigSeasChipItems, bigSeasChipTipLines,
  dropProtectedBigSeasChips, labelTierOrder, placeLabelPass, withoutBigSeasDuplicates,
} from '../utils/mapLabels.js';
import {
  MONDAY_NOT_ALIGNED, SATURDAY, SUNDAY, astroRow, solarRow,
} from './bigSeasFixtures.js';

/**
 * The big-seas chips' pure logic (big-seas plan F2), imported through `utils/mapLabels.js`, the
 * module the label pass's vocabulary is exposed from (the implementation is the leaf
 * `utils/bigSeasChips.js`, so `MapView` can read the chip list without reaching `d3-geo`).
 *
 * Fixed dates: Saturday 24 Oct 2026 is still BST (14:30Z reads 15:30), Sunday 25 Oct is GMT (the
 * clocks go back at 01:00Z), so Sunday's 06:49Z reads 06:49 and its high water 07:31.
 */

// The FULL roster, ids 243/28/21: the chips join to this, never to the label pool.
const ROSTER = new Map([
  [243, {
    name: 'Roker Pier & Lighthouse', lat: 54.92, lon: -1.36, regionName: 'North East', driveMinutes: 45,
  }],
  [28, {
    name: "St. Mary's Lighthouse", lat: 55.07, lon: -1.44, regionName: 'North East', driveMinutes: null,
  }],
  [21, {
    name: 'Amble', lat: 55.33, lon: -1.58, regionName: 'North East', driveMinutes: 80,
  }],
]);
const SUNSET = solarRow(SATURDAY.date, 'SUNSET');
const SUNRISE = solarRow(SATURDAY.date, 'SUNRISE');

describe('bigSeasChipItems', () => {
  it('joins each served structure to the roster BY ID: the location name and coordinates, the served label and height', () => {
    const items = bigSeasChipItems(SATURDAY, ROSTER, 9, SUNSET);
    expect(items.map((i) => [i.locationId, i.name, i.label, i.lat, i.lng, i.heightM])).toEqual([
      [243, 'Roker Pier & Lighthouse', 'Roker Pier', 54.92, -1.36, 4.3],
      [28, "St. Mary's Lighthouse", "St Mary's", 55.07, -1.44, 4.1],
      [21, 'Amble', 'Amble piers', 55.33, -1.58, 3.9],
    ]);
    expect(items.map((i) => i.key)).toEqual(['bs:243', 'bs:28', 'bs:21']);
  });

  it('a stood-down or below-floor place still gets a chip: the join reads the roster, never the label pool', () => {
    // The pool `labelSpots` is built from has dropped Roker (stood down) and Amble (under the floor);
    // the roster still holds them, and the chip list is a function of the roster alone.
    const pool = [{ name: "St. Mary's Lighthouse", rating: 4 }];
    const items = bigSeasChipItems(SATURDAY, ROSTER, 9, SUNSET);
    expect(pool.map((p) => p.name)).not.toContain('Roker Pier & Lighthouse');
    expect(items.map((i) => i.locationId)).toEqual([243, 28, 21]);
  });

  it('skips a structure the roster does not hold, or holds without finite coordinates', () => {
    const partial = new Map([
      [243, ROSTER.get(243)],
      [28, { ...ROSTER.get(28), lat: Number.NaN }],
    ]);
    expect(bigSeasChipItems(SATURDAY, partial, 9, SUNSET).map((i) => i.locationId)).toEqual([243]);
    expect(bigSeasChipItems(SATURDAY, new Map(), 9, SUNSET)).toHaveLength(0);
  });

  it('shows below zoom 13 and not from 13 up', () => {
    expect(BIG_SEAS_CHIP_MAX_ZOOM).toBe(13);
    expect(bigSeasChipItems(SATURDAY, ROSTER, 12.99, SUNSET)).toHaveLength(3);
    expect(bigSeasChipItems(SATURDAY, ROSTER, 13, SUNSET)).toHaveLength(0);
    expect(bigSeasChipItems(SATURDAY, ROSTER, 15, SUNSET)).toHaveLength(0);
  });

  it('produces none on a night row, a past row, or without a full day', () => {
    expect(bigSeasChipItems(SATURDAY, ROSTER, 9, astroRow(SATURDAY.date))).toHaveLength(0);
    expect(bigSeasChipItems(SATURDAY, ROSTER, 9, { kind: 'aurora', eventType: 'AURORA', date: SATURDAY.date }))
      .toHaveLength(0);
    expect(bigSeasChipItems(SATURDAY, ROSTER, 9, { ...SUNSET, past: true })).toHaveLength(0);
    expect(bigSeasChipItems(null, ROSTER, 9, SUNSET)).toHaveLength(0);
    expect(bigSeasChipItems(SATURDAY, ROSTER, 9, null)).toHaveLength(0);
  });

  it('rings a chip only when the row on screen is the day\'s aligned event', () => {
    expect(bigSeasChipItems(SATURDAY, ROSTER, 9, SUNSET).map((i) => i.ringed)).toEqual([true, true, true]);
    expect(bigSeasChipItems(SATURDAY, ROSTER, 9, SUNRISE).map((i) => i.ringed)).toEqual([false, false, false]);
    // Sunday is aligned with the SUNRISE: the other way round.
    expect(bigSeasChipItems(SUNDAY, ROSTER, 9, solarRow(SUNDAY.date, 'SUNRISE'))[0].ringed).toBe(true);
    expect(bigSeasChipItems(SUNDAY, ROSTER, 9, solarRow(SUNDAY.date, 'SUNSET'))[0].ringed).toBe(false);
  });

  it('never rings a day with no aligned event', () => {
    for (const type of ['SUNRISE', 'SUNSET']) {
      const items = bigSeasChipItems(MONDAY_NOT_ALIGNED, ROSTER, 9, solarRow(MONDAY_NOT_ALIGNED.date, type));
      expect(items).toHaveLength(3);
      expect(items.every((i) => i.ringed === false)).toBe(true);
    }
  });
});

describe('the big-seas chip words', () => {
  const aligned = bigSeasChipItems(SATURDAY, ROSTER, 9, SUNSET);
  const unaligned = bigSeasChipItems(SATURDAY, ROSTER, 9, SUNRISE);

  it('the accessible name carries the name, the word, the height, the window and (ringed) the event', () => {
    expect(bigSeasChipAriaLabel(aligned[0]))
      .toBe('Roker Pier, big seas, waves 4.3 metres, 15:30 to 18:15, high water on the sunset');
    expect(bigSeasChipAriaLabel(unaligned[0])).toBe('Roker Pier, big seas, waves 4.3 metres, 15:30 to 18:15');
    const sunday = bigSeasChipItems(SUNDAY, ROSTER, 9, solarRow(SUNDAY.date, 'SUNRISE'))[0];
    expect(bigSeasChipAriaLabel(sunday))
      .toBe('Roker Pier, big seas, waves 3.4 metres, 06:49 to 09:00, high water on the sunrise');
  });

  it('the tooltip lines are the handoff\'s, in order', () => {
    expect(bigSeasChipTipLines(aligned[0])).toEqual([
      'Roker Pier & Lighthouse',
      'Big seas · 15:30–18:15',
      'High water 16:58 · 49 min before sunset',
      'Waves 4.3 m from NNE, 11 s · faces NE, onshore',
      'North East · 45 min',
    ]);
  });

  it('prints the gap on the AFTER side, and a fractional period', () => {
    const sunday = bigSeasChipItems(SUNDAY, ROSTER, 9, solarRow(SUNDAY.date, 'SUNRISE'))[0];
    expect(bigSeasChipTipLines(sunday).slice(1)).toEqual([
      'Big seas · 06:49–09:00',
      'High water 07:31 · 42 min after sunrise',
      'Waves 3.4 m from NE, 9 s · faces NE, onshore',
      'North East · 45 min',
    ]);
    const monday = bigSeasChipItems(MONDAY_NOT_ALIGNED, ROSTER, 9, solarRow(MONDAY_NOT_ALIGNED.date, 'SUNSET'));
    expect(bigSeasChipTipLines(monday[0])).toContain('Waves 4.3 m from NNE, 11 s · faces NE, onshore');
    const fractional = { ...monday[0], structure: { ...monday[0].structure, wavePeriodS: 8.5 } };
    expect(bigSeasChipTipLines(fractional)).toContain('Waves 4.3 m from NNE, 8.5 s · faces NE, onshore');
  });

  it('leaves the high-water line out when not ringed, and the drive when it is unmeasured', () => {
    expect(bigSeasChipTipLines(unaligned[0]).some((l) => l.startsWith('High water'))).toBe(false);
    // St Mary's has no measured drive: the last line is the region alone.
    expect(bigSeasChipTipLines(unaligned[1]).pop()).toBe('North East');
  });

  it('omits the last line entirely when there is neither a region nor a drive', () => {
    const bare = { ...unaligned[0], regionName: '', driveMinutes: null };
    expect(bigSeasChipTipLines(bare).pop()).toBe('Waves 4.3 m from NNE, 11 s · faces NE, onshore');
  });
});

describe('suppressing the ordinary chip', () => {
  const items = bigSeasChipItems(SATURDAY, new Map([
    [243, { name: 'Roker', lat: 54.9, lon: -1.3 }],
    [28, { name: 'St Marys', lat: 55.0, lon: -1.4 }],
    [21, { name: 'Amble', lat: 55.3, lon: -1.5 }],
  ]), 9, SUNSET);
  const pool = [{ name: 'Roker' }, { name: 'St Marys' }, { name: 'Amble' }, { name: 'Bamburgh' }];

  it('drops the ordinary chip of every place that has a big-seas chip, and only those', () => {
    expect(withoutBigSeasDuplicates(pool, items).map((s) => s.name)).toEqual(['Bamburgh']);
    expect(withoutBigSeasDuplicates(pool, [])).toBe(pool);
  });

  it('never withholds the selected place\'s or an Ask pick\'s ordinary chip: the big-seas chip goes instead', () => {
    const kept = dropProtectedBigSeasChips(items, { selectedName: 'Roker', askNames: new Set(['Amble']) });
    expect(kept.map((i) => i.name)).toEqual(['St Marys']);
    // And so the protected places keep their ordinary chip through the suppression pass.
    expect(withoutBigSeasDuplicates(pool, kept).map((s) => s.name)).toEqual(['Roker', 'Amble', 'Bamburgh']);
  });

  it('changes nothing when nothing is protected', () => {
    expect(dropProtectedBigSeasChips(items, { selectedName: null, askNames: new Set() })).toBe(items);
    expect(dropProtectedBigSeasChips(items)).toBe(items);
  });
});

describe('the collision tiers', () => {
  const at = (key) => ({
    key, x: 50, y: 10, w: 90, h: 16,
  });

  it('orders home, rings, Ask picks, regions, big-seas chips, then ordinary chips', () => {
    const ordered = labelTierOrder({
      chips: [at('chip:a')],
      bigSeas: [at('bs:1')],
      regions: [at('region:r')],
      picks: [at('chip:pick')],
      rings: [at('ring:25')],
      home: [at('home')],
    });
    expect(ordered.map((i) => i.key)).toEqual(['home', 'ring:25', 'chip:pick', 'region:r', 'bs:1', 'chip:a']);
  });

  it('is the order the greedy pass honours: with room for one, the earlier tier keeps it', () => {
    // Everything anchored on one point in a frame with room for one box.
    const winner = (tiers) => [...placeLabelPass(labelTierOrder(tiers), 100, 20).keys()];
    expect(winner({ regions: [at('region:r')], bigSeas: [at('bs:1')], chips: [at('chip:a')] })).toEqual(['region:r']);
    expect(winner({ bigSeas: [at('bs:1')], chips: [at('chip:a')] })).toEqual(['bs:1']);
    expect(winner({ picks: [at('chip:pick')], regions: [at('region:r')] })).toEqual(['chip:pick']);
    expect(winner({ chips: [at('chip:a')] })).toEqual(['chip:a']);
  });
});
