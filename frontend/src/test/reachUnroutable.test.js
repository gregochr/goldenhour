import { describe, it, expect } from 'vitest';
import {
  DRIVE_UNROUTABLE, gateSpotsByReach, isFarSpot, isUnroutableDropped, reachIsMeasured,
} from '../utils/reachLens.js';
import {
  missingDriveStatus, originReachMap, ROSTER_VISIBILITY_MARGIN_MS,
} from '../utils/planOrigin.js';
import { buildWindowCards } from '../utils/windowFirstCards.js';
import { buildWindowSpots } from '../utils/windowFirstSpots.js';
import { buildHeatStripCards } from '../utils/windowFirstStrip.js';
import { bestReachLine } from '../components/WindowFirstHeatStrip.jsx';
import { buildRegionRows } from '../utils/windowFirstRegions.js';
import { browseEmptyLine, browseSpots } from '../utils/windowSpotBrowse.js';
import { buildPlanConflict } from '../utils/planConflicts.js';
import { noRouteClause, spreadTitle } from '../utils/windowFirstSpread.js';

/**
 * Reach-scoped-card-plan R1: a place OpenRouteService cannot route to fails a finite tier, a place not yet
 * measured does not, and nothing changes for a reader with no measured drive or under "Any".
 */

const TODAY = '2026-08-04';
const TOMORROW = '2026-08-05';
const REGION = 'Lake District';

const spot = (name, driveMinutes, driveStatus, rating = null) => ({
  locationName: name, driveMinutes, driveStatus, rating,
});

describe('the reach gate — measured x tier x status', () => {
  const MEASURED_NEAR = spot('Near', 30, 'MEASURED');
  const MEASURED_FAR = spot('Far', 80, 'MEASURED');
  const PENDING = spot('Pending', null, 'PENDING');
  const NEVER = spot('Never', null, null);
  const UNROUTABLE = spot('Helvellyn', null, DRIVE_UNROUTABLE);
  const ALL = [MEASURED_NEAR, MEASURED_FAR, PENDING, NEVER, UNROUTABLE];
  const names = (spots) => spots.map((s) => s.locationName);

  it('drops UNROUTABLE under a finite tier for a measured reader, keeping PENDING and in-range drives', () => {
    expect(names(gateSpotsByReach(ALL, 45, { measured: true }))).toEqual(['Near', 'Pending', 'Never']);
  });

  it('keeps UNROUTABLE for a reader with no measured drive (nothing to gate on)', () => {
    expect(names(gateSpotsByReach(ALL, 45, { measured: false })))
      .toEqual(['Near', 'Pending', 'Never', 'Helvellyn']);
  });

  it('behaves as before when the option is omitted', () => {
    expect(names(gateSpotsByReach(ALL, 45))).toEqual(['Near', 'Pending', 'Never', 'Helvellyn']);
  });

  it('gates nothing under Any, whatever the status', () => {
    expect(gateSpotsByReach(ALL, null, { measured: true })).toBe(ALL);
  });

  it('still drops an over-limit measured drive, and keeps one exactly on the limit', () => {
    const edge = [spot('On', 45, 'MEASURED'), spot('Over', 46, 'MEASURED')];
    expect(names(gateSpotsByReach(edge, 45, { measured: true }))).toEqual(['On']);
  });

  it('marks an unroutable place as dropped only for a finite tier and a measured reader', () => {
    expect(isUnroutableDropped(UNROUTABLE, 45, true)).toBe(true);
    expect(isUnroutableDropped(UNROUTABLE, null, true)).toBe(false);
    expect(isUnroutableDropped(UNROUTABLE, 45, false)).toBe(false);
    expect(isUnroutableDropped(PENDING, 45, true)).toBe(false);
    expect(isUnroutableDropped(MEASURED_FAR, 45, true)).toBe(false);
  });

  it('never marks an unroutable place far: it has no drive to be long', () => {
    expect(isFarSpot(UNROUTABLE.driveMinutes, 45)).toBe(false);
  });

  it('reads a reader as measured only when some entry has a drive time', () => {
    expect(reachIsMeasured(new Map([[1, { driveMinutes: 20 }], [2, { driveMinutes: null }]]))).toBe(true);
    expect(reachIsMeasured(new Map([[2, { driveMinutes: null, driveStatus: 'PENDING' }]]))).toBe(false);
    expect(reachIsMeasured(new Map())).toBe(false);
    expect(reachIsMeasured(null)).toBe(false);
    expect(reachIsMeasured({})).toBe(false);
  });
});

