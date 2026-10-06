/**
 * `MapView`'s side of Ask PhotoCast's map linkage (F3, `docs/engineering/ask-photocast-plan.md` §2.7):
 * the context it publishes, the window-follow channel, the picks it appends and marks, the heat dim
 * and the camera's wiring.
 *
 * <p>The harness is `MapViewPlanHandoff.test.jsx`'s own — a minimal `L.Control`, `react-leaflet`
 * reduced to a container, and every child that adds nothing here mocked to a probe that captures its
 * props — so what is asserted is what `MapView` HANDS each child, never a re-derivation. The clock's
 * `Date` is fixed: `isForwardableRow` asks whether a window is over by the real calendar, and the
 * fixture's dates must not rot.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';

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
  const L = {
    icon, divIcon, point, Control, DomEvent,
  };
  return { default: L, ...L };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    addControl: () => {},
    _corner: cornerEl,
  }),
}));

/** The field, as a probe that records the `dim` it is handed. */
const heatLayerCalls = [];
vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: (props) => { heatLayerCalls.push(props); return <div data-testid="map-heat-layer" />; },
}));

const mapLabelsCalls = [];
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => { mapLabelsCalls.push(props); return null; },
}));
const pinsLayerCalls = [];
vi.mock('../components/map/PinsLayer.jsx', () => ({
  default: (props) => { pinsLayerCalls.push(props); return <div data-testid="pins-layer" />; },
}));

/** The camera, as a probe: what `MapView` hands it is the whole of its contract with the map. */
const cameraCalls = [];
vi.mock('../components/map/AskCameraController.jsx', () => ({
  default: (props) => { cameraCalls.push(props); return null; },
}));

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
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';
const TOMORROW = '2026-01-16';

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

const DOOR = {
  source: 'plan', eventType: 'SUNSET', date: TODAY, region: null, minRating: null, limitMinutes: null, locationName: null,
};

/** One pick: the card's own facts for ITS window — a 4★ where the active window's rating is 5★. */
const pick = (over = {}) => ({
  rank: 1,
  locationId: NEAR.id,
  name: 'Near',
  date: TODAY,
  eventType: 'SUNRISE',
  shortWindow: 'Thu AM',
  rating: 4,
  verdict: 'WORTH_IT',
  label: 'Pick 1, Near, Thursday sunrise, 4 stars',
  ...over,
});

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
  pinsLayerCalls.length = 0;
  heatLayerCalls.length = 0;
  cameraCalls.length = 0;
  filtersPopoverCalls.length = 0;
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

const lastLabels = () => mapLabelsCalls.at(-1);
const askSpots = () => lastLabels().spots.filter((s) => s.askPick);
const lastPins = () => pinsLayerCalls.at(-1);

describe('MapView publishes what a question asked from here carries', () => {
  it('Everywhere, with the window on the pill, when no home narrows anything', async () => {
    const onAskContext = vi.fn();

    await renderTab({ onAskContext });

    expect(onAskContext).toHaveBeenLastCalledWith({
      focusRegion: null,
      scoped: false,
      // The scope pool is the area's whether or not it narrows anything; `scoped` is what says so.
      areaNames: ['North East'],
      areaLabel: null,
      window: { date: TODAY, eventType: 'SUNSET', label: 'Tonight sunset' },
    });
  });

  it('"My area" is scoped, and names the regions the scope pool holds', async () => {
    const onAskContext = vi.fn();

    await renderTab({ onAskContext, heat: HEAT_WITH_HOME });

    const facts = onAskContext.mock.lastCall[0];
    expect(facts.scoped).toBe(true);
    expect(facts.areaNames).toEqual(['North East']);
    expect(facts.focusRegion).toBeNull();
  });

  it('an away origin names its own area', async () => {
    const onAskContext = vi.fn();

    await renderTab({ onAskContext, heat: { ...HEAT_WITH_HOME, areaLabel: 'Around Keswick' } });

    expect(onAskContext.mock.lastCall[0].areaLabel).toBe('Around Keswick');
  });

  it('a standing Regions jump is the focused region, whatever the scope segment says', async () => {
    const onAskContext = vi.fn();

    await renderTab({
      onAskContext, heat: HEAT_WITH_HOME, planHandoff: { ...DOOR, region: 'Lake District', nonce: 1 },
    });

    expect(onAskContext.mock.lastCall[0].focusRegion).toBe('Lake District');
  });

  it('a window the briefing did not serve is no window: the chip would name one the server cannot use', async () => {
    const onAskContext = vi.fn();

    // TOMORROW is in the forecast's dates but not in the served windows: a D-13 filler row.
    await renderTab({ onAskContext, date: TOMORROW, forecastDates: [TODAY, TOMORROW] });

    expect(onAskContext.mock.lastCall[0].window).toBeNull();
  });

  it('publishes again only when something it carries changed', async () => {
    const onAskContext = vi.fn();
    await renderTab({ onAskContext });
    const calls = onAskContext.mock.calls.length;

    await rerenderTab({ onAskContext });
    await rerenderTab({ onAskContext, locations: [...LOCATIONS] });

    expect(onAskContext).toHaveBeenCalledTimes(calls);
  });
});

