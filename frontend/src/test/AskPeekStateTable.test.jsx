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
  const svg = (options) => ({ options });
  const L = {
    icon, divIcon, point, Control, DomEvent, svg,
  };
  return { default: L, ...L };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

/** Every handlers object a `useMapEvents` caller registered — see `touchHandlers`. */
let mapEventHandlers = [];
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
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
vi.mock('../components/map/MapTopicRings.jsx', () => ({ default: () => null }));
const cameraCalls = [];
vi.mock('../components/map/AskCameraController.jsx', () => ({
  default: (props) => { cameraCalls.push(props); return null; },
}));
const calloutCalls = [];
/** 'mount' / 'unmount' of the callout, so a test can tell a callout kept from one torn down and rebuilt. */
let calloutLifecycle = [];
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: function MockCallout(props) {
    calloutCalls.push(props);
    React.useEffect(() => {
      calloutLifecycle.push('mount');
      return () => { calloutLifecycle.push('unmount'); };
    }, []);
    return <div data-testid="mock-callout">{props.location?.name ?? ''}</div>;
  },
}));
// Only its return address is read here (the real one is the Regions list; its own tests are its own).
const regionsJumpCalls = [];
vi.mock('../components/map/RegionsJump.jsx', () => ({
  default: (props) => { regionsJumpCalls.push(props); return null; },
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
import { getAstroAvailableDates } from '../api/astroApi.js';
import { deferred } from './askFixtures.js';

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
/** What the pick-chip tests hand `MapView`, and the provider-simulated choice counter (see providerChooses). */
let pickOver = {};
let askNonce = 0;
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
  calloutLifecycle = [];
  pickOver = {};
  askNonce = 0;
  filtersPopoverCalls.length = 0;
  regionsJumpCalls.length = 0;
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

describe('a reader who is not PRO or ADMIN: the locked row', () => {
  it('draws the row locked at 126px, and pressing the disabled entry opens nothing', async () => {
    mockAsk = { ...conversation('empty'), availability: 'locked', typedDisabled: true };
    await act(async () => { view = render(<MapView {...props({ askLocked: true })} />); });

    const entry = screen.getByTestId('wf-map-peek-ask-entry');
    expect(entry).toBeDisabled();
    expect(screen.getByTestId('wf-map-peek-ask-locked').className).toContain('wf-lens-locked');
    expect(screen.getByTestId('wf-map-peek-ask-locked').contains(screen.getByTestId('wf-map-peek-ask-upsell'))).toBe(false);
    expect(mode()).toBe('collapsed');
    expect(psh()).toBe('126px');

    openAsk();
    await act(async () => {});

    expect(mode()).toBe('collapsed');
    expect(screen.queryByTestId('wf-map-peek-ask-field')).not.toBeInTheDocument();
  });

  it('⚠️ `openAskSection` itself refuses: with the entry ENABLED (a seam the lock never leaves) a press opens nothing', async () => {
    // The context says `on`, so the entry is a live button; only `MapView`'s own `askLocked` guard stands.
    mockAsk = conversation('empty');
    await act(async () => { view = render(<MapView {...props({ askLocked: true })} />); });
    const entry = screen.getByTestId('wf-map-peek-ask-entry');
    expect(entry).toBeEnabled();

    openAsk();
    await act(async () => {});

    expect(mode()).toBe('collapsed');
    expect(screen.queryByTestId('wf-map-peek-ask-field')).not.toBeInTheDocument();
  });

  it('Escape in the Layers body lands focus on the Layers button, never <body>: a disabled entry cannot take it', async () => {
    mockAsk = { ...conversation('empty'), availability: 'locked', typedDisabled: true };
    await act(async () => { view = render(<MapView {...props({ askLocked: true })} />); });
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    const regions = screen.getByTestId('wf-map-peek-lay-regions');
    regions.focus();

    fireEvent.keyDown(regions, { key: 'Escape' });

    expect(mode()).toBe('collapsed');
    expect(document.activeElement).not.toBe(document.body);
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-btn-lay'));
  });
});

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

/**
 * The provider's part of a CHOICE, which this file's stand-in conversation does not play: the pane's
 * `askWindow` memo is `{date, eventType, nonce, callout, locationId}`, with a nonce new on every choice and
 * `callout` the intent the choice carried (`AskContext.selectPick(rank, { callout })`).
 */
async function providerChooses(rank, { callout, cards = CARDS } = {}) {
  const card = cards.find((c) => c.rank === rank);
  askNonce += 1;
  pickOver = {
    ...pickOver,
    askSelectedRank: rank,
    askWindow: {
      date: card.date, eventType: card.targetType, nonce: askNonce, callout, locationId: card.locationId,
    },
  };
  await act(async () => { view.rerender(<MapView {...props(pickOver)} />); });
}

/**
 * A chip press as the whole chain sees it: the handler runs, then the provider answers with the intent the
 * handler PASSED (not one the test chooses), so a handler that stops asking for the callout fails here.
 */
async function chipChosen(rank, { cards = CARDS } = {}) {
  await pressPickChip(rank);
  const [, options] = pickOver.onSelectAskPick.mock.lastCall;
  await providerChooses(rank, { callout: options?.callout === true, cards });
}

/** Mounts with an answer's picks on the map and an `onSelectAskPick` that only records the call. */
async function mountPicks(phase, { cards = CARDS, ...over } = {}) {
  askNonce = 0;
  pickOver = {
    askPicks: cards.map((c) => ({ ...c, eventType: c.targetType })),
    askAnswerId: 1,
    onSelectAskPick: vi.fn(),
    ...over,
  };
  await mount(phase, pickOver);
  return pickOver.onSelectAskPick;
}

const calloutEvents = () => calloutCalls.map((c) => c.event?.eventType);

// docs/engineering/phone-pick-callout-plan.md §3 P1 (2026-10-17) superseded F4's rule here: a pick chip no
// longer expands the answer — it opens the pick's own callout over the 112px line.
describe('§2.7 table — row: pick chip press (phone, P1)', () => {
  it('with an answer expanded: minimises at once, the callout opens only once the window has followed, and it is the pick\'s place', async () => {
    const onSelect = await mountPicks('answer');
    expandMinimised();

    await pressPickChip(2);

    expect(onSelect).toHaveBeenCalledExactlyOnceWith(2, { callout: true });
    expect(mode()).toBe('minimised');
    expect(psh()).toBe('112px');
    expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();

    await providerChooses(2, { callout: true });

    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
    expect(mode()).toBe('minimised');
    expect(calloutCalls.at(-1).bandKey).toBe(112);
  });

  it('with the answer minimised: stays minimised, and the callout opens over the line', async () => {
    const onSelect = await mountPicks('answer');
    expect(mode()).toBe('minimised');

    await chipChosen(3);

    expect(onSelect).toHaveBeenCalledExactlyOnceWith(3, { callout: true });
    expect(mode()).toBe('minimised');
    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
  });

  it('with the answer minimised UNDER ANOTHER PLACE\'S CALLOUT: that callout comes down with the press, and the pick\'s opens after the follow', async () => {
    const OTHER = { ...NEAR, id: 2, name: 'Other' };
    await mountPicks('answer', { locations: [NEAR, OTHER] });
    await act(async () => { lastLabels().onSelect('Other'); });
    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Other');

    await pressPickChip(1);

    // A provider that does nothing must not leave the old place's callout standing for a new choice.
    expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
    expect(mode()).toBe('minimised');

    await providerChooses(1, { callout: true });

    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
  });

  // Mutant (b): the callout drawn before the window has followed would describe the pill's window.
  it('a pick for another window than the pill\'s: no callout is ever drawn for the pill\'s window, and the last carries the pick\'s', async () => {
    const SUNRISE_CARDS = CARDS.map((c) => ({ ...c, targetType: 'SUNRISE', shortWindow: 'Thu AM' }));
    await mountPicks('answer', { cards: SUNRISE_CARDS });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunset/i);

    await pressPickChip(1);
    expect(calloutCalls).toHaveLength(0);
    await providerChooses(1, { callout: true, cards: SUNRISE_CARDS });

    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
    expect(calloutCalls.length).toBeGreaterThan(0);
    expect(new Set(calloutEvents())).toEqual(new Set(['SUNRISE']));
    expect(calloutEvents().at(-1)).toBe('SUNRISE');
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/sunrise/i);
  });

  it('a pick whose window the map does not carry: the card is chosen, the answer stays minimised, and NO callout opens', async () => {
    const UNFOLLOWABLE = [
      CARDS[0], { ...CARDS[1], date: '2026-01-16', targetType: 'SUNRISE' },
    ];
    const onSelect = await mountPicks('answer', { cards: UNFOLLOWABLE });

    await chipChosen(2, { cards: UNFOLLOWABLE });

    expect(onSelect).toHaveBeenCalledExactlyOnceWith(2, { callout: true });
    expect(mode()).toBe('minimised');
    expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
  });

  // Tide has no button in this harness (it needs a coastal spot in the padded bounds, which the mocked
  // map never has); it is the same write, since every `peek:*` value gives way.
  it.each([['win'], ['lay']])('with the %s section open: it closes, and the callout opens', async (section) => {
    await mountPicks('answer');
    pressPill();
    if (section !== 'win') fireEvent.click(screen.getByTestId(`wf-map-peek-btn-${section}`));
    expect(mode()).toBe('section');

    await chipChosen(1);

    expect(mode()).toBe('minimised');
    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
  });

  // Panels about the map persist under a press on it (map-landing L3), and an ordinary chip's callout
  // already opens over an open drilldown: a pick chip does the same.
  it('with a drilldown open: it stays, the sheet stays at its line, and the callout opens over it', async () => {
    await mountPicks('answer');
    pressPill();
    fireEvent.click(screen.getByTestId('wf-map-peek-drilldown'));
    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();

    await chipChosen(2);

    expect(screen.getByTestId('wf-win-panel')).toBeInTheDocument();
    expect(mode()).toBe('minimised');
    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
  });

  // Collapsing in the HANDLER (not through the selection-collapse effect, which waits for the provider)
  // means the camera never flies for the 470px sheet and then snaps.
  it('from an expanded answer, the camera is never given the 470px inset after the press', async () => {
    await mountPicks('answer');
    expandMinimised();
    expect(cameraCalls.at(-1).inset.bottom).toBe(470);
    const before = cameraCalls.length;

    await pressPickChip(2);

    const after = cameraCalls.slice(before).map((c) => c.inset.bottom);
    expect(after.length).toBeGreaterThan(0);
    expect(after.every((bottom) => bottom === 112)).toBe(true);
  });

  // An ordinary chip tap never moves the camera: the chip was on screen to be tapped, and a flight would put
  // it above the frame centre, where the card fits neither side of it and covers the ring just tapped.
  it('a chip choice does not ask the camera to fly to the pick; a CARD choice still does', async () => {
    await mountPicks('answer');

    await chipChosen(1);
    expect(cameraCalls.at(-1).selection).toMatchObject({ nonce: 1, still: true });

    await providerChooses(2, { callout: false });
    expect(cameraCalls.at(-1).selection).toMatchObject({ nonce: 2, still: false });
  });

  // A `1·2` place is one chip for two windows (ask-thread-plan): each press steps the rank, follows that
  // rank's window and keeps the callout on the place — the callout is not torn down and rebuilt.
  it('a re-tap of a place picked at two windows keeps ONE callout mounted, and follows the second window', async () => {
    const TWO_WINDOWS = [CARDS[0], { ...CARDS[1], targetType: 'SUNRISE' }];
    await mountPicks('answer', { cards: TWO_WINDOWS });
    await chipChosen(1, { cards: TWO_WINDOWS });
    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
    expect(calloutLifecycle).toEqual(['mount']);

    await chipChosen(2, { cards: TWO_WINDOWS });

    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
    expect(calloutEvents().at(-1)).toBe('SUNRISE');
    expect(calloutLifecycle).toEqual(['mount']);
  });

  it('Escape clears the pick\'s callout and leaves the line', async () => {
    await mountPicks('answer');
    await chipChosen(1);
    expect(screen.getByTestId('mock-callout')).toBeInTheDocument();

    escape();

    expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
    expect(mode()).toBe('minimised');
  });

  // The pick names a place the roster no longer lists under that id: the callout falls back to the pick's name.
  it('opens the callout under the pick\'s NAME when its locationId is not among the locations', async () => {
    const RENAMED = CARDS.map((c) => ({ ...c, locationId: 99, name: 'Near' }));
    await mountPicks('answer', { cards: RENAMED });

    await chipChosen(1, { cards: RENAMED });

    expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
  });

  // The callout's ✕ unmounts with the callout; focus must not fall to <body>, or the pane's Escape rule
  // (it fires only while focus is inside the pane) goes quiet.
  it('the callout\'s ✕ leaves focus inside the pane, and Escape fired AT the active element still reaches it', async () => {
    await mountPicks('answer');
    await chipChosen(1);
    expect(screen.getByTestId('mock-callout')).toBeInTheDocument();

    await act(async () => { calloutCalls.at(-1).onClose(); });

    expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
    expect(document.activeElement).not.toBe(document.body);
    expect(screen.getByTestId('wf-map-pane')).toContainElement(document.activeElement);
    // Escape reaches the pane's ladder from there: it closes an open section.
    pressPill();
    expect(mode()).toBe('section');
    fireEvent.keyDown(document.activeElement, { key: 'Escape' });
    expect(mode()).toBe('minimised');
  });

  describe('a DEFERRED night choice (its row has not arrived yet)', () => {
    const NIGHT_CARDS = CARDS.map((c) => ({ ...c, targetType: 'ASTRO' }));
    const OTHER = { ...NEAR, id: 2, name: 'Other' };
    let gate;
    beforeEach(() => {
      gate = deferred();
      getAstroAvailableDates.mockReturnValue(gate.promise);
    });
    afterEach(() => {
      getAstroAvailableDates.mockReset();
      getAstroAvailableDates.mockResolvedValue([]);
    });
    const rowArrives = () => act(async () => { gate.resolve([TODAY]); await gate.promise; });

    // The plan's headline reason for carrying the intent ON the choice.
    it('opens its callout when the row arrives, and the answer is still minimised', async () => {
      await mountPicks('answer', { cards: NIGHT_CARDS });
      await chipChosen(1, { cards: NIGHT_CARDS });
      expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();

      await rowArrives();

      expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/astro/i);
      expect(screen.getByTestId('mock-callout')).toHaveTextContent('Near');
      expect(mode()).toBe('minimised');
    });

    it('does NOT open when the reader has reopened the answer meanwhile', async () => {
      await mountPicks('answer', { cards: NIGHT_CARDS });
      await chipChosen(1, { cards: NIGHT_CARDS });
      expandMinimised();
      expect(mode()).toBe('expanded');

      await rowArrives();

      expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/astro/i);
      expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
      expect(mode()).toBe('expanded');
    });

    it('does NOT displace a place the reader has selected meanwhile', async () => {
      await mountPicks('answer', { cards: NIGHT_CARDS, locations: [NEAR, OTHER] });
      await chipChosen(1, { cards: NIGHT_CARDS });
      await act(async () => { lastLabels().onSelect('Other'); });
      expect(screen.getByTestId('mock-callout')).toHaveTextContent('Other');

      await rowArrives();

      expect(screen.getByTestId('wf-win-pill')).toHaveTextContent(/astro/i);
      expect(screen.getByTestId('mock-callout')).toHaveTextContent('Other');
    });
  });

  describe('a card, or a choice that never arrived, never inherits a chip\'s callout', () => {
    // Mutant (c): the intent kept anywhere but ON the choice (a flag in the handler, a ref in the pane)
    // is spent by the first choice that happens to run the effect.
    it('a chip on a window the map does not carry, then a CARD choice: no callout', async () => {
      const UNFOLLOWABLE = [
        CARDS[0], { ...CARDS[1], date: '2026-01-16', targetType: 'SUNRISE' },
      ];
      await mountPicks('answer', { cards: UNFOLLOWABLE });
      await chipChosen(2, { cards: UNFOLLOWABLE });
      expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();

      await providerChooses(1, { callout: false, cards: UNFOLLOWABLE });

      expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
    });

    it('a chip, its callout, Escape, then a CARD choice: no callout comes back', async () => {
      const TWO_WINDOWS = [CARDS[0], { ...CARDS[1], targetType: 'SUNRISE' }];
      await mountPicks('answer', { cards: TWO_WINDOWS });
      await chipChosen(1, { cards: TWO_WINDOWS });
      escape();
      expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();

      await providerChooses(2, { callout: false, cards: TWO_WINDOWS });

      expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
    });

    it('a chip whose choice never yields a window (the conversation went busy), then a CARD choice: no callout', async () => {
      await mountPicks('answer');
      await pressPickChip(1); // the provider never answers with an `askWindow`

      await providerChooses(2, { callout: false });

      expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
    });
  });
});