describe('the away status — derived from the matrix and the two served stamps', () => {
  const ORIGIN = { id: 7, name: REGION, baseName: 'Keswick' };
  const MATRIX = { 7: { 11: 25 } };
  const STAMP = '2026-11-05T02:00:00Z';

  it('is UNROUTABLE when the base was fully measured well after the place last moved', () => {
    expect(missingDriveStatus(STAMP, '2026-03-01T12:00:00')).toBe(DRIVE_UNROUTABLE);
  });

  it('is PENDING with no stamp (everything pending), no target change, or an unparseable one', () => {
    expect(missingDriveStatus(null, '2026-03-01T12:00:00')).toBe('PENDING');
    expect(missingDriveStatus(STAMP, null)).toBe('PENDING');
    expect(missingDriveStatus('not a date', '2026-03-01T12:00:00')).toBe('PENDING');
    expect(missingDriveStatus(STAMP, '')).toBe('PENDING');
  });

  it('is PENDING for a place that moved after the stamp, or inside the one-hour margin before it', () => {
    expect(missingDriveStatus(STAMP, '2026-11-05T09:00:00')).toBe('PENDING');
    expect(missingDriveStatus(STAMP, '2026-11-05T01:30:00')).toBe('PENDING');
  });

  it('uses the server margin: exactly one hour before is PENDING, a second earlier is UNROUTABLE', () => {
    expect(ROSTER_VISIBILITY_MARGIN_MS).toBe(60 * 60 * 1000);
    expect(missingDriveStatus(STAMP, '2026-11-05T01:00:00')).toBe('PENDING');
    expect(missingDriveStatus(STAMP, '2026-11-05T00:59:59')).toBe(DRIVE_UNROUTABLE);
  });

  it('reads a location timestamp with no zone as UTC, and one with a zone as given', () => {
    // 01:00:00 without a zone is 01:00 UTC; the same wall time at +01:00 is 00:00 UTC, an hour earlier.
    expect(missingDriveStatus(STAMP, '2026-11-05T00:59:59.500')).toBe(DRIVE_UNROUTABLE);
    expect(missingDriveStatus(STAMP, '2026-11-05T01:30:00+01:00')).toBe(DRIVE_UNROUTABLE);
  });

  it('gives a matrix row MEASURED and every other roster place its PENDING / UNROUTABLE status', () => {
    const map = originReachMap(ORIGIN, MATRIX, {
      measuredAt: STAMP,
      locations: [
        { id: 11, driveTargetChangedAt: '2026-03-01T12:00:00' },
        { id: 12, driveTargetChangedAt: '2026-03-01T12:00:00' },
        { id: 13, driveTargetChangedAt: '2026-11-05T09:00:00' },
      ],
    });

    expect(map.get(11)).toEqual({ driveMinutes: 25, distanceMiles: null, driveStatus: 'MEASURED' });
    expect(map.get(12)).toEqual({ driveMinutes: null, distanceMiles: null, driveStatus: DRIVE_UNROUTABLE });
    expect(map.get(13)).toEqual({ driveMinutes: null, distanceMiles: null, driveStatus: 'PENDING' });
  });

  it('reads every missing place PENDING when the base has never been fully measured (null stamp)', () => {
    const map = originReachMap(ORIGIN, MATRIX, {
      measuredAt: null,
      locations: [{ id: 12, driveTargetChangedAt: '2026-03-01T12:00:00' }],
    });
    expect(map.get(12).driveStatus).toBe('PENDING');
  });

  it('reads a base that has just moved (stamp cleared, matrix emptied) as nothing measured', () => {
    const map = originReachMap(ORIGIN, {}, {
      measuredAt: null,
      locations: [{ id: 11, driveTargetChangedAt: '2026-03-01T12:00:00' }],
    });
    expect([...map.values()].map((e) => e.driveStatus)).toEqual(['PENDING']);
    expect(reachIsMeasured(map)).toBe(false);
  });

  it('ignores roster entries it cannot key, and adds nothing without a roster', () => {
    const map = originReachMap(ORIGIN, MATRIX, {
      measuredAt: STAMP, locations: [{ id: 'x' }, null, { id: 11 }],
    });
    expect([...map.keys()]).toEqual([11]);
    expect([...originReachMap(ORIGIN, MATRIX).keys()]).toEqual([11]);
  });
});

