/**
 * `utils/mapLanding.js` — which windows the landing card compares, what it calls them, and what it
 * says when neither is worth the drive (`docs/engineering/map-landing-plan.md` §3 L4).
 *
 * <p>⚠️ <b>Why this file carries the weight rather than the component.</b> Every rule L4 states is
 * a selection rule — the `served` gate, the header's two branches, the two pick suppressions, the
 * all-Poor branch's "strictly later AND Worth it" — and none of them is reachable through
 * `MapLandingCard` without asserting on rendered text that could pass for the wrong reason. The
 * component renders this model and decides nothing; these tests drive the decisions directly.
 */
import { describe, it, expect } from 'vitest';
import {
  landingRows, landingHeader, elsewherePicks, nextWorthIt, landingCardModel, LANDING_ROW_COUNT,
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

describe('elsewherePicks', () => {
  const events = [
    solar(TODAY, 'SUNRISE', { served: false }),
    solar(TODAY, 'SUNSET', { pickKind: 'also' }),
    solar(TOMORROW, 'SUNRISE'),
    night(TOMORROW),
    solar(THURSDAY, 'SUNSET', { dayLabel: 'Thursday', pickKind: 'best' }),
  ];

  it('names only picks that are NOT one of the two rows', () => {
    const rows = landingRows({ events });

    expect(elsewherePicks({ events, rows })).toEqual([
      expect.objectContaining({ index: 4, kind: 'best' }),
    ]);
  });

  it('suppresses a pick EARLIER than the first row — a window that has passed is not an answer', () => {
    // The elapsed filler leads the list and carries a pick. It is index 0, the first ROW is index 1.
    const withEarlyPick = [{ ...events[0], pickKind: 'best' }, ...events.slice(1)];
    const rows = landingRows({ events: withEarlyPick });

    expect(rows[0].index).toBe(1);
    expect(elsewherePicks({ events: withEarlyPick, rows }).map((p) => p.index)).toEqual([4]);
  });

  it('a pick on the SECOND row is a medallion too — never also a quiet line', () => {
    // ⚠️ Every earlier fixture put the on-row pick on row 0, where the `index <= firstIndex` clause
    // already suppressed it — so `onRows` was dead across the whole suite and deleting it left the
    // tests green while row 1's Best bet rendered twice. Measured against the real module.
    const onSecond = [
      solar(TODAY, 'SUNSET'),
      solar(TOMORROW, 'SUNRISE', { pickKind: 'best' }),
      solar(TOMORROW, 'SUNSET', { pickKind: 'also' }),
    ];
    const rows = landingRows({ events: onSecond });

    expect(rows.map((r) => r.index)).toEqual([0, 1]);
    expect(elsewherePicks({ events: onSecond, rows }).map((p) => p.index)).toEqual([2]);
  });

  it('orders best before also', () => {
    const both = [
      solar(TODAY, 'SUNSET'),
      solar(TOMORROW, 'SUNRISE'),
      solar(TOMORROW, 'SUNSET', { pickKind: 'also' }),
      solar(THURSDAY, 'SUNSET', { dayLabel: 'Thursday', pickKind: 'best' }),
    ];
    const rows = landingRows({ events: both });

    expect(elsewherePicks({ events: both, rows }).map((p) => p.kind)).toEqual(['best', 'also']);
  });

  it('names nothing when there are no rows to be later than', () => {
    expect(elsewherePicks({ events, rows: [] })).toEqual([]);
  });
});

describe('nextWorthIt', () => {
  const events = [
    solar(TODAY, 'SUNSET'),
    solar(TOMORROW, 'SUNRISE'),
    solar(TOMORROW, 'SUNSET'),
    night(THURSDAY),
    solar(THURSDAY, 'SUNSET', { dayLabel: 'Thursday' }),
  ];

  it('names the first WORTH_IT window STRICTLY after the index it is given', () => {
    // ⚠️ Both boundary windows are WORTH_IT on purpose: index 0 (before) and index 1 (`afterIndex`
    // itself). Without the strict `+ 1` the search starts at 1 and returns it — a "next up" line
    // naming one of the two rows the sentence above has just called not worth the drive.
    const verdicts = verdictsOf([
      [`solar:${TODAY}:SUNSET`, 'WORTH_IT'],
      [`solar:${TOMORROW}:SUNRISE`, 'WORTH_IT'],
      [`solar:${THURSDAY}:SUNSET`, 'WORTH_IT'],
    ]);

    const found = nextWorthIt({ events, verdicts, afterIndex: 1 });

    expect(found.index).toBe(4);
  });

  it('never names a MAYBE — the sentence above it has called the drive not worth making', () => {
    const verdicts = verdictsOf([[`solar:${TOMORROW}:SUNSET`, 'MAYBE'], [`solar:${THURSDAY}:SUNSET`, 'MAYBE']]);

    expect(nextWorthIt({ events, verdicts, afterIndex: 1 })).toBeNull();
  });

  it('will not name a window the CARD would refuse to show as a row', () => {
    // ⚠️ The briefing serves up to ten solar windows and renders six, and the verdict index folds
    // the DAYS — so ~four windows carry a served verdict while appearing here as unrendered fillers
    // with no time and no star. Ungated, "Next up" offered a destination the Plan tab does not draw.
    const withFiller = [
      solar(TODAY, 'SUNSET'),
      solar(TOMORROW, 'SUNRISE', { served: false }),
      solar(TOMORROW, 'SUNSET'),
      solar(THURSDAY, 'SUNSET', { dayLabel: 'Thursday' }),
    ];
    const verdicts = verdictsOf([
      [`solar:${TOMORROW}:SUNRISE`, 'WORTH_IT'],
      [`solar:${THURSDAY}:SUNSET`, 'WORTH_IT'],
    ]);

    expect(nextWorthIt({ events: withFiller, verdicts, afterIndex: 0 }).index).toBe(3);
  });

  it('will not send the reader to a window they are AWAY for', () => {
    const withTravel = [
      solar(TODAY, 'SUNSET'),
      solar(TOMORROW, 'SUNRISE', { away: true }),
      solar(THURSDAY, 'SUNSET', { dayLabel: 'Thursday' }),
    ];
    const verdicts = verdictsOf([
      [`solar:${TOMORROW}:SUNRISE`, 'WORTH_IT'],
      [`solar:${THURSDAY}:SUNSET`, 'WORTH_IT'],
    ]);

    expect(nextWorthIt({ events: withTravel, verdicts, afterIndex: 0 }).index).toBe(2);
  });

  it('is null when nothing later is scored at all', () => {
    expect(nextWorthIt({ events, verdicts: new Map(), afterIndex: 1 })).toBeNull();
  });
});

describe('landingCardModel — the all-Poor branch', () => {
  const events = [
    solar(TODAY, 'SUNSET', { dayLabel: 'Tonight', pickKind: 'also' }),
    solar(TOMORROW, 'SUNRISE'),
    solar(THURSDAY, 'SUNSET', { dayLabel: 'Thursday', pickKind: 'best' }),
  ];

  it('stops comparing when BOTH rows are Poor, and points at the next Worth it', () => {
    const verdicts = verdictsOf([
      [`solar:${TODAY}:SUNSET`, 'STAND_DOWN'],
      [`solar:${TOMORROW}:SUNRISE`, 'STAND_DOWN'],
      [`solar:${THURSDAY}:SUNSET`, 'WORTH_IT'],
    ]);

    const model = landingCardModel({ events, verdicts });

    expect(model.allPoor).toBe(true);
    expect(model.lead).toBe('Neither is worth the drive.');
    expect(model.nextUp.index).toBe(2);
    expect(model.nextUp.index).toBeGreaterThan(model.rows[model.rows.length - 1].index);
  });

  it('withholds the elsewhere picks in that branch — one pointer, not two', () => {
    const verdicts = verdictsOf([
      [`solar:${TODAY}:SUNSET`, 'STAND_DOWN'],
      [`solar:${TOMORROW}:SUNRISE`, 'STAND_DOWN'],
      [`solar:${THURSDAY}:SUNSET`, 'WORTH_IT'],
    ]);

    expect(landingCardModel({ events, verdicts }).picks).toEqual([]);
  });

  it('does NOT fire when a row is merely UNRATED — that is a different claim from Poor', () => {
    const verdicts = verdictsOf([[`solar:${TODAY}:SUNSET`, 'STAND_DOWN']]);

    const model = landingCardModel({ events, verdicts });

    expect(model.allPoor).toBe(false);
    expect(model.lead).toBeNull();
    expect(model.nextUp).toBeNull();
    expect(model.picks.map((p) => p.index)).toEqual([2]);
  });

  it('draws "next up" from the SAME population as the rows, so the two cannot disagree', () => {
    // ⚠️ Read this together with the mutation record. A review lens refuted an earlier comment here
    // that excused `afterIndex: rows[last]` vs `rows[0]` as structurally equivalent — and it was
    // right at the time, because `nextWorthIt` then searched a WIDER population than `landingRows`
    // (it gated on neither `served` nor `away`), so an unserved WORTH_IT window between the two rows
    // made the two measurements differ. The fix was to give both one population, which is what this
    // asserts — and which makes that mutation equivalent again, this time by construction rather
    // than by assumption: nothing in the shared population can sit between rows[0] and rows[last].
    // The strictness that CAN still break is `nextWorthIt`'s own `afterIndex + 1`, pinned above.
    const gapped = [
      solar(TODAY, 'SUNSET', { dayLabel: 'Tonight' }),
      solar(TOMORROW, 'SUNRISE', { away: true }),
      solar(TOMORROW, 'SUNSET'),
      solar(THURSDAY, 'SUNSET', { dayLabel: 'Thursday' }),
    ];
    const verdicts = verdictsOf([
      [`solar:${TODAY}:SUNSET`, 'STAND_DOWN'],
      [`solar:${TOMORROW}:SUNRISE`, 'WORTH_IT'],
      [`solar:${TOMORROW}:SUNSET`, 'STAND_DOWN'],
      [`solar:${THURSDAY}:SUNSET`, 'WORTH_IT'],
    ]);

    const model = landingCardModel({ events: gapped, verdicts });

    expect(model.rows.map((r) => r.index)).toEqual([0, 2]);
    expect(model.allPoor).toBe(true);
    // Index 1 is a travel day AND WORTH_IT. It is not a row, so it must not be the answer either.
    expect(model.nextUp.index).toBe(3);
  });

  it('does not fire on an empty card', () => {
    expect(landingCardModel({ events: [], verdicts: new Map() }).allPoor).toBe(false);
  });

  it('drops the comparison word when there is only one row to judge', () => {
    const one = [solar(TODAY, 'SUNSET', { dayLabel: 'Tonight' })];
    const verdicts = verdictsOf([[`solar:${TODAY}:SUNSET`, 'STAND_DOWN']]);

    expect(landingCardModel({ events: one, verdicts }).lead).toBe('Not worth the drive.');
  });

  it('ends the sentence after "drive" when nothing later is Worth it', () => {
    const verdicts = verdictsOf([
      [`solar:${TODAY}:SUNSET`, 'STAND_DOWN'],
      [`solar:${TOMORROW}:SUNRISE`, 'STAND_DOWN'],
      [`solar:${THURSDAY}:SUNSET`, 'MAYBE'],
    ]);

    const model = landingCardModel({ events, verdicts });

    expect(model.lead).toBe('Neither is worth the drive.');
    expect(model.nextUp).toBeNull();
  });
});
