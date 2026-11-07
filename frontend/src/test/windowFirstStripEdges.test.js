import { describe, it, expect } from 'vitest';
import { buildHeatStripCards } from '../utils/windowFirstStrip.js';

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';

/** A card as `buildWindowCards` shapes it — only the fields the strip reads. */
function card(overrides = {}) {
  return {
    key: `${TODAY}:SUNSET`,
    date: TODAY,
    targetType: 'SUNSET',
    kicker: 'Tonight',
    when: 'Sunset',
    time: '21:11',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    ...overrides,
  };
}

const build = (events, cards, travel = new Set(), days = []) => buildHeatStripCards(
  events, cards, travel, days, TODAY, TOMORROW,
);

describe('buildHeatStripCards — a window that has no card', () => {
  it('takes the sun time from the day the window falls on, not from another day', () => {
    const days = [
      { date: TODAY, eventSummaries: [{ targetType: 'SUNSET', solarEventTime: `${TODAY}T19:00:00` }] },
      { date: TOMORROW, eventSummaries: [{ targetType: 'SUNSET', solarEventTime: `${TOMORROW}T18:00:00` }] },
    ];
    // The summary instants are UTC; the reader sees UK summer time, an hour on.
    expect(build([{ date: TOMORROW, targetType: 'SUNSET' }], [], new Set(), days)[0].time).toBe('19:00');
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [], new Set(), days)[0].time).toBe('20:00');
  });

  it('takes the sun time from the summary of the same event, not the first one in the day', () => {
    const days = [{
      date: TODAY,
      eventSummaries: [
        { targetType: 'SUNRISE', solarEventTime: `${TODAY}T04:00:00` },
        { targetType: 'SUNSET', solarEventTime: `${TODAY}T19:00:00` },
      ],
    }];
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [], new Set(), days)[0].time).toBe('20:00');
    expect(build([{ date: TODAY, targetType: 'SUNRISE' }], [], new Set(), days)[0].time).toBe('05:00');
  });

  it('has an empty time when the day is unknown or has no summaries', () => {
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [], new Set(), [])[0].time).toBe('');
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [], new Set(), null)[0].time).toBe('');
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [], new Set(), [{ date: TODAY }])[0].time).toBe('');
  });

  it('names itself from the calendar words and carries no badges', () => {
    const [only] = build([{ date: TODAY, targetType: 'SUNSET' }], [], new Set(), []);
    expect(only.label).toBe('Today sunset');
    expect(only.badges).toEqual([]);
    expect(only.pool).toEqual([]);
    expect(only.verdict).toBe('AWAITING');
    expect(only.verdictLabel).toBe('Not scored');
  });

  it('copes with no window cards and no travel set at all', () => {
    const [only] = buildHeatStripCards([{ date: TODAY, targetType: 'SUNSET' }], null, null, [], TODAY, TOMORROW);
    expect(only.away).toBe(false);
    expect(only.key).toBe(`${TODAY}:SUNSET`);
  });

  it('answers no thumbnails for no events', () => {
    expect(build(null, [])).toEqual([]);
  });
});

describe('buildHeatStripCards — a window that has a card', () => {
  it('prints the label without a stray leading space when the card has no kicker', () => {
    const events = [{ date: TOMORROW, targetType: 'SUNRISE' }];
    const cards = [card({ key: `${TOMORROW}:SUNRISE`, date: TOMORROW, targetType: 'SUNRISE', kicker: null, when: 'Tomorrow sunrise' })];
    expect(build(events, cards)[0].label).toBe('Tomorrow sunrise');
  });

  it('prints the label without a trailing space when the card has no "when"', () => {
    const [only] = build([{ date: TODAY, targetType: 'SUNSET' }], [card({ when: null })]);
    expect(only.label).toBe('Tonight');
  });

  it("passes the card's best rating through, and null when the card has none", () => {
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [card({ bestRating: 4 })])[0].bestRating).toBe(4);
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [card()])[0].bestRating).toBeNull();
  });

  it("passes the card's badges through", () => {
    const allBadges = [{ type: 'AURORA', label: 'Aurora' }];
    expect(build([{ date: TODAY, targetType: 'SUNSET' }], [card({ allBadges })])[0].badges).toBe(allBadges);
  });

  it('carries the scoped thin count only on a scoped verdict, and null when the card gives none', () => {
    const events = [{ date: TODAY, targetType: 'SUNSET' }];
    const scoped = build(events, [card({ verdictScope: { kind: 'reach' }, scopedThinCount: 3 })])[0];
    expect(scoped.scopedThinCount).toBe(3);
    expect(scoped.verdictScope).toEqual({ kind: 'reach' });
    const scopedNoCount = build(events, [card({ verdictScope: { kind: 'reach' } })])[0];
    expect(scopedNoCount.scopedThinCount).toBeNull();
    const unscoped = build(events, [card({ scopedThinCount: 3 })])[0];
    expect('scopedThinCount' in unscoped).toBe(false);
  });
});
