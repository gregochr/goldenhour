/**
 * `MapView` + the real `MapTopicChips`, `MapTopicRings`, `WindowControl` and `buildMapEvents`: the
 * Map's hot-topic chips, rings and selection, end to end through the `hotTopics` prop
 * (hot-topics-by-day-plan §3 M1).
 *
 * <p>Not mocked: `MapView`, the chips, the rings, the window control, the event list. Mocked:
 * Leaflet, the heat layer, the callout (a probe) and the two label layers (probes that expose the
 * props `MapView` hands them and a button that selects the fixture spot).
 *
 * <p>Thursday 22 Oct 2026. Fixed dates, the wall clock frozen.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen, within,
} from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  const svg = (options) => ({ options });
  return { default: { icon, divIcon, point, svg }, icon, divIcon, point, svg };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

const VIEWPORT = { pad: () => ({ contains: () => true }) };
vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="mock-leaflet">{children}</div>,
  TileLayer: () => null,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  Pane: ({ name, children }) => <div data-testid="ring-pane" data-name={name}>{children}</div>,
  CircleMarker: ({ radius, color }) => (
    <i data-testid="ring-circle" data-radius={radius} data-colour={color ?? ''} />
  ),
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500 }),
    getZoom: () => 9,
    getBounds: () => VIEWPORT,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    removeLayer: () => {},
  }),
}));

vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));

/** The props each label layer last received: `chromeKey` is what this file asserts on. */
const labelProps = { labels: [] };
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => {
    labelProps.labels.push(props);
    return (
      <button type="button" data-testid="probe-chip" onClick={() => props.onSelect('Bamburgh-0')}>
        chip
      </button>
    );
  },
}));
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: () => <div data-testid="probe-callout" />,
}));

let mockIsMobile = false;
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
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
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { chromeHeightOf } from '../utils/askCamera.js';
import { windowKey } from '../utils/windowKeys.js';
import { SATURDAY } from './bigSeasFixtures.js';

const TODAY = '2026-10-22';
const SAT = '2026-10-24';
const NE = 'North East';

const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: NE, regionName: NE, bortleClass: 4,
};

function heatProp() {
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map([[windowKey(TODAY, 'SUNSET'), [{
      id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [5],
    }]]]),
    windows: [{
      key: windowKey(TODAY, 'SUNSET'),
      date: TODAY,
      targetType: 'SUNSET',
      label: 'Tonight sunset',
      time: '18:12',
      bestRating: 5,
      conf: 1,
    }],
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
    tideByWindow: new Map(),
  };
}

