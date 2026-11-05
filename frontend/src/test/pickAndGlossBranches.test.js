import { describe, it, expect, afterEach, vi } from 'vitest';
import {
  compareNames, regionPickSummaries, pickedLineText, countWord, picksNoteText,
} from '../utils/pickGeography.js';
import {
  buildRegionGlossIndex, regionGlossFor, regionGlossEntry, glossLead,
} from '../utils/regionGloss.js';
import { subRegionAction } from '../utils/subRegionAction.js';
import { scrollToEntry } from '../utils/scrollToEntry.js';
import { LOCKED_FULL, OPEN } from './subRegionFixtures.js';

describe('pickGeography compareNames', () => {
  it('orders case-insensitively, treats case-only differences as equal and null as empty', () => {
    expect(compareNames('apple', 'Banana')).toBe(-1);
    expect(compareNames('Banana', 'apple')).toBe(1);
    expect(compareNames('Same', 'same')).toBe(0);
    expect(compareNames(null, 'a')).toBe(-1);
    expect(compareNames('a', undefined)).toBe(1);
  });
});

describe('pickGeography regionPickSummaries', () => {
  const spots = [
    { id: 1, name: 'Zed', regionName: 'North', subRegionId: 10, subRegionName: 'Tyne' },
    { id: 2, name: 'Alpha', regionName: 'North', subRegionId: 10, subRegionName: 'Tyne', skySubject: false },
    { id: 3, name: 'Beta', regionName: 'North', subRegionId: null, subRegionName: 'Wear' },
    { id: 4, name: 'Gamma', regionName: 'South' },
    { id: null, name: 'Orphan', regionName: 'South' },
    { id: 5, name: 'Nowhere' },
    null,
  ];

  it('is null for a body that is not shaped', () => {
    expect(regionPickSummaries(spots, undefined)).toBeNull();
    expect(regionPickSummaries(spots, 'x')).toBeNull();
  });

  it('counts places, allowed places, sky outsiders and sorted outside places per region', () => {
    const out = regionPickSummaries(spots, [1]);
    expect([...out.keys()]).toEqual(['North', 'South']);
    const north = out.get('North');
    expect(north.places).toBe(3);
    expect(north.allowedPlaces).toBe(1);
    // Alpha is not a sky subject, so only Beta is a sky place outside the picks.
    expect(north.skyOutside).toBe(1);
    expect(north.outsidePlaces.map((p) => p.name)).toEqual(['Alpha', 'Beta']);
    expect(north.outsidePlaces[1]).toEqual({ id: 3, name: 'Beta', subRegionId: null, subRegionName: 'Wear' });
    expect(north.outsidePlaces[0].subRegionName).toBe('Tyne');
    expect(north.pickedNames).toEqual(['Tyne']);
    expect(north.subRegions).toEqual([
      { key: 'Wear', name: 'Wear', places: 1, picked: false },
      { key: '10', name: 'Tyne', places: 2, picked: true },
    ].sort((a, b) => a.name.localeCompare(b.name)));
  });

  it('treats a place with no id as outside and nulls missing sub-region facts', () => {
    const south = regionPickSummaries(spots, [4]).get('South');
    expect(south.allowedPlaces).toBe(1);
    expect(south.skyOutside).toBe(1);
    expect(south.outsidePlaces).toEqual([{ id: null, name: 'Orphan', subRegionId: null, subRegionName: null }]);
    expect(south.subRegions).toEqual([]);
  });

  it('copes with a non-array spot list', () => {
    expect(regionPickSummaries(null, []).size).toBe(0);
  });
});

describe('pickGeography wording', () => {
  it('words the third region-label line, singular at one', () => {
    expect(pickedLineText(0)).toBe('outside your sub-regions');
    expect(pickedLineText(undefined)).toBe('outside your sub-regions');
    expect(pickedLineText(1)).toBe('1 sub-region picked');
    expect(pickedLineText(2)).toBe('2 sub-regions picked');
  });

  it('counts with the right noun', () => {
    expect(countWord(1, 'place', 'places')).toBe('1 place');
    expect(countWord(0, 'place', 'places')).toBe('0 places');
  });

  it('says what the colour covers, or where to choose', () => {
    expect(picksNoteText({ allowed: 1, total: 40 }))
      .toBe('Colour shows the 1 place in your sub-regions. Pro rates all 40.');
    expect(picksNoteText({ allowed: 0, total: 40 })).toBe('Choose your sub-regions in Settings to see ratings.');
  });
});

