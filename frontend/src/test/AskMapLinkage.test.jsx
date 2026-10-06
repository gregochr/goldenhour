/**
 * Ask PhotoCast on the REAL Map chain (F3, `docs/engineering/ask-photocast-plan.md` §2.7):
 * `AskProvider` → `WindowFirstMapPane` → `MapView` → `MapLabels` / `AskCameraController`, with the real
 * conversation on one side and the real chips on the other and only Leaflet, the heat canvas and the
 * network replaced. The unit suites prove each end (`MapViewAsk`, `MapLabelsAsk`, `AskCameraController`,
 * `AskContextAsked`); this file proves they are joined — the answer lands and numbers the map, a card
 * moves the window and the camera, a chip chooses the card, clearing puts everything back.
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
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
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
import {
  AskApiError, ask, getAskSettings, getReady,
} from '../api/askApi.js';

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
/** What a test can do to the running harness: re-render it, or take the pane away and leave the provider. */
let controls;
/** A parent that owns the date, as `App` does — a pick moves the window only if the parent follows. */
function Harness({ onSelectDate }) {
  const [date, setDate] = useState(TODAY);
  const [showPane, setShowPane] = useState(true);
  const [, bump] = useState(0);
  // In an effect, not the render: a render must not write a variable outside the component.
  useEffect(() => { controls = { setShowPane, bump: () => bump((n) => n + 1) }; }, []);
  return (
    <AskProvider>
      <Capture />
      {showPane && (
        <WindowFirstMapPane
          locations={LOCATIONS}
          dates={[TODAY, TOMORROW]}
          selectedDate={date}
          onSelectDate={(d, opts) => { selectedDates.push(d); onSelectDate?.(d, opts); setDate(d); }}
          autoEventType="SUNSET"
        />
      )}
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
  await waitFor(() => expect(ask$?.availability).toBe('on'));
  return result;
}
const chips = () => [...document.querySelectorAll('[data-testid="map-label-chip"]')];
const pickChips = () => chips().filter((c) => c.dataset.ask === 'pick');
const askAnAnswer = async () => {
  ask.mockResolvedValue(answer());
  await act(async () => { await ask$.askTyped('Where is good?', { view: 'map', regionIds: [] }); });
  await waitFor(() => expect(pickChips().length).toBe(2));
};

beforeEach(() => {
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

describe('the Map publishes its context into Ask', () => {
  it('Everywhere with the pill\'s window: no regions, the window id in the server\'s encoding, and the labels', async () => {
    await renderMap();

    await waitFor(() => expect(ask$.mapContext).not.toBeNull());
    expect(ask$.mapContext).toEqual({
      regionIds: [],
      regionNames: [],
      windowId: `${TODAY}_sunset`,
      windowLabel: 'Tonight sunset',
      viewLabel: 'Map · Everywhere',
    });
  });

  it('planning from an away region is that region\'s scope — by id, under the origin\'s own name', async () => {
    await renderMap({ ctx: { origin: { id: 5, name: REGION, baseName: 'Whitby' } } });

    await waitFor(() => expect(ask$.mapContext?.regionIds).toEqual([5]));
    expect(ask$.mapContext.viewLabel).toBe('Map · Around Whitby');
  });

  it('follows the window when the reader steps it', async () => {
    await renderMap();
    await waitFor(() => expect(ask$.mapContext?.windowId).toBe(`${TODAY}_sunset`));

    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-next')); });

    await waitFor(() => expect(ask$.mapContext?.windowId).toBe(`${TOMORROW}_sunrise`));
    expect(ask$.mapContext.windowLabel).toBe('Tomorrow sunrise');
  });

  it('⚠️ forgets the Map when the PANE goes while the provider stays — the dock must not quote a scope that no longer exists', async () => {
    await renderMap();
    await waitFor(() => expect(ask$.mapContext).not.toBeNull());

    await act(async () => { controls.setShowPane(false); });

    expect(ask$.mapContext).toBeNull();
  });

  it('⚠️ regions that arrive AFTER the first publish still name the region: the context is republished with them', async () => {
    const away = { id: 5, name: REGION, baseName: 'Whitby' };
    await renderMap({ ctx: { origin: away, regions: [] } });
    // Nothing can place the region yet, so the question would go everywhere — and the chip says so.
    await waitFor(() => expect(ask$.mapContext?.viewLabel).toBe('Map · Everywhere'));
    expect(ask$.mapContext.regionIds).toEqual([]);

    briefingValue = context({ origin: away, regions: [{ id: 5, name: REGION, enabled: true }] });
    await act(async () => { controls.bump(); });

    await waitFor(() => expect(ask$.mapContext?.regionIds).toEqual([5]));
    expect(ask$.mapContext.viewLabel).toBe('Map · Around Whitby');
  });
});

describe('an answer lands on the map', () => {
  it('numbers each pick from ITS OWN window, fades every other chip, dims the field and fits the camera once', async () => {
    await renderMap();
    await waitFor(() => expect(chips().length).toBeGreaterThan(0));
    expect(chips().every((c) => !c.hasAttribute('data-ask'))).toBe(true);
    expect(screen.getByTestId('map-heat-layer')).toHaveAttribute('data-dim', '1');

    await askAnAnswer();

    // Pick 1 is tonight at Saltburn (4★); pick 2 is Whitby TOMORROW's sunrise, its OWN 4★ — not the
    // 5★ Whitby carries tonight, which is the window on screen.
    const [one, two] = pickChips().sort((a, b) => a.dataset.askRank - b.dataset.askRank);
    expect(one).toHaveTextContent('Saltburn');
    expect(one).toHaveTextContent('4★');
    expect(two).toHaveTextContent('Whitby');
    expect(two).toHaveTextContent('4★');
    expect(two).not.toHaveTextContent('5★');
    expect(two).toHaveTextContent('Tue AM');
    expect(two).toHaveAccessibleName(/^Pick 2, Whitby, Tuesday sunrise, 4 stars$/);
    // The one other location in the pool steps back.
    expect(chips().find((c) => c.textContent.includes('Roseberry'))).toHaveAttribute('data-ask', 'fade');
    expect(screen.getByTestId('map-heat-layer')).toHaveAttribute('data-dim', '0.36');
    // One fit, over both picks, capped at zoom 10.
    expect(fakeMap.flyToBoundsCalls).toHaveLength(1);
    expect(fakeMap.flyToBoundsCalls[0][1].maxZoom).toBe(10);
    expect(fakeMap.flyToBoundsCalls[0][0].points).toHaveLength(2);
  });

  it('does not refit when something else re-renders the map', async () => {
    await renderMap();
    await askAnAnswer();

    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-next')); });
    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-prev')); });

    expect(fakeMap.flyToBoundsCalls).toHaveLength(1);
  });
});

describe('choosing a pick', () => {
  it('a CARD moves the window to the pick\'s, selects it on the map, and flies to it at 10.5', async () => {
    await renderMap();
    await askAnAnswer();
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);
    fakeMap.flyToCalls.length = 0;

    await act(async () => { ask$.selectPick(2); });

    await waitFor(() => expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i));
    expect(selectedDates).toContain(TOMORROW);
    expect(pickChips().find((c) => c.dataset.askRank === '2')).toHaveAttribute('data-ask-selected', 'true');
    expect(fakeMap.flyToCalls).toHaveLength(1);
    expect(fakeMap.flyToCalls[0][1]).toBe(10.5);
    // No callout for any location: the selection was cleared, and a pick opens none.
    expect(screen.queryByTestId('map-callout')).toBeNull();
  });

  it('clears a callout that was open for an ordinary location', async () => {
    await renderMap();
    await askAnAnswer();
    await act(async () => { fireEvent.click(chips().find((c) => c.textContent.includes('Roseberry'))); });
    expect(await screen.findByTestId('map-callout')).toHaveTextContent('Roseberry');

    await act(async () => { ask$.selectPick(1); });

    await waitFor(() => expect(screen.queryByTestId('map-callout')).toBeNull());
  });

  it('a pick CHIP chooses its card — it does not select the location or open a callout', async () => {
    await renderMap();
    await askAnAnswer();

    await act(async () => { fireEvent.click(pickChips().find((c) => c.dataset.askRank === '2')); });

    expect(ask$.selectedPick).toBe(2);
    expect(screen.queryByTestId('map-callout')).toBeNull();
    await waitFor(() => expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i));
  });

  it('an ordinary chip still opens its callout while an answer is showing', async () => {
    await renderMap();
    await askAnAnswer();

    await act(async () => { fireEvent.click(chips().find((c) => c.textContent.includes('Roseberry'))); });

    expect(await screen.findByTestId('map-callout')).toHaveTextContent('Roseberry');
    expect(ask$.selectedPick).toBeNull();
  });

  it('⚠️ focus stays on the pick chip that was pressed', async () => {
    await renderMap();
    await askAnAnswer();
    const chip = pickChips().find((c) => c.dataset.askRank === '1');
    chip.focus();

    await act(async () => { fireEvent.click(chip); });
    await waitFor(() => expect(ask$.selectedPick).toBe(1));

    expect(document.activeElement).toBe(chip);
  });

  it('choosing the same pick again flies again', async () => {
    await renderMap();
    await askAnAnswer();
    await act(async () => { ask$.selectPick(1); });
    fakeMap.flyToCalls.length = 0;

    await act(async () => { ask$.selectPick(1); });

    expect(fakeMap.flyToCalls).toHaveLength(1);
  });
});

