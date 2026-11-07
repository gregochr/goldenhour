import { describe, it, expect } from 'vitest';
import { buildFourDayTiles, buildGlanceLanes, ALMANAC_TYPES_BY_TOPIC } from '../utils/fourDayTiles.js';

/**
 * The "Next four days" tiles model (coming-up-glance-plan §3 T1), now feeding At a Glance's days-ahead lanes
 * (days-ahead-lanes-plan.md). Pure data in, tiles out; the component has its own suite for markup, names and taps.
 *
 * <p>TODAY is Sat 24 Oct 2026, passed in — no wall clock — so the four days are Sat 24, Sun 25,
 * Mon 26 and Tue 27 (the last Plan day), and "the next run" in a list is a November one.
 */
const TODAY = '2026-10-24';
const SUN = '2026-10-25';
const MON = '2026-10-26';
const TUE = '2026-10-27';

const glance = (over = {}) => ({ weather: false, ...over });
const topic = (type, date, eventType, over = {}) => ({
  type, label: type, date, eventType, ...over,
});
const tide = (date, over = {}) => topic('SPRING_TIDE', date, null, {
  tideRun: { dayNumber: 1, dayCount: 4, peak: false }, ...over,
});
/** Day {@code n} of a {@code count}-day spring run, the way the server serves it (the run row says which day). */
const tideRun = (date, n, count = 3, over = {}) => topic('SPRING_TIDE', date, null, {
  tideRun: { runLabel: 'SPRING RUN', dayNumber: n, dayCount: count },
  glance: glance({ value: `day ${n}` }),
  ...over,
});
const wx = (type, date, eventType = 'SUNRISE', over = {}) => topic(type, date, eventType, {
  glance: glance({ weather: true, value: 'dawn · 9/10' }), ...over,
});

const tiles = (over = {}) => buildFourDayTiles({ todayStr: TODAY, hotTopics: [], ...over });
const chipsOf = (result, date) => result.days.find((d) => d.date === date).chips;

const entry = (over = {}) => ({
  id: 'spring-tide:2026-10-24:2026-10-30',
  type: 'spring-tide',
  startDate: TODAY,
  endDate: '2026-10-30',
  coincidence: [],
  dayRanges: [],
  ...over,
});

describe('buildFourDayTiles — the four days', () => {
  it('always has four tiles, with the weekday, day number and both relative forms', () => {
    const { days } = tiles();
    expect(days.map((d) => d.date)).toEqual([TODAY, SUN, MON, TUE]);
    expect(days.map((d) => d.dow)).toEqual(['Sat', 'Sun', 'Mon', 'Tue']);
    expect(days.map((d) => d.dayNum)).toEqual(['24', '25', '26', '27']);
    expect(days.map((d) => d.relative)).toEqual(['today', 'tomorrow', 'in 2 days', 'in 3 days']);
    expect(days.map((d) => d.relativeShort)).toEqual(['today', 'tmrw', '+2', '+3']);
    expect(days.map((d) => d.today)).toEqual([true, false, false, false]);
  });

  it('has no days at all without a today', () => {
    expect(buildFourDayTiles({ todayStr: null, hotTopics: [] })).toEqual({ days: [], topicCount: null });
  });

  it('distinguishes "not arrived" (topicCount null) from "arrived empty" (0)', () => {
    expect(buildFourDayTiles({ todayStr: TODAY, hotTopics: undefined }).topicCount).toBeNull();
    expect(buildFourDayTiles({ todayStr: TODAY, hotTopics: undefined }).days).toHaveLength(4);
    const empty = buildFourDayTiles({ todayStr: TODAY, hotTopics: [] });
    expect(empty.topicCount).toBe(0);
    expect(empty.days.every((d) => d.chips.length === 0)).toBe(true);
  });

  it('counts distinct types across the four tiles, not chips', () => {
    const result = tiles({
      hotTopics: [tide(TODAY), tide(SUN), tide(MON), topic('DUST', SUN, 'SUNSET'),
        topic('AURORA', TODAY, 'NIGHT'), topic('DUST', '2026-10-30', 'SUNSET')],
    });
    // Tide, dust and aurora; the 30 Oct dust is outside the window.
    expect(result.topicCount).toBe(3);
  });

  it('drops a topic whose every date is outside the four days', () => {
    const result = tiles({ hotTopics: [topic('DUST', '2026-10-28', 'SUNSET'), topic('DUST', '2026-10-23', 'SUNSET')] });
    expect(result.topicCount).toBe(0);
  });
});

