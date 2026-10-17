/**
 * The phone Map's "Open in Plan ›" as ONE chain (F5, `docs/engineering/ask-photocast-plan.md` §2.8, and F4's
 * "For F5 (5)"): the real `WindowFirstShell` hosting the real Map pane and peek sheet, with a host that does
 * what `App` does — turns the pane's `onOpenLocationSheet` into the shell's `locationSheetHandoff`. The pieces
 * are each tested alone (`AskPeekChain` the payload, `WindowFirstShellLocationSheetHandoff` the shell's half);
 * this proves they meet: the tab moves, the sheet opens at the pick's window, the pane goes hidden so the Ask
 * section closes and the Map comes back minimised, the conversation survives, and focus lands somewhere real
 * when the sheet closes. Only Leaflet, the heat canvas and the network are replaced.
 */
import React, { useEffect, useRef, useState } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen, waitFor, within,
} from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  const STOPPED_EVENTS = ['mousedown', 'touchstart', 'dblclick', 'contextmenu'];
  const DomEvent = {
    disableClickPropagation: (el) => {
      for (const type of STOPPED_EVENTS) el.addEventListener(type, (e) => e.stopPropagation());
      el._leaflet_disable_click = true;
    },
    disableScrollPropagation: () => {},
  };
  const DomUtil = { setPosition: () => {} };
  const latLngBounds = (points) => ({ points });
  const L = {
    icon, divIcon, point, DomEvent, DomUtil, latLngBounds,
  };
  return { default: L, ...L };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

let fakeMap;

function makeMap() {
  const handlers = new Map();
  const container = document.createElement('div');
  document.body.appendChild(container);
  Object.defineProperty(container, 'offsetWidth', { value: 800, configurable: true });
  const panes = {};
  return {
    flyToBoundsCalls: [],
    flyToCalls: [],
    zoom: 9,
    container,
    getZoom() { return this.zoom; },
    getSize: () => ({ x: 800, y: 500 }),
    getContainer: () => container,
    getCenter: () => ({ lat: 55, lng: -2 }),
    getBounds: () => ({ contains: () => true }),
    latLngToContainerPoint: ([lat, lng]) => ({ x: (lng + 3) * 100, y: (56 - lat) * 100 }),
    containerPointToLayerPoint: (p) => ({ x: -40 + p[0], y: -25 + p[1] }),
    project: ([lat, lng]) => ({ add: ([dx, dy]) => ({ lat: lat + dy, lng: lng + dx }) }),
    unproject: (p) => p,
    createPane: (name) => {
      const el = document.createElement('div');
      panes[name] = el;
      container.appendChild(el);
      return el;
    },
    getPane: (name) => panes[name] || null,
    on(events, fn) { for (const e of events.split(' ')) handlers.set(e, [...(handlers.get(e) || []), fn]); },
    off(events, fn) {
      for (const e of events.split(' ')) handlers.set(e, (handlers.get(e) || []).filter((h) => h !== fn));
    },
    once: () => {},
    eachLayer: () => {},
    fitBounds: () => {},
    setView: () => {},
    invalidateSize: () => {},
    panInside: () => {},
    flyToBounds(bounds, options) { this.flyToBoundsCalls.push([bounds, options]); },
    flyTo(latlng, zoom) { this.flyToCalls.push([latlng, zoom]); },
  };
}

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: (handlers) => {
    React.useEffect(() => {
      const target = fakeMap.container;
      const entries = Object.entries(handlers || {}).map(([eventName, handler]) => {
        if (eventName !== 'click') return [eventName, handler];
        const gated = (e) => {
          let el = e.target;
          while (el && el !== target) {
            if (el._leaflet_disable_click) return;
            el = el.parentNode;
          }
          handler(e);
        };
        return [eventName, gated];
      });
      for (const [eventName, handler] of entries) target.addEventListener(eventName, handler);
      return () => {
        for (const [eventName, handler] of entries) target.removeEventListener(eventName, handler);
      };
    });
    return fakeMap;
  },
  useMap: () => fakeMap,
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: ({ dim }) => <div data-testid="map-heat-layer" data-dim={dim} />,
}));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
let mockIsMobile = true;
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
vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
import { ask, getAskSettings, getReady } from '../api/askApi.js';

let briefingValue = null;
vi.mock('../context/WindowFirstBriefingContext.jsx', () => ({
  useWindowFirstBriefing: () => briefingValue,
}));

import WindowFirstMapPane from '../components/WindowFirstMapPane.jsx';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import { shellCtx } from './askShellHarness.jsx';
import { AskProvider, useAsk } from '../context/AskContext.jsx';
import {
  briefing, NOW, ownResponse, pick, readyResponse, SALTBURN, settings, WHITBY,
} from './askFixtures.js';


