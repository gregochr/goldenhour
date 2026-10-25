/**
 * The sweep (hot-topics-by-day-plan §3 M3): `MapView` with the daylight High-water row selected.
 *
 * <p>A grep for `eventType ===` misses most of the hazards, so each swept path has its own assertion
 * here. The default for the new kind is "no score, no fetch, no reset", never "treat as sunset" and
 * never "treat as night":
 * <ul>
 *   <li>no stars through `getRatingForLocation` (the sunset's would have leaked through
 *       `solarWindowOnScreen`);</li>
 *   <li>the persisted star floor survives sunrise to High water to sunset, while a direct sunrise to
 *       sunset step still resets it;</li>
 *   <li>the big-seas chips are present and ringed, Big seas is auto-selected and the ordinary chips
 *       reduced to it, and the auto-selection clears when the reader leaves (unless it is theirs);</li>
 *   <li>no "not scored" line, no tide strip, no heat;</li>
 *   <li>the callout is handed the row as itself (no rating, the lead copy), and a FUTURE High-water row
 *       hands its date to {@code onSelectDate} as a day, not a night, so the parent and the pane agree;</li>
 *   <li>expiry: the selection moves off a vanished row (timer, hidden tab, no sunset to land on), keeps
 *       the star floor, and never leaves {@code activeEvIndex} at -1.</li>
 * </ul>
 * The row's own shape is `mapEventsHighWater.test.js`'s; the callout and the rating block's own refusal are
 * `MapCalloutHighWater.test.jsx`'s. Thursday 22 Oct 2026; Friday 23 Oct (BST) is the High-water day.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, fireEvent, render, screen,
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

const probes = { labels: [], heat: [] };
vi.mock('../components/MapHeatLayer.jsx', () => ({
  default: (props) => {
    probes.heat.push(props);
    return <div data-testid="probe-heat" />;
  },
}));
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: (props) => {
    probes.labels.push(props);
    return (
      <button type="button" data-testid="probe-chip" onClick={() => props.onSelect('Bamburgh-0')}>chip</button>
    );
  },
}));
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: (props) => (
    <div
      data-testid="probe-callout"
      data-kind={props.event?.kind}
      data-event-type={props.event?.eventType}
      data-rating={props.rating == null ? 'none' : String(props.rating)}
      data-lead={props.highWaterLead ?? ''}
    />
  ),
}));

let mockRole = 'PRO_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));
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
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { getAstroAvailableDates, getAstroConditions } from '../api/astroApi.js';
import { getAuroraForecastResults } from '../api/auroraApi.js';
import { setRewind } from '../utils/rewind.js';
import { windowKey } from '../utils/windowKeys.js';
import { FRIDAY_HW } from './bigSeasFixtures.js';

const TODAY = '2026-10-22';
const FRI = '2026-10-23';
const NE = 'North East';
const HW_ID = `hw:${FRI}:HIGH_WATER`;
const SUNSET_ID = `solar:${FRI}:SUNSET`;
const FLOOR_KEY = 'mapFilterMinStars';

const SPOT = {
  id: 1, name: 'Bamburgh-0', lat: 55.61, lng: -1.71, rid: NE, regionName: NE, bortleClass: 4,
};

const TIDE = {
  locationName: 'Bamburgh Castle', state: 'LOW', direction: 'RISING', range: '4.3 m', rangeAnomaly: 'about average',
  curve: [0, 0.3, 0.7, 1, 0.6, 0.2, 0], windowPosition: 0.32, windowLevel: 0.2, sunrisePosition: 0.32,
  sunsetPosition: 0.7, extremes: [{ kind: 'LW', position: 0.3, time: '07:30' }], heightAtWindow: '0.9 m',
};

function windowOf(date, targetType, extra = {}) {
  return {
    key: windowKey(date, targetType), date, targetType, label: `${date} ${targetType}`,
    time: targetType === 'SUNRISE' ? '07:20' : '17:40', bestRating: 4, conf: 1, ...extra,
  };
}

/** A place the pipeline triaged out of Friday's sunset (no rating, a weather reason). */
const TRIAGED = {
  id: 5, name: 'Triaged-0', lat: 55.39, lng: -1.61, rid: NE, regionName: NE, bortleClass: 4, skySubject: true,
};

