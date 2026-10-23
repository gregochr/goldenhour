import { describe, it, expect } from 'vitest';
import { ALMANAC_TYPES_BY_TOPIC, buildFourDayLanes } from '../utils/fourDayLanes.js';

/**
 * The "Next four days" lanes' model (hot-topics-by-day-plan §3 C1).
 *
 * <p>TODAY is Fri 23 Oct 2026, so the four days are Fri 23, Sat 24, Sun 25 and Mon 26 (the last Plan
 * day). Topics are fixtures shaped like the served {@code HotTopic}: a {@code NIGHT} topic buckets
 * onto its own sunset and the NEXT morning's sunrise, a tide onto both windows of its date, and an
 * unanchored topic onto none.
 */

const TODAY = '2026-10-23';
const FRI = '2026-10-23';
const SAT = '2026-10-24';
const SUN = '2026-10-25';
const MON = '2026-10-26';

const topic = (type, date, eventType, over = {}) => ({
  type, label: type, date, eventType, ...over,
});
const aurora = (date) => topic('AURORA', date, 'NIGHT');
const tideDay = (date, tideRun = {}, type = 'SPRING_TIDE') => topic(type, date, null, {
  tideRun: { dayNumber: 1, dayCount: 1, peak: false, ...tideRun },
});

const ALL_WINDOWS = new Set([
  `${FRI}:SUNRISE`, `${FRI}:SUNSET`, `${SAT}:SUNRISE`, `${SAT}:SUNSET`,
  `${SUN}:SUNRISE`, `${SUN}:SUNSET`, `${MON}:SUNRISE`, `${MON}:SUNSET`,
]);

const lanes = (hotTopics, extra = {}) => buildFourDayLanes({
  todayStr: TODAY, hotTopics, openableKeys: ALL_WINDOWS, ...extra,
});
const rowOf = (result, type) => result.rows.find((r) => r.type === type);
const only = (result, type) => {
  const row = rowOf(result, type);
  expect(row.segments).toHaveLength(1);
  return row.segments[0];
};

describe('buildFourDayLanes — the day columns and the degrade states', () => {
  it('returns the four days from today, flagging today', () => {
    const { days } = lanes([]);
    expect(days.map((d) => [d.date, d.dow, d.dayNum, d.today])).toEqual([
      [FRI, 'Fri', '23', true], [SAT, 'Sat', '24', false],
      [SUN, 'Sun', '25', false], [MON, 'Mon', '26', false],
    ]);
  });

  it('crosses a month and a year boundary', () => {
    const { days } = buildFourDayLanes({ todayStr: '2026-12-30', hotTopics: [] });
    expect(days.map((d) => d.date)).toEqual(['2026-12-30', '2026-12-31', '2027-01-01', '2027-01-02']);
  });

  it('has no rows until hotTopics has arrived (null), distinct from an arrived empty list', () => {
    expect(lanes(null).rows).toBeNull();
    expect(lanes(undefined).rows).toBeNull();
    expect(lanes([]).rows).toEqual([]);
    expect(lanes(null).days).toHaveLength(4);
  });

  it('has nothing at all while todayStr is not yet known', () => {
    expect(buildFourDayLanes({ todayStr: '', hotTopics: [] })).toEqual({ days: [], rows: null });
  });

  it('drops a topic with no type, no date, or no date inside the four days', () => {
    const result = lanes([
      { date: FRI, eventType: 'SUNSET' },
      { type: 'DUST', eventType: 'SUNSET' },
      topic('DUST', '2026-10-20', 'SUNSET'),
      topic('MIST', '2026-10-28', 'SUNRISE'),
    ]);
    expect(result.rows).toEqual([]);
  });
});

