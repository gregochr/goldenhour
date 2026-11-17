import { describe, it, expect } from 'vitest';
import {
  compareWarnings, joinWords, levelWord, orderWarnings, resolveWarnings, staleLead, typeWords,
  warningLabel, warningName, warningsForPlaceAt, warningsForWindow, WARNING_LEVELS,
} from '../utils/warnings.js';
import { warning } from './warningsFixture.js';

describe('warnings utils', () => {
  it('carries the official level colours and the guide\'s ink', () => {
    expect(WARNING_LEVELS.RED).toMatchObject({ hex: '#ca0a37', ink: '#ffffff' });
    expect(WARNING_LEVELS.AMBER).toMatchObject({ hex: '#ff9900', ink: '#000000' });
    expect(WARNING_LEVELS.YELLOW).toMatchObject({ hex: '#ffe923', ink: '#000000' });
  });

  it('prints the level word, sentence case, and never guesses an unknown one', () => {
    expect(levelWord('RED')).toBe('Red');
    expect(levelWord('amber')).toBe('Amber');
    expect(levelWord(' YELLOW ')).toBe('Yellow');
    expect(levelWord('PURPLE_ISH')).toBe('Purple ish');
    expect(levelWord('')).toBeNull();
    expect(levelWord(null)).toBeNull();
    expect(levelWord(4)).toBeNull();
  });

  it('lower-cases types in the order received and keeps unknown ones', () => {
    expect(typeWords(['RAIN', 'EXTREME_HEAT', 'tsunami'])).toEqual(['rain', 'extreme heat', 'tsunami']);
    expect(typeWords(['WIND', '', null, 7])).toEqual(['wind']);
    expect(typeWords(undefined)).toEqual([]);
  });

  it('joins words as prose', () => {
    expect(joinWords([])).toBe('');
    expect(joinWords(['wind'])).toBe('wind');
    expect(joinWords(['wind', 'rain'])).toBe('wind and rain');
    expect(joinWords(['wind', 'rain', 'snow'])).toBe('wind, rain and snow');
  });

  it('builds the label and the accessible name', () => {
    expect(warningLabel(warning())).toBe('Yellow warning of wind');
    expect(warningLabel(warning({ level: 'AMBER', weatherTypes: ['RAIN', 'WIND'] })))
      .toBe('Amber warning of rain and wind');
    expect(warningLabel(warning({ weatherTypes: [] }))).toBe('Yellow warning');
    expect(warningLabel(warning({ level: null }))).toBeNull();
    expect(warningLabel(null)).toBeNull();
    expect(warningName(warning())).toBe('Yellow warning of wind');
    expect(warningName(warning({ updated: true }))).toBe('Yellow warning of wind, updated');
    expect(warningName(warning({ level: '' }))).toBeNull();
  });

  describe('ordering (style guide: level, impact, likelihood, validFrom)', () => {
    const red = warning({ warningId: 'r', level: 'RED', impact: 1, likelihood: 1 });
    const amber = warning({ warningId: 'a', level: 'AMBER', impact: 4, likelihood: 4 });
    const yellow = warning({ warningId: 'y', level: 'YELLOW', impact: 4, likelihood: 4 });

    it('sorts by level first, whatever the impact', () => {
      expect(orderWarnings([yellow, amber, red]).map((w) => w.warningId)).toEqual(['r', 'a', 'y']);
    });

    it('then by impact, highest first, a missing one last', () => {
      const hi = warning({ warningId: 'hi', impact: 3 });
      const lo = warning({ warningId: 'lo', impact: 1 });
      const none = warning({ warningId: 'none', impact: null });
      expect(orderWarnings([none, lo, hi]).map((w) => w.warningId)).toEqual(['hi', 'lo', 'none']);
    });

    it('then by likelihood, highest first', () => {
      const a = warning({ warningId: 'a', impact: 2, likelihood: 4 });
      const b = warning({ warningId: 'b', impact: 2, likelihood: 2 });
      expect(orderWarnings([b, a]).map((w) => w.warningId)).toEqual(['a', 'b']);
    });

    it('then by validFrom, earliest first, a missing one last, then by id', () => {
      const early = warning({ warningId: 'early', validFrom: '2026-08-14T00:00:00Z' });
      const late = warning({ warningId: 'late', validFrom: '2026-08-15T00:00:00Z' });
      const open = warning({ warningId: 'open', validFrom: null });
      expect(orderWarnings([open, late, early]).map((w) => w.warningId)).toEqual(['early', 'late', 'open']);
      const x = warning({ warningId: 'x' });
      const y = warning({ warningId: 'y' });
      expect(orderWarnings([y, x]).map((w) => w.warningId)).toEqual(['x', 'y']);
      expect(compareWarnings(x, x)).toBe(0);
    });

    it('ranks an unknown level below Yellow and tolerates junk', () => {
      const odd = warning({ warningId: 'odd', level: 'GREEN' });
      expect(orderWarnings([odd, yellow]).map((w) => w.warningId)).toEqual(['y', 'odd']);
      expect(orderWarnings(null)).toEqual([]);
      expect(orderWarnings([null, yellow])).toHaveLength(1);
      expect(compareWarnings(undefined, undefined)).toBe(0);
    });

    it('returns a copy and leaves the input alone', () => {
      const input = [yellow, red];
      orderWarnings(input);
      expect(input[0]).toBe(yellow);
    });
  });

  describe('resolveWarnings', () => {
    const byId = new Map([
      ['y', warning({ warningId: 'y' })],
      ['r', warning({ warningId: 'r', level: 'RED' })],
      ['nolevel', warning({ warningId: 'nolevel', level: null })],
    ]);

    it('looks ids up once each, in the style guide order, dropping unknown ones', () => {
      expect(resolveWarnings(['y', 'r', 'y', 'gone', 'nolevel'], byId).map((w) => w.warningId))
        .toEqual(['r', 'y']);
    });

    it('is empty for no ids or no map', () => {
      expect(resolveWarnings(undefined, byId)).toEqual([]);
      expect(resolveWarnings(['y'], null)).toEqual([]);
    });
  });

  describe('warningsForPlaceAt', () => {
    const w = warning({ locationIds: [7, 9] });

    it('matches a covered place inside the validity span, bare or Z-suffixed', () => {
      expect(warningsForPlaceAt([w], 7, '2026-08-15T05:38:00')).toHaveLength(1);
      expect(warningsForPlaceAt([w], 7, '2026-08-15T05:38:00Z')).toHaveLength(1);
      expect(warningsForPlaceAt([w], 7, '2026-08-15T05:38:00+00:00')).toHaveLength(1);
    });

    it('is inclusive at both ends of the span', () => {
      expect(warningsForPlaceAt([w], 7, '2026-08-14T12:00:00')).toHaveLength(1);
      expect(warningsForPlaceAt([w], 7, '2026-08-16T12:00:00')).toHaveLength(1);
    });

    it('excludes an uncovered place, an instant outside the span and a warning with no level', () => {
      expect(warningsForPlaceAt([w], 8, '2026-08-15T05:38:00')).toEqual([]);
      expect(warningsForPlaceAt([w], 7, '2026-08-14T11:59:00')).toEqual([]);
      expect(warningsForPlaceAt([w], 7, '2026-08-16T12:01:00')).toEqual([]);
      expect(warningsForPlaceAt([warning({ level: null })], 7, '2026-08-15T05:38:00')).toEqual([]);
    });

    it('is empty for unusable input and for a warning with no locationIds', () => {
      expect(warningsForPlaceAt([w], null, '2026-08-15T05:38:00')).toEqual([]);
      expect(warningsForPlaceAt([w], 7, null)).toEqual([]);
      expect(warningsForPlaceAt([w], 7, 'not a time')).toEqual([]);
      expect(warningsForPlaceAt(null, 7, '2026-08-15T05:38:00')).toEqual([]);
      expect(warningsForPlaceAt([warning({ locationIds: undefined })], 7, '2026-08-15T05:38:00')).toEqual([]);
    });
  });

  describe('warningsForWindow', () => {
    const w = warning({ locationIds: [7] });
    const served = { warnings: [w], byId: new Map([[w.warningId, w]]) };

    it('trusts a stored row\'s own ids, and an absent field means none', () => {
      expect(warningsForWindow(served, { row: { warningIds: [w.warningId] }, locationId: 7, eventIso: null }))
        .toHaveLength(1);
      expect(warningsForWindow(served, { row: {}, locationId: 7, eventIso: '2026-08-15T05:38:00' }))
        .toEqual([]);
    });

    it('matches the place against the served list when there is no row', () => {
      expect(warningsForWindow(served, { row: null, locationId: 7, eventIso: '2026-08-15T05:38:00' }))
        .toHaveLength(1);
      expect(warningsForWindow(served, { row: null, locationId: 8, eventIso: '2026-08-15T05:38:00' }))
        .toEqual([]);
    });

    it('is empty with nothing served', () => {
      expect(warningsForWindow(null, { row: null, locationId: 7, eventIso: '2026-08-15T05:38:00' })).toEqual([]);
      expect(warningsForWindow({ warnings: [], byId: new Map() }, { row: null, locationId: 7, eventIso: null }))
        .toEqual([]);
    });
  });

  it('words the stale line from the UK clock, or admits no check yet', () => {
    // 13:05Z on 14 August is 14:05 BST.
    expect(staleLead('2026-08-14T13:05:00Z')).toBe('Warnings last checked 14:05');
    expect(staleLead(null)).toBe('Warnings have not been checked yet');
  });
});