describe('§2.7 table — row: pick chip press (tablet and desktop, unchanged)', () => {
  // Mutant (d): the effect opening the callout without the phone's gate. A provider that asked for one
  // (which only the phone's handler does) must still get none off the phone.
  it('chooses the card and opens no callout: the handler asks for none, and the follow ignores one that was asked', async () => {
    mockIsMobile = false;
    const onSelect = await mountPicks('answer');

    await pressPickChip(2);
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(2);

    await providerChooses(2, { callout: true });

    expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
    // And the camera flies for it as it always did: no callout opened, so nothing is in the way.
    expect(cameraCalls.at(-1).selection).toMatchObject({ nonce: 1, still: false });
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
    expect(screen.getByTestId('wf-map-peek-ask-x')).toHaveAccessibleName('New conversation');

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
  // phone-pick-callout-plan.md §3 P2: the minimised line has a Layers door, so that is where focus goes now
  // (the Ask row's entry button is not the line; it was the rescue target only while the line had no door).
  it('closing a section with its OWN button over a settled answer: focus lands on the Layers door, not <body>', async () => {
    await mount('answer');
    pressPill();
    const windows = screen.getByTestId('wf-map-peek-btn-win');
    windows.focus();
    expect(document.activeElement).toBe(windows);

    fireEvent.click(windows); // "CLOSE"

    expect(mode()).toBe('minimised');
    expect(screen.queryByTestId('wf-map-peek-btn-win')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-mini-lay'));
  });

  it('an answer replacing the buttons while one holds focus: focus lands on the Layers door', async () => {
    await mount('empty');
    screen.getByTestId('wf-map-peek-btn-lay').focus();

    await setPhase('answer');

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-mini-lay'));
  });

  it('a hot-topics bar press folds the sheet over a settled answer: focus lands on the Layers door, not <body>', async () => {
    const dust = {
      type: 'DUST', label: 'Dust', date: TODAY, eventType: 'SUNSET', regions: [], locationNames: ['Near'],
    };
    await mount('answer', { hotTopics: [dust] });
    pressPill();
    fireEvent.click(screen.getByTestId('wf-topic-strip-toggle'));
    fireEvent.click(screen.getByTestId('wf-topic-bar-DUST-1'));
    expect(mode()).toBe('minimised');
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-mini-lay'));
  });

  it('a press on the pill that closes the section keeps focus where the reader put it (the pill)', async () => {
    await mount('answer');
    pressPill();
    const pill = screen.getByTestId('wf-win-pill');
    pill.focus();

    pressPill();

    expect(document.activeElement).toBe(pill);
  });

  it('Regions and Filters return focus to the Layers button — or, while an answer has taken the buttons, to the Layers door', async () => {
    await mount('empty');
    const layers = screen.getByTestId('wf-map-peek-btn-lay');
    expect(filtersPopoverCalls.at(-1).restoreFallback()).toBe(layers);
    expect(regionsJumpCalls.at(-1).restoreFallback()).toBe(layers);

    await setPhase('answer');

    expect(screen.queryByTestId('wf-map-peek-btn-lay')).not.toBeInTheDocument();
    expect(filtersPopoverCalls.at(-1).restoreFallback()).toBe(screen.getByTestId('wf-map-peek-mini-lay'));
    expect(regionsJumpCalls.at(-1).restoreFallback()).toBe(screen.getByTestId('wf-map-peek-mini-lay'));
  });
});

// docs/engineering/phone-pick-callout-plan.md §3 P2: a Layers door beside the minimised line.
describe('the Layers door (phone, P2)', () => {
  const door = () => screen.getByTestId('wf-map-peek-mini-lay');

  it('opens Layers over the settled answer, which is kept — the buttons come back, the line goes', async () => {
    await mount('answer');
    expect(mode()).toBe('minimised');

    fireEvent.click(door());

    expect(mode()).toBe('section');
    expect(screen.getByTestId('wf-map-peek-btn-lay')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('wf-map-peek-body')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-map-peek-mini')).not.toBeInTheDocument();
    expect(mockAsk.clear).not.toHaveBeenCalled();
    expect(psh()).toBe('112px');
  });

  // The mutant that wrote `setOpenMapMenu('peek:lay')` directly would leave the callout standing beside an
  // open 408px sheet, which D-7 forbids in either order.
  it('clears a standing callout first: an ordinary chip → its callout → the door → no callout, and Layers open', async () => {
    await mount('answer');
    await pressOrdinaryChip();
    expect(screen.getByTestId('mock-callout')).toBeInTheDocument();
    expect(mode()).toBe('minimised');

    fireEvent.click(door());

    expect(screen.queryByTestId('mock-callout')).not.toBeInTheDocument();
    expect(mode()).toBe('section');
  });

  it('hands focus to the Layers button — the door unmounted under the press — and Escape fired AT the active element closes Layers', async () => {
    await mount('answer');
    door().focus();

    fireEvent.click(door());

    expect(screen.queryByTestId('wf-map-peek-mini-lay')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-btn-lay'));
    fireEvent.keyDown(document.activeElement, { key: 'Escape' });
    expect(mode()).toBe('minimised');
  });

  it('takes focus onto itself on the press, for a browser that does not focus a button it was tapped on (Safari)', async () => {
    await mount('answer');
    expect(document.activeElement).toBe(document.body);

    fireEvent.click(door());

    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-btn-lay'));
  });

  it.each([
    ['the Layers button itself', async () => { fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay')); }],
    ['Escape', async () => { fireEvent.keyDown(document.activeElement, { key: 'Escape' }); }],
  ])('closing Layers with %s returns focus to the door, never <body>', async (_name, close) => {
    await mount('answer');
    door().focus();
    fireEvent.click(door());
    expect(mode()).toBe('section');

    await close();

    expect(mode()).toBe('minimised');
    expect(document.activeElement).toBe(door());
  });

  it('Regions and Filters, closing back onto the minimised line, find the door as their return address', async () => {
    await mount('answer');
    fireEvent.click(door());
    expect(filtersPopoverCalls.at(-1).restoreFallback()).toBe(screen.getByTestId('wf-map-peek-btn-lay'));
    expect(regionsJumpCalls.at(-1).restoreFallback()).toBe(screen.getByTestId('wf-map-peek-btn-lay'));

    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay')); // Layers closed: the line is back

    expect(filtersPopoverCalls.at(-1).restoreFallback()).toBe(door());
    expect(regionsJumpCalls.at(-1).restoreFallback()).toBe(door());
  });

  it('a question going busy while the door holds focus lands focus on the Ask entry — the line is unmounted', async () => {
    await mount('answer');
    door().focus();
    expect(document.activeElement).toBe(door());

    await setPhase('busy');

    expect(mode()).toBe('collapsed');
    expect(screen.queryByTestId('wf-map-peek-mini-lay')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-entry'));
  });

  // Focus INSIDE the open section's body (Regions, Tide mode…) when the section closes: the body
  // unmounts with it, and the button row's report (focus inside the row) does not see it.
  it('Escape fired AT a control inside the Layers body, with an answer standing, lands focus on the door', async () => {
    await mount('answer');
    fireEvent.click(door());
    const regions = screen.getByTestId('wf-map-peek-lay-regions');
    regions.focus();
    expect(document.activeElement).toBe(regions);

    fireEvent.keyDown(regions, { key: 'Escape' });

    expect(mode()).toBe('minimised');
    expect(screen.queryByTestId('wf-map-peek-lay-regions')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(door());
  });

  it('the same with NO answer standing: focus lands on the Ask entry, never <body>', async () => {
    await mount('empty');
    fireEvent.click(screen.getByTestId('wf-map-peek-btn-lay'));
    const regions = screen.getByTestId('wf-map-peek-lay-regions');
    regions.focus();

    fireEvent.keyDown(regions, { key: 'Escape' });

    expect(mode()).toBe('collapsed');
    expect(document.activeElement).not.toBe(document.body);
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-peek-ask-entry'));
  });

  // The class test in the door's unmount report: the line also unmounts when the PILL opens Windows over a
  // minimised answer, and focus that was never on the door must not be dragged to the Layers button.
  it('the line unmounting for the pill\'s Windows section, with focus on <body>, moves focus nowhere', async () => {
    await mount('answer');
    expect(document.activeElement).toBe(document.body);

    pressPill();

    expect(mode()).toBe('section');
    expect(screen.queryByTestId('wf-map-peek-mini-lay')).not.toBeInTheDocument();
    expect(document.activeElement).not.toBe(screen.getByTestId('wf-map-peek-btn-lay'));
    expect(document.activeElement).toBe(document.body);
  });

  it('is not drawn on the sheets that have no minimised line: collapsed, expanded, a section open', async () => {
    await mount('empty');
    expect(screen.queryByTestId('wf-map-peek-mini-lay')).not.toBeInTheDocument();

    await setPhase('answer');
    expandMinimised();
    expect(screen.queryByTestId('wf-map-peek-mini-lay')).not.toBeInTheDocument();
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