describe('buildFourDayTiles — one chip per type per day', () => {
  it('lands a NIGHT topic on its date (sunset) and the next morning (sunrise)', () => {
    const result = tiles({ hotTopics: [topic('AURORA', TODAY, 'NIGHT')] });
    expect(chipsOf(result, TODAY).map((c) => [c.type, c.events])).toEqual([['AURORA', ['SUNSET']]]);
    expect(chipsOf(result, SUN).map((c) => [c.type, c.events])).toEqual([['AURORA', ['SUNRISE']]]);
    expect(chipsOf(result, MON)).toEqual([]);
  });

  it('lands a yesterday-dated NIGHT topic on this morning only', () => {
    const result = tiles({ hotTopics: [topic('AURORA', '2026-10-23', 'NIGHT')] });
    expect(chipsOf(result, TODAY).map((c) => c.events)).toEqual([['SUNRISE']]);
    expect(result.topicCount).toBe(1);
  });

  it('makes one chip per type, taking the first topic in served order', () => {
    const result = tiles({
      hotTopics: [
        topic('DUST', SUN, 'SUNSET', { label: 'First dust' }),
        topic('DUST', SUN, 'SUNRISE', { label: 'Second dust' }),
      ],
    });
    const chips = chipsOf(result, SUN);
    expect(chips).toHaveLength(1);
    expect(chips[0].key).toBe(`${SUN}:DUST`);
    // Both windows are listed, in morning-then-evening order.
    expect(chips[0].events).toEqual(['SUNRISE', 'SUNSET']);
  });

  it('takes the glance from the first topic of the type that has one', () => {
    const result = tiles({
      hotTopics: [
        topic('STORM_SURGE', SUN, null),
        topic('STORM_SURGE', SUN, null, {
          glance: glance({ value: 'HW 16:02 · +0.4 m', badge: 'PEAK' }),
        }),
      ],
    });
    const [chip] = chipsOf(result, SUN);
    expect(chip.value).toBe('HW 16:02 · +0.4 m');
    expect(chip.badge).toBe('PEAK');
    expect(chip.weather).toBe(false);
  });

  it('prints the surge PEAK whichever event carries it', () => {
    for (const eventType of ['SUNRISE', 'SUNSET']) {
      const result = tiles({
        hotTopics: [
          topic('STORM_SURGE', SUN, eventType === 'SUNRISE' ? 'SUNSET' : 'SUNRISE'),
          topic('STORM_SURGE', SUN, eventType, { glance: glance({ badge: 'PEAK', value: 'x' }) }),
        ],
      });
      expect(chipsOf(result, SUN)[0].badge, eventType).toBe('PEAK');
    }
  });

  it('makes a topic with no glance a name-only chip that is not weather', () => {
    const [chip] = chipsOf(tiles({ hotTopics: [topic('DUST', SUN, 'SUNSET')] }), SUN);
    expect(chip).toMatchObject({
      type: 'DUST', name: 'Dust', value: null, badge: null,
      weather: false, mapDoor: null, jump: null,
    });
  });

  it('copies the served glance without deriving anything', () => {
    const [chip] = chipsOf(tiles({
      hotTopics: [topic('SUPERMOON', SUN, 'SUNSET', {
        glance: glance({ value: 'rise 16:34', badge: 'FULL' }),
      })],
    }), SUN);
    expect(chip).toMatchObject({ value: 'rise 16:34', badge: 'FULL' });
  });

  it('puts non-weather chips first, then weather chips, each group in served order', () => {
    const result = tiles({
      hotTopics: [
        wx('MIST', SUN), tide(SUN), wx('INVERSION', SUN), topic('DUST', SUN, 'SUNSET'),
      ],
    });
    expect(chipsOf(result, SUN).map((c) => c.type))
      .toEqual(['SPRING_TIDE', 'DUST', 'MIST', 'INVERSION']);
  });

  it('gives a chip the identity of its event: the topic itself, or the tide run it is a day of', () => {
    const result = tiles({
      hotTopics: [wx('MIST', SUN), wx('MIST', MON), tideRun(SUN, 1), tideRun(MON, 2)],
    });
    const mist = [chipsOf(result, SUN), chipsOf(result, MON)].map((chips) => chips.find((c) => c.type === 'MIST'));
    expect(mist.map((c) => c.eventId)).toEqual([`MIST:${SUN}`, `MIST:${MON}`]);
    const runs = [chipsOf(result, SUN), chipsOf(result, MON)].map((chips) => chips.find((c) => c.type === 'SPRING_TIDE'));
    expect(runs[0].eventId).toBe(`TIDE:SPRING RUN:3:${SUN}`);
    expect(runs[1].eventId).toBe(runs[0].eventId);
  });

  it('identifies a tide with no served run row by its own date, and does not call two such days one event', () => {
    const bare = (date) => topic('SPRING_TIDE', date, null);
    const result = tiles({ hotTopics: [bare(SUN), bare(MON)] });
    expect(chipsOf(result, SUN)[0].eventId).toBe(`SPRING_TIDE:${SUN}`);
    expect(chipsOf(result, MON)[0].eventId).toBe(`SPRING_TIDE:${MON}`);
  });

  it('marks the next-morning half of a NIGHT topic as not covering that day, and its own night as covering', () => {
    const result = tiles({ hotTopics: [wx('AURORA', TODAY, 'NIGHT')] });
    expect(chipsOf(result, TODAY)[0].coversDay).toBe(true);
    expect(chipsOf(result, SUN)[0].coversDay).toBe(false);
  });

  it('lets only the covering topic speak for a chip when last night\'s aurora is merged beside tonight\'s', () => {
    const lastNight = wx('AURORA', '2026-10-23', 'NIGHT', { glance: glance({ weather: true, value: 'last night' }) });
    const tonight = wx('AURORA', TODAY, 'NIGHT', { glance: glance({ weather: true, value: 'tonight' }) });
    const [chip] = chipsOf(tiles({ hotTopics: [lastNight, tonight] }), TODAY);
    expect(chip).toMatchObject({ coversDay: true, value: 'tonight', eventId: `AURORA:${TODAY}` });
  });

  it('names the chip from the palette, with no phone short forms', () => {
    const [chip] = chipsOf(tiles({ hotTopics: [tide(SUN)] }), SUN);
    expect(chip).toMatchObject({ name: 'Spring tide', colour: '#6FA8B0' });
    expect(chip).not.toHaveProperty('chipName');
    expect(chip).not.toHaveProperty('shortValue');
  });
});