describe('buildWindowSpots copies the status', () => {
  it('carries driveStatus from the reach map, and null when the origin has no entry', () => {
    const es = {
      regions: [{
        regionName: REGION,
        slots: [
          { locationId: 1, locationName: 'A', claudeRating: 3 },
          { locationId: 2, locationName: 'B', claudeRating: 3 },
        ],
      }],
    };
    const reach = new Map([[1, { driveMinutes: null, distanceMiles: null, driveStatus: DRIVE_UNROUTABLE }]]);
    const spots = buildWindowSpots(es, reach);
    expect(spots.find((s) => s.locationName === 'A').driveStatus).toBe(DRIVE_UNROUTABLE);
    expect(spots.find((s) => s.locationName === 'B').driveStatus).toBeNull();
  });
});

describe('the card — a Helvellyn-shaped place with no route', () => {
  const slots = [
    { locationId: 1, locationName: 'Near Wood', claudeRating: 2, canopy: false },
    { locationId: 2, locationName: 'Far Beach', claudeRating: 3, canopy: false },
    { locationId: 3, locationName: 'Helvellyn', claudeRating: 5, canopy: false },
  ];
  const days = (extraSlots = slots) => [{
    date: TODAY,
    eventSummaries: [{
      targetType: 'SUNSET',
      regions: [{ regionName: REGION, meanRating: 3.5, sampleSufficient: true, slots: extraSlots }],
      unregioned: [],
      window: { verdict: 'WORTH_IT', badges: [] },
    }],
  }];
  const REACH = (helvellyn) => new Map([
    [1, { driveMinutes: 20, distanceMiles: 8, driveStatus: 'MEASURED' }],
    [2, { driveMinutes: 70, distanceMiles: 50, driveStatus: 'MEASURED' }],
    [3, helvellyn],
  ]);
  const UNROUTABLE_ENTRY = { driveMinutes: null, distanceMiles: null, driveStatus: DRIVE_UNROUTABLE };
  const PENDING_ENTRY = { driveMinutes: null, distanceMiles: null, driveStatus: 'PENDING' };
  const cardFor = (reach, limitMinutes, extra = {}, day = days()) => buildWindowCards(
    [{ date: TODAY, targetType: 'SUNSET' }], day, TODAY, TOMORROW, new Set(), reach,
    { limitMinutes, defaultLimitMinutes: 45, ...extra },
  )[0];
  const strip = (card) => buildHeatStripCards(
    [{ date: TODAY, targetType: 'SUNSET' }], [card], new Set(), days(), TODAY, TOMORROW,
  )[0];

  it('is not the best place at 45 min, but is at Any', () => {
    const limited = cardFor(REACH(UNROUTABLE_ENTRY), 45);
    expect(limited.bestReach.locationName).toBe('Near Wood');
    expect(limited.pool.map((s) => s.locationName)).toEqual(['Near Wood']);
    expect(limited.reachUnroutableCount).toBe(1);

    const any = cardFor(REACH(UNROUTABLE_ENTRY), null);
    expect(any.bestReach.locationName).toBe('Helvellyn');
    expect(any.reachUnroutableCount).toBe(0);
  });

  it('is still the best place while only PENDING (nothing has measured it yet)', () => {
    const card = cardFor(REACH(PENDING_ENTRY), 45);
    expect(card.bestReach.locationName).toBe('Helvellyn');
    expect(card.reachUnroutableCount).toBe(0);
  });

  it('computes reachMeasured once from the whole reach map, not from this window\'s spots', () => {
    // Every spot in THIS window is unmeasured, but the reader has a measured drive elsewhere in the roster.
    const reach = new Map([
      [3, UNROUTABLE_ENTRY],
      [99, { driveMinutes: 15, distanceMiles: 5, driveStatus: 'MEASURED' }],
    ]);
    const only = [slots[2]];
    const card = cardFor(reach, 45, {}, days(only));
    expect(card.reachMeasured).toBe(true);
    expect(card.pool).toEqual([]);
    expect(card.reachUnroutableCount).toBe(1);
  });

  it('honours a reachMeasured handed in by the provider over the map', () => {
    const card = cardFor(REACH(UNROUTABLE_ENTRY), 45, { reachMeasured: false });
    expect(card.reachMeasured).toBe(false);
    // Not measured, so nothing is dropped as unroutable.
    expect(card.bestReach.locationName).toBe('Helvellyn');
  });

  it('is unchanged for a reader with no measured drive at all', () => {
    const none = new Map([[3, UNROUTABLE_ENTRY]]);
    const card = cardFor(none, 45);
    expect(card.reachMeasured).toBe(false);
    expect(card.pool).toHaveLength(3);
    expect(card.bestReach.locationName).toBe('Helvellyn');
    expect(card.reachUnroutableCount).toBe(0);
  });

  it('carries the base town when planning away, and null at home', () => {
    expect(cardFor(REACH(UNROUTABLE_ENTRY), 45).reachOriginName).toBeNull();
    const away = cardFor(REACH(UNROUTABLE_ENTRY), 45, { origin: { id: 7, name: REGION, baseName: 'Keswick' } });
    expect(away.reachOriginName).toBe('Keswick');
  });

  describe('the "beyond" line never calls an unroutable place far', () => {
    // Every RATED place is gone from the pool: only an unrated near spot remains.
    const unratedNear = [
      { locationId: 1, locationName: 'Near Wood', claudeRating: null, canopy: false },
      { locationId: 2, locationName: 'Far Beach', claudeRating: 3, canopy: false },
      { locationId: 3, locationName: 'Helvellyn', claudeRating: 5, canopy: false },
    ];
    const lineFor = (reach, extra = {}, rated = unratedNear) => {
      const card = cardFor(reach, 45, extra, days(rated));
      return { card, line: bestReachLine(strip(card)) };
    };

    it('says "beyond" of far places only, as it always did', () => {
      const reach = new Map([
        [1, { driveMinutes: 20, distanceMiles: 8, driveStatus: 'MEASURED' }],
        [2, { driveMinutes: 70, distanceMiles: 50, driveStatus: 'MEASURED' }],
        [3, { driveMinutes: 80, distanceMiles: 55, driveStatus: 'MEASURED' }],
      ]);
      const { card, line } = lineFor(reach);
      expect(card.reachUnroutableRemoved).toBe(0);
      expect(card.reachFarRemoved).toBe(2);
      expect(line.value).toBe(`${REGION} · beyond 45 min`);
      expect(line.spoken).toBe(`region ${REGION}, beyond 45 min`);
    });

    it('says "no route from home" when every removed rated place is unroutable, and never "beyond"', () => {
      const reach = new Map([
        [1, { driveMinutes: 20, distanceMiles: 8, driveStatus: 'MEASURED' }],
        [2, UNROUTABLE_ENTRY],
        [3, UNROUTABLE_ENTRY],
      ]);
      const { card, line } = lineFor(reach);
      expect(card.reachUnroutableRemoved).toBe(2);
      expect(card.reachFarRemoved).toBe(0);
      expect(line.value).toBe(`${REGION} · no route from home`);
      expect(line.title).toContain('have no route from home');
      expect(line.title).not.toContain('further than');
      expect(line.spoken).toBe(`region ${REGION}, no route from home`);
      expect(line.beyond).toBe(true);
    });

    it('names the base town instead of home when planning away', () => {
      const reach = new Map([
        [1, { driveMinutes: 20, distanceMiles: null, driveStatus: 'MEASURED' }],
        [2, UNROUTABLE_ENTRY],
        [3, UNROUTABLE_ENTRY],
      ]);
      const { line } = lineFor(reach, { origin: { id: 7, name: REGION, baseName: 'Keswick' } });
      expect(line.value).toBe(`${REGION} · no route from Keswick`);
    });

    it('says both when far and unroutable places were removed', () => {
      const reach = new Map([
        [1, { driveMinutes: 20, distanceMiles: 8, driveStatus: 'MEASURED' }],
        [2, { driveMinutes: 70, distanceMiles: 50, driveStatus: 'MEASURED' }],
        [3, UNROUTABLE_ENTRY],
      ]);
      const { card, line } = lineFor(reach);
      expect(card.reachUnroutableRemoved).toBe(1);
      expect(card.reachFarRemoved).toBe(1);
      expect(line.value).toBe(`${REGION} · beyond 45 min or no route`);
      expect(line.title).toContain('further than 45 min away or have no route from home');
      expect(line.spoken).toBe(`region ${REGION}, beyond 45 min or no route from home`);
    });

    it('keeps the unrated verdict wording on the no-route line', () => {
      const reach = new Map([
        [1, { driveMinutes: 20, distanceMiles: 8, driveStatus: 'MEASURED' }],
        [2, UNROUTABLE_ENTRY],
        [3, UNROUTABLE_ENTRY],
      ]);
      const { card } = lineFor(reach);
      const line = bestReachLine({ ...strip(card), verdict: 'UNRATED' });
      expect(line.title).toBe(`Too few rated in ${REGION} to call it, and its rated places have no route from home`);
    });

    it('counts only the reader\'s own rated places, not unrated ones', () => {
      const reach = new Map([
        [1, UNROUTABLE_ENTRY],
        [2, { driveMinutes: 20, distanceMiles: 8, driveStatus: 'MEASURED' }],
        [3, { driveMinutes: 21, distanceMiles: 8, driveStatus: 'MEASURED' }],
      ]);
      // Near Wood is unrated: dropping it as unroutable is counted, but not as a rated place.
      const card = cardFor(reach, 45, {}, days(unratedNear));
      expect(card.reachUnroutableCount).toBe(1);
      expect(card.reachUnroutableRemoved).toBe(0);
    });
  });

  it('says how many places have no route in the spread tooltip, and nothing when none', () => {
    const card = cardFor(REACH(UNROUTABLE_ENTRY), 45);
    const tip = `${spreadTitle({ total: 1, rated: 1, counts: [0, 1, 0, 0, 0], unrated: 0 }, true)}`
      + `${noRouteClause(strip(card).reachUnroutableCount, strip(card).reachOriginName)}`;
    expect(tip).toContain('1 place has no route from home');
    expect(noRouteClause(0)).toBe('');
    expect(noRouteClause(undefined)).toBe('');
    expect(noRouteClause(3, 'Keswick')).toBe(' · 3 places have no route from Keswick');
  });

  it('agrees between the card and the region rail on the same location across two windows', () => {
    // Two windows, one location: the gate must drop it in both the card and the rail row built from it.
    const reach = REACH(UNROUTABLE_ENTRY);
    const twoDays = [
      { date: TODAY, eventSummaries: days()[0].eventSummaries },
      { date: TOMORROW, eventSummaries: days()[0].eventSummaries },
    ];
    const cards = buildWindowCards(
      [{ date: TODAY, targetType: 'SUNSET' }, { date: TOMORROW, targetType: 'SUNSET' }],
      twoDays, TODAY, TOMORROW, new Set(), reach, { limitMinutes: 45, defaultLimitMinutes: 45 },
    );
    cards.forEach((card, index) => {
      const es = twoDays[index].eventSummaries[0];
      const rows = buildRegionRows(es, card.spots, card.allSpots, {
        limitMinutes: 45, minRating: null, measured: card.reachMeasured,
      });
      expect(card.spots.map((s) => s.locationName)).toEqual(['Near Wood']);
      // The rail counted the same survivors: Helvellyn is out of both.
      expect(rows[0].drawnCount).toBe(card.spots.length);
      expect(rows[0].totalCount).toBe(3);
    });
  });

  it('the rail says a region emptied by reach is "away", counting an unroutable place as removed by reach', () => {
    // Near Wood is 90 min and Helvellyn has no route: at 45 min both are out, so reach emptied the region and
    // its row states the nearest measured drive. If the rail ran the gate without the card's `measured`, the
    // unroutable place would "survive" reach and the row would stay silent about why it is empty.
    const es = days()[0].eventSummaries[0];
    const reach = REACH(UNROUTABLE_ENTRY);
    reach.set(1, { driveMinutes: 90, distanceMiles: 40, driveStatus: 'MEASURED' });
    const card = cardFor(reach, 45);
    expect(card.spots).toEqual([]);
    const lens = { limitMinutes: 45, minRating: null };
    const measured = buildRegionRows(es, card.spots, card.allSpots, { ...lens, measured: card.reachMeasured });
    const unthreaded = buildRegionRows(es, card.spots, card.allSpots, lens);
    expect(measured[0].awayLabel).toBe('1h 10min away');
    expect(unthreaded[0].awayLabel).toBeNull();
  });
});