function heatProp({ windows }) {
  const fridaySunset = windowKey(FRI, 'SUNSET');
  return {
    enabled: true,
    hasHome: false,
    spots: [SPOT, TRIAGED],
    areaSpots: [SPOT, TRIAGED],
    pointsByKey: new Map(windows.map((w) => [w.key, [{
      id: SPOT.id, name: SPOT.name, lat: SPOT.lat, lng: SPOT.lng, rid: SPOT.rid, r: [4],
    }]])),
    // Friday's sunset stood the triaged place down for the weather: a CONTROL for the High-water row,
    // which must draw none of it.
    windowStates: new Map([[fridaySunset, {
      unrated: [],
      stood: [{
        lat: TRIAGED.lat, lng: TRIAGED.lng, reason: 'HIGH_CLOUD', rid: NE, key: `#${TRIAGED.id}`,
      }],
      states: new Map([[`#${SPOT.id}`, 'rated'], [`#${TRIAGED.id}`, 'stood']]),
    }]]),
    windows,
    areaBounds: [[54.3, -3.4], [55.7, -1.3]],
    catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
    tideByWindow: new Map(),
  };
}

/** Bamburgh is rated on every solar window; the three structures carry no forecast at all. */
function makeLocations() {
  const base = {
    regionName: NE, bortleClass: 4, locationType: ['LANDSCAPE'], tideType: ['HIGH'],
  };
  return [
    {
      ...base,
      id: SPOT.id,
      name: SPOT.name,
      lat: SPOT.lat,
      lon: SPOT.lng,
      forecastsByDate: new Map([
        [TODAY, { sunset: { rating: 5, solarEventTime: `${TODAY}T17:12:00` } }],
        [FRI, {
          sunrise: { rating: 4, solarEventTime: `${FRI}T06:20:00` },
          sunset: {
            rating: 5, solarEventTime: `${FRI}T16:40:00`, fierySkyPotential: 70, goldenHourPotential: 60,
          },
        }],
      ]),
    },
    {
      ...base, id: 243, name: 'Roker Pier & Lighthouse', lat: 54.92, lon: -1.36, forecastsByDate: new Map(),
    },
    {
      ...base, id: 28, name: "St. Mary's Lighthouse", lat: 55.07, lon: -1.44, forecastsByDate: new Map(),
    },
    {
      ...base, id: 21, name: 'Amble', lat: 55.33, lon: -1.58, forecastsByDate: new Map(),
    },
    {
      ...base,
      id: TRIAGED.id,
      name: TRIAGED.name,
      lat: TRIAGED.lat,
      lon: TRIAGED.lng,
      forecastsByDate: new Map([[FRI, { sunset: { triageReason: 'HIGH_CLOUD', solarEventTime: `${FRI}T16:40:00` } }]]),
    },
  ];
}
const LOCATIONS = makeLocations();

const KING_TIDE = {
  type: 'KING_TIDE', label: 'King tide', date: FRI, eventType: 'SUNRISE', regions: [NE], locationNames: [SPOT.name],
};

let onSelectDate;
/** The pane's own state, reachable from a test: the parent's date, its door, the pane's visibility, the served days. */
const pane = { current: null };
function Pane({
  startDate = TODAY, autoEventType = 'SUNSET', bigSeas: initialBigSeas = [FRIDAY_HW], windows = null,
  forecastDates = [TODAY, FRI], briefingScores = undefined,
}) {
  const [date, setDate] = React.useState(startDate);
  const [handoff, setHandoff] = React.useState(null);
  const [visible, setVisible] = React.useState(true);
  const [bigSeas, setBigSeas] = React.useState(initialBigSeas);
  React.useEffect(() => {
    pane.current = {
      setDate, setHandoff, setVisible, setBigSeas,
    };
  }, []);
  const wins = windows ?? [
    windowOf(TODAY, 'SUNSET'),
    windowOf(FRI, 'SUNRISE', { tide: TIDE }),
    windowOf(FRI, 'SUNSET'),
  ];
  return (
    <MapView
      locations={LOCATIONS}
      date={date}
      forecastDates={forecastDates}
      autoEventType={autoEventType}
      heat={heatProp({ windows: wins })}
      paneVisible={visible}
      planHandoff={handoff}
      briefingScores={briefingScores}
      runId={null}
      bigSeas={bigSeas}
      hotTopics={[KING_TIDE]}
      onSelectDate={(d, opts) => { onSelectDate(d, opts); setDate(d); }}
    />
  );
}