describe('buildFourDayTiles — the tap target', () => {
  it('is the first Plan window key on that date that Plan renders', () => {
    const result = tiles({
      hotTopics: [tide(SUN)],
      openableKeys: new Set([`${SUN}:SUNSET`]),
    });
    expect(chipsOf(result, SUN)[0].target).toBe(`${SUN}:SUNSET`);
  });

  it('is null when Plan renders none of them', () => {
    const result = tiles({ hotTopics: [tide(SUN)], openableKeys: new Set(['2026-10-26:SUNSET']) });
    expect(chipsOf(result, SUN)[0].target).toBeNull();
  });

  it('lands a yesterday-dated NIGHT topic on this morning\'s sunrise key', () => {
    const result = tiles({
      hotTopics: [topic('AURORA', '2026-10-23', 'NIGHT')],
      openableKeys: [`${TODAY}:SUNRISE`, '2026-10-23:SUNSET'],
    });
    expect(chipsOf(result, TODAY)[0].target).toBe(`${TODAY}:SUNRISE`);
  });
});

describe('buildFourDayTiles — the jump (§2.3: the entry\'s own span must cover the chip\'s date)', () => {
  const NOV_RUN = entry({
    id: 'spring-tide:2026-11-08:2026-11-10', startDate: '2026-11-08', endDate: '2026-11-10',
  });

  it('⚠️ gives a Sunday tide chip NO jump when its own run has no rendered entry (filtered out) and only a November run is rendered', () => {
    const result = tiles({
      // A run of two days: Sunday is day 1 of 2, Monday day 2 of 2.
      hotTopics: [
        tide(SUN, { tideRun: { dayNumber: 1, dayCount: 2, peak: false } }),
        tide(MON, { tideRun: { dayNumber: 2, dayCount: 2, peak: true } }),
      ],
      entries: [NOV_RUN],
      renderedEntryIds: new Set([NOV_RUN.id]),
    });
    expect(chipsOf(result, SUN)[0].jump).toBeNull();
    expect(chipsOf(result, MON)[0].jump).toBeNull();
  });

  it('a chip on a date covered by a rendered entry wholly inside the four days gets a jump, which the chip prefers over its Glance target', () => {
    const near = entry({
      id: 'spring-tide:2026-10-25:2026-10-26', startDate: SUN, endDate: MON,
    });
    const result = tiles({
      hotTopics: [
        tide(SUN, { tideRun: { dayNumber: 1, dayCount: 2, peak: false } }),
        tide(MON, { tideRun: { dayNumber: 2, dayCount: 2, peak: true } }),
      ],
      entries: [near],
      renderedEntryIds: new Set([near.id]),
      openableKeys: new Set([`${SUN}:SUNSET`, `${MON}:SUNSET`]),
    });
    for (const date of [SUN, MON]) {
      const chip = chipsOf(result, date)[0];
      // The Glance target is still computed, but the component prefers `jump` (WindowGlanceLanes).
      expect(chip.target, date).toBe(`${date}:SUNSET`);
      expect(chip.jump, date).toEqual({ entryId: near.id });
    }
  });

  it('jumps on every in-window day a straddling run covers', () => {
    const run = entry();
    const result = tiles({
      hotTopics: [tide(TODAY), tide(SUN), tide(MON), tide(TUE)],
      entries: [NOV_RUN, run],
      renderedEntryIds: new Set([run.id, NOV_RUN.id]),
    });
    for (const date of [TODAY, SUN, MON, TUE]) {
      expect(chipsOf(result, date)[0].jump, date).toEqual({ entryId: run.id });
    }
  });

  it('does not jump to an entry the list is not rendering', () => {
    const result = tiles({
      hotTopics: [tide(SUN)], entries: [entry()], renderedEntryIds: new Set(),
    });
    expect(chipsOf(result, SUN)[0].jump).toBeNull();
  });

  it('does not jump before the run starts', () => {
    const run = entry({ id: 'later', startDate: MON, endDate: '2026-10-30' });
    const result = tiles({
      hotTopics: [tide(SUN), tide(MON)], entries: [run], renderedEntryIds: [run.id],
    });
    expect(chipsOf(result, SUN)[0].jump).toBeNull();
    expect(chipsOf(result, MON)[0].jump).toEqual({ entryId: 'later' });
  });

  it('picks the earliest-starting entry when two rendered entries cover the date, whatever the order', () => {
    const early = entry({ id: 'early', startDate: TODAY, endDate: '2026-10-30' });
    const late = entry({ id: 'late', startDate: SUN, endDate: '2026-10-30' });
    const result = tiles({
      hotTopics: [tide(MON)],
      entries: [late, early],
      renderedEntryIds: ['late', 'early'],
    });
    expect(chipsOf(result, MON)[0].jump).toEqual({ entryId: 'early' });
  });

  it('stops an unmerged tide entry at its own endDate', () => {
    const ends = entry({ id: 'ends', startDate: TODAY, endDate: SUN });
    const result = tiles({
      hotTopics: [tide(SUN), tide(MON)], entries: [ends], renderedEntryIds: ['ends'],
    });
    expect(chipsOf(result, SUN)[0].jump).toEqual({ entryId: 'ends' });
    expect(chipsOf(result, MON)[0].jump).toBeNull();
  });

  it('reads a merged entry\'s tide days from dayRanges, never its union dates', () => {
    // A tide that ends on Monday, merged into a supermoon card that runs on to the 30th.
    const merged = entry({
      id: 'supermoon:2026-10-24:2026-10-30', type: 'supermoon', endDate: '2026-10-30',
      coincidence: [{ type: 'spring-tide' }],
      dayRanges: [{ date: TODAY }, { date: SUN }, { date: MON }],
    });
    const result = tiles({
      hotTopics: [tide(SUN), tide(MON), tide(TUE)],
      entries: [merged],
      renderedEntryIds: [merged.id],
    });
    expect(chipsOf(result, SUN)[0].jump).toEqual({ entryId: merged.id });
    expect(chipsOf(result, MON)[0].jump).toEqual({ entryId: merged.id });
    // Tuesday is inside the card's union dates, but the tide is not.
    expect(chipsOf(result, TUE)[0].jump).toBeNull();
  });

  it('never counts a shoulder day as covered by the run on a merged entry', () => {
    const merged = entry({
      id: 'supermoon:2026-10-24:2026-10-30', type: 'supermoon', endDate: '2026-10-30',
      coincidence: [{ type: 'spring-tide' }],
      dayRanges: [{ date: TODAY, shoulder: true }, { date: SUN }, { date: MON }, { date: TUE, shoulder: true }],
    });
    const result = tiles({
      hotTopics: [tide(TODAY), tide(SUN), tide(TUE)],
      entries: [merged],
      renderedEntryIds: [merged.id],
    });
    expect(chipsOf(result, SUN)[0].jump).toEqual({ entryId: merged.id });
    expect(chipsOf(result, TODAY)[0].jump).toBeNull();
    expect(chipsOf(result, TUE)[0].jump).toBeNull();
  });

  it('offers a merged entry with no dayRanges no jump for the tide', () => {
    const merged = entry({ id: 'm', type: 'supermoon', coincidence: [{ type: 'spring-tide' }] });
    const result = tiles({ hotTopics: [tide(SUN)], entries: [merged], renderedEntryIds: ['m'] });
    expect(chipsOf(result, SUN)[0].jump).toBeNull();
  });

  it('uses startDate..endDate for a non-tide type, and the host card\'s span for the supermoon', () => {
    const moon = entry({ id: 'moon', type: 'supermoon', startDate: SUN, endDate: MON });
    const result = tiles({
      hotTopics: [
        topic('SUPERMOON', TODAY, 'SUNSET'), topic('SUPERMOON', SUN, 'SUNSET'),
        topic('SUPERMOON', TUE, 'SUNSET'),
      ],
      entries: [moon],
      renderedEntryIds: ['moon'],
    });
    expect(chipsOf(result, TODAY)[0].jump).toBeNull();
    expect(chipsOf(result, SUN)[0].jump).toEqual({ entryId: 'moon' });
    expect(chipsOf(result, TUE)[0].jump).toBeNull();
  });

  it('never jumps for a type with no almanac counterpart', () => {
    expect(ALMANAC_TYPES_BY_TOPIC.DUST).toBeUndefined();
    const result = tiles({
      hotTopics: [topic('DUST', SUN, 'SUNSET')],
      entries: [entry({ id: 'x', type: 'dust' })],
      renderedEntryIds: ['x'],
    });
    expect(chipsOf(result, SUN)[0].jump).toBeNull();
  });

  it('matches either tide type for either tide topic', () => {
    const run = entry({ id: 'king', type: 'king-tide' });
    const result = tiles({
      hotTopics: [tide(SUN)], entries: [run], renderedEntryIds: ['king'],
    });
    expect(chipsOf(result, SUN)[0].jump).toEqual({ entryId: 'king' });
  });
});

