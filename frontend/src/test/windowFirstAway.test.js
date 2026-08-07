import { describe, it, expect } from 'vitest';
import { awayDateLabel, buildPaneItems } from '../utils/windowFirstAway.js';

/** Cards as `buildWindowCards` emits them — only the two fields this module reads. */
const cardFor = (date, targetType) => ({ key: `${date}:${targetType}`, date, targetType });

const RANGE = { startDate: '2026-08-05', endDate: '2026-08-06', note: 'Business trip' };

describe('awayDateLabel', () => {
  it('names one day on its own rather than a range of length one', () => {
    // "Wed 5 – Wed 5" is the kind of thing a range formatter emits and nobody proof-reads.
    expect(awayDateLabel(['2026-08-05'])).toBe('Wed 5');
  });

  it('names both ends of a longer run', () => {
    expect(awayDateLabel(['2026-08-05', '2026-08-06', '2026-08-07'])).toBe('Wed 5 – Fri 7');
  });

  it('reads the date as UTC, so a BST evening does not shift the day back one', () => {
    // The dates are Europe/London calendar dates from the briefing, not instants. Parsing at
    // midnight would put a 1 August date on 31 July for anyone west of UTC.
    expect(awayDateLabel(['2026-08-01'])).toBe('Sat 1');
  });
});

