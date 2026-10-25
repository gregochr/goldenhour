/**
 * `utils/mapLanding.js` — which two solar windows are "your next two" and what the heading over them
 * says (`docs/engineering/map-landing-plan.md` §3 L4; the card itself was removed by
 * `map-landing-nocard-plan.md` P1).
 *
 * <p>⚠️ <b>Why this file carries the weight rather than a component.</b> Every rule here is a
 * selection rule — the `served` gate, the away-day drop, the header's two branches — and none of them
 * is reachable through a mounted map without asserting on rendered text that could pass for the wrong
 * reason. These tests drive the decisions directly; `MapViewNoLandingCard.test.jsx` pins the one
 * mounted reader (the phone sheet's Windows heading).
 */
import { describe, it, expect } from 'vitest';
import {
  landingRows, landingHeader, LANDING_ROW_COUNT,
} from '../utils/mapLanding.js';
import { EVENT_KIND } from '../utils/mapEvents.js';

const TODAY = '2026-01-15';
const TOMORROW = '2026-01-16';
const THURSDAY = '2026-01-22';

/** A served solar EV row, the shape `mapEvents.solarRow` builds for a briefing window. */
function solar(date, eventType, {
  dayLabel, label, time = '16:12', pickKind = null, served = true, away = false,
} = {}) {
  const word = eventType === 'SUNRISE' ? 'sunrise' : 'sunset';
  const day = dayLabel ?? (date === TODAY ? 'Today' : 'Tomorrow');
  return {
    id: `solar:${date}:${eventType}`,
    kind: EVENT_KIND.SOLAR,
    eventType,
    date,
    label: label ?? `${day} ${word}`,
    dayLabel: day,
    time,
    served,
    away,
    scored: served,
    pickKind,
  };
}

/** A night row — never a landing row, never a pick, never the "next up" answer. */
function night(date, kind = EVENT_KIND.ASTRO) {
  return {
    id: `${kind}:${date}`, kind, eventType: 'ASTRO', date, label: `${date} night`, dayLabel: date, time: '',
    served: true, scored: true, pickKind: null,
  };
}

const verdictsOf = (pairs) => new Map(pairs.map(([id, tier]) => [id, { tier, regionName: 'The Lakes', sharingCount: 0, allInScope: false }]));

describe('landingRows', () => {
  it('takes the next two SOLAR windows, in list order', () => {
    const events = [solar(TODAY, 'SUNSET'), solar(TOMORROW, 'SUNRISE'), solar(TOMORROW, 'SUNSET')];

    const rows = landingRows({ events });

    expect(rows.map((r) => r.row.id)).toEqual([
      `solar:${TODAY}:SUNSET`, `solar:${TOMORROW}:SUNRISE`,
    ]);
    expect(rows.map((r) => r.index)).toEqual([0, 1]);
  });

  it('SKIPS an unserved D-13 filler row, even when it leads the list', () => {
    // The defect this gate exists for: from the moment this morning's sunrise passes, the EV list
    // still leads with a filler SUNRISE row for a window hours in the past (`mapEvents.js` gates
    // fillers on `date >= todayStr` alone). Taking "the first two solar rows" opens the card on a
    // window the reader cannot reach.
    const events = [
      solar(TODAY, 'SUNRISE', { served: false, time: '' }),
      solar(TODAY, 'SUNSET'),
      solar(TOMORROW, 'SUNRISE'),
    ];

    const rows = landingRows({ events });

    expect(rows.map((r) => r.row.id)).toEqual([
      `solar:${TODAY}:SUNSET`, `solar:${TOMORROW}:SUNRISE`,
    ]);
    // The INDEX is the row's place in the full EV list, not in the pair — every "strictly later
    // than" rule below is stated against it.
    expect(rows.map((r) => r.index)).toEqual([1, 2]);
  });

  it('SKIPS a travel day — a window you are away for is not one of "your next two"', () => {
    // `buildHeatStripCards` publishes every window the strip must show, away days included, so an
    // away window arrives here as an ordinary served row. Without this the card opened on
    // "Tomorrow — sunrise or sunset?" over two days the reader is not there for, as live buttons,
    // while the Plan matrix drew the same two cards as Away. CLAUDE.md: a travel day is a div.
    const events = [
      solar(TODAY, 'SUNSET', { away: true }),
      solar(TOMORROW, 'SUNRISE', { away: true }),
      solar(TOMORROW, 'SUNSET'),
      solar(THURSDAY, 'SUNSET', { dayLabel: 'Thursday' }),
    ];

    expect(landingRows({ events }).map((r) => r.index)).toEqual([2, 3]);
  });

  /**
   * ⚠️ **The card is a TWO-row card, and that is a design fact rather than an accident of the
   * fixture.** L7's orphan sweep found `LANDING_ROW_COUNT` exported with no reader anywhere — not
   * production (its only use is `landingRows`' own default) and not a test. Asserting the number
   * here is what gives the export a reason to exist: it pins "should I go tonight or in the
   * morning" as a question about exactly two windows, so widening the card becomes a deliberate
   * edit rather than a silent one.
   */
  it('takes exactly LANDING_ROW_COUNT rows, and that is two', () => {
    const events = [
      solar(TODAY, 'SUNSET'), solar(TOMORROW, 'SUNRISE'),
      solar(TOMORROW, 'SUNSET'), solar(THURSDAY, 'SUNSET'),
    ];

    expect(LANDING_ROW_COUNT).toBe(2);
    expect(landingRows({ events })).toHaveLength(LANDING_ROW_COUNT);
    // …and the default is the constant, not a literal that happens to agree with it.
    expect(landingRows({ events, count: 3 })).toHaveLength(3);
  });

  it('skips night rows — they carry no per-region rollup and answer a different question', () => {
    const events = [night(TODAY), solar(TODAY, 'SUNSET'), night(TODAY, EVENT_KIND.AURORA), solar(TOMORROW, 'SUNRISE')];

    expect(landingRows({ events }).map((r) => r.row.kind)).toEqual(['solar', 'solar']);
  });

  it('attaches each row\'s verdict, and null for a served window nothing is rated in', () => {
    const events = [solar(TODAY, 'SUNSET'), solar(TOMORROW, 'SUNRISE')];
    const verdicts = verdictsOf([[`solar:${TODAY}:SUNSET`, 'WORTH_IT']]);

    const rows = landingRows({ events, verdicts });

    expect(rows[0].verdict.tier).toBe('WORTH_IT');
    expect(rows[1].verdict).toBeNull();
  });

  it('returns what it has when fewer than two windows are ahead', () => {
    expect(landingRows({ events: [solar(TODAY, 'SUNSET')] })).toHaveLength(1);
    expect(landingRows({ events: [] })).toEqual([]);
    expect(landingRows({ events: null })).toEqual([]);
  });
});

