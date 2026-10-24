import { describe, it, expect } from 'vitest';
import {
  BIG_SEAS_TYPE, barTarget, bigSeasPlaces, columnWord, currentColumnOf, fourDayGrid, labelTarget,
  mapScopeNames, placesFor, skyWideNotice, topicAnnouncement, topicsOnRow,
} from '../utils/mapTopics.js';
import { buildTopicIndex } from '../utils/windowFirstTopics.js';
import { spotsNamed } from '../utils/mapDoor.js';
import { SATURDAY } from './bigSeasFixtures.js';

/**
 * The Map's chip model (hot-topics-by-day-plan §3 M1). The subject is the JOIN, not a rule: the
 * window comes from the topic index, the places from the served `locationNames`, the scope from
 * `topicInScope`. Fixtures are shaped like the served payload.
 */

const NE = 'Northumberland & Tyneside';
const LAKES = 'The Lake District';
const TODAY = '2026-10-23';
const TOMORROW = '2026-10-24';

function topic(overrides = {}) {
  return {
    type: 'KING_TIDE',
    label: 'King tide',
    date: TODAY,
    eventType: 'SUNSET',
    regions: [NE],
    locationNames: [],
    ...overrides,
  };
}

const ROSTER = [
  { id: 1, name: 'Bamburgh', lat: 55.6, lng: -1.7, regionName: NE },
  { id: 2, name: 'Seahouses', lat: 55.58, lng: -1.65, regionName: NE },
  { id: 3, name: 'Roker Pier', lat: 54.9, lng: -1.35, regionName: NE },
  { id: 4, name: 'Buttermere', lat: 54.5, lng: -3.3, regionName: LAKES },
];

function chipsFor(row, hotTopics, extra = {}) {
  return topicsOnRow({
    row,
    topicIndex: buildTopicIndex(hotTopics),
    hotTopics,
    scopeNames: [],
    roster: ROSTER,
    ...extra,
  });
}

const solar = (date, eventType, extra = {}) => ({
  id: `solar:${date}:${eventType}`, kind: 'solar', date, eventType, badges: [], ...extra,
});
const astro = (date) => ({
  id: `astro:${date}:ASTRO`, kind: 'astro', date, eventType: 'ASTRO', badges: [],
});

