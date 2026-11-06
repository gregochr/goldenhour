import { describe, it, expect, vi } from 'vitest';

/**
 * Edge behaviour of the four-day topic tiles' jump rule and its lookup tables: the topic-type to
 * almanac-type map, the tide-only span rule, malformed almanac entries, tie-breaking by start date and the
 * relative-day labels. Companion to fourDayTiles.test.js; each case pins an answer the happy-path tests never
 * ask for.
 *
 * <p>The module is re-imported inside each test so its module-level tables execute under that test (a
 * table built once at file load is attributed to no test by mutation testing).
 */

const TODAY = '2026-10-24';
const SUN = '2026-10-25';
const MON = '2026-10-26';
const TUE = '2026-10-27';

async function load() {
  vi.resetModules();
  return import('../utils/fourDayTiles.js');
}

const topic = (type, date, eventType, over = {}) => ({ type, label: type, date, eventType, ...over });
const entry = (over = {}) => ({
  id: 'e', type: 'spring-tide', startDate: TODAY, endDate: '2026-10-30', coincidence: [], dayRanges: [], ...over,
});
const jumpOf = (result, date) => result.days.find((d) => d.date === date).chips[0].jump;

describe('the tables', () => {
  it('maps each topic type to its almanac types', async () => {
    const { ALMANAC_TYPES_BY_TOPIC } = await load();
    expect(ALMANAC_TYPES_BY_TOPIC).toEqual({
      KING_TIDE: ['king-tide', 'spring-tide'],
      SPRING_TIDE: ['king-tide', 'spring-tide'],
      SUPERMOON: ['supermoon'],
      METEOR: ['meteor'],
      NLC: ['nlc-season'],
      EQUINOX: ['equinox'],
      ECLIPSE: ['eclipse'],
      LUNAR_ECLIPSE: ['lunar-eclipse'],
    });
  });

  it('jumps each topic type to the entry of its own almanac type only', async () => {
    const { buildFourDayTiles } = await load();
    const almanac = ['king-tide', 'spring-tide', 'supermoon', 'meteor', 'nlc-season', 'equinox', 'eclipse',
      'lunar-eclipse'];
    const entries = almanac.map((type) => entry({ id: `id-${type}`, type }));
    const expected = {
      KING_TIDE: 'id-king-tide', SPRING_TIDE: 'id-king-tide', SUPERMOON: 'id-supermoon', METEOR: 'id-meteor',
      NLC: 'id-nlc-season', EQUINOX: 'id-equinox', ECLIPSE: 'id-eclipse', LUNAR_ECLIPSE: 'id-lunar-eclipse',
    };
    for (const [type, id] of Object.entries(expected)) {
      const result = buildFourDayTiles({
        todayStr: TODAY, hotTopics: [topic(type, SUN, 'SUNSET')], entries, renderedEntryIds: entries.map((e) => e.id),
      });
      expect(jumpOf(result, SUN), type).toEqual({ entryId: id });
    }
    // With the first-listed tide entry removed, the other tide type still serves both tide topics.
    const spring = entries.filter((e) => e.type !== 'king-tide');
    for (const type of ['KING_TIDE', 'SPRING_TIDE']) {
      const result = buildFourDayTiles({
        todayStr: TODAY, hotTopics: [topic(type, SUN, 'SUNSET')], entries: spring,
        renderedEntryIds: spring.map((e) => e.id),
      });
      expect(jumpOf(result, SUN), type).toEqual({ entryId: 'id-spring-tide' });
    }
  });

  it('reads a merged entry\'s days from dayRanges for BOTH tide topics, never its union dates', async () => {
    const { buildFourDayTiles } = await load();
    const merged = entry({
      id: 'm', type: 'supermoon', coincidence: [{ type: 'spring-tide' }, { type: 'king-tide' }],
      dayRanges: [{ date: SUN }, { date: MON }],
    });
    for (const type of ['KING_TIDE', 'SPRING_TIDE']) {
      const result = buildFourDayTiles({
        todayStr: TODAY, hotTopics: [topic(type, SUN, null), topic(type, TUE, null)], entries: [merged],
        renderedEntryIds: ['m'],
      });
      expect(jumpOf(result, SUN), type).toEqual({ entryId: 'm' });
      expect(jumpOf(result, TUE), type).toBeNull();
    }
  });

  it('reads a non-tide topic\'s span from the entry\'s own dates even when the entry is merged', async () => {
    const { buildFourDayTiles } = await load();
    const merged = entry({
      id: 'm', type: 'supermoon', coincidence: [{ type: 'spring-tide' }], dayRanges: [], endDate: TUE,
    });
    const result = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [topic('SUPERMOON', TUE, 'SUNSET')], entries: [merged], renderedEntryIds: ['m'],
    });
    expect(jumpOf(result, TUE)).toEqual({ entryId: 'm' });
  });

  it('labels the four days, long and short', async () => {
    const { buildFourDayTiles } = await load();
    const { days } = buildFourDayTiles({ todayStr: TODAY, hotTopics: [] });
    expect(days.map((d) => d.relative)).toEqual(['today', 'tomorrow', 'in 2 days', 'in 3 days']);
    expect(days.map((d) => d.relativeShort)).toEqual(['today', 'tmrw', '+2', '+3']);
  });
});

