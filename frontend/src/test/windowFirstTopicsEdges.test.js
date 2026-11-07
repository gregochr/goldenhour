import { describe, it, expect, beforeEach, vi } from 'vitest';

// Loaded afresh in each test so the module-level type sets are read during a test.
let buildTopicIndex;
let chipClock;
let filterCalloutTopics;
let isWholeSkyTopic;
let topicWindowKeys;
let windowTopics;

beforeEach(async () => {
  vi.resetModules();
  ({
    buildTopicIndex, chipClock, filterCalloutTopics, isWholeSkyTopic, topicWindowKeys, windowTopics,
  } = await import('../utils/windowFirstTopics.js'));
});

const KEY = '2026-08-06:SUNSET';

const aurora = { type: 'AURORA', label: 'Aurora', rarityRank: 1 };

describe('windowTopics — sources that are missing or malformed', () => {
  it('answers no rows when the badge list is null or not a list', () => {
    expect(windowTopics(KEY, null, new Map(), [])).toEqual([]);
    expect(windowTopics(KEY, 'AURORA', new Map(), [])).toEqual([]);
  });

  it('skips empty slots in the badge list', () => {
    const rows = windowTopics(KEY, [null, aurora, undefined], new Map(), []);
    expect(rows).toHaveLength(1);
    expect(rows[0].badge).toBe(aurora);
  });

  it('copes with no topic index, and with one that cannot be asked', () => {
    expect(windowTopics(KEY, [aurora], null, [])[0].topic).toBeNull();
    expect(windowTopics(KEY, [aurora], undefined, [])[0].topic).toBeNull();
    expect(windowTopics(KEY, [aurora], {}, [])[0].topic).toBeNull();
  });

  it('pairs a badge that names nothing with no topic when the window has none', () => {
    const [row] = windowTopics(KEY, [{ rarityRank: 1 }], new Map(), []);
    expect(row.topic).toBeNull();
    expect(windowTopics(KEY, [{ rarityRank: 1 }], null, [])[0].topic).toBeNull();
  });

  it('pairs a badge with the topic of the same type and label, and not with a different label', () => {
    const matching = { type: 'AURORA', label: 'Aurora', date: '2026-08-06', eventType: 'SUNSET' };
    const other = { type: 'AURORA', label: 'Something else', date: '2026-08-06', eventType: 'SUNSET' };
    const index = buildTopicIndex([other, matching]);
    expect(windowTopics(KEY, [aurora], index, [])[0].topic).toBe(matching);
  });
});

describe('windowTopics — the row it builds', () => {
  it('carries empty scoped regions on a whole-sky topic', () => {
    const [row] = windowTopics(KEY, [aurora], new Map(), ['Northumberland']);
    expect(row.wholeSky).toBe(true);
    expect(row.regionsInScope).toBeNull();
    expect(row.scopedRegions).toEqual([]);
  });

  it('carries empty scoped regions on a region-scoped topic that names no regions', () => {
    const mist = { type: 'MIST', label: 'Mist', rarityRank: 2 };
    const topic = { type: 'MIST', label: 'Mist', date: '2026-08-06', eventType: 'SUNSET' };
    const [row] = windowTopics(KEY, [mist], buildTopicIndex([topic]), ['Northumberland']);
    expect(row.wholeSky).toBe(false);
    expect(row.regionsInScope).toBeNull();
    expect(row.scopedRegions).toEqual([]);
  });

  it('lists the in-scope regions of a region-scoped topic and drops it when none are in scope', () => {
    const mist = { type: 'MIST', label: 'Mist', rarityRank: 2 };
    const topic = {
      type: 'MIST', label: 'Mist', date: '2026-08-06', eventType: 'SUNSET', regions: ['Lakes', 'Northumberland'],
    };
    const index = buildTopicIndex([topic]);
    const [row] = windowTopics(KEY, [mist], index, ['Northumberland']);
    expect(row.regionsInScope).toBe(1);
    expect(row.scopedRegions).toEqual(['Northumberland']);
    expect(windowTopics(KEY, [mist], index, ['Elsewhere'])).toEqual([]);
  });

  it('sorts the rarest first and puts an unranked badge last', () => {
    const rare = { type: 'METEOR', label: 'Meteors', rarityRank: 1 };
    const common = { type: 'AURORA', label: 'Aurora', rarityRank: 5 };
    const unranked = { type: 'NLC', label: 'NLC' };
    const rows = windowTopics(KEY, [unranked, common, rare], new Map(), []);
    expect(rows.map((r) => r.badge.type)).toEqual(['METEOR', 'AURORA', 'NLC']);
  });
});