const TODAY = '2026-10-05';
const TOMORROW = '2026-10-06';
const REGION = 'North York Moors & Coast';

const loc = (id, name, lat, lon, forecasts) => ({
  id, name, lat, lon, regionName: REGION, bortleClass: 4, locationType: ['SEASCAPE'],
  forecastsByDate: new Map(Object.entries(forecasts)),
});
const sun = (date, kind, rating) => ({
  [kind]: {
    rating,
    solarEventTime: `${date}T${kind === 'sunset' ? '17:41' : '05:58'}:00`,
    fierySkyPotential: 70,
    goldenHourPotential: 60,
  },
});
const LOCATIONS = [
  loc(WHITBY, 'Whitby', 54.49, -0.61, {
    [TODAY]: sun(TODAY, 'sunset', 5), [TOMORROW]: sun(TOMORROW, 'sunrise', 4),
  }),
  loc(SALTBURN, 'Saltburn', 54.58, -0.97, { [TODAY]: sun(TODAY, 'sunset', 4) }),
];
const HEAT_SPOTS = LOCATIONS.map((l) => ({
  id: l.id, name: l.name, lat: l.lat, lng: l.lon, regionName: l.regionName, rid: l.regionName, bortleClass: 4, scores: [5],
}));
const card = (date, targetType, over = {}) => ({
  key: `${date}:${targetType}`,
  date,
  targetType,
  dow: date === TODAY ? 'Mon' : 'Tue',
  sunrise: targetType === 'SUNRISE',
  label: date === TODAY ? 'Tonight sunset' : 'Tomorrow sunrise',
  time: targetType === 'SUNSET' ? '18:41' : '06:58',
  verdict: 'WORTH_IT',
  verdictLabel: 'Worth it',
  bestRating: 5,
  confidence: 'high',
  away: false,
  pool: [],
  badges: [],
  ...over,
});
const CARDS = [card(TODAY, 'SUNSET'), card(TOMORROW, 'SUNRISE')];

/** The shell's own context fields, with the Map pane's on top. */
function context() {
  return {
    ...shellCtx(),
    heatSpots: HEAT_SPOTS,
    heatPointSets: new Map([[`${TODAY}:SUNSET`, []], [`${TOMORROW}:SUNRISE`, []]]),
    heatStripCards: CARDS,
    reachById: new Map([[WHITBY, { driveMinutes: 95, distanceMiles: 60 }]]),
    effectiveReachById: new Map(),
    scoreRows: [],
    scoresLoaded: true,
    briefing: { ...briefing(), hotTopics: [] },
    regions: [{ id: 5, name: REGION, enabled: true }],
    todayStr: TODAY,
    origin: null,
  };
}

let ask$;
function Capture() {
  const value = useAsk();
  useEffect(() => { ask$ = value; });
  return null;
}

/** What `App` does: the pane's door becomes the shell's `locationSheetHandoff`, with a nonce. */
function Host({ onOpenSettings }) {
  const [handoff, setHandoff] = useState(null);
  const nonce = useRef(0);
  return (
    <AskProvider>
      <Capture />
      <WindowFirstShell
        onOpenSettings={onOpenSettings}
        onSignOut={vi.fn()}
        onShowOnMap={vi.fn()}
        locations={[]}
        locationSheetHandoff={handoff}
        mapPane={(
          <WindowFirstMapPane
            locations={LOCATIONS}
            dates={[TODAY, TOMORROW]}
            selectedDate={TODAY}
            onSelectDate={() => {}}
            autoEventType="SUNSET"
            onOpenLocationSheet={(spot) => { nonce.current += 1; setHandoff({ ...spot, nonce: nonce.current }); }}
          />
        )}
      />
    </AskProvider>
  );
}

function withMeasuredLayout(width, height) {
  const w = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetWidth');
  const h = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  Object.defineProperty(HTMLElement.prototype, 'offsetWidth', { configurable: true, get: () => width });
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', { configurable: true, get: () => height });
  return () => {
    if (w) Object.defineProperty(HTMLElement.prototype, 'offsetWidth', w);
    else delete HTMLElement.prototype.offsetWidth;
    if (h) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', h);
    else delete HTMLElement.prototype.offsetHeight;
  };
}

let restoreLayout;
let observers;
let originalRO;
beforeEach(() => {
  mockIsMobile = true;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  vi.spyOn(document, 'hasFocus').mockReturnValue(true);
  restoreLayout = withMeasuredLayout(90, 22);
  localStorage.clear();
  ask$ = undefined;
  observers = [];
  originalRO = global.ResizeObserver;
  global.ResizeObserver = class {
    constructor(callback) { this.callback = callback; observers.push(this); }

    observe() {}

    unobserve() {}

    disconnect() {}
  };
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
  briefingValue = context();
  fakeMap = makeMap();
});