async function renderMap(props = {}) {
  let result;
  await act(async () => { result = render(<Pane {...props} />); });
  return result;
}

/** Chooses a row through the week menu. */
async function choose(id) {
  await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
  const row = screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === id);
  await act(async () => { fireEvent.click(row); });
}

const lastSpots = () => probes.labels.at(-1).spots;
const lastChips = () => probes.labels.at(-1).bigSeasChips;
const pill = () => screen.getByTestId('wf-win-pill');
const bigSeasChip = () => screen.getByTestId('wf-topic-chip-BIG_SEAS');

beforeEach(() => {
  localStorage.clear();
  probes.labels.length = 0;
  probes.heat.length = 0;
  onSelectDate = vi.fn();
  mockRole = 'PRO_USER';
  getAstroAvailableDates.mockResolvedValue([]);
  getAstroConditions.mockReset();
  getAstroConditions.mockResolvedValue([]);
  getAuroraForecastResults.mockClear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  vi.useRealTimers();
  setRewind(null);
  // A test that faked the tab's visibility must not leak it into the next one.
  delete document.visibilityState;
  localStorage.clear();
});

describe('hwEventSweep: no stars (solarWindowOnScreen, getRatingForLocation, stand-down)', () => {
  it('the sunset\'s stars do NOT reach the chips, pins, floor or counts on the High-water row', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    // Control: Friday's sunset is rated for Bamburgh.
    expect(lastSpots().some((s) => s.name === SPOT.name && s.rating === 5)).toBe(true);
    await choose(HW_ID);
    expect(pill()).toHaveTextContent('High water');
    expect(lastSpots().every((s) => s.rating == null)).toBe(true);
    expect(lastSpots().some((s) => s.name === SPOT.name && s.rating === 5)).toBe(false);
  });

  it('draws no heat-window colour key and no scored toast for it', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    await choose(HW_ID);
    expect(screen.queryByTestId('photocast-scored-legend')).toBeNull();
  });
});

describe('hwEventSweep: selectEvRow\'s typeChanged (the persisted star floor)', () => {
  it('sunrise to High water to sunset keeps the saved floor (no reset in either direction)', async () => {
    localStorage.setItem(FLOOR_KEY, '4');
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    expect(pill()).toHaveTextContent('Sunrise');
    await choose(HW_ID);
    expect(pill()).toHaveTextContent('High water');
    expect(localStorage.getItem(FLOOR_KEY)).toBe('4');
    await choose(SUNSET_ID);
    expect(pill()).toHaveTextContent('Sunset');
    expect(localStorage.getItem(FLOOR_KEY)).toBe('4');
  });

  it('a DIRECT sunrise to sunset step still resets it, as it always did', async () => {
    localStorage.setItem(FLOOR_KEY, '4');
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(SUNSET_ID);
    expect(pill()).toHaveTextContent('Sunset');
    expect(localStorage.getItem(FLOOR_KEY)).toBeNull();
  });

  it('and the HW to sunset step does not reset after a sunrise to HW step (no "last solar type" compare)', async () => {
    localStorage.setItem(FLOOR_KEY, '4');
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    await choose(SUNSET_ID);
    // The hazard Codex named: comparing SUNSET with the last SOLAR type (SUNRISE) would reset here.
    expect(localStorage.getItem(FLOOR_KEY)).toBe('4');
  });
});

