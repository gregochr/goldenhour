/**
 * The Map tab for a body the server shaped by a Lite reader's sub-region picks (sub-regions-plan S5a-1):
 * the veil and outline grounds handed to the field, the gated places kept on the map as grey dots, the
 * counts that leave them out, the verdict word that survives the stripped means, and the legend rows.
 *
 * The two layers (`MapHeatLayer`, `MapLabels`) are probes that record what `MapView` hands them, as in
 * `MapViewStandDown.test.jsx`: their pixels are `heatFieldOutside.test.js`'s and `MapLabels.test.jsx`'s.
 * The role is deliberately fixed and never consulted: "shaped" is the served marker, nothing else.
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

let mockRole = 'LITE_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));
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
import { buildRegionVerdictIndex, buildShapedLeadIndex } from '../utils/mapVerdict.js';

const TODAY = '2026-01-15';
const KEY = `${TODAY}:SUNSET`;

/**
 * Six places. Bamburgh is the reader's own and rated; Tynemouth is theirs and not rated yet; the other
 * four are outside their sub-regions (the server marked their rows `gated: true`). Kelso lies outside the
 * planning area, the rest inside.
 */
const SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East', bortleClass: 4 },
  { id: 2, name: 'Tynemouth', lat: 55.02, lng: -1.42, rid: 'North East', regionName: 'North East', bortleClass: 6 },
  { id: 3, name: 'Wastwater', lat: 54.44, lng: -3.3, rid: 'The Lakes', regionName: 'The Lakes', bortleClass: 2 },
  { id: 4, name: 'Kelso', lat: 56.2, lng: -2.43, rid: 'The Borders', regionName: 'The Borders', bortleClass: 5 },
  { id: 5, name: 'Alnmouth', lat: 55.39, lng: -1.61, rid: 'North East', regionName: 'North East', bortleClass: 5 },
  { id: 6, name: 'Coquet', lat: 55.33, lng: -1.53, rid: 'North East', regionName: 'North East', bortleClass: null },
].map((s) => ({ ...s, skySubject: true }));
const AREA_SPOTS = [SPOTS[0], SPOTS[1], SPOTS[2], SPOTS[4], SPOTS[5]];
const GATED_IDS = [3, 4, 5, 6];

const pointOf = (spot, score) => ({
  id: spot.id, name: spot.name, lat: spot.lat, lng: spot.lng, rid: spot.rid, r: [score],
});

function statesFor({ rated = [1], gated = GATED_IDS } = {}) {
  return new Map(SPOTS.map((s) => [
    `#${s.id}`, gated.includes(s.id) ? 'gated' : (rated.includes(s.id) ? 'rated' : 'unrated'),
  ]));
}

/**
 * The shaped body's heat prop. `bestRating` is null by default because the server STRIPS the window's
 * best star from a shaped Lite response; the shaped checks must work without it.
 */
function shapedHeat({
  rated = [1], gated = GATED_IDS, allowedCount = 2, bestRating = null, overrides = {},
} = {}) {
  const states = statesFor({ rated, gated });
  return {
    enabled: true,
    hasHome: true,
    shaped: true,
    allowedCount,
    spots: SPOTS,
    areaSpots: AREA_SPOTS,
    pointsByKey: new Map([
      [KEY, SPOTS.filter((s) => rated.includes(s.id)).map((s) => pointOf(s, 4))],
      [`${TODAY}:SUNRISE`, []],
    ]),
    windows: [
      { key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'This morning sunrise', time: '08:24', bestRating: null, conf: 1 },
      { key: KEY, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating, conf: 1 },
    ],
    windowStates: new Map([[KEY, {
      unrated: SPOTS.filter((s) => states.get(`#${s.id}`) === 'unrated').map((s) => ({
        id: s.id, name: s.name, lat: s.lat, lng: s.lng, rid: s.rid,
      })),
      stood: [],
      states,
    }]]),
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
    ...overrides,
  };
}

