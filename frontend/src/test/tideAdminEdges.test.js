import { describe, it, expect } from 'vitest';
import {
  buildTideRows, fmtMetres, fmtPercent, numOrNull, signedMetres,
} from '../utils/tideAdmin.js';

describe('blank strings are missing numbers, not zero', () => {
  it('fmtMetres, fmtPercent and numOrNull treat an empty string as no value', () => {
    expect(fmtMetres('')).toBe('—');
    expect(fmtPercent('')).toBe('—');
    expect(numOrNull('')).toBeNull();
  });

  it('still formats a real zero', () => {
    expect(fmtMetres(0)).toBe('0.00');
    expect(fmtMetres('0')).toBe('0.00');
    expect(fmtPercent(0)).toBe('0.0%');
    expect(numOrNull(0)).toBe(0);
    expect(numOrNull('0')).toBe(0);
  });
});

describe('signedMetres', () => {
  it('is an em dash for null, undefined, blank and non-numeric values', () => {
    expect(signedMetres(null)).toBe('—');
    expect(signedMetres(undefined)).toBe('—');
    expect(signedMetres('')).toBe('—');
    expect(signedMetres('abc')).toBe('—');
  });

  it('puts a plus on zero and positives and a true minus on negatives', () => {
    expect(signedMetres(0)).toBe('+0.00 m');
    expect(signedMetres(2.874)).toBe('+2.87 m');
    expect(signedMetres(-2.894)).toBe('−2.89 m');
  });
});

describe('buildTideRows — rows with no matching location', () => {
  it('gives a stats-only row an empty tide-type list, no region and a null enabled flag', () => {
    const rows = buildTideRows({ Lonely: { dataPoints: 10 } }, []);
    expect(rows).toEqual([{
      id: 'stats:Lonely',
      name: 'Lonely',
      stats: { dataPoints: 10 },
      locationId: null,
      region: null,
      subRegion: null,
      tideTypes: [],
      enabled: null,
    }]);
  });

  it('copes with a missing locations list and a missing stats map', () => {
    expect(buildTideRows({ A: { dataPoints: 1 } }, null).map((r) => r.id)).toEqual(['stats:A']);
    expect(buildTideRows({ A: { dataPoints: 1 } }, undefined)[0].tideTypes).toEqual([]);
    expect(buildTideRows(null, [{ id: 1, name: 'A' }])).toEqual([]);
  });

  it('gives a matched location with no tideType array an empty list, and keeps a real one', () => {
    const rows = buildTideRows(
      { A: { dataPoints: 1 }, B: null },
      [{ id: 1, name: 'A', tideType: 'HIGH' }, { id: 2, name: 'B', tideType: ['HIGH', 'LOW'], enabled: false }],
    );
    expect(rows[0].tideTypes).toEqual([]);
    expect(rows[1].tideTypes).toEqual(['HIGH', 'LOW']);
    expect(rows[1].enabled).toBe(false);
    expect(rows[1].stats).toEqual({});
    expect(rows[0].enabled).toBe(true);
  });
});