afterEach(() => {
  restoreLayout();
  global.ResizeObserver = originalRO;
  briefingValue = null;
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.clearAllMocks();
  fakeMap?.container.remove();
});

const tab = (name) => screen.getByRole('tab', { name });
const sheet = () => screen.getByTestId('wf-map-peek');
const mode = () => sheet().getAttribute('data-ask');
/** The shell hiding (0 x 0) or showing the Map's panel: the pane's own ResizeObserver fires with its box. */
const paneBox = async (width, height) => {
  const pane = screen.getByTestId('window-first-map-pane');
  vi.spyOn(pane, 'getBoundingClientRect').mockReturnValue({
    width, height, top: 0, left: 0, right: width, bottom: height,
  });
  await act(async () => { observers.forEach((o) => o.callback([])); });
};
const openRowKey = () => screen.getAllByTestId('location-sheet-row')
  .find((row) => within(row).getByTestId('location-sheet-row-toggle').getAttribute('aria-expanded') === 'true')
  ?.dataset.window;

/** On the Map tab with an answer in the open Ask section, pick 2 (Whitby, TOMORROW's sunrise) planned. */
async function planOnTheMap(onOpenSettings = vi.fn()) {
  render(<Host onOpenSettings={onOpenSettings} />);
  await waitFor(() => expect(ask$?.availability).toBe('on'));
  fireEvent.click(tab('Map'));
  await screen.findByTestId('wf-map-peek');
  await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-ask-entry')); });
  ask.mockResolvedValue(ownResponse({
    picks: [
      pick({
        rank: 1, locationId: SALTBURN, locationName: 'Saltburn', why: 'Close, and the sky is open.',
      }),
      pick({
        rank: 2, date: TOMORROW, targetType: 'SUNRISE', windowId: `${TOMORROW}_sunrise`,
      }),
    ],
    events: [],
  }));
  await act(async () => { fireEvent.change(screen.getByTestId('ask-input'), { target: { value: 'Where?' } }); });
  await act(async () => { fireEvent.submit(screen.getByTestId('ask-input-row')); });
  await screen.findByTestId('ask-picks');
  await act(async () => { fireEvent.click(screen.getByTestId('ask-plan-this-2')); });
}

describe('Open in Plan, from the phone Map’s Ask section, through the real shell', () => {
  it('moves to Plan and opens the location sheet at the PICK’s window — the pane goes hidden, the conversation stays', async () => {
    await planOnTheMap();
    expect(ask$.phase).toBe('plan');
    expect(mode()).toBe('expanded');

    await act(async () => { fireEvent.click(screen.getByTestId('ask-plan-open')); });

    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('location-sheet-title')).toHaveTextContent('Whitby');
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('window-first-panel-map')).toHaveAttribute('hidden');
    expect(openRowKey()).toBe(`${TOMORROW}:SUNRISE`);
    expect(document.querySelectorAll('[aria-modal="true"]')).toHaveLength(1);
    // The conversation survives the move — the plan view is still the plan view.
    expect(ask$.phase).toBe('plan');
    expect(ask$.planPick).toBe(2);
  });

  it('the Map comes back with the Ask section CLOSED and the answer MINIMISED', async () => {
    await planOnTheMap();
    await act(async () => { fireEvent.click(screen.getByTestId('ask-plan-open')); });
    await screen.findByTestId('location-sheet');
    await paneBox(0, 0);

    fireEvent.click(screen.getByTestId('location-sheet-close'));
    await waitFor(() => expect(screen.queryByTestId('location-sheet')).toBeNull());
    fireEvent.click(tab('Map'));
    await paneBox(390, 500);

    expect(mode()).toBe('minimised');
    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('Plan this');
    expect(ask$.phase).toBe('plan');
  });

  it('closing the sheet leaves focus somewhere real — never <body>', async () => {
    await planOnTheMap();
    await act(async () => { fireEvent.click(screen.getByTestId('ask-plan-open')); });
    await screen.findByTestId('location-sheet');
    await waitFor(() => expect(document.activeElement).toBe(screen.getByTestId('location-sheet')));

    fireEvent.click(screen.getByTestId('location-sheet-close'));

    await waitFor(() => expect(screen.queryByTestId('location-sheet')).toBeNull());
    expect(document.activeElement).not.toBe(document.body);
    expect(screen.getByTestId('window-first-shell').contains(document.activeElement)).toBe(true);
  });
});
