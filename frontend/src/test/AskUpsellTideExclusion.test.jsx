/**
 * The Ask upsell and the tide upsell are never open together (docs/engineering/ask-upsell-plan.md §3.2 step 6).
 *
 * <p>On a LITE reader's desktop or tablet Map tab both can be drawn: the Ask field in the tab row and the tide
 * teaser in the map's bottom-left chrome. Opening one is a press outside the other's surface, so each closes
 * the other through its own outside-press rule (the tide popover's `useOutsideDismiss` ignores everything
 * outside the map frame; the Ask upsell's capture-phase `pointerdown` listener closes on anything outside
 * itself and its trigger). This mounts the REAL `MapView` and `MapTideStrip` inside the REAL shell, in both
 * directions, because each half is the other's only guard.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, screen,
} from '@testing-library/react';
import { buildRegionVerdictIndex } from '../utils/mapVerdict.js';
import { tideIndexOf } from './tideFactsFixture.js';
import { resetViewport } from './askViewport.js';
import { renderAskShell } from './askShellHarness.jsx';
import { readyResponse, settings } from './askFixtures.js';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  const svg = (options) => ({ options });
  const latLngBounds = (points) => points;
  return { default: { icon, divIcon, point, svg, latLngBounds }, icon, divIcon, point, svg, latLngBounds };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

const IN_VIEW_BOUNDS = { pad: () => ({ contains: () => true }) };
const mockMapInstance = { getZoom: () => 9, getBounds: () => IN_VIEW_BOUNDS };
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  Pane: ({ children }) => <div>{children}</div>,
  CircleMarker: () => null,
  useMapEvents: () => mockMapInstance,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, offsetWidth: 800, parentElement: document.body }),
    getZoom: () => 9,
    getBounds: () => IN_VIEW_BOUNDS,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    panInside: () => {},
    getSize: () => ({ x: 390, y: 844 }),
    latLngToContainerPoint: () => ({ x: 195, y: 400 }),
  }),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'LITE_USER' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/settingsApi.js', () => ({
  getDriveTimes: vi.fn().mockResolvedValue({}),
  markComingUpSeen: vi.fn().mockResolvedValue(undefined),
}));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../api/almanacApi.js', () => ({
  getAlmanac: vi.fn(() => Promise.resolve({
    builtFor: '2026-10-05', bands: null, counts: null, conditions: [], entries: [],
  })),
  ALMANAC_DAYS: 90,
}));
vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: () => null }));
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapCallout.jsx', () => ({ default: () => null }));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { getAskSettings, getReady } from '../api/askApi.js';

const TODAY = '2026-01-15';
const KEY_SUNSET = `${TODAY}:SUNSET`;
const KEY_SUNRISE = `${TODAY}:SUNRISE`;
const LITE = { isPro: false, isLiteUser: true };

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
  heightAtWindow: '3.84 m',
};
const SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East', regionName: 'North East', coastal: true },
  { id: 2, name: 'Tynemouth', lat: 55.02, lng: -1.42, rid: 'North East', regionName: 'North East', coastal: true },
];
const locations = () => SPOTS.map((s) => ({
  id: s.id,
  name: s.name,
  lat: s.lat,
  lon: s.lng,
  regionName: s.rid,
  bortleClass: 4,
  locationType: ['LANDSCAPE'],
  tideType: ['HIGH'],
  forecastsByDate: new Map([[TODAY, {
    sunrise: { rating: 4, solarEventTime: `${TODAY}T08:24:00` },
    sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00` },
  }]]),
}));
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
const DAYS = [{
  date: TODAY,
  eventSummaries: ['SUNSET', 'SUNRISE'].map((targetType) => ({
    targetType,
    regions: [{
      regionName: 'North East',
      meanRating: 4.2,
      displayVerdict: 'WORTH_IT',
      slots: [slotFor(SPOTS[0], true), slotFor(SPOTS[1], false)],
    }],
  })),
}];
const pointOf = (s) => ({ id: s.id, name: s.name, lat: s.lat, lng: s.lng, rid: s.rid, r: [4] });
const heat = () => ({
  enabled: true,
  hasHome: false,
  spots: SPOTS,
  areaSpots: SPOTS,
  pointsByKey: new Map([[KEY_SUNSET, SPOTS.map(pointOf)], [KEY_SUNRISE, SPOTS.map(pointOf)]]),
  windows: [
    { key: KEY_SUNRISE, date: TODAY, targetType: 'SUNRISE', label: 'This morning', time: '08:24', bestRating: 4, conf: 1, tide: TIDE },
    { key: KEY_SUNSET, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 4, conf: 1, tide: TIDE },
  ],
  areaBounds: [[54.3, -3.4], [55.7, -1.3]],
  catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
});
const topic = (type, label) => ({
  type, label, date: TODAY, eventType: null, regions: ['North East'], locationNames: ['Bamburgh', 'Tynemouth'],
});

const mapPane = (
  <MapView
    locations={locations()}
    date={TODAY}
    autoEventType="SUNSET"
    heat={heat()}
    regionVerdictIndex={buildRegionVerdictIndex(DAYS)}
    tideAlignmentIndex={tideIndexOf(DAYS)}
    tideLocked
    hotTopics={[topic('SPRING_TIDE', 'Spring tide'), topic('KING_TIDE', 'King tide')]}
    forecastDates={[TODAY]}
  />
);

const askField = () => screen.getByTestId('ask-field');
const tideRow = () => screen.getByTestId('wf-tide-strip-locked');
const askPopover = () => screen.queryByTestId('wf-ask-upsell');
const tidePopover = () => screen.queryByTestId('wf-tide-upsell');
const click = (node) => act(async () => { fireEvent.click(node); });
/** A real press is a pointer press then a click; both documents' outside-press rules listen to the press. */
const tap = (node) => act(async () => {
  fireEvent.pointerDown(node);
  fireEvent.mouseDown(node);
  fireEvent.click(node);
});

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
  localStorage.clear();
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  localStorage.clear();
  resetViewport();
});

describe.each([[800, 'the tablet'], [1280, 'the desktop']])('on %i px (%s), a LITE reader\'s Map tab', (width) => {
  async function openMapTab() {
    renderAskShell({ width, ctx: LITE, props: { mapPane } });
    await act(async () => { fireEvent.click(await screen.findByRole('tab', { name: 'Map' })); });
    await screen.findByTestId('wf-tide-strip-locked');
  }

  it('draws both doors', async () => {
    await openMapTab();

    expect(askField()).toHaveAttribute('aria-haspopup', 'dialog');
    expect(tideRow()).toHaveAttribute('aria-haspopup', 'dialog');
  });

  it('the tide upsell open, then the Ask upsell: the tide one closes, never both', async () => {
    await openMapTab();
    await click(tideRow());
    expect(tidePopover()).toBeInTheDocument();

    await tap(askField());

    expect(askPopover()).toBeInTheDocument();
    expect(tidePopover()).toBeNull();
  });

  it('the Ask upsell open, then the tide upsell: the Ask one closes, never both', async () => {
    await openMapTab();
    await tap(askField());
    expect(askPopover()).toBeInTheDocument();

    await tap(tideRow());

    expect(tidePopover()).toBeInTheDocument();
    expect(askPopover()).toBeNull();
  });
});
