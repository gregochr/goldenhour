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

const h = vi.hoisted(() => ({
  flyTo: vi.fn(),
  corner: null,
  labels: [],
  filters: [],
  inert: () => ({ default: () => null }),
  // A data endpoint that answers "nothing yet", one per name.
  empty: (...names) => Object.fromEntries(names.map((name) => [name, vi.fn().mockResolvedValue([])])),
}));

// Only what MapView reads of Leaflet: a Control that mounts its own element, and the three constructors.
vi.mock('leaflet', () => {
  function Control() {}
  Control.prototype.addTo = function addTo(map) {
    this._container = this.onAdd(map);
    map._corner.appendChild(this._container);
    return this;
  };
  Control.prototype.remove = function remove() {
    this._container?.remove();
    return this;
  };
  const L = {
    icon: () => ({}),
    divIcon: (options) => ({ options }),
    point: (x, y) => ({ x, y }),
    Control,
    DomEvent: { disableClickPropagation() {}, disableScrollPropagation() {} },
  };
  return { default: L, ...L };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));
vi.mock('react-leaflet', () => {
  const noop = () => {};
  const none = () => null;
  const wrap = ({ children }) => <div>{children}</div>;
  return {
    MapContainer: wrap,
    Popup: wrap,
    TileLayer: none,
    Polyline: none,
    useMapEvents: none,
    useMap: () => ({
      eachLayer: noop,
      once: noop,
      off: noop,
      fitBounds: noop,
      addControl: noop,
      getZoom: () => 9,
      getContainer: () => ({ clientHeight: 500 }),
      flyTo: h.flyTo,
      _corner: h.corner,
    }),
  };
});

// Children that add nothing here: inert, or a probe that records what MapView handed it.
vi.mock('../components/MapHeatLayer.jsx', h.inert);
vi.mock('../components/map/AskCameraController.jsx', h.inert);
vi.mock('../components/InfoTip.jsx', h.inert);
vi.mock('../components/AuroraViewlineOverlay.jsx', h.inert);
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <>{children}</> }));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: (props) => { h.labels.push(props); return null; } }));
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: ({ location }) => <b data-testid="probe-callout-name">{location?.name}</b>,
}));
vi.mock('../components/map/FiltersPopover.jsx', () => ({
  default: (props) => { h.filters.push(props); return null; },
  DRIVE_TIME_TIERS: [[0, 'Any'], [45, '45 min']],
}));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  ...h.empty('getAuroraLocations', 'getAuroraForecastResults', 'getAuroraForecastAvailableDates'),
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
}));
vi.mock('../api/astroApi.js', () => h.empty('getAstroConditions', 'getAstroAvailableDates'));
vi.mock('../api/travelDayApi.js', () => h.empty('fetchTravelDayRanges'));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));

import MapView from '../components/MapView.jsx';
import PlanSearch from '../components/PlanSearch.jsx';
import { reachLensStub } from './reachLensStub.js';

const TODAY = '2026-01-15';

/** Three places, one per case: shown by the floor, hidden by it, and outside the planning area. */
const PLACES = [
  ['Near', 55.61, -1.71, 'North East', 5],
  ['Weak', 55.62, -1.72, 'North East', 1],
  ['Far', 54.45, -3.0, 'Lake District', 5],
];
const [NEAR, WEAK, FAR] = PLACES.map(([name, lat, lon, regionName, rating], i) => {
  const day = (time) => ({
    rating, solarEventTime: `${TODAY}T${time}:00`, fierySkyPotential: 70, goldenHourPotential: 60,
  });
  return {
    id: i + 1,
    name,
    lat,
    lon,
    regionName,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map([[TODAY, { sunset: day('16:12'), sunrise: day('08:12') }]]),
  };
});
const LOCATIONS = [NEAR, WEAK, FAR];

const heatFor = (hasHome) => {
  const spot = (l) => ({
    id: l.id, name: l.name, lat: l.lat, lng: l.lon, regionName: l.regionName, rid: l.regionName, bortleClass: 4, r: [5],
  });
  const windows = ['SUNSET', 'SUNRISE'].map((targetType) => ({
    key: `${TODAY}:${targetType}`,
    date: TODAY,
    targetType,
    label: `Tonight ${targetType.toLowerCase()}`,
    time: targetType === 'SUNSET' ? '16:12' : '08:12',
    bestRating: 5,
    conf: 1,
  }));
  return {
    enabled: true,
    hasHome,
    spots: LOCATIONS.map(spot),
    areaSpots: [NEAR, WEAK].map(spot),
    pointsByKey: new Map(windows.map((w) => [w.key, []])),
    windows,
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[53.9, -3.4], [55.7, -1.3]],
  };
};
const HEAT = heatFor(false);
const HEAT_WITH_HOME = heatFor(true);

