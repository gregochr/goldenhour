import { describe, it, expect } from 'vitest';
import {
  buildPickCards, eventKicker, newestRunLabel, readyBusyLine, resolveSuggestions,
} from '../utils/askModel.js';
import {
  astroRow, auroraRow, briefing, nightPick, pick, slot,
} from './askFixtures.js';

/** Night payloads by date, as the Ask client holds them. */
function nightsOf(over = {}) {
  return {
    astro: new Map([['2026-10-05', [astroRow()]]]),
    aurora: new Map([['2026-10-05', [auroraRow()]]]),
    canSeeAurora: true,
    todayStr: '2026-10-05',
    tomorrowStr: '2026-10-06',
    ...over,
  };
}

describe('buildPickCards — night cards, edge by edge', () => {
  it('shows no aurora figure when the nights object does not say the reader may see aurora', () => {
    const nights = nightsOf();
    delete nights.canSeeAurora;

    const [card] = buildPickCards([nightPick()], [], null, nights);

    expect(card.auroraStars).toBeNull();
    expect(card.label).toBe('Pick 1, Kielder Observatory, Mon night, 4 stars, Tonight astro');
  });

  it('drops an aurora pick when the nights object does not say the reader may see aurora', () => {
    const nights = nightsOf();
    delete nights.canSeeAurora;

    expect(buildPickCards([nightPick({ targetType: 'AURORA' })], [], null, nights)).toEqual([]);
  });

  it('keeps the card but prints no star in its name for a night whose rating is out of scale', () => {
    const nights = nightsOf({
      astro: new Map([['2026-10-05', [astroRow({ stars: 9 })]]]),
      aurora: new Map([['2026-10-05', [auroraRow({ stars: 0 })]]]),
    });

    const [card] = buildPickCards([nightPick()], [], null, nights);

    expect(card.rating).toBeNull();
    expect(card.astroStars).toBeNull();
    expect(card.auroraStars).toBeNull();
    expect(card.label).toBe('Pick 1, Kielder Observatory, Mon night, Tonight astro');
  });

  it('adds the other kind’s star to the label only when it is a usable rating', () => {
    const [card] = buildPickCards([nightPick()], [], null, nightsOf());

    expect(card.label).toBe('Pick 1, Kielder Observatory, Mon night, 4 stars, Tonight astro, aurora 3 stars');
  });

  it('trims a night summary and has none when it is blank', () => {
    const trimmed = buildPickCards([nightPick()], [], null,
      nightsOf({ astro: new Map([['2026-10-05', [astroRow({ summary: '  Dark and clear.  ' })]]]) }));
    const blank = buildPickCards([nightPick()], [], null,
      nightsOf({ astro: new Map([['2026-10-05', [astroRow({ summary: '   ' })]]]) }));

    expect(trimmed[0].summary).toBe('Dark and clear.');
    expect(blank[0].summary).toBeNull();
  });

  it('has no moon phase text for a blank phase, and trims a real one', () => {
    const blank = buildPickCards([nightPick()], [], null,
      nightsOf({ astro: new Map([['2026-10-05', [astroRow({ moonPhase: '   ' })]]]) }));
    const padded = buildPickCards([nightPick()], [], null,
      nightsOf({ astro: new Map([['2026-10-05', [astroRow({ moonPhase: ' Full Moon ' })]]]) }));

    expect(blank[0].moon).toBe('23%');
    expect(padded[0].moon).toBe('Full Moon · 23%');
  });

  it('prints a night window only when both ends are served', () => {
    const noEnd = buildPickCards([nightPick()], [], null,
      nightsOf({ astro: new Map([['2026-10-05', [astroRow({ nightEnd: null })]]]) }));
    const noStart = buildPickCards([nightPick({ targetType: 'AURORA' })], [], null,
      nightsOf({
        aurora: new Map([['2026-10-05', [auroraRow({ nightStart: null })]]]),
        astro: new Map([['2026-10-05', [astroRow({ nightStart: null, nightEnd: '2026-10-06T04:10:00' })]]]),
      }));

    expect(noEnd[0].nightStart).toBe('2026-10-05T19:40:00');
    expect(noEnd[0].nightEnd).toBeNull();
    expect(noEnd[0].nightWindow).toBeNull();
    expect(noStart[0].nightStart).toBeNull();
    expect(noStart[0].nightWindow).toBeNull();
  });

  it('prints the full window when both ends are served', () => {
    const [card] = buildPickCards([nightPick()], [], null, nightsOf());

    expect(card.nightWindow).toBe('20:40–05:10');
  });

  it('falls back to the other kind’s window when the pick’s own row has no night start', () => {
    const nights = nightsOf({
      aurora: new Map([['2026-10-05', [auroraRow({ nightStart: null, nightEnd: null })]]]),
    });

    const [card] = buildPickCards([nightPick({ targetType: 'AURORA' })], [], null, nights);

    expect(card.nightStart).toBe('2026-10-05T19:40:00');
    expect(card.nightEnd).toBe('2026-10-06T04:10:00');
  });

  it('carries no why when the pick served none, and no window id', () => {
    const [card] = buildPickCards([nightPick({ why: undefined, windowId: undefined })], [], null, nightsOf());

    expect(card.why).toBe('');
    expect(card.windowId).toBeNull();
  });

  it('keeps a served why and window id untouched', () => {
    const [card] = buildPickCards([nightPick()], [], null, nightsOf());

    expect(card.why).toBe('Low cloud all night and Bortle 3 — the clearest sky on offer.');
    expect(card.windowId).toBe('2026-10-05_astro');
  });

  it('drops a night pick with no location rather than matching a row that has none', () => {
    const nights = nightsOf({
      astro: new Map([['2026-10-05', [astroRow({ locationId: null })]]]),
    });

    expect(buildPickCards([nightPick({ locationId: null })], [], null, nights)).toEqual([]);
  });

  it('drops a night pick whose date is not a string even if a payload is keyed by that value', () => {
    const nights = nightsOf({
      astro: new Map([[20261005, [astroRow()]]]),
    });

    expect(buildPickCards([nightPick({ date: 20261005 })], [], null, nights)).toEqual([]);
  });
});

