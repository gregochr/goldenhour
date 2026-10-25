import { describe, it, expect } from 'vitest';
import { buildWindowCards } from '../utils/windowFirstCards.js';
import { buildLocationSheet, buildSlotIndex } from '../utils/locationSheet.js';
import { calloutFacts } from '../utils/mapCallout.js';

/**
 * What a locked (LITE) reader's page is drawn from: an EMPTY reach map (docs/engineering/
 * pro-features-gate-plan.md §9 D1 — the context never asks for /reach, and a stale client's 403 empties
 * it). Every drive and leave line is derived from that map, so each must go absent with it. The same
 * fixtures with a measured map are asserted first, so an absence cannot pass because the fixture was
 * unable to produce the line in the first place.
 */
const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';
const EVENT_TIME = `${TODAY}T19:30:00Z`;

const SLOTS = [
  {
    locationId: 1, locationName: 'Simonside', claudeRating: 4, canopy: false, solarEventTime: EVENT_TIME,
  },
  {
    locationId: 2, locationName: 'Blyth Beach', claudeRating: 3, canopy: false, solarEventTime: EVENT_TIME,
  },
];
const DAYS = [{
  date: TODAY,
  eventSummaries: [{
    targetType: 'SUNSET',
    regions: [{ regionName: 'Northumberland & Tyneside', slots: SLOTS }],
    unregioned: [],
    window: { verdict: 'WORTH_IT', badges: [] },
  }],
}];
const EVENTS = [{ date: TODAY, targetType: 'SUNSET' }];
const MEASURED = new Map([
  [1, { driveMinutes: 19, distanceMiles: 10 }],
  [2, { driveMinutes: 41, distanceMiles: 19 }],
]);
const EMPTY = new Map();

const cardsFor = (reachById) => buildWindowCards(
  EVENTS, DAYS, TODAY, TOMORROW, new Set(), reachById, { limitMinutes: null, defaultLimitMinutes: null },
);

describe('an empty reach map draws no drive or leave line', () => {
  it('the spot cards and the popup\'s "within reach" wording: no minutes, reach unmeasured', () => {
    const [measured] = cardsFor(MEASURED);
    expect(measured.reachMeasured).toBe(true);
    expect(measured.spots.every((s) => s.driveMinutes != null)).toBe(true);

    const [locked] = cardsFor(EMPTY);
    expect(locked.reachMeasured).toBe(false);
    expect(locked.spots).toHaveLength(2);
    expect(locked.spots.every((s) => s.driveMinutes == null)).toBe(true);
    expect(locked.spots.every((s) => s.distanceMiles == null)).toBe(true);
    expect(locked.withinReachCount).toBeNull();
  });

  it('the location sheet: no page drive time (the meta line) and no row leave-by', () => {
    const [card] = cardsFor(EMPTY);
    const spot = { id: 1, name: 'Simonside', regionName: 'Northumberland & Tyneside' };
    const slotIndex = buildSlotIndex(DAYS);

    const measured = buildLocationSheet(spot, [card], { slotIndex, reachById: MEASURED, todayStr: TODAY });
    expect(measured.driveMinutes).toBe(19);
    expect(measured.rows[0].leave).not.toBeNull();

    const sheet = buildLocationSheet(spot, [card], { slotIndex, reachById: EMPTY, todayStr: TODAY });
    expect(sheet.driveMinutes).toBeNull();
    expect(sheet.rows.every((row) => row.leave == null)).toBe(true);
  });

  it('the Map callout facts: no Drive and no Leave by', () => {
    const keys = (facts) => facts.map((f) => f.key);
    expect(keys(calloutFacts({ driveMinutes: 30, distanceMiles: 10, eventTimeIso: EVENT_TIME })))
      .toEqual(['drive', 'leave']);
    expect(keys(calloutFacts({ driveMinutes: null, distanceMiles: null, eventTimeIso: EVENT_TIME })))
      .toEqual([]);
  });
});
