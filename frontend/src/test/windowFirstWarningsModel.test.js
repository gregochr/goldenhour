/**
 * The window card carries the fields the warning surfaces read (weather-warnings-plan.md W3), and the strip's fold
 * passes them on. What breaks if these fail: a warning surface has no event instant or place ids to match against.
 */
import { describe, it, expect } from 'vitest';
import { buildWindowCards } from '../utils/windowFirstCards.js';
import { buildHeatStripCards } from '../utils/windowFirstStrip.js';

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';

const summaryOf = (window, regions = []) => ({ targetType: 'SUNSET', regions, unregioned: [], window });
const slot = (id) => ({
  locationId: id, locationName: `P${id}`, solarEventTime: `${TODAY}T19:41:00`, verdict: 'GO',
  weather: {}, flags: {}, rating: 4,
});
const regions = [{
  regionName: 'Coast', confidence: 'high', bestRating: 4, meanRating: 4, slots: [slot(7), slot(8)],
}];

const build = (window, regs = regions) => buildWindowCards(
  [{ date: TODAY, targetType: 'SUNSET' }],
  [{ date: TODAY, eventSummaries: [summaryOf({ verdict: 'WORTH_IT', badges: [], ...window }, regs)] }],
  TODAY, TOMORROW, new Set(),
)[0];

describe('the card carries the warning fields', () => {
  it('copies the served overlay verbatim and the window\'s event instant', () => {
    const [counts] = [[{ warningId: 'a', regionName: 'Coast', covered: 1, total: 2 }]];
    const card = build({ eventTime: `${TODAY}T19:40:00`, warningIds: ['a'], warningPlaceCounts: counts });
    expect(card.eventIso).toBe(`${TODAY}T19:40:00`);
    expect(card.warningIds).toEqual(['a']);
    expect(card.warningPlaceCounts).toEqual(counts);
  });

  it('falls back to a slot\'s solar event time, and to empty lists with no overlay', () => {
    const card = build({});
    expect(card.eventIso).toBe(`${TODAY}T19:41:00`);
    expect(card.warningIds).toEqual([]);
    expect(card.warningPlaceCounts).toEqual([]);
  });

  it('has a null instant when the window has no time anywhere', () => {
    expect(build({}, []).eventIso).toBeNull();
  });
});

describe('the strip fold', () => {
  const fold = (cards, travel = new Set()) => buildHeatStripCards(
    [{ date: TODAY, targetType: 'SUNSET' }], cards, travel, [], TODAY, TOMORROW,
  )[0];

  it('passes the instant, the served ids and the ids of the window\'s places', () => {
    const strip = fold([build({ eventTime: `${TODAY}T19:40:00`, warningIds: ['a'] })]);
    expect(strip.eventIso).toBe(`${TODAY}T19:40:00`);
    expect(strip.warningIds).toEqual(['a']);
    expect(strip.placeIds.map(Number).sort()).toEqual([7, 8]);
  });

  it('adds nothing on an away day, which has no card', () => {
    const strip = fold([], new Set([TODAY]));
    expect(strip).not.toHaveProperty('eventIso');
    expect(strip).not.toHaveProperty('placeIds');
  });
});