describe('topicsOnRow: solar rows', () => {
  it('lists a filler row\'s topics although its badges are empty (the index, never row.badges)', () => {
    const hot = [topic({ type: 'DUST', label: 'Dust', date: '2026-10-26', eventType: 'SUNSET' })];
    const filler = solar('2026-10-26', 'SUNSET', { badges: [], filler: true });
    const chips = chipsFor(filler, hot);
    expect(chips.map((c) => c.type)).toEqual(['DUST']);
  });

  it('lists a PAST row\'s topics too', () => {
    const hot = [topic({ eventType: 'SUNRISE' })];
    const past = solar(TODAY, 'SUNRISE', { past: true });
    expect(chipsFor(past, hot).map((c) => c.type)).toEqual(['KING_TIDE']);
  });

  it('puts a day-scoped tide on both of its date\'s windows', () => {
    const hot = [topic({ eventType: null })];
    expect(chipsFor(solar(TODAY, 'SUNRISE'), hot)).toHaveLength(1);
    expect(chipsFor(solar(TODAY, 'SUNSET'), hot)).toHaveLength(1);
    expect(chipsFor(solar(TOMORROW, 'SUNSET'), hot)).toHaveLength(0);
  });

  it('puts a NIGHT topic on its evening and the next morning', () => {
    const hot = [topic({ type: 'AURORA', label: 'Aurora', eventType: 'NIGHT', regions: [] })];
    expect(chipsFor(solar(TODAY, 'SUNSET'), hot)).toHaveLength(1);
    expect(chipsFor(solar(TOMORROW, 'SUNRISE'), hot)).toHaveLength(1);
    expect(chipsFor(solar(TODAY, 'SUNRISE'), hot)).toHaveLength(0);
  });

  it('groups topics of one type into one chip, in served order', () => {
    const hot = [
      topic({ type: 'INVERSION', label: 'Inversion', locationNames: ['Bamburgh'] }),
      topic({ type: 'KING_TIDE', locationNames: ['Roker Pier'] }),
      topic({ type: 'INVERSION', label: 'Inversion', locationNames: ['Seahouses'] }),
    ];
    const chips = chipsFor(solar(TODAY, 'SUNSET'), hot);
    expect(chips.map((c) => c.type)).toEqual(['INVERSION', 'KING_TIDE']);
    expect(chips[0].topics).toHaveLength(2);
    expect(chips[0].places.map((p) => p.name)).toEqual(['Bamburgh', 'Seahouses']);
  });

  it('applies the region scope to region-scoped types and exempts whole-sky ones', () => {
    const hot = [
      topic({ type: 'DUST', label: 'Dust', regions: [LAKES] }),
      topic({ type: 'AURORA', label: 'Aurora', eventType: 'SUNSET', regions: [LAKES] }),
      topic({ type: 'KING_TIDE', regions: [NE] }),
    ];
    const chips = chipsFor(solar(TODAY, 'SUNSET'), hot, { scopeNames: [NE] });
    expect(chips.map((c) => c.type)).toEqual(['AURORA', 'KING_TIDE']);
  });

  it('keeps everything when the scope is empty (not known yet)', () => {
    const hot = [topic({ type: 'DUST', label: 'Dust', regions: [LAKES] })];
    expect(chipsFor(solar(TODAY, 'SUNSET'), hot, { scopeNames: [] })).toHaveLength(1);
  });

  it('is empty for a window with no topics and for no row at all', () => {
    expect(chipsFor(solar(TODAY, 'SUNSET'), [])).toEqual([]);
    expect(chipsFor(null, [topic()])).toEqual([]);
  });
});

describe('topicsOnRow: night rows', () => {
  it('shows that night\'s NIGHT topics only - never the evening\'s sunset topics', () => {
    const hot = [
      topic({ type: 'DUST', label: 'Dust', eventType: 'SUNSET' }),
      topic({ type: 'AURORA', label: 'Aurora', eventType: 'NIGHT', regions: [] }),
      topic({ type: 'AURORA', label: 'Aurora', eventType: 'NIGHT', date: TOMORROW, regions: [] }),
    ];
    const chips = chipsFor(astro(TODAY), hot);
    expect(chips.map((c) => c.type)).toEqual(['AURORA']);
    expect(chips[0].topics).toHaveLength(1);
  });

  it('scopes night topics too', () => {
    const hot = [topic({ type: 'METEOR', label: 'Meteors', eventType: 'NIGHT', regions: [LAKES] })];
    // METEOR is whole-sky: exempt from the scope even with a foreign region list.
    expect(chipsFor(astro(TODAY), hot, { scopeNames: [NE] })).toHaveLength(1);
    const scoped = [topic({ type: 'INVERSION', eventType: 'NIGHT', regions: [LAKES] })];
    expect(chipsFor(astro(TODAY), scoped, { scopeNames: [NE] })).toHaveLength(0);
  });
});