describe('buildFourDayLanes — dots', () => {
  it('draws a single SUNRISE as a one-day dot carrying ↑', () => {
    const seg = only(lanes([topic('INVERSION', SAT, 'SUNRISE')]), 'INVERSION');
    expect(seg).toMatchObject({
      kind: 'dot', start: 1, end: 1, glyph: '↑', windows: ['SUNRISE'], open: false,
      target: `${SAT}:SUNRISE`, jump: null,
    });
  });

  it('draws a single SUNSET as a dot carrying ↓', () => {
    const seg = only(lanes([topic('DUST', SUN, 'SUNSET')]), 'DUST');
    expect(seg).toMatchObject({ kind: 'dot', start: 2, glyph: '↓', target: `${SUN}:SUNSET` });
  });

  it('draws a tide on both windows of a day as ↑↓, targeting the first window Plan has', () => {
    const topics = [tideDay(SAT)];
    expect(only(lanes(topics), 'SPRING_TIDE')).toMatchObject({
      kind: 'dot', glyph: '↑↓', windows: ['SUNRISE', 'SUNSET'], target: `${SAT}:SUNRISE`,
    });
    // Sunrise has no card (passed, travel day, over the six): the sunset one is the target.
    const sunsetOnly = lanes(topics, { openableKeys: new Set([`${SAT}:SUNSET`]) });
    expect(only(sunsetOnly, 'SPRING_TIDE').target).toBe(`${SAT}:SUNSET`);
  });

  it('puts two MIST topics on one day in ONE row, and taps the first one\'s window', () => {
    const result = lanes([
      topic('MIST', SAT, 'SUNRISE', { label: 'Valley mist A' }),
      topic('MIST', SAT, 'SUNSET', { label: 'Valley mist B' }),
    ]);
    expect(result.rows.filter((r) => r.type === 'MIST')).toHaveLength(1);
    expect(only(result, 'MIST')).toMatchObject({
      kind: 'dot', glyph: '↑↓', target: `${SAT}:SUNRISE`,
    });
  });

  it('draws an unanchored topic as a day dot with no glyph and no tap target', () => {
    const seg = only(lanes([topic('STORM_SURGE', SUN, null)]), 'STORM_SURGE');
    expect(seg).toMatchObject({ kind: 'dot', start: 2, glyph: '', windows: [], target: null });
    // Not even a full set of Plan windows gives it one — it is bucketed on none.
  });

  it('draws a null-eventType topic of a type with no day scope as an unanchored dot too', () => {
    const seg = only(lanes([topic('DUST', SAT, null)]), 'DUST');
    expect(seg).toMatchObject({ kind: 'dot', glyph: '', target: null });
  });

  it('has a null target when no window of the segment is in openableKeys', () => {
    const result = lanes([topic('DUST', SAT, 'SUNSET')], { openableKeys: new Set() });
    expect(only(result, 'DUST').target).toBeNull();
  });
});

describe('buildFourDayLanes — bars, contiguity and the start word', () => {
  it('merges a NIGHT topic dated today into one bar reading "tonight → Sat"', () => {
    const seg = only(lanes([aurora(FRI)]), 'AURORA');
    expect(seg).toMatchObject({
      kind: 'bar', start: 0, end: 1, leftText: 'tonight → Sat', rightText: '', open: false,
      target: `${FRI}:SUNSET`,
    });
  });

  it('reads "this morning" when today is on only through a NIGHT topic dated yesterday, and '
      + 'targets today\'s sunrise', () => {
    const result = lanes([
      aurora('2026-10-22'), // this morning's sunrise, from last night's alert
      topic('AURORA', SAT, 'SUNRISE'),
    ]);
    const seg = only(result, 'AURORA');
    expect(seg).toMatchObject({
      kind: 'bar', start: 0, end: 1, leftText: 'this morning → Sat', target: `${FRI}:SUNRISE`,
    });
  });

  it('a yesterday-dated NIGHT topic on its own is a dot on today\'s sunrise, not a bar', () => {
    const seg = only(lanes([aurora('2026-10-22')]), 'AURORA');
    expect(seg).toMatchObject({ kind: 'dot', start: 0, glyph: '↑', target: `${FRI}:SUNRISE` });
  });

  it('prefers "tonight" when both last night\'s and tonight\'s alerts are served', () => {
    const seg = only(lanes([aurora('2026-10-22'), aurora(FRI)]), 'AURORA');
    expect(seg.leftText).toBe('tonight → Sat');
  });

  it('reads "today" for a multi-day run that starts today without a NIGHT topic', () => {
    const seg = only(lanes([
      topic('DUST', FRI, 'SUNSET'), topic('DUST', SAT, 'SUNSET'), topic('DUST', SUN, 'SUNSET'),
    ]), 'DUST');
    expect(seg).toMatchObject({ kind: 'bar', start: 0, end: 2, leftText: 'today → Sun' });
  });

  it('names a later start by its three-letter weekday', () => {
    const seg = only(lanes([topic('DUST', SAT, 'SUNSET'), topic('DUST', SUN, 'SUNSET')]), 'DUST');
    expect(seg.leftText).toBe('Sat → Sun');
  });

  it('draws two separate dots for days with a gap, and one bar for contiguous days', () => {
    const gapped = rowOf(lanes([topic('DUST', FRI, 'SUNSET'), topic('DUST', SUN, 'SUNSET')]), 'DUST');
    expect(gapped.segments.map((s) => [s.kind, s.start, s.end])).toEqual([['dot', 0, 0], ['dot', 2, 2]]);

    const contiguous = rowOf(lanes([
      topic('DUST', FRI, 'SUNSET'), topic('DUST', SAT, 'SUNSET'), topic('DUST', SUN, 'SUNSET'),
    ]), 'DUST');
    expect(contiguous.segments.map((s) => [s.kind, s.start, s.end])).toEqual([['bar', 0, 2]]);
  });

  it('takes the segment\'s tap target from the first key produced INSIDE the segment', () => {
    // A bar starting Sat: its first produced key inside it, not "the date + SUNSET".
    const seg = only(lanes([tideDay(SAT), tideDay(SUN)]), 'SPRING_TIDE');
    expect(seg).toMatchObject({ kind: 'bar', start: 1, end: 2, target: `${SAT}:SUNRISE` });
  });
});