describe('askWindow follows a chosen pick\'s window — on its own channel', () => {
  it('moves the window and reports the date up, once per nonce', async () => {
    const onSelectDate = vi.fn();
    await renderTab({ onSelectDate });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);
    onSelectDate.mockClear();

    await rerenderTab({ onSelectDate, askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 1 } });

    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);
    expect(onSelectDate).toHaveBeenCalledTimes(1);
    expect(onSelectDate).toHaveBeenCalledWith(TODAY, { isNight: false });

    // The same nonce again changes nothing...
    await rerenderTab({ onSelectDate, askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 1 } });
    expect(onSelectDate).toHaveBeenCalledTimes(1);
  });

  it('applies again for a NEW nonce — the same pick chosen twice is two choices', async () => {
    const onSelectDate = vi.fn();
    await renderTab({ onSelectDate, askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 1 } });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);
    // The reader moves the window away with the control...
    await act(async () => { screen.getByTestId('wf-win-next').click(); });
    expect(screen.getByTestId('wf-win-pill')).not.toHaveTextContent(/sunrise/i);
    onSelectDate.mockClear();

    // ...and choosing the SAME pick again (a new nonce, the same window) brings it back.
    await rerenderTab({ onSelectDate, askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 2 } });

    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);
    expect(onSelectDate).toHaveBeenCalledWith(TODAY, { isNight: false });
  });

  it('⚠️ touches no lens, no scope, no floor and no breadcrumb — and a live Plan-door handoff survives it', async () => {
    const door = {
      ...DOOR, minRating: 4, limitMinutes: 150, region: 'Lake District', nonce: 7,
    };
    await renderTab({ planHandoff: door });
    expect(filtersPopoverCalls.at(-1).minStars).toBe(4);
    expect(filtersPopoverCalls.at(-1).driveTimeFilter).toBe(150);
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(false);
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('Lake District');

    await rerenderTab({
      planHandoff: door, askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 1 },
    });

    // The window moved...
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);
    // ...and everything the door carried is exactly as the door left it.
    expect(filtersPopoverCalls.at(-1).minStars).toBe(4);
    expect(filtersPopoverCalls.at(-1).driveTimeFilter).toBe(150);
    expect(filtersPopoverCalls.at(-1).heatArea).toBe(false);
    expect(screen.getByTestId('wf-map-breadcrumb')).toBeInTheDocument();
    expect(screen.getByTestId('wf-map-breadcrumb-carrying')).toHaveTextContent('Lake District');
  });

  it('leaves the reader\'s own rating floor alone when the pick\'s window is another KIND of event', async () => {
    await renderTab();
    await act(async () => { filtersPopoverCalls.at(-1).onSelectMinStars(4); });

    // selectEvRow would reset the floor to the default on a SUNSET → SUNRISE press; a pick must not.
    await rerenderTab({ askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 1 } });

    expect(filtersPopoverCalls.at(-1).minStars).toBe(4);
  });

  it('⚠️ applies a choice ONCE: the answer going and coming back with the same nonce (a refused question) is not a second choice', async () => {
    const onSelectDate = vi.fn();
    const choice = { date: TODAY, eventType: 'SUNRISE', nonce: 4 };
    await renderTab({ onSelectDate, askWindow: choice });
    // The reader moves the window away...
    await act(async () => { screen.getByTestId('wf-win-next').click(); });
    expect(screen.getByTestId('wf-win-pill')).not.toHaveTextContent(/sunrise/i);
    onSelectDate.mockClear();

    // ...a typed question goes out (no answer, so no chosen pick) and is refused (the answer is back).
    await rerenderTab({ onSelectDate, askWindow: null });
    await rerenderTab({ onSelectDate, askWindow: choice });

    expect(screen.getByTestId('wf-win-pill')).not.toHaveTextContent(/sunrise/i);
    expect(onSelectDate).not.toHaveBeenCalled();
  });

  it('⚠️ holds while the pane is not on screen, and applies once it is: a card chosen behind the reader moves nothing yet', async () => {
    const onSelectDate = vi.fn();
    await renderTab({ onSelectDate, paneVisible: false });
    onSelectDate.mockClear();

    await rerenderTab({
      onSelectDate, paneVisible: false, askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 1 },
    });
    expect(onSelectDate).not.toHaveBeenCalled();
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);

    await rerenderTab({
      onSelectDate, paneVisible: true, askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 1 },
    });

    expect(onSelectDate).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);
  });

  it('clears the selected location — no stale callout for another window — and opens none', async () => {
    await renderTab({ planHandoff: { ...DOOR, locationName: 'Near', nonce: 1 } });
    expect(screen.getByTestId('probe-callout-name')).toHaveTextContent('Near');

    await rerenderTab({
      planHandoff: { ...DOOR, locationName: 'Near', nonce: 1 },
      askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 1 },
    });

    expect(screen.queryByTestId('probe-callout')).toBeNull();
  });

  it('a window `App` would refuse is not followed — but the selection is still cleared', async () => {
    const onSelectDate = vi.fn();
    await renderTab({ onSelectDate, planHandoff: { ...DOOR, locationName: 'Near', nonce: 1 } });
    onSelectDate.mockClear();

    await rerenderTab({
      onSelectDate,
      planHandoff: { ...DOOR, locationName: 'Near', nonce: 1 },
      // A window the map has no row for at all.
      askWindow: { date: '2026-02-01', eventType: 'SUNRISE', nonce: 1 },
    });

    expect(onSelectDate).not.toHaveBeenCalled();
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);
    expect(screen.queryByTestId('probe-callout')).toBeNull();
  });

  it('a window that has already passed is not followed either', async () => {
    const onSelectDate = vi.fn();
    vi.setSystemTime(new Date('2026-01-20T10:00:00Z'));
    await renderTab({ onSelectDate, date: TODAY });
    onSelectDate.mockClear();

    await rerenderTab({ onSelectDate, date: TODAY, askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 1 } });

    expect(onSelectDate).not.toHaveBeenCalled();
  });
});

