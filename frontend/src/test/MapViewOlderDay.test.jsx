/**
 * `MapView` and the older-day fetch (outcome-rating-plan.md §2 #2, §3 O3b): the seams between the
 * pane's input (`canLoadOlder`, `olderDayDate`, `olderDayLoading`, `onLoadOlderDay`), the window
 * control, the phone sheet and `onSelectDate`.
 *
 * <p>Pinned here, because only a real `MapView` mount can: `‹`, `←` and the Load row (dropdown and
 * phone sheet alike) each hand the pane the day to fetch; the pill says "Loading …" while it is out;
 * and when the day lands, selection moves to that day's SUNSET row if it has one, else its SUNRISE —
 * unless the day came back empty (nothing moves; the menus and the status region say so, §4 #15 as amended
 * 2026-10-17) or the reader picked another window
 * while it was out. The harness re-renders the same mount with new props the way `useForecasts` does:
 * the rows and the end of the loading state in ONE render.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, cleanup, fireEvent, render, screen,
} from '@testing-library/react';

vi.mock('leaflet', () => {
  const icon = () => ({});
  const divIcon = (options) => ({ options });
  const point = (x, y) => ({ x, y });
  return { default: { icon, divIcon, point }, icon, divIcon, point };
});
vi.mock('leaflet/dist/leaflet.css', () => ({}));

vi.mock('react-leaflet', () => ({
  MapContainer: ({ children }) => <div data-testid="map-container">{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div data-testid="marker">{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => ({ getZoom: () => 9, getBounds: () => ({ pad: () => ({ contains: () => false }) }) }),
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, clientWidth: 800, offsetWidth: 800, parentElement: document.body }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
    panInside: () => {},
    getSize: () => ({ x: 390, y: 844 }),
    latLngToContainerPoint: () => ({ x: 195, y: 400 }),
  }),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'ADMIN' }) }));
let mockIsMobile = false;
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
vi.mock('../components/BottomSheet.jsx', () => ({ default: () => null }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/map/MapCallout.jsx', () => ({ default: () => null }));
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/map/MapLabels.jsx', () => ({ default: () => null }));

import MapView from '../components/MapView.jsx';
import { buildPastSolarWindows } from '../utils/mapEvents.js';

const T4 = '2026-01-11';
const T3 = '2026-01-12'; // Mon 12
const T2 = '2026-01-13';
const T1 = '2026-01-14';
const TODAY = '2026-01-15';

const SPOT = {
  id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East',
};
const sunrise = (date) => ({ solarEventTime: `${date}T08:24:00`, rating: 3 });
const sunset = (date) => ({ solarEventTime: `${date}T16:12:00`, rating: 3 });

const heat = {
  enabled: true,
  hasHome: false,
  spots: [SPOT],
  areaSpots: [SPOT],
  pointsByKey: new Map(),
  windows: [
    { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 5, conf: 1 },
  ],
  areaBounds: [[54.3, -3.4], [55.7, -1.3]],
  catalogueBounds: [[54.3, -3.4], [55.7, -1.3]],
};

/** One place whose forecast rows are `days` (date → {sunrise?, sunset?}). */
const locationsFor = (days) => [{
  id: 1,
  name: 'Bamburgh',
  lat: SPOT.lat,
  lon: SPOT.lng,
  regionName: SPOT.rid,
  bortleClass: 4,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map(Object.entries(days)),
}];

/** T-2 and T-1 are held (the list's own past days) with only their sunset, so T-2's sunset is the oldest window. */
const BASE_DAYS = {
  [T2]: { sunset: sunset(T2) },
  [T1]: { sunset: sunset(T1) },
  [TODAY]: { sunset: sunset(TODAY) },
};

beforeEach(() => {
  mockIsMobile = false;
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  // 09:40 on TODAY: tonight's sunset (16:12) is still to come; every earlier window has gone.
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
  vi.clearAllMocks();
});

/**
 * MapView under a parent that behaves like `App` + `useForecasts`: it owns the date (`onSelectDate`),
 * the loading date and the rows, and `ctl.finish(days)` lands a day — rows and end of loading in one
 * render. `onLoad` records what the pane was asked to fetch.
 */