// What the server actually sends: a tide topic's `tideRun` is built from the days INSIDE Plan's window
// only, so on the last in-window day `dayNumber === dayCount` whether or not the run goes on. Whether
// it goes on is the almanac's fact: a tide entry survives there only when it ends after day 4.
const lastDay = { dayNumber: 4, dayCount: 4 };
const TIDE_ENTRY = {
  id: 'spring-tide:2026-10-24:2026-10-30', type: 'spring-tide', startDate: SAT, endDate: '2026-10-30',
};

describe('buildFourDayLanes — open runs', () => {
  it('draws a tide bar reaching Monday as an open "runs on" bar when a straddling tide entry is '
      + 'served — even though dayNumber equals dayCount', () => {
    const seg = only(lanes(
      [tideDay(SUN, { dayNumber: 3, dayCount: 4 }), tideDay(MON, lastDay)],
      { entries: [TIDE_ENTRY] },
    ), 'SPRING_TIDE');
    expect(seg).toMatchObject({ kind: 'bar', start: 2, end: 3, leftText: 'runs on', open: true });
    expect(seg.spoken.left).toBe('Sunday onwards');
  });

  it('reads the FULL entry list, not only the rendered ids — a chip may hide the card, the run still goes on', () => {
    const seg = only(lanes(
      [tideDay(SUN), tideDay(MON, lastDay)],
      { entries: [TIDE_ENTRY], renderedEntryIds: new Set() },
    ), 'SPRING_TIDE');
    expect(seg.open).toBe(true);
    expect(seg.jump).toBeNull(); // nothing rendered to jump to — open, but no dead button
  });

  it('draws a closed bar when no tide entry straddles, however the topic counts its days', () => {
    const seg = only(lanes(
      [tideDay(SUN, { dayNumber: 1, dayCount: 9 }), tideDay(MON, { dayNumber: 2, dayCount: 9 })],
    ), 'SPRING_TIDE');
    expect(seg).toMatchObject({ open: false, leftText: 'Sun → Mon' });
  });

  it('ignores a tide entry that starts after the last Plan day — a next run is not this run', () => {
    const later = { ...TIDE_ENTRY, id: 'later', startDate: '2026-10-29', endDate: '2026-11-02' };
    const seg = only(lanes([tideDay(SUN), tideDay(MON, lastDay)], { entries: [later] }), 'SPRING_TIDE');
    expect(seg.open).toBe(false);
  });

  it('draws an open dot for a one-day Monday segment when the run goes on', () => {
    const seg = only(lanes([tideDay(MON, lastDay)], { entries: [TIDE_ENTRY] }), 'SPRING_TIDE');
    expect(seg).toMatchObject({ kind: 'dot', start: 3, open: true });
  });

  it('counts either tide type for either topic, and a tide carried only in a coincidence line', () => {
    const king = { ...TIDE_ENTRY, id: 'k', type: 'king-tide' };
    expect(only(lanes([tideDay(MON, lastDay)], { entries: [king] }), 'SPRING_TIDE').open).toBe(true);
    expect(only(lanes([tideDay(MON, lastDay, 'KING_TIDE')], { entries: [TIDE_ENTRY] }), 'KING_TIDE').open)
      .toBe(true);
    const merged = {
      ...TIDE_ENTRY, id: 'm', type: 'supermoon', coincidence: [{ type: 'king-tide', family: 'coastal' }],
    };
    expect(only(lanes([tideDay(MON, lastDay)], { entries: [merged] }), 'SPRING_TIDE').open).toBe(true);
  });

  it('is not triggered for a non-tide topic by a tide entry', () => {
    const seg = only(lanes([topic('DUST', MON, 'SUNSET')], { entries: [TIDE_ENTRY] }), 'DUST');
    expect(seg.open).toBe(false);
  });

  it('opens a NIGHT topic dated Monday, whose sunrise key lands after day 4', () => {
    const seg = only(lanes([aurora(MON)]), 'AURORA');
    expect(seg).toMatchObject({ kind: 'dot', start: 3, glyph: '↓', open: true });
  });

  it('is not open when the segment stops short of Monday, whatever the entries say', () => {
    const seg = only(lanes([tideDay(SAT)], { entries: [TIDE_ENTRY] }), 'SPRING_TIDE');
    expect(seg.open).toBe(false);
  });
});

