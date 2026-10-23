import { describe, it, expect } from 'vitest';
import {
  BIG_SEAS_TYPE, bigSeasPlaces, mapScopeNames, placesFor, skyWideNotice, topicAnnouncement,
  topicsOnRow,
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
