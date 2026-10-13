import { describe, it, expect } from 'vitest';
import {
  buildPickCards, eventKicker, meridiemOf, newestRunLabel, questionsForView, readyBusyLine,
  resolveSuggestions, shortWindow,
} from '../utils/askModel.js';
import { windowKey } from '../utils/windowKeys.js';
import {
  astroRow, auroraRow, briefing, KIELDER, nightPick, pick, readyResponse, ROSEBERRY, SALTBURN, slot, WHITBY,
} from './askFixtures.js';

/** The reader's HOME reach map, as the provider's `reachById` serves it. */
const home = (entries) => new Map(entries.map(([id, driveMinutes]) => [id, { driveMinutes, distanceMiles: 10 }]));

describe('buildPickCards — a served pick joined to the briefing', () => {
  it('names the place, the verdict word and star, the UK event time and the HOME drive', () => {
    const [card] = buildPickCards([pick()], briefing().days, home([[WHITBY, 95]]));

    expect(card).toMatchObject({
      rank: 1,
      locationId: WHITBY,
      name: 'Whitby',
      rating: 5,
      verdict: 'WORTH_IT',
      verdictLabel: 'Worth it',
      targetType: 'SUNSET',
      dayWord: 'Mon',
      shortWindow: 'Mon PM',
      driveMinutes: 95,
      driveLabel: '1h 35min',
    });
    // 17:41 UTC on a BST date is 18:41 on the reader's UK clock — never the UTC digits.
    expect(card.eventTime).toBe('18:41');
  });

  it('carries what F5 needs raw: the served event instant and the slot’s own one-line reading', () => {
    const days = briefing().days;
    days[0].eventSummaries[0].regions[0].slots[0].claudeSummary = '  Clear horizon, high cloud to catch it.  ';

    const [card] = buildPickCards([pick()], days, null);

    expect(card.eventInstant).toBe('2026-10-05T17:41:00');
    expect(card.summary).toBe('Clear horizon, high cloud to catch it.');
  });

  it('carries the window key the shell addresses a Plan window by, so nothing recomputes it from the card', () => {
    const [card] = buildPickCards([pick()], briefing().days, null);

    expect(card.windowKey).toBe(windowKey(card.date, card.targetType));
    expect(card.windowKey).toBe('2026-10-05:SUNSET');
  });

  it('has no summary rather than an empty one', () => {
    const [card] = buildPickCards([pick()], briefing().days, null);

    expect(card.summary).toBeNull();
  });

  it('gives a rank circle the accessible name the map’s marker for the same pick will carry', () => {
    const [card] = buildPickCards([pick()], briefing().days, null);

    expect(card.label).toBe('Pick 1, Whitby, Monday sunset, 5 stars');
  });

  it('says "1 star" in the singular', () => {
    const days = briefing().days;
    days[0].eventSummaries[0].regions[0].slots[0].claudeRating = 1;

    expect(buildPickCards([pick()], days, null)[0].label).toBe('Pick 1, Whitby, Monday sunset, 1 star');
  });

  it('reads a matched tide on the PREFERENCE axis, with the served state', () => {
    const [card] = buildPickCards([pick()], briefing().days, null);

    expect(card.tide).toEqual({
      tier: 'match', state: 'HIGH', shortfall: null, clause: 'high water, right here',
    });
  });

  it('reads a missed tide with its served shortfall, not a guessed one', () => {
    const [card] = buildPickCards([pick({ locationId: SALTBURN, locationName: 'Saltburn' })],
      briefing().days, null);

    expect(card.tide).toMatchObject({ tier: 'miss', shortfall: 'LOWER', clause: 'wants the water lower' });
  });

  it('gives an inland place no tide at all — a missing fact is not a served miss', () => {
    const [card] = buildPickCards([pick({ locationId: ROSEBERRY, locationName: 'Roseberry Topping' })],
      briefing().days, null);

    expect(card.tide).toBeNull();
    expect(card.verdictLabel).toBe('Maybe');
    expect(card.rating).toBe(3);
  });

  it('joins on the pick’s OWN window: the same place at another event carries that event’s star', () => {
    const [card] = buildPickCards(
      [pick({ date: '2026-10-06', targetType: 'SUNRISE', windowId: '2026-10-06_sunrise' })],
      briefing().days, null,
    );

    expect(card).toMatchObject({ rating: 4, shortWindow: 'Tue AM', eventTime: '06:58' });
  });
});