describe('buildFourDayLanes — the tide peak label (§1.10)', () => {
  const run = (peakRun) => only(lanes([
    tideDay(SAT),
    tideDay(SUN, { peak: true, ...peakRun }),
  ]), 'SPRING_TIDE');

  it('prints "peak Sun" for a peak day with no light, and speaks it in full words', () => {
    expect(run({}).rightText).toBe('peak Sun');
    expect(run({}).spoken).toEqual({ left: 'Saturday to Sunday', peak: 'peak Sunday', day: '' });
  });

  it('adds the light for a served alignedEvent, matched case-insensitively', () => {
    expect(run({ alignedEvent: 'sunset' }).rightText).toBe('peak Sun · ↓ sunset');
    expect(run({ alignedEvent: 'sunset' }).spoken.peak).toBe('peak Sunday at sunset');
    expect(run({ alignedEvent: 'Sunrise' }).rightText).toBe('peak Sun · ↑ sunrise');
    expect(run({ alignedEvent: 'Sunrise' }).spoken.peak).toBe('peak Sunday at sunrise');
    expect(run({ alignedEvent: null }).rightText).toBe('peak Sun');
  });

  it('prints no peak on an OPEN segment — the visible peak may not be the run\'s', () => {
    const seg = only(lanes(
      [tideDay(SUN), tideDay(MON, { ...lastDay, peak: true, alignedEvent: 'sunset' })],
      { entries: [TIDE_ENTRY] },
    ), 'SPRING_TIDE');
    expect(seg.open).toBe(true);
    expect(seg.rightText).toBe('');
    expect(seg.spoken.peak).toBe('');
  });

  it('prints nothing when no day of the run is the peak', () => {
    const seg = only(lanes([tideDay(SAT), tideDay(SUN)]), 'SPRING_TIDE');
    expect(seg.rightText).toBe('');
  });

  it('never prints a peak for a non-tide topic, whatever else it carries', () => {
    const seg = only(lanes([
      topic('STORM_SURGE', SAT, null, { surgeRun: { peak: '1.2 m' } }),
      topic('STORM_SURGE', SUN, null, { surgeRun: { peak: '1.4 m' } }),
    ]), 'STORM_SURGE');
    expect(seg.rightText).toBe('');
  });
});

describe('buildFourDayLanes — spoken forms carry no symbols', () => {
  it('speaks a bar, an open bar and a dot in words', () => {
    const night = only(lanes([aurora(FRI)]), 'AURORA');
    expect(night.leftText).toBe('tonight → Sat');
    expect(night.spoken.left).toBe('tonight to Saturday');
    const dust = only(lanes([topic('DUST', FRI, 'SUNSET'), topic('DUST', SAT, 'SUNSET')]), 'DUST');
    expect(dust.spoken.left).toBe('today to Saturday');
    const dot = only(lanes([topic('INVERSION', SAT, 'SUNRISE')]), 'INVERSION');
    expect(dot.spoken.day).toBe('Saturday');
    const today = only(lanes([topic('INVERSION', FRI, 'SUNRISE')]), 'INVERSION');
    expect(today.spoken.day).toBe('today');
    for (const text of [night.spoken.left, dust.spoken.left, dot.spoken.day]) {
      expect(text).not.toMatch(/[→↑↓·]/);
    }
  });
});