describe('hwEventSweep: what the map shows on the row', () => {
  it('hands the label layer the big-seas chips, every one ringed', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    expect(lastChips()).toHaveLength(3);
    expect(lastChips().every((c) => c.ringed === true)).toBe(true);
  });

  it('says no "not scored yet" (the window is not scored by design)', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    expect(screen.queryByText(/not scored yet/i)).toBeNull();
    expect(screen.queryByText(/no forecast/i)).toBeNull();
  });

  it('shows no tide strip (the row carries no tide) where the sunrise beside it does', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    expect(screen.getByTestId('wf-tide-strip')).toBeInTheDocument();
    await choose(HW_ID);
    expect(screen.queryByTestId('wf-tide-strip')).toBeNull();
  });

  it('the chips are Big seas only, named with the high water, and pressed (auto-selected)', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    // The sunrise has King tide and Big seas.
    expect(screen.getByTestId('wf-topic-chip-KING_TIDE')).toBeInTheDocument();
    await choose(HW_ID);
    expect(screen.queryByTestId('wf-topic-chip-KING_TIDE')).toBeNull();
    expect(bigSeasChip()).toHaveTextContent('Big seas · HW 12:40');
    expect(bigSeasChip()).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('wf-map-pane')).toHaveAttribute('data-topic-selected', 'true');
  });

  it('the auto-selection clears when the reader leaves the row', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    expect(bigSeasChip()).toHaveAttribute('aria-pressed', 'true');
    await choose(SUNSET_ID);
    // Big seas is on the sunset too, but the reader never chose it.
    expect(bigSeasChip()).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('wf-map-pane')).not.toHaveAttribute('data-topic-selected');
  });

  it('a selection the READER made stays when they leave the row', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    // Clearing and re-selecting by hand makes it theirs.
    await act(async () => { fireEvent.click(bigSeasChip()); });
    expect(bigSeasChip()).toHaveAttribute('aria-pressed', 'false');
    await act(async () => { fireEvent.click(bigSeasChip()); });
    expect(bigSeasChip()).toHaveAttribute('aria-pressed', 'true');
    await choose(SUNSET_ID);
    expect(bigSeasChip()).toHaveAttribute('aria-pressed', 'true');
  });

  it('a selection made BEFORE arriving is the reader\'s, and is not cleared on leaving', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await act(async () => { fireEvent.click(bigSeasChip()); });
    await choose(HW_ID);
    await choose(SUNSET_ID);
    expect(bigSeasChip()).toHaveAttribute('aria-pressed', 'true');
  });

  it('the reader clearing the auto-selection on the row is not undone by a re-render', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    await act(async () => { fireEvent.click(bigSeasChip()); });
    expect(bigSeasChip()).toHaveAttribute('aria-pressed', 'false');
  });
});

describe('hwEventSweep: the callout is handed the row as itself', () => {
  it('kind hw, no rating (never the sunset\'s 5), and the lead copy', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    await choose(HW_ID);
    await act(async () => { fireEvent.click(screen.getByTestId('probe-chip')); });
    const callout = screen.getByTestId('probe-callout');
    expect(callout).toHaveAttribute('data-kind', 'hw');
    expect(callout).toHaveAttribute('data-event-type', 'HIGH_WATER');
    expect(callout).toHaveAttribute('data-rating', 'none');
    expect(callout.getAttribute('data-lead')).toBe(
      'High water at 12:40 with 3.4 m waves from NNE. Not a light window: this is for spray over the piers, not for colour.',
    );
  });

  it('hands no lead on a window that is not the High-water row', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    await act(async () => { fireEvent.click(screen.getByTestId('probe-chip')); });
    const callout = screen.getByTestId('probe-callout');
    expect(callout).toHaveAttribute('data-kind', 'solar');
    expect(callout).toHaveAttribute('data-lead', '');
  });
});