function Harness({ register, onLoad, onSelect, startDate = T2, baseDays = BASE_DAYS }) {
  const [date, setDate] = React.useState(startDate);
  const [loading, setLoading] = React.useState(null);
  const [extra, setExtra] = React.useState({});
  const [loaded, setLoaded] = React.useState([]);
  const days = { ...baseDays, ...extra };
  const locations = locationsFor(days);
  const dates = Object.keys(days).filter((d) => days[d].sunrise || days[d].sunset).sort();
  const oldestHeld = [...dates, ...loaded].sort()[0];
  // What `WindowFirstMapPane` hands over: the most recently loaded (= oldest loaded) day, when it had no rows.
  const oldestLoaded = [...loaded].sort()[0];
  const emptyOlderDayDate = oldestLoaded && !days[oldestLoaded] ? oldestLoaded : null;
  const next = new Date(`${oldestHeld}T12:00:00Z`);
  next.setUTCDate(next.getUTCDate() - 1);
  const nextDate = next.toISOString().slice(0, 10);
  // The parent's way to land a day: handed OUT through `register`, never written onto a prop.
  React.useEffect(() => {
    register((day, rows, failed = false) => {
      // A failed fetch records nothing as loaded (as the hook does) and only ends the loading state.
      if (!failed) {
        setLoaded((prev) => [...prev, day]);
        setExtra((prev) => (rows ? { ...prev, [day]: rows } : prev));
      }
      setLoading(null);
    });
  }, [register]);
  return (
    <MapView
      locations={locations}
      pastSolarWindows={buildPastSolarWindows(locations, TODAY)}
      forecastDates={dates}
      date={date}
      onSelectDate={(d, opts) => { onSelect(d, opts); setDate(d); }}
      autoEventType="SUNSET"
      heat={heat}
      canLoadOlder={nextDate >= T4}
      olderDayDate={nextDate >= T4 ? nextDate : null}
      emptyOlderDayDate={emptyOlderDayDate}
      olderDayLoading={loading}
      onLoadOlderDay={(d) => { setLoading(d); onLoad(d); }}
    />
  );
}

async function mount(props = {}) {
  const ctl = {};
  const register = (fn) => { ctl.finish = fn; };
  const onLoad = vi.fn();
  const onSelect = vi.fn();
  await act(async () => {
    render(<Harness register={register} onLoad={onLoad} onSelect={onSelect} {...props} />);
  });
  return { ctl, onLoad, onSelect };
}

const finish = async (ctl, day, rows, failed = false) => {
  await act(async () => { ctl.finish(day, rows, failed); });
};
const pill = () => screen.getByTestId('wf-win-pill');

