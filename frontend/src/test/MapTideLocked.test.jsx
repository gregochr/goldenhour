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
 * <p>`MapLabels` is a probe that surfaces `spot.tideTier` as `data-tide` straight off
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
import userEvent from '@testing-library/user-event';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';
import { tideIndexOf } from './tideFactsFixture.js';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  const svg = (options) => ({ options });
  const latLngBounds = (points) => points;
  return { default: { icon, divIcon, point, svg, latLngBounds }, icon, divIcon, point, svg, latLngBounds };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

let mapEventHandlers = [];
const IN_VIEW_BOUNDS = { pad: () => ({ contains: () => true }) };
const AWAY_BOUNDS = { pad: () => ({ contains: () => false }) };
let mockMapBounds = IN_VIEW_BOUNDS;
const mockFlyTo = vi.fn();
const mockFitBounds = vi.fn();
const mockMapInstance = { getZoom: () => 9, getBounds: () => mockMapBounds };
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  Pane: ({ children }) => <div>{children}</div>,
  CircleMarker: () => null,
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
    flyTo: (...args) => mockFlyTo(...args),
    fitBounds: (...args) => mockFitBounds(...args),
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
    // F2: the row IS the one button (it opens the upsell); nothing else in the strip is.
    expect(strip.querySelectorAll('button')).toHaveLength(1);
    expect(strip.querySelector('button')).toBe(within(strip).getByTestId('wf-tide-strip-locked'));
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

  it('LITE: the section draws the kicker, the state and the upsell — no chart, no height line, no footer', async () => {
    await renderMap();
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-btn-tide')); });
    const section = screen.getByTestId('wf-map-peek-tide');
    expect(within(section).getByTestId('wf-map-peek-tide-key')).toBeInTheDocument();
    expect(within(section).getByTestId('wf-map-peek-tide-phase')).toBeInTheDocument();
    // F2: the F1 one-liner is replaced by the upsell's content, inline (asserted in full below).
    expect(within(section).getByTestId('wf-map-peek-tide-locked')).toBeInTheDocument();
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

// ──────────────────────────────────────────────────────────────────────────────────────────────────
// Phase F2 — the upsell (docs/engineering/tide-pro-gate-plan.md §6)
// ──────────────────────────────────────────────────────────────────────────────────────────────────

const topicOf = (type, extra = {}) => ({
  type,
  label: type === 'KING_TIDE' ? 'King tide' : 'Spring tide',
  date: TODAY,
  eventType: null,
  regions: ['North East'],
  locationNames: ['Bamburgh', 'Tynemouth'],
  ...extra,
});
const TIDE_TOPICS = [topicOf('SPRING_TIDE'), topicOf('KING_TIDE'), {
  ...topicOf('DUST'), label: 'Dust', eventType: 'SUNSET',
}];

function upsellElement(props = {}) {
  return mapElement({ hotTopics: TIDE_TOPICS, forecastDates: [TODAY], ...props });
}

async function renderUpsell(props = {}) {
  let result;
  await act(async () => {
    result = render(upsellElement(props));
  });
  return result;
}

const row = () => screen.getByTestId('wf-tide-strip-locked');
const dialog = () => screen.queryByTestId('wf-tide-upsell');
const pane = () => screen.getByTestId('wf-map-pane');
const chipOf = (type) => screen.getByTestId(`wf-topic-chip-${type}`);
const click = (node) => act(async () => { fireEvent.click(node); });
const latestHandler = (name) => [...mapEventHandlers].reverse().find((h) => typeof h[name] === 'function');

describe('Map tide upsell — the popover (§6.1)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
  });
  afterEach(() => {
    vi.useRealTimers();
    mockFlyTo.mockReset();
    document.querySelectorAll('[data-test-foreign]').forEach((n) => n.remove());
  });

  it('the teaser row is a button that names a dialog and starts collapsed; no popover is drawn at rest', async () => {
    await renderUpsell();
    expect(row().tagName).toBe('BUTTON');
    expect(row()).toHaveAttribute('type', 'button');
    expect(row()).toHaveAttribute('aria-haspopup', 'dialog');
    expect(row()).toHaveAttribute('aria-expanded', 'false');
    expect(dialog()).toBeNull();
    // The root keeps its load-bearing identity.
    expect(screen.getByTestId('wf-tide-strip')).toHaveClass('wf-map-tide-strip');
    expect(screen.getByTestId('wf-tide-strip')).toContainElement(row());
  });

  it('opens by click: a non-modal, inline dialog named by its heading, with the pill, words and static preview', async () => {
    await renderUpsell();
    row().focus();
    await click(row());
    const pop = dialog();
    expect(pop).toBeInTheDocument();
    expect(pop).toHaveAttribute('role', 'dialog');
    expect(pop).not.toHaveAttribute('aria-modal');
    const heading = within(pop).getByTestId('wf-tide-upsell-heading');
    expect(pop).toHaveAttribute('aria-labelledby', heading.id);
    expect(pop).toHaveAccessibleName('Match spots to the tide at your light');
    expect(within(pop).getByTestId('wf-tide-upsell-pill')).toHaveClass('wf-lens-pro');
    expect(within(pop).getByTestId('wf-tide-upsell-body')).toHaveTextContent(
      'See the tide curve for each sunrise and sunset, and dim coastal spots that need different water.',
    );
    const preview = within(pop).getByTestId('wf-tide-upsell-preview');
    expect(preview).toHaveAttribute('aria-hidden', 'true');
    expect(preview).toHaveAttribute('height', '70');
    expect(preview.innerHTML).toContain('var(--color-verdict-marginal)');
    expect(within(pop).getByRole('button', { name: 'Not now' })).toBeInTheDocument();
    // Inline in the pane, in the bottom-left chrome and above the teaser: never portalled.
    expect(pane()).toContainElement(pop);
    expect(screen.getByTestId('wf-map-chrome-bl')).toContainElement(pop);
    expect(pop.compareDocumentPosition(screen.getByTestId('wf-tide-strip')) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
    // The row states and controls it; focus moved to the heading.
    expect(row()).toHaveAttribute('aria-expanded', 'true');
    expect(row()).toHaveAttribute('aria-controls', pop.id);
    expect(document.activeElement).toBe(heading);
  });

  it('opens by Enter and by Space on the row', async () => {
    const user = userEvent.setup({ advanceTimers: () => {} });
    await renderUpsell();
    row().focus();
    await act(async () => { await user.keyboard('{Enter}'); });
    expect(dialog()).toBeInTheDocument();
    await act(async () => { await user.keyboard('{Escape}'); });
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(row());
    await act(async () => { await user.keyboard(' '); });
    expect(dialog()).toBeInTheDocument();
  });

  it('has no link and no upgrade word anywhere in the popover, and the preview is identical for any tide', async () => {
    const first = await renderUpsell();
    await click(row());
    const pop = dialog();
    expect(pop.querySelector('a[href]')).toBeNull();
    expect(pop.textContent).not.toMatch(/upgrade/i);
    expect(pane().querySelector('a[href]')).toBeNull();
    const a = within(pop).getByTestId('wf-tide-upsell-preview').outerHTML;
    first.unmount();

    const other = heatProp();
    other.windows = other.windows.map((w) => ({
      ...w, tide: { ...TIDE, state: 'LOW', direction: 'RISING', curve: [1, 0.5, 0], heightAtWindow: '0.2 m' },
    }));
    await renderUpsell({ heat: other });
    await click(row());
    expect(within(dialog()).getByTestId('wf-tide-upsell-preview').outerHTML).toBe(a);
    expect(dialog()).not.toHaveTextContent(HEIGHT);
  });

  it('"Not now" closes it and returns focus to the row', async () => {
    await renderUpsell();
    row().focus();
    await click(row());
    await click(within(dialog()).getByTestId('wf-tide-upsell-dismiss'));
    expect(dialog()).toBeNull();
    expect(row()).toHaveAttribute('aria-expanded', 'false');
    expect(document.activeElement).toBe(row());
  });

  it('a second press on the row closes it', async () => {
    await renderUpsell();
    await click(row());
    await click(row());
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(row());
  });

  it('Escape inside the popover closes it and returns focus to the row', async () => {
    await renderUpsell();
    await click(row());
    await act(async () => { fireEvent.keyDown(document.activeElement, { key: 'Escape' }); });
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(row());
  });

  it('the pane-level Escape rung also closes it and restores focus to the opener', async () => {
    await renderUpsell();
    await click(row());
    // Focus has left the popover (the reader is on the map): only the pane rung can hear the key.
    pane().focus();
    await act(async () => { fireEvent.keyDown(pane(), { key: 'Escape' }); });
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(row());
  });

  it('Escape under a foreign modal does nothing, from the popover and from the pane', async () => {
    await renderUpsell();
    await click(row());
    const foreign = document.createElement('div');
    foreign.setAttribute('role', 'dialog');
    foreign.setAttribute('aria-modal', 'true');
    foreign.setAttribute('data-test-foreign', '1');
    document.body.appendChild(foreign);
    await act(async () => {
      fireEvent.keyDown(within(dialog()).getByTestId('wf-tide-upsell-heading'), { key: 'Escape' });
    });
    expect(dialog()).toBeInTheDocument();
    await act(async () => { fireEvent.keyDown(pane(), { key: 'Escape' }); });
    expect(dialog()).toBeInTheDocument();
  });

  it('an outside press (the masthead) closes it; a press inside the map frame does not', async () => {
    await renderUpsell();
    await click(row());
    await act(async () => { fireEvent.mouseDown(screen.getAllByTestId('map-container')[0]); });
    expect(dialog()).toBeInTheDocument();
    await act(async () => { fireEvent.mouseDown(document.body); });
    expect(dialog()).toBeNull();
  });

  it('a plain click on the map closes it and returns focus', async () => {
    await renderUpsell();
    row().focus();
    await click(row());
    await act(async () => { latestHandler('click').click(); });
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(row());
  });

  it('a hand drag closes it; a hand zoom closes it', async () => {
    await renderUpsell();
    await click(row());
    await act(async () => { latestHandler('dragstart').dragstart(); });
    expect(dialog()).toBeNull();
    await click(row());
    expect(dialog()).toBeInTheDocument();
    await act(async () => { latestHandler('zoomstart').zoomstart(); });
    expect(dialog()).toBeNull();
  });

  it('a programmatic fit (a handoff framing pins) does not close it, though Leaflet fires zoomstart', async () => {
    const result = await renderUpsell();
    await click(row());
    mockFitBounds.mockImplementation(() => latestHandler('zoomstart').zoomstart());
    const handoff = {
      source: 'plan', eventType: 'SUNSET', date: TODAY, region: null, nonce: 7,
      focusPoints: [[55.6, -1.7], [55.0, -1.4]],
    };
    await rerenderMap(result, { hotTopics: TIDE_TOPICS, forecastDates: [TODAY], planHandoff: handoff });
    expect(mockFitBounds).toHaveBeenCalled();
    expect(dialog()).toBeInTheDocument();
  });

  it('closes when the teaser unmounts (no coastal spot in view) and moves focus to the pane', async () => {
    const result = await renderUpsell();
    await click(row());
    expect(document.activeElement).toBe(within(dialog()).getByTestId('wf-tide-upsell-heading'));
    await reportBounds(AWAY_BOUNDS);
    await rerenderMap(result, { hotTopics: TIDE_TOPICS, forecastDates: [TODAY] });
    expect(screen.queryByTestId('wf-tide-strip')).toBeNull();
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(pane());
  });

  it('is exclusive with the filters popover, both ways', async () => {
    await renderUpsell();
    await click(row());
    await click(screen.getByTestId('wf-filters-chip'));
    expect(dialog()).toBeNull();
    await click(row());
    expect(dialog()).toBeInTheDocument();
    expect(screen.queryByTestId('wf-filters-panel')).toBeNull();
  });

  it('PRO\'s teaser area is unchanged: the strip, no upsell, no popover', async () => {
    mockRole = 'ADMIN';
    await renderUpsell({ tideLocked: false });
    expect(screen.getByTestId('wf-tide-strip-toggle')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-tide-strip-locked')).toBeNull();
    expect(dialog()).toBeNull();
    expect(screen.getByTestId('wf-tide-strip').querySelector('[aria-haspopup]')).toBeNull();
  });
});

describe('Map tide upsell — the tide topic doors (§6.2)', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
  });
  afterEach(() => { vi.useRealTimers(); });

  const noTopicSelected = () => {
    expect(screen.getByTestId('map-status')).not.toHaveTextContent(/selected/i);
    expect(pane()).not.toHaveAttribute('data-topic-selected');
    expect(document.querySelectorAll('[data-topic-type][aria-pressed="true"], .wf-tl-n[aria-pressed="true"]'))
      .toHaveLength(0);
  };

  it.each(['SPRING_TIDE', 'KING_TIDE'])('LITE: the %s chip is a popover door — no aria-pressed, haspopup and expanded', async (type) => {
    await renderUpsell();
    const c = chipOf(type);
    expect(c).not.toHaveAttribute('aria-pressed');
    expect(c).toHaveAttribute('aria-haspopup', 'dialog');
    expect(c).toHaveAttribute('aria-expanded', 'false');
    c.focus();
    await click(c);
    expect(dialog()).toBeInTheDocument();
    expect(c).toHaveAttribute('aria-expanded', 'true');
    noTopicSelected();
    // Closing returns focus to the chip.
    await click(within(dialog()).getByTestId('wf-tide-upsell-dismiss'));
    expect(document.activeElement).toBe(c);
  });

  it('LITE: a non-tide chip stays an ordinary toggle', async () => {
    await renderUpsell();
    const dust = chipOf('DUST');
    expect(dust).toHaveAttribute('aria-pressed', 'false');
    expect(dust).not.toHaveAttribute('aria-haspopup');
    await click(dust);
    expect(dust).toHaveAttribute('aria-pressed', 'true');
    expect(dialog()).toBeNull();
  });

  it('PRO (the positive half): the chips still toggle rings', async () => {
    mockRole = 'ADMIN';
    await renderUpsell({ tideLocked: false });
    const c = chipOf('KING_TIDE');
    expect(c).toHaveAttribute('aria-pressed', 'false');
    expect(c).not.toHaveAttribute('aria-haspopup');
    await click(c);
    expect(c).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('map-status')).toHaveTextContent(/King tide selected/);
    expect(dialog()).toBeNull();
    await click(c);
    expect(c).toHaveAttribute('aria-pressed', 'false');
  });

  async function openStrip() {
    // The four-day strip is held closed under the tide teaser: its header opens it.
    await click(screen.getByTestId('wf-topic-strip-toggle'));
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
  }

  it.each([['SPRING_TIDE', 'Spring tide'], ['KING_TIDE', 'King tide']])('LITE: the desktop strip row %s says it is a Pro map view, opens the upsell, and selects nothing', async (type, name) => {
    await renderUpsell();
    await openStrip();
    const label = screen.getByTestId(`wf-topic-strip-label-${type}`);
    expect(label).toHaveAccessibleName(`${name}, a Pro map view`);
    expect(label).toHaveAttribute('aria-haspopup', 'dialog');
    expect(label).not.toHaveAttribute('aria-pressed');
    const strip = screen.getByTestId('wf-topic-strip');
    await click(label);
    expect(dialog()).toBeInTheDocument();
    noTopicSelected();
    // Focus was parked on the strip root and returns there.
    await click(within(dialog()).getByTestId('wf-tide-upsell-dismiss'));
    expect(document.activeElement).toBe(strip);
  });

  it('LITE: a strip bar of a tide row opens the upsell too', async () => {
    await renderUpsell();
    await openStrip();
    const bar = document.querySelector('[data-testid^="wf-topic-bar-KING_TIDE-"]');
    expect(bar).toHaveAttribute('aria-haspopup', 'dialog');
    expect(bar.getAttribute('aria-label')).toMatch(/^King tide, .*, a Pro map view$/);
    await click(bar);
    expect(dialog()).toBeInTheDocument();
    noTopicSelected();
  });

  it('LITE: the Coming up / Plan handoff lands with NO topic selected and does not open the upsell', async () => {
    const handoff = (type, nonce) => ({
      source: 'plan', eventType: 'SUNSET', date: TODAY, region: null, nonce, topicType: type,
    });
    const king = await renderUpsell({ planHandoff: handoff('KING_TIDE', 1) });
    expect(dialog()).toBeNull();
    noTopicSelected();
    king.unmount();
    await renderUpsell({ planHandoff: handoff('SPRING_TIDE', 2) });
    expect(dialog()).toBeNull();
    noTopicSelected();
  });

  it('PRO (the positive half): the same handoff selects the tide topic', async () => {
    mockRole = 'ADMIN';
    await renderUpsell({
      tideLocked: false,
      planHandoff: {
        source: 'plan', eventType: 'SUNSET', date: TODAY, region: null, nonce: 3, topicType: 'KING_TIDE',
      },
    });
    expect(chipOf('KING_TIDE')).toHaveAttribute('aria-pressed', 'true');
  });

  it('LITE can never select a tide topic: Escape finds none to clear after every door', async () => {
    await renderUpsell({
      planHandoff: {
        source: 'plan', eventType: 'SUNSET', date: TODAY, region: null, nonce: 4, topicType: 'SPRING_TIDE',
      },
    });
    await click(chipOf('SPRING_TIDE'));
    await click(chipOf('KING_TIDE'));
    await act(async () => { fireEvent.keyDown(pane(), { key: 'Escape' }); });
    await act(async () => { fireEvent.keyDown(pane(), { key: 'Escape' }); });
    expect(screen.getByTestId('map-status')).not.toHaveTextContent(/King tide|Spring tide/);
    expect(screen.getByTestId('map-status')).not.toHaveTextContent(/cleared/i);
  });
});