describe('buildPickCards — a pick with nothing to join is dropped, never half-rendered', () => {
  it('drops a pick whose window is not in the client’s briefing', () => {
    const cards = buildPickCards([pick({ date: '2026-10-09', windowId: '2026-10-09_sunset' })],
      briefing().days, null);

    expect(cards).toEqual([]);
  });

  it('drops a pick whose location is not in that window', () => {
    expect(buildPickCards([pick({ locationId: 999 })], briefing().days, null)).toEqual([]);
  });

  it('drops a pick for the other event of a day the briefing holds only one event of', () => {
    expect(buildPickCards([pick({ targetType: 'SUNRISE' })], briefing().days, null)).toEqual([]);
  });

  it('keeps the others, and never renumbers a rank', () => {
    const cards = buildPickCards(
      [pick({ rank: 1, locationId: 999 }), pick({ rank: 2, locationId: SALTBURN }), pick({ rank: 3 })],
      briefing().days, null,
    );

    expect(cards.map((c) => c.rank)).toEqual([2, 3]);
  });

  it.each([
    ['an empty list', []],
    ['null', null],
    ['undefined', undefined],
    ['a non-array', { rank: 1 }],
  ])('returns no cards for %s', (_name, picks) => {
    expect(buildPickCards(picks, briefing().days, null)).toEqual([]);
  });

  it.each([
    ['no days', null],
    ['an empty briefing', []],
    ['a non-array', {}],
  ])('drops everything when the client holds %s', (_name, days) => {
    expect(buildPickCards([pick()], days, null)).toEqual([]);
  });

  it('skips entries that are not picks, and picks it cannot name', () => {
    const cards = buildPickCards(
      [null, 'x', { rank: 'one' }, pick({ locationId: null }), pick({ targetType: 'NOON' }),
        pick({ date: 20261005 }), pick({ rank: 2 })],
      briefing().days, null,
    );

    expect(cards.map((c) => c.rank)).toEqual([2]);
  });

  it('falls back to the served pick’s own name when the slot carries none, and drops when neither does', () => {
    const days = briefing().days;
    days[0].eventSummaries[0].regions[0].slots[0].locationName = '';

    expect(buildPickCards([pick()], days, null)[0].name).toBe('Whitby');
    expect(buildPickCards([pick({ locationName: null })], days, null)).toEqual([]);
  });

  it('finds a slot a region does not own (unregioned)', () => {
    const days = briefing().days;
    days[0].eventSummaries[0].unregioned = [slot({ locationId: 77, locationName: 'Stray' })];

    expect(buildPickCards([pick({ locationId: 77 })], days, null)[0].name).toBe('Stray');
  });
});

describe('buildPickCards — the star and the verdict are the served ones', () => {
  it('keeps the card but prints no number for a slot with no usable rating', () => {
    const days = briefing().days;
    days[0].eventSummaries[0].regions[0].slots[0].claudeRating = null;
    days[0].eventSummaries[0].regions[0].slots[0].displayVerdict = 'AWAITING';

    const [card] = buildPickCards([pick()], days, null);

    expect(card.rating).toBeNull();
    expect(card.verdictLabel).toBe('Not scored');
    expect(card.label).toBe('Pick 1, Whitby, Monday sunset');
  });

  it.each([0, 6, 491, 3.5, '4'])('refuses a rating of %s, which Claude’s 1–5 scale never produces', (bad) => {
    const days = briefing().days;
    days[0].eventSummaries[0].regions[0].slots[0].claudeRating = bad;

    expect(buildPickCards([pick()], days, null)[0].rating).toBeNull();
  });

  it('speaks a slot\'s UNRATED at SLOT level — "Not rated", never the region word, and keeps the value for the chip (U2)', () => {
    // A pick is one PLACE. "Too few rated" describes a sample; this place simply has no rating of its
    // own to speak of. The chip's tier colour has no rule for UNRATED, which is the neutral AWAITING
    // ink on purpose — a place nothing has rated gets no verdict hue.
    const days = briefing().days;
    days[0].eventSummaries[0].regions[0].slots[0].displayVerdict = 'UNRATED';

    const [card] = buildPickCards([pick()], days, null);

    expect(card.verdict).toBe('UNRATED');
    expect(card.verdictLabel).toBe('Not rated');
    expect(card.verdictLabel).not.toBe('Too few rated');
  });

  it('reads an unknown display verdict as not scored rather than as a verdict', () => {
    const days = briefing().days;
    days[0].eventSummaries[0].regions[0].slots[0].displayVerdict = 'SPLENDID';

    expect(buildPickCards([pick()], days, null)[0]).toMatchObject({
      verdict: 'AWAITING', verdictLabel: 'Not scored',
    });
  });

  it('omits the time, not the card, when the slot has no event time', () => {
    const days = briefing().days;
    delete days[0].eventSummaries[0].regions[0].slots[0].solarEventTime;

    const [card] = buildPickCards([pick()], days, null);

    expect(card.eventTime).toBeNull();
    expect(card.name).toBe('Whitby');
  });
});

