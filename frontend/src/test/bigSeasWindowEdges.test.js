import { describe, it, expect } from 'vitest';
import { bigSeasWindowAhead } from '../utils/bigSeasWindow.js';

const NOW = new Date('2026-11-02T12:00:00Z');

describe('bigSeasWindowAhead', () => {
  it('is false, not an error, for no day at all', () => {
    expect(bigSeasWindowAhead(null, NOW)).toBe(false);
    expect(bigSeasWindowAhead(undefined, NOW)).toBe(false);
    expect(bigSeasWindowAhead({}, NOW)).toBe(false);
  });

  it('is strictly after now', () => {
    expect(bigSeasWindowAhead({ windowEnd: '2026-11-02T12:00:00Z' }, NOW)).toBe(false);
    expect(bigSeasWindowAhead({ windowEnd: '2026-11-02T12:00:01Z' }, NOW)).toBe(true);
    expect(bigSeasWindowAhead({ windowEnd: '2026-11-02T11:59:59Z' }, NOW)).toBe(false);
  });

  it('reads an unparseable end as not ahead', () => {
    expect(bigSeasWindowAhead({ windowEnd: 'soon' }, NOW)).toBe(false);
  });
});
