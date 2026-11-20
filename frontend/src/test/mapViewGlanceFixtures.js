import { windowKey } from '../utils/windowKeys.js';
import { warning } from './warningsFixture.js';

/** The shared fixtures of the glance-to-map G1 Map tests (not a test file). */
export const TODAY = '2026-01-15';
export const TOMORROW = '2026-01-16';

export const SPOTS = [
  {
    id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East', bortleClass: 4,
  },
  {
    id: 2, name: 'Tynemouth', lat: 55.02, lng: -1.42, rid: 'North East', regionName: 'North East', bortleClass: 6,
  },
  {
    id: 3, name: 'Wastwater', lat: 54.44, lng: -3.3, rid: 'The Lakes', regionName: 'The Lakes', bortleClass: 2,
  },
  {
    id: 4, name: 'Kelso', lat: 56.2, lng: -2.43, rid: 'The Borders', regionName: 'The Borders', bortleClass: 5,
  },
];
// "The Borders" is outside the reader's area.
export const AREA_SPOTS = SPOTS.slice(0, 3);
export const pointOf = (spot, score) => ({
  id: spot.id, name: spot.name, lat: spot.lat, lng: spot.lng, rid: spot.rid, r: [score],
});
export const SUNSET = windowKey(TODAY, 'SUNSET');

export function heatProp() {
  return {
    enabled: true,
    hasHome: true,
    spots: SPOTS,
    areaSpots: AREA_SPOTS,
    pointsByKey: new Map([
      [SUNSET, [pointOf(SPOTS[0], 4), pointOf(SPOTS[1], 2), pointOf(SPOTS[2], 5), pointOf(SPOTS[3], 4)]],
      [windowKey(TODAY, 'SUNRISE'), [pointOf(SPOTS[0], 3)]],
    ]),
    windows: [
      {
        key: windowKey(TODAY, 'SUNRISE'), date: TODAY, targetType: 'SUNRISE', label: 'Sunrise', time: '08:24', bestRating: 3, conf: 1,
      },
      {
        key: SUNSET, date: TODAY, targetType: 'SUNSET', label: 'Sunset', time: '16:12', bestRating: 5, conf: 1,
      },
    ],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
    tideByWindow: new Map(),
  };
}

export const region = (regionName, meanRating, bestRating, displayVerdict, extra = {}) => ({
  regionName, meanRating, bestRating, displayVerdict, sampleSufficient: true, slots: [{ canopy: false }], ...extra,
});
export const DAYS = [{
  date: TODAY,
  eventSummaries: [{
    targetType: 'SUNSET',
    regions: [
      region('North East', 2.0, 3, 'STAND_DOWN'),
      region('The Lakes', 4.4, 5, 'WORTH_IT'),
      region('The Borders', 4.9, 5, 'WORTH_IT'),
    ],
  }],
}];

export const rowOf = (rating) => ({
  sunset: { rating, solarEventTime: `${TODAY}T16:12:00` },
  sunrise: { rating, solarEventTime: `${TODAY}T08:24:00` },
});
export const locations = () => SPOTS.map((s) => ({
  id: s.id,
  name: s.name,
  lat: s.lat,
  lon: s.lng,
  regionName: s.rid,
  bortleClass: s.bortleClass,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, rowOf(4)]]),
}));

export const spotOf = (s, rating) => ({
  key: String(s.id), locationId: s.id, locationName: s.name, regionName: s.rid, rating, driveMinutes: 30 + s.id,
});
export const PICK = {
  kind: 'best',
  regionName: 'The Lakes',
  headline: 'The Lakes has the best sky tonight.',
  detail: 'Broken high cloud lights from below as the sun drops.',
  locationName: 'Wastwater',
};
export const CARD = {
  key: SUNSET,
  date: TODAY,
  targetType: 'SUNSET',
  pick: PICK,
  allSpots: SPOTS.map((s) => spotOf(s, 4)),
};
export const cardsByKey = (card = CARD) => new Map([[card.key, card]]);

export const AMBER = warning({
  warningId: 'a',
  level: 'AMBER',
  weatherTypes: ['SNOW'],
  locationIds: [1],
  validFrom: `${TODAY}T00:00:00Z`,
  validTo: `${TODAY}T23:00:00Z`,
});

