/**
 * Ask PhotoCast's phone peek row on the REAL chain (F4, `docs/engineering/ask-photocast-plan.md` §2.7's
 * phone paragraph): `AskProvider` → `WindowFirstMapPane` → `MapView` → `MapPeekSheet` / `MapPeekAsk` →
 * `AskConversation`, with the real conversation on one side and the real chips and pick cards on the
 * other, and only Leaflet, the heat canvas and the network replaced. `AskPeekStateTable` proves each cell
 * of the table at the `MapView` level against a stand-in conversation; this file proves the joins — the
 * Ready list in the expanded sheet, an answer that lands while the sheet is open, a map touch that
 * minimises it, a chip that expands it, a ✕ that clears it — and that the camera is told what the sheet
 * covers, at each step.
 */
import React, { useEffect, useState } from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen, waitFor,
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
import { AskProvider, useAsk } from '../context/AskContext.jsx';
import {
  briefing, NOW, ownResponse, pick, readyResponse, ROSEBERRY, SALTBURN, settings, WHITBY,
} from './askFixtures.js';

const TODAY = '2026-10-05';
const TOMORROW = '2026-10-06';
const REGION = 'North York Moors & Coast';

const loc = (id, name, lat, lon, forecasts, regionName = REGION) => ({
  id,
  name,
  lat,
  lon,
  regionName,
  bortleClass: 4,
  locationType: ['SEASCAPE'],
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
  // A second region, so that planning from the first is a real narrowing.
  loc(ROSEBERRY, 'Roseberry Topping', 54.5, -1.19, { [TODAY]: sun(TODAY, 'sunset', 3) }, 'Teesside'),
];
const HEAT_SPOTS = LOCATIONS.map((l) => ({
  id: l.id, name: l.name, lat: l.lat, lng: l.lon, regionName: l.regionName, rid: l.regionName, bortleClass: 4, scores: [5],
}));
const CARDS = [
  {
    key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '17:41', bestRating: 5, confidence: 'high', badges: [],
  },
  {
    key: `${TOMORROW}:SUNRISE`, date: TOMORROW, targetType: 'SUNRISE', label: 'Tomorrow sunrise', time: '05:58', bestRating: 4, confidence: 'high', badges: [],
  },
];

function context(over = {}) {
  return {
    heatSpots: HEAT_SPOTS,
    heatPointSets: new Map([[`${TODAY}:SUNSET`, []], [`${TOMORROW}:SUNRISE`, []]]),
    heatStripCards: CARDS,
    reachById: new Map([[WHITBY, { driveMinutes: 95, distanceMiles: 60 }]]),
    effectiveReachById: new Map(),
    homePlace: null,
    todayStr: TODAY,
    origin: null,
    scoreRows: [],
    scoresLoaded: true,
    briefing: briefing(),
    regions: [{ id: 5, name: REGION, enabled: true }],
    isPro: false,
    ...over,
  };
}

/** What the conversation holds, so a test can drive it and read what it published. */
let ask$;
function Capture() {
  const value = useAsk();
  useEffect(() => { ask$ = value; });
  return null;
}

let selectedDates;
/** A parent that owns the date, as `App` does — a pick moves the window only if the parent follows. */
function Harness({ onSelectDate }) {
  const [date, setDate] = useState(TODAY);
  return (
    <AskProvider>
      <Capture />
      <WindowFirstMapPane
        locations={LOCATIONS}
        dates={[TODAY, TOMORROW]}
        selectedDate={date}
        onSelectDate={(d, opts) => { selectedDates.push(d); onSelectDate?.(d, opts); setDate(d); }}
        autoEventType="SUNSET"
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

/** Saltburn tonight at 4★ and Whitby TOMORROW's sunrise at 4★ — the second is another window. */
const answer = () => ownResponse({
  picks: [
    pick({
      rank: 1, locationId: SALTBURN, locationName: 'Saltburn', why: 'Close, and the sky is open.',
    }),
    pick({
      rank: 2, date: TOMORROW, targetType: 'SUNRISE', windowId: `${TOMORROW}_sunrise`,
    }),
  ],
});

let restoreLayout;
async function renderMap(props = {}) {
  briefingValue = context(props.ctx);
  fakeMap = makeMap();
  const root = document.createElement('div');
  document.body.appendChild(root);
  let result;
  await act(async () => {
    result = render(<Harness onSelectDate={props.onSelectDate} />, { container: root });
  });
  await waitFor(() => expect(ask$?.availability).toBe(props.availability ?? 'on'));
  return result;
}
const chips = () => [...document.querySelectorAll('[data-testid="map-label-chip"]')];
const pickChips = () => chips().filter((c) => c.dataset.ask === 'pick');

beforeEach(() => {
  mockIsMobile = true;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(NOW);
  vi.spyOn(document, 'hasFocus').mockReturnValue(true);
  restoreLayout = withMeasuredLayout(90, 22);
  localStorage.clear();
  selectedDates = [];
  ask$ = undefined;
  getReady.mockResolvedValue(readyResponse());
  getAskSettings.mockResolvedValue(settings());
});

afterEach(() => {
  restoreLayout();
  briefingValue = null;
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.clearAllMocks();
  fakeMap?.container.remove();
});

const sheet = () => screen.getByTestId('wf-map-peek');
const mode = () => sheet().getAttribute('data-ask');
const psh = () => screen.getByTestId('wf-map-pane').style.getPropertyValue('--psh');
const buttonsShown = () => screen.queryByTestId('wf-map-peek-btn-win') !== null;
/** A hand on the map: Leaflet's `dragstart`, which the sheet's own listener answers. */
const dragMap = () => act(async () => { fakeMap.container.dispatchEvent(new Event('dragstart')); });
const openAsk = () => act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-ask-entry')); });

/** Types a question into the sheet's field and sends it; the provider's `ask` answers with the fixture. */
async function typeQuestion(text = 'Where is good?') {
  ask.mockResolvedValue(answer());
  await act(async () => { fireEvent.change(screen.getByTestId('ask-input'), { target: { value: text } }); });
  await act(async () => { fireEvent.submit(screen.getByTestId('ask-input-row')); });
  await waitFor(() => expect(pickChips().length).toBe(2));
}

describe('the row, on the real chain', () => {
  it('is the Ask row above the three buttons, at 126, when Ask is on', async () => {
    await renderMap();

    expect(mode()).toBe('collapsed');
    expect(psh()).toBe('126px');
    expect(screen.getByTestId('wf-map-peek-ask-entry')).toHaveTextContent('Ask about what’s on the map…');
    expect(buttonsShown()).toBe(true);
  });

  it('is not there when the server says Ask is off: the sheet is the sheet it was, at 74', async () => {
    getAskSettings.mockResolvedValue(settings({ enabled: false }));

    await renderMap({ availability: 'off' });

    expect(mode()).toBe('off');
    expect(psh()).toBe('74px');
    expect(screen.queryByTestId('wf-map-peek-ask-entry')).not.toBeInTheDocument();
    expect(buttonsShown()).toBe(true);
  });

  it('is exactly the old Map on a tablet or desktop: no sheet, no --psh, Ask on', async () => {
    mockIsMobile = false;

    await renderMap();

    expect(screen.queryByTestId('wf-map-peek')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-map-pane').style.getPropertyValue('--psh')).toBe('');
  });

  it('opens to the Ready suggestions for the Map, with the cursor in the field', async () => {
    await renderMap();

    await openAsk();

    expect(mode()).toBe('expanded');
    expect(buttonsShown()).toBe(false);
    expect(await screen.findByTestId('ask-ready-list')).toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByTestId('ask-input'));
  });
});

describe('an answer, from landing to clearing', () => {
  it('lands in the open sheet and STAYS open; the picks are numbered and the camera fits inside what is left of the frame', async () => {
    await renderMap();
    await openAsk();

    await typeQuestion();

    expect(mode()).toBe('expanded');
    expect(screen.getByTestId('ask-picks')).toBeInTheDocument();
    expect(pickChips()).toHaveLength(2);
    // 470 covered, clamped: top + bottom padding never exceed 60% of the 500px frame.
    expect(fakeMap.flyToBoundsCalls).toHaveLength(1);
    const options = fakeMap.flyToBoundsCalls[0][1];
    expect(options.paddingTopLeft[1] + options.paddingBottomRight[1]).toBeLessThanOrEqual(500 * 0.6 + 1e-9);
    expect(options.paddingBottomRight[1]).toBeGreaterThan(options.paddingTopLeft[1]);
  });

  it('a map drag minimises it to ONE LINE — rank, spot, time, "2 picks" — and the picks are re-fitted into the room that opens up', async () => {
    await renderMap();
    await openAsk();
    await typeQuestion();
    const fitBounds = vi.spyOn(fakeMap, 'fitBounds');

    await dragMap();

    expect(mode()).toBe('minimised');
    expect(psh()).toBe('112px');
    const line = screen.getByTestId('wf-map-peek-mini');
    expect(line).toHaveTextContent('Saltburn');
    expect(line).toHaveTextContent('2 picks');
    expect(line).toHaveAccessibleName(/^Pick 1, Saltburn, .* 2 picks\. Show the answer$/);
    // The conversation is kept, not cleared, and the camera was told the sheet came down to 112.
    expect(ask$.phase).toBe('answer');
    await waitFor(() => expect(fitBounds).toHaveBeenCalled());
    expect(fitBounds.mock.calls.at(-1)[1].animate).toBe(false);
    expect(fitBounds.mock.calls.at(-1)[1].paddingBottomRight[1]).toBe(60 + 112);
    expect(pickChips()).toHaveLength(2);
  });

  it('the camera\'s OWN fit does not minimise the answer it was fitting (a zoom is not always a hand)', async () => {
    await renderMap();
    await openAsk();

    await typeQuestion();
    // The camera flew; a real Leaflet would fire `zoomstart` for it. Inside its own move that is not a hand.
    await act(async () => { fakeMap.container.dispatchEvent(new Event('zoomstart')); });

    expect(mode()).toBe('expanded');
  });

  it('tapping the line brings the answer back, cards and all', async () => {
    await renderMap();
    await openAsk();
    await typeQuestion();
    await dragMap();

    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-mini')); });

    expect(mode()).toBe('expanded');
    expect(screen.getByTestId('ask-pick-1')).toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask'));
  });

  it('a pick chip pressed while minimised EXPANDS the sheet to the card it chose, and the window follows', async () => {
    await renderMap();
    await openAsk();
    await typeQuestion();
    await dragMap();

    await act(async () => { fireEvent.click(pickChips().find((c) => c.dataset.askRank === '2')); });

    expect(mode()).toBe('expanded');
    expect(ask$.selectedPick).toBe(2);
    expect(screen.getByTestId('ask-pick-2')).toHaveAttribute('data-selected', 'true');
    await waitFor(() => expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i));
  });

  it('a callout may stand over the minimised line — and its band is told the sheet is 112', async () => {
    await renderMap();
    await openAsk();
    await typeQuestion();
    await dragMap();

    await act(async () => { fireEvent.click(chips().find((c) => c.textContent.includes('Roseberry'))); });

    expect(await screen.findByTestId('map-callout')).toHaveTextContent('Roseberry');
    expect(mode()).toBe('minimised');
    expect(psh()).toBe('112px');
  });

  it('the ✕ clears the answer, brings the three buttons back, unnumbers the map — and focus lands on the Ask row, not <body>', async () => {
    await renderMap();
    await openAsk();
    await typeQuestion();
    expect(buttonsShown()).toBe(false);
    screen.getByTestId('wf-map-peek-ask-x').focus();

    await act(async () => { fireEvent.click(screen.getByTestId('wf-map-peek-ask-x')); });

    expect(ask$.phase).toBe('empty');
    expect(mode()).toBe('collapsed');
    expect(buttonsShown()).toBe(true);
    expect(psh()).toBe('126px');
    expect(pickChips()).toHaveLength(0);
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-entry'));
  });

  it('Escape in the field closes the open section onto the entry — and the pane\'s own Escape is still alive after it', async () => {
    await renderMap();
    await openAsk();
    screen.getByTestId('ask-input').focus();

    await act(async () => { fireEvent.keyDown(screen.getByTestId('ask-input'), { key: 'Escape' }); });

    expect(mode()).toBe('collapsed');
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-entry'));
    // The pane's rule runs only while focus is inside the pane: an ordinary chip's callout is cleared by
    // the next Escape, from where focus now is.
    await act(async () => { fireEvent.click(chips().find((c) => c.textContent.includes('Roseberry'))); });
    expect(await screen.findByTestId('map-callout')).toBeInTheDocument();
    screen.getByTestId('wf-map-peek-ask-entry').focus();
    await act(async () => { fireEvent.keyDown(document.activeElement, { key: 'Escape' }); });
    await waitFor(() => expect(screen.queryByTestId('map-callout')).toBeNull());
  });
});

describe('a tab switch, through the pane\'s own ResizeObserver (the real source of `panelShown`)', () => {
  let observers;
  let originalRO;
  beforeEach(() => {
    observers = [];
    originalRO = global.ResizeObserver;
    global.ResizeObserver = class {
      constructor(callback) { this.callback = callback; observers.push(this); }

      observe() {}

      unobserve() {}

      disconnect() {}
    };
  });
  afterEach(() => { global.ResizeObserver = originalRO; });

  /** The shell hiding (0 x 0) or showing the Map's panel: the observer fires with the pane's own box. */
  const paneBox = async (width, height) => {
    const pane = screen.getByTestId('window-first-map-pane');
    vi.spyOn(pane, 'getBoundingClientRect').mockReturnValue({
      width, height, top: 0, left: 0, right: width, bottom: height,
    });
    await act(async () => { observers.forEach((o) => o.callback([])); });
  };

  it('with the suggestions showing: closed on the way back', async () => {
    await renderMap();
    await openAsk();
    expect(mode()).toBe('expanded');

    await paneBox(0, 0);
    await paneBox(390, 500);

    expect(mode()).toBe('collapsed');
    expect(buttonsShown()).toBe(true);
  });

  it('with an answer expanded: kept, minimised — and the camera was not asked to move for the round trip', async () => {
    await renderMap();
    await openAsk();
    await typeQuestion();
    const flights = fakeMap.flyToBoundsCalls.length;

    await paneBox(0, 0);
    expect(mode()).toBe('minimised');
    await paneBox(390, 500);

    expect(mode()).toBe('minimised');
    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('Saltburn');
    expect(ask$.phase).toBe('answer');
    expect(fakeMap.flyToBoundsCalls).toHaveLength(flights);
  });
});

describe('Escape from an expanded answer, from where focus really is', () => {
  it('minimises it, keeps the answer, and focus lands on the Ask row — whose Escape then clears the selection', async () => {
    await renderMap();
    await openAsk();
    await typeQuestion();
    screen.getByTestId('ask-input').focus();

    await act(async () => { fireEvent.keyDown(document.activeElement, { key: 'Escape' }); });

    expect(mode()).toBe('minimised');
    expect(ask$.phase).toBe('answer');
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-entry'));
    await act(async () => { fireEvent.click(chips().find((c) => c.textContent.includes('Roseberry'))); });
    expect(await screen.findByTestId('map-callout')).toBeInTheDocument();
    await act(async () => { fireEvent.keyDown(document.activeElement, { key: 'Escape' }); });
    await waitFor(() => expect(screen.queryByTestId('map-callout')).toBeNull());
    expect(mode()).toBe('minimised');
  });
});

