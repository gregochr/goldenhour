/**
 * The Map tab's stand-down ground (big-seas-plan.md F3): the point set the hatch is drawn around,
 * the unscored line's wording, the keys on desktop and on the phone, the footer's
 * ` · N stood down`, and the regions that earn a "stood down · cause" label.
 *
 * The two layers (`MapHeatLayer`, `MapLabels`) are probes that record what
 * `MapView` hands them, as in `MapViewHeat.test.jsx`: their own pixels are `heatFieldStandDown`'s
 * and `MapLabels.test.jsx`'s business.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, screen, fireEvent, within,
} from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

let mapEventHandlers = [];
const mockMapInstance = { getZoom: () => 9 };
vi.mock('react-leaflet', () => ({
  MapContainer: (props) => <div data-testid="map-container">{props.children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: (handlers) => {
    mapEventHandlers.push(handlers);
    return mockMapInstance;
  },
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    fitBounds: () => {},
    getZoom: () => 9,
    flyTo: () => {},
    panInside: () => {},
  }),
}));

const heatLayerProps = { last: null };
vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: (props) => {
    heatLayerProps.last = props;
    return <div data-testid="map-heat-layer" />;
  },
}));
const labelProps = { last: null };
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => {
    labelProps.last = props;
    return <div data-testid="map-labels-stub" />;
  },
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));
let auroraStatus = null;
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: auroraStatus }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null, source: null }) }));
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
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';
import { buildPastSolarWindows } from '../utils/mapEvents.js';
import { getAuroraLocations } from '../api/auroraApi.js';

const TODAY = '2026-01-15';
const YESTERDAY = '2026-01-14';
const KEY = `${TODAY}:SUNSET`;

/** Six spots in three regions; Kelso is outside the planning area, Coquet has no measured Bortle. */
const SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East', bortleClass: 4 },
  { id: 2, name: 'Tynemouth', lat: 55.02, lng: -1.42, rid: 'North East', regionName: 'North East', bortleClass: 6 },
  { id: 3, name: 'Wastwater', lat: 54.44, lng: -3.3, rid: 'The Lakes', regionName: 'The Lakes', bortleClass: 2 },
  { id: 4, name: 'Kelso', lat: 56.2, lng: -2.43, rid: 'The Borders', regionName: 'The Borders', bortleClass: 5 },
  { id: 5, name: 'Alnmouth', lat: 55.39, lng: -1.61, rid: 'North East', regionName: 'North East', bortleClass: 5 },
  { id: 6, name: 'Coquet', lat: 55.33, lng: -1.53, rid: 'North East', regionName: 'North East', bortleClass: null },
].map((s) => ({ ...s, skySubject: true }));
const AREA_SPOTS = [SPOTS[0], SPOTS[1], SPOTS[2], SPOTS[4], SPOTS[5]];

const pointOf = (spot, score) => ({
  id: spot.id, name: spot.name, lat: spot.lat, lng: spot.lng, rid: spot.rid, r: [score],
});

/**
 * Tonight's window entry: the named ids rated (4 stars), the {id: reason} map stood down, every
 * other sky place unrated. Returns the entry and the rated points it implies.
 */
function entryFor({ rated = [], stood = {} }) {
  const states = new Map(SPOTS.map((s) => [
    `#${s.id}`,
    rated.includes(s.id) ? 'rated' : (s.id in stood ? 'stood' : 'unrated'),
  ]));
  return {
    entry: {
      unrated: [],
      stood: SPOTS.filter((s) => s.id in stood).map((s) => ({
        lat: s.lat, lng: s.lng, reason: stood[s.id], rid: s.rid, key: `#${s.id}`,
      })),
      states,
    },
    points: SPOTS.filter((s) => rated.includes(s.id)).map((s) => pointOf(s, 4)),
  };
}

const windows = (bestRating) => [
  { key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'This morning sunrise', time: '08:24', bestRating: 3, conf: 1 },
  { key: KEY, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating, conf: 1 },
];

function heatFor({ rated = [], stood = {}, bestRating = null }, overrides = {}) {
  const { entry, points } = entryFor({ rated, stood });
  return {
    enabled: true,
    hasHome: true,
    spots: SPOTS,
    areaSpots: AREA_SPOTS,
    pointsByKey: new Map([[KEY, points], [`${TODAY}:SUNRISE`, []]]),
    windows: windows(bestRating),
    windowStates: new Map([[KEY, entry]]),
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
    ...overrides,
  };
}

