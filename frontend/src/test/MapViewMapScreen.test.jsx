/**
 * `MapView` inside the map view (`docs/engineering/one-screen-plan.md` O1): what it publishes through
 * `MapScreenContext` (the header's served fields, the route, its Escape ladder), the one `WindowControl` portalled up into
 * the header, and the lens note that replaced the door's landing strip.
 *
 * The harness is `MapViewPlanHandoff.test.jsx`'s own: a minimal Leaflet, with the field, labels, callout and filter
 * popover mocked to probes.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, cleanup, render, screen, fireEvent } from '@testing-library/react';

let cornerEl;
vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });

  class Control {
    constructor(options = {}) { this.options = options; }

    addTo(map) {
      this._container = this.onAdd(map);
      this._container.classList.add('leaflet-control');
      map._corner.appendChild(this._container);
      return this;
    }

    remove() {
      this._container?.remove();
      return this;
    }
  }

  const DomEvent = {
    disableClickPropagation: () => {},
    disableScrollPropagation: () => {},
  };
  // `latLngBounds` for `FitBoundsController` (the `focusPoints` door fits through it).
  const latLngBounds = (points) => ({ points });
  const L = { icon, divIcon, point, Control, DomEvent, latLngBounds };
  return { default: L, ...L };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: (...args) => { flyToCalls.push(args); },
    fitBounds: (bounds) => { fitBoundsCalls.push(bounds); },
    addControl: () => {},
    _corner: cornerEl,
  }),
}));

/** Every `map.flyTo` call - the camera a LOCATION door flies, read off the map. */
const flyToCalls = [];

/** Every `map.fitBounds` call - what the `focusPoints` door's camera does, read off the map. */
const fitBoundsCalls = [];

/** The field, recording the points it is handed - the lens is asserted on what the kernel gets. */
const heatLayerCalls = [];
vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: (props) => { heatLayerCalls.push(props); return <div data-testid="map-heat-layer" />; },
}));

/** Captures every props object handed to `MapLabels` — the filtered/visible pool, for the region
 *  scope-flip assertion (`heatArea`/`areaSpots` vs `spots`). */
const mapLabelsCalls = [];
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => { mapLabelsCalls.push(props); return null; },
}));

/** The selection callout, mocked to a probe — proves a location the carried floor filters OUT of
 *  the visible pool still gets its callout, because the effect is a direct state write. */
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => (
    <div data-testid="probe-callout">
      <span data-testid="probe-callout-name">{props.location?.name ?? ''}</span>
    </div>
  ),
}));

/** Every filter/scope figure the effect writes, read straight off what `MapView` hands the REAL
 *  filter popover — never re-derived, so this file cannot disagree with the production wiring
 *  about which prop carries which state. `DRIVE_TIME_TIERS` is re-exported for the other chrome that
 *  imports it from this same module. */
const filtersPopoverCalls = [];
vi.mock('../components/map/FiltersPopover.jsx', () => ({
  default: (props) => { filtersPopoverCalls.push(props); return null; },
  DRIVE_TIME_TIERS: [[0, 'Any'], [45, '45 min'], [90, '1h 30'], [150, '2h 30']],
}));

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
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn(() => Promise.resolve({})) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import MapScreen from '../components/MapScreen.jsx';
import { MapScreenContext } from '../context/MapScreenContext.jsx';
import { escapeAnswered } from '../utils/mapEscape.js';
import { buildScoreIndex, lightWindows, lookupForWindow } from '../utils/locationSheet.js';

const TODAY = '2026-01-15';