describe('topicsOnRow: Big seas', () => {
  const byId = new Map([[243, { id: 243, name: 'Roker Pier', lat: 54.9, lon: -1.35 }],
    [28, { id: 28, name: "St Mary's", lat: 55.07, lon: -1.45 }]]);

  it('is prepended on solar AND night rows of a full day', () => {
    const hot = [topic()];
    const withSeas = (row) => chipsFor(row, hot, { bigSeasDay: SATURDAY, locationsById: byId });
    const solarChips = withSeas(solar(TOMORROW, 'SUNSET'));
    expect(solarChips[0].type).toBe(BIG_SEAS_TYPE);
    const nightChips = withSeas(astro(TOMORROW));
    expect(nightChips.map((c) => c.type)).toEqual([BIG_SEAS_TYPE]);
  });

  it('is absent without a full day', () => {
    expect(chipsFor(solar(TOMORROW, 'SUNSET'), [topic()]).map((c) => c.type)).toEqual([]);
  });

  it('joins its structures by locationId, not by name', () => {
    const [seas] = chipsFor(solar(TOMORROW, 'SUNSET'), [], { bigSeasDay: SATURDAY, locationsById: byId });
    // SATURDAY serves 243, 28 and 21; 21 is not in the roster.
    expect(seas.places.map((p) => p.name)).toEqual(['Roker Pier', "St Mary's"]);
    expect(seas.where).toBe('2 structures');
    expect(seas.label).toBe('Big seas, 2 structures');
    expect(seas.wholeSky).toBe(false);
  });

  it('keeps the chip with no where when no structure joined', () => {
    const [seas] = chipsFor(solar(TOMORROW, 'SUNSET'), [], {
      bigSeasDay: SATURDAY, locationsById: new Map(),
    });
    expect(seas.where).toBeNull();
    expect(seas.label).toBe('Big seas');
  });

  it('bigSeasPlaces tolerates a missing day or roster', () => {
    expect(bigSeasPlaces(null, byId)).toEqual([]);
    expect(bigSeasPlaces(SATURDAY, null)).toEqual([]);
  });
});

describe('placesFor', () => {
  const tides = [topic({ locationNames: ['Bamburgh', 'Seahouses', 'Gone Away'] })];

  it('joins locationNames to the roster byte-identically and drops a name the roster lacks', () => {
    const places = placesFor('KING_TIDE', tides, ROSTER, []);
    expect(places.map((p) => p.name)).toEqual(['Bamburgh', 'Seahouses']);
    expect(placesFor('KING_TIDE', [topic({ locationNames: ['bamburgh'] })], ROSTER, [])).toEqual([]);
  });

  it('applies the region scope to the roster', () => {
    const lakeTide = [topic({ locationNames: ['Bamburgh', 'Buttermere'] })];
    expect(placesFor('KING_TIDE', lakeTide, ROSTER, [LAKES]).map((p) => p.name)).toEqual(['Buttermere']);
    expect(placesFor('KING_TIDE', lakeTide, ROSTER, []).map((p) => p.name))
      .toEqual(['Bamburgh', 'Buttermere']);
  });

  it('returns null for every whole-sky type, whatever locationNames says', () => {
    const named = [topic({ locationNames: ['Bamburgh'] })];
    ['AURORA', 'NLC', 'METEOR', 'SUPERMOON', 'EQUINOX', 'ECLIPSE', 'LUNAR_ECLIPSE'].forEach((type) => {
      expect(placesFor(type, named, ROSTER, [])).toBeNull();
    });
  });

  it('⚠️ treats an UNRECOGNISED type as sky-wide too (fail-open, the same rule as the scope filter)', () => {
    const odd = [topic({ type: 'SOMETHING_NEW', label: 'Something new', locationNames: ['Bamburgh'] })];
    expect(placesFor('SOMETHING_NEW', odd, ROSTER, [])).toBeNull();
    const [chip] = chipsFor(solar(TODAY, 'SUNSET'), odd);
    expect(chip.where).toBe('sky-wide');
    expect(chip.wholeSky).toBe(true);
  });

  it('rings dust (region-scoped, Q1), not sky-wide', () => {
    const dust = [topic({ type: 'DUST', locationNames: ['Roker Pier'] })];
    expect(placesFor('DUST', dust, ROSTER, []).map((p) => p.name)).toEqual(['Roker Pier']);
  });

  it('keeps places the label layers would drop: it joins the roster it is GIVEN (stood-down kept)', () => {
    // A stood-down place is in the scoped roster (it is a catalogue), only absent from `labelSpots`.
    const stoodRoster = [{ id: 9, name: 'Stood Down Bay', lat: 55, lng: -1, regionName: NE, stood: true }];
    const stood = [topic({ locationNames: ['Stood Down Bay'] })];
    expect(placesFor('KING_TIDE', stood, stoodRoster, [NE])).toHaveLength(1);
  });

  it('tolerates empty inputs', () => {
    expect(placesFor('KING_TIDE', null, null, null)).toEqual([]);
    expect(placesFor('KING_TIDE', [{}], ROSTER, [])).toEqual([]);
  });
});