/** The same window as a Pro reader gets it: not shaped, nothing gated, the served best star present. */
function unshapedHeat() {
  const states = new Map(SPOTS.map((s) => [`#${s.id}`, s.id === 1 ? 'rated' : 'unrated']));
  return {
    ...shapedHeat({ gated: [] }),
    shaped: false,
    allowedCount: null,
    windows: shapedHeat().windows.map((w) => (w.key === KEY ? { ...w, bestRating: 4 } : w)),
    windowStates: new Map([[KEY, {
      unrated: SPOTS.filter((s) => s.id !== 1).map((s) => ({
        id: s.id, name: s.name, lat: s.lat, lng: s.lng, rid: s.rid,
      })),
      stood: [],
      states,
    }]]),
  };
}

let nonce = 0;
const makeLocations = ({ gated = GATED_IDS } = {}) => SPOTS.map((s) => ({
  id: s.id,
  name: `${s.name}-${nonce}`,
  lat: s.lat,
  lon: s.lng,
  regionName: s.rid,
  bortleClass: s.bortleClass,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: gated.includes(s.id)
      ? { rating: null, gated: true, solarEventTime: `${TODAY}T16:12:00` }
      : { rating: s.id === 1 ? 4 : null, solarEventTime: `${TODAY}T16:12:00` },
    sunrise: { rating: null, solarEventTime: `${TODAY}T08:24:00` },
  }]]),
}));

/** Served region records with NO meanRating, as the shaper leaves them, and the lead region by tier. */
const shapedDays = [{
  date: TODAY,
  eventSummaries: [{
    targetType: 'SUNSET',
    shapedLeadRegion: 'North East',
    regions: ['North East', 'The Lakes'].map((regionName, i) => ({
      regionName,
      displayVerdict: i === 0 ? 'WORTH_IT' : 'MAYBE',
      scoredLocationCount: 3,
      slots: [{ canopy: false, gated: true }],
    })),
  }],
}];

async function renderMap(props = {}) {
  let result;
  const locations = props.locations ?? makeLocations();
  await act(async () => {
    result = render(
      <MapView
        locations={locations}
        date={TODAY}
        autoEventType={null}
        regionVerdictIndex={buildRegionVerdictIndex(shapedDays)}
        shapedLeadIndex={buildShapedLeadIndex(shapedDays)}
        {...props}
      />,
    );
  });
  return { ...result, locations };
}