describe('buildFourDayLanes — the jump button (§1.9)', () => {
  // An open tide run: Sun and Mon, and a straddling tide entry that is always in the full list.
  const run = [tideDay(SUN), tideDay(MON, lastDay)];
  const STRADDLER = { ...TIDE_ENTRY, id: 'unrendered-straddler' };
  const entry = (over) => ({ ...TIDE_ENTRY, ...over });
  /** `entries` always also holds an unrendered straddler, so the run is open whichever is rendered. */
  const jumpOf = (entries, renderedEntryIds, topics = run) => only(
    lanes(topics, { entries: [STRADDLER, ...(entries ?? [])], renderedEntryIds }), topics[0].type,
  ).jump;

  it('targets the straddling run — the listed entry that began by Monday and ends after it', () => {
    const e = entry({});
    expect(jumpOf([e], new Set([e.id]))).toEqual({
      entryId: e.id, label: 'In the list · 24–30 Oct', dates: '24–30 Oct', straddling: true,
    });
  });

  it('falls back to the next run, with its own wording, when none rendered straddles', () => {
    const e = entry({ id: 'next', startDate: '2026-10-29', endDate: '2026-10-31' });
    expect(jumpOf([e], new Set(['next']))).toEqual({
      entryId: 'next', label: 'Next run in the list · 29–31 Oct', dates: '29–31 Oct', straddling: false,
    });
  });

  it('prefers the straddling run over a later one, whatever the array order', () => {
    const later = entry({ id: 'later', startDate: '2026-11-08', endDate: '2026-11-12' });
    const straddling = entry({});
    expect(jumpOf([later, straddling], new Set(['later', straddling.id])).entryId)
      .toBe(straddling.id);
  });

  it('writes a cross-month run with both ends', () => {
    const e = entry({ id: 'x', startDate: '2026-10-29', endDate: '2026-11-02' });
    expect(jumpOf([e], new Set(['x'])).label).toBe('Next run in the list · 29 Oct–2 Nov');
  });

  it('finds a tide through coincidence[].type on a card another topic hosts', () => {
    const merged = entry({
      id: 'supermoon:2026-10-24:2026-10-30', type: 'supermoon',
      coincidence: [{ type: 'spring-tide', family: 'coastal' }],
    });
    expect(jumpOf([merged], new Set([merged.id])).entryId).toBe(merged.id);
  });

  it('accepts a king-tide entry for a spring-tide topic and the other way round', () => {
    const king = entry({ id: 'king', type: 'king-tide' });
    expect(jumpOf([king], new Set(['king'])).entryId).toBe('king');
  });

  it('is null when the matching entry is not in the rendered list (a chip filtered it out)', () => {
    expect(jumpOf([entry({})], new Set())).toBeNull();
    expect(jumpOf([entry({})], new Set(['something-else']))).toBeNull();
    expect(jumpOf([entry({})], null)).toBeNull();
  });

  it('is null when no entry of a tide type is rendered, or entries has not arrived', () => {
    const other = entry({ id: 'sm', type: 'supermoon' });
    expect(jumpOf([other], new Set(['sm']))).toBeNull();
    expect(only(lanes(run, { entries: null, renderedEntryIds: new Set(['a']) }), 'SPRING_TIDE').jump)
      .toBeNull();
  });

  it('is null for aurora, which has no almanac entry, whatever the feed holds', () => {
    const e = entry({ type: 'aurora' });
    const aurora26 = only(lanes([aurora(MON)], { entries: [e], renderedEntryIds: new Set([e.id]) }), 'AURORA');
    expect(aurora26.open).toBe(true);
    expect(aurora26.jump).toBeNull();
  });

  it('is offered only on an open segment', () => {
    const closed = only(lanes([tideDay(SAT)], {
      entries: [TIDE_ENTRY], renderedEntryIds: new Set([TIDE_ENTRY.id]),
    }), 'SPRING_TIDE');
    expect(closed.open).toBe(false);
    expect(closed.jump).toBeNull();
  });

  it('accepts rendered ids as an array as well as a Set', () => {
    expect(jumpOf([entry({})], [entry({}).id])).not.toBeNull();
  });
});