describe('the "where" text', () => {
  it('reads sky-wide for a whole-sky type', () => {
    const [chip] = chipsFor(solar(TODAY, 'SUNSET'), [
      topic({ type: 'AURORA', label: 'Aurora', regions: [] }),
    ]);
    expect(chip.where).toBe('sky-wide');
    expect(chip.label).toBe('Aurora, sky-wide');
    expect(chip.wholeSky).toBe(true);
    expect(chip.places).toBeNull();
  });

  it('uses the palette\'s placeNoun: "2 coastal" for a tide, "2 places" otherwise', () => {
    const [tide] = chipsFor(solar(TODAY, 'SUNSET'), [
      topic({ locationNames: ['Bamburgh', 'Seahouses'] }),
    ]);
    expect(tide.where).toBe('2 coastal');
    expect(tide.label).toBe('King tide, 2 coastal places');
    const [inversion] = chipsFor(solar(TODAY, 'SUNSET'), [
      topic({ type: 'INVERSION', label: 'Inversion', locationNames: ['Bamburgh', 'Seahouses'] }),
    ]);
    expect(inversion.where).toBe('2 places');
    expect(inversion.label).toBe('Inversion, 2 places');
  });

  it('singularises one place', () => {
    const [tide] = chipsFor(solar(TODAY, 'SUNSET'), [topic({ locationNames: ['Bamburgh'] })]);
    expect(tide.label).toBe('King tide, 1 coastal place');
    const [dust] = chipsFor(solar(TODAY, 'SUNSET'), [
      topic({ type: 'DUST', label: 'Dust', locationNames: ['Bamburgh'] }),
    ]);
    expect(dust.where).toBe('1 place');
  });

  it('omits the where when no place joined, but still lists the chip', () => {
    const [tide] = chipsFor(solar(TODAY, 'SUNSET'), [topic({ locationNames: ['Gone Away'] })]);
    expect(tide.where).toBeNull();
    expect(tide.label).toBe('King tide');
  });
});

describe('mapScopeNames', () => {
  it('is the Plan scope list, unique and non-empty, while the area scope is on', () => {
    expect(mapScopeNames([NE, LAKES, NE, '', null], true)).toEqual([NE, LAKES]);
  });

  it('is empty (no filter) when the area scope is off or there is no list', () => {
    expect(mapScopeNames([NE], false)).toEqual([]);
    expect(mapScopeNames(null, true)).toEqual([]);
  });

  it('keeps a region that has no spots: the list is the caller\'s, not derived from spots', () => {
    // Away from home an origin region with no heat spots is still the scope (it is `[origin.name]`).
    expect(mapScopeNames(['Cumbria'], true)).toEqual(['Cumbria']);
  });
});

describe('spotsNamed - the shared mapDoor joiner', () => {
  it('filters a roster by exact name and is empty for no names', () => {
    expect(spotsNamed(ROSTER, ['Bamburgh']).map((s) => s.id)).toEqual([1]);
    expect(spotsNamed(ROSTER, [])).toEqual([]);
    expect(spotsNamed(null, ['x'])).toEqual([]);
  });
});