describe('which entries can be jumped to', () => {
  it('ignores an entry of an unrelated type that happens to cover the date', async () => {
    const { buildFourDayTiles } = await load();
    const other = entry({ id: 'other', type: 'meteor' });
    const result = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [topic('SUPERMOON', SUN, 'SUNSET')], entries: [other], renderedEntryIds: ['other'],
    });
    expect(jumpOf(result, SUN)).toBeNull();
  });

  it('finds the topic in any coincidence line, and skips a null line', async () => {
    const { buildFourDayTiles } = await load();
    const host = entry({
      id: 'host', type: 'meteor', coincidence: [null, { type: 'other' }, { type: 'supermoon' }],
    });
    const result = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [topic('SUPERMOON', SUN, 'SUNSET')], entries: [host], renderedEntryIds: ['host'],
    });
    expect(jumpOf(result, SUN)).toEqual({ entryId: 'host' });
  });

  it('needs only one coincidence line to match, not all of them', async () => {
    const { buildFourDayTiles } = await load();
    const host = entry({ id: 'host', type: 'meteor', coincidence: [{ type: 'supermoon' }, { type: 'other' }] });
    const result = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [topic('SUPERMOON', SUN, 'SUNSET')], entries: [host], renderedEntryIds: ['host'],
    });
    expect(jumpOf(result, SUN)).toEqual({ entryId: 'host' });
  });

  it('gives an unrelated entry with no coincidence field no jump', async () => {
    const { buildFourDayTiles } = await load();
    const bare = { id: 'bare', type: 'meteor', startDate: TODAY, endDate: TUE };
    const result = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [topic('SUPERMOON', SUN, 'SUNSET')], entries: [bare], renderedEntryIds: ['bare'],
    });
    expect(jumpOf(result, SUN)).toBeNull();
  });

  it('skips null, id-less and unrendered members without throwing', async () => {
    const { buildFourDayTiles } = await load();
    const idless = entry({ id: undefined, type: 'supermoon' });
    const nullId = entry({ id: null, type: 'supermoon' });
    const real = entry({ id: 'real', type: 'supermoon' });
    const result = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [topic('SUPERMOON', SUN, 'SUNSET')],
      entries: [null, undefined, idless, nullId, real],
      renderedEntryIds: [undefined, null, 'real'],
    });
    expect(jumpOf(result, SUN)).toEqual({ entryId: 'real' });
    const none = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [topic('SUPERMOON', SUN, 'SUNSET')], entries: [null, idless, nullId],
      renderedEntryIds: [undefined, null],
    });
    expect(jumpOf(none, SUN)).toBeNull();
  });

  it('treats omitted rendered ids as nothing rendered', async () => {
    const { buildFourDayTiles } = await load();
    const sneaky = entry({ id: 'Stryker was here', type: 'supermoon' });
    for (const renderedEntryIds of [undefined, null]) {
      const result = buildFourDayTiles({
        todayStr: TODAY, hotTopics: [topic('SUPERMOON', SUN, 'SUNSET')], entries: [sneaky], renderedEntryIds,
      });
      expect(jumpOf(result, SUN)).toBeNull();
    }
  });

  it('gives no jump when entries is not an array', async () => {
    const { buildFourDayTiles } = await load();
    const result = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [topic('SUPERMOON', SUN, 'SUNSET')], entries: 'x', renderedEntryIds: ['x'],
    });
    expect(jumpOf(result, SUN)).toBeNull();
  });
});

describe('a non-tide entry\'s span', () => {
  it('covers its first and last day inclusive, and nothing past them', async () => {
    const { buildFourDayTiles } = await load();
    const moon = entry({ id: 'moon', type: 'supermoon', startDate: SUN, endDate: MON });
    const result = buildFourDayTiles({
      todayStr: TODAY,
      hotTopics: [SUN, MON, TUE].map((d) => topic('SUPERMOON', d, 'SUNSET')),
      entries: [moon], renderedEntryIds: ['moon'],
    });
    expect(jumpOf(result, SUN)).toEqual({ entryId: 'moon' });
    expect(jumpOf(result, MON)).toEqual({ entryId: 'moon' });
    expect(jumpOf(result, TUE)).toBeNull();
  });

  it('treats an entry with no endDate as a single day', async () => {
    const { buildFourDayTiles } = await load();
    const oneDay = entry({ id: 'one', type: 'supermoon', startDate: SUN, endDate: undefined });
    const result = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [SUN, MON].map((d) => topic('SUPERMOON', d, 'SUNSET')),
      entries: [oneDay], renderedEntryIds: ['one'],
    });
    expect(jumpOf(result, SUN)).toEqual({ entryId: 'one' });
    expect(jumpOf(result, MON)).toBeNull();
  });

  it('gives an entry with no startDate no jump', async () => {
    const { buildFourDayTiles } = await load();
    const undated = entry({ id: 'u', type: 'supermoon', startDate: undefined, endDate: TUE });
    const result = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [topic('SUPERMOON', SUN, 'SUNSET')], entries: [undated], renderedEntryIds: ['u'],
    });
    expect(jumpOf(result, SUN)).toBeNull();
  });
});