describe('ALMANAC_TYPES_BY_TOPIC — the explicit vocabulary map (§1.9)', () => {
  it('maps every hot-topic type that has an almanac counterpart to its kebab form', () => {
    expect(ALMANAC_TYPES_BY_TOPIC).toEqual({
      // Either tide type for either topic: the topic and the almanac can label one run differently.
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

  it('has no entry for aurora, which the almanac does not list', () => {
    expect(ALMANAC_TYPES_BY_TOPIC.AURORA).toBeUndefined();
  });

  it('finds an NLC run through its almanac type, nlc-season', () => {
    const nlc = [topic('NLC', MON, 'NIGHT')];
    const e = { id: 'nlc', type: 'nlc-season', startDate: '2026-05-25', endDate: '2026-11-30' };
    const result = lanes(nlc, { entries: [e], renderedEntryIds: new Set(['nlc']) });
    expect(only(result, 'NLC').jump).toMatchObject({ entryId: 'nlc', label: 'In the list · 25 May–30 Nov' });
  });
});

describe('buildFourDayLanes — the six-window cap on Plan', () => {
  // Plan renders six windows (PlanRenderLimits.MAX_VISIBLE_EVENTS). Before today's sunrise has
  // passed they are Fri sunrise … Sun sunset, so day 4 has no card at all; after it they are Fri
  // sunset … Mon sunrise, so day 4's SUNRISE has one and its SUNSET still has none.
  const at0600 = new Set([
    `${FRI}:SUNRISE`, `${FRI}:SUNSET`, `${SAT}:SUNRISE`, `${SAT}:SUNSET`, `${SUN}:SUNRISE`, `${SUN}:SUNSET`,
  ]);
  const at0900 = new Set([
    `${FRI}:SUNSET`, `${SAT}:SUNRISE`, `${SAT}:SUNSET`, `${SUN}:SUNRISE`, `${SUN}:SUNSET`, `${MON}:SUNRISE`,
  ]);
  const day4 = [topic('INVERSION', MON, 'SUNRISE'), topic('DUST', MON, 'SUNSET')];

  it('at 06:00 neither day-4 window has a card, so both targets are null', () => {
    const result = lanes(day4, { openableKeys: at0600 });
    expect(only(result, 'INVERSION').target).toBeNull();
    expect(only(result, 'DUST').target).toBeNull();
  });

  it('at 09:00 day 4\'s sunrise has a card and its sunset still does not', () => {
    const result = lanes(day4, { openableKeys: at0900 });
    expect(only(result, 'INVERSION').target).toBe(`${MON}:SUNRISE`);
    expect(only(result, 'DUST').target).toBeNull();
  });

  it('at 09:00 a passed window (today\'s sunrise) has no card either', () => {
    const result = lanes([topic('INVERSION', FRI, 'SUNRISE')], { openableKeys: at0900 });
    expect(only(result, 'INVERSION').target).toBeNull();
  });

  it('accepts openableKeys as an array', () => {
    const result = lanes([topic('DUST', SAT, 'SUNSET')], { openableKeys: [`${SAT}:SUNSET`] });
    expect(only(result, 'DUST').target).toBe(`${SAT}:SUNSET`);
  });
});

describe('buildFourDayLanes — rows', () => {
  it('has one row per type, in served order, styled from the palette', () => {
    const result = lanes([
      topic('DUST', SAT, 'SUNSET'), aurora(FRI), topic('DUST', SUN, 'SUNSET'),
    ]);
    expect(result.rows.map((r) => r.type)).toEqual(['DUST', 'AURORA']);
    expect(result.rows[1]).toMatchObject({ name: 'Aurora', glyph: '🌌', colour: '#8E86D6' });
    expect(result.rows[0].shortName).toBe('Dust');
  });

  it('names an unknown type by its served label, so it is never dropped', () => {
    const result = lanes([topic('SOLAR_HALO', SAT, 'SUNSET', { label: 'Solar halo' })]);
    expect(result.rows[0]).toMatchObject({ name: 'Solar halo', glyph: '•' });
  });

  it('counts exactly the topics with a covered date inside the four days — unanchored and '
      + 'yesterday-night included', () => {
    const hotTopics = [
      topic('DUST', SAT, 'SUNSET'),
      topic('STORM_SURGE', SUN, null), // unanchored
      topic('AURORA', '2026-10-22', 'NIGHT'), // yesterday's night, reaching this morning
      topic('AURORA', MON, 'NIGHT'), // reaches Tuesday's sunrise too
      tideDay(SAT),
      topic('MIST', '2026-10-20', 'SUNRISE'), // before today: out
      topic('MIST', '2026-10-29', 'SUNRISE'), // after Monday: out
      topic('NLC', '2026-10-21', 'NIGHT'), // covers 21 and 22 only: out
      { type: 'DUST', eventType: 'SUNSET' }, // undated: out
      { date: SAT, eventType: 'SUNSET' }, // untyped: out
    ];
    expect(lanes(hotTopics).rows.map((r) => r.type))
      .toEqual(['DUST', 'STORM_SURGE', 'AURORA', 'SPRING_TIDE']);
  });
});