describe('buildFourDayTiles — the Map door', () => {
  it('is set only for a weather chip', () => {
    const result = tiles({
      hotTopics: [wx('INVERSION', SUN), topic('DUST', SUN, 'SUNSET'), tide(SUN)],
    });
    const byType = Object.fromEntries(chipsOf(result, SUN).map((c) => [c.type, c.mapDoor]));
    expect(byType.INVERSION).toEqual({ date: SUN, eventType: 'SUNRISE' });
    expect(byType.DUST).toBeNull();
    expect(byType.SPRING_TIDE).toBeNull();
  });

  it('allows the door while forecastDates is unknown (null / omitted)', () => {
    expect(chipsOf(tiles({ hotTopics: [wx('MIST', SUN)], forecastDates: null }), SUN)[0].mapDoor)
      .not.toBeNull();
    expect(chipsOf(tiles({ hotTopics: [wx('MIST', SUN)] }), SUN)[0].mapDoor).not.toBeNull();
  });

  it('allows the door for a date in a known forecastDates, and withholds it for one outside', () => {
    const result = tiles({
      hotTopics: [wx('MIST', SUN), wx('MIST', MON)],
      forecastDates: [SUN],
    });
    expect(chipsOf(result, SUN)[0].mapDoor).toEqual({ date: SUN, eventType: 'SUNRISE' });
    expect(chipsOf(result, MON)[0].mapDoor).toBeNull();
  });

  it('withholds every door when forecastDates is known and empty', () => {
    expect(chipsOf(tiles({ hotTopics: [wx('MIST', SUN)], forecastDates: [] }), SUN)[0].mapDoor)
      .toBeNull();
  });

  it('⚠️ takes the door\'s event from the topic that supplied the glance, not from the display order', () => {
    // A dusk MIST carrying the glance, served first, plus a dawn MIST with none.
    const dusk = wx('MIST', SUN, 'SUNSET');
    const dawn = topic('MIST', SUN, 'SUNRISE');
    const result = tiles({ hotTopics: [dusk, dawn] });
    const [chip] = chipsOf(result, SUN);
    // Display order stays chronological ...
    expect(chip.events).toEqual(['SUNRISE', 'SUNSET']);
    // ... but the door opens the evening the glance text is about.
    expect(chip.mapDoor).toEqual({ date: SUN, eventType: 'SUNSET' });
  });

  it('falls back to the first record\'s window when the glance topic is unanchored', () => {
    const dusk = topic('MIST', SUN, 'SUNSET');
    const glanced = wx('MIST', SUN, null);
    const [chip] = chipsOf(tiles({ hotTopics: [dusk, glanced] }), SUN);
    expect(chip.mapDoor.eventType).toBe('SUNSET');
  });

  it('builds no door at all when the caller has no Map door (hasMapDoor false)', () => {
    const result = tiles({ hotTopics: [wx('MIST', SUN)], hasMapDoor: false });
    expect(chipsOf(result, SUN)[0].mapDoor).toBeNull();
    expect(chipsOf(result, SUN)[0].weather).toBe(true);
  });

  it('lists a chip\'s events in morning-then-evening order', () => {
    const result = tiles({
      hotTopics: [wx('DUST', SUN, 'SUNSET'), wx('DUST', SUN, 'SUNRISE')],
    });
    expect(chipsOf(result, SUN)[0].events).toEqual(['SUNRISE', 'SUNSET']);
    const evening = tiles({ hotTopics: [wx('DUST', SUN, 'SUNSET')] });
    expect(chipsOf(evening, SUN)[0].mapDoor.eventType).toBe('SUNSET');
  });

  it('uses sunset for a NIGHT topic\'s own night and sunrise for its next morning', () => {
    const result = tiles({ hotTopics: [wx('AURORA', TODAY, 'NIGHT')] });
    expect(chipsOf(result, TODAY)[0].mapDoor.eventType).toBe('SUNSET');
    expect(chipsOf(result, SUN)[0].mapDoor.eventType).toBe('SUNRISE');
  });

  it('defaults an unanchored (null event) weather topic to sunrise', () => {
    const result = tiles({ hotTopics: [wx('CLEARANCE', SUN, null)] });
    const [chip] = chipsOf(result, SUN);
    expect(chip.events).toEqual([]);
    expect(chip.mapDoor).toEqual({ date: SUN, eventType: 'SUNRISE' });
  });
});


