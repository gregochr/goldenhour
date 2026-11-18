/**
 * `MapView`'s side of Find (find-and-favourites-plan.md F1): a place or region picked in Find on the Map tab.
 *
 * <p>The harness is `MapViewAsk.test.jsx`'s own — a minimal `L.Control`, `react-leaflet` reduced to a container,
 * and every child that adds nothing here mocked to a probe that captures its props — so what is asserted is what
 * `MapView` HANDS each child and what the Leaflet map was asked to do, never a re-derivation. The clock's `Date`
 * is fixed: `isForwardableRow` asks whether a window is over by the real calendar.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen,
} from '@testing-library/react';

let cornerEl;
const flyToSpy = vi.hoisted(() => vi.fn());
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
  const L = {
    icon, divIcon, point, Control, DomEvent,
  };
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
    flyTo: flyToSpy,
    fitBounds: () => {},
    addControl: () => {},
    _corner: cornerEl,
  }),
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: () => <div data-testid="map-heat-layer" />,
}));

const mapLabelsCalls = [];
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => { mapLabelsCalls.push(props); return null; },
}));
vi.mock('../components/map/AskCameraController.jsx', () => ({ default: () => null }));

vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => (
    <div data-testid="probe-callout">
      <span data-testid="probe-callout-name">{props.location?.name ?? ''}</span>
    </div>
  ),
}));

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
import PlanSearch from '../components/PlanSearch.jsx';
import { reachLensStub } from './reachLensStub.js';

const TODAY = '2026-01-15';

const forecast = (rating) => ({
  sunset: {
    rating, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60,
  },
  sunrise: {
    rating, solarEventTime: `${TODAY}T08:12:00`, fierySkyPotential: 70, goldenHourPotential: 60,
  },
});
const loc = (id, name, lat, lon, regionName, rating) => ({
  id, name, lat, lon, regionName, locationType: ['LANDSCAPE'], forecastsByDate: new Map([[TODAY, forecast(rating)]]),
});

const NEAR = loc(1, 'Near', 55.61, -1.71, 'North East', 5);
const WEAK = loc(2, 'Weak', 55.62, -1.72, 'North East', 1);
const FAR = loc(3, 'Far', 54.45, -3.0, 'Lake District', 5);
const LOCATIONS = [NEAR, WEAK, FAR];

const spotOf = (l) => ({
  id: l.id, name: l.name, lat: l.lat, lng: l.lon, regionName: l.regionName, rid: l.regionName, bortleClass: 4, r: [5],
});
const AREA_SPOTS = [spotOf(NEAR), spotOf(WEAK)];
const ALL_SPOTS = [...AREA_SPOTS, spotOf(FAR)];

const HEAT = {
  enabled: true,
  hasHome: false,
  spots: ALL_SPOTS,
  areaSpots: AREA_SPOTS,
  pointsByKey: new Map([[`${TODAY}:SUNSET`, []], [`${TODAY}:SUNRISE`, []]]),
  windows: [
    {
      key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 5, conf: 1,
    },
    {
      key: `${TODAY}:SUNRISE`, date: TODAY, targetType: 'SUNRISE', label: 'Tonight sunrise', time: '08:12', bestRating: 5, conf: 1,
    },
  ],
  areaBounds: [[54.3, -3.4], [55.7, -1.3]],
  catalogueBounds: [[53.9, -3.4], [55.7, -1.3]],
};
const HEAT_WITH_HOME = { ...HEAT, hasHome: true };

const baseProps = (over = {}) => ({
  locations: LOCATIONS,
  date: TODAY,
  autoEventType: 'SUNSET',
  forecastDates: [TODAY],
  heat: HEAT,
  ...over,
});

let view;
async function renderTab(over = {}) {
  await act(async () => { view = render(<MapView {...baseProps(over)} />); });
  return view;
}
async function rerenderTab(over = {}) {
  await act(async () => { view.rerender(<MapView {...baseProps(over)} />); });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T10:00:00Z`));
  localStorage.clear();
  mapLabelsCalls.length = 0;
  filtersPopoverCalls.length = 0;
  flyToSpy.mockClear();
  cornerEl = document.createElement('div');
  cornerEl.className = 'leaflet-bottom leaflet-right';
  document.body.appendChild(cornerEl);
});

afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
  cornerEl.remove();
  vi.clearAllMocks();
});

const pickOf = (over = {}) => ({
  kind: 'location', id: WEAK.id, name: 'Weak', nonce: 1, ...over,
});
const nearPick = (over = {}) => pickOf({ id: NEAR.id, name: 'Near', ...over });
/** The place names the label layer was last handed, the selected place included. */
const lastLabelNames = () => (mapLabelsCalls.at(-1)?.spots ?? []).map((s) => s.name);
const calloutName = () => screen.queryByTestId('probe-callout-name')?.textContent ?? null;
const note = () => screen.queryByTestId('map-find-note');

