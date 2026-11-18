import { describe, it, expect } from 'vitest';
import { warningsForMapWindow } from '../utils/warnings.js';
import { buildScoreIndex, buildSlotIndex, warningInputsFor } from '../utils/locationSheet.js';
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

  describe('a place with no stored row, matched exactly as the callout matches it', () => {
    const a = W({ warningId: 'a', locationIds: [2] });
    const bare = { id: 2, name: 'Bare', forecastsByDate: new Map() };
    const slots = (time) => buildSlotIndex([{
      date: DATE,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{ regionName: 'R', confidence: 'high', slots: [{ locationId: 2, locationName: 'Bare', solarEventTime: time }] }],
      }],
    }]);
    const scores = (golden) => buildScoreIndex([{
      locationId: 2, locationName: 'Bare', date: DATE, targetType: 'SUNSET', rating: 4, goldenHourEnd: golden,
    }]);

    it('uses the place\'s own briefing slot when the roster has no rows at all', () => {
      expect(warningsForMapWindow(served(a), { event: EVENT, locations: [bare], slotIndex: slots(`${DATE}T19:40:00`) }))
        .toHaveLength(1);
    });

    it('uses the place\'s own slot instant, not another place\'s row, when both exist', () => {
      // Another place's row says 19:40 (inside the span); this place's own slot says 23:30 (outside it).
      const late = slots(`${DATE}T23:30:00`);
      expect(warningsForMapWindow(served(a), { event: EVENT, locations: [loc(1), bare], slotIndex: late })).toEqual([]);
    });

    it('falls back to the place\'s own score row, then to nothing', () => {
      expect(warningsForMapWindow(served(a), { event: EVENT, locations: [bare], scoreIndex: scores(`${DATE}T19:40:00`) }))
        .toHaveLength(1);
      expect(warningsForMapWindow(served(a), { event: EVENT, locations: [bare] })).toEqual([]);
    });

    it('does not borrow another place\'s row or the window\'s tide instant', () => {
      const event = { ...EVENT, tide: { eventTime: `${DATE}T19:40:00` } };
      expect(warningsForMapWindow(served(a), { event, locations: [loc(1), bare] })).toEqual([]);
    });
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

  it('warningInputsFor answers nothing for no place or no window', () => {
    expect(warningInputsFor(null, EVENT, null, null)).toEqual({ row: null, eventIso: null });
    expect(warningInputsFor(loc(1), null, null, null)).toEqual({ row: null, eventIso: null });
  });

  it('is empty for no warnings, no roster and a missing served value', () => {
    const a = W({ warningId: 'a', locationIds: [1] });
    expect(warningsForMapWindow(served(), { event: EVENT, locations: [loc(1)] })).toEqual([]);
    expect(warningsForMapWindow(served(a), { event: EVENT, locations: [] })).toEqual([]);
    expect(warningsForMapWindow(served(a), { event: EVENT, locations: null })).toEqual([]);
    expect(warningsForMapWindow(null, { event: EVENT, locations: [loc(1)] })).toEqual([]);
  });
});