describe('buildPickCards — solar cards, edge by edge', () => {
  it('drops a pick whose date is not a string even if the briefing holds a day with that value', () => {
    const days = briefing().days;
    days[0].date = 20261005;

    expect(buildPickCards([pick({ date: 20261005 })], days, null)).toEqual([]);
  });

  it('drops a window kind that is neither a sunrise, a sunset nor a night, even when the briefing holds one', () => {
    const days = briefing().days;
    days[0].eventSummaries.push({
      targetType: 'NOON',
      regions: [{ regionName: 'R', slots: [slot()] }],
      unregioned: [],
    });

    expect(buildPickCards([pick({ targetType: 'NOON' })], days, null)).toEqual([]);
  });

  it('survives holes in the briefing: null days, null summaries and null slots', () => {
    const days = briefing().days;
    days[0].eventSummaries[0].regions[0].slots.unshift(null, slot({ locationId: null, locationName: 'Anon' }));
    days[0].eventSummaries.unshift(null);
    days.unshift(null);

    const [card] = buildPickCards([pick()], days, null);

    expect(card.name).toBe('Whitby');
    expect(card.rating).toBe(5);
  });

  it('carries a null window id, and the served one when there is one', () => {
    const without = buildPickCards([pick({ windowId: undefined })], briefing().days, null)[0];
    const withId = buildPickCards([pick()], briefing().days, null)[0];

    expect(without.windowId).toBeNull();
    expect(withId.windowId).toBe('2026-10-05_sunset');
  });

  it('carries no why when the pick served none', () => {
    const [card] = buildPickCards([pick({ why: undefined })], briefing().days, null);

    expect(card.why).toBe('');
  });

  it('has no summary for a blank or whitespace-only one', () => {
    const empty = briefing().days;
    empty[0].eventSummaries[0].regions[0].slots[0].claudeSummary = '';
    const spaces = briefing().days;
    spaces[0].eventSummaries[0].regions[0].slots[0].claudeSummary = '   ';
    const number = briefing().days;
    number[0].eventSummaries[0].regions[0].slots[0].claudeSummary = 7;

    expect(buildPickCards([pick()], empty, null)[0].summary).toBeNull();
    expect(buildPickCards([pick()], spaces, null)[0].summary).toBeNull();
    expect(buildPickCards([pick()], number, null)[0].summary).toBeNull();
  });

  it('has no cards for no picks even when the briefing is absent', () => {
    expect(buildPickCards([], undefined, null)).toEqual([]);
  });

  it('does not match a slot with no location id to a pick', () => {
    const days = briefing().days;
    days[0].eventSummaries[0].regions[0].slots = [slot({ locationId: undefined, locationName: 'Whitby' })];

    // The id is what the join is on; the name alone does not make a slot the pick's.
    expect(buildPickCards([pick()], days, null)).toEqual([]);
  });
});