describe('the other gates take the same measured flag', () => {
  const spots = [
    { locationName: 'Near', driveMinutes: 20, driveStatus: 'MEASURED', rating: 3 },
    { locationName: 'Helvellyn', driveMinutes: null, driveStatus: DRIVE_UNROUTABLE, rating: 5 },
  ];

  it('the browse sheet drops it under a finite tier when measured, and lists it under Any', () => {
    const base = { spots, ratingFloorId: 'any', typeId: 'any', typesByName: new Map() };
    expect(browseSpots({ ...base, limitMinutes: 45, measured: true }).map((s) => s.locationName))
      .toEqual(['Near']);
    expect(browseSpots({ ...base, limitMinutes: null, measured: true }).map((s) => s.locationName))
      .toEqual(['Helvellyn', 'Near']);
    // Not measured (or not passed): unchanged.
    expect(browseSpots({ ...base, limitMinutes: 45 }).map((s) => s.locationName))
      .toEqual(['Helvellyn', 'Near']);
  });

  it('the browse sheet\'s empty line suggests widening only where widening helps', () => {
    const only = [spots[1]];
    const args = {
      spots: only, limitMinutes: 45, ratingFloorId: 'any', typeId: 'any', typesByName: new Map(), measured: true,
    };
    expect(browseEmptyLine(args)).toBe('Nothing matches — widen the reach.');
  });

  it('the plan conflict offers a wider tier only when it would bring something back', () => {
    const card = {
      key: 'k', kicker: null, when: 'Tonight', time: '20:00',
      allSpots: [spots[1]], pool: [], spots: [],
    };
    const base = {
      cards: [card], origin: null, homePlace: 'Durham', tierId: '45', limitMinutes: 45,
      floorId: 'any', minRating: null,
    };
    // Unroutable stays dropped at every finite tier, so only "Any" would show it.
    const measured = buildPlanConflict({ ...base, reachMeasured: true });
    expect(measured.actions.map((a) => a.id)).toEqual(['any']);
    // Not measured: nothing is dropped as unroutable, so the very next tier would bring it back.
    const unmeasured = buildPlanConflict({ ...base, reachMeasured: false });
    expect(unmeasured.actions.map((a) => a.id)).toEqual(['90']);
  });
});