describe('hwEventSweep: a FUTURE High-water row keeps the parent date and the pane in step', () => {
  it('from the week menu: onSelectDate(date, {isNight: false}) and the pane stays on the row', async () => {
    await renderMap({ startDate: TODAY, autoEventType: 'SUNSET' });
    expect(pill()).toHaveTextContent('Sunset');
    expect(pill()).toHaveTextContent(TODAY);
    await choose(HW_ID);
    expect(onSelectDate).toHaveBeenCalledWith(FRI, { isNight: false });
    // The parent moved to Friday and the pane is still ON the row (no bounce back to another window).
    expect(pill()).toHaveTextContent('High water');
    expect(pill()).toHaveTextContent('Tomorrow');
    expect(pill()).toHaveTextContent('12:40');
    expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
    expect(lastChips()).toHaveLength(3);
  });

  it('from the strip\'s tick: the same hand-off, announced once', async () => {
    await renderMap({ startDate: TODAY, autoEventType: 'SUNSET' });
    await act(async () => { fireEvent.click(screen.getByTestId(`wf-topic-strip-hw-tick-${FRI}`)); });
    expect(onSelectDate).toHaveBeenCalledWith(FRI, { isNight: false });
    expect(pill()).toHaveTextContent('High water');
    expect(screen.getByTestId('map-status')).toHaveTextContent('Window moved to Friday high water 12:40');
    expect(screen.getByTestId(`wf-topic-strip-hw-tick-${FRI}`)).toHaveAttribute('data-current', 'true');
  });

  it('the big-seas line\'s "At high water 12:40 ›" action goes to the row, and is gone on it', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    expect(screen.getByTestId('wf-big-seas-go')).toHaveTextContent('At high water 12:40 ›');
    await act(async () => { fireEvent.click(screen.getByTestId('wf-big-seas-go')); });
    expect(pill()).toHaveTextContent('High water');
    expect(screen.queryByTestId('wf-big-seas-go')).toBeNull();
  });
});

describe('hwEventSweep: no heat, no stand-down, no fetch, no light-window chrome', () => {
  it('hands the heat layer NO points, no stood places and no unrated hatch on the row (control: the sunset has them)', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    const sunsetProps = probes.heat.at(-1);
    expect(sunsetProps.points.length).toBeGreaterThan(0);
    expect(sunsetProps.stood.length).toBeGreaterThan(0);
    await choose(HW_ID);
    const hw = probes.heat.at(-1);
    expect(hw.points).toEqual([]);
    expect(hw.stood).toEqual([]);
    expect(hw.unrated).toEqual([]);
  });

  it('the scored-locations legend is on the sunset (control) and absent on the row', async () => {
    const briefingScores = new Map([[`x|${FRI}|SUNSET|${SPOT.name}`, { rating: 5 }]]);
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET', briefingScores });
    expect(screen.getByTestId('photocast-scored-legend')).toBeInTheDocument();
    await choose(HW_ID);
    expect(screen.queryByTestId('photocast-scored-legend')).toBeNull();
  });

  it('the SELECTED place\'s chip carries no rating on the row (control: the sunset has one)', async () => {
    // The label layer appends the selected place even when the rating floor would drop it, so this is
    // the one spot that can still be drawn on the row: it must carry nothing of the sunset's. (The
    // map's per-location markers used to carry sunset arcs too; they are gone, so that half of this
    // probe has no observable left and is deleted.)
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    expect(lastSpots().find((s) => s.name === SPOT.name)).toMatchObject({ rating: 5 });
    await choose(HW_ID);
    await act(async () => { fireEvent.click(screen.getByTestId('probe-chip')); });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent('High water');
    const selected = lastSpots().find((s) => s.name === SPOT.name);
    expect(selected).toBeDefined();
    expect(selected.rating ?? null).toBeNull();
  });

  it('fetches no night, aurora or astro scores for it', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    await choose(HW_ID);
    expect(getAstroConditions).not.toHaveBeenCalled();
    expect(getAuroraForecastResults).not.toHaveBeenCalled();
  });

  it('the footer says what is drawn and why there is no score, with no "0 of N shown", rated or stood-down clause', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    // Control: the sunset's footer counts places and the triaged one as stood down.
    expect(screen.getByTestId('wf-map-counts-footer')).toHaveTextContent(/of \d+ shown/);
    expect(screen.getByTestId('wf-map-counts-standdown')).toHaveTextContent('1 stood down');
    await choose(HW_ID);
    const footer = screen.getByTestId('wf-map-counts-footer');
    expect(footer).toHaveTextContent('3 big-seas structures · high water has no light score');
    expect(footer).not.toHaveTextContent(/shown|rated|stood down/);
    expect(screen.queryByTestId('wf-map-counts-rated')).toBeNull();
    expect(screen.queryByTestId('wf-map-counts-standdown')).toBeNull();
  });

  it('the admin "unknown" and stand-down toggles are not actionable on the row (control: both are on the sunset)', async () => {
    mockRole = 'ADMIN';
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    await act(async () => { fireEvent.click(screen.getByTestId('wf-filters-chip')); });
    expect(screen.getByTestId('star-filter-unrated')).not.toBeDisabled();
    expect(screen.getByTestId('star-filter-standdown')).not.toBeDisabled();
    await choose(HW_ID);
    await act(async () => { fireEvent.click(screen.getByTestId('wf-filters-chip')); });
    expect(screen.getByTestId('star-filter-unrated')).toBeDisabled();
    expect(screen.getByTestId('star-filter-standdown')).toBeDisabled();
  });

  it('Bamburgh is among the spots (rated 5 on the sunset), so "no stars" is not just "no spots"', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    await choose(HW_ID);
    expect(lastSpots().some((s) => s.name === SPOT.name)).toBe(false);
    // The place is not dropped by accident: selecting it brings it back as an UNRATED spot.
    await act(async () => { fireEvent.click(screen.getByTestId('probe-chip')); });
    const bamburgh = lastSpots().find((s) => s.name === SPOT.name);
    expect(bamburgh).toBeDefined();
    expect(bamburgh.rating).toBeNull();
  });
});