describe('Map tide upsell — the phone (§6.2, §6.3)', () => {
  beforeEach(() => {
    mockIsMobile = true;
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
  });
  afterEach(() => { vi.useRealTimers(); });

  it('the peek Tide section draws the upsell content inline: pill, heading, body, preview, and no popover or "Not now"', async () => {
    await renderUpsell();
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-btn-tide')); });
    const section = screen.getByTestId('wf-map-peek-tide');
    expect(within(section).getByTestId('wf-map-peek-tide-upsell-pill')).toHaveClass('wf-lens-pro');
    expect(within(section).getByTestId('wf-map-peek-tide-upsell-heading'))
      .toHaveTextContent('Match spots to the tide at your light');
    expect(within(section).getByTestId('wf-map-peek-tide-upsell-body'))
      .toHaveTextContent('See the tide curve for each sunrise and sunset, and dim coastal spots that need different water.');
    expect(within(section).getByTestId('wf-tide-upsell-preview')).toHaveAttribute('height', '70');
    expect(within(section).queryByText('Not now')).toBeNull();
    expect(section.querySelector('a[href]')).toBeNull();
    expect(section.textContent).not.toMatch(/upgrade/i);
    expect(dialog()).toBeNull();
    expect(section).not.toHaveTextContent(HEIGHT);
  });

  it('the inline preview is the popover\'s preview, byte for byte', async () => {
    mockIsMobile = false;
    const desk = await renderUpsell();
    await click(row());
    const a = within(dialog()).getByTestId('wf-tide-upsell-preview').outerHTML;
    desk.unmount();
    mockIsMobile = true;
    await renderUpsell();
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-btn-tide')); });
    expect(within(screen.getByTestId('wf-map-peek-tide')).getByTestId('wf-tide-upsell-preview').outerHTML).toBe(a);
  });

  it('LITE: a tide chip opens the peek Tide section explicitly (a second press leaves it open); nothing is selected', async () => {
    await renderUpsell();
    expect(screen.queryByTestId('wf-map-peek-tide')).toBeNull();
    await click(chipOf('KING_TIDE'));
    expect(screen.getByTestId('wf-map-peek-tide')).toBeInTheDocument();
    await click(chipOf('KING_TIDE'));
    expect(screen.getByTestId('wf-map-peek-tide')).toBeInTheDocument();
    expect(screen.getByTestId('map-status')).not.toHaveTextContent(/selected/i);
    expect(dialog()).toBeNull();
  });

  it('LITE: a tide row in the sheet strip opens the Tide section and does not collapse the sheet back to peek', async () => {
    await renderUpsell();
    await click(screen.getByTestId('wf-map-peek-btn-win'));
    await click(screen.getByTestId('wf-topic-strip-toggle'));
    const label = await screen.findByTestId('wf-topic-strip-label-SPRING_TIDE');
    expect(label).toHaveAccessibleName('Spring tide, a Pro map view');
    await click(label);
    expect(screen.getByTestId('wf-map-peek-tide')).toBeInTheDocument();
    expect(screen.getByTestId('wf-map-peek-tide-upsell-heading')).toBeInTheDocument();
    expect(screen.getByTestId('map-status')).not.toHaveTextContent(/selected/i);
  });

  it('LITE: with the Tide section not on offer (no coastal spot in view) a tide chip does nothing', async () => {
    const result = await renderUpsell();
    await reportBounds(AWAY_BOUNDS);
    await rerenderMap(result, { hotTopics: TIDE_TOPICS, forecastDates: [TODAY] });
    expect(screen.queryByTestId('wf-map-peek-btn-tide')).toBeNull();
    await click(chipOf('SPRING_TIDE'));
    expect(screen.queryByTestId('wf-map-peek-tide')).toBeNull();
    expect(screen.getByTestId('map-status')).not.toHaveTextContent(/selected/i);
  });

  it('PRO (the positive half): the section is the chart, with none of the upsell', async () => {
    mockRole = 'ADMIN';
    await renderUpsell({ tideLocked: false });
    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-btn-tide')); });
    expect(screen.queryByTestId('wf-tide-upsell-preview')).toBeNull();
    expect(screen.queryByTestId('wf-map-peek-tide-upsell-pill')).toBeNull();
  });
});

