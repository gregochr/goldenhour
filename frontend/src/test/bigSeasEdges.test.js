import { describe, it, expect } from 'vitest';
import { bigSeasFor, bigSeasCopy, bigSeasDayName } from '../utils/bigSeas.js';

const NOW = new Date('2026-10-22T09:00:00Z');

function makeDay(over = {}) {
  return {
    date: '2026-10-24',
    windowStart: '2026-10-24T14:30:00',
    windowEnd: '2026-10-24T17:15:00',
    hwTime: '2026-10-24T15:58:00',
    alignedEvent: 'SUNSET',
    gapMinutes: 49,
    gapSide: 'BEFORE',
    daylightHighWater: false,
    waveHeightM: 4.1,
    waveDirectionDeg: 22.5,
    structures: [{ label: 'Roker Pier', locationId: 1 }],
    ...over,
  };
}

function makeRow(over = {}) {
  return {
    id: 'solar:2026-10-24:SUNSET', kind: 'solar', eventType: 'SUNSET', date: '2026-10-24', past: false, ...over,
  };
}

describe('bigSeasFor edges', () => {
  it('skips a null entry in the served list instead of throwing', () => {
    const day = makeDay();
    const sel = bigSeasFor([null, day], makeRow(), [], NOW);
    expect(sel.day).toBe(day);
  });

  it('orders the served days by date whatever order they arrive in', () => {
    const d26 = makeDay({ date: '2026-10-26', windowEnd: '2026-10-26T17:15:00' });
    const d24 = makeDay({ date: '2026-10-24' });
    const d25 = makeDay({ date: '2026-10-25', windowEnd: '2026-10-25T17:15:00' });
    const row = makeRow({ date: '2026-10-23', id: 'solar:2026-10-23:SUNSET' });
    // The Map carries a solar row on each served day (a day with none is not named).
    const rows = ['2026-10-24', '2026-10-25', '2026-10-26']
      .map((date) => makeRow({ date, id: `solar:${date}:SUNSET` }));
    expect(bigSeasFor([d26, d24, d25], row, rows, NOW).day).toBe(d24);
    expect(bigSeasFor([d24, d25, d26], row, rows, NOW).day).toBe(d24);
    expect(bigSeasFor([d25, d26, d24], row, rows, NOW).day).toBe(d24);
    expect(bigSeasFor([d26, d25], row, rows, NOW).day).toBe(d25);
  });

  it('keeps served order between two days of the same date', () => {
    const first = makeDay({ gapMinutes: 1 });
    const second = makeDay({ gapMinutes: 2 });
    expect(bigSeasFor([first, second], makeRow(), [], NOW).day).toBe(first);
    expect(bigSeasFor([second, first], makeRow(), [], NOW).day).toBe(second);
  });

  it('is not aligned on a non-solar row even when its event type matches', () => {
    const day = makeDay();
    const hw = makeRow({ kind: 'hw', id: 'hw:2026-10-24' });
    const sel = bigSeasFor([day], hw, [], NOW);
    expect(sel.form).toBe('full');
    expect(sel.aligned).toBe(false);
  });

  it('copes with a missing events list', () => {
    const sel = bigSeasFor([makeDay()], makeRow({ eventType: 'SUNRISE' }), undefined, NOW);
    expect(sel.form).toBe('full');
    expect(sel.jumpRow).toBeNull();
  });

  it('jumps to an aligned row that sits first in the list', () => {
    const aligned = makeRow({ id: 'solar:2026-10-24:SUNSET' });
    const reader = makeRow({ id: 'solar:2026-10-24:SUNRISE', eventType: 'SUNRISE' });
    const sel = bigSeasFor([makeDay()], reader, [aligned, reader], NOW);
    expect(sel.jumpRow).toBe(aligned);
  });

  it('offers no jump on an aligned day when the reader is on the aligned event, even with a high-water row', () => {
    const aligned = makeRow();
    const hw = makeRow({ id: 'hw:2026-10-24', kind: 'hw', eventType: 'HIGH_WATER' });
    const sel = bigSeasFor([makeDay({ daylightHighWater: true })], aligned, [aligned, hw], NOW);
    expect(sel.aligned).toBe(true);
    expect(sel.jumpRow).toBeNull();
  });

  it('offers no high-water jump on an unaligned day that is not a daylight high-water day', () => {
    const reader = makeRow({ id: 'astro:2026-10-24', kind: 'astro', eventType: 'ASTRO' });
    const hw = makeRow({ id: 'hw:2026-10-24', kind: 'hw', eventType: 'HIGH_WATER' });
    const day = makeDay({ alignedEvent: null, daylightHighWater: false });
    expect(bigSeasFor([day], reader, [hw], NOW).jumpRow).toBeNull();
    const absent = makeDay({ alignedEvent: null, daylightHighWater: undefined });
    expect(bigSeasFor([absent], reader, [hw], NOW).jumpRow).toBeNull();
  });

  it('jumps to a high-water row that sits first in the list', () => {
    const reader = makeRow({ id: 'astro:2026-10-24', kind: 'astro', eventType: 'ASTRO' });
    const hw = makeRow({ id: 'hw:2026-10-24', kind: 'hw', eventType: 'HIGH_WATER' });
    const day = makeDay({ alignedEvent: null, daylightHighWater: true });
    expect(bigSeasFor([day], reader, [hw, reader], NOW).jumpRow).toBe(hw);
  });

  it('offers no jump on a daylight high-water day whose list has no high-water row', () => {
    const reader = makeRow({ id: 'astro:2026-10-24', kind: 'astro', eventType: 'ASTRO' });
    const day = makeDay({ alignedEvent: null, daylightHighWater: true });
    expect(bigSeasFor([day], reader, [reader], NOW).jumpRow).toBeNull();
    expect(bigSeasFor([day], reader, [], NOW).jumpRow).toBeNull();
  });

  it('offers no jump when the reader is already on the high-water row', () => {
    const hw = makeRow({ id: 'hw:2026-10-24', kind: 'hw', eventType: 'HIGH_WATER' });
    const day = makeDay({ alignedEvent: null, daylightHighWater: true });
    expect(bigSeasFor([day], hw, [hw], NOW).jumpRow).toBeNull();
  });
});

