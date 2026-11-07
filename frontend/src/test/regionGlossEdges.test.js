import { describe, it, expect } from 'vitest';
import { buildRegionGlossIndex, glossLead } from '../utils/regionGloss.js';

describe('glossLead — a headline ends in terminal punctuation or gets a full stop', () => {
  it('adds a full stop to a headline that only has a stop or a mark inside it', () => {
    expect(glossLead('Clear to 3.5 oktas')).toBe('Clear to 3.5 oktas.');
    expect(glossLead('Wow! Clear skies')).toBe('Wow! Clear skies.');
    expect(glossLead('Why? Cloud breaks')).toBe('Why? Cloud breaks.');
  });

  it('leaves a headline that already ends in a terminal mark, and trims trailing space first', () => {
    expect(glossLead('Clear skies.')).toBe('Clear skies.');
    expect(glossLead('Clear skies!  ')).toBe('Clear skies!');
    expect(glossLead('Clear skies?')).toBe('Clear skies?');
    expect(glossLead('Clear skies…')).toBe('Clear skies…');
  });

  it('is null for nothing, blank or a non-string', () => {
    expect(glossLead(null)).toBeNull();
    expect(glossLead('   ')).toBeNull();
    expect(glossLead(42)).toBeNull();
  });
});

describe('buildRegionGlossIndex — malformed days are skipped', () => {
  it('builds an empty index from a non-array, and skips entries that are not objects', () => {
    expect(buildRegionGlossIndex(null).size).toBe(0);
    expect(buildRegionGlossIndex('days').size).toBe(0);
    expect(buildRegionGlossIndex(['x', 7, null]).size).toBe(0);
  });

  it('skips a day with no event summaries, a summary with no regions, and a non-object summary or region', () => {
    const days = [
      { date: '2026-11-02' },
      { date: '2026-11-03', eventSummaries: ['x', null, { targetType: 'SUNSET' }] },
      { date: '2026-11-04', eventSummaries: [{ targetType: 'SUNSET', regions: ['y', null] }] },
    ];
    expect(buildRegionGlossIndex(days).size).toBe(0);
  });

  it('indexes a real region and keeps the first of a duplicate key', () => {
    const region = (glossHeadline, glossDetail) => ({ regionName: 'North', glossHeadline, glossDetail });
    const index = buildRegionGlossIndex([{
      date: '2026-11-02',
      eventSummaries: [{ targetType: 'SUNSET', regions: [region('Clear', null), region('Murky', 'x')] }],
    }]);
    expect(index.get('2026-11-02|SUNSET|North')).toEqual({ headline: 'Clear', detail: null });
  });
});