// ──────────────────────────────────────────────────────────────────────────────────────────────────
// F2 review fixes
// ──────────────────────────────────────────────────────────────────────────────────────────────────

describe('Map tide upsell — review fixes', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
  });
  afterEach(() => {
    vi.useRealTimers();
    mockFlyTo.mockReset();
    mockFitBounds.mockReset();
  });

  it('(1) selecting a place closes the upsell; opening it clears the selection; a ground click closes only the upsell', async () => {
    await renderUpsell();
    await click(row());
    expect(dialog()).toBeInTheDocument();
    await click(screen.getAllByTestId('probe-spot').find((s) => s.getAttribute('data-name') === 'Bamburgh'));
    expect(dialog()).toBeNull();
    expect(screen.getByTestId('mock-callout')).toBeInTheDocument();
    // Opening the upsell puts the callout away (one layer at a time).
    await click(row());
    expect(dialog()).toBeInTheDocument();
    expect(screen.queryByTestId('mock-callout')).toBeNull();
  });

  it('(1) a ground click with the upsell open closes ONLY the upsell', async () => {
    await renderUpsell();
    await click(row());
    await act(async () => { latestHandler('click').click(); });
    expect(dialog()).toBeNull();
    // The next ground click is an ordinary one again (it would deselect; nothing is selected, nothing breaks).
    await act(async () => { latestHandler('click').click(); });
    expect(dialog()).toBeNull();
  });

  it('(2) focus is not stolen when the reader has moved on: a map click leaves it on the window stepper', async () => {
    await renderUpsell();
    row().focus();
    await click(row());
    const stepper = screen.getByTestId('wf-win-prev');
    stepper.focus();
    await act(async () => { latestHandler('click').click(); });
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(stepper);
  });

  it('(2) a different door toggling it closed takes the focus, not the first opener', async () => {
    await renderUpsell();
    await click(row());
    const king = chipOf('KING_TIDE');
    king.focus();
    await click(king);
    expect(dialog()).toBeNull();
    expect(document.activeElement).toBe(king);
  });

  it('(3) Shift+Tab out of the popover closes it without moving focus', async () => {
    const user = userEvent.setup({ advanceTimers: () => {} });
    await renderUpsell();
    await click(row());
    expect(document.activeElement).toBe(within(dialog()).getByTestId('wf-tide-upsell-heading'));
    await act(async () => { await user.tab({ shift: true }); });
    expect(dialog()).toBeNull();
    expect(document.activeElement).not.toBe(pane());
    expect(document.activeElement).not.toBe(document.body);
  });

  it('(3) focus moving to a tide door (a chip) does not close it by itself', async () => {
    await renderUpsell();
    await click(row());
    await act(async () => { chipOf('KING_TIDE').focus(); });
    expect(dialog()).toBeInTheDocument();
  });

  it('(4) a window change closes a chip-opened upsell', async () => {
    await renderUpsell();
    await click(chipOf('KING_TIDE'));
    expect(dialog()).toBeInTheDocument();
    await click(screen.getByTestId('wf-win-prev'));
    expect(dialog()).toBeNull();
  });

  it('(6) a late planning-area fit (HeatBoundsController) is a programmatic move: it does not close the upsell', async () => {
    const result = await renderUpsell();
    await click(row());
    mockFitBounds.mockImplementation(() => latestHandler('zoomstart').zoomstart());
    const moved = heatProp();
    moved.areaBounds = [[54.0, -3.9], [55.9, -1.0]];
    await rerenderMap(result, { hotTopics: TIDE_TOPICS, forecastDates: [TODAY], heat: moved });
    expect(mockFitBounds).toHaveBeenCalled();
    expect(dialog()).toBeInTheDocument();
  });

  it('(7) desktop: every open locked door points at the popover with aria-controls', async () => {
    await renderUpsell();
    await click(row());
    const id = dialog().id;
    expect(chipOf('SPRING_TIDE')).toHaveAttribute('aria-controls', id);
    expect(chipOf('KING_TIDE')).toHaveAttribute('aria-controls', id);
    expect(row()).toHaveAttribute('aria-controls', id);
    expect(dialog()).toHaveAttribute('aria-describedby', within(dialog()).getByTestId('wf-tide-upsell-body').id);
  });

  it('(8) the teaser button has a data-driven accessible name', async () => {
    await renderUpsell();
    expect(row()).toHaveAccessibleName(/^Tide at this light: .*falling.*\. Pro$/i);
  });

  it('(11) a later-day King bar as LITE opens the upsell and moves neither the window nor the date', async () => {
    const D2 = '2026-01-16';
    const onSelectDate = vi.fn();
    const heat = heatProp();
    heat.windows = [...heat.windows, {
      key: `${D2}:SUNSET`, date: D2, targetType: 'SUNSET', label: 'Friday sunset', time: '16:14', bestRating: 4, conf: 1, tide: TIDE,
    }];
    heat.pointsByKey = new Map([...heat.pointsByKey, [`${D2}:SUNSET`, SPOTS.map(pointOf)]]);
    const topics = [...TIDE_TOPICS, topicOf('KING_TIDE', { date: D2, eventType: null })];
    await renderUpsell({
      heat, hotTopics: topics, forecastDates: [TODAY, D2], onSelectDate,
    });
    await click(screen.getByTestId('wf-topic-strip-toggle'));
    const pillBefore = screen.getByTestId('wf-win-pill').textContent;
    const bars = [...document.querySelectorAll('[data-testid^="wf-topic-bar-KING_TIDE-"]')];
    expect(bars.length).toBeGreaterThan(0);
    await click(bars[bars.length - 1]);
    expect(dialog()).toBeInTheDocument();
    expect(onSelectDate).not.toHaveBeenCalled();
    expect(screen.getByTestId('wf-win-pill').textContent).toBe(pillBefore);
  });

  describe('phone door semantics (7)', () => {
    beforeEach(() => { mockIsMobile = true; });

    it('a locked chip is not a dialog door: no haspopup, expanded follows the Tide section', async () => {
      await renderUpsell();
      const c = chipOf('KING_TIDE');
      expect(c).not.toHaveAttribute('aria-haspopup');
      expect(c).toHaveAttribute('aria-expanded', 'false');
      expect(c).not.toHaveAttribute('aria-disabled');
      await click(c);
      expect(chipOf('KING_TIDE')).toHaveAttribute('aria-expanded', 'true');
    });

    it('with no coastal spot in view the door is aria-disabled and says so', async () => {
      const result = await renderUpsell();
      await reportBounds(AWAY_BOUNDS);
      await rerenderMap(result, { hotTopics: TIDE_TOPICS, forecastDates: [TODAY] });
      const c = chipOf('SPRING_TIDE');
      expect(c).toHaveAttribute('aria-disabled', 'true');
      expect(c).toHaveAccessibleName(/, no coastal spot in view$/);
    });

    it('a sheet-strip row has no haspopup either', async () => {
      await renderUpsell();
      await click(screen.getByTestId('wf-map-peek-btn-win'));
      await click(screen.getByTestId('wf-topic-strip-toggle'));
      const label = await screen.findByTestId('wf-topic-strip-label-SPRING_TIDE');
      expect(label).not.toHaveAttribute('aria-haspopup');
      expect(label).toHaveAttribute('aria-expanded', 'false');
    });
  });
});