describe('a tide entry\'s span', () => {
  it('uses an unmerged entry\'s own start and end dates, and none without both', async () => {
    const { buildFourDayTiles } = await load();
    const run = (over) => buildFourDayTiles({
      todayStr: TODAY, hotTopics: [topic('SPRING_TIDE', SUN, null)], entries: [entry({ id: 't', ...over })],
      renderedEntryIds: ['t'],
    });
    expect(jumpOf(run({ startDate: SUN, endDate: SUN }), SUN)).toEqual({ entryId: 't' });
    expect(jumpOf(run({ startDate: SUN, endDate: undefined }), SUN)).toBeNull();
    expect(jumpOf(run({ startDate: undefined, endDate: TUE }), SUN)).toBeNull();
  });

  it('reads dayRanges that are unsorted, hold nulls and hold dateless members', async () => {
    const { buildFourDayTiles } = await load();
    const merged = entry({
      id: 'm', type: 'supermoon', coincidence: [{ type: 'spring-tide' }],
      dayRanges: [{ date: MON }, null, {}, { date: SUN }, { date: TUE, shoulder: true }, undefined],
    });
    const result = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [TODAY, SUN, MON, TUE].map((d) => topic('SPRING_TIDE', d, null)),
      entries: [merged], renderedEntryIds: ['m'],
    });
    expect(jumpOf(result, TODAY)).toBeNull();
    expect(jumpOf(result, SUN)).toEqual({ entryId: 'm' });
    expect(jumpOf(result, MON)).toEqual({ entryId: 'm' });
    expect(jumpOf(result, TUE)).toBeNull();
  });

  it('gives a merged entry whose only dayRanges are shoulders or dateless no span', async () => {
    const { buildFourDayTiles } = await load();
    const merged = entry({
      id: 'm', type: 'supermoon', coincidence: [{ type: 'spring-tide' }],
      dayRanges: [{ date: SUN, shoulder: true }, {}],
    });
    const result = buildFourDayTiles({
      todayStr: TODAY, hotTopics: [topic('SPRING_TIDE', SUN, null)], entries: [merged], renderedEntryIds: ['m'],
    });
    expect(jumpOf(result, SUN)).toBeNull();
  });
});

describe('the earliest-starting entry wins', () => {
  it('picks the earliest start, then the first listed, whatever the input order', async () => {
    const { buildFourDayTiles } = await load();
    const mk = (id, startDate) => entry({ id, type: 'supermoon', startDate, endDate: '2026-10-30' });
    const pool = [mk('a1', TODAY), mk('a2', TODAY), mk('b', SUN), mk('c', MON)];
    const permute = (items) => (items.length <= 1 ? [items] : items.flatMap((item, i) => permute(
      [...items.slice(0, i), ...items.slice(i + 1)],
    ).map((rest) => [item, ...rest])));
    for (const order of permute(pool)) {
      const result = buildFourDayTiles({
        todayStr: TODAY, hotTopics: [topic('SUPERMOON', MON, 'SUNSET')], entries: order,
        renderedEntryIds: order.map((e) => e.id),
      });
      const expected = order.find((e) => e.id === 'a1' || e.id === 'a2').id;
      expect(jumpOf(result, MON), order.map((e) => e.id).join()).toEqual({ entryId: expected });
    }
  });

  it('orders by start date when no entry ties', async () => {
    const { buildFourDayTiles } = await load();
    const mk = (id, startDate) => entry({ id, type: 'supermoon', startDate, endDate: '2026-10-30' });
    for (const order of [
      [mk('x', MON), mk('y', SUN), mk('z', TODAY)],
      [mk('z', TODAY), mk('x', MON), mk('y', SUN)],
      [mk('y', SUN), mk('z', TODAY), mk('x', MON)],
      [mk('z', TODAY), mk('y', SUN), mk('x', MON)],
    ]) {
      const result = buildFourDayTiles({
        todayStr: TODAY, hotTopics: [topic('SUPERMOON', MON, 'SUNSET')], entries: order,
        renderedEntryIds: ['x', 'y', 'z'],
      });
      expect(jumpOf(result, MON)).toEqual({ entryId: 'z' });
    }
  });
});