describe('announcements', () => {
  const sky = { type: 'AURORA', name: 'Aurora', wholeSky: true, places: null };
  const tide = { type: 'KING_TIDE', name: 'King tide', wholeSky: false, places: [{}, {}] };
  const seas = { type: 'BIG_SEAS', name: 'Big seas', wholeSky: false, bigSeas: true, places: [{}, {}, {}] };

  it('says what was selected and how many places are ringed - only when rings are drawn', () => {
    expect(topicAnnouncement(tide, true, true)).toBe('King tide selected. 2 places ringed on the map.');
    expect(topicAnnouncement({ ...tide, places: [{}] }, true, true))
      .toBe('King tide selected. 1 place ringed on the map.');
    expect(topicAnnouncement({ ...tide, places: [] }, true, true)).toBe('King tide selected.');
    expect(topicAnnouncement(tide, true, false)).toBe('King tide selected.');
    expect(topicAnnouncement(tide, true)).toBe('King tide selected.');
  });

  it('uses the chip\'s noun: Big seas rings structures', () => {
    expect(topicAnnouncement(seas, true, true)).toBe('Big seas selected. 3 structures ringed on the map.');
    expect(topicAnnouncement({ ...seas, places: [{}] }, true, true))
      .toBe('Big seas selected. 1 structure ringed on the map.');
  });

  it('says a whole-sky selection is sky-wide, and a clear is a clear', () => {
    expect(topicAnnouncement(sky, true, false)).toContain('Sky-wide');
    expect(topicAnnouncement(tide, false)).toBe('King tide cleared.');
    expect(topicAnnouncement(null, true)).toBe('');
  });

  it('prints the sky-wide notice only for a whole-sky chip', () => {
    expect(skyWideNotice(sky))
      .toBe('Aurora possible · sky-wide — every location in range sees it or none does');
    expect(skyWideNotice({ ...sky, type: 'METEOR', name: 'Meteors' }))
      .toBe('Meteors · sky-wide — every location in range sees it or none does');
    expect(skyWideNotice(tide)).toBeNull();
    expect(skyWideNotice(null)).toBeNull();
  });
});

// ── The four-day strip (hot-topics-by-day-plan §3 M2) ──────────────────────────────────────────────
// Friday 23 Oct 2026 is "today"; the strip runs Fri 23 .. Mon 26.
const FRI = '2026-10-23';
const SAT = '2026-10-24';
const SUN = '2026-10-25';
const MON = '2026-10-26';
const NOW = new Date('2026-10-23T09:00:00Z');

const night = (date) => ({ id: `aur:${date}:AURORA`, kind: 'aur', date, eventType: 'AURORA', badges: [] });

/** The EV list the Map builds: both solar windows of Fri..Sun (Friday's sunrise is past), Monday only a sunset. */
function eventList() {
  return [
    solar(FRI, 'SUNRISE', { past: true }),
    solar(FRI, 'SUNSET'),
    solar(SAT, 'SUNRISE'),
    solar(SAT, 'SUNSET'),
    solar(SUN, 'SUNRISE'),
    solar(SUN, 'SUNSET'),
    solar(MON, 'SUNSET'),
    night(FRI),
  ];
}

function gridFor(hotTopics, {
  activeRow = null, events = eventList(), bigSeas = null, scopeNames = [], nightDate = null,
} = {}) {
  return fourDayGrid({
    events,
    topicIndex: buildTopicIndex(hotTopics),
    hotTopics,
    scopeNames,
    bigSeas,
    todayStr: FRI,
    activeRow,
    nightDate,
    now: NOW,
  });
}

const rowOf = (grid, type) => grid.rows.find((r) => r.type === type);

describe('fourDayGrid: columns', () => {
  it('has eight columns by date, today to +3, sunrise then sunset, each with its EV row', () => {
    const grid = gridFor([]);
    expect(grid.days.map((d) => `${d.dow} ${d.dayNum}`))
      .toEqual(['Friday 23', 'Saturday 24', 'Sunday 25', 'Monday 26']);
    expect(grid.days.map((d) => d.today)).toEqual([true, false, false, false]);
    expect(grid.columns.map((c) => `${c.date}:${c.eventType}`)).toEqual([
      `${FRI}:SUNRISE`, `${FRI}:SUNSET`, `${SAT}:SUNRISE`, `${SAT}:SUNSET`,
      `${SUN}:SUNRISE`, `${SUN}:SUNSET`, `${MON}:SUNRISE`, `${MON}:SUNSET`,
    ]);
    expect(grid.columns[3].row.id).toBe(`solar:${SAT}:SUNSET`);
  });

  it('draws a column with no EV row but does not make it interactive', () => {
    const grid = gridFor([]);
    expect(grid.columns[6].row).toBeNull();
    expect(grid.columns[6].interactive).toBe(false);
    expect(grid.columns[7].interactive).toBe(true);
  });

  it('draws a past column of today but does not make it interactive', () => {
    const grid = gridFor([]);
    expect(grid.columns[0].row).not.toBeNull();
    expect(grid.columns[0].interactive).toBe(false);
    expect(grid.columns[1].interactive).toBe(true);
  });

  it('ignores a night row and a row outside the four days when placing columns', () => {
    const grid = gridFor([], { events: [night(FRI), solar('2026-10-30', 'SUNSET')] });
    expect(grid.columns.every((c) => c.row === null)).toBe(true);
  });
});

