/**
 * `MapView` + the real `MapTopicStrip`, `WindowControl`, `MapTideStrip` and `buildMapEvents`: the
 * four-day strip's mount, its coupling to the rest of the bottom-left chrome and its presses
 * (hot-topics-by-day-plan §3 M2).
 *
 * <p>Not mocked: `MapView`, the strip, the tide strip, the window control, the event list. Mocked:
 * Leaflet, the heat layer, the callout (a probe that prints the height it was handed) and the two
 * label layers (probes that expose `chromeKey` and a button that selects the fixture spot).
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
  Pane: ({ children }) => <div>{children}</div>,
  CircleMarker: () => null,
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
  default: (props) => <div data-testid="probe-callout">{JSON.stringify(props.topicStripHeight)}</div>,
}));

let mockIsMobile = false;
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
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
import { TOPIC_STRIP_OPEN_KEY, TOPIC_STRIP_OPEN_SHEET_KEY } from '../components/map/MapTopicStrip.jsx';
import { windowKey } from '../utils/windowKeys.js';
import { FRIDAY_HW } from './bigSeasFixtures.js';

const TODAY = '2026-10-22';
const SAT = '2026-10-24';
const NE = 'North East';

const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: NE, regionName: NE, bortleClass: 4,
};

const TIDE = {
  locationName: 'Bamburgh Castle',
  state: 'LOW',
  direction: 'RISING',
  range: '4.3 m',
  rangeAnomaly: 'about average',
  curve: [0, 0.3, 0.7, 1, 0.6, 0.2, 0],
  windowPosition: 0.32,
  windowLevel: 0.2,
  sunrisePosition: 0.32,
  sunsetPosition: 0.7,
  extremes: [{ kind: 'LW', position: 0.3, time: '07:30' }],
  heightAtWindow: '0.9 m',
};

function heatProp({ tide = null, tideSat = null } = {}) {
  const satWindow = tideSat ? [{
    key: windowKey(SAT, 'SUNRISE'),
    date: SAT,
    targetType: 'SUNRISE',
    label: 'Saturday sunrise',
    time: '07:20',
    bestRating: 4,
    conf: 1,
    tide: tideSat,
  }] : [];
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT],
    areaSpots: [SPOT],
    pointsByKey: new Map([[windowKey(TODAY, 'SUNSET'), [{
      id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [5],
    }]], ...(tideSat ? [[windowKey(SAT, 'SUNRISE'), [{
      id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [4],
    }]]] : [])]),
    windows: [{
      key: windowKey(TODAY, 'SUNSET'),
      date: TODAY,
      targetType: 'SUNSET',
      label: 'Tonight sunset',
      time: '18:12',
      bestRating: 5,
      conf: 1,
      ...(tide ? { tide } : {}),
    }, ...satWindow],
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
    }], [SAT, {
      sunrise: { rating: 4, solarEventTime: `${SAT}T07:20:00`, fierySkyPotential: 60, goldenHourPotential: 55 },
    }]]),
  };
}

const topic = (overrides = {}) => ({
  type: 'KING_TIDE', label: 'King tide', date: TODAY, eventType: null, regions: [NE],
  locationNames: [SPOT.name], ...overrides,
});

// King tide today and Saturday (not Friday: two bars); dust tonight; an aurora night tonight.
const HOT_TOPICS = [
  topic(),
  topic({ date: SAT }),
  topic({ type: 'DUST', label: 'Dust', eventType: 'SUNSET' }),
  topic({ type: 'AURORA', label: 'Aurora', eventType: 'NIGHT', regions: [], locationNames: [] }),
];

function Pane({
  hotTopics, tide = null, tideSat = null, startDate = TODAY, bigSeas = undefined, forecastDates = [TODAY, SAT],
  planHandoff = null, externalDate = null, tideLocked = false,
}) {
  const [date, setDate] = React.useState(startDate);
  return (
    <MapView
      locations={[makeLocation()]}
      date={externalDate ?? date}
      forecastDates={forecastDates}
      autoEventType="SUNSET"
      heat={heatProp({ tide, tideSat })}
      paneVisible
      hotTopics={hotTopics}
      bigSeas={bigSeas}
      onSelectDate={(d) => setDate(d)}
      planHandoff={planHandoff}
      tideLocked={tideLocked}
    />
  );
}

async function renderMap(props = {}) {
  let result;
  await act(async () => {
    result = render(<Pane hotTopics={HOT_TOPICS} {...props} />);
  });
  return result;
}

const strip = () => screen.getByTestId('wf-topic-strip');
const toggle = () => screen.getByTestId('wf-topic-strip-toggle');
const press = (testid) => act(async () => { fireEvent.click(screen.getByTestId(testid)); });
const chip = (type) => screen.getByTestId(`wf-topic-chip-${type}`);

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

describe('MapView: the four-day strip mount', () => {
  it('mounts inside the bottom-left chrome on desktop, with the topics of the next four days', async () => {
    await renderMap();
    const chromeBl = screen.getByTestId('wf-map-chrome-bl');
    expect(chromeBl.contains(strip())).toBe(true);
    expect(within(strip()).getByTestId('wf-topic-strip-label-KING_TIDE')).toBeInTheDocument();
    expect(within(strip()).getByTestId('wf-topic-strip-label-DUST')).toBeInTheDocument();
    expect(within(strip()).getByTestId('wf-topic-strip-label-AURORA')).toBeInTheDocument();
  });

  it('is not mounted when there is no topic on the four days (no empty panel)', async () => {
    await renderMap({ hotTopics: [] });
    expect(screen.queryByTestId('wf-topic-strip')).toBeNull();
  });

  it('is NOT mounted in the bottom-left chrome on a phone (M4 puts it in the peek sheet)', async () => {
    mockIsMobile = true;
    await renderMap();
    expect(screen.queryByTestId('wf-topic-strip')).toBeNull();
    expect(screen.queryByTestId('wf-map-chrome-bl')).toBeNull();
  });

  it('desktop markup is exactly the panel variant: no sheet class, no variant attribute', async () => {
    await renderMap();
    expect(strip().className).toBe('wf-topic-strip');
    expect(strip().hasAttribute('data-variant')).toBe(false);
    expect(screen.queryByTestId('wf-map-peek')).toBeNull();
    expect(document.querySelectorAll('.wf-topic-strip-sheet')).toHaveLength(0);
  });

  it('draws the current window\'s column shaded and its bars current (Thursday sunset is column 1)', async () => {
    await renderMap();
    expect(screen.getByTestId('wf-topic-strip-now').style.gridArea).toMatch(/^1 \/ 3 \//);
    expect(screen.getByTestId('wf-topic-bar-DUST-1')).toHaveAttribute('data-current', 'true');
  });
});

describe('MapView: the tide at the light (Pro and Admin)', () => {
  // The served rollup of tonight's sunset, with the Pro-only alignment fields the server adds.
  const LIGHT_TIDE = {
    ...TIDE,
    nearestType: 'HW',
    nearestTime: '18:30',
    nearestOffset: '18m after sunset',
    lightAlignment: 'HW',
    nearestHeight: '4.3 m',
    eventTime: '18:12',
  };

  it('marks the window, writes the detail line and puts the clause on the chip for a Pro reader', async () => {
    await renderMap({ tide: LIGHT_TIDE });
    await act(async () => { fireEvent.click(toggle()); });

    expect(screen.getByTestId('wf-topic-strip-mark-KING_TIDE-1')).toHaveTextContent('HW');
    expect(screen.getByTestId('wf-topic-strip-tide-detail')).toHaveTextContent(
      'Thu sunset 18:12 \u00B7 high water 18:30, 18m after \u00B7 4.3 m \u00B7 Bamburgh Castle',
    );
    expect(within(chip('KING_TIDE')).getByTestId('wf-topic-light')).toHaveTextContent('HW on the light');
  });

  it('draws none of it for a LITE reader, who keeps the locked row and the plain chip', async () => {
    await renderMap({ tide: LIGHT_TIDE, tideLocked: true });
    await act(async () => { fireEvent.click(toggle()); });

    expect(screen.queryByTestId('wf-topic-strip-grid')).not.toBeNull();
    expect(document.querySelector('.wf-tl-m')).toBeNull();
    expect(screen.queryByTestId('wf-topic-strip-tide-detail')).toBeNull();
    expect(screen.queryByTestId('wf-topic-light')).toBeNull();
    expect(screen.queryByTestId('wf-topic-strip-sub-KING_TIDE')).toBeNull();
  });
});

describe('MapView: the strip collapses under the tide strip', () => {
  it('holds the grid closed under an open tide strip: header enabled, preference kept, flex order kept', async () => {
    localStorage.setItem(TOPIC_STRIP_OPEN_KEY, '1');
    await renderMap({ tide: TIDE });
    const chromeBl = screen.getByTestId('wf-map-chrome-bl');
    const tideStrip = screen.getByTestId('wf-tide-strip');
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(toggle()).not.toHaveAttribute('aria-disabled');
    expect(toggle()).toHaveAccessibleDescription('Opening it collapses the tide strip');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    expect(screen.queryByTestId('wf-tide-strip-collapsed')).toBeNull();
    // Flex order: the topic strip comes BEFORE the tide strip.
    expect(strip().compareDocumentPosition(tideStrip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(chromeBl.lastElementChild).toBe(tideStrip);
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('1');
  });

  it('a press on the held header opens the grid and collapses the tide strip, focus staying on the header', async () => {
    await renderMap({ tide: TIDE });
    toggle().focus();
    await act(async () => { fireEvent.click(toggle()); });
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
    expect(screen.getByTestId('wf-tide-strip-collapsed')).toBeInTheDocument();
    expect(document.activeElement).toBe(toggle());
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('1');
  });

  it('the tide strip\'s Open puts the tide strip back and holds the grid again, its preference still 1', async () => {
    await renderMap({ tide: TIDE });
    await act(async () => { fireEvent.click(toggle()); });
    await press('wf-tide-strip-toggle');
    expect(screen.queryByTestId('wf-tide-strip-collapsed')).toBeNull();
    expect(screen.getByTestId('wf-tide-strip-toggle')).not.toHaveTextContent('Open');
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('1');
  });

  it('closing the yielded grid brings the tide strip back open', async () => {
    await renderMap({ tide: TIDE });
    await act(async () => { fireEvent.click(toggle()); });
    expect(screen.getByTestId('wf-tide-strip-collapsed')).toBeInTheDocument();
    await act(async () => { fireEvent.click(toggle()); });
    expect(screen.queryByTestId('wf-tide-strip-collapsed')).toBeNull();
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('0');
  });

  it('the yield is per window: another tidal window is held again with the tide strip open', async () => {
    await renderMap({ tide: TIDE, tideSat: TIDE });
    await act(async () => { fireEvent.click(toggle()); });
    expect(screen.getByTestId('wf-tide-strip-collapsed')).toBeInTheDocument();
    // Thursday sunset -> Saturday sunrise (the other tidal window).
    await press('wf-win-next');
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent('Sunrise Saturday');
    expect(screen.getByTestId('wf-tide-strip')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-tide-strip-collapsed')).toBeNull();
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    // The reader's own tide-strip state was never touched by the yield.
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBe('1');
    // Back on the first window the yield was cleared as the window moved away: it does not revive.
    await press('wf-win-prev');
    expect(screen.getByTestId('wf-win-pill')).not.toHaveTextContent('Sunrise Saturday');
    expect(screen.getByTestId('wf-tide-strip')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-tide-strip-collapsed')).toBeNull();
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
  });

  it('a bar press in the yielded grid that moves to another tidal window keeps the grid open', async () => {
    await renderMap({ tide: TIDE, tideSat: TIDE });
    await act(async () => { fireEvent.click(toggle()); });
    await press('wf-topic-bar-KING_TIDE-4');
    expect(screen.getByTestId('wf-topic-bar-KING_TIDE-4')).toHaveAttribute('data-current', 'true');
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
    expect(screen.getByTestId('wf-tide-strip-collapsed')).toBeInTheDocument();
  });

  it('a reader who collapses the tide strip with its own toggle does not pop the grid open', async () => {
    localStorage.setItem(TOPIC_STRIP_OPEN_KEY, '1');
    await renderMap({ tide: TIDE });
    await press('wf-tide-strip-toggle');
    expect(screen.getByTestId('wf-tide-strip-collapsed')).toBeInTheDocument();
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
  });

  it('opens again by itself when the tide strip goes (the window no longer carries a tide)', async () => {
    const { rerender } = await renderMap({ tide: TIDE });
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    await act(async () => { rerender(<Pane hotTopics={HOT_TOPICS} tide={null} />); });
    expect(screen.queryByTestId('wf-tide-strip')).toBeNull();
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
  });
});

describe('MapView: presses on the strip', () => {
  it('a label whose topic is on the window toggles the topic (the chips and the strip agree)', async () => {
    await renderMap();
    await press('wf-topic-strip-label-DUST');
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('wf-topic-strip-label-DUST')).toHaveAttribute('aria-pressed', 'true');
    await press('wf-topic-strip-label-DUST');
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'false');
  });

  it('a bar on this window selects the topic and stays', async () => {
    await renderMap();
    await press('wf-topic-bar-DUST-1');
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('wf-topic-bar-DUST-1')).toHaveAttribute('data-selected', 'true');
    expect(screen.getByTestId('map-status')).toHaveTextContent('Dust selected.');
  });

  it('a bar on another day moves the window there and selects the topic', async () => {
    await renderMap();
    // King tide: Thursday (columns 0-1) and Saturday (columns 4-5). The Saturday bar is not the current one.
    await press('wf-topic-bar-KING_TIDE-4');
    expect(screen.getByTestId('wf-topic-bar-KING_TIDE-4')).toHaveAttribute('data-current', 'true');
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'true');
    // Spoken once, AFTER the row change has cleared the one-shot status line.
    expect(screen.getByTestId('map-status')).toHaveTextContent('King tide selected; window moved to Saturday sunrise');
  });

  it('a label jump announces the move too, and an earlier sentence is not left pending', async () => {
    await renderMap();
    await press('wf-topic-bar-KING_TIDE-4');
    await press('wf-topic-strip-label-DUST');
    expect(screen.getByTestId('map-status')).toHaveTextContent('Dust selected; window moved to Thursday sunset');
    await press('wf-topic-bar-KING_TIDE-4');
    expect(screen.getByTestId('map-status')).toHaveTextContent('King tide selected; window moved to Saturday sunrise');
    expect(screen.getByTestId('map-status')).not.toHaveTextContent('Dust');
  });

  it('a label whose topic is not on the window jumps to its first window and selects it', async () => {
    await renderMap();
    // Aurora is NIGHT tonight: Thursday sunset + Friday sunrise, so it IS on the window. Dust, on
    // Thursday sunset only, is too; move to Saturday first, where neither is.
    await press('wf-topic-bar-KING_TIDE-4');
    expect(screen.queryByTestId('wf-topic-chip-DUST')).toBeNull();
    await press('wf-topic-strip-label-DUST');
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('wf-topic-bar-DUST-1')).toHaveAttribute('data-current', 'true');
  });

  it('a press whose target window shows the tide strip leaves focus on the strip, never on <body>', async () => {
    // Start on Saturday (no tide, grid open); Thursday's sunset carries the tide strip. The reader is
    // using the grid, so it yields: the grid stays open and the tide strip shows its collapsed row.
    await renderMap({ tide: TIDE, startDate: SAT });
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    screen.getByTestId('wf-topic-strip-label-DUST').focus();
    await act(async () => { fireEvent.click(screen.getByTestId('wf-topic-strip-label-DUST')); });
    expect(screen.getByTestId('wf-tide-strip-collapsed')).toBeInTheDocument();
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    expect(document.activeElement).not.toBe(document.body);
    expect(strip().contains(document.activeElement)).toBe(true);
  });

  it('focus on a bar goes to the pane when the strip unmounts (the topics all go)', async () => {
    const { rerender } = await renderMap();
    screen.getByTestId('wf-topic-bar-DUST-1').focus();
    await act(async () => { rerender(<Pane hotTopics={[]} />); });
    expect(screen.queryByTestId('wf-topic-strip')).toBeNull();
    expect(document.activeElement).toBe(screen.getByTestId('wf-map-pane'));
  });
});

describe('MapView: a door that names a topic (T3)', () => {
  const door = (extra = {}) => ({
    source: 'plan', eventType: 'SUNSET', date: TODAY, region: null, nonce: 1, topicType: 'DUST', ...extra,
  });

  it('selects the named chip on arrival, through the strip\'s own path', async () => {
    await renderMap({ planHandoff: door() });
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('map-status')).toHaveTextContent('Dust selected.');
  });

  it('selects nothing when the door names no topic', async () => {
    await renderMap({ planHandoff: door({ topicType: undefined }) });
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'false');
  });

  it('selects nothing when the landed window does not carry the type', async () => {
    await renderMap({ planHandoff: door({ topicType: 'INVERSION' }) });
    expect(screen.queryByTestId('wf-topic-chip-INVERSION')).toBeNull();
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'false');
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'false');
  });

  it('does not re-select on a repeat render of the same nonce; a reader\'s deselect stays', async () => {
    const { rerender } = await renderMap({ planHandoff: door() });
    await press('wf-topic-chip-DUST');
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'false');
    await act(async () => { rerender(<Pane hotTopics={[...HOT_TOPICS]} planHandoff={door()} />); });
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'false');
  });

  const INVERSION = topic({ type: 'INVERSION', label: 'Inversion', eventType: 'SUNSET' });

  it('a door to a window with no row is spent, not left waiting: nothing is selected', async () => {
    await renderMap({ planHandoff: door({ eventType: 'SUNRISE', topicType: 'DUST' }) });
    expect(screen.queryByTestId('wf-topic-chip-DUST')).toBeNull();
    // Reaching the door's topic by hand afterwards is the reader's own press, not the door firing late.
    await press('wf-topic-bar-DUST-1');
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('wf-topic-bar-DUST-1')).toHaveAttribute('data-selected', 'true');
  });

  it('a door the map did not land on is spent: navigating to its window by hand selects nothing', async () => {
    // The door names Saturday; the pane keeps showing today, so the landed window is not the door's.
    await renderMap({ planHandoff: door({ date: SAT, topicType: 'KING_TIDE' }) });
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'false');
    await press('wf-topic-bar-KING_TIDE-4');
    await press('wf-topic-bar-DUST-1');
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'true');
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'false');
  });

  it('a door that landed elsewhere stays spent when the date later arrives at its window', async () => {
    const mismatched = door({ date: SAT, topicType: 'KING_TIDE' });
    const { rerender } = await renderMap({ planHandoff: mismatched });
    await act(async () => {
      rerender(<Pane hotTopics={HOT_TOPICS} planHandoff={mismatched} externalDate={SAT} />);
    });
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'false');
  });

  it('a reader\'s window step (the pill\'s stepper) before the door applies cancels it', async () => {
    const waiting = door({ topicType: 'INVERSION' });
    const { rerender } = await renderMap({ hotTopics: undefined, planHandoff: waiting });
    await press('wf-win-next');
    await press('wf-win-prev');
    await act(async () => {
      rerender(<Pane hotTopics={[...HOT_TOPICS, INVERSION]} planHandoff={waiting} />);
    });
    expect(chip('INVERSION')).toHaveAttribute('aria-pressed', 'false');
  });

  it('Escape, pressed while the door waits for its data, cancels it', async () => {
    const waiting = door({ topicType: 'INVERSION' });
    const { rerender } = await renderMap({ hotTopics: undefined, planHandoff: waiting });
    await act(async () => { fireEvent.keyDown(screen.getByTestId('wf-map-pane'), { key: 'Escape' }); });
    await act(async () => {
      rerender(<Pane hotTopics={[...HOT_TOPICS, INVERSION]} planHandoff={waiting} />);
    });
    expect(chip('INVERSION')).toHaveAttribute('aria-pressed', 'false');
  });

  it('a door waits for data that has not been served, then applies when it arrives', async () => {
    const waiting = door({ topicType: 'INVERSION' });
    const { rerender } = await renderMap({ hotTopics: undefined, planHandoff: waiting });
    await act(async () => {
      rerender(<Pane hotTopics={[...HOT_TOPICS, INVERSION]} planHandoff={waiting} />);
    });
    expect(chip('INVERSION')).toHaveAttribute('aria-pressed', 'true');
  });

  it('a landed door with its data loaded decides once: an absent chip is spent, a later poll adding it is ignored', async () => {
    const decided = door({ topicType: 'INVERSION' });
    const { rerender } = await renderMap({ planHandoff: decided });
    expect(screen.queryByTestId('wf-topic-chip-INVERSION')).toBeNull();
    await act(async () => {
      rerender(<Pane hotTopics={[...HOT_TOPICS, INVERSION]} planHandoff={decided} />);
    });
    expect(chip('INVERSION')).toHaveAttribute('aria-pressed', 'false');
  });

  it('selects again for a new nonce (a repeat arrival)', async () => {
    const { rerender } = await renderMap({ planHandoff: door() });
    await press('wf-topic-chip-DUST');
    await act(async () => { rerender(<Pane hotTopics={HOT_TOPICS} planHandoff={door({ nonce: 2 })} />); });
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'true');
  });
});

describe('MapView: the counts footer clears the strip only where it overlaps it', () => {
  const originalRect = Element.prototype.getBoundingClientRect;
  const originalHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  const OriginalRO = globalThis.ResizeObserver;
  let footerLeft;
  let tideHeight;
  let observers;
  beforeEach(() => {
    footerLeft = 577;
    tideHeight = 200;
    observers = [];
    globalThis.ResizeObserver = class {
      constructor(cb) { this.cb = cb; observers.push(this); }

      observe() {}

      disconnect() {}
    };
    Element.prototype.getBoundingClientRect = function stubRect() {
      const id = this.dataset?.testid;
      const r = { left: 0, right: 0, top: 0, bottom: 0, width: 0, height: 0, x: 0, y: 0 };
      if (id === 'wf-map-counts-footer') return { ...r, left: footerLeft, right: footerLeft + 127, width: 127 };
      if (id === 'wf-topic-strip') return { ...r, left: 8, right: 448, width: 440 };
      // The real tide strip is 474px wide (index.css `.wf-map-tide-strip`), 34px wider than the topic strip.
      if (id === 'wf-tide-strip') return { ...r, left: 8, right: 482, width: 474 };
      return r;
    };
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get() {
        const id = this.dataset?.testid;
        if (id === 'wf-topic-strip') return 150;
        if (id === 'wf-tide-strip') return tideHeight;
        if (id === 'wf-map-chrome-bl') return 400;
        return 0;
      },
    });
  });
  afterEach(() => {
    globalThis.ResizeObserver = OriginalRO;
    Element.prototype.getBoundingClientRect = originalRect;
    if (originalHeight) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', originalHeight);
    else delete HTMLElement.prototype.offsetHeight;
  });

  const footer = () => screen.getByTestId('wf-map-counts-footer');

  it('at 1280 (footer at x=577, clear of the strip\'s 8..448) nothing moves, and the legend chip is irrelevant', async () => {
    await renderMap();
    expect(footer().style.bottom).toBe('');
  });

  it('at 820 (footer at x=347) it lifts by the strip\'s height plus 16, and not by the whole stack (400)', async () => {
    footerLeft = 347;
    await renderMap();
    expect(footer().style.bottom).toBe('166px');
  });

  it('with the tide strip showing as well it lifts by both heights and the 6px gap between them', async () => {
    footerLeft = 347;
    await renderMap({ tide: TIDE });
    // 150 (strip) + 6 + 200 (tide strip) + 16.
    expect(footer().style.bottom).toBe('372px');
  });

  it('with ONLY the tide strip mounted and no horizontal overlap, the footer is not lifted at all', async () => {
    footerLeft = 577;
    await renderMap({ hotTopics: [], tide: TIDE });
    expect(screen.getByTestId('wf-tide-strip')).toBeInTheDocument();
    expect(footer().style.bottom).toBe('');
  });

  it('with ONLY the tide strip mounted and a horizontal overlap, it lifts by the tide strip plus 16', async () => {
    footerLeft = 347;
    await renderMap({ hotTopics: [], tide: TIDE });
    expect(screen.getByTestId('wf-tide-strip')).toBeInTheDocument();
    expect(footer().style.bottom).toBe('216px');
  });

  it('with both strips mounted, a footer that meets only the tide strip\'s extra width (448..482) still lifts', async () => {
    footerLeft = 460;
    await renderMap({ tide: TIDE });
    // The footer (460..587) is clear of the topic strip (8..448) but inside the union (8..482):
    // 150 (strip) + 6 + 200 (tide strip) + 16.
    expect(footer().style.bottom).toBe('372px');
  });

  it('collapsing the tide strip (its height changing) changes the tide-only lift', async () => {
    footerLeft = 347;
    await renderMap({ hotTopics: [], tide: TIDE });
    expect(footer().style.bottom).toBe('216px');
    tideHeight = 34;
    await act(async () => { fireEvent.click(screen.getByTestId('wf-tide-strip-toggle')); });
    await act(async () => { observers.forEach((o) => o.cb()); });
    // 34 (collapsed tide strip) + 16.
    expect(footer().style.bottom).toBe('50px');
  });

  it('puts the footer back where it was when the window narrows back out of overlap', async () => {
    footerLeft = 347;
    await renderMap();
    expect(footer().style.bottom).toBe('166px');
    footerLeft = 577;
    await act(async () => { window.dispatchEvent(new Event('resize')); });
    expect(footer().style.bottom).toBe('');
  });

  it('leaves the footer alone when the strip is not mounted at all', async () => {
    footerLeft = 347;
    await renderMap({ hotTopics: [] });
    expect(footer().style.bottom).toBe('');
  });
});

describe('MapView: the strip and the rest of the chrome', () => {
  const OriginalRO = globalThis.ResizeObserver;
  const original = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight');
  let observers;
  let blHeight;
  beforeEach(() => {
    observers = [];
    blHeight = 190;
    globalThis.ResizeObserver = class {
      constructor(cb) { this.cb = cb; observers.push(this); }

      observe() {}

      disconnect() {}
    };
    Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
      configurable: true,
      get() {
        const id = this.dataset?.testid;
        if (id === 'wf-map-chrome-bl') return blHeight;
        if (id === 'wf-topic-strip') return 150;
        return 0;
      },
    });
  });
  afterEach(() => {
    globalThis.ResizeObserver = OriginalRO;
    if (original) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', original);
    else delete HTMLElement.prototype.offsetHeight;
  });

  it('publishes the WHOLE bottom-left stack\'s height as --wf-chrome-bl-h on the frame, and removes it with the stack', async () => {
    const { unmount } = await renderMap();
    const frame = screen.getByTestId('wf-map-chrome-bl').parentElement;
    expect(frame.style.getPropertyValue('--wf-chrome-bl-h')).toBe('190px');
    // The counts footer is inside the frame that carries it, so its `calc(var(--wf-chrome-bl-h) ...)` reads it.
    expect(frame.contains(screen.getByTestId('wf-map-counts-footer'))).toBe(true);
    blHeight = 220;
    await act(async () => { observers.forEach((o) => o.cb()); });
    expect(frame.style.getPropertyValue('--wf-chrome-bl-h')).toBe('220px');
    unmount();
    expect(frame.style.getPropertyValue('--wf-chrome-bl-h')).toBe('');
  });

  it('moves the label layers\' repaint key when the stack\'s height does (the strip opening or collapsing)', async () => {
    await renderMap();
    const lastKey = () => labelProps.labels[labelProps.labels.length - 1].chromeKey;
    const before = lastKey();
    await act(async () => { observers.forEach((o) => o.cb()); });
    expect(lastKey()).toBe(before);
    blHeight = 60;
    await act(async () => { fireEvent.click(toggle()); });
    await act(async () => { observers.forEach((o) => o.cb()); });
    expect(lastKey()).not.toBe(before);
  });

  it('moves the repaint key for a bottom-left change even with NO topics (a deliberate widening)', async () => {
    await renderMap({ hotTopics: [] });
    const lastKey = () => labelProps.labels[labelProps.labels.length - 1].chromeKey;
    const before = lastKey();
    blHeight = 36;
    await act(async () => { observers.forEach((o) => o.cb()); });
    expect(lastKey()).not.toBe(before);
  });

  it('hands the callout the strip\'s measured height, so it repaints when the strip changes', async () => {
    await renderMap();
    await act(async () => { fireEvent.click(screen.getByTestId('probe-chip')); });
    expect(screen.getByTestId('probe-callout')).toHaveTextContent('150');
  });
});

describe('MapView: the phone\'s hot-topics section in the peek sheet (M4)', () => {
  const openWindows = () => press('wf-map-peek-btn-win');
  const SHEET_OPEN = () => localStorage.setItem(TOPIC_STRIP_OPEN_SHEET_KEY, '1');
  const winBtn = () => screen.getByTestId('wf-map-peek-btn-win');

  beforeEach(() => { mockIsMobile = true; });

  it('sits in the Windows body after the list, as the sheet variant, closed until the reader opens it', async () => {
    await renderMap();
    expect(screen.queryByTestId('wf-topic-strip')).toBeNull();
    await openWindows();
    const body = screen.getByTestId('wf-map-peek-windows');
    expect(body.contains(strip())).toBe(true);
    expect(strip().className).toContain('wf-topic-strip-sheet');
    expect(strip()).toHaveAttribute('data-variant', 'sheet');
    expect(body.lastElementChild).toBe(strip());
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('wf-topic-strip-grid')).toBeNull();
    await act(async () => { fireEvent.click(toggle()); });
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('wf-topic-strip-grid')).toBeInTheDocument();
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_SHEET_KEY)).toBe('1');
    expect(localStorage.getItem(TOPIC_STRIP_OPEN_KEY)).toBeNull();
  });

  it('keeps its own storage key: the panel\'s stored choice does not open the sheet strip', async () => {
    localStorage.setItem(TOPIC_STRIP_OPEN_KEY, '1');
    await renderMap();
    await openWindows();
    expect(toggle()).toHaveAttribute('aria-expanded', 'false');
  });

  it('is not mounted when the four days carry no topic', async () => {
    await renderMap({ hotTopics: [] });
    await openWindows();
    expect(screen.queryByTestId('wf-topic-strip')).toBeNull();
  });

  it('tolerates a storage failure when opening', async () => {
    await renderMap();
    await openWindows();
    const spy = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('denied'); });
    await act(async () => { fireEvent.click(toggle()); });
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
    spy.mockRestore();
  });

  it('a bar press that MOVES the window selects, announces, collapses and focuses the Windows button (unfocused bar)', async () => {
    SHEET_OPEN();
    await renderMap();
    await openWindows();
    await press('wf-topic-bar-KING_TIDE-4');
    expect(screen.getByTestId('map-status')).toHaveTextContent('King tide selected; window moved to Saturday sunrise');
    expect(screen.queryByTestId('wf-map-peek-body')).toBeNull();
    expect(winBtn()).toHaveAttribute('aria-expanded', 'false');
    expect(document.activeElement).toBe(winBtn());
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'true');
  });

  it('a moving LABEL press collapses the sheet too', async () => {
    SHEET_OPEN();
    await renderMap();
    await openWindows();
    await press('wf-topic-strip-label-KING_TIDE');
    expect(screen.queryByTestId('wf-map-peek-body')).toBeNull();
    expect(chip('KING_TIDE')).toHaveAttribute('aria-pressed', 'true');
    expect(document.activeElement).toBe(winBtn());
  });

  it('a press in place (the current window) selects and still collapses the sheet', async () => {
    SHEET_OPEN();
    await renderMap();
    await openWindows();
    await press('wf-topic-bar-DUST-1');
    expect(screen.getByTestId('map-status')).toHaveTextContent('Dust selected');
    expect(screen.queryByTestId('wf-map-peek-body')).toBeNull();
    expect(document.activeElement).toBe(winBtn());
  });

  it('a label that turns the topic ON collapses the sheet; turning it OFF leaves the sheet open', async () => {
    SHEET_OPEN();
    await renderMap();
    await openWindows();
    await press('wf-topic-strip-label-DUST');
    expect(screen.queryByTestId('wf-map-peek-body')).toBeNull();
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'true');
    await openWindows();
    await press('wf-topic-strip-label-DUST');
    expect(screen.getByTestId('wf-map-peek-body')).toBeInTheDocument();
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'false');
  });

  it('crossing 640 with a selection and focus in the strip keeps the selection and never drops focus to <body>', async () => {
    SHEET_OPEN();
    const { rerender } = await renderMap();
    await openWindows();
    await press('wf-topic-strip-label-DUST');
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'true');
    await openWindows();
    screen.getByTestId('wf-topic-bar-DUST-1').focus();
    mockIsMobile = false;
    await act(async () => { rerender(<Pane hotTopics={HOT_TOPICS} />); });
    expect(document.activeElement).not.toBe(document.body);
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByTestId('wf-map-peek')).toBeNull();
    expect(strip().className).toBe('wf-topic-strip');
    mockIsMobile = true;
    await act(async () => { rerender(<Pane hotTopics={HOT_TOPICS} />); });
    expect(document.activeElement).not.toBe(document.body);
    expect(chip('DUST')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.queryByTestId('wf-map-chrome-bl')).toBeNull();
  });

  it('an HW tick press collapses the sheet, moves to the High-water row, auto-selects Big seas and announces', async () => {
    SHEET_OPEN();
    const FRI = '2026-10-23';
    await renderMap({ bigSeas: [FRIDAY_HW], forecastDates: [TODAY, FRI, SAT] });
    await openWindows();
    const tick = screen.getByTestId(`wf-topic-strip-hw-tick-${FRI}`);
    expect(tick).toHaveAttribute('aria-label', 'HW 12:40, high water Friday');
    await act(async () => { fireEvent.click(tick); });
    expect(screen.queryByTestId('wf-map-peek-body')).toBeNull();
    expect(document.activeElement).toBe(winBtn());
    expect(screen.getByTestId('map-status')).toHaveTextContent(/Window moved to Friday high water/);
    // The chips row is hidden on a big-seas day (Q13), so read the selection off the strip itself.
    await openWindows();
    expect(screen.getByTestId(`wf-topic-strip-hw-tick-${FRI}`)).toHaveAttribute('data-current', 'true');
    expect(screen.getByTestId('wf-topic-strip-label-BIG_SEAS')).toHaveAttribute('aria-pressed', 'true');
  });

  it('defaults OPEN on a big-seas day (the chips row is hidden there)', async () => {
    const day = { ...FRIDAY_HW, date: TODAY, daylightHighWater: false };
    await renderMap({ bigSeas: [day] });
    await openWindows();
    expect(toggle()).toHaveAttribute('aria-expanded', 'true');
  });

  it('opening the grid scrolls it into view (smooth, or instant under reduced motion)', async () => {
    const original = Element.prototype.scrollIntoView;
    const scroll = vi.fn();
    Element.prototype.scrollIntoView = scroll;
    const mm = window.matchMedia;
    try {
      window.matchMedia = () => ({ matches: false });
      await renderMap();
      await openWindows();
      await act(async () => { fireEvent.click(toggle()); });
      expect(scroll).toHaveBeenCalledWith({ block: 'nearest', behavior: 'smooth' });
      await act(async () => { fireEvent.click(toggle()); });
      window.matchMedia = () => ({ matches: true });
      await act(async () => { fireEvent.click(toggle()); });
      expect(scroll).toHaveBeenLastCalledWith({ block: 'nearest', behavior: 'auto' });
    } finally {
      Element.prototype.scrollIntoView = original;
      window.matchMedia = mm;
    }
  });
});

describe('MapView: desktop chrome-bl carries no sheet markup (M4)', () => {
  it('holds the panel strip only', async () => {
    await renderMap();
    const chromeBl = screen.getByTestId('wf-map-chrome-bl');
    expect(chromeBl.querySelector('[data-variant]')).toBeNull();
    expect(chromeBl.querySelector('.wf-topic-strip-sheet')).toBeNull();
    expect(chromeBl.querySelector('.wf-topic-strip').className).toBe('wf-topic-strip');
    expect(chromeBl.innerHTML).not.toContain('sheet');
  });
});