describe('buildGlanceLanes — the four days as lanes (days-ahead-lanes-plan.md §2)', () => {
  /** The matrix's columns for these dates; an {@code away} date is a column that is not live. */
  const cols = (dates, away = []) => dates.map((date) => ({ date, live: !away.includes(date) }));
  const ALL = [TODAY, SUN, MON, TUE];
  const lanesOf = (hotTopics, columns = cols(ALL), over = {}) => {
    const { days } = tiles({ hotTopics, ...over });
    return buildGlanceLanes(days, columns, hotTopics);
  };

  it('makes a single-day topic a one-column lane under its day', () => {
    const [lane] = lanesOf([topic('DUST', MON, 'SUNSET')]);
    expect(lane).toMatchObject({
      type: 'DUST', name: 'Dust', startCol: 3, endCol: 3, row: 1, dates: [MON],
      detail: null, detailDate: null, peak: null, startsBefore: false, endsAfter: false,
      jump: null, mapDoor: null, target: null,
    });
    expect(lane.key).toBe(`DUST:${MON}@${MON}`);
  });

  it('draws nothing for a quiet week, or for hot topics that have not arrived', () => {
    expect(lanesOf([])).toEqual([]);
    expect(lanesOf(undefined)).toEqual([]);
  });

  it('makes a three-day tide run ONE lane, with the PEAK day\'s detail and the peak date', () => {
    const [lane, ...rest] = lanesOf([
      tideRun(SUN, 1), tideRun(MON, 2, 3, { glance: glance({ value: '4.6 m', badge: 'PEAK' }) }), tideRun(TUE, 3),
    ]);
    expect(rest).toEqual([]);
    expect(lane).toMatchObject({
      type: 'SPRING_TIDE', startCol: 2, endCol: 4, dates: [SUN, MON, TUE],
      detail: '4.6 m', detailDate: MON, peak: MON, startsBefore: false, endsAfter: false,
    });
  });

  it('falls back to the first day\'s detail when the run has no PEAK', () => {
    const [lane] = lanesOf([tideRun(SUN, 1), tideRun(MON, 2)]);
    expect(lane).toMatchObject({ detail: 'day 1', detailDate: SUN, peak: null });
  });

  it('falls back to the first day\'s detail when the PEAK day has none', () => {
    const [lane] = lanesOf([
      tideRun(SUN, 1), tideRun(MON, 2, 3, { glance: glance({ badge: 'PEAK', value: null }) }),
    ]);
    expect(lane).toMatchObject({ detail: 'day 1', detailDate: SUN, peak: MON });
  });

  it('puts two types sharing a day on two rows, the non-weather one first', () => {
    const lanes = lanesOf([wx('MIST', SUN), topic('DUST', SUN, 'SUNSET')]);
    expect(lanes.map((l) => [l.type, l.row, l.startCol])).toEqual([['DUST', 1, 2], ['MIST', 2, 2]]);
  });

  it('lets single-day topics on different days share a row', () => {
    const lanes = lanesOf([topic('DUST', TODAY, 'SUNSET'), wx('MIST', MON)]);
    expect(lanes.map((l) => [l.type, l.row, l.startCol])).toEqual([['DUST', 1, 1], ['MIST', 1, 3]]);
  });

  it('breaks a run at a column that is absent from the strip', () => {
    // Monday is not a column at all: Sunday and Tuesday are not adjacent, so they are two lanes.
    const lanes = lanesOf([tideRun(SUN, 1), tideRun(MON, 2), tideRun(TUE, 3)], cols([TODAY, SUN, TUE]));
    expect(lanes.map((l) => [l.startCol, l.endCol, l.dates])).toEqual([[2, 2, [SUN]], [3, 3, [TUE]]]);
  });

  it('breaks a run at an away column, and says it carries on across it', () => {
    const lanes = lanesOf(
      [tideRun(SUN, 1), tideRun(MON, 2), tideRun(TUE, 3)], cols(ALL, [MON]),
    );
    expect(lanes.map((l) => [l.startCol, l.endCol, l.startsBefore, l.endsAfter])).toEqual([
      [2, 2, false, true],
      [4, 4, true, false],
    ]);
  });

  it('keeps a lane under Mon–Tue when an away column sits before it (columns are matrix index + 1)', () => {
    const lanes = lanesOf([tideRun(MON, 1, 2), tideRun(TUE, 2, 2)], cols(ALL, [SUN]));
    expect(lanes).toHaveLength(1);
    expect(lanes[0]).toMatchObject({ startCol: 3, endCol: 4, dates: [MON, TUE] });
  });

  it('draws no lane for an away column\'s topics', () => {
    expect(lanesOf([topic('DUST', SUN, 'SUNSET')], cols(ALL, [SUN]))).toEqual([]);
  });

  it('says a run began earlier when a served topic of the same event covers the day before its first column', () => {
    // The strip starts tomorrow: today's tide day is served but not a column.
    const lanes = lanesOf([tideRun(TODAY, 1), tideRun(SUN, 2)], cols([SUN, MON, TUE]));
    expect(lanes[0]).toMatchObject({ startCol: 1, endCol: 1, startsBefore: true, endsAfter: false });
  });

  it('says a run continues when a served topic covers the day after its last column', () => {
    const lanes = lanesOf([tideRun(SUN, 1), tideRun(MON, 2)], cols([TODAY, SUN]));
    expect(lanes[0]).toMatchObject({ startCol: 2, endCol: 2, startsBefore: false, endsAfter: true });
  });

  it('reads only served dates: a run the server clipped shows no edge', () => {
    const [lane] = lanesOf([tideRun(MON, 2), tideRun(TUE, 3)]);
    expect(lane).toMatchObject({ startsBefore: false, endsAfter: false });
  });

  it('is not fooled by a different event on the neighbouring day', () => {
    const lanes = lanesOf([wx('MIST', SUN), wx('MIST', MON)]);
    expect(lanes.map((l) => [l.startCol, l.endCol, l.startsBefore, l.endsAfter]))
      .toEqual([[2, 2, false, false], [3, 3, false, false]]);
  });

  it('makes two separate topics of one type on adjacent days two lanes', () => {
    const lanes = lanesOf([wx('MIST', SUN), wx('MIST', MON)]);
    expect(lanes).toHaveLength(2);
    expect(lanes.map((l) => l.dates)).toEqual([[SUN], [MON]]);
  });

  it('makes a one-night aurora one column under the night it falls, not its next morning', () => {
    const lanes = lanesOf([wx('AURORA', TODAY, 'NIGHT')]);
    expect(lanes).toHaveLength(1);
    expect(lanes[0]).toMatchObject({ type: 'AURORA', startCol: 1, endCol: 1, dates: [TODAY], endsAfter: false });
  });

  it('keeps tonight\'s aurora as a lane under tomorrow\'s column when the strip starts tomorrow', () => {
    const [lane, ...rest] = lanesOf([wx('AURORA', TODAY, 'NIGHT')], cols([SUN, MON, TUE]));
    expect(rest).toEqual([]);
    expect(lane).toMatchObject({
      type: 'AURORA', startCol: 1, endCol: 1, startsBefore: true, endsAfter: false, detail: 'dawn · 9/10',
    });
  });

  it('builds that kept lane from the night topic, not a different same-type topic', () => {
    const tonight = wx('AURORA', TODAY, 'NIGHT', { glance: glance({ weather: true, value: 'tonight' }) });
    const next = wx('AURORA', SUN, 'NIGHT', { glance: glance({ weather: true, value: 'next' }) });
    const lanes = lanesOf([tonight, next], cols([SUN, MON, TUE]));
    // Sunday's column holds the covering chip (next night's); the morning half of tonight's is dropped there.
    expect(lanes.map((l) => [l.startCol, l.detail])).toEqual([[1, 'next']]);
  });

  it('keeps a night topic\'s morning after a mid-strip away gap, and drops it in a normal strip', () => {
    // Sunday (the dusk) is an away column: Monday's morning is where the night shows up, as a lane that began earlier.
    const gap = lanesOf([wx('AURORA', SUN, 'NIGHT')], cols(ALL, [SUN]));
    expect(gap.map((l) => [l.startCol, l.endCol, l.startsBefore])).toEqual([[3, 3, true]]);
    // With a normal strip the same aurora is one column under its own night and nothing under the morning.
    const normal = lanesOf([wx('AURORA', SUN, 'NIGHT')]);
    expect(normal.map((l) => [l.startCol, l.endCol, l.startsBefore])).toEqual([[2, 2, false]]);
  });

  it('does not say an NLC dated on the last day continues after', () => {
    const [lane] = lanesOf([wx('NLC', TUE, 'NIGHT')]);
    expect(lane).toMatchObject({ startCol: 4, endCol: 4, endsAfter: false });
  });

  it('covers every dusk date of a multi-night topic and no morning', () => {
    const [lane] = lanesOf([wx('AURORA', SUN, 'NIGHT', { endDate: MON })]);
    expect(lane).toMatchObject({ startCol: 2, endCol: 3, dates: [SUN, MON] });
  });

  it('makes tonight\'s aurora and last night\'s two lanes', () => {
    const lanes = lanesOf([wx('AURORA', TODAY, 'NIGHT'), wx('AURORA', SUN, 'NIGHT')]);
    expect(lanes.map((l) => [l.startCol, l.endCol])).toEqual([[1, 1], [2, 2]]);
  });

  it('takes each route from the first chip in the run that has one', () => {
    const jumpEntry = entry({ id: 'run-x', startDate: MON, endDate: MON });
    const lanes = lanesOf(
      [tideRun(SUN, 1), tideRun(MON, 2)],
      cols(ALL),
      { entries: [jumpEntry], renderedEntryIds: new Set(['run-x']), openableKeys: new Set([`${MON}:SUNRISE`]) },
    );
    expect(lanes).toHaveLength(1);
    expect(lanes[0].jump).toEqual({ entryId: 'run-x' });
    expect(lanes[0].target).toBe(`${MON}:SUNRISE`);
    expect(lanes[0].mapDoor).toBeNull();
  });

  it('takes a weather topic\'s Map door from the run\'s first chip that has one', () => {
    const [lane] = lanesOf([wx('MIST', SUN, 'SUNSET')], cols(ALL), { forecastDates: [SUN] });
    expect(lane.mapDoor).toEqual({ date: SUN, eventType: 'SUNSET' });
  });

  it('assigns rows first-fit, longest span first, and orders the output by (row, startCol)', () => {
    const lanes = lanesOf([
      wx('MIST', SUN), wx('INVERSION', MON), topic('DUST', TODAY, 'SUNSET'),
      tideRun(SUN, 1), tideRun(MON, 2), tideRun(TUE, 3),
    ]);
    expect(lanes.map((l) => [l.type, l.row, l.startCol, l.endCol])).toEqual([
      ['DUST', 1, 1, 1],
      ['SPRING_TIDE', 1, 2, 4],
      ['MIST', 2, 2, 2],
      ['INVERSION', 2, 3, 3],
    ]);
  });

  it('copies the topic\'s palette colour and name onto the lane', () => {
    const [lane] = lanesOf([tideRun(SUN, 1)]);
    expect(lane).toMatchObject({ name: 'Spring tide', colour: '#6FA8B0' });
  });

  it('ignores a malformed topic', () => {
    expect(lanesOf([null, { label: 'x' }, topic('DUST', MON, 'SUNSET')])).toHaveLength(1);
  });
});