describe('a place picked in Find', () => {
  it('flies to it and opens its callout, once per nonce, leaving the reader\'s window alone', async () => {
    await renderTab();
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);

    await rerenderTab({ findPick: nearPick() });

    expect(calloutName()).toBe('Near');
    expect(flyToSpy).toHaveBeenCalledTimes(1);
    expect(flyToSpy.mock.calls[0][0]).toEqual([NEAR.lat, NEAR.lon]);
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);
    // Nothing needed relaxing, so nothing is said.
    expect(note()).toBeNull();

    // The same nonce again changes nothing.
    await rerenderTab({ findPick: nearPick() });
    expect(flyToSpy).toHaveBeenCalledTimes(1);
  });

  it('resolves a place by name when the pick carries no id', async () => {
    await renderTab();
    await rerenderTab({ findPick: nearPick({ id: null }) });
    expect(calloutName()).toBe('Near');
  });

  it('does nothing for a place the roster does not hold, and for one with no coordinates', async () => {
    await renderTab();
    await rerenderTab({ findPick: pickOf({ id: 999, name: 'Nowhere' }) });
    expect(calloutName()).toBeNull();
    await rerenderTab({
      findPick: pickOf({ id: 5, name: 'Lost', nonce: 2 }),
      locations: [...LOCATIONS, { ...NEAR, id: 5, name: 'Lost', lat: null, lon: null }],
    });
    expect(calloutName()).toBeNull();
    expect(flyToSpy).not.toHaveBeenCalled();
  });

  it('⚠️ relaxes the star floor for a place it hides, in memory only, and says so', async () => {
    localStorage.setItem('mapFilterMinStars', '3');
    await renderTab();
    expect(filtersPopoverCalls.at(-1).minStars).toBe(3);

    await rerenderTab({ findPick: pickOf() });

    expect(filtersPopoverCalls.at(-1).minStars).toBe(1);
    expect(calloutName()).toBe('Weak');
    expect(note()).toHaveTextContent('Showing Weak: the 3★ floor relaxed for it.');
    // The saved floor is the reader's own and is never written by a pick: a reload brings it back.
    expect(localStorage.getItem('mapFilterMinStars')).toBe('3');
    expect(screen.getByTestId('map-status')).toHaveTextContent('the 3★ floor relaxed');
  });

  it('relaxes nothing for a place the floor already shows', async () => {
    await renderTab();
    await rerenderTab({ findPick: nearPick() });
    expect(filtersPopoverCalls.at(-1).minStars).toBe(3);
    expect(note()).toBeNull();
  });

  it('⚠️ relaxes the drive-time filter for a place beyond it on the Map ALONE: the shared lens and storage are untouched', async () => {
    const lens = reachLensStub(45);
    const reachById = new Map([[NEAR.id, { driveMinutes: 20 }], [FAR.id, { driveMinutes: 200 }]]);
    const props = { reachLens: lens, reachMeasured: true, reachById };
    await renderTab(props);
    const before = JSON.stringify({ ...localStorage });
    const names = () => lastLabelNames();
    expect(names()).not.toContain('Far');

    await rerenderTab({ ...props, findPick: pickOf({ id: FAR.id, name: 'Far' }) });

    expect(lens.selectTier).not.toHaveBeenCalled();
    expect(JSON.stringify({ ...localStorage })).toBe(before);
    expect(filtersPopoverCalls.at(-1).driveTimeFilter).toBe(45);
    expect(note()).toHaveTextContent('the drive-time filter');
    expect(names()).toContain('Far');

    // The override lapses with the selection: the reader's own tier is back for everything else.
    await rerenderTab({ ...props, findPick: nearPick({ nonce: 2 }) });
    expect(names()).not.toContain('Far');
  });

  it('leaves the drive-time filter alone for a place inside it', async () => {
    const lens = reachLensStub(45);
    const reachById = new Map([[NEAR.id, { driveMinutes: 20 }]]);
    await renderTab({ reachLens: lens, reachMeasured: true, reachById });

    await rerenderTab({
      reachLens: lens, reachMeasured: true, reachById, findPick: nearPick(),
    });

    expect(lens.selectTier).not.toHaveBeenCalled();
  });

  it('relaxes the place-type filter by adding the place\'s own type, and dark sky by switching it off', async () => {
    await renderTab();
    await act(async () => { filtersPopoverCalls.at(-1).onToggleType('SEASCAPE'); });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleDarkSky(); });
    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(true);
    expect(filtersPopoverCalls.at(-1).activeTypeFilters.has('SEASCAPE')).toBe(true);

    await rerenderTab({ findPick: nearPick() });

    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(false);
    const types = filtersPopoverCalls.at(-1).activeTypeFilters;
    expect(types.has('LANDSCAPE')).toBe(true);
    expect(types.has('SEASCAPE')).toBe(true);
    expect(note()).toHaveTextContent('the place-type filter, dark sky');
  });

  it('keeps dark sky on for a place that is dark enough', async () => {
    const dark = { ...NEAR, bortleClass: 2 };
    await renderTab({ locations: [dark, WEAK, FAR] });
    await act(async () => { filtersPopoverCalls.at(-1).onToggleDarkSky(); });

    await rerenderTab({ locations: [dark, WEAK, FAR], findPick: nearPick() });

    expect(filtersPopoverCalls.at(-1).darkSkyFilter).toBe(true);
  });

  it('⚠️ frames the region of a place outside My area (widening the scope) instead of flying to it', async () => {
    await renderTab({ heat: HEAT_WITH_HOME });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(true);

    await rerenderTab({ heat: HEAT_WITH_HOME, findPick: pickOf({ id: FAR.id, name: 'Far' }) });

    expect(calloutName()).toBe('Far');
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(false);
    expect(note()).toHaveTextContent('the My area scope');
    expect(flyToSpy).not.toHaveBeenCalled();
  });

  it('flies to a place inside My area without touching the scope', async () => {
    await renderTab({ heat: HEAT_WITH_HOME });
    await rerenderTab({ heat: HEAT_WITH_HOME, findPick: nearPick() });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(true);
    expect(flyToSpy).toHaveBeenCalledTimes(1);
  });

  it('the note can be dismissed, and goes with the selection', async () => {
    await renderTab();
    await rerenderTab({ findPick: pickOf() });
    expect(note()).not.toBeNull();

    await act(async () => { screen.getByTestId('map-find-note-dismiss').click(); });
    expect(note()).toBeNull();

    // Picking the same place again has nothing left to relax (the floor is already down), so nothing is said;
    // and picking another place takes any note away with the selection.
    await rerenderTab({ findPick: pickOf({ nonce: 2 }) });
    expect(note()).toBeNull();
    await rerenderTab({ findPick: nearPick({ nonce: 3 }) });
    expect(calloutName()).toBe('Near');
    expect(note()).toBeNull();
  });

  it('waits for the pane to be on screen, and applies the moment it is', async () => {
    await renderTab();
    await rerenderTab({ paneVisible: false, findPick: nearPick() });
    expect(calloutName()).toBeNull();
    expect(flyToSpy).not.toHaveBeenCalled();

    await rerenderTab({ paneVisible: true, findPick: nearPick() });

    expect(calloutName()).toBe('Near');
  });

  it('⚠️ a pick already in flight when the map mounts is spent: it is not replayed on the first visit', async () => {
    await renderTab({ findPick: nearPick() });
    expect(calloutName()).toBeNull();
    expect(flyToSpy).not.toHaveBeenCalled();
  });
});