describe('buildTopicIndex', () => {
  it('answers an empty index for something that is not a list', () => {
    expect(buildTopicIndex(null).size).toBe(0);
    expect(buildTopicIndex('topics').size).toBe(0);
  });

  it('files a night topic under both of the windows it spans', () => {
    const night = { type: 'AURORA', label: 'Aurora', date: '2026-08-06', eventType: 'NIGHT' };
    const index = buildTopicIndex([night]);
    expect([...index.keys()].sort()).toEqual(['2026-08-06:SUNSET', '2026-08-07:SUNRISE']);
  });
});

describe('filterCalloutTopics', () => {
  it('keeps an empty slot rather than failing on it', () => {
    expect(filterCalloutTopics([null], false)).toEqual([null]);
  });

  it('drops day-scoped tide topics for an inland spot and keeps them for a coastal one', () => {
    const king = { type: 'KING_TIDE' };
    const mist = { type: 'MIST' };
    expect(filterCalloutTopics([king, mist], false)).toEqual([mist]);
    expect(filterCalloutTopics([king, mist], true)).toEqual([king, mist]);
  });

  it('answers an empty list for something that is not a list', () => {
    expect(filterCalloutTopics(null, true)).toEqual([]);
  });
});

describe('the topic type sets', () => {
  it('treats every region-scoped type as not whole-sky, and everything else as whole-sky', () => {
    const regional = ['KING_TIDE', 'SPRING_TIDE', 'STORM_SURGE', 'INVERSION', 'MIST', 'SEA_MIST', 'DUST',
      'SNOW_TOPS', 'SNOW_MIST', 'SNOW_FRESH', 'CLEARANCE', 'BLUEBELL'];
    regional.forEach((type) => expect(isWholeSkyTopic(type)).toBe(false));
    ['AURORA', 'NLC', 'METEOR', 'SUPERMOON', 'EQUINOX', 'ECLIPSE', 'LUNAR_ECLIPSE', 'UNKNOWN']
      .forEach((type) => expect(isWholeSkyTopic(type)).toBe(true));
    expect(isWholeSkyTopic('mist')).toBe(false);
    expect(isWholeSkyTopic(null)).toBe(true);
  });

  it('files a tide topic under both windows of its day whatever its event type, and others by event', () => {
    expect(topicWindowKeys({ type: 'KING_TIDE', date: '2026-08-06' }))
      .toEqual(['2026-08-06:SUNRISE', '2026-08-06:SUNSET']);
    expect(topicWindowKeys({ type: 'spring_tide', date: '2026-08-06', eventType: 'SUNSET' }))
      .toEqual(['2026-08-06:SUNRISE', '2026-08-06:SUNSET']);
    expect(topicWindowKeys({ type: 'MIST', date: '2026-08-06', eventType: 'SUNSET' })).toEqual(['2026-08-06:SUNSET']);
    expect(topicWindowKeys({ type: 'MIST', date: '2026-08-06' })).toEqual([]);
  });

  it('prints a clock only on the eclipse chips', () => {
    expect(chipClock({ type: 'ECLIPSE', eventTime: '21:10' })).toBe('21:10');
    expect(chipClock({ type: 'lunar_eclipse', eventTime: '21:10' })).toBe('21:10');
    expect(chipClock({ type: 'ECLIPSE' })).toBeNull();
    expect(chipClock({ type: 'AURORA', eventTime: '21:10' })).toBeNull();
    expect(chipClock(null)).toBeNull();
  });
});