describe('regionGloss', () => {
  const days = [
    {
      date: '2026-11-04',
      eventSummaries: [
        {
          targetType: 'SUNRISE',
          regions: [
            { regionName: 'North', glossHeadline: 'Fiery', glossDetail: 'Clear east.' },
            { regionName: 'North', glossHeadline: 'Duplicate', glossDetail: 'Ignored.' },
            { regionName: 'South', glossHeadline: 'Flat' },
            { regionName: 'West', glossDetail: 'Only detail' },
            { regionName: 'Empty', glossHeadline: '', glossDetail: '' },
            { glossHeadline: 'No name' },
            null,
          ],
        },
        { regions: [{ regionName: 'North', glossHeadline: 'No target' }] },
        null,
        { targetType: 'SUNSET' },
      ],
    },
    { date: '2026-11-05' },
    { eventSummaries: [{ targetType: 'SUNRISE', regions: [{ regionName: 'North', glossHeadline: 'No date' }] }] },
    null,
  ];

  it('indexes only regions with a gloss, keeping the first per window', () => {
    const index = buildRegionGlossIndex(days);
    expect([...index.keys()].sort()).toEqual([
      '2026-11-04|SUNRISE|North', '2026-11-04|SUNRISE|South', '2026-11-04|SUNRISE|West',
    ]);
    expect(index.get('2026-11-04|SUNRISE|North')).toEqual({ headline: 'Fiery', detail: 'Clear east.' });
    expect(index.get('2026-11-04|SUNRISE|South')).toEqual({ headline: 'Flat', detail: null });
    expect(index.get('2026-11-04|SUNRISE|West')).toEqual({ headline: null, detail: 'Only detail' });
  });

  it('indexes nothing for a non-array', () => {
    expect(buildRegionGlossIndex(null).size).toBe(0);
  });

  it('prefers the detail, then the headline, then null', () => {
    const index = buildRegionGlossIndex(days);
    expect(regionGlossFor(index, '2026-11-04', 'SUNRISE', 'North')).toBe('Clear east.');
    expect(regionGlossFor(index, '2026-11-04', 'SUNRISE', 'South')).toBe('Flat');
    expect(regionGlossFor(index, '2026-11-04', 'SUNSET', 'North')).toBeNull();
    expect(regionGlossFor(null, '2026-11-04', 'SUNRISE', 'North')).toBeNull();
  });

  it('returns the unflattened entry, or null without an index or a region name', () => {
    const index = buildRegionGlossIndex(days);
    expect(regionGlossEntry(index, '2026-11-04', 'SUNRISE', 'West')).toEqual({ headline: null, detail: 'Only detail' });
    expect(regionGlossEntry(index, '2026-11-04', 'SUNRISE', null)).toBeNull();
    expect(regionGlossEntry(null, '2026-11-04', 'SUNRISE', 'West')).toBeNull();
  });

  it('ends a headline with a full stop unless it already has terminal punctuation', () => {
    expect(glossLead('Fiery sky  ')).toBe('Fiery sky.');
    expect(glossLead('Fiery sky!')).toBe('Fiery sky!');
    expect(glossLead('Wait…')).toBe('Wait…');
    expect(glossLead('   ')).toBeNull();
    expect(glossLead(null)).toBeNull();
    expect(glossLead(42)).toBeNull();
  });
});

describe('subRegionAction edges', () => {
  it('offers nothing for a status it does not know', () => {
    expect(subRegionAction({ ...OPEN, status: 'MYSTERY' })).toBeNull();
  });

  it('offers no button and no line for a locked reader at the limit with no server date', () => {
    expect(subRegionAction({ ...LOCKED_FULL, nextChangeOn: undefined })).toEqual({ label: null, line: null });
  });
});

describe('scrollToEntry reduced-motion guard', () => {
  afterEach(() => {
    document.body.innerHTML = '';
    delete Element.prototype.scrollIntoView;
    vi.unstubAllGlobals();
  });

  it('scrolls smoothly when matchMedia throws', () => {
    const wrap = document.createElement('div');
    wrap.setAttribute('data-entry-id', 'e9');
    document.body.appendChild(wrap);
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    vi.stubGlobal('matchMedia', () => {
      throw new Error('boom');
    });

    expect(scrollToEntry('e9')).toBe(true);
    expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'smooth', block: 'center' });
  });
});
