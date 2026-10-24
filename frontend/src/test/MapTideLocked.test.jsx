/**
 * The Map tab's tide lock for a LITE reader (docs/engineering/tide-pro-gate-plan.md §5, phase F1).
 *
 * <p>The tide CURVE and the per-location MATCH are Pro. `MapView` receives one derived boolean,
 * `tideLocked`, and clears the match at ONE choke point (`mapTideIndex`); the desktop strip becomes
 * a static teaser; the phone peek's Tide section draws the teaser content; the Layers Tide segment
 * is drawn locked. This file mounts the real `MapView`, the real `MapTideStrip`, the real
 * `MapPeekTideSection` and the real `MapRegionPanel`, and states each claim from the PRO side too,
 * so a test cannot pass on a component that locks everyone.
 *
 * <p>`MapLabels`/`PinsLayer` are probes that surface `spot.tideTier` as `data-tide` straight off
 * the real `labelSpots`; `MapCallout` records the props `MapView` hands it (its `TideFitBlock` is
 * driven by exactly those two).
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen, within,
} from '@testing-library/react';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';
import { tideIndexOf } from './tideFactsFixture.js';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

let mapEventHandlers = [];
const IN_VIEW_BOUNDS = { pad: () => ({ contains: () => true }) };
const AWAY_BOUNDS = { pad: () => ({ contains: () => false }) };
let mockMapBounds = IN_VIEW_BOUNDS;
const mockMapInstance = { getZoom: () => 9, getBounds: () => mockMapBounds };
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: (handlers) => {
    mapEventHandlers.push(handlers);
    return mockMapInstance;
  },
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, offsetWidth: 800, parentElement: document.body }),
    getZoom: () => 9,
    getBounds: () => mockMapBounds,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    panInside: () => {},
    getSize: () => ({ x: 390, y: 844 }),
    latLngToContainerPoint: () => ({ x: 195, y: 400 }),
  }),
}));

let mockRole = 'LITE_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));
let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
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
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: () => null }));
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));

const calloutProps = { last: null };
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => {
    calloutProps.last = props;
    return props.location ? <div data-testid="mock-callout">{props.location.name}</div> : null;
  },
}));
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => (
    <div data-testid="probe-labels">
      {(props.spots || []).map((s) => (
        <button
          key={s.name}
          type="button"
          data-testid="probe-spot"
          data-name={s.name}
          data-tide={s.tideTier ?? undefined}
          onClick={() => props.onSelect(s.name)}
        >
          {s.name}
        </button>
      ))}
    </div>
  ),
}));
vi.mock('../components/map/PinsLayer.jsx', () => ({
  default: (props) => (
    <div data-testid="probe-pins">
      {(props.spots || []).map((s) => (
        <div key={s.name} data-testid="probe-pin" data-tide={s.tideTier ?? undefined}>{s.name}</div>
      ))}
    </div>
  ),
}));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';
const KEY_SUNSET = `${TODAY}:SUNSET`;
const KEY_SUNRISE = `${TODAY}:SUNRISE`;

/** A distinctive height string: its absence is the claim that no curve fact reaches a LITE reader. */
const HEIGHT = '3.84 m';
const TIDE = {
  state: 'MID',
  direction: 'FALLING',
  locationName: 'Bamburgh',
  range: '5.1 m',
  rangeAnomaly: 'about average',
  curve: [0, 0.3, 0.7, 1, 0.6, 0.2, 0],
  windowPosition: 0.4,
  windowLevel: 0.55,
  sunrisePosition: 0.32,
  sunsetPosition: 0.7,
  extremes: [{ kind: 'LW', position: 0.3, time: '07:30' }],
  heightAtWindow: HEIGHT,
};

/** Bamburgh's water lands on the light, Tynemouth's does not, Wastwater is inland. */
const SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East', coastal: true },
  { id: 2, name: 'Tynemouth', lat: 55.02, lng: -1.42, rid: 'North East', regionName: 'North East', coastal: true },
  { id: 3, name: 'Wastwater', lat: 54.44, lng: -3.3, rid: 'The Lakes', regionName: 'The Lakes', coastal: false },
];