describe('picks on the map', () => {
  it('puts each pick on a spot carrying the PICK\'S OWN window\'s facts, not the active window\'s', async () => {
    await renderTab({ askPicks: [pick()], askAnswerId: 1 });

    const [near] = askSpots();
    expect(near.name).toBe('Near');
    expect(near.askPick).toEqual({
      rank: 1,
      shortWindow: 'Thu AM',
      rating: 4,
      verdict: 'WORTH_IT',
      label: 'Pick 1, Near, Thursday sunrise, 4 stars',
      selected: false,
    });
    // The spot's own rating stays the ACTIVE window's (5★ tonight): everything else reads it.
    expect(near.rating).toBe(5);
  });

  it('⚠️ a pick the reader\'s rating floor would remove is still on the map', async () => {
    // WEAK is 1★ tonight and below the default 3★ floor: with no pick it is not in the pool.
    await renderTab();
    expect(lastLabels().spots.some((s) => s.name === 'Weak')).toBe(false);

    await rerenderTab({
      askPicks: [pick({
        rank: 1, locationId: WEAK.id, name: 'Weak', rating: 4, label: 'Pick 1, Weak, Thursday sunrise, 4 stars',
      })],
      askAnswerId: 1,
    });

    expect(askSpots().map((s) => s.name)).toEqual(['Weak']);
  });

  it('a pick whose location is outside the scope pool is still on the map', async () => {
    await renderTab({ heat: HEAT_WITH_HOME });
    expect(lastLabels().spots.some((s) => s.name === 'Far')).toBe(false);

    await rerenderTab({
      heat: HEAT_WITH_HOME,
      askPicks: [pick({
        rank: 2, locationId: FAR.id, name: 'Far', label: 'Pick 2, Far, Thursday sunrise, 4 stars',
      })],
      askAnswerId: 1,
    });

    expect(askSpots().map((s) => s.name)).toEqual(['Far']);
  });

  it('joins a pick to its location by id first and name second', async () => {
    await renderTab({
      askPicks: [pick({ locationId: 999, name: 'Far', label: 'Pick 1, Far, Thursday sunrise, 4 stars' })],
      askAnswerId: 1,
    });

    expect(askSpots().map((s) => s.name)).toEqual(['Far']);
  });

  it('⚠️ joins by id FIRST: a pick whose id and name name different places lands on the id\'s', async () => {
    await renderTab({
      askPicks: [pick({ locationId: NEAR.id, name: 'Far', label: 'Pick 1, Far, Thursday sunrise, 4 stars' })],
      askAnswerId: 1,
    });

    expect(askSpots().map((s) => s.name)).toEqual(['Near']);
  });

  it('a pick the catalogue does not hold is skipped, with no spot, no camera point and no dimming', async () => {
    await renderTab({
      askPicks: [pick({ locationId: 404, name: 'Nowhere' })],
      askAnswerId: 1,
    });

    expect(askSpots()).toEqual([]);
    expect(cameraCalls.at(-1).points).toEqual([]);
    expect(heatLayerCalls.at(-1).dim).toBe(1);
  });

  it('two picks at one location (the server cannot send this) keep the lowest rank', async () => {
    await renderTab({
      askPicks: [
        pick({ rank: 2, shortWindow: 'Thu PM' }),
        pick({ rank: 1, shortWindow: 'Thu AM' }),
      ],
      askAnswerId: 1,
    });

    expect(askSpots()).toHaveLength(1);
    expect(askSpots()[0].askPick.rank).toBe(1);
  });

  it('marks the chosen pick as selected', async () => {
    await renderTab({
      askPicks: [pick(), pick({
        rank: 2, locationId: FAR.id, name: 'Far', label: 'Pick 2, Far, Thursday sunrise, 4 stars',
      })],
      askSelectedRank: 2,
      askAnswerId: 1,
    });

    expect(askSpots().map((s) => [s.name, s.askPick.selected])).toEqual([['Near', false], ['Far', true]]);
  });

  it('hands the chip\'s way of choosing a pick to the label layer', async () => {
    const onSelectAskPick = vi.fn();

    await renderTab({ askPicks: [pick()], askAnswerId: 1, onSelectAskPick });

    expect(lastLabels().onSelectAskPick).toBe(onSelectAskPick);
  });

  it('⚠️ with no answer, nothing carries a pick and nothing is dimmed', async () => {
    await renderTab();

    expect(askSpots()).toEqual([]);
    expect(heatLayerCalls.at(-1).dim).toBe(1);
  });
});