describe('fourDayGrid: rows and bars', () => {
  it('fills a day-4 column from the topic index although its row carries no badges', () => {
    const hot = [topic({ type: 'INVERSION', label: 'Inversion', date: MON, eventType: 'SUNSET' })];
    const grid = gridFor(hot);
    expect(grid.columns[7].row.badges).toEqual([]);
    expect(rowOf(grid, 'INVERSION').on).toEqual([false, false, false, false, false, false, false, true]);
  });

  it('fills a column that has NO row at all, and that bar has nothing to open', () => {
    const hot = [topic({ type: 'INVERSION', label: 'Inversion', date: MON, eventType: 'SUNRISE' })];
    const grid = gridFor(hot);
    const row = rowOf(grid, 'INVERSION');
    expect(row.on[6]).toBe(true);
    expect(grid.columns[6].interactive).toBe(false);
    expect(barTarget(grid, row.bars[0], false)).toBeNull();
  });

  it('merges contiguous windows into one bar, and a NIGHT topic Fri down + Sat up into one', () => {
    const hot = [topic({ type: 'AURORA', label: 'Aurora', date: FRI, eventType: 'NIGHT', regions: [] })];
    const aurora = rowOf(gridFor(hot), 'AURORA');
    expect(aurora.on).toEqual([false, true, true, false, false, false, false, false]);
    expect(aurora.bars).toEqual([{ start: 1, end: 2 }]);
  });

  it('draws a topic with a gap as two bars', () => {
    const hot = [
      topic({ type: 'DUST', label: 'Dust', date: FRI, eventType: 'SUNSET' }),
      topic({ type: 'DUST', label: 'Dust', date: SUN, eventType: 'SUNRISE' }),
    ];
    expect(rowOf(gridFor(hot), 'DUST').bars).toEqual([{ start: 1, end: 1 }, { start: 4, end: 4 }]);
  });

  it('puts a day-scoped tide (KING_TIDE, no eventType) on both windows of its date', () => {
    const hot = [
      topic({ date: SAT, eventType: null }),
      topic({ date: SUN, eventType: null }),
    ];
    const king = rowOf(gridFor(hot), 'KING_TIDE');
    expect(king.on).toEqual([false, false, true, true, true, true, false, false]);
    expect(king.bars).toEqual([{ start: 2, end: 5 }]);
  });

  it('orders rows as served, with Big seas first', () => {
    const hot = [
      topic({ type: 'DUST', label: 'Dust', date: SAT, eventType: 'SUNSET' }),
      topic({ date: FRI, eventType: null }),
      topic({ type: 'DUST', label: 'Dust', date: FRI, eventType: 'SUNSET' }),
    ];
    const grid = gridFor(hot, { bigSeas: [SATURDAY] });
    expect(grid.rows.map((r) => r.type)).toEqual([BIG_SEAS_TYPE, 'DUST', 'KING_TIDE']);
  });

  it('marks Big seas on both columns of each served day whose window is still ahead', () => {
    const grid = gridFor([], {
      bigSeas: [
        SATURDAY,
        { ...SATURDAY, date: FRI, windowEnd: '2026-10-23T08:00:00' },
      ],
    });
    // Friday's window ended before the app clock (09:00Z): not on. Saturday: both columns.
    const seas = rowOf(grid, BIG_SEAS_TYPE);
    expect(seas.on).toEqual([false, false, true, true, false, false, false, false]);
    expect(seas.bars).toEqual([{ start: 2, end: 3 }]);
    expect(seas.bigSeas).toBe(true);
  });

  it('adds no Big seas row when no served day is in the four days', () => {
    const grid = gridFor([], { bigSeas: [{ ...SATURDAY, date: '2026-10-30' }] });
    expect(rowOf(grid, BIG_SEAS_TYPE)).toBeUndefined();
  });

  it('applies the region scope to the topics', () => {
    const hot = [topic({ type: 'DUST', label: 'Dust', date: FRI, eventType: 'SUNSET', regions: [LAKES] })];
    expect(rowOf(gridFor(hot, { scopeNames: [NE] }), 'DUST')).toBeUndefined();
    expect(rowOf(gridFor(hot, { scopeNames: [LAKES] }), 'DUST')).toBeDefined();
  });

  it('draws a topic with no solar anchor on no column', () => {
    const hot = [topic({ type: 'STORM_SURGE', label: 'Storm surge', date: SAT, eventType: null })];
    expect(rowOf(gridFor(hot), 'STORM_SURGE')).toBeUndefined();
  });

  it('is empty, not an error, for nothing', () => {
    expect(fourDayGrid({
      events: null, topicIndex: null, hotTopics: null, todayStr: FRI, now: NOW,
    }).rows).toEqual([]);
  });
});