const verdictIndex = (regions) => buildRegionVerdictIndex([{
  date: TODAY,
  eventSummaries: [{
    targetType: 'SUNSET',
    regions: regions.map(([regionName, meanRating]) => ({
      regionName, meanRating, displayVerdict: 'MAYBE', slots: [{ canopy: false }],
    })),
  }],
}]);

let nonce = 0;
const makeLocations = () => SPOTS.map((s) => ({
  id: s.id,
  name: `${s.name}-${nonce}`,
  lat: s.lat,
  lon: s.lng,
  regionName: s.rid,
  bortleClass: s.bortleClass,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00` },
    sunrise: { rating: 4, solarEventTime: `${TODAY}T08:24:00` },
  }]]),
}));

async function renderMap(props = {}) {
  let result;
  const locations = makeLocations();
  await act(async () => {
    result = render(<MapView locations={locations} date={TODAY} autoEventType={null} {...props} />);
  });
  return { ...result, locations };
}

const openLegend = () => fireEvent.click(screen.getByTestId('wf-legend-chip'));
const openFilters = () => fireEvent.click(screen.getByTestId('wf-filters-chip'));

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  localStorage.clear();
  heatLayerProps.last = null;
  labelProps.last = null;
  mapEventHandlers = [];
  mockIsMobile = false;
  auroraStatus = null;
  nonce += 1;
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView stand-down — the point set the hatch is drawn around', () => {
  it('a WHOLE-MAP stand-down still hatches: no rated place, no bestRating, every place stood', async () => {
    const stood = Object.fromEntries(SPOTS.map((s) => [s.id, 'HIGH_CLOUD']));
    await renderMap({ heat: heatFor({ stood }) });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.stood.map((p) => p.key).sort()).toEqual(SPOTS.map((s) => `#${s.id}`).sort());
    expect(heatLayerProps.last.points).toEqual([]);
  });

  it('keeps the unrated hatch out of stood ground and the stood hatch out of unrated ground', async () => {
    await renderMap({ heat: heatFor({ rated: [1], stood: { 3: 'PRECIPITATION' }, bestRating: 4 }) });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.stood.map((p) => p.key)).toEqual(['#3']);
  });

  it('excludes TIDE_MISALIGNED: that is not "ruled out by the weather"', async () => {
    await renderMap({
      heat: heatFor({ rated: [1], stood: { 3: 'TIDE_MISALIGNED', 4: 'HIGH_CLOUD' }, bestRating: 4 }),
    });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.stood.map((p) => p.key)).toEqual(['#4']);
  });

  it('hands none on an astro night', async () => {
    await renderMap({
      heat: heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD' }, bestRating: 4 }), autoEventType: 'ASTRO',
    });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.stood).toEqual([]);
  });

  it('hands none while the scores have not loaded: the pane then offers the window no states at all', async () => {
    await renderMap({ heat: heatFor({ stood: { 3: 'HIGH_CLOUD' } }, { windowStates: new Map() }) });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.stood).toEqual([]);
  });

  it('hands none when the window holds no evidence: only unrated places', async () => {
    const heat = heatFor({});
    // A stood list with nothing rated or stood in the states is not evidence of anything.
    heat.windowStates.get(KEY).stood = [{
      lat: 54.44, lng: -3.3, reason: 'HIGH_CLOUD', rid: 'The Lakes', key: '#3',
    }];
    await renderMap({ heat });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.stood).toEqual([]);
  });

  it('is narrowed by the dark-sky filter as the colour is (Bortle <= 4 keeps Bamburgh and Wastwater)', async () => {
    await renderMap({
      heat: heatFor({ rated: [1], stood: { 2: 'HIGH_CLOUD', 3: 'HIGH_CLOUD' }, bestRating: 4 }),
    });
    expect(heatLayerProps.last.stood.map((p) => p.key).sort()).toEqual(['#2', '#3']);
    openFilters();
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.stood.map((p) => p.key)).toEqual(['#3']);
  });

  it('is NOT narrowed by the rating floor or the planning area: Kelso lies outside the area', async () => {
    await renderMap({ heat: heatFor({ rated: [1], stood: { 4: 'HIGH_CLOUD' }, bestRating: 4 }) });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.stood.map((p) => p.key)).toEqual(['#4']);
  });

  it('a PAST window is hatched from the pane\'s window states, like a served one', async () => {
    const pastKey = `${YESTERDAY}:SUNSET`;
    const locations = SPOTS.map((s) => ({
      id: s.id,
      name: `${s.name}-${nonce}`,
      lat: s.lat,
      lon: s.lng,
      regionName: s.rid,
      bortleClass: s.bortleClass,
      locationType: ['LANDSCAPE'],
      forecastsByDate: new Map([[YESTERDAY, { sunset: { rating: 4, solarEventTime: `${YESTERDAY}T16:10:00` } }]]),
    }));
    const { entry } = entryFor({ rated: [1], stood: { 3: 'PRECIPITATION' } });
    const heat = heatFor({ rated: [1], bestRating: 4 }, {
      windows: [...windows(4), {
        key: pastKey, date: YESTERDAY, targetType: 'SUNSET', past: true, bestRating: 4, conf: 0.82,
      }],
      pointsByKey: new Map([[KEY, []], [pastKey, [pointOf(SPOTS[0], 4)]]]),
      windowStates: new Map([[KEY, { unrated: [], stood: [], states: new Map() }], [pastKey, entry]]),
    });
    await act(async () => {
      render(
        <MapView
          locations={locations}
          date={YESTERDAY}
          autoEventType="SUNSET"
          forecastDates={[YESTERDAY, TODAY]}
          pastSolarWindows={buildPastSolarWindows(locations, TODAY)}
          heat={heat}
        />,
      );
    });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.stood.map((p) => p.key)).toEqual(['#3']);
  });
});