describe('Pins view carries the picks too', () => {
  it('hands the pins layer the same pick spots, and the way to choose one', async () => {
    const onSelectAskPick = vi.fn();
    await renderTab({ askPicks: [pick()], askAnswerId: 1, onSelectAskPick });
    await act(async () => { screen.getByTestId('wf-map-view-pins').click(); });

    expect(screen.getByTestId('pins-layer')).toBeInTheDocument();
    expect(lastPins().spots.filter((s) => s.askPick).map((s) => s.name)).toEqual(['Near']);
    expect(lastPins().onSelectAskPick).toBe(onSelectAskPick);
  });
});

describe('the heat field steps back while picks are on the map', () => {
  it('passes dim 0.36 to the field, and 1 once the answer is cleared', async () => {
    await renderTab({ askPicks: [pick()], askAnswerId: 1 });
    expect(heatLayerCalls.at(-1).dim).toBe(0.36);

    await rerenderTab({ askPicks: [], askAnswerId: null });

    expect(heatLayerCalls.at(-1).dim).toBe(1);
  });
});

describe('the camera is handed the picks and the choice', () => {
  const TWO = [pick(), pick({
    rank: 2, locationId: FAR.id, name: 'Far', label: 'Pick 2, Far, Thursday sunrise, 4 stars',
  })];

  it('frames the points of the picks that reached the map, for the answer on screen', async () => {
    await renderTab({ askPicks: TWO, askAnswerId: 5 });

    const camera = cameraCalls.at(-1);
    expect(camera.answerId).toBe(5);
    expect(camera.points.map((p) => [p.lat, p.lng])).toEqual([[55.61, -1.71], [54.45, -3.0]]);
    expect(camera.selection).toBeNull();
    expect(camera.active).toBe(true);
  });

  it('flies to a pick only for a CHOICE — its nonce is the window channel\'s', async () => {
    await renderTab({
      askPicks: TWO, askAnswerId: 5, askSelectedRank: 2, askWindow: { date: TODAY, eventType: 'SUNRISE', nonce: 9 },
    });

    expect(cameraCalls.at(-1).selection).toEqual({ lat: 54.45, lng: -3.0, nonce: 9 });
  });

  it('has no selection when a pick is selected but no choice was made (a rank with no nonce)', async () => {
    await renderTab({ askPicks: TWO, askAnswerId: 5, askSelectedRank: 2, askWindow: null });

    expect(cameraCalls.at(-1).selection).toBeNull();
  });

  it('holds while the pane is not on screen with nothing modal over it (`paneVisible`)', async () => {
    await renderTab({ askPicks: TWO, askAnswerId: 5, paneVisible: false });

    expect(cameraCalls.at(-1).active).toBe(false);
  });

  it('is not mounted on the frozen overlay', async () => {
    await renderTab({ overlayMode: true, askPicks: TWO, askAnswerId: 5 });

    expect(cameraCalls).toEqual([]);
  });
});