function makeLocation() {
  return {
    id: SPOT.id,
    name: SPOT.name,
    lat: SPOT.lat,
    lon: SPOT.lng,
    regionName: NE,
    bortleClass: SPOT.bortleClass,
    locationType: ['LANDSCAPE'],
    tideType: ['HIGH'],
    forecastsByDate: new Map([[TODAY, {
      sunset: { rating: 5, solarEventTime: `${TODAY}T17:12:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }]]),
  };
}

/** A served topic. Row badges are NEVER involved: the chips must come from this list alone. */
function topic(overrides = {}) {
  return {
    type: 'KING_TIDE', label: 'King tide', date: TODAY, eventType: null, regions: [NE],
    locationNames: [SPOT.name], ...overrides,
  };
}

const HOT_TOPICS = [
  topic(),
  topic({ date: SAT }),
  topic({ type: 'DUST', label: 'Dust', eventType: 'SUNSET' }),
  topic({ type: 'AURORA', label: 'Aurora', eventType: 'NIGHT', regions: [], locationNames: [] }),
];

/**
 * The pane owns the date and the visibility; this stands in for it, with buttons to move to Saturday
 * and back and to hide and show the pane.
 */
function Pane({
  hotTopics, bigSeas, runId, origin = null, spots = [SPOT], extraLocations = [],
}) {
  const [date, setDate] = React.useState(TODAY);
  const [paneVisible, setPaneVisible] = React.useState(true);
  return (
    <>
      <button type="button" data-testid="go-sat" onClick={() => setDate(SAT)}>sat</button>
      <button type="button" data-testid="go-today" onClick={() => setDate(TODAY)}>today</button>
      <button type="button" data-testid="hide-pane" onClick={() => setPaneVisible(false)}>hide</button>
      <button type="button" data-testid="show-pane" onClick={() => setPaneVisible(true)}>show</button>
      <MapView
        locations={[makeLocation(), ...extraLocations]}
        date={date}
        forecastDates={[TODAY, SAT]}
        autoEventType="SUNSET"
        heat={{ ...heatProp(), spots, areaSpots: spots }}
        origin={origin}
        paneVisible={paneVisible}
        runId={runId}
        bigSeas={bigSeas}
        hotTopics={hotTopics}
        onSelectDate={(d) => setDate(d)}
      />
    </>
  );
}

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(<Pane hotTopics={HOT_TOPICS} bigSeas={undefined} runId={null} {...props} />);
  });
  return result;
}

const chip = (type) => screen.getByTestId(`wf-topic-chip-${type}`);
const press = (type) => act(async () => { fireEvent.click(chip(type)); });
const pane = () => screen.getByTestId('wf-map-pane');
const status = () => screen.getByTestId('map-status').textContent;

beforeEach(() => {
  localStorage.clear();
  mockIsMobile = false;
  labelProps.labels.length = 0;
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView: the chips row', () => {
  it('lists the served topics of the window on screen, inside the chrome group, under the control', async () => {
    await renderMap();
    const chrome = screen.getByTestId('wf-map-chrome-tl');
    const group = within(chrome).getByRole('group', { name: 'Hot topics on this window' });
    expect(within(group).getAllByRole('button').map((b) => b.getAttribute('data-topic-type')))
      .toEqual(['KING_TIDE', 'DUST', 'AURORA']);
    expect(chip('KING_TIDE')).toHaveAttribute('aria-label', 'King tide, 1 coastal place');
    expect(chip('DUST')).toHaveAttribute('aria-label', 'Dust, 1 place');
  });

  it('says "No hot topics on this window" when none is served, on desktop', async () => {
    await renderMap({ hotTopics: [] });
    expect(screen.getByTestId('wf-topics-empty')).toBeInTheDocument();
  });

  it('renders nothing for an empty window on the phone', async () => {
    mockIsMobile = true;
    await renderMap({ hotTopics: [] });
    expect(screen.queryByTestId('wf-topics')).toBeNull();
  });
});

describe('MapView: the chrome height on a day WITHOUT big seas', () => {
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  beforeEach(() => {
    // jsdom has no layout: the chrome group is 96px tall once the chips row is in it.
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get() { return this.dataset?.testid === 'wf-map-chrome-tl' ? 96 : 0; },
    });
  });
  afterEach(() => {
    if (original) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', original);
    else delete HTMLElement.prototype.offsetHeight;
  });

  it('publishes --wf-chrome-tl-h, so the landing card, panel, phone toast and Ask all clear the chips', async () => {
    await renderMap({ runId: '2026-01-15T04:00:00' });
    expect(screen.queryByTestId('wf-big-seas')).toBeNull();
    const frame = screen.getByTestId('wf-map-chrome-tl').parentElement;
    expect(frame.style.getPropertyValue('--wf-chrome-tl-h')).toBe('96px');
    // The landing card is inside the frame that carries it, so its `calc(var(--wf-chrome-tl-h) ...)` reads it.
    expect(frame.contains(screen.getByTestId('wf-land'))).toBe(true);
    // Ask's camera reads the same published figure from any element in the frame.
    expect(chromeHeightOf(screen.getByTestId('mock-leaflet'))).toBe(96);
  });
});


/** One Big seas day on today's date with one structure that IS in this one-spot roster (locationId 1). */
const SEAS_TODAY = {
  ...SATURDAY,
  date: TODAY,
  structures: [{ ...SATURDAY.structures[0], locationId: 1 }],
};
const ui = (props = {}) => <Pane hotTopics={HOT_TOPICS} bigSeas={undefined} runId={null} {...props} />;
const ringCircles = () => screen.queryAllByTestId('ring-circle');
const baseRings = () => ringCircles().filter((c) => c.dataset.radius === '11');

describe('MapView: the selection', () => {
  it('toggles a chip on and off; the pane is marked (the dim) while rings are drawn', async () => {
    await renderMap();
    expect(pane()).not.toHaveAttribute('data-topic-selected');
    await press('KING_TIDE');
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'true');
    expect(pane()).toHaveAttribute('data-topic-selected', 'true');
    await press('KING_TIDE');
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'false');
    expect(pane()).not.toHaveAttribute('data-topic-selected');
  });

  it('⚠️ dims nothing when no ring is drawn: a whole-sky topic, or a topic with no place that joined', async () => {
    await renderMap({
      hotTopics: [
        topic({ locationNames: [] }),
        topic({ type: 'AURORA', label: 'Aurora', eventType: 'NIGHT', regions: [], locationNames: [] }),
      ],
    });
    await press('AURORA');
    expect(chip('AURORA')).toHaveAttribute('aria-pressed', 'true');
    expect(pane()).not.toHaveAttribute('data-topic-selected');
    await press('KING_TIDE');
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'true');
    expect(baseRings()).toHaveLength(0);
    expect(pane()).not.toHaveAttribute('data-topic-selected');
  });

  it('persists to a window that carries the same type, and clears on one that does not', async () => {
    await renderMap();
    await press('KING_TIDE');
    await act(async () => { fireEvent.click(screen.getByTestId('go-sat')); });
    // Saturday carries a king tide: the selection followed.
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'true');
    expect(pane()).toHaveAttribute('data-topic-selected', 'true');

    await act(async () => { fireEvent.click(screen.getByTestId('go-today')); });
    await press('DUST');
    await act(async () => { fireEvent.click(screen.getByTestId('go-sat')); });
    // Saturday has no dust: the selection ends with it, and says so.
    expect(screen.queryByTestId('wf-topic-chip-DUST')).toBeNull();
    expect(pane()).not.toHaveAttribute('data-topic-selected');
    expect(status()).toBe('Topic selection cleared: it is not on this window.');
  });

  it('does not touch the star floor, stand-down or any persisted filter', async () => {
    await renderMap();
    const before = JSON.stringify(Object.entries(localStorage));
    await press('KING_TIDE');
    await press('KING_TIDE');
    expect(JSON.stringify(Object.entries(localStorage))).toBe(before);
  });
});

describe('MapView: the announcement', () => {
  it('goes through the existing map-status region - and there is no second live region', async () => {
    await renderMap();
    const liveRegions = screen.getAllByRole('status').length;
    await press('KING_TIDE');
    expect(status()).toBe('King tide selected. 1 place ringed on the map.');
    await press('KING_TIDE');
    expect(status()).toBe('King tide cleared.');
    expect(screen.getAllByRole('status')).toHaveLength(liveRegions);
  });

  it('says a whole-sky selection is sky-wide and prints the one-line notice in the row, with no rings', async () => {
    await renderMap();
    // Aurora is a NIGHT topic: it sits on this evening's sunset window.
    await press('AURORA');
    expect(status()).toContain('Sky-wide');
    expect(screen.getByTestId('wf-topic-skywide').textContent).toContain('Aurora possible');
    expect(ringCircles()).toHaveLength(0);
  });

  it('⚠️ speaks the "N ringed" clause ONLY when rings are drawn', async () => {
    await renderMap({ hotTopics: [topic({ locationNames: ['Nowhere Bay'] })] });
    await press('KING_TIDE');
    expect(status()).toBe('King tide selected.');
  });

  it('uses the chip\'s own noun: Big seas rings structures', async () => {
    await renderMap({ bigSeas: [SEAS_TODAY] });
    await press('BIG_SEAS');
    expect(status()).toBe('Big seas selected. 1 structure ringed on the map.');
  });

  it('⚠️ is ONE-SHOT: hiding the pane empties the region, so showing it again re-speaks nothing', async () => {
    await renderMap();
    await press('KING_TIDE');
    expect(status()).not.toBe('');
    await act(async () => { fireEvent.click(screen.getByTestId('hide-pane')); });
    expect(status()).toBe('');
    await act(async () => { fireEvent.click(screen.getByTestId('show-pane')); });
    expect(status()).toBe('');
  });

  it('⚠️ is cleared when the window changes (a selection that follows says nothing new)', async () => {
    await renderMap();
    await press('KING_TIDE');
    expect(status()).not.toBe('');
    await act(async () => { fireEvent.click(screen.getByTestId('go-sat')); });
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'true');
    expect(status()).toBe('');
  });

  it('speaks the auto-clear sentence ONCE: not after the pane is re-shown', async () => {
    await renderMap();
    await press('DUST');
    await act(async () => { fireEvent.click(screen.getByTestId('go-sat')); });
    expect(status()).toBe('Topic selection cleared: it is not on this window.');
    await act(async () => { fireEvent.click(screen.getByTestId('hide-pane')); });
    await act(async () => { fireEvent.click(screen.getByTestId('show-pane')); });
    expect(status()).toBe('');
  });

  it('times out: the sentence is gone a few seconds after it was spoken', async () => {
    vi.useRealTimers();
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
    await renderMap();
    await press('KING_TIDE');
    expect(status()).not.toBe('');
    await act(async () => { vi.advanceTimersByTime(5000); });
    expect(status()).not.toBe('');
    await act(async () => { vi.advanceTimersByTime(1500); });
    expect(status()).toBe('');
  });
});

describe('MapView: the rings', () => {
  it('draws one ring per place for a located topic, in Heat', async () => {
    await renderMap();
    expect(ringCircles()).toHaveLength(0);
    await press('KING_TIDE');
    const rings = ringCircles().filter((c) => c.dataset.radius === '11');
    expect(rings).toHaveLength(1);
    expect(rings[0].dataset.colour).toBe('#6FA8B0');
    expect(screen.getByTestId('ring-pane').dataset.name).toBe('wf-topic-rings');
    await press('KING_TIDE');
    expect(ringCircles()).toHaveLength(0);
  });

  it('rings Big seas\' structures by id and keeps its chip on a full day', async () => {
    const { rerender } = await renderMap({ bigSeas: [{ ...SATURDAY, date: TODAY }] });
    // The fixture's structures (243, 28, 21) are not in this one-spot roster: the chip says so by
    // carrying no where text, and still lists.
    expect(chip('BIG_SEAS')).toHaveAttribute('aria-label', 'Big seas');
    rerender(ui({ bigSeas: [SEAS_TODAY] }));
    expect(chip('BIG_SEAS')).toHaveAttribute('aria-label', 'Big seas, 1 structure');
    await press('BIG_SEAS');
    expect(baseRings()).toHaveLength(1);
  });
});

describe('MapView: Escape', () => {
  it('clears the topic AFTER the callout: one press, one level', async () => {
    await renderMap();
    await press('KING_TIDE');
    await act(async () => { fireEvent.click(screen.getByTestId('probe-chip')); });
    expect(screen.getByTestId('probe-callout')).toBeInTheDocument();

    fireEvent.keyDown(chip('KING_TIDE'), { key: 'Escape' });
    expect(screen.queryByTestId('probe-callout')).toBeNull();
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'true');

    fireEvent.keyDown(chip('KING_TIDE'), { key: 'Escape' });
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'false');
    expect(status()).toBe('Topic selection cleared.');
  });

  it('⚠️ inside the pane the landing card defers while a topic is selected: one press never closes two levels', async () => {
    await renderMap({ runId: '2026-01-15T04:00:00' });
    expect(screen.getByTestId('wf-land')).toBeInTheDocument();
    await press('KING_TIDE');

    fireEvent.keyDown(chip('KING_TIDE'), { key: 'Escape' });
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('wf-land')).toBeInTheDocument();

    fireEvent.keyDown(chip('KING_TIDE'), { key: 'Escape' });
    expect(screen.queryByTestId('wf-land')).toBeNull();
  });

  it('⚠️ with focus on <body> (the pane handler cannot hear it) the card clears the topic itself, ONE level, then dismisses on the next', async () => {
    await renderMap({ runId: '2026-01-15T04:00:00' });
    await press('KING_TIDE');
    expect(document.activeElement).toBe(document.body);

    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('wf-land')).toBeInTheDocument();
    expect(status()).toBe('Topic selection cleared.');

    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByTestId('wf-land')).toBeNull();
  });

  it('with no topic selected a body Escape still dismisses the card, as it did before the chips', async () => {
    await renderMap({ runId: '2026-01-15T04:00:00' });
    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByTestId('wf-land')).toBeNull();
  });
});

describe('MapView: the chips row on a phone', () => {
  it('shows the chips under the pill with the where text in the DOM (CSS hides it inside the media query only)', async () => {
    mockIsMobile = true;
    await renderMap();
    const chrome = screen.getByTestId('wf-map-chrome-tl');
    const pill = within(chrome).getByTestId('wf-win-pill');
    const group = within(chrome).getByTestId('wf-topics');
    expect(pill.compareDocumentPosition(group) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(group).getAllByRole('button')).toHaveLength(3);
    expect(group.querySelector('.wf-tch-w')).not.toBeNull();
  });

  it('draws nothing on an empty window', async () => {
    mockIsMobile = true;
    await renderMap({ hotTopics: [] });
    expect(screen.queryByTestId('wf-topics')).toBeNull();
  });

  it('⚠️ is NOT drawn while the big-seas line shows (owner rule): Big seas is the line there', async () => {
    mockIsMobile = true;
    await renderMap({ bigSeas: [SEAS_TODAY] });
    expect(screen.getByTestId('wf-big-seas')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-topics')).toBeNull();
  });

  it('the compact big-seas form (a later day) hides it too', async () => {
    mockIsMobile = true;
    await renderMap({ bigSeas: [SATURDAY] });
    expect(screen.getByTestId('wf-big-seas').dataset.form).toBe('compact');
    expect(screen.queryByTestId('wf-topics')).toBeNull();
  });

  it('desktop is unchanged: the chips AND the big-seas line show together', async () => {
    mockIsMobile = false;
    await renderMap({ bigSeas: [SEAS_TODAY] });
    expect(screen.getByTestId('wf-big-seas')).toBeInTheDocument();
    expect(screen.getByTestId('wf-topics')).toBeInTheDocument();
  });

  it('⚠️ a selection made before the row hides stays: rings still draw, Escape still clears it, focus parks on the pane', async () => {
    const { rerender } = await renderMap({ bigSeas: [SEAS_TODAY] });
    await press('KING_TIDE');
    chip('KING_TIDE').focus();
    expect(baseRings()).toHaveLength(1);

    mockIsMobile = true;
    await act(async () => { rerender(ui({ bigSeas: [SEAS_TODAY] })); });
    expect(screen.queryByTestId('wf-topics')).toBeNull();
    // Selection and rings survive the hidden row, and so does the dim.
    expect(baseRings()).toHaveLength(1);
    expect(pane()).toHaveAttribute('data-topic-selected', 'true');
    // The focused chip went with the row: focus lands on the pane root, not <body>.
    expect(document.activeElement).toBe(pane());

    fireEvent.keyDown(pane(), { key: 'Escape' });
    expect(baseRings()).toHaveLength(0);
    expect(pane()).not.toHaveAttribute('data-topic-selected');
  });
});

describe('MapView: the scope is the Plan tab\'s own list', () => {
  const AWAY_CUMBRIA = { name: 'Cumbria' };

  it('⚠️ away from home, with that region having NO heat spots, region-scoped topics are still filtered to it', async () => {
    // `areaSpots` is empty (no spot in Cumbria): derived from it the scope would be [] = no filter, and
    // the North East's dust and tide would show on a Cumbria plan. `scopeRegions` says [Cumbria].
    await renderMap({ origin: AWAY_CUMBRIA, spots: [] });
    expect(screen.queryByTestId('wf-topic-chip-DUST')).toBeNull();
    expect(screen.queryByTestId('wf-topic-chip-KING_TIDE')).toBeNull();
    // Whole-sky topics are exempt from the scope.
    expect(screen.getByTestId('wf-topic-chip-AURORA')).toBeInTheDocument();
  });

  it('at home the North East topics show', async () => {
    await renderMap();
    expect(screen.getByTestId('wf-topic-chip-DUST')).toBeInTheDocument();
  });
});

describe('MapView: the repaint key', () => {
  const OriginalRO = globalThis.ResizeObserver;
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  let observers;
  let height;
  beforeEach(() => {
    observers = [];
    height = 70;
    globalThis.ResizeObserver = class {
      constructor(cb) { this.cb = cb; observers.push(this); }

      observe() {}

      disconnect() {}
    };
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get() { return this.dataset?.testid === 'wf-map-chrome-tl' ? height : 0; },
    });
  });
  afterEach(() => {
    globalThis.ResizeObserver = OriginalRO;
    if (original) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', original);
    else delete HTMLElement.prototype.offsetHeight;
  });

  const lastKey = () => labelProps.labels[labelProps.labels.length - 1].chromeKey;

  it('hands the label layer a key that moves when the chrome group\'s height does, and only then', async () => {
    await renderMap();
    const before = lastKey();
    // A resize that leaves the height alone repaints nothing.
    await act(async () => { observers.forEach((o) => o.cb()); });
    expect(lastKey()).toBe(before);
    // A taller group (a toggled sky-wide line, a wrapped chips row) does.
    height = 104;
    await act(async () => { observers.forEach((o) => o.cb()); });
    expect(lastKey()).not.toBe(before);
  });
});

describe('MapView: the ringed places are named (topic-ring-names-plan.md)', () => {
  /** A place the default floor withholds (unrated for this window), yet one the topic rings. */
  const HIDDEN = {
    id: 2, name: 'Hidden-2', lat: 55.2, lng: -1.6, rid: NE, regionName: NE, bortleClass: 4,
  };
  const hiddenLocation = () => ({
    id: HIDDEN.id, name: HIDDEN.name, lat: HIDDEN.lat, lon: HIDDEN.lng, regionName: NE,
    bortleClass: 4, locationType: ['LANDSCAPE'], tideType: ['HIGH'], forecastsByDate: new Map(),
  });
  const surge = (names) => [
    topic({
      type: 'STORM_SURGE', label: 'Storm surge', eventType: 'SUNSET', locationNames: names,
    }),
    topic({ type: 'AURORA', label: 'Aurora', eventType: 'NIGHT', regions: [], locationNames: [] }),
  ];
  const withHidden = (names = [SPOT.name, HIDDEN.name]) => ({
    hotTopics: surge(names), spots: [SPOT, HIDDEN], extraLocations: [hiddenLocation()],
  });
  const lastLabels = () => labelProps.labels[labelProps.labels.length - 1].spots;
  const namesOf = (spots) => spots.map((sp) => sp.name);

  it('with no topic selected the layer gets the plain label spots: nothing ringed, nothing appended', async () => {
    await renderMap(withHidden());
    expect(namesOf(lastLabels())).toEqual([SPOT.name]);
    expect(lastLabels().some((sp) => sp.topicRing || sp.topicRingAdded)).toBe(false);
    // (With no ring drawn `chipSpots` IS `labelSpots`, the very list the tide strip and counts read.)
  });

  it('⚠️ marks a ringed place that is already drawn and APPENDS the ringed one the floor withheld, in Heat', async () => {
    await renderMap(withHidden());
    const footer = screen.getByTestId('wf-map-counts-footer').textContent;
    await press('STORM_SURGE');
    // The counts footer (and the tide strip) read `labelSpots`: selecting a topic moves neither.
    expect(screen.getByTestId('wf-map-counts-footer').textContent).toBe(footer);
    const spots = lastLabels();
    expect(namesOf(spots)).toEqual([SPOT.name, HIDDEN.name]);
    expect(spots[0]).toMatchObject({ topicRing: true });
    expect(spots[0].topicRingAdded).toBeUndefined();
    expect(spots[1]).toMatchObject({
      name: HIDDEN.name, topicRing: true, topicRingAdded: true, lat: HIDDEN.lat, lng: HIDDEN.lng, id: HIDDEN.id,
    });
    // Deselecting removes it again.
    await press('STORM_SURGE');
    expect(namesOf(lastLabels())).toEqual([SPOT.name]);
  });

  it('skips a ringed place with no catalogue row, and one with no finite coordinates', async () => {
    const broken = { ...hiddenLocation(), id: 3, name: 'Broken-3', lat: null };
    await renderMap({
      hotTopics: surge([SPOT.name, HIDDEN.name, 'Broken-3']),
      spots: [SPOT, HIDDEN, { ...HIDDEN, id: 3, name: 'Broken-3' }],
      extraLocations: [broken],
    });
    await press('STORM_SURGE');
    expect(namesOf(lastLabels())).toEqual([SPOT.name]);
  });

  it('draws the ring past the filters and names it too: a drive filter drops nothing from the appended names', async () => {
    await renderMap(withHidden());
    await press('STORM_SURGE');
    expect(baseRings()).toHaveLength(2);
    expect(namesOf(lastLabels())).toHaveLength(2);
  });

  it('Big seas appends and marks nothing: its structures are named by the big-seas chips', async () => {
    await renderMap({ bigSeas: [SEAS_TODAY] });
    const before = lastLabels();
    await press('BIG_SEAS');
    expect(baseRings()).toHaveLength(1);
    expect(lastLabels().some((sp) => sp.topicRing || sp.topicRingAdded)).toBe(false);
    expect(namesOf(lastLabels())).toEqual(namesOf(before));
  });

  it('a whole-sky topic (no rings drawn) leaves the spots untouched', async () => {
    await renderMap(withHidden());
    await press('AURORA');
    expect(lastLabels().some((sp) => sp.topicRing || sp.topicRingAdded)).toBe(false);
  });
});
