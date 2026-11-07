import { describe, it, expect, afterEach, vi } from 'vitest';
import { parseAliases, formatAliases, errorText } from '../utils/aliasText.js';
import {
  comingUpOutsideFacts, entrySubject, outsideLineText, COASTAL_KIND, DARK_SKY_KIND,
} from '../utils/comingUpOutside.js';
import { firstClause } from '../utils/firstClause.js';
import { formatCostGbp, formatCostUsd, formatTokens } from '../utils/formatCost.js';
import { resolveInitialTab } from '../utils/initialTab.js';
import {
  buildLocationTypeMap, locationTypeLabel, isSkyPromptCandidate, subjectWordsOf, isWildlifeOnly,
} from '../utils/locationTypes.js';
import { topicStyle, DEFAULT_SWATCH_COLOR } from '../utils/topicPalette.js';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('aliasText', () => {
  it('parses null and undefined text as an empty list', () => {
    expect(parseAliases(null)).toEqual([]);
    expect(parseAliases(undefined)).toEqual([]);
  });

  it('trims parts and drops blanks', () => {
    expect(parseAliases(' Dunstan ,, Craster ,')).toEqual(['Dunstan', 'Craster']);
  });

  it('formats a list with comma-space and a non-list as empty', () => {
    expect(formatAliases(['a', 'b'])).toBe('a, b');
    expect(formatAliases(undefined)).toBe('');
  });

  it('prefers the server error text over the fallback', () => {
    expect(errorText({ response: { data: { error: 'Clashes with Foo' } } }, 'Failed')).toBe('Clashes with Foo');
    expect(errorText(new Error('Network Error'), 'Failed')).toBe('Failed');
    expect(errorText(null, 'Failed')).toBe('Failed');
  });
});

describe('comingUpOutside', () => {
  const roster = [
    { id: 1, locationType: ['SEASCAPE'], bortleClass: 3 },
    { id: 2, locationType: ['LANDSCAPE'], bortleClass: 6 },
    { id: 3, locationType: ['LANDSCAPE'] },
  ];

  it('returns null for an unshaped body', () => {
    expect(comingUpOutsideFacts(roster, undefined)).toBeNull();
  });

  it('counts coastal and dark-sky places over the roster and the allowed ids', () => {
    expect(comingUpOutsideFacts(roster, [2])).toEqual({
      zeroPicks: false,
      rosterKnown: true,
      coastal: { roster: 1, allowed: 0 },
      darkSky: { roster: 1, allowed: 0 },
    });
    expect(comingUpOutsideFacts(roster, [1]).coastal).toEqual({ roster: 1, allowed: 1 });
  });

  it('treats a non-array roster as unknown and an empty pick list as zero picks', () => {
    expect(comingUpOutsideFacts(null, [])).toEqual({
      zeroPicks: true,
      rosterKnown: false,
      coastal: { roster: 0, allowed: 0 },
      darkSky: { roster: 0, allowed: 0 },
    });
  });

  it('maps an entry to its door subject', () => {
    expect(entrySubject({ action: { kind: COASTAL_KIND } })).toBe('coastal');
    expect(entrySubject({ action: { kind: DARK_SKY_KIND } })).toBe('darkSky');
    expect(entrySubject({ action: { kind: 'other' } })).toBeNull();
    expect(entrySubject(null)).toBeNull();
  });

  it('gives a withheld door no subject unless it carries day chips', () => {
    expect(entrySubject({ actionWithheld: true, action: { kind: COASTAL_KIND } })).toBeNull();
    expect(entrySubject({ actionWithheld: true, dayChips: [1], action: { kind: COASTAL_KIND } })).toBe('coastal');
  });

  it('says nothing for an unshaped body', () => {
    expect(outsideLineText('coastal', null)).toBeNull();
  });

  it('tells a reader with no picks to choose sub-regions', () => {
    const facts = comingUpOutsideFacts(roster, []);
    expect(outsideLineText('coastal', facts))
      .toBe('Choose your sub-regions in Settings to see ratings at coastal spots.');
    expect(outsideLineText('darkSky', facts))
      .toBe('Choose your sub-regions in Settings to see ratings at dark-sky spots.');
  });

  it('says nothing while the roster is unloaded or the reader holds a matching place', () => {
    expect(outsideLineText('coastal', comingUpOutsideFacts(null, [9]))).toBeNull();
    expect(outsideLineText('coastal', comingUpOutsideFacts(roster, [1]))).toBeNull();
  });

  it('explains a missing coast, singular and plural', () => {
    const facts = comingUpOutsideFacts(roster, [2]);
    expect(outsideLineText('coastal', facts)).toBe(
      'None of your sub-regions are on the coast. These days open the map with coastal spots shown outside your sub-regions.',
    );
    expect(outsideLineText('coastal', facts, true)).toContain('This day opens the map');
  });

  it('explains a missing dark-sky site, singular and plural', () => {
    const facts = comingUpOutsideFacts(roster, [2]);
    expect(outsideLineText('darkSky', facts)).toBe(
      'None of your sub-regions are dark-sky sites. These nights open the map with dark-sky spots shown outside your sub-regions.',
    );
    expect(outsideLineText('darkSky', facts, true)).toContain('This night opens the map');
  });
});

