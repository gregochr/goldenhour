import { describe, it, expect } from 'vitest';
import { buildPaneItems } from '../utils/windowFirstAway.js';

/** Cards as `buildWindowCards` emits them — only the two fields this module reads. */
const cardFor = (date, targetType) => ({ key: `${date}:${targetType}`, date, targetType });

/**
 * ⚠️ The block's rendered payload — `label`, `note`, `windowCount`, and the `awayDateLabel` and
 * `noteFor` helpers behind them — went at M5 with the promoted strip, which was their last reader
 * (plan §7's M5 row, and M2's own note that this module "drops to a `length === 0` check once the
 * promoted strip goes"). The suites that pinned the label's UTC parsing, the range-note's per-day
 * coverage rule and the window count went with the code, rather than being kept green against a
 * derivation nothing renders.
 *
 * <p>What is pinned here is the shape that still has a reader: one item per rendered thing, in date
 * order, with a run of consecutive away days folded into one — the empty-state line's denominator.
 * Every assertion below is on `kind`, `key`, `dates` or `card`, so a fixture cannot pass by carrying
 * a field the deriver no longer emits.
 */
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
    const items = buildPaneItems(events.slice(0, 2), cards, new Set());

    expect(items.map((i) => i.kind)).toEqual(['card', 'card']);
    expect(items.map((i) => i.card)).toEqual(cards);
  });

  it('puts the away block where the missing days fall, not after the whole list', () => {
    // The pane's only ordering spine is time — the same rule Hot Topics settled for a multi-day
    // tide run. A gap between Tuesday and Friday belongs between Tuesday and Friday; the mock's
    // appended block reads as a footnote and hides which days it describes.
    const cards = [cardFor('2026-08-04', 'SUNSET'), cardFor('2026-08-07', 'SUNSET')];
    const away = new Set(['2026-08-05', '2026-08-06']);

    const items = buildPaneItems(events, cards, away);

    expect(items.map((i) => i.kind)).toEqual(['card', 'away', 'card']);
    expect(items[0].card.date).toBe('2026-08-04');
    expect(items[2].card.date).toBe('2026-08-07');
  });

  it('folds a run of consecutive away days into one block', () => {
    const cards = [cardFor('2026-08-04', 'SUNSET'), cardFor('2026-08-07', 'SUNSET')];
    const items = buildPaneItems(events, cards, new Set(['2026-08-05', '2026-08-06']));

    expect(items.filter((i) => i.kind === 'away')).toHaveLength(1);
    expect(items[1].dates).toEqual(['2026-08-05', '2026-08-06']);
  });

  it('lists a day once however many of its windows the run swallows', () => {
    // 5 Aug contributes two events and 6 Aug one. The dates are a SET of days, not a tally of
    // windows: the count that used to ride beside them had one consumer and went with it.
    const cards = [cardFor('2026-08-04', 'SUNSET'), cardFor('2026-08-07', 'SUNSET')];
    const items = buildPaneItems(events, cards, new Set(['2026-08-05', '2026-08-06']));

    expect(items[1].dates).toEqual(['2026-08-05', '2026-08-06']);
  });

  it('keeps two runs either side of a forecast day as two blocks', () => {
    // They describe different gaps. Merging them would claim the middle day was away too.
    const cards = [cardFor('2026-08-05', 'SUNSET')];
    const items = buildPaneItems(events, cards, new Set(['2026-08-04', '2026-08-06', '2026-08-07']));

    const blocks = items.filter((i) => i.kind === 'away');
    expect(blocks).toHaveLength(2);
    expect(blocks[0].dates).toEqual(['2026-08-04']);
    expect(blocks[1].dates).toEqual(['2026-08-06', '2026-08-07']);
  });

  it('splits a run across a calendar gap the event list cannot see', () => {
    // The walk is over EVENTS, so a date contributing none of them is invisible to it. Two away
    // days either side of such a date would have folded into one block spanning Wed 5 – Fri 7,
    // asserting Thursday was a travel day when nothing in the payload says so.
    const gapped = [
      { date: '2026-08-05', targetType: 'SUNSET' },
      { date: '2026-08-07', targetType: 'SUNSET' },
    ];
    const items = buildPaneItems(gapped, [], new Set(['2026-08-05', '2026-08-07']));

    expect(items.map((i) => i.dates)).toEqual([['2026-08-05'], ['2026-08-07']]);
  });

  it('keys each block on the day it starts, so two blocks never share a React key', () => {
    const items = buildPaneItems(events, [cardFor('2026-08-05', 'SUNSET')],
      new Set(['2026-08-04', '2026-08-06', '2026-08-07']));

    expect(items.filter((i) => i.kind === 'away').map((i) => i.key))
      .toEqual(['away:2026-08-04', 'away:2026-08-06']);
  });

  it('yields nothing at all when there are no events', () => {
    expect(buildPaneItems([], [], new Set())).toEqual([]);
  });

  it('tolerates a null event list and a null travel set', () => {
    // The provider hands these over before the travel-day fetch resolves, on every cold mount.
    expect(buildPaneItems(null, [], null)).toEqual([]);
    const items = buildPaneItems([{ date: '2026-08-04', targetType: 'SUNSET' }],
      [cardFor('2026-08-04', 'SUNSET')], null);
    expect(items.map((i) => i.kind)).toEqual(['card']);
  });

  it('emits an away block for a day that is away with no forecast day at all beside it', () => {
    // Every rendered day away: the pane would otherwise be entirely empty and say "No windows to
    // show", which reads as a failed forecast rather than a fortnight off. This is the one reader
    // the derivation still has, so it is the one that must not regress.
    const items = buildPaneItems(events.slice(0, 2), [], new Set(['2026-08-04', '2026-08-05']));

    expect(items.map((i) => i.kind)).toEqual(['away']);
    expect(items[0].dates).toEqual(['2026-08-04', '2026-08-05']);
    expect(items).not.toHaveLength(0);
  });

  it('drops a card position it has no card for rather than emitting a hole', () => {
    // Defensive: a caller that filtered the cards differently would otherwise put `undefined` in
    // the list and crash the pane on `item.card.key`.
    const items = buildPaneItems(events.slice(0, 2), [cardFor('2026-08-04', 'SUNSET')], new Set());

    expect(items).toHaveLength(1);
    expect(items[0].card.date).toBe('2026-08-04');
  });

  it('emits no field the pane does not read', () => {
    // The deletion's own pin. `label`/`note`/`windowCount` were dead weight the moment the promoted
    // strip went, and dead weight comes back by being re-added "for completeness" — so the away
    // block's key set is asserted exactly rather than by absence of any one name.
    const cards = [cardFor('2026-08-04', 'SUNSET'), cardFor('2026-08-07', 'SUNSET')];
    const [, block] = buildPaneItems(events, cards, new Set(['2026-08-05', '2026-08-06']));

    expect(Object.keys(block).sort()).toEqual(['dates', 'key', 'kind']);
  });
});