describe('landingHeader', () => {
  it('poses the two events when both rows fall on ONE day', () => {
    const rows = landingRows({ events: [solar(TOMORROW, 'SUNRISE'), solar(TOMORROW, 'SUNSET')] });

    expect(landingHeader(rows)).toBe('Tomorrow — sunrise or sunset?');
  });

  it('poses the two DAYS when the rows fall on different ones', () => {
    const rows = landingRows({
      events: [solar(TODAY, 'SUNSET', { dayLabel: 'Tonight' }), solar(TOMORROW, 'SUNRISE')],
    });

    expect(landingHeader(rows)).toBe('Tonight, or tomorrow?');
  });

  it('does NOT lower-case a weekday — it is a proper noun, not a relative day word', () => {
    // The bundle lower-cases the second label unconditionally, which is right for its own
    // Tonight/Tomorrow vocabulary and a grammar error against this app's, where `dayLabelFor`
    // returns a full weekday name past T+1.
    const rows = landingRows({
      events: [
        solar(THURSDAY, 'SUNSET', { dayLabel: 'Thursday' }),
        solar('2026-01-23', 'SUNRISE', { dayLabel: 'Friday' }),
      ],
    });

    expect(landingHeader(rows)).toBe('Thursday, or Friday?');
  });

  it('lower-cases every relative day word the app can produce, and only those', () => {
    // ⚠️ `dayLabelFor` returns Today / Tomorrow / a weekday, and `buildWindowCards` adds Tonight as
    // the lead sunset's kicker. Only `tomorrow` was ever exercised in the trailing position, so
    // deleting `today` or `tonight` from the set left the suite green.
    const pair = (a, b) => landingHeader(landingRows({
      events: [solar(TODAY, 'SUNSET', { dayLabel: a }), solar(TOMORROW, 'SUNRISE', { dayLabel: b })],
    }));

    expect(pair('Tonight', 'Today')).toBe('Tonight, or today?');
    expect(pair('Today', 'Tonight')).toBe('Today, or tonight?');
    expect(pair('Today', 'Tomorrow')).toBe('Today, or tomorrow?');
    expect(pair('Tomorrow', 'Thursday')).toBe('Tomorrow, or Thursday?');
  });

  it('poses the app\'s OWN same-day wording, not the bundle\'s', () => {
    // The shipped same-day header on a today-pair is "Today — …", never the bundle's "Tonight".
    const rows = landingRows({
      events: [solar(TODAY, 'SUNRISE', { dayLabel: 'Today' }), solar(TODAY, 'SUNSET', { dayLabel: 'Tonight' })],
    });

    expect(landingHeader(rows)).toBe('Today — sunrise or sunset?');
  });

  it('poses no comparison with one row, and says nothing with none', () => {
    expect(landingHeader(landingRows({ events: [solar(TODAY, 'SUNSET')] }))).toBe('Your next window');
    expect(landingHeader([])).toBe('');
    expect(landingHeader(null)).toBe('');
  });
});