describe('resolveSuggestions — edges', () => {
  it('resolves nothing against a missing question list, even for a suggestion with no id', () => {
    expect(resolveSuggestions([{ text: 'x' }], null)).toEqual([]);
    expect(resolveSuggestions([{ text: 'x' }], undefined)).toEqual([]);
  });

  it('does not let a missing suggestion list match a question that has no id', () => {
    expect(resolveSuggestions(null, [{ text: 'no id' }])).toEqual([]);
    expect(resolveSuggestions(undefined, [{ text: 'no id' }])).toEqual([]);
  });

  it('tolerates null questions and null suggestions in their lists', () => {
    const resolved = resolveSuggestions([null, { id: 'A' }], [null, { id: 'A' }]);

    expect(resolved).toEqual([{ id: 'A' }]);
  });
});

describe('newestRunLabel — edges', () => {
  it('skips a question with no usable run label however new it is', () => {
    const questions = [
      { runLabel: null, generatedAt: '2026-10-06T05:00:00' },
      { runLabel: '06:02', generatedAt: '2026-10-05T05:02:11' },
    ];

    expect(newestRunLabel(questions)).toBe('06:02');
  });

  it('tolerates a hole in the list', () => {
    expect(newestRunLabel([null, { runLabel: '06:02', generatedAt: '2026-10-05T05:02:11' }])).toBe('06:02');
  });

  it('keeps the first of two runs generated at the same instant', () => {
    const questions = [
      { runLabel: '06:02', generatedAt: '2026-10-05T05:02:11' },
      { runLabel: '06:03', generatedAt: '2026-10-05T05:02:11' },
    ];

    expect(newestRunLabel(questions)).toBe('06:02');
  });

  it('takes the later of two runs whichever order they are served in', () => {
    const early = { runLabel: '06:02', generatedAt: '2026-10-05T05:02:11' };
    const late = { runLabel: '18:03', generatedAt: '2026-10-05T17:03:40' };

    expect(newestRunLabel([early, late])).toBe('18:03');
    expect(newestRunLabel([late, early])).toBe('18:03');
  });
});

describe('readyBusyLine and eventKicker — edges', () => {
  it.each([
    ['06:02', 'Opening this morning’s answer'],
    ['6:02', 'Opening this morning’s answer'],
    ['11:59', 'Opening this morning’s answer'],
    ['12:00', 'Opening this evening’s answer'],
    ['18:03', 'Opening this evening’s answer'],
  ])('reads %s as %s', (label, line) => {
    expect(readyBusyLine(label)).toBe(line);
  });

  it.each([
    ['06:022'],
    ['x06:02'],
    ['106:02'],
    ['123:45'],
    ['06:02 '],
    [' 06:02'],
    ['abc'],
    [''],
    [null],
    [undefined],
  ])('says neither morning nor evening for the unreadable label %j', (label) => {
    expect(readyBusyLine(label)).toBe('Opening the answer');
  });

  it('spells an event type for reading and trims the space it leaves', () => {
    expect(eventKicker('LUNAR_ECLIPSE')).toBe('LUNAR ECLIPSE');
    expect(eventKicker('_ECLIPSE_')).toBe('ECLIPSE');
    expect(eventKicker(' ECLIPSE ')).toBe('ECLIPSE');
    expect(eventKicker(null)).toBe('');
    expect(eventKicker(undefined)).toBe('');
  });
});
