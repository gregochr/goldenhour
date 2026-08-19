import { describe, it, expect } from 'vitest';
import {
  orderPaneItems, PLAN_ORDERS, PLAN_ORDER_BEST, PLAN_ORDER_IDS, PLAN_ORDER_WHEN,
} from '../utils/windowFirstOrder.js';

/**
 * The Plan pane's ordering axis.
 *
 * <p>Two rules carry most of the value here and both are about not lying with an ordinal. An
 * unrated window ranks LAST rather than as a zero, because AWAITING means "not looked at yet" and
 * not "looked at and poor". And an away day gets no rank at all — a day with no forecast has no
 * position in a ranking of forecasts, so slotting it between two ranked cards would imply one.
 */

/** A pane item as `buildPaneItems` emits it, carrying only what the sort reads. */
function item(date, targetType, topMeanRating) {
  return {
    kind: 'card',
    key: `${date}:${targetType}`,
    card: { key: `${date}:${targetType}`, date, targetType, topMeanRating },
  };
}

const away = (date) => ({ kind: 'away', key: `away:${date}`, dates: [date], windowCount: 2 });

const keysOf = (items) => items.map((i) => i.key);

describe('orderPaneItems — When', () => {
  it('returns the list untouched, identity included', () => {
    // Identity matters: the shell memoises on the result, and a fresh array every render would
    // rebuild the pane on every health tick for a setting nobody has changed.
    const items = [item('2026-08-04', 'SUNSET', 2), item('2026-08-05', 'SUNRISE', 5)];
    expect(orderPaneItems(items, PLAN_ORDER_WHEN)).toBe(items);
  });

  it('numbers nothing, because a number in date order is an ordinal for no ranking', () => {
    const items = [item('2026-08-04', 'SUNSET', 2), item('2026-08-05', 'SUNRISE', 5)];
    orderPaneItems(items, PLAN_ORDER_WHEN).forEach((i) => expect(i.rank).toBeUndefined());
  });

  it('treats an unrecognised order as the default rather than sorting on nothing', () => {
    const items = [item('2026-08-04', 'SUNSET', 2), item('2026-08-05', 'SUNRISE', 5)];
    expect(orderPaneItems(items, 'sideways')).toBe(items);
    expect(orderPaneItems(items, undefined)).toBe(items);
  });
});