const NEAR = {
  id: 1, name: 'Near', lat: 55.61, lon: -1.71, regionName: 'North East', locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 5, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    sunrise: { rating: 5, solarEventTime: `${TODAY}T08:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
  }]]),
};
const OTHER = {
  id: 2, name: 'Other', lat: 55.62, lon: -1.72, regionName: 'North East', locationType: ['LANDSCAPE'],
  forecastsByDate: new Map([[TODAY, {
    sunset: { rating: 4, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 50, goldenHourPotential: 50 },
  }]]),
};

function spotOf(loc, rating = 5) {
  return {
    id: loc.id, name: loc.name, lat: loc.lat, lng: loc.lon, regionName: loc.regionName,
    rid: loc.regionName, bortleClass: 4, r: [rating],
  };
}

const HEAT = {
  enabled: true,
  hasHome: false,
  spots: [spotOf(NEAR), spotOf(OTHER, 4)],
  areaSpots: [spotOf(NEAR), spotOf(OTHER, 4)],
  pointsByKey: new Map([[`${TODAY}:SUNSET`, []], [`${TODAY}:SUNRISE`, []]]),
  windows: [
    { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 5, conf: 1 },
    { key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'Tonight sunrise', time: '08:12', bestRating: 5, conf: 1 },
  ],
  areaBounds: [[54.3, -3.4], [55.7, -1.3]],
  catalogueBounds: [[53.9, -3.4], [55.7, -1.3]],
};

/** The Plan's own card for the sunset window, as the shell's `windowCards` carry it: the header names its served fields. */
const SUNSET_CARD = {
  key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', verdictLabel: 'Worth it', bestRating: 5, confidence: 'high',
  pick: null, allSpots: [], reachMeasured: false,
};

const header = vi.fn();
const route = vi.fn();
const escapeRegistered = vi.fn();
const stepRegistered = vi.fn();
const focusTitle = vi.fn();

/** The view's context, with the header slot a real node so the window control has somewhere to be portalled. */
function ViewContext({ slot, open = true, children }) {
  const value = React.useMemo(() => ({
    windowSlot: slot,
    publishHeader: header,
    publishRoute: route,
    publishEscape: escapeRegistered,
    publishStep: stepRegistered,
    focusTitle,
    open,
  }), [slot, open]);
  return <MapScreenContext.Provider value={value}>{children}</MapScreenContext.Provider>;
}

async function renderTab(props = {}, { slot = null, open = true } = {}) {
  let result;
  await act(async () => {
    result = render(
      <ViewContext slot={slot} open={open}>
        <MapView
          locations={[NEAR, OTHER]}
          date={TODAY}
          autoEventType="SUNSET"
          forecastDates={[TODAY]}
          heat={HEAT}
          windowCardsByKey={new Map([[SUNSET_CARD.key, SUNSET_CARD]])}
          {...props}
        />
      </ViewContext>,
    );
  });
  return result;
}

beforeEach(() => {
  localStorage.clear();
  mapLabelsCalls.length = 0;
  filtersPopoverCalls.length = 0;
  heatLayerCalls.length = 0;
  fitBoundsCalls.length = 0;
  flyToCalls.length = 0;
  header.mockClear();
  route.mockClear();
  escapeRegistered.mockClear();
  stepRegistered.mockClear();
  focusTitle.mockClear();
  cornerEl = document.createElement('div');
  cornerEl.className = 'leaflet-bottom leaflet-right';
  document.body.appendChild(cornerEl);
});

afterEach(() => {
  localStorage.clear();
  cornerEl.remove();
  vi.clearAllMocks();
});

const door = (extra) => ({
  source: 'plan', eventType: 'SUNSET', date: TODAY, region: null, minRating: null, limitMinutes: null,
  locationName: null, nonce: 1, ...extra,
});

describe('MapView inside the map view: what it publishes', () => {
  it('names the window on screen and states its served fields, from the Plan card', async () => {
    await renderTab();
    expect(header).toHaveBeenLastCalledWith({
      title: expect.stringMatching(/sunset/i), verdictLabel: 'Worth it', bestRating: 5, confidence: 'high', light: null,
    });
  });

  it('states nothing about a window the Plan has no card for', async () => {
    await renderTab({ windowCardsByKey: new Map() });
    expect(header).toHaveBeenLastCalledWith({
      title: expect.stringMatching(/sunset/i), verdictLabel: null, bestRating: null, confidence: null, light: null,
    });
  });

  describe('the light in the header (screen-spec.md D4): served score rows, never a calculation', () => {
    /** A score row for one place's sunset: the golden and blue hours as the score endpoint serves them. */
    const row = (id, name, golden, blue) => ({
      locationId: id, locationName: name, date: TODAY, targetType: 'SUNSET', rating: 4,
      goldenHourStart: `${TODAY}T${golden[0]}:00`, goldenHourEnd: `${TODAY}T${golden[1]}:00`,
      blueHourStart: `${TODAY}T${blue[0]}:00`, blueHourEnd: `${TODAY}T${blue[1]}:00`,
    });
    const scoreIndex = buildScoreIndex([
      row(1, 'Near', ['15:30', '16:12'], ['16:12', '16:50']),
      row(2, 'Other', ['15:40', '16:22'], ['16:22', '17:00']),
    ]);
    const expected = (id, name) => ({
      place: name,
      windows: lightWindows(lookupForWindow(scoreIndex, id, name, TODAY, 'SUNSET'), 'SUNSET')
        .map(({ label, range }) => ({ label, range })),
    });
    const withBestReach = (spot) => new Map([[SUNSET_CARD.key, { ...SUNSET_CARD, bestReach: spot }]]);

    it('is the selected place\'s, when one is selected', async () => {
      await renderTab({
        scoreIndex,
        windowCardsByKey: withBestReach({ locationId: 2, locationName: 'Other' }),
        planHandoff: door({ locationName: 'Near' }),
      });
      const { light } = header.mock.lastCall[0];
      expect(light).toEqual(expected(1, 'Near'));
      expect(light.windows.map((w) => w.label)).toEqual(['golden', 'blue']);
      expect(light.place).toBe('Near');
    });

    it('is the header card\'s best reachable place when none is selected', async () => {
      await renderTab({ scoreIndex, windowCardsByKey: withBestReach({ locationId: 2, locationName: 'Other' }) });
      expect(header.mock.lastCall[0].light).toEqual(expected(2, 'Other'));
    });

    it('finds the best reachable place by name when its id is missing', async () => {
      await renderTab({ scoreIndex, windowCardsByKey: withBestReach({ locationName: 'Other' }) });
      expect(header.mock.lastCall[0].light).toEqual(expected(2, 'Other'));
    });

    it('is omitted when neither a selected place nor a best reachable place exists', async () => {
      await renderTab({ scoreIndex });
      expect(header.mock.lastCall[0].light).toBeNull();
    });

    it('is omitted when the place has no score row', async () => {
      await renderTab({
        scoreIndex: buildScoreIndex([]),
        windowCardsByKey: withBestReach({ locationId: 2, locationName: 'Other' }),
      });
      expect(header.mock.lastCall[0].light).toBeNull();
    });

    it('has no light clause for a night window', async () => {
      await renderTab({
        scoreIndex,
        autoEventType: 'ASTRO',
        windowCardsByKey: withBestReach({ locationId: 1, locationName: 'Near' }),
        planHandoff: door({ eventType: 'ASTRO', locationName: 'Near' }),
      });
      expect(header.mock.lastCall[0].light).toBeNull();
    });

    it('is omitted with no score index at all', async () => {
      await renderTab({ windowCardsByKey: withBestReach({ locationId: 2, locationName: 'Other' }) });
      expect(header.mock.lastCall[0].light).toBeNull();
    });
  });

  it('publishes the route: the window, and the selected place by id once one is selected', async () => {
    await renderTab({ planHandoff: door({ locationName: 'Near' }) });
    expect(route).toHaveBeenLastCalledWith({ date: TODAY, eventType: 'SUNSET', locationId: 1 });
  });

  it('publishes an Escape ladder that answers whether it closed a layer: the callout first, then nothing', async () => {
    await renderTab({ planHandoff: door({ locationName: 'Near' }) });
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();
    const ladder = escapeRegistered.mock.calls.at(-1)[0];
    let closed;
    await act(async () => { closed = ladder(); });
    expect(closed).toBe(true);
    expect(screen.queryByTestId('probe-callout')).toBeNull();
    await act(async () => { closed = ladder(); });
    expect(closed).toBe(false);
  });

  it('a pane-level Escape marks the press answered only when it closed something', async () => {
    await renderTab({ planHandoff: door({ locationName: 'Near' }) });
    const pane = screen.getByTestId('wf-map-pane');
    const first = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    await act(async () => { pane.dispatchEvent(first); });
    expect(escapeAnswered(first)).toBe(true);
    const second = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    await act(async () => { pane.dispatchEvent(second); });
    expect(escapeAnswered(second)).toBe(false);
  });
});

describe('MapView inside the map view: the one window control', () => {
  it('is portalled into the header slot, once, and not drawn in the map chrome', async () => {
    const slot = document.createElement('div');
    document.body.appendChild(slot);
    await renderTab({}, { slot });
    expect(slot.querySelector('[data-testid="wf-win-control"]')).not.toBeNull();
    expect(screen.getAllByTestId('wf-win-control')).toHaveLength(1);
    expect(screen.getByTestId('wf-map-chrome-tl').querySelector('[data-testid="wf-win-control"]')).toBeNull();
    slot.remove();
  });

  it('draws in the map chrome when there is no view around it', async () => {
    await renderTab({}, { slot: null });
    expect(screen.getByTestId('wf-map-chrome-tl').querySelector('[data-testid="wf-win-control"]')).not.toBeNull();
  });
});

describe('MapView: the lens note replaces the landing strip', () => {
  it('states a named-spot lens a door applied, and Clear lifts it', async () => {
    await renderTab({ planHandoff: door({ focusNames: ['Near'], title: 'Moors', caption: '1 spot' }) });
    expect(screen.getByTestId('map-lens-note')).toHaveTextContent('Showing 1 spot');
    await act(async () => { fireEvent.click(screen.getByTestId('map-lens-note-clear')); });
    expect(screen.queryByTestId('map-lens-note')).toBeNull();
  });

  it('draws no note for a door that applied no lens, and never the old landing strip', async () => {
    await renderTab({ planHandoff: door({}) });
    expect(screen.queryByTestId('map-lens-note')).toBeNull();
    expect(screen.queryByTestId('wf-map-breadcrumb')).toBeNull();
  });
});

describe('MapView inside the map view: review round 1', () => {
  it('the window control\'s Escape closes its menu and nothing else: the callout it sits beside stays', async () => {
    const slot = document.createElement('div');
    document.body.appendChild(slot);
    await renderTab({ planHandoff: door({ locationName: 'Near' }) }, { slot });
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    expect(screen.getByTestId('wf-win-pill')).toHaveAttribute('aria-expanded', 'true');

    await act(async () => { fireEvent.keyDown(screen.getByTestId('wf-win-pill'), { key: 'Escape' }); });

    expect(screen.getByTestId('wf-win-pill')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();
    slot.remove();
  });

  it('a press the pane\'s children already answered (default prevented) closes nothing in the pane', async () => {
    await renderTab({ planHandoff: door({ locationName: 'Near' }) });
    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    event.preventDefault();
    await act(async () => { screen.getByTestId('wf-map-pane').dispatchEvent(event); });
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();
    expect(escapeAnswered(event)).toBe(false);
  });

  it('Clear hands focus to the view\'s title when the note unmounts under it', async () => {
    await renderTab({ planHandoff: door({ focusNames: ['Near'] }) });
    await act(async () => { fireEvent.click(screen.getByTestId('map-lens-note-clear')); });
    expect(screen.queryByTestId('map-lens-note')).toBeNull();
    expect(focusTitle).toHaveBeenCalledTimes(1);
  });

  it('Clear with no view around the pane focuses the pane itself, never <body>', async () => {
    let result;
    await act(async () => {
      result = render(
        <MapView
          locations={[NEAR, OTHER]}
          date={TODAY}
          autoEventType="SUNSET"
          forecastDates={[TODAY]}
          heat={HEAT}
          planHandoff={door({ focusNames: ['Near'] })}
        />,
      );
    });
    await act(async () => { fireEvent.click(screen.getByTestId('map-lens-note-clear')); });
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-pane'));
    result.unmount();
  });

  it('republishes its current route each time the view opens, not only when the route changes', async () => {
    const view = await renderTab({}, { open: false });
    expect(route).not.toHaveBeenCalled();
    const element = (open) => (
      <ViewContext slot={null} open={open}>
        <MapView
          locations={[NEAR, OTHER]}
          date={TODAY}
          autoEventType="SUNSET"
          forecastDates={[TODAY]}
          heat={HEAT}
          windowCardsByKey={new Map([[SUNSET_CARD.key, SUNSET_CARD]])}
        />
      </ViewContext>
    );
    await act(async () => { view.rerender(element(true)); });
    expect(route).toHaveBeenCalledTimes(1);
    expect(route).toHaveBeenLastCalledWith({ date: TODAY, eventType: 'SUNSET', locationId: null });
    await act(async () => { view.rerender(element(false)); });
    await act(async () => { view.rerender(element(true)); });
    expect(route).toHaveBeenCalledTimes(2);
  });

  it('hands the arrow keys the window control\'s OWN step, so it moves one window and stops at either end', async () => {
    await renderTab();
    const step = stepRegistered.mock.calls.at(-1)[0];
    expect(header).toHaveBeenLastCalledWith(expect.objectContaining({ title: expect.stringMatching(/sunset/i) }));
    await act(async () => { step(-1); });
    expect(header).toHaveBeenLastCalledWith(expect.objectContaining({ title: expect.stringMatching(/sunrise/i) }));
    await act(async () => { step(-1); });
    expect(header).toHaveBeenLastCalledWith(expect.objectContaining({ title: expect.stringMatching(/sunrise/i) }));
    await act(async () => { step(1); });
    expect(header).toHaveBeenLastCalledWith(expect.objectContaining({ title: expect.stringMatching(/sunset/i) }));
    await act(async () => { step(1); });
    expect(header).toHaveBeenLastCalledWith(expect.objectContaining({ title: expect.stringMatching(/sunset/i) }));
  });

  it('is inert while an older day is loading (the control\'s loading guard)', async () => {
    await renderTab({ olderDayLoading: '2026-01-14' });
    const step = stepRegistered.mock.calls.at(-1)[0];
    header.mockClear();
    await act(async () => { step(-1); });
    await act(async () => { step(1); });
    expect(header).not.toHaveBeenCalledWith(expect.objectContaining({ title: expect.stringMatching(/sunrise/i) }));
  });

  it('at the oldest window ← fetches the next older day instead of stepping (the control\'s own rule)', async () => {
    const onLoadOlderDay = vi.fn();
    await renderTab({
      autoEventType: 'SUNRISE', canLoadOlder: true, olderDayDate: '2026-01-14', onLoadOlderDay,
    });
    const step = stepRegistered.mock.calls.at(-1)[0];
    await act(async () => { step(-1); });
    expect(onLoadOlderDay).toHaveBeenCalledExactlyOnceWith('2026-01-14');
  });

  it('closes the window menu when it steps, as the control\'s own stepper does', async () => {
    await renderTab();
    await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
    expect(screen.getByTestId('wf-win-pill')).toHaveAttribute('aria-expanded', 'true');
    const step = stepRegistered.mock.calls.at(-1)[0];
    await act(async () => { step(-1); });
    expect(screen.getByTestId('wf-win-pill')).toHaveAttribute('aria-expanded', 'false');
  });

  it('does nothing once the control is gone (unmounted pane)', async () => {
    const view = await renderTab();
    const step = stepRegistered.mock.calls.at(-1)[0];
    view.unmount();
    expect(() => step(1)).not.toThrow();
  });

  it('end to end: ← and → on the REAL view header step the real control, and are inert while loading', async () => {
    const mount = async (extra = {}) => {
      await act(async () => {
        render(
          <MapScreen open onClose={vi.fn()} onScrimPress={vi.fn()} onRoute={vi.fn()} titleRef={React.createRef()}>
            <MapView
              locations={[NEAR, OTHER]}
              date={TODAY}
              autoEventType="SUNSET"
              forecastDates={[TODAY]}
              heat={HEAT}
              windowCardsByKey={new Map([[SUNSET_CARD.key, SUNSET_CARD]])}
              {...extra}
            />
          </MapScreen>,
        );
      });
    };
    await mount();
    expect(screen.getByTestId('map-screen-title')).toHaveTextContent(/sunset/i);
    await act(async () => { fireEvent.keyDown(screen.getByTestId('map-screen-title'), { key: 'ArrowLeft' }); });
    expect(screen.getByTestId('map-screen-title')).toHaveTextContent(/sunrise/i);
    await act(async () => { fireEvent.keyDown(screen.getByTestId('map-screen-close'), { key: 'ArrowRight' }); });
    expect(screen.getByTestId('map-screen-title')).toHaveTextContent(/sunset/i);
    cleanup();

    await mount({ olderDayLoading: '2026-01-14' });
    await act(async () => { fireEvent.keyDown(screen.getByTestId('map-screen-title'), { key: 'ArrowLeft' }); });
    expect(screen.getByTestId('map-screen-title')).toHaveTextContent(/sunset/i);
  });
});