describe('hwEventSweep: nothing reads the row as a night', () => {
  it('the Regions jump menu shows no region stars on it (control: an astro night of the same date does)', async () => {
    getAstroAvailableDates.mockResolvedValue([FRI]);
    getAstroConditions.mockResolvedValue([{ locationName: SPOT.name, stars: 5 }]);
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    await choose(`astro:${FRI}:ASTRO`);
    await act(async () => { fireEvent.click(screen.getByTestId('wf-jump-chip')); });
    expect(screen.getAllByTestId('wf-jump-row').map((r) => r.textContent).join(' ')).toMatch(/5★/);
    await choose(HW_ID);
    await act(async () => { fireEvent.click(screen.getByTestId('wf-jump-chip')); });
    expect(screen.getAllByTestId('wf-jump-row').map((r) => r.textContent).join(' ')).not.toMatch(/★/);
  });

  it('a step between the row and a NIGHT resets the star floor like any solar to night step', async () => {
    getAstroAvailableDates.mockResolvedValue([FRI]);
    localStorage.setItem(FLOOR_KEY, '4');
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    expect(localStorage.getItem(FLOOR_KEY)).toBe('4');
    await choose(`astro:${FRI}:ASTRO`);
    expect(localStorage.getItem(FLOOR_KEY)).toBeNull();
    // And back: night to the row resets again (nothing to reset here, but the step is not exempt).
    localStorage.setItem(FLOOR_KEY, '4');
    await choose(HW_ID);
    expect(localStorage.getItem(FLOOR_KEY)).toBeNull();
  });

  it('an arriving High-water row with ANOTHER topic selected says Big seas replaced it, not "cleared"', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await act(async () => { fireEvent.click(screen.getByTestId('wf-topic-chip-KING_TIDE')); });
    expect(screen.getByTestId('wf-topic-chip-KING_TIDE')).toHaveAttribute('aria-pressed', 'true');
    await choose(HW_ID);
    expect(bigSeasChip()).toHaveAttribute('aria-pressed', 'true');
    const status = screen.getByTestId('map-status');
    expect(status).toHaveTextContent('Big seas selected: 3 structures ringed.');
    expect(status).not.toHaveTextContent(/cleared/i);
  });

  it('arriving with nothing selected selects Big seas silently', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    expect(bigSeasChip()).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('map-status')).toBeEmptyDOMElement();
  });
});