function makeLocations({ sunriseRating = 4 } = {}) {
  return SPOTS.map((s) => ({
    id: s.id,
    name: s.name,
    lat: s.lat,
    lon: s.lng,
    regionName: s.rid,
    bortleClass: 4,
    locationType: ['LANDSCAPE'],
    tideType: s.coastal ? ['HIGH'] : [],
    forecastsByDate: new Map([[TODAY, {
      sunrise: { rating: sunriseRating, solarEventTime: `${TODAY}T08:24:00` },
      sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00` },
    }]]),
  }));
}

const slotFor = (spot, aligned) => ({
  canopy: false,
  locationId: spot.id,
  locationName: spot.name,
  tideState: 'HIGH',
  tideAligned: aligned,
  tideOnTheLight: aligned,
  tideFitPhrase: aligned ? 'Water at the light' : 'Wrong water for this place',
  tideShortfall: aligned ? null : 'LOW',
  nearestSolarOffsetPhrase: 'HW 12 min after sunset',
});

function daysWith(event) {
  return [{
    date: TODAY,
    eventSummaries: [{
      targetType: event,
      regions: [{
        regionName: 'North East',
        meanRating: 4.2,
        displayVerdict: 'WORTH_IT',
        slots: [slotFor(SPOTS[0], true), slotFor(SPOTS[1], false)],
      }, {
        regionName: 'The Lakes', meanRating: 4.0, displayVerdict: 'WORTH_IT', slots: [{ canopy: false }],
      }],
    }],
  }];
}

const DAYS = [...daysWith('SUNSET'), ...daysWith('SUNRISE')].reduce((acc, d, i) => {
  if (i === 0) return [d];
  return [{ ...acc[0], eventSummaries: [...acc[0].eventSummaries, ...d.eventSummaries] }];
}, []);

const pointOf = (s) => ({
  id: s.id, name: s.name, lat: s.lat, lng: s.lng, rid: s.rid, r: [4],
});

function heatProp({ stood = null } = {}) {
  const states = new Map(SPOTS.map((s) => [`#${s.id}`, stood && s.id in stood ? 'stood' : 'rated']));
  return {
    enabled: true,
    hasHome: false,
    spots: SPOTS,
    areaSpots: SPOTS,
    pointsByKey: new Map([
      [KEY_SUNSET, SPOTS.filter((s) => !stood || !(s.id in stood)).map(pointOf)],
      [KEY_SUNRISE, SPOTS.map(pointOf)],
    ]),
    windows: [
      {
        key: KEY_SUNRISE, date: TODAY, targetType: 'SUNRISE', label: 'This morning', time: '08:24',
        bestRating: 4, conf: 1, tide: TIDE,
      },
      {
        key: KEY_SUNSET, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12',
        bestRating: 4, conf: 1, tide: TIDE,
      },
    ],
    windowStates: stood ? new Map([[KEY_SUNSET, {
      unrated: [],
      stood: SPOTS.filter((s) => s.id in stood).map((s) => ({
        lat: s.lat, lng: s.lng, reason: stood[s.id], rid: s.rid, key: `#${s.id}`,
      })),
      states,
    }]]) : undefined,
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
  };
}

function mapElement(props = {}) {
  return (
    <MapView
      locations={makeLocations()}
      date={TODAY}
      autoEventType="SUNSET"
      heat={heatProp()}
      regionVerdictIndex={buildRegionVerdictIndex(DAYS)}
      tideAlignmentIndex={tideIndexOf(DAYS)}
      tideLocked
      {...props}
    />
  );
}

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(mapElement(props));
  });
  return result;
}

async function rerenderMap(result, props = {}) {
  await act(async () => {
    result.rerender(mapElement(props));
  });
}

async function reportBounds(bounds) {
  mockMapBounds = bounds;
  const handlers = mapEventHandlers.find((h) => typeof h.moveend === 'function');
  await act(async () => {
    handlers.moveend({ target: { getBounds: () => bounds } });
  });
}

beforeEach(() => {
  mockRole = 'LITE_USER';
  mockIsMobile = false;
  mockMapBounds = IN_VIEW_BOUNDS;
  mapEventHandlers = [];
  calloutProps.last = null;
  localStorage.clear();
});
afterEach(() => {
  localStorage.clear();
  vi.clearAllMocks();
});

describe('Map tide lock — the desktop teaser (§5.2)', () => {
  it('LITE: the strip is a one-line teaser with the state, the Pro pill and an aria-hidden spark', async () => {
    await renderMap();
    const strip = screen.getByTestId('wf-tide-strip');
    // The root is load-bearing DOM (band bar, footer lift, obstacle lists): kept exactly.
    expect(strip).toHaveClass('wf-map-tide-strip');
    expect(strip).toHaveAttribute('role', 'group');
    expect(strip).toHaveAttribute('aria-label', 'Tide');
    const row = within(strip).getByTestId('wf-tide-strip-locked');
    expect(row).toHaveClass('wf-tide-strip-locked');
    expect(within(row).getByTestId('wf-tide-strip-state')).toHaveTextContent(/mid tide.*falling|falling/i);
    expect(within(row).getByText('Pro')).toHaveClass('wf-lens-pro');
    const spark = within(row).getByTestId('wf-tide-strip-spark');
    expect(spark.tagName.toLowerCase()).toBe('svg');
    expect(spark).toHaveAttribute('aria-hidden', 'true');
    expect(spark).toHaveAttribute('focusable', 'false');
  });

  it('LITE: no toggle, no header, no chart, no footer, and not a trace of the height', async () => {
    await renderMap();
    const strip = screen.getByTestId('wf-tide-strip');
    expect(screen.queryByTestId('wf-tide-strip-toggle')).toBeNull();
    expect(screen.queryByTestId('wf-tide-strip-header')).toBeNull();
    expect(screen.queryByTestId('wf-tide-strip-chart')).toBeNull();
    expect(screen.queryByTestId('wf-tide-strip-footer')).toBeNull();
    expect(strip).not.toHaveTextContent(HEIGHT);
    expect(strip).not.toHaveTextContent(/dimmed/i);
    expect(strip.querySelector('a[href]')).toBeNull();
    expect(strip.querySelector('button')).toBeNull();
  });

  it('the spark never depends on the served tide: identical markup for two different tides', async () => {
    const first = await renderMap();
    const a = screen.getByTestId('wf-tide-strip-spark').outerHTML;
    first.unmount();
    const other = heatProp();
    other.windows = other.windows.map((w) => ({
      ...w, tide: { ...TIDE, state: 'LOW', direction: 'RISING', curve: [1, 0.5, 0], heightAtWindow: '0.2 m' },
    }));
    await renderMap({ heat: other });
    expect(screen.getByTestId('wf-tide-strip-spark').outerHTML).toBe(a);
  });

  it('PRO (the positive half): the same fixture draws the full strip with its toggle, chart and footer', async () => {
    mockRole = 'ADMIN';
    await renderMap({ tideLocked: false });
    expect(screen.queryByTestId('wf-tide-strip-locked')).toBeNull();
    expect(screen.getByTestId('wf-tide-strip-toggle')).toBeInTheDocument();
    expect(screen.getByTestId('wf-tide-strip-chart')).toBeInTheDocument();
    expect(screen.getByTestId('wf-tide-strip-footer')).toHaveTextContent(/dimmed/i);
    expect(screen.getByTestId('wf-tide-strip')).toHaveTextContent(HEIGHT);
  });

  it('the locked teaser still reports its height through onHeightChange (the callout band and footer lift follow it)', async () => {
    const seen = [];
    const RealRO = global.ResizeObserver;
    global.ResizeObserver = class { observe() {} disconnect() {} };
    try {
      const { default: MapTideStrip } = await import('../components/map/MapTideStrip.jsx');
      const pane = { current: document.createElement('div') };
      render(
        <MapTideStrip
          locked
          model={{ visible: true, dimmed: [], matched: [], namedCoastal: [{}] }}
          tide={TIDE}
          mapPaneRef={pane}
          onHeightChange={(h) => seen.push(h)}
        />,
      );
    } finally {
      global.ResizeObserver = RealRO;
    }
    expect(seen.length).toBeGreaterThan(0);
    expect(typeof seen[0]).toBe('number');
  });

  it('the default is unlocked: a render that passes no tideLocked is the PRO strip', async () => {
    mockRole = 'ADMIN';
    await renderMap({ tideLocked: undefined });
    expect(screen.getByTestId('wf-tide-strip-toggle')).toBeInTheDocument();
  });

  it('the teaser follows the strip: no coastal spot in view, no teaser', async () => {
    const result = await renderMap();
    expect(screen.getByTestId('wf-tide-strip-locked')).toBeInTheDocument();
    await reportBounds(AWAY_BOUNDS);
    await rerenderMap(result);
    expect(screen.queryByTestId('wf-tide-strip')).toBeNull();
  });
});

describe('Map tide lock — no match cue anywhere (§5.1)', () => {
  it('LITE: no chip carries data-tide; PRO\'s do (match and miss)', async () => {
    const lite = await renderMap();
    screen.getAllByTestId('probe-spot').forEach((s) => expect(s).not.toHaveAttribute('data-tide'));
    lite.unmount();

    mockRole = 'ADMIN';
    await renderMap({ tideLocked: false });
    const tides = Object.fromEntries(screen.getAllByTestId('probe-spot')
      .map((s) => [s.getAttribute('data-name'), s.getAttribute('data-tide')]));
    expect(tides.Bamburgh).toBe('match');
    expect(tides.Tynemouth).toBe('miss');
  });

  it('LITE: no pin carries data-tide either (Pins view)', async () => {
    mockIsMobile = true;
    await renderMap();
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay')); });
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-view-pins')); });
    screen.getAllByTestId('probe-pin').forEach((p) => expect(p).not.toHaveAttribute('data-tide'));
  });

  async function openNorthEastPanel() {
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    fireEvent.click(screen.getByTestId('wf-win-more'));
    const row = screen.getAllByTestId('wf-win-panel-row').find((r) => r.getAttribute('data-region') === 'North East');
    expect(row).toBeTruthy();
    fireEvent.click(row);
  }

  it('LITE: the region panel draws no tide glyph; PRO\'s draws one on the matched place', async () => {
    await renderMap();
    await openNorthEastPanel();
    expect(screen.getAllByTestId('wf-reg-panel-row').length).toBeGreaterThan(0);
    expect(screen.queryByTestId('wf-reg-panel-tide')).toBeNull();
  });

  it('PRO (the positive half) draws the region panel glyph from the same fixture', async () => {
    mockRole = 'ADMIN';
    await renderMap({ tideLocked: false });
    await openNorthEastPanel();
    expect(screen.getAllByTestId('wf-reg-panel-tide').length).toBeGreaterThan(0);
  });

  it('LITE: the callout is handed no tide index and no tide-on-light fact (so no TideFitBlock, no next-fit jump)', async () => {
    await renderMap();
    fireEvent.click(screen.getAllByTestId('probe-spot').find((s) => s.getAttribute('data-name') === 'Bamburgh'));
    expect(screen.getByTestId('mock-callout')).toBeInTheDocument();
    expect(calloutProps.last.tideAlignmentIndex).toBeNull();
    expect(calloutProps.last.tideOnLight).toBeNull();
  });

  it('PRO is handed the index and the fact for the same pick', async () => {
    mockRole = 'ADMIN';
    await renderMap({ tideLocked: false });
    fireEvent.click(screen.getAllByTestId('probe-spot').find((s) => s.getAttribute('data-name') === 'Bamburgh'));
    expect(calloutProps.last.tideAlignmentIndex).not.toBeNull();
    expect(calloutProps.last.tideOnLight).not.toBeNull();
  });
});

describe('Map tide lock — the star floor (§5.1 #2, Q11)', () => {
  const sunrise2Star = { autoEventType: 'SUNRISE', locations: makeLocations({ sunriseRating: 2 }) };

  it('a 2-star coastal spot with a served tide fact is drawn for PRO (as a miss) but withheld for LITE like any 2-star spot', async () => {
    mockRole = 'ADMIN';
    const pro = await renderMap({ ...sunrise2Star, tideLocked: false });
    const proNames = screen.getAllByTestId('probe-spot').map((s) => s.getAttribute('data-name'));
    expect(proNames).toContain('Bamburgh');
    expect(proNames).toContain('Tynemouth');
    pro.unmount();

    mockRole = 'LITE_USER';
    await renderMap(sunrise2Star);
    expect(screen.queryAllByTestId('probe-spot')).toHaveLength(0);
  });

  it('a 4-star coastal spot is drawn for both (the lock never withholds a spot the floor passes)', async () => {
    await renderMap();
    const names = screen.getAllByTestId('probe-spot').map((s) => s.getAttribute('data-name'));
    expect(names).toEqual(expect.arrayContaining(['Bamburgh', 'Tynemouth', 'Wastwater']));
  });
});

describe('Map tide lock — the stand-down footer carries no tide term (§5.1 #5)', () => {
  it('LITE: a TIDE_MISALIGNED stand-down leaves the " stood down" count and the " filtered" flag as they were without it', async () => {
    const weatherOnly = await renderMap({ heat: heatProp({ stood: { 3: 'HIGH_CLOUD' } }) });
    const withoutTide = {
      standdown: screen.getByTestId('wf-map-counts-standdown').textContent,
      filtered: screen.queryByTestId('wf-map-counts-filtered') != null,
    };
    weatherOnly.unmount();

    await renderMap({ heat: heatProp({ stood: { 3: 'HIGH_CLOUD', 2: 'TIDE_MISALIGNED' } }) });
    expect(screen.getByTestId('wf-map-counts-standdown').textContent).toBe(withoutTide.standdown);
    expect(screen.getByTestId('wf-map-counts-standdown')).toHaveTextContent('· 1 stood down');
    expect(screen.queryByTestId('wf-map-counts-filtered') != null).toBe(withoutTide.filtered);
  });

  it('PRO shows the same count and flag for the same fixtures (the footer has no tide term for either role)', async () => {
    mockRole = 'ADMIN';
    const pro = await renderMap({ tideLocked: false, heat: heatProp({ stood: { 3: 'HIGH_CLOUD', 2: 'TIDE_MISALIGNED' } }) });
    expect(screen.getByTestId('wf-map-counts-standdown')).toHaveTextContent('· 1 stood down');
    const filtered = screen.queryByTestId('wf-map-counts-filtered') != null;
    pro.unmount();
    mockRole = 'LITE_USER';
    await renderMap({ heat: heatProp({ stood: { 3: 'HIGH_CLOUD', 2: 'TIDE_MISALIGNED' } }) });
    expect(screen.getByTestId('wf-map-counts-standdown')).toHaveTextContent('· 1 stood down');
    expect(screen.queryByTestId('wf-map-counts-filtered') != null).toBe(filtered);
  });
});

describe('Map tide lock — the phone peek (§5.3)', () => {
  beforeEach(() => { mockIsMobile = true; });

  it('LITE: the Tide button shows the state with no dim count (PRO shows "1 dim" for the same fixture)', async () => {
    const lite = await renderMap();
    const liteBtn = screen.getByTestId('wf-map-peek-btn-tide');
    expect(liteBtn).toHaveTextContent(/mid/i);
    expect(liteBtn).not.toHaveTextContent(/dim/i);
    lite.unmount();

    mockRole = 'ADMIN';
    await renderMap({ tideLocked: false });
    expect(screen.getByTestId('wf-map-peek-btn-tide')).toHaveTextContent('1 dim');
  });

  it('LITE: the section draws the kicker, the state and the Pro line — no chart, no height line, no footer', async () => {
    await renderMap();
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-btn-tide')); });
    const section = screen.getByTestId('wf-map-peek-tide');
    expect(within(section).getByTestId('wf-map-peek-tide-key')).toBeInTheDocument();
    expect(within(section).getByTestId('wf-map-peek-tide-phase')).toBeInTheDocument();
    expect(within(section).getByTestId('wf-map-peek-tide-locked'))
      .toHaveTextContent('The tide curve and spot matching are part of Pro.');
    expect(within(section).queryByTestId('wf-tide-strip-chart')).toBeNull();
    expect(within(section).queryByTestId('wf-map-peek-tide-meta')).toBeNull();
    expect(within(section).queryByTestId('wf-tide-strip-footer')).toBeNull();
    expect(within(section).queryByTestId('wf-map-peek-tide-out-of-view')).toBeNull();
    expect(section).not.toHaveTextContent(HEIGHT);
  });

  it('LITE: the open section stays open across renders (the force-close gate reads the same derived value)', async () => {
    const result = await renderMap();
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-btn-tide')); });
    expect(screen.getByTestId('wf-map-peek-tide')).toBeInTheDocument();
    await rerenderMap(result);
    await rerenderMap(result, { mapTideMode: 'off' });
    expect(screen.getByTestId('wf-map-peek-tide')).toBeInTheDocument();
  });

  it('LITE: panning every coastal spot out of view shuts the section with its button, as for PRO', async () => {
    const result = await renderMap();
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-btn-tide')); });
    await reportBounds(AWAY_BOUNDS);
    await rerenderMap(result);
    expect(screen.queryByTestId('wf-map-peek-btn-tide')).toBeNull();
    expect(screen.queryByTestId('wf-map-peek-tide')).toBeNull();
  });

  it('PRO (the positive half): the section draws the chart and the height line', async () => {
    mockRole = 'ADMIN';
    await renderMap({ tideLocked: false });
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-btn-tide')); });
    const section = screen.getByTestId('wf-map-peek-tide');
    expect(within(section).getByTestId('wf-tide-strip-chart')).toBeInTheDocument();
    expect(within(section).queryByTestId('wf-map-peek-tide-locked')).toBeNull();
  });

  async function openLayers() {
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay')); });
  }

  it('LITE: the Layers Tide segment is locked — wrapper greyed, buttons really disabled, pill outside the grey, no Auto hint, save never called', async () => {
    const saveTideMode = vi.fn().mockResolvedValue('saved');
    await renderMap({ saveTideMode });
    await openLayers();
    const wrap = screen.getByTestId('wf-map-peek-tide-mode-wrap');
    expect(wrap).toHaveClass('wf-lens-locked');
    ['auto', 'always', 'off'].forEach((m) => {
      const btn = screen.getByTestId(`wf-map-peek-tide-mode-${m}`);
      expect(btn).toBeDisabled();
      fireEvent.click(btn);
    });
    const pill = screen.getByTestId('wf-map-peek-tide-mode-upsell');
    expect(pill).toHaveClass('wf-lens-pro');
    expect(wrap).not.toContainElement(pill);
    expect(screen.queryByTestId('wf-map-peek-tide-hint')).toBeNull();
    // The saved mode is not in force for LITE, so nothing is drawn pressed or `.on`.
    ['auto', 'always', 'off'].forEach((m) => {
      const btn = screen.getByTestId(`wf-map-peek-tide-mode-${m}`);
      expect(btn).toHaveAttribute('aria-pressed', 'false');
      expect(btn).not.toHaveClass('on');
    });
    expect(saveTideMode).not.toHaveBeenCalled();
  });

  it('PRO (the positive half): the Layers Tide segment is live, hinted, and saves', async () => {
    mockRole = 'ADMIN';
    const saveTideMode = vi.fn().mockResolvedValue('saved');
    await renderMap({ tideLocked: false, saveTideMode });
    await openLayers();
    // PRO's DOM is main's: the segment sits directly in the row, with no lock wrappers.
    expect(screen.queryByTestId('wf-map-peek-tide-mode-wrap')).toBeNull();
    expect(screen.getByTestId('wf-map-peek-tide-mode-auto').closest('.wf-map-peek-lay-tide-row')
      .querySelector('.wf-map-peek-tide-seg-group')).toBeNull();
    expect(screen.getByTestId('wf-map-peek-tide-mode-auto')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByTestId('wf-map-peek-tide-mode-upsell')).toBeNull();
    expect(screen.getByTestId('wf-map-peek-tide-hint')).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-tide-mode-off')); });
    expect(saveTideMode).toHaveBeenCalledWith('off');
  });
});
