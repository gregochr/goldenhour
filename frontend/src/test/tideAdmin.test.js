import { describe, it, expect } from 'vitest';
import {
  THIN_TIDE_READINGS, buildTideRows, fmtCount, fmtMetres, fmtPercent, isThin, medianReadings, tideTypesText,
} from '../utils/tideAdmin.js';

const row = (dataPoints) => ({ stats: { dataPoints } });

describe('tideAdmin', () => {
  it('pins the thin threshold at 2000', () => {
    expect(THIN_TIDE_READINGS).toBe(2000);
    expect(isThin(row(1999))).toBe(true);
    expect(isThin(row(2000))).toBe(false);
    expect(isThin(row(null))).toBe(false);
  });

  it('takes the median of the readings, ignoring missing counts', () => {
    expect(medianReadings([row(100), row(300), row(200)])).toBe(200);
    expect(medianReadings([row(100), row(200), row(300), row(null)])).toBe(200);
    expect(medianReadings([row(100), row(200), row(null)])).toBeNull();
    expect(medianReadings([row(null)])).toBeNull();
    expect(medianReadings([])).toBeNull();
  });

  it('leaves the excluded row out and needs three others', () => {
    const own = row(10);
    expect(medianReadings([own, row(100), row(200), row(300)], own)).toBe(200);
    expect(medianReadings([own, row(100), row(200)], own)).toBeNull();
  });

  it('puts en-GB thousands separators on counts', () => {
    expect(fmtCount(5000)).toBe('5,000');
    expect(fmtCount(null)).toBe('—');
  });

  it('formats metres and percentages, with an em dash for nothing', () => {
    expect(fmtMetres(1.4)).toBe('1.40');
    expect(fmtMetres('2')).toBe('2.00');
    expect(fmtMetres(null)).toBe('—');
    expect(fmtMetres('x')).toBe('—');
    expect(fmtPercent(0.05)).toBe('5.0%');
    expect(fmtPercent(undefined)).toBe('—');
  });

  it('words the tide types', () => {
    expect(tideTypesText(['HIGH', 'LOW'])).toBe('High, Low');
    expect(tideTypesText([])).toBe('—');
    expect(tideTypesText(null)).toBe('—');
  });

  it('joins by name and keeps an unmatched entry with a null location id', () => {
    const rows = buildTideRows(
      { A: { dataPoints: 1 }, B: { dataPoints: 2 } },
      [{ id: 5, name: 'A', enabled: false, region: { name: 'R' }, tideType: ['MID'], subRegionName: 'Sub' }],
    );
    expect(rows[0]).toMatchObject({ id: 5, locationId: 5, region: 'R', subRegion: 'Sub', enabled: false, tideTypes: ['MID'] });
    expect(rows[1]).toMatchObject({ id: 'stats:B', locationId: null, region: null, subRegion: null, enabled: null });
  });
});