describe('fourDayGrid: the current column', () => {
  const dates = [FRI, SAT, SUN, MON];

  it('puts a solar row in its own date\'s column', () => {
    expect(currentColumnOf(solar(SAT, 'SUNRISE'), dates, FRI)).toBe(2);
    expect(currentColumnOf(solar(SUN, 'SUNSET'), dates, FRI)).toBe(5);
    expect(gridFor([], { activeRow: solar(SAT, 'SUNSET') }).currentColumn).toBe(3);
  });

  it('puts a night row under its date\'s sunset', () => {
    expect(currentColumnOf(night(FRI), dates, FRI)).toBe(1);
    expect(currentColumnOf(astro(SAT), dates, FRI)).toBe(3);
  });

  it('puts the pre-dawn night in progress, dated YESTERDAY, under today\'s sunrise (Codex P1 on #1319)', () => {
    // `nightDate` is the backend's night in progress: it is what says yesterday's night is still running.
    expect(currentColumnOf(night('2026-10-22'), dates, FRI, '2026-10-22')).toBe(0);
    expect(gridFor([], { activeRow: night('2026-10-22'), nightDate: '2026-10-22' }).currentColumn).toBe(0);
  });

  it('puts an ENDED night nowhere: yesterday\'s night once dawn has passed, or any older one', () => {
    // No night in progress (LITE has no status, or it has ended): the calendar answer, which is "over".
    expect(currentColumnOf(night('2026-10-22'), dates, FRI)).toBe(-1);
    expect(currentColumnOf(night('2026-10-22'), dates, FRI, null)).toBe(-1);
    // A stale status naming an older night is not believed for yesterday's row.
    expect(currentColumnOf(night('2026-10-22'), dates, FRI, '2026-10-21')).toBe(-1);
    // Two nights back is never under today's sunrise, whatever the status says.
    expect(currentColumnOf(night('2026-10-21'), dates, FRI, '2026-10-21')).toBe(-1);
    expect(gridFor([], { activeRow: night('2026-10-22') }).currentColumn).toBe(-1);
  });

  it('is -1 for a row outside the four days, for no row, and for an unknown kind', () => {
    expect(currentColumnOf(solar('2026-10-30', 'SUNSET'), dates, FRI)).toBe(-1);
    expect(currentColumnOf(night('2026-10-30'), dates, FRI)).toBe(-1);
    expect(currentColumnOf(null, dates, FRI)).toBe(-1);
    expect(currentColumnOf({ kind: 'hw', date: FRI }, dates, FRI)).toBe(-1);
  });

  it('flags exactly one column current', () => {
    const grid = gridFor([], { activeRow: solar(SAT, 'SUNRISE') });
    expect(grid.columns.filter((c) => c.current).map((c) => c.index)).toEqual([2]);
  });
});