describe('buildPickCards — drive time is the HOME map and unknown stays unknown', () => {
  it.each([
    ['no map at all', null],
    ['a map with no entry for the place', home([[999, 20]])],
    ['an entry with no drive time', new Map([[WHITBY, { driveMinutes: null }]])],
    ['a negative drive time', home([[WHITBY, -5]])],
    ['a non-number drive time', new Map([[WHITBY, { driveMinutes: '40' }]])],
    ['something that is not a Map', { [WHITBY]: { driveMinutes: 40 } }],
  ])('prints no drive line for %s', (_name, reach) => {
    const [card] = buildPickCards([pick()], briefing().days, reach);

    expect(card.driveMinutes).toBeNull();
    expect(card.driveLabel).toBeNull();
  });

  it('formats under an hour, an hour and a part, and a whole number of hours', () => {
    const drive = (minutes) => buildPickCards([pick()], briefing().days, home([[WHITBY, minutes]]))[0].driveLabel;

    expect(drive(45)).toBe('45 min');
    expect(drive(65)).toBe('1h 5min');
    expect(drive(120)).toBe('2h');
  });

  it('rounds a fractional drive to the minute', () => {
    expect(buildPickCards([pick()], briefing().days, home([[WHITBY, 44.6]]))[0].driveMinutes).toBe(45);
  });
});

describe('meridiemOf', () => {
  it('is AM for a sunrise and PM for a sunset, and nothing for anything else', () => {
    expect(meridiemOf('SUNRISE')).toBe('AM');
    expect(meridiemOf('SUNSET')).toBe('PM');
    expect(meridiemOf('ASTRO')).toBeNull();
    expect(meridiemOf(undefined)).toBeNull();
  });
});

describe('shortWindow', () => {
  it.each([
    ['2026-10-10', 'SUNRISE', 'Sat AM'],
    ['2026-10-10', 'SUNSET', 'Sat PM'],
    ['2026-10-11', 'SUNRISE', 'Sun AM'],
    ['2026-10-11', 'SUNSET', 'Sun PM'],
    ['2026-10-05', 'SUNSET', 'Mon PM'],
  ])('%s %s is %s', (date, targetType, expected) => {
    expect(shortWindow({ date, targetType })).toBe(expected);
  });

  it('names the UK day across the clocks-change weekend', () => {
    // 25 Oct 2026 is the day BST ends. The weekday comes from the date string, so it is a Sunday
    // on both sides of the change.
    expect(shortWindow({ date: '2026-10-24', targetType: 'SUNSET' })).toBe('Sat PM');
    expect(shortWindow({ date: '2026-10-25', targetType: 'SUNRISE' })).toBe('Sun AM');
  });

  it.each([
    ['an unknown event', { date: '2026-10-10', targetType: 'NOON' }],
    ['no date', { date: '', targetType: 'SUNRISE' }],
    ['a non-string date', { date: 20261010, targetType: 'SUNRISE' }],
    ['null', null],
  ])('is null for %s', (_name, input) => {
    expect(shortWindow(input)).toBeNull();
  });
});