let view;
/** MapView with the fixture's props, mounted the first time and updated after: `over` is what a test changes. */
const show = async (over = {}, sibling = null) => {
  // A fragment from the start, so a sibling arriving later (Find, in the shell's tree) never remounts the map.
  const ui = (
    <>
      <MapView
        locations={LOCATIONS}
        date={TODAY}
        autoEventType="SUNSET"
        forecastDates={[TODAY]}
        heat={HEAT}
        {...over}
      />
      {sibling}
    </>
  );
  await act(async () => { if (view) view.rerender(ui); else view = render(ui); });
};
const renderTab = show;
const rerenderTab = show;

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T10:00:00Z`));
  localStorage.clear();
  h.labels.length = 0;
  h.filters.length = 0;
  h.corner = document.body.appendChild(document.createElement('div'));
  h.corner.className = 'leaflet-bottom leaflet-right';
  view = null;
});

afterEach(() => {
  view?.unmount();
  vi.useRealTimers();
  localStorage.clear();
  h.corner.remove();
  vi.clearAllMocks();
});

const pickOf = (over = {}) => ({
  kind: 'location', id: WEAK.id, name: 'Weak', nonce: 1, ...over,
});
const nearPick = (over = {}) => pickOf({ id: NEAR.id, name: 'Near', ...over });
/** The place names the label layer was last handed, the selected place included. */
const lastLabelNames = () => (h.labels.at(-1)?.spots ?? []).map((s) => s.name);
const calloutName = () => screen.queryByTestId('probe-callout-name')?.textContent ?? null;
const note = () => screen.queryByTestId('map-find-note');

describe('a place picked in Find', () => {
  it('flies to it and opens its callout, once per nonce, leaving the reader\'s window alone', async () => {
    await renderTab();
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);

    await rerenderTab({ findPick: nearPick() });

    expect(calloutName()).toBe('Near');
    expect(h.flyTo).toHaveBeenCalledTimes(1);
    expect(h.flyTo.mock.calls[0][0]).toEqual([NEAR.lat, NEAR.lon]);
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);
    // Nothing needed relaxing, so nothing is said.
    expect(note()).toBeNull();

    // The same nonce again changes nothing.
    await rerenderTab({ findPick: nearPick() });
    expect(h.flyTo).toHaveBeenCalledTimes(1);
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
    expect(h.flyTo).not.toHaveBeenCalled();
  });

  it('⚠️ relaxes the star floor for a place it hides, in memory only, and says so', async () => {
    localStorage.setItem('mapFilterMinStars', '3');
    await renderTab();
    expect(h.filters.at(-1).minStars).toBe(3);

    await rerenderTab({ findPick: pickOf() });

    expect(h.filters.at(-1).minStars).toBe(1);
    expect(calloutName()).toBe('Weak');
    expect(note()).toHaveTextContent('Showing Weak: the 3★ floor relaxed for it.');
    // The saved floor is the reader's own and is never written by a pick: a reload brings it back.
    expect(localStorage.getItem('mapFilterMinStars')).toBe('3');
    expect(screen.getByTestId('map-status')).toHaveTextContent('the 3★ floor relaxed');
  });

  it('relaxes nothing for a place the floor already shows', async () => {
    await renderTab();
    await rerenderTab({ findPick: nearPick() });
    expect(h.filters.at(-1).minStars).toBe(3);
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
    expect(h.filters.at(-1).driveTimeFilter).toBe(45);
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
    await act(async () => { h.filters.at(-1).onToggleType('SEASCAPE'); });
    await act(async () => { h.filters.at(-1).onToggleDarkSky(); });
    expect(h.filters.at(-1).darkSkyFilter).toBe(true);
    expect(h.filters.at(-1).activeTypeFilters.has('SEASCAPE')).toBe(true);

    await rerenderTab({ findPick: nearPick() });

    expect(h.filters.at(-1).darkSkyFilter).toBe(false);
    const types = h.filters.at(-1).activeTypeFilters;
    expect(types.has('LANDSCAPE')).toBe(true);
    expect(types.has('SEASCAPE')).toBe(true);
    expect(note()).toHaveTextContent('the place-type filter, dark sky');
  });

  it('keeps dark sky on for a place that is dark enough', async () => {
    const dark = { ...NEAR, bortleClass: 2 };
    await renderTab({ locations: [dark, WEAK, FAR] });
    await act(async () => { h.filters.at(-1).onToggleDarkSky(); });

    await rerenderTab({ locations: [dark, WEAK, FAR], findPick: nearPick() });

    expect(h.filters.at(-1).darkSkyFilter).toBe(true);
  });

  it('⚠️ frames the region of a place outside My area (widening the scope) instead of flying to it', async () => {
    await renderTab({ heat: HEAT_WITH_HOME });
    expect(h.filters.at(-1).heatArea).toBe(true);

    await rerenderTab({ heat: HEAT_WITH_HOME, findPick: pickOf({ id: FAR.id, name: 'Far' }) });

    expect(calloutName()).toBe('Far');
    expect(h.filters.at(-1).heatArea).toBe(false);
    expect(note()).toHaveTextContent('the My area scope');
    expect(h.flyTo).not.toHaveBeenCalled();
  });

  it('flies to a place inside My area without touching the scope', async () => {
    await renderTab({ heat: HEAT_WITH_HOME });
    await rerenderTab({ heat: HEAT_WITH_HOME, findPick: nearPick() });
    expect(h.filters.at(-1).heatArea).toBe(true);
    expect(h.flyTo).toHaveBeenCalledTimes(1);
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
    expect(h.flyTo).not.toHaveBeenCalled();

    await rerenderTab({ paneVisible: true, findPick: nearPick() });

    expect(calloutName()).toBe('Near');
  });

  it('⚠️ a pick already in flight when the map mounts is spent: it is not replayed on the first visit', async () => {
    await renderTab({ findPick: nearPick() });
    expect(calloutName()).toBeNull();
    expect(h.flyTo).not.toHaveBeenCalled();
  });
});

describe('a region picked in Find', () => {
  it('is the Regions picker\'s own jump: a frame outside My area widens the scope, and nothing moves the origin', async () => {
    await renderTab({ heat: HEAT_WITH_HOME });
    expect(h.filters.at(-1).heatArea).toBe(true);

    await rerenderTab({ heat: HEAT_WITH_HOME, findPick: { kind: 'region', name: 'Lake District', nonce: 1 } });

    expect(h.filters.at(-1).heatArea).toBe(false);
  });

  it('does nothing for a region the map holds no spots for', async () => {
    await renderTab({ heat: HEAT_WITH_HOME });
    await rerenderTab({ heat: HEAT_WITH_HOME, findPick: { kind: 'region', name: 'Cornwall', nonce: 1 } });
    expect(h.filters.at(-1).heatArea).toBe(true);
  });
});

describe('Find opening closes the map\'s menus', () => {
  it('takes the Filters popover down, and does not reopen it when Find closes', async () => {
    await renderTab();
    await act(async () => { h.filters.at(-1).onOpenChange(true); });
    expect(h.filters.at(-1).open).toBe(true);

    await rerenderTab({ findOpen: true });
    expect(h.filters.at(-1).open).toBe(false);

    await rerenderTab({ findOpen: false });
    expect(h.filters.at(-1).open).toBe(false);
  });
});

describe('⚠️ one Escape closes only Find over a selected callout', () => {
  it('the Map\'s own Escape rules stand down under Find\'s modal, and the callout survives', async () => {
    const onClose = vi.fn();
    const search = (
      <PlanSearch windows={[]} regions={[]} locations={[]} onClose={onClose} onPickWindow={vi.fn()} />
    );
    await show();
    await show({ findPick: nearPick() });
    expect(calloutName()).toBe('Near');

    // Find opens over the selection, as a sibling of the pane in the shell's tree.
    await show({ findPick: nearPick() }, search);
    const field = await screen.findByTestId('plan-search-input');
    await act(async () => { fireEvent.keyDown(field, { key: 'Escape' }); });

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(calloutName()).toBe('Near');
  });
});