describe('hwEventSweep: a date that moves from OUTSIDE wins over the row', () => {
  /** What a Plan door does: the parent's date changes and a handoff names a window. */
  async function door(handoff, date = TODAY) {
    await act(async () => {
      pane.current.setDate(date);
      pane.current.setHandoff(handoff);
    });
  }

  it('the door\'s date and window win; nothing is forwarded back to the old date', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    onSelectDate.mockClear();
    await door({ nonce: 1, source: 'map', eventType: 'SUNSET' });
    expect(pill()).toHaveTextContent('Sunset');
    expect(pill()).toHaveTextContent(TODAY);
    expect(onSelectDate).not.toHaveBeenCalled();
  });

  it('a door that names no window falls back to the auto-selected event, locally', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    await choose(HW_ID);
    onSelectDate.mockClear();
    await door({ nonce: 1, source: 'map' });
    expect(pill()).toHaveTextContent('Sunset');
    expect(pill()).toHaveTextContent(TODAY);
    expect(onSelectDate).not.toHaveBeenCalled();
  });

  it('a clamp of the parent\'s date (no door at all) moves the pane locally and never forwards', async () => {
    await renderMap({ startDate: FRI, autoEventType: 'SUNSET' });
    await choose(HW_ID);
    onSelectDate.mockClear();
    await act(async () => { pane.current.setDate(TODAY); });
    expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
    expect(pill()).toHaveTextContent('Sunset');
    expect(onSelectDate).not.toHaveBeenCalled();
  });
});

describe('hwEventSweep: the served lead changing under the row', () => {
  it('the row dropped by a poll (not expired, same date) moves to that date\'s sunset, filters kept', async () => {
    localStorage.setItem(FLOOR_KEY, '4');
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    // The next poll serves an aligned lead: the day is no longer a High-water day.
    await act(async () => {
      pane.current.setBigSeas([{ ...FRIDAY_HW, daylightHighWater: false, alignedEvent: 'SUNSET' }]);
    });
    expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
    expect(pill()).toHaveTextContent('Sunset');
    expect(localStorage.getItem(FLOOR_KEY)).toBe('4');
    expect(screen.getByTestId('map-status')).toHaveTextContent(/High water has passed: now Sunset/);
  });
});

