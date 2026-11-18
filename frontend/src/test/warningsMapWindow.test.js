import { describe, it, expect } from 'vitest';
import { warningsForMapWindow } from '../utils/warnings.js';
import { response, warning } from './warningsFixture.js';

const DATE = '2026-08-14';
const row = (iso = `${DATE}T19:40:00`) => ({ sunset: { solarEventTime: iso } });
const loc = (id, rows = row()) => ({ id, forecastsByDate: new Map([[DATE, rows]]) });
const EVENT = {
  kind: 'solar', eventType: 'SUNSET', date: DATE, past: false,
};
const served = (...warnings) => response(warnings);
const W = (overrides) => warning({ validFrom: `${DATE}T00:00:00Z`, validTo: `${DATE}T23:00:00Z`, ...overrides });

describe('warningsForMapWindow', () => {
  it('unions the warnings covering any roster place at the window instant, most severe first', () => {
    const a = W({ warningId: 'a', locationIds: [1] });
    const b = W({ warningId: 'b', level: 'RED', locationIds: [2] });
    const out = warningsForMapWindow(served(a, b), { event: EVENT, locations: [loc(1), loc(2)] });
    expect(out.map((w) => w.warningId)).toEqual(['b', 'a']);
  });

  it('lists a warning once however many places it covers', () => {
    const a = W({ warningId: 'a', locationIds: [1, 2] });
    expect(warningsForMapWindow(served(a), { event: EVENT, locations: [loc(1), loc(2)] })).toHaveLength(1);
  });

  it('compares ids as numbers, so a string id on either side still matches', () => {
    const a = W({ warningId: 'a', locationIds: ['1'] });
    expect(warningsForMapWindow(served(a), { event: EVENT, locations: [loc(1)] })).toHaveLength(1);
  });

  it('ignores a warning that covers no roster place, or not at the instant', () => {
    const elsewhere = W({ warningId: 'x', locationIds: [9] });
    const later = W({ warningId: 'y', locationIds: [1], validFrom: `${DATE}T21:00:00Z` });
    expect(warningsForMapWindow(served(elsewhere, later), { event: EVENT, locations: [loc(1)] })).toEqual([]);
  });

  it('matches a place with no stored row at the instant another place carries', () => {
    const a = W({ warningId: 'a', locationIds: [2] });
    const bare = { id: 2, forecastsByDate: new Map() };
    expect(warningsForMapWindow(served(a), { event: EVENT, locations: [loc(1), bare] })).toHaveLength(1);
  });

  it('falls back to the window\'s served tide instant when no place has a row', () => {
    const a = W({ warningId: 'a', locationIds: [2] });
    const bare = { id: 2, forecastsByDate: new Map() };
    const event = { ...EVENT, tide: { eventTime: `${DATE}T19:40:00` } };
    expect(warningsForMapWindow(served(a), { event, locations: [bare] })).toHaveLength(1);
    expect(warningsForMapWindow(served(a), { event: EVENT, locations: [bare] })).toEqual([]);
  });

  it.each([
    ['a night', { ...EVENT, kind: 'astro' }],
    ['High water', { ...EVENT, kind: 'hw' }],
    ['a past window', { ...EVENT, past: true }],
    ['no window', null],
    ['a window without a date', { ...EVENT, date: null }],
  ])('is empty for %s', (_name, event) => {
    const a = W({ warningId: 'a', locationIds: [1] });
    expect(warningsForMapWindow(served(a), { event, locations: [loc(1)] })).toEqual([]);
  });

  it('is empty for no warnings, no roster and a missing served value', () => {
    const a = W({ warningId: 'a', locationIds: [1] });
    expect(warningsForMapWindow(served(), { event: EVENT, locations: [loc(1)] })).toEqual([]);
    expect(warningsForMapWindow(served(a), { event: EVENT, locations: [] })).toEqual([]);
    expect(warningsForMapWindow(served(a), { event: EVENT, locations: null })).toEqual([]);
    expect(warningsForMapWindow(null, { event: EVENT, locations: [loc(1)] })).toEqual([]);
  });
});
