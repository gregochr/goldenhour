/**
 * The phone peek sheet's Ask states, cell by cell — `docs/engineering/ask-photocast-plan.md` §2.7's
 * phone state table (F4), asserted at the `MapView` level because every cell is a decision `MapView`
 * makes about its one gate, `openMapMenu`: ONE test per cell of the table, and the cells the table
 * leaves silent (a map touch while an answer is still being fetched) named as such.
 *
 * <p>The sheet's state is derived — "minimised" is a settled answer with nothing open — so every
 * assertion here is on what the READER can see: the sheet's `data-ask` (the attribute the stylesheet's
 * heights key on), the `--psh` the pane publishes, the three buttons, the answer's line, the callout.
 * The conversation is Ask state, stood in for by a mutable `useAsk` value, and the provider's own
 * reaction to a "clear" is simulated by the test (a rerender with the phase the provider would give).
 *
 * <p>The harness is `MapViewAsk.test.jsx`'s own (a container for `react-leaflet`, every child that adds
 * nothing mocked to a probe), with the phone on and `useMapEvents` collecting what the sheet's map-touch
 * listener registers — the only way to fire a Leaflet event without a map.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen,
} from '@testing-library/react';

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
  const DomEvent = { disableClickPropagation: () => {}, disableScrollPropagation: () => {} };
  const L = {
    icon, divIcon, point, Control, DomEvent,
  };
  return { default: L, ...L };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

/** Every handlers object a `useMapEvents` caller registered — see `touchHandlers`. */
let mapEventHandlers = [];
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: (handlers) => { mapEventHandlers.push(handlers); return null; },
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

vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div data-testid="map-heat-layer" /> }));
const mapLabelsCalls = [];
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => { mapLabelsCalls.push(props); return null; },
}));
vi.mock('../components/map/PinsLayer.jsx', () => ({ default: () => null }));
const cameraCalls = [];
vi.mock('../components/map/AskCameraController.jsx', () => ({
  default: (props) => { cameraCalls.push(props); return null; },
}));
const calloutCalls = [];
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => {
    calloutCalls.push(props);
    return <div data-testid="mock-callout">{props.location?.name ?? ''}</div>;
  },
}));
const filtersPopoverCalls = [];
vi.mock('../components/map/FiltersPopover.jsx', () => ({
  default: (props) => { filtersPopoverCalls.push(props); return null; },
  DRIVE_TIME_TIERS: [[0, 'Any'], [45, '45 min'], [90, '1h 30'], [150, '2h 30']],
}));
// The conversation is its own component's business (and reads the briefing); here it is a marker.
vi.mock('../components/ask/AskConversation.jsx', () => ({
  default: () => <div data-testid="stub-conversation" />,
}));

let mockAsk;
vi.mock('../context/AskContext.jsx', () => ({ useAsk: () => mockAsk }));

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
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn(() => Promise.resolve({})) }));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: () => null }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';

const TODAY = '2026-01-15';