describe('firstClause', () => {
  it('returns null for null and for blank text', () => {
    expect(firstClause(null)).toBeNull();
    expect(firstClause('   ')).toBeNull();
  });

  it('cuts at the first boundary past the minimum length', () => {
    expect(firstClause('Clear skies over the coast this morning, with some haze later'))
      .toBe('Clear skies over the coast this morning.');
  });

  it('keeps the whole text when a late boundary leaves only junk before it', () => {
    const junk = ','.repeat(30);
    expect(firstClause(junk)).toBe(`${junk}.`);
  });

  it('keeps a short text whole and full-stops it', () => {
    expect(firstClause('Short, text')).toBe('Short, text.');
  });

  it('keeps text that already ends in terminal punctuation', () => {
    expect(firstClause('Short text!')).toBe('Short text!');
  });
});

describe('formatCost', () => {
  it('formats GBP above and below one penny, and a dash for missing inputs', () => {
    expect(formatCostGbp(2_000_000, 0.8)).toBe('£1.6000');
    expect(formatCostGbp(5000, 0.8)).toBe('0.400p');
    expect(formatCostGbp(null, 0.8)).toBe('—');
    expect(formatCostGbp(0, 0.8)).toBe('—');
    expect(formatCostGbp(1000, null)).toBe('—');
  });

  it('formats USD above and below one cent, and a dash for null or zero', () => {
    expect(formatCostUsd(1_500_000)).toBe('$1.5000');
    expect(formatCostUsd(5000)).toBe('0.500¢');
    expect(formatCostUsd(null)).toBe('—');
    expect(formatCostUsd(0)).toBe('—');
  });

  it('formats token counts with thousands separators and a dash for none', () => {
    expect(formatTokens(1234567)).toBe((1234567).toLocaleString());
    expect(formatTokens(0)).toBe('—');
    expect(formatTokens(null)).toBe('—');
  });
});

describe('initialTab', () => {
  it('opens Plan when the window cannot answer media queries', () => {
    expect(resolveInitialTab({})).toBe('plan');
  });

  it('opens Plan on a phone and Map otherwise', () => {
    expect(resolveInitialTab({ matchMedia: () => ({ matches: true }) })).toBe('plan');
    expect(resolveInitialTab({ matchMedia: () => ({ matches: false }) })).toBe('map');
  });

  it('reads the global window by default', () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false }));
    expect(resolveInitialTab()).toBe('map');
  });

  it('opens Plan with no window at all', () => {
    vi.stubGlobal('window', undefined);
    expect(resolveInitialTab()).toBe('plan');
  });
});

describe('locationTypes', () => {
  it('maps names to types and skips unnamed or untyped places and a null roster', () => {
    const map = buildLocationTypeMap([
      { name: 'A', locationType: ['SEASCAPE'] },
      { name: 'B' },
      { locationType: ['LANDSCAPE'] },
      null,
    ]);
    expect([...map.entries()]).toEqual([['A', ['SEASCAPE']]]);
    expect(buildLocationTypeMap(null).size).toBe(0);
  });

  it('labels known types and passes unknown ones through', () => {
    expect(locationTypeLabel('WATERFALL')).toBe('Waterfall');
    expect(locationTypeLabel('MYSTERY')).toBe('MYSTERY');
  });

  it('treats untyped as a sky candidate and woodland alone as not', () => {
    expect(isSkyPromptCandidate(null)).toBe(true);
    expect(isSkyPromptCandidate([])).toBe(true);
    expect(isSkyPromptCandidate(['WOODLAND'])).toBe(false);
    expect(isSkyPromptCandidate(['WOODLAND', 'SEASCAPE'])).toBe(true);
  });

  it('lists subject words in catalogue order from an array, a bare string or nothing', () => {
    expect(subjectWordsOf(['SEASCAPE', 'LANDSCAPE', 'BLUEBELL'])).toEqual(['Landscape', 'Seascape']);
    expect(subjectWordsOf('WILDLIFE')).toEqual(['Wildlife']);
    expect(subjectWordsOf(null)).toEqual([]);
  });

  it('identifies a pure wildlife hide from an array, a bare string or nothing', () => {
    expect(isWildlifeOnly(['WILDLIFE'])).toBe(true);
    expect(isWildlifeOnly('WILDLIFE')).toBe(true);
    expect(isWildlifeOnly(['WILDLIFE', 'LANDSCAPE'])).toBe(false);
    expect(isWildlifeOnly(null)).toBe(false);
    expect(isWildlifeOnly([])).toBe(false);
  });
});

describe('topicPalette', () => {
  it('styles a known type with its colour and a chip name', () => {
    expect(topicStyle('king_tide', 'ignored')).toMatchObject({
      colour: '#6FA8B0', glyph: '🌊', name: 'King tide', placeNoun: 'coastal',
    });
  });

  it('names an unknown type by its label, then its type, then a default', () => {
    expect(topicStyle('WEIRD', 'Weird thing')).toEqual({
      colour: DEFAULT_SWATCH_COLOR, glyph: '•', name: 'Weird thing',
    });
    expect(topicStyle('WEIRD').name).toBe('WEIRD');
    expect(topicStyle(null).name).toBe('Topic');
  });
});