describe('hwEventSweep: expiry', () => {
  const FRI_NOON = `${FRI}T12:00:00Z`; // 13:00 BST: an hour before the row's windowEnd (13:00Z = 14:00 BST)

  function useTimers() {
    vi.useRealTimers();
    vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] });
    vi.setSystemTime(new Date(FRI_NOON));
  }

  /** Moves the (faked) clock and lets the timers that fall due run; the fake Date moves with them. */
  async function advance(ms) {
    await act(async () => { vi.advanceTimersByTime(ms); });
  }

  it('the selection moves to the date\'s sunset when the deadline passes, keeping the star floor', async () => {
    useTimers();
    localStorage.setItem(FLOOR_KEY, '4');
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    expect(pill()).toHaveTextContent('High water');
    await advance(59 * 60 * 1000);
    expect(pill()).toHaveTextContent('High water');
    await advance(2 * 60 * 1000);
    expect(pill()).toHaveTextContent('Sunset');
    expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
    expect(localStorage.getItem(FLOOR_KEY)).toBe('4');
    // The auto-selected Big seas went with the row.
    expect(screen.getByTestId('wf-map-pane')).not.toHaveAttribute('data-topic-selected');
  });

  it('activeEvIndex is never -1 after the timer fires (the pill never says "No forecast")', async () => {
    useTimers();
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    await advance(61 * 60 * 1000);
    expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
    expect(pill()).not.toHaveTextContent('No forecast');
  });

  it('a hidden tab that becomes visible past windowEnd advances too (no timer ran)', async () => {
    useTimers();
    localStorage.setItem(FLOOR_KEY, '4');
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    expect(pill()).toHaveTextContent('High water');
    // The tab sleeps: the clock moves two hours, no timer fires.
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' });
    vi.setSystemTime(new Date(Date.now() + 2 * 60 * 60 * 1000));
    expect(pill()).toHaveTextContent('High water');
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' });
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    try {
      expect(pill()).toHaveTextContent('Sunset');
      expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
      expect(localStorage.getItem(FLOOR_KEY)).toBe('4');
    } finally {
      delete document.visibilityState;
    }
  });

  it('with no sunset on that date it lands on a reachable SOLAR window, never on that date\'s night', async () => {
    useTimers();
    getAstroAvailableDates.mockResolvedValue([FRI]);
    // Friday has only a served sunrise and is not in the forecast dates: no filler sunset exists, but its
    // astro night is offered.
    await renderMap({
      startDate: FRI,
      autoEventType: 'SUNRISE',
      forecastDates: [TODAY],
      windows: [windowOf(TODAY, 'SUNSET'), windowOf(FRI, 'SUNRISE')],
    });
    await choose(HW_ID);
    expect(pill()).toHaveTextContent('High water');
    await advance(61 * 60 * 1000);
    expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
    // Thursday's windows are over by Friday noon, so the first reachable solar window is Friday's sunrise.
    expect(pill()).toHaveTextContent('Sunrise');
    expect(pill()).toHaveTextContent(FRI);
    expect(pill()).not.toHaveTextContent('Astro');
  });

  it('says so once, in words: "High water has passed: now Sunset 17:40"', async () => {
    useTimers();
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    await advance(61 * 60 * 1000);
    expect(screen.getByTestId('map-status')).toHaveTextContent('High water has passed: now Sunset 17:40');
  });

  it('the automatic move on the same date hands the parent nothing (it is not a pick)', async () => {
    useTimers();
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    onSelectDate.mockClear();
    await advance(61 * 60 * 1000);
    expect(pill()).toHaveTextContent('Sunset');
    expect(onSelectDate).not.toHaveBeenCalled();
  });

  it('under a Rewound appNow the deadline is the app clock\'s: the system clock is far away, the move happens', async () => {
    useTimers();
    // The wall clock sits on Friday noon (an hour before windowEnd). The app clock is rewound to 12:30Z,
    // 30 minutes before windowEnd; the row is on screen.
    setRewind(`${FRI}T12:30:00Z`);
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    expect(pill()).toHaveTextContent('High water');
    // Rewind forward to after windowEnd (the system clock is nowhere near it) and let a slice run.
    await act(async () => { setRewind(`${FRI}T13:30:00Z`); });
    await advance(31 * 1000);
    expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
    expect(pill()).toHaveTextContent('Sunset');
  });

  it('wakes in slices of at most 30 s, never one long timer', async () => {
    useTimers();
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    // The first slice has run (armed at 0 ms); what is pending now is one timer, set to <= 30 s.
    await advance(1);
    const pending = vi.getTimerCount();
    expect(pending).toBeGreaterThan(0);
    // 59 minutes remain; 29 s later the row is still there, and the next slice has been armed again.
    await advance(29 * 1000);
    expect(pill()).toHaveTextContent('High water');
    expect(vi.getTimerCount()).toBeGreaterThan(0);
    // A single 59-minute timer would not have fired by now either, so pin the slice length itself.
    const spy = vi.spyOn(globalThis, 'setTimeout');
    await advance(31 * 1000);
    const delays = spy.mock.calls.map((c) => c[1]).filter((d) => typeof d === 'number' && d >= 1000);
    expect(delays.length).toBeGreaterThan(0);
    expect(Math.max(...delays)).toBeLessThanOrEqual(30000);
    spy.mockRestore();
  });

  it('while the pane is hidden the move waits, and applies when the pane shows', async () => {
    useTimers();
    localStorage.setItem(FLOOR_KEY, '4');
    await renderMap({ startDate: FRI, autoEventType: 'SUNRISE' });
    await choose(HW_ID);
    await act(async () => { pane.current.setVisible(false); });
    onSelectDate.mockClear();
    await advance(61 * 60 * 1000);
    // Nothing moved while hidden (the pill reads "No forecast" for the vanished row, as it always did).
    expect(onSelectDate).not.toHaveBeenCalled();
    expect(pill()).toHaveTextContent('No forecast');
    await act(async () => { pane.current.setVisible(true); });
    expect(pill()).toHaveTextContent('Sunset');
    expect(localStorage.getItem(FLOOR_KEY)).toBe('4');
  });
});