const forecast = {
  sunset: {
    rating: 5, solarEventTime: `${TODAY}T16:12:00`, fierySkyPotential: 70, goldenHourPotential: 60,
  },
  sunrise: {
    rating: 5, solarEventTime: `${TODAY}T08:12:00`, fierySkyPotential: 70, goldenHourPotential: 60,
  },
};
const NEAR = {
  id: 1, name: 'Near', lat: 55.61, lon: -1.71, regionName: 'North East', locationType: ['LANDSCAPE'], forecastsByDate: new Map([[TODAY, forecast]]),
};
const SPOT = {
  id: 1, name: 'Near', lat: 55.61, lng: -1.71, regionName: 'North East', rid: 'North East', bortleClass: 4, r: [5],
};
const HEAT = {
  enabled: true,
  hasHome: false,
  spots: [SPOT],
  areaSpots: [SPOT],
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

const CARDS = [1, 2, 3].map((rank) => ({
  rank,
  locationId: 1,
  name: `Spot ${rank}`,
  date: TODAY,
  targetType: 'SUNSET',
  shortWindow: 'Thu PM',
  eventTime: '16:12',
  label: `Pick ${rank}, Spot ${rank}, Thursday sunset, 4 stars`,
}));

/** The conversation, as the provider would hold it, for each phase the sheet distinguishes. */
const conversation = (phase) => {
  const base = {
    phase,
    kind: 'own',
    question: '',
    answer: null,
    pickCards: [],
    thread: [],
    history: [],
    resetReason: null,
    asked: null,
    selectedPick: null,
    selectionNonce: 0,
    mapContext: null,
    planPick: null,
    removedWindow: null,
    error: null,
    inputError: null,
    restored: false,
    busyRunLabel: null,
    allowance: {
      loaded: true, enabled: true, left: 3, limit: 3, typedAvailable: true,
    },
    typedDisabled: false,
    availability: 'on',
    isPro: false,
    askTyped: vi.fn(),
    openReady: vi.fn(),
    selectPick: vi.fn(),
    registerMapContext: vi.fn(),
    clear: vi.fn(),
    retry: vi.fn(),
    removeContextWindow: vi.fn(),
    restoreContextWindow: vi.fn(),
  };
  if (phase === 'error') {
    // `errorFor` always sets a message; a fake that left it null would test a state that cannot occur.
    return { ...base, error: { status: 502, code: 'ENGINE_FAILED', message: 'Couldn’t answer just now. No question used.' } };
  }
  if (phase === 'answer') {
    return {
      ...base,
      question: 'Best tonight?',
      answer: { id: 1, summary: 'Go to the coast.', picks: [], events: [] },
      pickCards: CARDS,
    };
  }
  return base;
};

let view;
const props = (over = {}) => ({
  locations: [NEAR],
  date: TODAY,
  autoEventType: 'SUNSET',
  forecastDates: [TODAY],
  heat: { ...HEAT },
  askOffered: true,
  askPhase: mockAsk.phase,
  panelShown: true,
  ...over,
});

/** Mounts the map with Ask in {@code phase}. */
async function mount(phase = 'empty', over = {}) {
  mockAsk = conversation(phase);
  await act(async () => { view = render(<MapView {...props(over)} />); });
}

/** Re-renders with the conversation in {@code phase} — what the provider's own update would do. */
async function setPhase(phase, over = {}) {
  mockAsk = { ...conversation(phase), clear: mockAsk.clear };
  await act(async () => { view.rerender(<MapView {...props(over)} />); });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T10:00:00Z`));
  localStorage.clear();
  mockIsMobile = true;
  mapEventHandlers = [];
  mapLabelsCalls.length = 0;
  cameraCalls.length = 0;
  calloutCalls.length = 0;
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

const sheet = () => screen.getByTestId('wf-map-peek');
const mode = () => sheet().getAttribute('data-ask');
const psh = () => screen.getByTestId('wf-map-pane').style.getPropertyValue('--psh');
const buttonsShown = () => screen.queryByTestId('wf-map-peek-btn-win') !== null;
const lastLabels = () => mapLabelsCalls.at(-1);

/**
 * Fires a Leaflet map event the sheet's map-touch listener has registered for. It is the LAST handlers
 * object carrying `touchstart` — the listener's own, from the latest render: an earlier render's
 * closure holds an earlier `open`, and calling every one would let a stale closure answer.
 */
async function mapEvent(kind) {
  const own = mapEventHandlers.filter((h) => 'touchstart' in h).at(-1);
  expect(own, 'the map-touch listener is mounted').toBeDefined();
  await act(async () => { own[kind]({}); });
}

const openAsk = () => fireEvent.click(screen.getByTestId('wf-map-peek-ask-entry'));
const expandMinimised = () => fireEvent.click(screen.getByTestId('wf-map-peek-mini'));
const pressPill = () => fireEvent.click(screen.getByTestId('wf-win-pill'));
const escape = () => fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' });

/** An ordinary chip press: the label layer's own `onSelect`, which is `selectMapLocation`. */
async function pressOrdinaryChip() {
  await act(async () => { lastLabels().onSelect('Near'); });
}
/** A pick chip press: the label layer's `onSelectAskPick`. */
async function pressPickChip(rank) {
  await act(async () => { lastLabels().onSelectAskPick(rank); });
}

// Ask is an answer's phase for these: `select` is what the provider records.
const withPicks = { askPicks: CARDS.map((c) => ({ ...c, eventType: c.targetType })), askAnswerId: 1, onSelectAskPick: vi.fn() };

describe('the sheet at rest', () => {
  it('draws no Ask row, and the sheet before Ask existed, when Ask is not offered', async () => {
    await mount('empty', { askOffered: false });

    expect(mode()).toBe('off');
    expect(screen.queryByTestId('wf-map-peek-ask')).not.toBeInTheDocument();
    expect(buttonsShown()).toBe(true);
    expect(psh()).toBe('74px');
  });

  it('draws the Ask row above the three buttons, at 126px, with nothing settled', async () => {
    await mount('empty');

    expect(mode()).toBe('collapsed');
    expect(screen.getByTestId('wf-map-peek-ask-entry')).toBeInTheDocument();
    expect(buttonsShown()).toBe(true);
    expect(psh()).toBe('126px');
  });

  it('rests at 112px, as one line, with an answer settled — and the answer has taken the buttons\' place', async () => {
    await mount('answer');

    expect(mode()).toBe('minimised');
    expect(psh()).toBe('112px');
    expect(screen.getByTestId('wf-map-peek-mini')).toBeInTheDocument();
    expect(buttonsShown()).toBe(false);
  });

  it('rests at 112px for a "not in the forecast" reply and for a failure too — the line says what they say', async () => {
    await mount('cant');
    expect(mode()).toBe('minimised');
    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('Not in the forecast');

    mockAsk = { ...conversation('error'), error: { message: 'Couldn’t answer just now. No question used.' } };
    await act(async () => { view.rerender(<MapView {...props({ askPhase: 'error' })} />); });
    expect(mode()).toBe('minimised');
    expect(screen.getByTestId('wf-map-peek-mini')).toHaveTextContent('Couldn’t answer just now');
  });

  it('is exactly what it was on desktop and tablet: no sheet at all, and no --psh — with Ask on, an answer settled, the same fixture', async () => {
    mockIsMobile = false;

    await mount('answer');

    expect(screen.queryByTestId('wf-map-peek')).not.toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-peek-ask')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-map-pane').style.getPropertyValue('--psh')).toBe('');
    // The desktop chrome the sheet replaces on the phone is still there.
    expect(screen.getByTestId('wf-map-chrome-tr')).toBeInTheDocument();
  });

  // The same fixture with picks ON the map, so the phone-only props have something to be wrong about:
  // a sheet that is not there must never be an obstacle, an inset or a band.
  it('desktop and tablet with picks on the map: no camera inset, no label obstacle, no callout band — the same fixture', async () => {
    mockIsMobile = false;

    await mount('answer', withPicks);
    await pressOrdinaryChip();

    expect(cameraCalls.at(-1).inset).toEqual({
      top: 0, right: 0, bottom: 0, left: 0,
    });
    expect(lastLabels().bottomInset).toBe(0);
    expect(calloutCalls.at(-1).bandKey).toBeNull();
  });
});

describe('§2.7 table — row: map touch / drag / zoom', () => {
  it.each(['mousedown', 'touchstart', 'dragstart', 'zoomstart'])(
    '%s with the suggestions showing: collapses to 126',
    async (kind) => {
      await mount('empty');
      openAsk();
      expect(mode()).toBe('expanded');

      await mapEvent(kind);

      expect(mode()).toBe('collapsed');
      expect(psh()).toBe('126px');
      expect(buttonsShown()).toBe(true);
    },
  );

  it.each(['mousedown', 'touchstart', 'dragstart', 'zoomstart'])(
    '%s with an answer expanded: minimises to 112 (the answer is kept)',
    async (kind) => {
      await mount('answer');
      expandMinimised();
      expect(mode()).toBe('expanded');

      await mapEvent(kind);

      expect(mode()).toBe('minimised');
      expect(psh()).toBe('112px');
      expect(mockAsk.clear).not.toHaveBeenCalled();
    },
  );

  it.each(['mousedown', 'touchstart', 'dragstart', 'zoomstart'])(
    '%s with the answer minimised: stays minimised',
    async (kind) => {
      await mount('answer');

      await mapEvent(kind);

      expect(mode()).toBe('minimised');
      expect(psh()).toBe('112px');
    },
  );

  // The table is silent on this cell; the mock leaves the sheet alone, and so do we: collapsing would
  // hide the only sign the question is out.
  it.each(['mousedown', 'touchstart', 'dragstart', 'zoomstart'])(
    '%s while an answer is still being fetched: the section stays open',
    async (kind) => {
      await mount('busy');
      openAsk();
      expect(mode()).toBe('expanded');

      await mapEvent(kind);

      expect(mode()).toBe('expanded');
    },
  );

  // Busy is the ONLY exemption: a reply that settled as "not in the forecast" or a failure is something
  // to minimise like an answer, not something to hold open.
  it.each(['cant', 'error'])('with a %s expanded: minimises like an answer, and keeps it', async (phase) => {
    await mount(phase);
    expandMinimised();
    expect(mode()).toBe('expanded');

    await mapEvent('dragstart');

    expect(mode()).toBe('minimised');
    expect(mockAsk.clear).not.toHaveBeenCalled();
  });

  // Leaflet fires `zoomstart` for ANY zoom: the camera's own fit to a new answer must not read as a hand.
  it('a zoom inside the camera\'s own move is the camera\'s: it minimises nothing; the next one is the reader\'s', async () => {
    await mount('answer', withPicks);
    expandMinimised();
    expect(cameraCalls.at(-1).onOwnMove).toBeTypeOf('function');

    act(() => { cameraCalls.at(-1).onOwnMove(Date.now() + 900); });
    await mapEvent('zoomstart');
    expect(mode()).toBe('expanded');

    vi.setSystemTime(new Date(`${TODAY}T10:00:02Z`));
    await mapEvent('zoomstart');
    expect(mode()).toBe('minimised');
  });
});

describe('§2.7 table — row: ordinary chip press', () => {
  it('with the suggestions showing: collapses, and the callout opens', async () => {
    await mount('empty');
    openAsk();

    await pressOrdinaryChip();

    expect(mode()).toBe('collapsed');
    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
  });

  it('with an answer expanded: minimises, and the callout opens', async () => {
    await mount('answer');
    expandMinimised();

    await pressOrdinaryChip();

    expect(mode()).toBe('minimised');
    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
  });

  it('with the answer minimised: the callout opens over the 112px line, and its band is told the height', async () => {
    await mount('answer');

    await pressOrdinaryChip();

    expect(mode()).toBe('minimised');
    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
    // D-7 is broken in exactly this state, so the card's band must be measured against 112 — and
    // repaint when that changes (`bandKey`, which is what a stale band is repainted on).
    expect(calloutCalls.at(-1).bandKey).toBe(112);
    expect(psh()).toBe('112px');
  });

  it('the callout\'s band follows the sheet when it changes height under a placed card', async () => {
    await mount('answer');
    await pressOrdinaryChip();
    expect(calloutCalls.at(-1).bandKey).toBe(112);

    await setPhase('empty'); // the answer is cleared elsewhere: the sheet comes down to 126

    expect(calloutCalls.at(-1).bandKey).toBe(126);
    expect(psh()).toBe('126px');
  });
});

describe('§2.7 table — row: pick chip press', () => {
  it('with an answer expanded: selects the card, and the sheet stays expanded', async () => {
    const onSelectAskPick = vi.fn();
    await mount('answer', { ...withPicks, onSelectAskPick });
    expandMinimised();

    await pressPickChip(2);

    expect(onSelectAskPick).toHaveBeenCalledExactlyOnceWith(2);
    expect(mode()).toBe('expanded');
  });

  it('with the answer minimised: expands, and selects the card', async () => {
    const onSelectAskPick = vi.fn();
    await mount('answer', { ...withPicks, onSelectAskPick });
    expect(mode()).toBe('minimised');

    await pressPickChip(3);

    expect(onSelectAskPick).toHaveBeenCalledExactlyOnceWith(3);
    expect(mode()).toBe('expanded');
  });

  // The callout and an EXPANDED sheet must never coexist. The window-follow effect clears the selection
  // too, but only while the pane is on screen and once per choice: the press itself must not rely on it.
  it('with the answer minimised UNDER A CALLOUT: expands, and the callout comes down with the press', async () => {
    const onSelectAskPick = vi.fn(); // a provider that does nothing — nothing else may clear the callout
    await mount('answer', { ...withPicks, onSelectAskPick });
    await pressOrdinaryChip();
    expect(screen.getByTestId('mock-callout')).toBeInTheDocument();
    expect(mode()).toBe('minimised');

    await pressPickChip(1);

    expect(mode()).toBe('expanded');
    expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
    expect(onSelectAskPick).toHaveBeenCalledExactlyOnceWith(1);
  });

  // Panels about the map persist under a press on it (map-landing L3): a drilldown is not the Ask
  // section's to close, so the card is chosen and the sheet stays at its line.
  it('with a drilldown open: the card is chosen and the drilldown stays, the sheet does not expand over it', async () => {
    const onSelectAskPick = vi.fn();
    await mount('answer', { ...withPicks, onSelectAskPick });
    pressPill();
    fireEvent.click(screen.getByTestId('wf-map-peek-drilldown'));
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();

    await pressPickChip(2);

    expect(onSelectAskPick).toHaveBeenCalledExactlyOnceWith(2);
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(mode()).toBe('minimised');
  });

  it('a pick for a window other than the one on the pill is still a pick: the sheet does not care which', async () => {
    const onSelectAskPick = vi.fn();
    const other = CARDS.map((c) => ({ ...c, eventType: 'SUNRISE', date: '2026-01-16' }));
    await mount('answer', { askPicks: other, askAnswerId: 1, onSelectAskPick });

    await pressPickChip(2);

    expect(mode()).toBe('expanded');
  });
});

describe('§2.7 table — row: Escape', () => {
  it('with the suggestions showing: collapses', async () => {
    await mount('empty');
    openAsk();

    escape();

    expect(mode()).toBe('collapsed');
  });

  it('with an answer expanded: minimises (and the selection, if any, is left for the next Escape)', async () => {
    await mount('answer');
    expandMinimised();

    escape();

    expect(mode()).toBe('minimised');
    expect(mockAsk.clear).not.toHaveBeenCalled();
  });

  it('with the answer minimised: as today — it clears the selection, and the line stays', async () => {
    await mount('answer');
    await pressOrdinaryChip();
    expect(screen.getByTestId('mock-callout')).toBeInTheDocument();

    escape();

    expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
    expect(mode()).toBe('minimised');
  });
});

describe('§2.7 table — row: window pill / a peek button', () => {
  it('with the suggestions showing: switches to that section', async () => {
    await mount('empty');
    openAsk();

    pressPill();

    expect(mode()).toBe('section');
    expect(screen.getByTestId('wf-map-peek-windows')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-peek-ask-body')).not.toBeInTheDocument();
    expect(buttonsShown()).toBe(true);
  });

  it('with an answer expanded: minimises, and opens that section (the answer is kept)', async () => {
    await mount('answer');
    expandMinimised();

    pressPill();

    expect(mode()).toBe('section');
    expect(screen.getByTestId('wf-map-peek-windows')).toBeInTheDocument();
    expect(mockAsk.clear).not.toHaveBeenCalled();
  });

  it('with the answer minimised: opens that section', async () => {
    await mount('answer');

    pressPill();

    expect(mode()).toBe('section');
    expect(screen.getByTestId('wf-map-peek-windows')).toBeInTheDocument();
    expect(buttonsShown()).toBe(true);
  });

  it('a peek button (Layers) opens its section over the Ask row, and a press on the Ask row gives it way', async () => {
    await mount('empty');
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    expect(screen.getByTestId('wf-map-peek-layers')).toBeInTheDocument();

    openAsk();

    expect(mode()).toBe('expanded');
    expect(screen.queryByTestId('wf-map-peek-layers')).not.toBeInTheDocument();
  });
});

describe('§2.7 table — row: that section closes', () => {
  it('with the answer kept: back to the 112 line', async () => {
    await mount('answer');
    pressPill();
    expect(mode()).toBe('section');

    pressPill(); // the pill toggles its own section shut

    expect(mode()).toBe('minimised');
    expect(screen.getByTestId('wf-map-peek-mini')).toBeInTheDocument();
    expect(psh()).toBe('112px');
  });

  it('with an answer that was EXPANDED when the pill was pressed: back to the 112 line, not to the answer', async () => {
    await mount('answer');
    expandMinimised();
    pressPill();

    pressPill();

    expect(mode()).toBe('minimised');
  });

  it('with nothing settled: back to the 126px row', async () => {
    await mount('empty');
    pressPill();

    pressPill();

    expect(mode()).toBe('collapsed');
  });
});

describe('§2.7 table — row: ✕ in the ask row', () => {
  it('with the suggestions showing: collapses, and clears nothing', async () => {
    await mount('empty');
    openAsk();

    fireEvent.click(screen.getByTestId('wf-map-peek-ask-x'));

    expect(mode()).toBe('collapsed');
    expect(mockAsk.clear).not.toHaveBeenCalled();
  });

  it('with an answer expanded: clears the answer, and the three buttons are restored', async () => {
    await mount('answer');
    expandMinimised();
    expect(screen.getByTestId('wf-map-peek-ask-x')).toHaveAccessibleName('Clear answer');

    fireEvent.click(screen.getByTestId('wf-map-peek-ask-x'));
    expect(mockAsk.clear).toHaveBeenCalledTimes(1);
    await setPhase('empty'); // what the provider does on `clear()`

    expect(mode()).toBe('collapsed');
    expect(buttonsShown()).toBe(true);
    expect(psh()).toBe('126px');
  });

  it('while an answer is being fetched: closes the section and clears nothing (a charged answer is not binned)', async () => {
    await mount('busy');
    openAsk();
    expect(screen.getByTestId('wf-map-peek-ask-x')).toHaveAccessibleName('Close Ask');

    fireEvent.click(screen.getByTestId('wf-map-peek-ask-x'));

    expect(mockAsk.clear).not.toHaveBeenCalled();
    expect(mode()).toBe('collapsed');
  });
});

describe('§2.7 table — row: tap the 112 line', () => {
  it('expands the answer', async () => {
    await mount('answer');

    expandMinimised();

    expect(mode()).toBe('expanded');
    expect(screen.getByTestId('wf-map-peek-ask-body')).toBeInTheDocument();
    expect(buttonsShown()).toBe(false);
  });
});

describe('§2.7 table — row: tab switch away and back', () => {
  it('with the suggestions showing: closed', async () => {
    await mount('empty');
    openAsk();

    await setPhase('empty', { panelShown: false });
    await setPhase('empty', { panelShown: true });

    expect(mode()).toBe('collapsed');
  });

  it('with an answer expanded: kept, minimised', async () => {
    await mount('answer');
    expandMinimised();

    await setPhase('answer', { panelShown: false });
    expect(mode()).toBe('minimised');
    await setPhase('answer', { panelShown: true });

    expect(mode()).toBe('minimised');
    expect(screen.getByTestId('wf-map-peek-mini')).toBeInTheDocument();
    expect(mockAsk.clear).not.toHaveBeenCalled();
  });

  it('with the answer minimised: kept', async () => {
    await mount('answer');

    await setPhase('answer', { panelShown: false });
    await setPhase('answer', { panelShown: true });

    expect(mode()).toBe('minimised');
  });

  it('another section is not the Ask section\'s to close: it was never closed by a tab switch and is not now', async () => {
    await mount('empty');
    pressPill();

    await setPhase('empty', { panelShown: false });

    expect(mode()).toBe('section');
  });
});

describe('one gate: openMapMenu', () => {
  it('Ask going away (switched off, a rewind) takes an open section with it — no stale value, no sheet', async () => {
    await mount('empty');
    openAsk();
    expect(mode()).toBe('expanded');

    await setPhase('empty', { askOffered: false });

    expect(mode()).toBe('off');
    // And it does not come back by itself when Ask does.
    await setPhase('empty', { askOffered: true });
    expect(mode()).toBe('collapsed');
  });

  it('an answer landing while the sheet is collapsed rests as the 112 line, and the camera is told its height', async () => {
    await mount('empty', withPicks);
    expect(mode()).toBe('collapsed');

    await setPhase('answer', withPicks);

    expect(mode()).toBe('minimised');
    expect(cameraCalls.at(-1).inset).toEqual({
      top: 0, right: 0, bottom: 112, left: 0,
    });
  });

  it('opening the Ask section over a dialog is refused', async () => {
    await mount('empty');
    const dialog = document.createElement('div');
    dialog.setAttribute('role', 'dialog');
    dialog.setAttribute('aria-modal', 'true');
    document.body.appendChild(dialog);

    openAsk();

    expect(mode()).toBe('collapsed');
    dialog.remove();
  });
});

describe('answers arriving, and focus, around the buttons', () => {
  it('an answer landing while ANOTHER section is open waits behind it: the section stays, and closing it rests at 112', async () => {
    await mount('empty');
    pressPill();
    expect(mode()).toBe('section');

    await setPhase('answer');

    expect(mode()).toBe('section');
    expect(psh()).toBe('112px');
    pressPill();
    expect(mode()).toBe('minimised');
  });

  it('a settled answer with NO picks on the map still rests at 112, and covers nothing the camera or labels must avoid', async () => {
    await mount('answer', { askPicks: [], askAnswerId: null });

    expect(mode()).toBe('minimised');
    expect(psh()).toBe('112px');
    expect(cameraCalls.at(-1).inset).toEqual({
      top: 0, right: 0, bottom: 0, left: 0,
    });
    expect(lastLabels().bottomInset).toBe(0);
  });

  // The ordinary route: an answer is minimised, the pill opens Windows (the buttons come back), and the
  // reader shuts that section with its own button — which unmounts while it holds focus.
  it('closing a section with its OWN button over a settled answer: focus lands on the Ask row, not <body>', async () => {
    await mount('answer');
    pressPill();
    const windows = screen.getByTestId('wf-map-peek-btn-win');
    windows.focus();
    expect(document.activeElement).toBe(windows);

    fireEvent.click(windows); // "CLOSE"

    expect(mode()).toBe('minimised');
    expect(screen.queryByTestId('wf-map-peek-btn-win')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-entry'));
  });

  it('an answer replacing the buttons while one holds focus: focus lands on the Ask row', async () => {
    await mount('empty');
    screen.getByTestId('wf-map-peek-btn-lay').focus();

    await setPhase('answer');

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-entry'));
  });

  it('a press on the pill that closes the section keeps focus where the reader put it (the pill)', async () => {
    await mount('answer');
    pressPill();
    const pill = screen.getByTestId('wf-win-pill');
    pill.focus();

    pressPill();

    expect(document.activeElement).toBe(pill);
  });

  it('Regions and Filters return focus to the Layers button — or, while an answer has taken the buttons, to the Ask row', async () => {
    await mount('empty');
    const layers = screen.getByTestId('wf-map-peek-btn-lay');
    expect(filtersPopoverCalls.at(-1).restoreFallback()).toBe(layers);

    await setPhase('answer');

    expect(screen.queryByTestId('wf-map-peek-btn-lay')).not.toBeInTheDocument();
    expect(filtersPopoverCalls.at(-1).restoreFallback()).toBe(screen.getByTestId('wf-map-peek-ask-entry'));
  });
});

describe('the camera is told what the sheet covers (F4)', () => {
  it.each([
    ['answer expanded', async () => { expandMinimised(); }, 470],
    ['answer minimised', async () => {}, 112],
    ['another section open over the answer', async () => { pressPill(); }, 408],
  ])('%s: a bottom inset of %i', async (_name, act1, bottom) => {
    await mount('answer', withPicks);
    await act1();

    expect(cameraCalls.at(-1).inset).toEqual({
      top: 0, right: 0, bottom, left: 0,
    });
  });

  it('is no inset at all with no picks on the map — which is what the camera was given before F4', async () => {
    await mount('empty');
    openAsk();

    expect(cameraCalls.at(-1).inset).toEqual({
      top: 0, right: 0, bottom: 0, left: 0,
    });
  });

  it('the label layer sees the sheet as an obstacle only while picks are on the map', async () => {
    await mount('empty');
    expect(lastLabels().bottomInset).toBe(0);

    await setPhase('answer', withPicks);
    expect(lastLabels().bottomInset).toBe(112);
    expandMinimised();
    expect(lastLabels().bottomInset).toBe(470);
  });
});