describe('bigSeasDayName edges', () => {
  it('is an empty string for an unparseable date', () => {
    expect(bigSeasDayName('not-a-date')).toBe('');
  });
});

describe('bigSeasCopy edges', () => {
  it('is null when either the form or the day is missing', () => {
    expect(bigSeasCopy({ form: 'full', day: null, aligned: false, jumpRow: null })).toBeNull();
    expect(bigSeasCopy({ form: null, day: makeDay(), aligned: false, jumpRow: null })).toBeNull();
    expect(bigSeasCopy(undefined)).toBeNull();
  });

  it('prints no onto clause for a day with no structures key', () => {
    const day = makeDay();
    delete day.structures;
    const copy = bigSeasCopy({ form: 'full', day, aligned: false, jumpRow: null });
    expect(copy.line2).toBe('Waves 4.1 m from NNE · watch from high ground, not the pier');
  });

  it('omits the gap clause when the served gap minutes are missing', () => {
    const day = makeDay({ gapMinutes: null });
    const copy = bigSeasCopy({ form: 'full', day, aligned: true, jumpRow: null });
    expect(copy.line1).toBe('15:30–18:15 · high water 16:58');
    const absent = makeDay();
    delete absent.gapMinutes;
    expect(bigSeasCopy({ form: 'full', day: absent, aligned: true, jumpRow: null }).line1)
      .toBe('15:30–18:15 · high water 16:58');
  });

  it('prints the gap clause when everything is served', () => {
    const copy = bigSeasCopy({ form: 'full', day: makeDay(), aligned: true, jumpRow: null });
    expect(copy.line1).toBe('15:30–18:15 · high water 16:58, 49 min before sunset');
    expect(copy.lead).toBeUndefined();
  });

  describe('the high-water lead', () => {
    const hwDay = (over) => makeDay({ alignedEvent: null, daylightHighWater: true, ...over });
    const select = (day) => ({ form: 'full', day, aligned: false, jumpRow: null });
    const tail = 'High water at 16:58. Not a light window: this is for spray over the piers, not for colour.';

    it('names the waves when both facts are served', () => {
      expect(bigSeasCopy(select(hwDay())).lead).toBe(
        'High water at 16:58 with 4.1 m waves from NNE. Not a light window: this is for spray over the piers, '
        + 'not for colour.',
      );
    });

    it('leaves the waves clause out when the height is missing, null or not a number', () => {
      expect(bigSeasCopy(select(hwDay({ waveHeightM: null }))).lead).toBe(tail);
      expect(bigSeasCopy(select(hwDay({ waveHeightM: undefined }))).lead).toBe(tail);
      expect(bigSeasCopy(select(hwDay({ waveHeightM: 'big' }))).lead).toBe(tail);
    });

    it('leaves the waves clause out when the direction is missing, null or not a number', () => {
      expect(bigSeasCopy(select(hwDay({ waveDirectionDeg: null }))).lead).toBe(tail);
      expect(bigSeasCopy(select(hwDay({ waveDirectionDeg: undefined }))).lead).toBe(tail);
      expect(bigSeasCopy(select(hwDay({ waveDirectionDeg: 'north' }))).lead).toBe(tail);
    });

    it('has no lead for a day with an aligned event, even when flagged daylight high water', () => {
      const copy = bigSeasCopy(select(makeDay({ daylightHighWater: true })));
      expect(copy).not.toHaveProperty('lead');
      expect(copy.line1).toBe('15:30–18:15 · high water 16:58');
    });

    it('has no lead and no between-the-lights text when the day is not daylight high water', () => {
      const copy = bigSeasCopy(select(hwDay({ daylightHighWater: false })));
      expect(copy).not.toHaveProperty('lead');
      expect(copy.line1).toBe('15:30–18:15 · high water 16:58');
      const absent = hwDay();
      delete absent.daylightHighWater;
      expect(bigSeasCopy(select(absent))).not.toHaveProperty('lead');
    });

    it('says between the lights on a flagged day', () => {
      expect(bigSeasCopy(select(hwDay())).line1).toBe('15:30–18:15 · high water 16:58, between the lights');
    });
  });
});