describe('buildPaneItems', () => {
  const events = [
    { date: '2026-08-04', targetType: 'SUNSET' },
    { date: '2026-08-05', targetType: 'SUNRISE' },
    { date: '2026-08-05', targetType: 'SUNSET' },
    { date: '2026-08-06', targetType: 'SUNRISE' },
    { date: '2026-08-07', targetType: 'SUNSET' },
  ];

  it('passes the cards straight through when no day is a travel day', () => {
    const cards = [cardFor('2026-08-04', 'SUNSET'), cardFor('2026-08-05', 'SUNRISE')];
    const items = buildPaneItems(events.slice(0, 2), cards, new Set(), []);

    expect(items.map((i) => i.kind)).toEqual(['card', 'card']);
    expect(items.map((i) => i.card)).toEqual(cards);
  });

  it('puts the away row where the missing days fall, not after the whole list', () => {
    // The pane's only ordering spine is time — the same rule Hot Topics settled for a multi-day
    // tide run. A gap between Tuesday and Friday belongs between Tuesday and Friday; the mock's
    // appended block reads as a footnote and hides which days it describes.
    const cards = [cardFor('2026-08-04', 'SUNSET'), cardFor('2026-08-07', 'SUNSET')];
    const away = new Set(['2026-08-05', '2026-08-06']);

    const items = buildPaneItems(events, cards, away, [RANGE]);

    expect(items.map((i) => i.kind)).toEqual(['card', 'away', 'card']);
    expect(items[0].card.date).toBe('2026-08-04');
    expect(items[2].card.date).toBe('2026-08-07');
  });

  it('folds a run of consecutive away days into one row', () => {
    const cards = [cardFor('2026-08-04', 'SUNSET'), cardFor('2026-08-07', 'SUNSET')];
    const items = buildPaneItems(events, cards, new Set(['2026-08-05', '2026-08-06']), [RANGE]);

    expect(items.filter((i) => i.kind === 'away')).toHaveLength(1);
    expect(items[1].label).toBe('Wed 5 – Thu 6');
    expect(items[1].dates).toEqual(['2026-08-05', '2026-08-06']);
  });

  it('counts the windows lost, not the days — a day can take two of them', () => {
    // 5 Aug contributes a sunrise AND a sunset; 6 Aug only a sunrise. Counting days would say 2
    // where three windows are missing, and the number's whole job is to account for the slots the
    // six-event cap already spent.
    const cards = [cardFor('2026-08-04', 'SUNSET'), cardFor('2026-08-07', 'SUNSET')];
    const items = buildPaneItems(events, cards, new Set(['2026-08-05', '2026-08-06']), [RANGE]);

    expect(items[1].windowCount).toBe(3);
  });

  it('keeps two runs either side of a forecast day as two rows', () => {
    // They describe different gaps. Merging them would claim the middle day was away too.
    const cards = [cardFor('2026-08-05', 'SUNSET')];
    const items = buildPaneItems(events, cards, new Set(['2026-08-04', '2026-08-06', '2026-08-07']), []);

    const rows = items.filter((i) => i.kind === 'away');
    expect(rows).toHaveLength(2);
    expect(rows[0].label).toBe('Tue 4');
    expect(rows[1].label).toBe('Thu 6 – Fri 7');
  });

  it('carries the travel range\'s own note, so nothing is invented', () => {
    const items = buildPaneItems(events, [cardFor('2026-08-04', 'SUNSET'),
      cardFor('2026-08-07', 'SUNSET')], new Set(['2026-08-05', '2026-08-06']), [RANGE]);

    expect(items[1].note).toBe('Business trip');
  });

  it('carries no note when the run is spanned by two ranges that disagree', () => {
    // There is no honest way to attribute one sentence to both days, so the row says "away" and
    // stops. Silence beats naming a reason that describes half of it.
    const ranges = [
      { startDate: '2026-08-05', endDate: '2026-08-05', note: 'Business trip' },
      { startDate: '2026-08-06', endDate: '2026-08-06', note: 'Wedding' },
    ];
    const items = buildPaneItems(events, [cardFor('2026-08-04', 'SUNSET'),
      cardFor('2026-08-07', 'SUNSET')], new Set(['2026-08-05', '2026-08-06']), ranges);

    expect(items[1].note).toBeNull();
  });

  it('carries no note when one day of the run is covered only by an un-noted range', () => {
    // Found by review. Discarding un-noted ranges BEFORE testing coverage left exactly one note in
    // the set, so the row printed "Skye" against a label covering Thursday as well — a claim no
    // range in the payload makes. Un-noted ranges are the ordinary case, and the backend does not
    // merge adjacent ones, so this shape is not contrived.
    const ranges = [
      { startDate: '2026-08-05', endDate: '2026-08-05', note: 'Skye' },
      { startDate: '2026-08-06', endDate: '2026-08-06', note: null },
    ];
    const items = buildPaneItems(events, [cardFor('2026-08-04', 'SUNSET'),
      cardFor('2026-08-07', 'SUNSET')], new Set(['2026-08-05', '2026-08-06']), ranges);

    expect(items[1].note).toBeNull();
  });

  it('still carries the note when two adjacent ranges agree on it', () => {
    // The fix for the case above must not be `some` → `every`: that would silence this, which works
    // today and is what an operator gets from two separately-entered legs of one trip.
    const ranges = [
      { startDate: '2026-08-05', endDate: '2026-08-05', note: 'Skye' },
      { startDate: '2026-08-06', endDate: '2026-08-06', note: 'Skye' },
    ];
    const items = buildPaneItems(events, [cardFor('2026-08-04', 'SUNSET'),
      cardFor('2026-08-07', 'SUNSET')], new Set(['2026-08-05', '2026-08-06']), ranges);

    expect(items[1].note).toBe('Skye');
  });

  it('carries the note when one range spans the whole run', () => {
    const items = buildPaneItems(events, [cardFor('2026-08-04', 'SUNSET'),
      cardFor('2026-08-07', 'SUNSET')], new Set(['2026-08-05', '2026-08-06']), [RANGE]);

    expect(items[1].note).toBe('Business trip');
  });

  it('carries no note when the run is spanned but one day has two notes of its own', () => {
    const ranges = [
      { startDate: '2026-08-05', endDate: '2026-08-06', note: 'Skye' },
      { startDate: '2026-08-05', endDate: '2026-08-05', note: 'Wedding' },
    ];
    const items = buildPaneItems(events, [cardFor('2026-08-04', 'SUNSET'),
      cardFor('2026-08-07', 'SUNSET')], new Set(['2026-08-05', '2026-08-06']), ranges);

    expect(items[1].note).toBeNull();
  });

  it('splits a run across a calendar gap the event list cannot see', () => {
    // The walk is over EVENTS, so a date contributing none of them is invisible to it. Two away
    // days either side of such a date would have folded into one row labelled "Wed 5 – Fri 7",
    // asserting Thursday was a travel day when nothing in the payload says so.
    const gapped = [
      { date: '2026-08-05', targetType: 'SUNSET' },
      { date: '2026-08-07', targetType: 'SUNSET' },
    ];
    const items = buildPaneItems(gapped, [], new Set(['2026-08-05', '2026-08-07']), []);

    expect(items.map((i) => i.label)).toEqual(['Wed 5', 'Fri 7']);
  });

  it('carries no note when the ranges have none, which is the normal case', () => {
    const items = buildPaneItems(events, [cardFor('2026-08-04', 'SUNSET'),
      cardFor('2026-08-07', 'SUNSET')], new Set(['2026-08-05', '2026-08-06']),
    [{ startDate: '2026-08-05', endDate: '2026-08-06', note: null }]);

    expect(items[1].note).toBeNull();
  });

  it('ignores a range that covers no day in the run', () => {
    const ranges = [{ startDate: '2026-09-01', endDate: '2026-09-03', note: 'Next month' }];
    const items = buildPaneItems(events, [cardFor('2026-08-04', 'SUNSET'),
      cardFor('2026-08-07', 'SUNSET')], new Set(['2026-08-05', '2026-08-06']), ranges);

    expect(items[1].note).toBeNull();
  });

  it('yields nothing at all when there are no events', () => {
    expect(buildPaneItems([], [], new Set(), [])).toEqual([]);
  });

  it('tolerates a null event list, a null travel set and null ranges', () => {
    // The provider hands these over before the travel-day fetch resolves, on every cold mount.
    expect(buildPaneItems(null, [], null, null)).toEqual([]);
    const items = buildPaneItems([{ date: '2026-08-04', targetType: 'SUNSET' }],
      [cardFor('2026-08-04', 'SUNSET')], null, null);
    expect(items.map((i) => i.kind)).toEqual(['card']);
  });

  it('emits an away row for a day that is away with no forecast day at all beside it', () => {
    // Every rendered day away: the pane would otherwise be entirely empty and say "No windows to
    // show", which reads as a failed forecast rather than a fortnight off.
    const items = buildPaneItems(events.slice(0, 2), [],
      new Set(['2026-08-04', '2026-08-05']), [RANGE]);

    expect(items.map((i) => i.kind)).toEqual(['away']);
    expect(items[0].windowCount).toBe(2);
    expect(items[0].label).toBe('Tue 4 – Wed 5');
  });

  it('drops a card position it has no card for rather than emitting a hole', () => {
    // Defensive: a caller that filtered the cards differently would otherwise put `undefined` in
    // the list and crash the pane on `item.card.key`.
    const items = buildPaneItems(events.slice(0, 2), [cardFor('2026-08-04', 'SUNSET')],
      new Set(), []);

    expect(items).toHaveLength(1);
    expect(items[0].card.date).toBe('2026-08-04');
  });
});
