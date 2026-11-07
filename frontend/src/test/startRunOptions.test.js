import { describe, it, expect } from 'vitest';
import {
  START_GROUPS, BUSY_MESSAGE, isBatchOption, tideDataDescription, isCoastal,
  getLocationSummary, getSeascapeTideSummary,
} from '../utils/startRunOptions.js';

const loc = (name, locationType, tideType, extra = {}) => ({ name, locationType, tideType, ...extra });

describe('START_GROUPS', () => {
  it('lists the options in display order across the two groups', () => {
    expect(START_GROUPS.map((g) => g.id)).toEqual(['forecast', 'data']);
    expect(START_GROUPS.flatMap((g) => g.options.map((o) => o.key))).toEqual([
      'scheduled-batch', 'jfdi-batch', 'briefing', 'aurora-forecast', 'tide-data', 'tide-history', 'light-pollution',
    ]);
  });

  it('every option has a "Start ..." label', () => {
    for (const o of START_GROUPS.flatMap((g) => g.options)) {
      expect(o.startLabel.startsWith('Start ')).toBe(true);
    }
  });
});

describe('isBatchOption', () => {
  it.each([
    ['scheduled-batch', true], ['jfdi-batch', true], ['briefing', false], ['tide-data', false], [undefined, false],
  ])('%s -> %s', (key, expected) => expect(isBatchOption(key)).toBe(expected));
});

describe('tideDataDescription', () => {
  it.each([
    [null, 'Coastal locations from WorldTides.'],
    [undefined, 'Coastal locations from WorldTides.'],
    [0, '0 coastal locations from WorldTides.'],
    [1, '1 coastal location from WorldTides.'],
    [12, '12 coastal locations from WorldTides.'],
  ])('%s', (count, text) => expect(tideDataDescription(count)).toBe(text));
});

describe('isCoastal', () => {
  it.each([
    [{ tideType: ['HIGH'] }, true], [{ tideType: [] }, false], [{}, false], [{ tideType: null }, false],
  ])('%j -> %s', (l, expected) => expect(isCoastal(l)).toBe(expected));
});

describe('getLocationSummary', () => {
  const locations = [
    loc('Bamburgh', ['SEASCAPE'], ['HIGH'], { region: { name: 'Northumberland' } }),
    loc('Alnwick', ['LANDSCAPE'], [], { region: { name: 'Northumberland' } }),
    loc('Seal Spot', ['WILDLIFE'], ['LOW'], { region: { name: 'Cumbria' } }),
    loc('Orphan', ['LANDSCAPE'], [], {}),
    loc('Disabled', ['LANDSCAPE'], [], { enabled: false, region: { name: 'Cumbria' } }),
    loc('Untyped', undefined, undefined, { region: { name: 'Cumbria' } }),
  ];

  it('default run type lists colour-type locations, untyped included, disabled excluded', () => {
    const { groups, total } = getLocationSummary(locations, 'SUNRISE');
    expect(total).toBe(4);
    expect(groups).toEqual([
      { region: 'Cumbria', locations: ['Untyped'] },
      { region: 'No region', locations: ['Orphan'] },
      { region: 'Northumberland', locations: ['Alnwick', 'Bamburgh'] },
    ]);
  });

  it('TIDE lists coastal locations only, whatever their type', () => {
    const { groups, total } = getLocationSummary(locations, 'TIDE');
    expect(total).toBe(2);
    expect(groups.map((g) => g.locations)).toEqual([['Seal Spot'], ['Bamburgh']]);
  });

  it('WEATHER lists pure-wildlife locations only', () => {
    const withWood = [
      ...locations,
      loc('Wood Hide', ['WILDLIFE', 'WOODLAND'], [], { region: { name: 'Cumbria' } }),
      loc('Mixed', ['WILDLIFE', 'LANDSCAPE'], [], { region: { name: 'Cumbria' } }),
    ];
    const { groups, total } = getLocationSummary(withWood, 'WEATHER');
    expect(total).toBe(2);
    expect(groups).toEqual([{ region: 'Cumbria', locations: ['Seal Spot', 'Wood Hide'] }]);
  });

  it('an empty list gives no groups', () => {
    expect(getLocationSummary([], 'TIDE')).toEqual({ groups: [], total: 0 });
  });
});

describe('getSeascapeTideSummary', () => {
  it('keeps only coastal locations that are also SEASCAPE', () => {
    const { groups, total } = getSeascapeTideSummary([
      loc('A', ['SEASCAPE'], ['HIGH'], { region: { name: 'R' } }),
      loc('B', ['LANDSCAPE'], ['HIGH'], { region: { name: 'R' } }),
      loc('C', ['SEASCAPE'], [], { region: { name: 'R' } }),
      loc('D', undefined, ['HIGH'], { region: { name: 'R' } }),
    ]);
    expect(total).toBe(1);
    expect(groups).toEqual([{ region: 'R', locations: ['A'] }]);
  });
});

describe('BUSY_MESSAGE', () => {
  it('is the 409 sentence', () => {
    expect(BUSY_MESSAGE).toBe('A run is already in progress. Wait for it to finish.');
  });
});
