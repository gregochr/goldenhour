/**
 * `MapView`'s "This event is not scored yet" line over a D-13 FILLER row
 * (docs/engineering/plan-card-verdict-honesty-plan.md, H3).
 *
 * <h2>The defect this pins</h2>
 *
 * <p>The line's gate was {@code heatWindow?.bestRating == null}, and {@code heatWindow} is the Plan
 * tab's SERVED window — which a filler row (a date past the six rendered events but inside the
 * forecast horizon) does not have. So the line was drawn beside chips and a counts footer that read
 * the scores endpoint and showed real stars (production, 2026-10-13: Friday sunset, 26 rated, line
 * still up). The line now answers "the forecast rated nothing here" from the score index on a
 * filler row, while the colour key — which describes the PAINTED field — stays withheld there.
 *
 * <p>The four states, as one table: served+rated, served+unrated, filler+ratings, filler+none.
 * Not mocked: `MapView`, `WindowControl`, `buildMapEvents`, the score index. Mocked: Leaflet and the
 * layers that need a real Leaflet pane.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => ({ getZoom: () => 9 }),
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    fitBounds: () => {},
    getZoom: () => 9,
    flyTo: () => {},
    panInside: () => {},
  }),
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div data-testid="map-heat-layer" /> }));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => <div /> }));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { getAstroConditions } from '../api/astroApi.js';

const TODAY = '2026-01-15';
const TOMORROW = '2026-01-16';
/** Past the served windows below but inside `forecastDates`: the D-13 filler row. */
const FILLER = '2026-01-17';

const SPOTS = [
  {
    id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East', bortleClass: 4,
  },
  {
    id: 2, name: 'Wastwater', lat: 54.44, lng: -3.3, rid: 'The Lakes', regionName: 'The Lakes', bortleClass: 2,
  },
];

const pointOf = (spot, score) => ({
  id: spot.id, name: spot.name, lat: spot.lat, lng: spot.lng, rid: spot.rid, r: [score],
});

const windowsWith = (bestRating) => [
  { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating, conf: 1 },
  { key: `${TOMORROW}:SUNRISE`, date: TOMORROW, targetType: 'SUNRISE', label: 'Tomorrow sunrise', time: '08:24', bestRating: 2, conf: 1 },
];

/** Only the rendered windows have points — the filler date has no field at all, by construction. */
function heatProp(bestRating = 4) {
  return {
    enabled: true,
    hasHome: true,
    spots: SPOTS,
    areaSpots: SPOTS,
    pointsByKey: new Map([
      [`${TODAY}:SUNSET`, SPOTS.map((s) => pointOf(s, 4))],
      [`${TOMORROW}:SUNRISE`, [pointOf(SPOTS[0], 2)]],
    ]),
    windows: windowsWith(bestRating),
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
  };
}

/** Locations with NO forecast rows of their own — ratings, where any, come from `briefingScores`. */
const makeLocations = () => SPOTS.map((s) => ({
  id: s.id,
  name: s.name,
  lat: s.lat,
  lon: s.lng,
  regionName: s.rid,
  bortleClass: s.bortleClass,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map(),
}));

/** The scores endpoint's shape: `region|date|targetType|locationName` → result. Both events, so
 *  whichever solar row the filler date opens on is rated. */
function scoresFor(date) {
  const entries = [];
  SPOTS.forEach((s) => {
    ['SUNRISE', 'SUNSET'].forEach((type) => {
      entries.push([`${s.rid}|${date}|${type}|${s.name}`, { rating: 4, summary: 'A good one.' }]);
    });
  });
  return new Map(entries);
}

async function renderMap(props = {}) {
  await act(async () => {
    render(
      <MapView
        locations={makeLocations()}
        date={TODAY}
        forecastDates={[TODAY, TOMORROW, FILLER]}
        autoEventType={null}
        heat={heatProp()}
        {...props}
      />,
    );
  });
}

const LINE = 'wf-map-heat-unscored';
const KEY = 'wf-map-heat-legend';

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  getAstroConditions.mockResolvedValue([]);
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView — "not scored yet" answers whether the forecast rated anything, on a served window and a filler row alike', () => {
  it('served window, rated: no line, and the key is up', async () => {
    await renderMap({ heat: heatProp(4) });
    expect(await screen.findByTestId(KEY)).toBeInTheDocument();
    expect(screen.queryByTestId(LINE)).toBeNull();
  });

  it('served window, unrated: the line, and no key — its own `bestRating` rule is unchanged', async () => {
    // Ratings in the score index must NOT silence a served window: the served `bestRating` stays
    // the authority wherever a served window exists, so this pins the rule's scope.
    await renderMap({ heat: heatProp(null), briefingScores: scoresFor(TODAY) });
    expect(await screen.findByTestId(LINE)).toHaveTextContent('This event is not scored yet');
    expect(screen.queryByTestId(KEY)).toBeNull();
  });

  it('filler row WITH ratings in the score index: no line, and still no key (no field is painted)', async () => {
    // THE REGRESSION: the old rule read the missing served window as "nothing rated" and drew the
    // line over chips that showed real stars. The key stays down because it describes the painted
    // field, and `heat.pointsByKey` holds nothing for a filler date.
    await renderMap({ date: FILLER, briefingScores: scoresFor(FILLER) });
    expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
    expect(screen.queryByTestId(LINE)).toBeNull();
    expect(screen.queryByTestId(KEY)).toBeNull();
  });

  it('filler row with NO ratings: the line, and no key', async () => {
    await renderMap({ date: FILLER });
    expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
    expect(await screen.findByTestId(LINE)).toHaveTextContent('This event is not scored yet');
    expect(screen.queryByTestId(KEY)).toBeNull();
  });

  it('filler row whose only ratings are for ANOTHER date: still the line', async () => {
    // The predicate is keyed on the row's own date, not "any rating anywhere" — a rated tomorrow
    // says nothing about the day after.
    await renderMap({ date: FILLER, briefingScores: scoresFor(TOMORROW) });
    expect(await screen.findByTestId(LINE)).toHaveTextContent('This event is not scored yet');
    expect(screen.queryByTestId(KEY)).toBeNull();
  });

  it('astro night: rated locations the field has no spot for leave the line UP — the filler rule must not follow', async () => {
    // Why the `!isAstroMode && !isAuroraMode` guard on `fillerHasRatings` is load-bearing. Astro has
    // no served window, so `heatWindow` is null there exactly as on a filler row — without the guard
    // the filler rule would read `getRatingForLocation` (which for ASTRO answers from the night's
    // own scores) and silence the line whenever ANY location is rated. But astro's `windowUnscored`
    // is its own question: "does the FIELD hold a rated spot" (`astroHeatPoints`, joined against
    // `heat.spots`). Here every location carries a real astro star yet the field's spot pool is
    // empty (the dark-sky pool excluding every rated spot is the same shape), so the field paints
    // nothing, the key is down and the line is the only thing saying so. Verified by removing the
    // guard: the line vanishes and this test fails.
    getAstroConditions.mockResolvedValue([
      { locationName: SPOTS[0].name, stars: 4 },
      { locationName: SPOTS[1].name, stars: 3 },
    ]);
    await renderMap({ heat: { ...heatProp(), spots: [] }, autoEventType: 'ASTRO' });
    expect(await screen.findByTestId(LINE)).toHaveTextContent('This event is not scored yet');
    expect(screen.queryByTestId(KEY)).toBeNull();
  });
});