describe('orderPaneItems — Best', () => {
  it('ranks by the best region mean, highest first', () => {
    const items = [
      item('2026-08-04', 'SUNSET', 2.4),
      item('2026-08-05', 'SUNRISE', 4.6),
      item('2026-08-05', 'SUNSET', 3.1),
    ];

    expect(keysOf(orderPaneItems(items, PLAN_ORDER_BEST)))
      .toEqual(['2026-08-05:SUNRISE', '2026-08-05:SUNSET', '2026-08-04:SUNSET']);
  });

  it('numbers the ranked cards from 1', () => {
    const items = [item('2026-08-04', 'SUNSET', 2), item('2026-08-05', 'SUNRISE', 5)];
    expect(orderPaneItems(items, PLAN_ORDER_BEST).map((i) => i.rank)).toEqual([1, 2]);
  });

  it('does not mutate the item it ranks, so the chronological list survives', () => {
    // The provider hands ONE `paneItems` array to both this and `buildPromotedStrip`, which reads
    // date order to decide whether its "Go to" control would scroll to the element directly beneath
    // it. Writing `rank` onto the original object would leak a ranking into that answer.
    const items = [item('2026-08-04', 'SUNSET', 2), item('2026-08-05', 'SUNRISE', 5)];
    const ranked = orderPaneItems(items, PLAN_ORDER_BEST);

    expect(items.map((i) => i.key)).toEqual(['2026-08-04:SUNSET', '2026-08-05:SUNRISE']);
    expect(items[0].rank).toBeUndefined();
    expect(ranked[0]).not.toBe(items[1]);
  });

  it('ranks an unrated window LAST, not as a zero', () => {
    // A 1★ window is a forecast; an AWAITING one is the absence of a forecast. Sorting null as 0
    // would put "we have not looked" below "we looked and it is bad", which is a claim.
    const items = [
      item('2026-08-04', 'SUNSET', null),
      item('2026-08-05', 'SUNRISE', 1),
    ];

    expect(keysOf(orderPaneItems(items, PLAN_ORDER_BEST)))
      .toEqual(['2026-08-05:SUNRISE', '2026-08-04:SUNSET']);
  });

  it('treats a non-finite mean as unrated rather than sorting on NaN', () => {
    const items = [
      item('2026-08-04', 'SUNSET', Number.NaN),
      item('2026-08-05', 'SUNRISE', 2),
    ];
    expect(keysOf(orderPaneItems(items, PLAN_ORDER_BEST)))
      .toEqual(['2026-08-05:SUNRISE', '2026-08-04:SUNSET']);
  });

  it('keeps several unrated windows in date order among themselves', () => {
    const items = [
      item('2026-08-06', 'SUNRISE', null),
      item('2026-08-04', 'SUNSET', null),
      item('2026-08-05', 'SUNSET', null),
    ];
    expect(keysOf(orderPaneItems(items, PLAN_ORDER_BEST)))
      .toEqual(['2026-08-04:SUNSET', '2026-08-05:SUNSET', '2026-08-06:SUNRISE']);
  });

  it('breaks a tie on the date, so the comparator is total', () => {
    // Six windows over four days make equal means ordinary rather than exotic, and a partial
    // comparator would leave the order at the mercy of the engine's sort stability.
    const items = [
      item('2026-08-06', 'SUNSET', 3),
      item('2026-08-04', 'SUNSET', 3),
      item('2026-08-05', 'SUNSET', 3),
    ];
    expect(keysOf(orderPaneItems(items, PLAN_ORDER_BEST)))
      .toEqual(['2026-08-04:SUNSET', '2026-08-05:SUNSET', '2026-08-06:SUNSET']);
  });

  it('breaks a same-day tie with sunrise first, the order they fall in', () => {
    const items = [
      item('2026-08-04', 'SUNSET', 3),
      item('2026-08-04', 'SUNRISE', 3),
    ];
    expect(keysOf(orderPaneItems(items, PLAN_ORDER_BEST)))
      .toEqual(['2026-08-04:SUNRISE', '2026-08-04:SUNSET']);
  });

  it('sinks the away rows below every ranked card', () => {
    const items = [
      item('2026-08-04', 'SUNSET', 2),
      away('2026-08-05'),
      item('2026-08-06', 'SUNRISE', 5),
    ];

    expect(keysOf(orderPaneItems(items, PLAN_ORDER_BEST)))
      .toEqual(['2026-08-06:SUNRISE', '2026-08-04:SUNSET', 'away:2026-08-05']);
  });

  it('keeps several away rows in date order below the cards', () => {
    const items = [away('2026-08-05'), item('2026-08-04', 'SUNSET', 2), away('2026-08-07')];
    expect(keysOf(orderPaneItems(items, PLAN_ORDER_BEST)))
      .toEqual(['2026-08-04:SUNSET', 'away:2026-08-05', 'away:2026-08-07']);
  });

  it('gives an away row no rank, because a day with no forecast has no place in a ranking', () => {
    const items = [item('2026-08-04', 'SUNSET', 2), away('2026-08-05')];
    const ranked = orderPaneItems(items, PLAN_ORDER_BEST);

    expect(ranked[0].rank).toBe(1);
    expect(ranked[1].rank).toBeUndefined();
  });

  it('handles a list of nothing but away rows', () => {
    const items = [away('2026-08-05'), away('2026-08-07')];
    expect(keysOf(orderPaneItems(items, PLAN_ORDER_BEST)))
      .toEqual(['away:2026-08-05', 'away:2026-08-07']);
  });

  it('returns an empty list for no items rather than throwing', () => {
    expect(orderPaneItems([], PLAN_ORDER_BEST)).toEqual([]);
    expect(orderPaneItems(null, PLAN_ORDER_BEST)).toEqual([]);
  });
});

describe('the options themselves', () => {
  it('offers When first, because it is the pane\'s own spine and the default', () => {
    expect(PLAN_ORDERS.map((o) => o.id)).toEqual([PLAN_ORDER_WHEN, PLAN_ORDER_BEST]);
    expect(PLAN_ORDERS.map((o) => o.label)).toEqual(['When', 'Best']);
  });

  it('derives its id list from the options, so the two cannot drift', () => {
    expect(PLAN_ORDER_IDS).toEqual(['when', 'best']);
  });
});