describe('starting the fetch', () => {
  it('shows ‹ as the fetch on the oldest window, naming the day, and asks the pane for it', async () => {
    const { onLoad } = await mount();
    expect(pill()).toHaveTextContent('Sunset');
    expect(screen.getByTestId('wf-win-prev')).toHaveAccessibleName('Load Mon 12');

    fireEvent.click(screen.getByTestId('wf-win-prev'));

    expect(onLoad).toHaveBeenCalledTimes(1);
    expect(onLoad).toHaveBeenCalledWith(T3);
  });

  it('does the same from ArrowLeft', async () => {
    const { onLoad } = await mount();
    fireEvent.keyDown(pill(), { key: 'ArrowLeft' });
    expect(onLoad).toHaveBeenCalledWith(T3);
  });

  it('does the same from the Load row, which closes the menu and focuses the pill', async () => {
    const { onLoad } = await mount();
    fireEvent.click(pill());
    const load = screen.getByTestId('wf-win-load');
    expect(load).toHaveTextContent('Load Mon 12');
    expect(load).toHaveTextContent('fetches Mon 12');

    fireEvent.click(load);

    expect(onLoad).toHaveBeenCalledWith(T3);
    expect(screen.queryByTestId('wf-win-menu')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(pill());
  });

  it('says "Loading Mon 12…" on the pill, aria-busy, with both steppers disabled', async () => {
    await mount();
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    expect(pill()).toHaveTextContent('Loading Mon 12…');
    expect(pill()).toHaveAttribute('aria-busy', 'true');
    expect(screen.getByTestId('wf-win-prev')).toBeDisabled();
    expect(screen.getByTestId('wf-win-next')).toBeDisabled();
  });

  it('offers no fetch once T-4 is held: ‹ is disabled on the oldest window and there is no Load row', async () => {
    await mount({ baseDays: { [T4]: { sunset: sunset(T4) }, [TODAY]: { sunset: sunset(TODAY) } }, startDate: T4 });
    expect(screen.getByTestId('wf-win-prev')).toBeDisabled();
    fireEvent.click(pill());
    expect(screen.queryByTestId('wf-win-load')).not.toBeInTheDocument();
  });
});

describe('when the day lands', () => {
  it('moves selection to that day\'s SUNSET when it has one, flagged past (the next ‹ is its sunrise)', async () => {
    const { ctl, onSelect } = await mount();
    fireEvent.click(screen.getByTestId('wf-win-prev'));

    await finish(ctl, T3, { sunrise: sunrise(T3), sunset: sunset(T3) });

    expect(onSelect).toHaveBeenLastCalledWith(T3, { isNight: false, isPast: true });
    expect(pill()).toHaveTextContent('Sunset');
    expect(pill()).toHaveTextContent('Mon 12');
    expect(screen.getByTestId('wf-win-past')).toBeInTheDocument();
    expect(pill()).not.toHaveAttribute('aria-busy');
    // ‹ now steps to that day's sunrise, not to a fetch.
    expect(screen.getByTestId('wf-win-prev')).toHaveAccessibleName('Previous event, past');
  });

  it('lands on the SUNRISE when the day has no sunset', async () => {
    const { ctl, onSelect } = await mount();
    fireEvent.click(screen.getByTestId('wf-win-prev'));

    await finish(ctl, T3, { sunrise: sunrise(T3) });

    expect(onSelect).toHaveBeenLastCalledWith(T3, { isNight: false, isPast: true });
    expect(pill()).toHaveTextContent('Sunrise');
    expect(pill()).toHaveTextContent('Mon 12');
  });

  it('lands on the sunset from ArrowLeft and from the Load row alike', async () => {
    const left = await mount();
    fireEvent.keyDown(pill(), { key: 'ArrowLeft' });
    await finish(left.ctl, T3, { sunrise: sunrise(T3), sunset: sunset(T3) });
    expect(left.onSelect).toHaveBeenLastCalledWith(T3, { isNight: false, isPast: true });
    expect(pill()).toHaveTextContent('Mon 12');
    expect(pill()).toHaveTextContent('Sunset');
    cleanup();

    const row = await mount();
    fireEvent.click(pill());
    fireEvent.click(screen.getByTestId('wf-win-load'));
    await finish(row.ctl, T3, { sunrise: sunrise(T3), sunset: sunset(T3) });
    expect(row.onSelect).toHaveBeenLastCalledWith(T3, { isNight: false, isPast: true });
    expect(pill()).toHaveTextContent('Sunset');
    expect(pill()).toHaveTextContent('Mon 12');
  });

  it('moves NOTHING for a day that came back empty, NAMES it, and offers the next older day', async () => {
    const { ctl, onSelect, onLoad } = await mount();
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    onSelect.mockClear();

    await finish(ctl, T3, null);

    expect(onSelect).not.toHaveBeenCalled();
    expect(pill()).toHaveTextContent('Sunset');
    expect(pill()).toHaveTextContent('Tue 13');
    expect(pill()).not.toHaveTextContent('Loading');
    expect(screen.getByTestId('wf-win-prev')).toHaveAccessibleName('Load Sun 11');
    // The status region says it (the pill keeps focus, and a content change under focus is not read) ...
    expect(screen.getByTestId('wf-win-status')).toHaveTextContent('No stored forecast for Mon 12');
    // ... and the menu draws the line above the Load row for the next older day.
    fireEvent.click(pill());
    expect(screen.getByTestId('wf-win-none')).toHaveTextContent('No stored forecast for Mon 12');
    expect(screen.getByTestId('wf-win-load')).toHaveTextContent('Load Sun 11');
    fireEvent.click(pill());

    fireEvent.click(screen.getByTestId('wf-win-prev'));
    expect(onLoad).toHaveBeenLastCalledWith(T4);
  });

  it('keeps the line at the T-4 floor after the last empty day, and drops it when a later day has rows', async () => {
    const { ctl } = await mount();
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    await finish(ctl, T3, null);
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    await finish(ctl, T4, null);

    // T-4 is the floor: nothing left to load, and `‹` is disabled where it would have fetched.
    expect(screen.getByTestId('wf-win-status')).toHaveTextContent('No stored forecast for Sun 11');
    fireEvent.click(pill());
    expect(screen.getByTestId('wf-win-none')).toHaveTextContent('No stored forecast for Sun 11');
    expect(screen.queryByTestId('wf-win-load')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-win-prev')).toBeDisabled();
  });

  it('says nothing about an empty day once the next older day lands WITH rows', async () => {
    const { ctl } = await mount();
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    await finish(ctl, T3, null);
    expect(screen.getByTestId('wf-win-status')).toHaveTextContent('No stored forecast for Mon 12');

    fireEvent.click(screen.getByTestId('wf-win-prev'));
    await finish(ctl, T4, { sunrise: sunrise(T4), sunset: sunset(T4) });

    expect(screen.getByTestId('wf-win-status')).toBeEmptyDOMElement();
    fireEvent.click(pill());
    expect(screen.queryByTestId('wf-win-none')).not.toBeInTheDocument();
  });

  it('leaves the reader\'s own pick alone when they chose another window while it was out', async () => {
    const { ctl, onSelect } = await mount();
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    // The pill is not disabled: the reader opens the menu and picks tonight's sunset.
    fireEvent.click(pill());
    const tonight = screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === `solar:${TODAY}:SUNSET`);
    await act(async () => { fireEvent.click(tonight); });
    onSelect.mockClear();

    await finish(ctl, T3, { sunrise: sunrise(T3), sunset: sunset(T3) });

    expect(onSelect).not.toHaveBeenCalled();
    expect(pill()).toHaveTextContent('Tonight');
    expect(pill()).not.toHaveTextContent('Mon 12');
  });

  it('a day arriving unasked does not move selection', async () => {
    const { ctl, onSelect } = await mount();
    await finish(ctl, T3, { sunrise: sunrise(T3), sunset: sunset(T3) });
    expect(onSelect).not.toHaveBeenCalled();
  });

  it('a FAILED fetch moves nothing, ends the loading state and offers the same day again', async () => {
    const { ctl, onSelect, onLoad } = await mount();
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    onSelect.mockClear();

    await finish(ctl, T3, null, true);

    expect(onSelect).not.toHaveBeenCalled();
    expect(pill()).not.toHaveTextContent('Loading');
    expect(pill()).not.toHaveAttribute('aria-busy');
    expect(screen.getByTestId('wf-win-prev')).toHaveAccessibleName('Load Mon 12');
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    expect(onLoad).toHaveBeenCalledTimes(2);
    expect(onLoad).toHaveBeenLastCalledWith(T3);
  });

  it('a press that began a fetch for one day does not land on a DIFFERENT day that arrives', async () => {
    const { ctl, onSelect } = await mount();
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    onSelect.mockClear();

    // The pending landing is for T-3; T-4 arrives (some other route) while T-3 is still out.
    await finish(ctl, T4, { sunrise: sunrise(T4), sunset: sunset(T4) });

    expect(onSelect).not.toHaveBeenCalled();
  });

  it('names the day "Yesterday" when that is the next one to fetch', async () => {
    await mount({ baseDays: { [TODAY]: { sunset: sunset(TODAY) } }, startDate: TODAY });
    // Today's elapsed sunrise (an unscored row, no past rows held) is the oldest window: step onto it.
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    expect(screen.getByTestId('wf-win-prev')).toHaveAccessibleName('Load Yesterday');
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    expect(pill()).toHaveTextContent('Loading Yesterday…');
  });
});

describe('an empty day is named on the phone sheet and in the calendar form', () => {
  it('draws the line in the open sheet, in the Load row\'s place', async () => {
    mockIsMobile = true;
    const { ctl } = await mount();
    fireEvent.click(pill());
    fireEvent.click(screen.getByTestId('wf-map-peek-load'));
    await finish(ctl, T3, null);

    fireEvent.click(pill());
    expect(screen.getByTestId('wf-map-peek-none')).toHaveTextContent('No stored forecast for Mon 12');
    expect(screen.getByTestId('wf-map-peek-load')).toHaveTextContent('Load Sun 11');
  });

  it('says Dow d for an empty YESTERDAY, never "Yesterday"', async () => {
    const { ctl } = await mount({ baseDays: { [TODAY]: { sunset: sunset(TODAY) } }, startDate: TODAY });
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    fireEvent.click(screen.getByTestId('wf-win-prev'));
    await finish(ctl, T1, null);

    expect(screen.getByTestId('wf-win-status')).toHaveTextContent('No stored forecast for Wed 14');
    expect(screen.getByTestId('wf-win-status')).not.toHaveTextContent('Yesterday');
  });
});

describe('the phone sheet\'s Load row', () => {
  it('closes the sheet, focuses the pill, fetches, and lands on the sunset', async () => {
    mockIsMobile = true;
    const { ctl, onLoad, onSelect } = await mount();
    fireEvent.click(pill());
    expect(screen.getByTestId('wf-map-peek-earlier')).toHaveTextContent('EARLIER');
    const load = screen.getByTestId('wf-map-peek-load');
    expect(load).toHaveTextContent('Load Mon 12');

    fireEvent.click(load);

    expect(onLoad).toHaveBeenCalledWith(T3);
    expect(screen.queryByTestId('wf-map-peek-load')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(pill());
    expect(pill()).toHaveTextContent('Loading Mon 12…');

    await finish(ctl, T3, { sunrise: sunrise(T3), sunset: sunset(T3) });
    expect(onSelect).toHaveBeenLastCalledWith(T3, { isNight: false, isPast: true });
    expect(pill()).toHaveTextContent('Mon 12');
  });
});