describe('barTarget / labelTarget / columnWord', () => {
  const hot = [
    topic({ type: 'AURORA', label: 'Aurora', date: FRI, eventType: 'NIGHT', regions: [] }),
    topic({ date: SAT, eventType: null }),
    topic({ date: SUN, eventType: null }),
  ];

  it('stays when the bar covers the current column and the topic is on the current row', () => {
    const grid = gridFor(hot, { activeRow: solar(FRI, 'SUNSET') });
    expect(barTarget(grid, rowOf(grid, 'AURORA').bars[0], true)).toEqual({ stay: true, column: 1 });
  });

  it('goes to the current column\'s window when it covers it but the topic is not a chip there', () => {
    const grid = gridFor(hot, { activeRow: night(FRI) });
    expect(barTarget(grid, rowOf(grid, 'AURORA').bars[0], false)).toEqual({ stay: false, column: 1 });
  });

  it('goes to the first covered column that has a row when the bar does not cover the current one', () => {
    const away = gridFor(hot, { activeRow: solar(FRI, 'SUNSET') });
    expect(barTarget(away, rowOf(away, 'KING_TIDE').bars[0], false)).toEqual({ stay: false, column: 2 });
  });

  it('skips a past first column: Friday sunrise has a row but cannot be opened', () => {
    const grid = gridFor([topic({ date: FRI, eventType: null })], { activeRow: solar(SAT, 'SUNSET') });
    expect(barTarget(grid, rowOf(grid, 'KING_TIDE').bars[0], false)).toEqual({ stay: false, column: 1 });
  });

  it('toggles a label when the topic is on the current row, else jumps to its first openable column', () => {
    const grid = gridFor(hot, { activeRow: solar(FRI, 'SUNSET') });
    expect(labelTarget(grid, rowOf(grid, 'KING_TIDE'), true)).toEqual({ toggle: true });
    expect(labelTarget(grid, rowOf(grid, 'KING_TIDE'), false)).toEqual({ toggle: false, column: 2 });
    expect(labelTarget(grid, { on: new Array(8).fill(false) }, false)).toBeNull();
  });

  it('names a column as its weekday and event', () => {
    const grid = gridFor([]);
    expect(columnWord(grid, 0)).toBe('Friday sunrise');
    expect(columnWord(grid, 3)).toBe('Saturday sunset');
  });
});

describe('the strip never selects a type the chips would clear', () => {
  // The Map clears a selected topic that is not a chip on the window it lands on. Every type a bar offers
  // on a column must therefore be a chip on that column's row, or a press selects and is undone at once.
  const hot = [
    topic({ type: 'DUST', label: 'Dust', date: FRI, eventType: 'SUNSET' }),
    topic({ type: 'AURORA', label: 'Aurora', date: FRI, eventType: 'NIGHT', regions: [] }),
    topic({ date: SAT, eventType: null }),
    topic({ type: 'INVERSION', label: 'Inversion', date: SUN, eventType: 'SUNRISE', regions: [LAKES] }),
    topic({ type: 'MIST', label: 'Mist', date: MON, eventType: 'SUNSET' }),
  ];

  it.each([[[]], [[NE]], [[LAKES]]])('for scope %j, every type on a column is a chip on that column\'s row', (scope) => {
    const grid = gridFor(hot, { scopeNames: scope, bigSeas: [SATURDAY] });
    expect(grid.rows.length).toBeGreaterThan(0);
    for (const col of grid.columns.filter((c) => c.row)) {
      const day = grid.days[Math.floor(col.index / 2)].date === SAT ? SATURDAY : null;
      const chips = topicsOnRow({
        row: col.row,
        topicIndex: buildTopicIndex(hot),
        hotTopics: hot,
        scopeNames: scope,
        bigSeasDay: day,
        roster: ROSTER,
      });
      const onColumn = grid.rows.filter((r) => r.on[col.index]).map((r) => r.type);
      expect(chips.map((c) => c.type), `column ${col.index}`).toEqual(expect.arrayContaining(onColumn));
    }
  });
});