describe('the Ready list', () => {
  const { questions } = readyResponse();

  it('offers on a tab exactly the questions that name it, in served order', () => {
    expect(questionsForView(questions, 'plan').map((q) => q.id)).toEqual(['BEST_NEXT', 'AM_OR_PM']);
    expect(questionsForView(questions, 'map').map((q) => q.id))
      .toEqual(['BEST_NEXT', 'COASTAL_HIGH', 'RARE_EVENTS']);
    expect(questionsForView(questions, 'coming-up').map((q) => q.id)).toEqual(['RARE_EVENTS']);
  });

  it('offers nothing from a malformed list', () => {
    expect(questionsForView(null, 'plan')).toEqual([]);
    expect(questionsForView([null, { id: 'X' }, { id: 'Y', tabs: 'plan' }], 'plan')).toEqual([]);
  });

  it('names the NEWEST run among the questions offered, read off the payload', () => {
    expect(newestRunLabel(questions)).toBe('18:03');
    expect(newestRunLabel(questions.slice(0, 2))).toBe('06:02');
  });

  it('names no run when none is served', () => {
    expect(newestRunLabel([])).toBeNull();
    expect(newestRunLabel(null)).toBeNull();
    expect(newestRunLabel([{ generatedAt: '2026-10-05T05:02:11', runLabel: '' }, { runLabel: null }]))
      .toBeNull();
  });

  it('resolves a cant reply’s suggestions to the questions already in hand, in suggestion order', () => {
    const resolved = resolveSuggestions(
      [{ id: 'RARE_EVENTS', text: 'x' }, { id: 'BEST_NEXT', text: 'y' }], questions,
    );

    expect(resolved.map((q) => q.id)).toEqual(['RARE_EVENTS', 'BEST_NEXT']);
  });

  it.each([
    ['none', []],
    ['one that is not in the list', [{ id: 'GONE', text: 'x' }]],
    ['null', null],
  ])('resolves %s to nothing to tap', (_name, suggestions) => {
    expect(resolveSuggestions(suggestions, questions)).toEqual([]);
  });

  it('keeps the suggestion that resolves when the other does not', () => {
    const resolved = resolveSuggestions([{ id: 'GONE' }, { id: 'BEST_NEXT' }], questions);

    expect(resolved.map((q) => q.id)).toEqual(['BEST_NEXT']);
  });
});

describe('eventKicker and readyBusyLine', () => {
  it('reads an underscored type as words and tolerates nothing', () => {
    expect(eventKicker('LUNAR_ECLIPSE')).toBe('LUNAR ECLIPSE');
    expect(eventKicker('AURORA')).toBe('AURORA');
    expect(eventKicker(null)).toBe('');
  });

  it('says morning for a morning run, evening for an evening one, and nothing false otherwise', () => {
    expect(readyBusyLine('06:02')).toBe('Opening this morning’s answer');
    expect(readyBusyLine('11:59')).toBe('Opening this morning’s answer');
    expect(readyBusyLine('12:00')).toBe('Opening this evening’s answer');
    expect(readyBusyLine('18:03')).toBe('Opening this evening’s answer');
    expect(readyBusyLine(null)).toBe('Opening the answer');
    expect(readyBusyLine('soon')).toBe('Opening the answer');
  });
});