describe('MapView stand-down — the unscored line', () => {
  it('reads "Stood down by the weather" when places stood and none is rated', async () => {
    await renderMap({ heat: heatFor({ stood: { 3: 'HIGH_CLOUD' } }) });
    expect(screen.getByTestId('wf-map-heat-unscored'))
      .toHaveTextContent('Stood down by the weather — no place rated for this event');
  });

  it('keeps the old wording when nothing stood down', async () => {
    await renderMap({ heat: heatFor({}) });
    expect(screen.getByTestId('wf-map-heat-unscored')).toHaveTextContent('This event is not scored yet');
  });
});

describe('MapView stand-down — the keys', () => {
  it('the Heat legend names the hatch, with the unrated ground key withheld on a whole-window stand-down', async () => {
    await renderMap({ heat: heatFor({ stood: { 3: 'HIGH_CLOUD' } }) });
    openLegend();
    expect(screen.getByTestId('wf-legend-standdown')).toHaveTextContent('ruled out by the weather');
    expect(screen.queryByTestId('wf-legend-ground')).toBeNull();
  });

  it('the Heat legend carries both ground keys on a rated window with stood ground', async () => {
    await renderMap({ heat: heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD' }, bestRating: 4 }) });
    openLegend();
    expect(screen.getByTestId('wf-legend-standdown')).toBeInTheDocument();
    expect(screen.getByTestId('wf-legend-ground')).toBeInTheDocument();
  });

  it('has no stand-down key when nothing stood down', async () => {
    await renderMap({ heat: heatFor({ rated: [1], bestRating: 4 }) });
    openLegend();
    expect(screen.queryByTestId('wf-legend-standdown')).toBeNull();
  });

  it('the phone Ground row names the hatch, and opens on a whole-window stand-down', async () => {
    mockIsMobile = true;
    await renderMap({ heat: heatFor({ stood: { 3: 'HIGH_CLOUD' } }) });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    const row = screen.getByTestId('wf-map-peek-ground');
    expect(within(row).getByTestId('wf-map-peek-standdown')).toHaveTextContent('ruled out by the weather');
    expect(row).not.toHaveTextContent('not rated yet');
  });

  it('the phone Ground row carries both items on a rated window, and none without ground to name', async () => {
    mockIsMobile = true;
    const { unmount } = await renderMap({
      heat: heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD' }, bestRating: 4 }),
    });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    expect(screen.getByTestId('wf-map-peek-ground')).toHaveTextContent('not rated yet');
    expect(screen.getByTestId('wf-map-peek-standdown')).toBeInTheDocument();
    unmount();

    await renderMap({ heat: heatFor({ rated: [1], bestRating: 4 }) });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    expect(screen.queryByTestId('wf-map-peek-standdown')).toBeNull();
  });

});

describe('MapView stand-down — the footer', () => {
  it('appends " · N stood down" over the scope pool, tide stand-downs and out-of-area places excluded', async () => {
    // In the area: Wastwater (3) stood for the weather, Tynemouth (2) for the tide. Kelso (4) is
    // stood too but lies outside "My area", so it is not in the footer's population.
    await renderMap({
      heat: heatFor({
        rated: [1], stood: { 2: 'TIDE_MISALIGNED', 3: 'HIGH_CLOUD', 4: 'HIGH_CLOUD' }, bestRating: 4,
      }),
    });
    const count = screen.getByTestId('wf-map-counts-standdown');
    expect(count).toHaveTextContent('· 1 stood down');
    expect(screen.getByTestId('wf-map-counts-footer')).toContainElement(count);
    // The cause word is named on the ground, not repeated here.
    expect(count).not.toHaveTextContent(/cloud|rain|murk|weather/);
  });

  it('counts the whole catalogue on the Everywhere scope', async () => {
    await renderMap({
      heat: heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD', 4: 'HIGH_CLOUD' }, bestRating: 4 }),
    });
    openFilters();
    fireEvent.click(screen.getByRole('button', { name: 'Everywhere' }));
    expect(screen.getByTestId('wf-map-counts-standdown')).toHaveTextContent('· 2 stood down');
  });

  it('is omitted at zero', async () => {
    await renderMap({ heat: heatFor({ rated: [1], bestRating: 4 }) });
    expect(screen.getByTestId('wf-map-counts-footer')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-counts-standdown')).toBeNull();
  });

  it('is omitted on an astro night, which has no stand-down', async () => {
    await renderMap({
      heat: heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD' }, bestRating: 4 }), autoEventType: 'ASTRO',
    });
    expect(screen.queryByTestId('wf-map-counts-standdown')).toBeNull();
  });
});

describe('MapView stand-down — the regions that earn a label', () => {
  const regionsOf = () => labelProps.last.standRegions.map((r) => [r.rid, r.cause]);

  it('qualifies a region with a stood place and no rated place (zero-rated)', async () => {
    await renderMap({ heat: heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD' }, bestRating: 4 }) });
    expect(regionsOf()).toEqual([['The Lakes', 'cloud']]);
  });

  it('one rated place disqualifies its region, even a 1-star one', async () => {
    // Tynemouth rated; Bamburgh (same region) stood.
    await renderMap({ heat: heatFor({ rated: [2], stood: { 1: 'PRECIPITATION' }, bestRating: 4 }) });
    expect(regionsOf()).toEqual([]);
  });

  it('a finite served mean disqualifies it, even with no rated place in the states', async () => {
    await renderMap({
      heat: heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD' }, bestRating: 4 }),
      regionVerdictIndex: verdictIndex([['The Lakes', 2.5]]),
    });
    expect(regionsOf()).toEqual([]);
  });

  it('an absent served entry counts as no mean, so the label stands', async () => {
    await renderMap({
      heat: heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD' }, bestRating: 4 }),
      regionVerdictIndex: verdictIndex([['North East', 3]]),
    });
    expect(regionsOf()).toEqual([['The Lakes', 'cloud']]);
  });

  it('names the single shared cause, and any mix of two or more reasons as weather', async () => {
    await renderMap({
      heat: heatFor({ rated: [3], stood: { 1: 'PRECIPITATION', 2: 'PRECIPITATION', 5: 'HIGH_CLOUD' }, bestRating: 4 }),
    });
    expect(regionsOf()).toEqual([['North East', 'weather']]);
  });

  it('a shared cause across several places reads that cause', async () => {
    await renderMap({
      heat: heatFor({ rated: [3], stood: { 1: 'LOW_VISIBILITY', 2: 'LOW_VISIBILITY' }, bestRating: 4 }),
    });
    expect(regionsOf()).toEqual([['North East', 'murk']]);
  });

  it('is judged over the states map, not the star floor: a stood-only region qualifies with nothing drawn', async () => {
    // Every place stood: no rated place anywhere, so every stood region qualifies.
    const stood = Object.fromEntries(SPOTS.map((s) => [s.id, 'PRECIPITATION']));
    await renderMap({ heat: heatFor({ stood }) });
    expect(regionsOf().sort()).toEqual([
      ['North East', 'rain'], ['The Borders', 'rain'], ['The Lakes', 'rain'],
    ]);
  });

  it('is narrowed by the dark-sky pool, as the hatch is', async () => {
    await renderMap({
      heat: heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD', 4: 'HIGH_CLOUD' }, bestRating: 4 }),
    });
    expect(regionsOf().map(([r]) => r).sort()).toEqual(['The Borders', 'The Lakes']);
    openFilters();
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));
    // Kelso (Bortle 5) leaves the pool, and with it its region's label.
    expect(regionsOf()).toEqual([['The Lakes', 'cloud']]);
  });

  it('a tide stand-down earns no label', async () => {
    await renderMap({ heat: heatFor({ rated: [1], stood: { 3: 'TIDE_MISALIGNED' }, bestRating: 4 }) });
    expect(regionsOf()).toEqual([]);
  });

  it('hands none on an astro night', async () => {
    await renderMap({
      heat: heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD' }, bestRating: 4 }), autoEventType: 'ASTRO',
    });
    expect(labelProps.last.standRegions).toEqual([]);
  });

  it('a PAST window consults no served mean: the label stands though the index holds one', async () => {
    const pastKey = `${YESTERDAY}:SUNSET`;
    const locations = SPOTS.map((s) => ({
      id: s.id,
      name: `${s.name}-${nonce}`,
      lat: s.lat,
      lon: s.lng,
      regionName: s.rid,
      bortleClass: s.bortleClass,
      locationType: ['LANDSCAPE'],
      forecastsByDate: new Map([[YESTERDAY, { sunset: { rating: 4, solarEventTime: `${YESTERDAY}T16:10:00` } }]]),
    }));
    const { entry } = entryFor({ rated: [1], stood: { 3: 'HIGH_CLOUD' } });
    const heat = heatFor({ rated: [1], bestRating: 4 }, {
      windows: [...windows(4), {
        key: pastKey, date: YESTERDAY, targetType: 'SUNSET', past: true, bestRating: 4, conf: 0.82,
      }],
      pointsByKey: new Map([[KEY, []], [pastKey, [pointOf(SPOTS[0], 4)]]]),
      windowStates: new Map([[pastKey, entry]]),
    });
    const sameDayIndex = buildRegionVerdictIndex([{
      date: YESTERDAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'The Lakes', meanRating: 2.5, displayVerdict: 'MAYBE', slots: [{ canopy: false }],
        }],
      }],
    }]);
    await act(async () => {
      render(
        <MapView
          locations={locations}
          date={YESTERDAY}
          autoEventType="SUNSET"
          forecastDates={[YESTERDAY, TODAY]}
          pastSolarWindows={buildPastSolarWindows(locations, TODAY)}
          heat={heat}
          regionVerdictIndex={sameDayIndex}
        />,
      );
    });
    expect(regionsOf()).toEqual([['The Lakes', 'cloud']]);
  });
});