describe('a region picked in Find', () => {
  it('is the Regions picker\'s own jump: a frame outside My area widens the scope, and nothing moves the origin', async () => {
    await renderTab({ heat: HEAT_WITH_HOME });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(true);

    await rerenderTab({ heat: HEAT_WITH_HOME, findPick: { kind: 'region', name: 'Lake District', nonce: 1 } });

    expect(filtersPopoverCalls.at(-1).heatArea).toBe(false);
  });

  it('does nothing for a region the map holds no spots for', async () => {
    await renderTab({ heat: HEAT_WITH_HOME });
    await rerenderTab({ heat: HEAT_WITH_HOME, findPick: { kind: 'region', name: 'Cornwall', nonce: 1 } });
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(true);
  });
});

describe('Find opening closes the map\'s menus', () => {
  it('takes the Filters popover down, and does not reopen it when Find closes', async () => {
    await renderTab();
    await act(async () => { filtersPopoverCalls.at(-1).onOpenChange(true); });
    expect(filtersPopoverCalls.at(-1).open).toBe(true);

    await rerenderTab({ findOpen: true });
    expect(filtersPopoverCalls.at(-1).open).toBe(false);

    await rerenderTab({ findOpen: false });
    expect(filtersPopoverCalls.at(-1).open).toBe(false);
  });
});

describe('⚠️ one Escape closes only Find over a selected callout', () => {
  it('the Map\'s own Escape rules stand down under Find\'s modal, and the callout survives', async () => {
    const onClose = vi.fn();
    const search = (
      <PlanSearch windows={[]} regions={[]} locations={[]} onClose={onClose} onPickWindow={vi.fn()} />
    );
    // Mounted as a fragment from the start, so opening Find is a sibling arriving and MapView is not remounted.
    const tree = (props, withFind) => (
      <><MapView {...baseProps(props)} />{withFind ? search : null}</>
    );
    await act(async () => { view = render(tree({}, false)); });
    await act(async () => { view.rerender(tree({ findPick: nearPick() }, false)); });
    expect(calloutName()).toBe('Near');

    // Find opens over the selection, as a sibling of the pane in the shell's tree.
    await act(async () => { view.rerender(tree({ findPick: nearPick() }, true)); });
    const field = await screen.findByTestId('plan-search-input');
    await act(async () => { fireEvent.keyDown(field, { key: 'Escape' }); });

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(calloutName()).toBe('Near');
  });
});
