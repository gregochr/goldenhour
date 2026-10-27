import { describe, it, expect } from 'vitest';
import { buildFourDayTiles, ALMANAC_TYPES_BY_TOPIC } from '../utils/fourDayTiles.js';

/**
 * The "Next four days" tiles model (coming-up-glance-plan §3 T1). Pure data in, tiles out; the
 * component has its own suite for markup, names and taps.
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
const wx = (type, date, eventType = 'SUNRISE', over = {}) => topic(type, date, eventType, {
  glance: glance({ weather: true, value: 'dawn · 9/10', shortValue: '9/10' }), ...over,
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
          glance: glance({ value: 'HW 16:02 · +0.4 m', shortValue: '+0.4 m pk', badge: 'PEAK' }),
        }),
      ],
    });
    const [chip] = chipsOf(result, SUN);
    expect(chip.value).toBe('HW 16:02 · +0.4 m');
    expect(chip.shortValue).toBe('+0.4 m pk');
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
      type: 'DUST', name: 'Dust', chipName: 'Dust', value: null, shortValue: null, badge: null,
      weather: false, mapDoor: null, jump: null,
    });
  });

  it('copies the served glance without deriving anything', () => {
    const [chip] = chipsOf(tiles({
      hotTopics: [topic('SUPERMOON', SUN, 'SUNSET', {
        glance: glance({ value: 'rise 16:34', shortValue: 'super', badge: 'FULL' }),
      })],
    }), SUN);
    expect(chip).toMatchObject({ value: 'rise 16:34', shortValue: 'super', badge: 'FULL' });
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

  it('flags a chip as a continuation when the same type was on the previous tile', () => {
    const result = tiles({ hotTopics: [tide(SUN), tide(MON), topic('DUST', TUE, 'SUNSET')] });
    expect(chipsOf(result, SUN)[0].isContinuation).toBe(false);
    expect(chipsOf(result, MON)[0].isContinuation).toBe(true);
    // Dust is new on Tuesday, and the tide is not there to continue.
    expect(chipsOf(result, TUE)[0].isContinuation).toBe(false);
  });

  it('does not call a run that has a gap a continuation', () => {
    const result = tiles({ hotTopics: [tide(TODAY), tide(MON)] });
    expect(chipsOf(result, MON)[0].isContinuation).toBe(false);
  });

  it('names the chip from the palette: the full name and the phone name', () => {
    const [chip] = chipsOf(tiles({ hotTopics: [tide(SUN)] }), SUN);
    expect(chip).toMatchObject({ name: 'Spring tide', chipName: 'Spring', colour: '#6FA8B0' });
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
      // The Glance target is still computed, but the component prefers `jump` (WindowComingUpFourDays).
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