describe('MapView stand-down — review corrections', () => {
  const regionsOf = () => labelProps.last.standRegions.map((r) => [r.rid, r.cause]);
  const darkSky = () => {
    openFilters();
    fireEvent.click(screen.getByTestId('dark-sky-filter-toggle'));
  };

  it('the unscored line says "Some places" while unrated places remain in the window', async () => {
    const heat = heatFor({ stood: { 3: 'HIGH_CLOUD' } });
    heat.windowStates.get(KEY).unrated = [pointOf(SPOTS[0], 0)];
    await renderMap({ heat });
    expect(screen.getByTestId('wf-map-heat-unscored'))
      .toHaveTextContent('Some places stood down by the weather — none rated yet');
  });

  it('dark sky cannot turn the stood wording back into "not scored yet"', async () => {
    // Tynemouth (Bortle 6) is the only stood place; dark sky narrows it out of the hatch.
    await renderMap({ heat: heatFor({ stood: { 2: 'HIGH_CLOUD' } }) });
    expect(screen.getByTestId('wf-map-heat-unscored')).toHaveTextContent('Stood down by the weather');
    darkSky();
    expect(heatLayerProps.last.stood).toEqual([]);
    expect(screen.getByTestId('wf-map-heat-unscored')).toHaveTextContent('Stood down by the weather');
  });

  it('a tide-only window still reads "not scored yet", with no stand-down key and no footer count', async () => {
    await renderMap({ heat: heatFor({ stood: { 3: 'TIDE_MISALIGNED' } }) });
    expect(screen.getByTestId('wf-map-heat-unscored')).toHaveTextContent('This event is not scored yet');
    expect(screen.queryByTestId('wf-map-counts-standdown')).toBeNull();
    openLegend();
    expect(screen.queryByTestId('wf-legend-standdown')).toBeNull();
    expect(screen.queryByTestId('wf-legend-ground')).toBeNull();
  });

  it('the legend key carries a "Stood down" heading, on desktop and on the phone', async () => {
    const heat = heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD' }, bestRating: 4 });
    const { unmount } = await renderMap({ heat });
    openLegend();
    expect(within(screen.getByTestId('wf-legend-standdown')).getByText('Stood down'))
      .toHaveClass('wf-legend-ramp-label');
    unmount();
    mockIsMobile = true;
    await renderMap({ heat });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    expect(screen.getByTestId('wf-map-peek-standdown')).toHaveTextContent(/Stood down\s+ruled out/);
  });

  it('the region label\'s rated side is narrowed too: a rated place dark sky hides cannot block it', async () => {
    // Tynemouth (Bortle 6) is rated, Bamburgh (Bortle 4) stood, same region.
    await renderMap({ heat: heatFor({ rated: [2], stood: { 1: 'PRECIPITATION' }, bestRating: 4 }) });
    expect(regionsOf()).toEqual([]);
    darkSky();
    expect(regionsOf()).toEqual([['North East', 'rain']]);
  });

  it('the footer count is the scope pool\'s, not the narrowed list: dark sky leaves it at 2', async () => {
    await renderMap({
      heat: heatFor({ rated: [1], stood: { 2: 'HIGH_CLOUD', 3: 'HIGH_CLOUD' }, bestRating: 4 }),
    });
    expect(screen.getByTestId('wf-map-counts-standdown')).toHaveTextContent('· 2 stood down');
    darkSky();
    expect(heatLayerProps.last.stood.map((p) => p.key)).toEqual(['#3']);
    expect(screen.getByTestId('wf-map-counts-standdown')).toHaveTextContent('· 2 stood down');
  });

  it('is NOT narrowed by the rating floor: a floor of 5 leaves the hatch and the labels as they were', async () => {
    const heat = heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD', 4: 'PRECIPITATION' }, bestRating: 4 });
    const { unmount } = await renderMap({ heat });
    const before = [heatLayerProps.last.stood.map((p) => p.key), regionsOf()];
    expect(before[0]).toEqual(['#3', '#4']);
    unmount();
    localStorage.setItem('mapFilterMinStars', '5');
    await renderMap({ heat });
    expect([heatLayerProps.last.stood.map((p) => p.key), regionsOf()]).toEqual(before);
  });

  it('an aurora night has no hatch, no labels, no key and no footer count', async () => {
    auroraStatus = { active: true, level: 'MODERATE', currentNightDate: TODAY };
    getAuroraLocations.mockResolvedValue([{ location: { name: SPOTS[0].name }, stars: 4 }]);
    await renderMap({
      heat: heatFor({ rated: [1], stood: { 3: 'HIGH_CLOUD' }, bestRating: 4 }), autoEventType: 'AURORA',
    });
    await screen.findByTestId('map-heat-layer');
    expect(heatLayerProps.last.stood).toEqual([]);
    expect(labelProps.last.standRegions).toEqual([]);
    expect(screen.queryByTestId('wf-map-counts-standdown')).toBeNull();
    openLegend();
    expect(screen.queryByTestId('wf-legend-standdown')).toBeNull();
  });
});