const openLegend = () => fireEvent.click(screen.getByTestId('wf-legend-chip'));

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T12:00:00Z`));
  localStorage.clear();
  heatLayerProps.last = null;
  labelProps.last = null;
  mapEventHandlers = [];
  mockIsMobile = false;
  mockRole = 'LITE_USER';
  auroraStatus = null;
  nonce += 1;
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView, shaped — what the field is handed', () => {
  it('hands the layer the outside places and every other place, rated or not', async () => {
    await renderMap({ heat: shapedHeat() });
    await screen.findByTestId('map-heat-layer');

    expect(heatLayerProps.last.outside.map((p) => p.id).sort()).toEqual(GATED_IDS);
    expect(heatLayerProps.last.allowed.map((p) => p.id).sort()).toEqual([1, 2]);
  });

  it('keeps a gated place out of the colour pool and out of the "not rated yet" hatch', async () => {
    await renderMap({ heat: shapedHeat() });
    await screen.findByTestId('map-heat-layer');

    expect(heatLayerProps.last.points.map((p) => p.id)).toEqual([1]);
    // Only Tynemouth (allowed, unrated) is hatched; the four gated places never are, which is the
    // whole point of a state of their own. And the hatch survives the stripped window best star.
    expect(heatLayerProps.last.unrated.map((p) => p.id)).toEqual([2]);
  });

  it('passes nothing for a body that is not shaped, however the role reads', async () => {
    mockRole = 'LITE_USER';
    await renderMap({ heat: unshapedHeat(), locations: makeLocations({ gated: [] }) });
    await screen.findByTestId('map-heat-layer');

    expect(heatLayerProps.last.outside).toBeNull();
    expect(heatLayerProps.last.allowed).toBeNull();
  });

  it('is the same for Pro and Admin: the marker decides, not the role', async () => {
    for (const role of ['PRO_USER', 'ADMIN', undefined]) {
      mockRole = role;
      heatLayerProps.last = null;
      const view = await renderMap({ heat: unshapedHeat(), locations: makeLocations({ gated: [] }) });
      await screen.findByTestId('map-heat-layer');
      expect(heatLayerProps.last.outside).toBeNull();
      view.unmount();
    }
  });

  it('hands a reader with no picks every place as outside, and nothing allowed', async () => {
    const all = SPOTS.map((s) => s.id);
    await renderMap({
      heat: shapedHeat({ rated: [], gated: all, allowedCount: 0 }),
      locations: makeLocations({ gated: all }),
    });
    await screen.findByTestId('map-heat-layer');

    expect(heatLayerProps.last.outside).toHaveLength(SPOTS.length);
    expect(heatLayerProps.last.allowed).toEqual([]);
    expect(heatLayerProps.last.points).toEqual([]);
    expect(heatLayerProps.last.unrated).toEqual([]);
  });

  it('draws nothing for a window that holds no place states (scores pending): no claim either way', async () => {
    await renderMap({ heat: shapedHeat({ overrides: { windowStates: new Map() } }) });
    await screen.findByTestId('map-heat-layer');

    expect(heatLayerProps.last.outside).toBeNull();
  });
});

describe('MapView, shaped — the places stay on the map as grey dots', () => {
  it('keeps every gated place in the named pool even under the default star floor, marked gated', async () => {
    await renderMap({ heat: shapedHeat() });
    await screen.findByTestId('map-labels-stub');

    const spots = labelProps.last.spots;
    const gatedNames = spots.filter((s) => s.gated).map((s) => s.name.split('-')[0]);
    // Kelso lies outside the planning area, and the scope narrows the named pool.
    expect(gatedNames.sort()).toEqual(['Alnmouth', 'Coquet', 'Wastwater']);
    spots.filter((s) => s.gated).forEach((s) => expect(s.rating).toBeNull());
    expect(spots.find((s) => s.name.startsWith('Bamburgh')).gated).toBe(false);
  });

  it('draws the unrated allowed place nowhere: the star floor still hides it, as it always did', async () => {
    await renderMap({ heat: shapedHeat() });
    await screen.findByTestId('map-labels-stub');

    expect(labelProps.last.spots.map((s) => s.name.split('-')[0])).not.toContain('Tynemouth');
  });

  it('marks nothing gated for a body that is not shaped', async () => {
    await renderMap({ heat: unshapedHeat(), locations: makeLocations({ gated: [] }) });
    await screen.findByTestId('map-labels-stub');

    expect(labelProps.last.spots.filter((s) => s.gated)).toEqual([]);
  });
});

describe('MapView, shaped — counts leave the outside places out of both halves', () => {
  it('prints the reader\'s own places in "x of y shown", not 260', async () => {
    await renderMap({ heat: shapedHeat() });

    const footer = screen.getByTestId('wf-map-counts-footer');
    expect(footer).toHaveTextContent(/^1 of 2 shown/);
    expect(footer).not.toHaveTextContent(/rated/);
  });

  it('prints the full count for a body that is not shaped', async () => {
    await renderMap({ heat: unshapedHeat(), locations: makeLocations({ gated: [] }) });

    expect(screen.getByTestId('wf-map-counts-footer')).toHaveTextContent(/of 5 shown/);
  });
});

describe('MapView, shaped — the window without its served best star', () => {
  it('keeps the colour key and says nothing unscored while an allowed place is rated', async () => {
    await renderMap({ heat: shapedHeat({ bestRating: null }) });

    expect(screen.queryByTestId('wf-map-heat-unscored')).toBeNull();
    expect(screen.getByTestId('wf-map-heat-legend')).toBeInTheDocument();
  });

  it('says the event is unscored when none of the reader\'s own places is rated', async () => {
    await renderMap({ heat: shapedHeat({ rated: [] }), locations: makeLocations() });

    expect(screen.getByTestId('wf-map-heat-unscored')).toHaveTextContent('This event is not scored yet');
  });

  it('says nothing unscored for a reader with no picks: nothing failed to be scored for them', async () => {
    const all = SPOTS.map((s) => s.id);
    await renderMap({
      heat: shapedHeat({ rated: [], gated: all, allowedCount: 0 }),
      locations: makeLocations({ gated: all }),
    });

    expect(screen.queryByTestId('wf-map-heat-unscored')).toBeNull();
  });
});

describe('MapView, shaped — the pill keeps its verdict word', () => {
  it('names the served lead region and its tier although no region carries a mean', async () => {
    await renderMap({ heat: shapedHeat() });

    const pill = screen.getByTestId('wf-win-pill');
    expect(within(pill).getByTestId('wf-win-verdict-word')).toHaveTextContent(/worth it/i);
    expect(within(pill).getByTestId('wf-win-verdict-region')).toHaveTextContent('North East');
  });

  it('shows no star on the pill', async () => {
    await renderMap({ heat: shapedHeat() });

    expect(screen.getByTestId('wf-win-pill').textContent).not.toContain('★');
  });
});

describe('MapView, shaped — the legend', () => {
  it('adds the two rows and the note with the served allowed count and the roster', async () => {
    await renderMap({ heat: shapedHeat({ allowedCount: 2 }) });
    openLegend();

    expect(screen.getByTestId('wf-legend-outside')).toHaveTextContent('outside your sub-regions');
    expect(screen.getByTestId('wf-legend-yours')).toBeInTheDocument();
    expect(screen.getByTestId('wf-legend-picks-note'))
      .toHaveTextContent(`Colour shows the 2 places in your sub-regions. Pro rates all ${SPOTS.length}.`);
  });

  it('is the old legend for a body that is not shaped', async () => {
    await renderMap({ heat: unshapedHeat(), locations: makeLocations({ gated: [] }) });
    openLegend();

    expect(screen.queryByTestId('wf-legend-picks')).toBeNull();
    expect(screen.queryByTestId('wf-legend-picks-note')).toBeNull();
  });

  it('tells a reader with no picks to choose them in Settings', async () => {
    const all = SPOTS.map((s) => s.id);
    await renderMap({
      heat: shapedHeat({ rated: [], gated: all, allowedCount: 0 }),
      locations: makeLocations({ gated: all }),
    });
    openLegend();

    expect(screen.getByTestId('wf-legend-picks-note'))
      .toHaveTextContent('Choose your sub-regions in Settings to see ratings.');
  });
});

describe('MapView, shaped — zero picks and the land clip', () => {
  const boundsStub = () => ({
    getSouth: () => 0, getWest: () => 0, getNorth: () => 0, getEast: () => 0, pad: () => ({ contains: () => false }),
  });

  it('hides the "x of y shown" count for a reader with no picks: it would read 0 of 0', async () => {
    const all = SPOTS.map((s) => s.id);
    await renderMap({
      heat: shapedHeat({ rated: [], gated: all, allowedCount: 0 }),
      locations: makeLocations({ gated: all }),
    });

    expect(screen.queryByText(/shown/)).toBeNull();
  });

  it('keeps the count for a reader who has picks', async () => {
    await renderMap({ heat: shapedHeat() });

    expect(screen.getByTestId('wf-map-counts-footer')).toHaveTextContent('shown');
  });

  it('shows the legend rows and note only while the veil and outline are drawn: below the land-clip zoom', async () => {
    await renderMap({ heat: shapedHeat() });
    openLegend();
    expect(screen.getByTestId('wf-legend-picks')).toBeInTheDocument();

    act(() => {
      const target = { getZoom: () => 12, getBounds: boundsStub };
      mapEventHandlers.forEach((handlers) => handlers.zoomend?.({ target }));
    });

    expect(screen.queryByTestId('wf-legend-picks')).toBeNull();
    expect(screen.queryByTestId('wf-legend-picks-note')).toBeNull();
  });
});

describe('MapView, shaped — the window panel k of m at 4 stars', () => {
  const openPanel = () => {
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.click(screen.getByTestId('wf-win-more'));
  };
  const statOf = (name) => {
    const row = screen.getAllByTestId('wf-win-panel-row')
      .find((r) => r.textContent.includes(name));
    return within(row).getByTestId('wf-win-panel-stat').textContent;
  };

  it('counts the reader own places only: the outside places are in neither the k nor the m', async () => {
    await renderMap({ heat: shapedHeat() });
    openPanel();

    // North East holds Bamburgh (rated 4, the reader's), Tynemouth (the reader's, unrated) and the
    // outside Alnmouth and Coquet: 1 of 2, never 1 of 4.
    expect(statOf('North East')).toMatch(/1 of 2 at 4/);
    expect(statOf('North East')).not.toMatch(/of 4/);
  });
});