describe('a refused question does not repeat a choice', () => {
  it('⚠️ the map keeps the window the reader moved it to when the earlier answer comes back', async () => {
    const onSelectDate = vi.fn();
    await renderMap({ onSelectDate });
    await askAnAnswer();
    await act(async () => { ask$.selectPick(2); });
    await waitFor(() => expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i));
    // The reader steps the window back to tonight.
    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-prev')); });
    await waitFor(() => expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i));
    onSelectDate.mockClear();

    // A follow-up is refused: the busy conversation has no answer, then the earlier one is put back.
    ask.mockRejectedValueOnce(new AskApiError({ status: 429, code: 'RATE_LIMITED', error: 'Slow down a moment.' }));
    await act(async () => { await ask$.askTyped('And again?', { view: 'map', regionIds: [] }); });
    expect(ask$.restored).toBe(true);
    await waitFor(() => expect(pickChips().length).toBe(2));

    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);
    expect(onSelectDate).not.toHaveBeenCalled();
  });
});

describe('a modal over the map (the tablet\'s Ask sheet) holds the camera and the window until it closes', () => {
  it('fits and follows only once the dialog is gone', async () => {
    await renderMap();
    await waitFor(() => expect(chips().length).toBeGreaterThan(0));
    // `BottomSheet`'s own dialog: role dialog, aria-modal, outside the pane (`foreignModalOver`'s test).
    const sheet = document.createElement('div');
    sheet.setAttribute('role', 'dialog');
    sheet.setAttribute('aria-modal', 'true');
    document.body.appendChild(sheet);
    await act(async () => { await Promise.resolve(); });

    ask.mockResolvedValue(answer());
    await act(async () => { await ask$.askTyped('Where is good?', { view: 'map', regionIds: [] }); });
    await waitFor(() => expect(pickChips().length).toBe(2));
    await act(async () => { ask$.selectPick(2); });

    // Numbered, but nothing has moved behind the dialog.
    expect(fakeMap.flyToBoundsCalls).toHaveLength(0);
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);

    await act(async () => { sheet.remove(); });

    await waitFor(() => expect(fakeMap.flyToBoundsCalls).toHaveLength(1));
    await waitFor(() => expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i));
  });
});