describe('buildPickCards — a NIGHT pick (docs/engineering/ask-night-plan.md §2.6)', () => {
  const TODAY = '2026-10-05';
  const TOMORROW = '2026-10-06';
  /** The night payloads the provider holds for the live answer, by date. */
  const nightsOf = (over = {}) => ({
    astro: new Map([[TODAY, [astroRow()]]]),
    aurora: new Map(),
    canSeeAurora: true,
    todayStr: TODAY,
    tomorrowStr: TOMORROW,
    ...over,
  });
  const build = (picks, nights = nightsOf(), reach = null) => buildPickCards(picks, briefing().days, reach, nights);

  it('is built from the astro payload, not the briefing: the place has no solar slot at all', () => {
    const [card] = build([nightPick()]);

    expect(card).toMatchObject({
      rank: 1,
      locationId: KIELDER,
      name: 'Kielder Observatory',
      regionName: 'Northumberland & Tyneside',
      targetType: 'ASTRO',
      night: true,
      kind: 'astro',
      date: TODAY,
      windowId: '2026-10-05_astro',
      windowKey: `${TODAY}:ASTRO`,
      rating: 4,
      astroStars: 4,
      auroraStars: null,
      bortle: 3,
    });
  });

  it('reads the night as the Map pill does, with its served dark window on the UK clock', () => {
    const [card] = build([nightPick()]);

    expect(card.nightWord).toBe('Tonight');
    // 19:40Z and 04:10Z on a BST date are 20:40 and 05:10 on the reader's clock — never the UTC digits.
    expect(card.nightWindow).toBe('20:40–05:10');
    expect(card.eventTime).toBe('20:40');
    expect(card.eventInstant).toBe('2026-10-05T19:40:00');
  });

  it('words the other nights the way the window control does', () => {
    const nights = nightsOf({ astro: new Map([[TOMORROW, [astroRow()]], ['2026-10-09', [astroRow()]]]) });

    expect(build([nightPick({ date: TOMORROW })], nights)[0].nightWord).toBe('Tomorrow night');
    expect(build([nightPick({ date: '2026-10-09' })], nights)[0].nightWord).toBe('Friday night');
  });

  it('has no verdict and no tide — a night has neither — and says so rather than inventing a word', () => {
    const [card] = build([nightPick()]);

    expect(card.verdict).toBeNull();
    expect(card.verdictLabel).toBeNull();
    expect(card.tide).toBeNull();
  });

  it('carries the served astro summary, the clear-sky line and the moon', () => {
    const [card] = build([nightPick()]);

    expect(card.summary).toBe('Clear and dark: low cloud, good visibility, little moon.');
    expect(card.clearSky).toBe('Cloud cover 12% — clear for most of the night.');
    expect(card.moon).toBe('Waxing Crescent · 23%');
  });

  it('gives a rank circle a name that says the night and the kind, as the map marker for the pick will', () => {
    expect(build([nightPick()])[0].label).toBe('Pick 1, Kielder Observatory, Tonight astro, 4 stars');
  });

  it('joins the HOME drive, never anything else', () => {
    const [card] = build([nightPick()], nightsOf(), home([[KIELDER, 95]]));

    expect(card.driveMinutes).toBe(95);
    expect(card.driveLabel).toBe('1h 35min');
  });

  it('puts the aurora star beside the astro one for a reader who may see it, when that payload is held', () => {
    const nights = nightsOf({ aurora: new Map([[TODAY, [auroraRow()]]]) });
    const [card] = build([nightPick()], nights);

    expect(card).toMatchObject({ rating: 4, astroStars: 4, auroraStars: 3 });
    expect(card.label).toBe('Pick 1, Kielder Observatory, Tonight astro, 4 stars, aurora 3 stars');
  });

  it('⚠️ never prints an aurora figure for a reader who may not see it, even when one was somehow held', () => {
    const nights = nightsOf({ aurora: new Map([[TODAY, [auroraRow()]]]), canSeeAurora: false });
    const [card] = build([nightPick()], nights);

    expect(card.auroraStars).toBeNull();
    expect(card.label).not.toContain('aurora');
  });

  it('an AURORA pick is built from the aurora payload, its own star first, the astro one beside it', () => {
    const nights = nightsOf({ aurora: new Map([[TODAY, [auroraRow()]]]) });
    const [card] = build([nightPick({ targetType: 'AURORA', windowId: '2026-10-05_aurora', ratingAtAnswer: 3 })], nights);

    expect(card).toMatchObject({
      kind: 'aurora', rating: 3, auroraStars: 3, astroStars: 4, windowKey: `${TODAY}:AURORA`,
    });
    expect(card.summary).toBe('Kp 6 forecast; dark horizon to the north.');
    expect(card.label).toBe('Pick 1, Kielder Observatory, Tonight aurora, 3 stars, astro 4 stars');
  });

  it('⚠️ drops an AURORA pick for a reader who may not see aurora: no aurora payload is ever read for them', () => {
    const nights = nightsOf({ aurora: new Map([[TODAY, [auroraRow()]]]), canSeeAurora: false });

    expect(build([nightPick({ targetType: 'AURORA', windowId: '2026-10-05_aurora' })], nights)).toEqual([]);
  });

  it('drops a night pick whose night the client does not hold — never a half-drawn card', () => {
    expect(build([nightPick({ date: TOMORROW })])).toEqual([]);
  });

  it('drops a night pick whose place is not on the night it holds', () => {
    expect(build([nightPick({ locationId: 999 })])).toEqual([]);
  });

  it('drops every night pick when no night payloads were handed in at all', () => {
    expect(buildPickCards([nightPick()], briefing().days, null)).toEqual([]);
    expect(buildPickCards([nightPick()], briefing().days, null, null)).toEqual([]);
  });

  it('⚠️ gates on the KIND, not the meridiem: a night pick is kept and an unknown kind is dropped', () => {
    const cards = build([nightPick(), nightPick({ rank: 2, targetType: 'MOONRISE' })]);

    expect(cards.map((c) => c.rank)).toEqual([1]);
  });

  it('keeps a solar pick and a night pick together, in the server’s order, ranks never renumbered', () => {
    const cards = build([pick({ rank: 1 }), nightPick({ rank: 3 }), pick({ rank: 2, locationId: 999 })]);

    expect(cards.map((c) => [c.rank, c.targetType])).toEqual([[1, 'SUNSET'], [3, 'ASTRO']]);
  });

  it('names the place by its payload name when the pick’s own is missing, and drops it when neither has one', () => {
    expect(build([nightPick({ locationName: undefined })])[0].name).toBe('Kielder Observatory');
    const nameless = nightsOf({ astro: new Map([[TODAY, [astroRow({ locationName: '' })]]]) });
    expect(build([nightPick({ locationName: undefined })], nameless)).toEqual([]);
  });

  it('takes the Bortle class and window from the other kind’s row when its own lacks them', () => {
    const nights = nightsOf({
      astro: new Map([[TODAY, [astroRow()]]]),
      aurora: new Map([[TODAY, [auroraRow({ bortleClass: null, nightStart: null, nightEnd: null })]]]),
    });
    const [card] = build([nightPick({ targetType: 'AURORA', windowId: '2026-10-05_aurora' })], nights);

    expect(card.bortle).toBe(3);
    expect(card.nightWindow).toBe('20:40–05:10');
  });

  it('has no window, and says no Bortle, when none is served — silence, not a placeholder', () => {
    const bare = nightsOf({
      astro: new Map([[TODAY, [astroRow({ nightStart: null, nightEnd: null, bortleClass: null })]]]),
    });
    const [card] = build([nightPick()], bare);

    expect(card.nightWindow).toBeNull();
    expect(card.eventTime).toBeNull();
    expect(card.bortle).toBeNull();
  });

  it('a rating off Claude’s 1–5 scale is no number at all', () => {
    const odd = nightsOf({ astro: new Map([[TODAY, [astroRow({ stars: 0 })]]]) });

    expect(build([nightPick()], odd)[0].rating).toBeNull();
  });
});

describe('the night kinds in the short window words', () => {
  it('has no meridiem for a night, and says "night" / "aurora" in its place', () => {
    expect(meridiemOf('ASTRO')).toBeNull();
    expect(meridiemOf('AURORA')).toBeNull();
    expect(shortWindow({ date: '2026-10-05', targetType: 'ASTRO' })).toBe('Mon night');
    expect(shortWindow({ date: '2026-10-05', targetType: 'AURORA' })).toBe('Mon aurora');
  });

  it('still says AM and PM for a solar window and null for a kind it does not know', () => {
    expect(shortWindow({ date: '2026-10-05', targetType: 'SUNRISE' })).toBe('Mon AM');
    expect(shortWindow({ date: '2026-10-05', targetType: 'SUNSET' })).toBe('Mon PM');
    expect(shortWindow({ date: '2026-10-05', targetType: 'MOONRISE' })).toBeNull();
    expect(shortWindow({ date: '', targetType: 'ASTRO' })).toBeNull();
  });
});