describe('focus', () => {
  it('⚠️ stays on pick 2\'s chip when pressing it moves the WINDOW (the harder case: the spots re-rate under it)', async () => {
    await renderMap();
    await askAnAnswer();
    const chip = pickChips().find((c) => c.dataset.askRank === '2');
    chip.focus();

    await act(async () => { fireEvent.click(chip); });
    await waitFor(() => expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i));

    expect(pickChips().find((c) => c.dataset.askRank === '2')).toBe(chip);
    expect(document.activeElement).toBe(chip);
  });
});

describe('clearing the answer', () => {
  it('removes every data-ask and the dim, and leaves the camera where it is', async () => {
    await renderMap();
    await askAnAnswer();
    await act(async () => { ask$.selectPick(1); });
    const flights = fakeMap.flyToCalls.length;
    const fits = fakeMap.flyToBoundsCalls.length;

    await act(async () => { ask$.clear(); });

    expect(chips().every((c) => !c.hasAttribute('data-ask'))).toBe(true);
    expect(chips().some((c) => c.textContent.includes('Whitby') || c.textContent.includes('Saltburn'))).toBe(true);
    expect(screen.getByTestId('map-heat-layer')).toHaveAttribute('data-dim', '1');
    expect(fakeMap.flyToCalls).toHaveLength(flights);
    expect(fakeMap.flyToBoundsCalls).toHaveLength(fits);
  });

  it('a new answer fits again', async () => {
    await renderMap();
    await askAnAnswer();
    await act(async () => { ask$.clear(); });

    ask.mockResolvedValue(answer());
    await act(async () => { await ask$.askTyped('And again?', { view: 'map', regionIds: [] }); });
    await waitFor(() => expect(pickChips().length).toBe(2));

    expect(fakeMap.flyToBoundsCalls).toHaveLength(2);
  });
});
