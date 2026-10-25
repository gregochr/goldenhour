/**
 * `MapView` on a PAST solar window (outcome-rating-plan.md §2 #2, §3 O3a steps 2, 3 and 5).
 *
 * Three things are pinned here, all of them seams between pieces that have their own unit tests:
 *
 * <ol>
 *   <li><b>The row exists and is selectable.</b> With the pane's {@code pastSolarWindows} (built by
 *   the real {@code buildPastSolarWindows} from the locations' own {@code forecastsByDate}, so the
 *   pane-to-view seam is exercised too), yesterday's sunset and this morning's elapsed sunrise are
 *   rows of the window control, the pill wears {@code Past}, and picking one reaches
 *   {@code onSelectDate} with the provenance flag {@code isPast: true} ({@code App.resolveMapDate}
 *   refuses a date before today without it). Picking a live window clears the flag.</li>
 *   <li><b>Ratings read the forecast row ALONE.</b> The briefing score index serves the latest
 *   result for a slot, which for a passed slot may have landed after the event; the forecast rows
 *   for a passed slot are served as of it. So with a briefing score of 5 and a forecast row of 3,
 *   a past window's chip reads 3 and a served window's reads 5 — and a forecast triage with a
 *   briefing rating is a stand-down on a past window and a rated spot on a served one.</li>
 *   <li><b>Nothing regresses for a window with no row.</b> Without the pane's input a past date is
 *   still "No forecast" (the existing MapViewStaleWindow contract).</li>
 * </ol>
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';

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
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, clientWidth: 800 }),
  }),
}));

vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'ADMIN' }) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));
vi.mock('../hooks/useAuroraStatus.js', () => ({ useAuroraStatus: () => ({ status: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn().mockResolvedValue([]),
  getAstroAvailableDates: vi.fn().mockResolvedValue([]),
}));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));

// What `MapView` hands `MapLabels` is the other end of the wire for a rating: its `rating` becomes
// the chip's `N★` and `isStandDown` the stand-down vocabulary. Placing a chip needs a real Leaflet
// pane (MapLabels.test.jsx's job), so a stub records the spots.
const labelSpotsSeen = { last: null };
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: ({ spots }) => {
    labelSpotsSeen.last = spots;
    return <div data-testid="map-labels-stub" />;
  },
}));

import MapView from '../components/MapView.jsx';
import { buildPastSolarWindows } from '../utils/mapEvents.js';

const TWO_AGO = '2026-01-13';
const YESTERDAY = '2026-01-14';
const TODAY = '2026-01-15';
const TOMORROW = '2026-01-16';

beforeEach(() => {
  labelSpotsSeen.last = null;
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  // 09:40 on TODAY: this morning's sunrise (08:24 + the 30-minute afterglow = 08:54) has elapsed,
  // tonight's sunset (16:12) has not.
  vi.setSystemTime(new Date(`${TODAY}T09:40:00Z`));
});
afterEach(() => { vi.useRealTimers(); localStorage.clear(); });

const SPOTS = [
  { id: 1, name: 'Bamburgh', lat: 55.61, lng: -1.71, rid: 'North East' },
  { id: 2, name: 'Tynemouth', lat: 55.02, lng: -1.42, rid: 'North East' },
];

/** A location whose forecast rows are exactly `days` (date → {sunrise?, sunset?}). */
const makeLocations = (days) => SPOTS.map((s) => ({
  id: s.id,
  name: s.name,
  lat: s.lat,
  lon: s.lng,
  regionName: s.rid,
  bortleClass: 4,
  locationType: ['LANDSCAPE'],
  forecastsByDate: new Map(Object.entries(days)),
}));

const sunset = (date, extra = {}) => ({ solarEventTime: `${date}T16:12:00`, ...extra });
const sunrise = (date, extra = {}) => ({ solarEventTime: `${date}T08:24:00`, ...extra });

const heat = {
  enabled: true,
  hasHome: false,
  spots: SPOTS,
  areaSpots: SPOTS,
  pointsByKey: new Map(),
  windows: [
    { key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', label: 'Tonight sunset', time: '16:12', bestRating: 5, conf: 1 },
    { key: `${TOMORROW}:SUNRISE`, date: TOMORROW, targetType: 'SUNRISE', label: 'Tomorrow sunrise', time: '08:24', bestRating: 4, conf: 0.82 },
  ],
  areaBounds: [[54.3, -3.4], [55.7, -1.3]],
  catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
};

/** The briefing score index key `MapView` reads: `region|date|targetType|location`. */
const briefingFor = (date, rating, extra = {}, type = 'SUNSET') => new Map(
  SPOTS.map((s) => [`North East|${date}|${type}|${s.name}`, { rating, ...extra }]),
);

async function renderMap({ locations, ...props }) {
  let out;
  await act(async () => {
    out = render(
      <MapView
        autoEventType={null}
        locations={locations}
        pastSolarWindows={buildPastSolarWindows(locations, TODAY)}
        heat={heat}
        {...props}
      />,
    );
  });
  return out;
}

const ratings = () => (labelSpotsSeen.last ?? []).map((s) => s.rating);

describe('a past window is a row of the window control', () => {
  it('offers yesterday\'s sunset and shows the Past tag instead of "No forecast"', async () => {
    await renderMap({
      locations: makeLocations({ [YESTERDAY]: { sunset: sunset(YESTERDAY, { rating: 3 }) } }),
      date: YESTERDAY,
      forecastDates: [YESTERDAY, TODAY],
    });

    expect(screen.queryByTestId('wf-win-no-match')).not.toBeInTheDocument();
    expect(screen.getByTestId('wf-win-past')).toHaveTextContent('Past');
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent('Yesterday');
  });

  it('control: WITHOUT the pane\'s past windows the same date is still "No forecast"', async () => {
    await renderMap({
      locations: makeLocations({ [YESTERDAY]: { sunset: sunset(YESTERDAY, { rating: 3 }) } }),
      pastSolarWindows: [],
      date: YESTERDAY,
      forecastDates: [YESTERDAY, TODAY],
    });

    expect(screen.getByTestId('wf-win-no-match')).toHaveTextContent('No forecast');
    expect(screen.queryByTestId('wf-win-past')).not.toBeInTheDocument();
  });

  it('draws Past on today\'s elapsed, unserved sunrise — and not on tonight\'s served sunset', async () => {
    await renderMap({
      locations: makeLocations({
        [TODAY]: { sunrise: sunrise(TODAY, { rating: 4 }), sunset: sunset(TODAY, { rating: 4 }) },
      }),
      date: TODAY,
      forecastDates: [TODAY],
      autoEventType: 'SUNRISE',
      ratedCounts: new Map([[`solar:${TODAY}:SUNRISE`, 0]]),
    });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent('Sunrise');
    expect(screen.getByTestId('wf-win-past')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const row = (id) => screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === id);
    expect(row(`solar:${TODAY}:SUNRISE`)).toBeDefined();
    expect(row(`solar:${TODAY}:SUNSET`)).toBeDefined();
    // The served sunset beside it wears no Past tag in the dropdown — only the elapsed sunrise does.
    expect(within(row(`solar:${TODAY}:SUNRISE`)).getByTestId('wf-win-notrated')).toBeInTheDocument();
    expect(within(row(`solar:${TODAY}:SUNSET`)).queryByTestId('wf-win-notrated')).not.toBeInTheDocument();
  });

  it('draws no Past tag on the served sunset once it is the window on screen', async () => {
    await renderMap({
      locations: makeLocations({
        [TODAY]: { sunrise: sunrise(TODAY, { rating: 4 }), sunset: sunset(TODAY, { rating: 4 }) },
      }),
      date: TODAY,
      forecastDates: [TODAY],
      autoEventType: 'SUNSET',
    });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent('Sunset');
    expect(screen.queryByTestId('wf-win-past')).not.toBeInTheDocument();
  });

  it.each([
    ['one minute short of the afterglow (08:24 + 30 min = 08:54, strict)', '08:54:00', false],
    ['one minute over it', '08:55:00', true],
  ])('with the REAL app clock, today\'s unserved sunrise at %s is past: %s', async (_name, clock, isPast) => {
    // Pins the shipped `briefingDisplay.isEventTimePast` at the boundary, not a copy of it.
    vi.setSystemTime(new Date(`${TODAY}T${clock}Z`));
    await renderMap({
      locations: makeLocations({ [TODAY]: { sunrise: sunrise(TODAY, { rating: 4 }) } }),
      date: TODAY,
      forecastDates: [TODAY],
      autoEventType: 'SUNRISE',
    });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent('Sunrise');
    if (isPast) expect(screen.getByTestId('wf-win-past')).toBeInTheDocument();
    else expect(screen.queryByTestId('wf-win-past')).not.toBeInTheDocument();
  });

  it('prints a BST window\'s time on the UK clock, not UTC', async () => {
    // 2026-07-14 17:18Z is 18:18 in London. The January fixtures cannot tell the two apart.
    vi.setSystemTime(new Date('2026-07-15T09:40:00Z'));
    const day = '2026-07-14';
    const locations = makeLocations({ [day]: { sunset: { rating: 3, solarEventTime: `${day}T17:18:00` } } });
    await renderMap({
      locations,
      // `renderMap` builds the windows against the file's January TODAY; this test's today is July.
      pastSolarWindows: buildPastSolarWindows(locations, '2026-07-15'),
      date: day,
      forecastDates: [day, '2026-07-15'],
      autoEventType: 'SUNSET',
    });
    expect(screen.getByTestId('wf-win-pill')).toHaveTextContent('18:18');
  });
});

describe('selecting a window reaches onSelectDate with the past provenance flag', () => {
  const pick = async (rowId) => {
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const row = screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === rowId);
    expect(row?.getAttribute('data-ev-id')).toBe(rowId);
    await act(async () => { fireEvent.click(row); });
  };

  it('forwards a past row as { isNight: false, isPast: true }', async () => {
    const onSelectDate = vi.fn();
    await renderMap({
      locations: makeLocations({
        [TWO_AGO]: { sunset: sunset(TWO_AGO, { rating: 2 }) },
        [YESTERDAY]: { sunset: sunset(YESTERDAY, { rating: 3 }) },
      }),
      date: TODAY,
      forecastDates: [TWO_AGO, YESTERDAY, TODAY],
      onSelectDate,
    });

    await pick(`solar:${YESTERDAY}:SUNSET`);
    expect(onSelectDate).toHaveBeenLastCalledWith(YESTERDAY, { isNight: false, isPast: true });

    await pick(`solar:${TWO_AGO}:SUNSET`);
    expect(onSelectDate).toHaveBeenLastCalledWith(TWO_AGO, { isNight: false, isPast: true });
  });

  it('forwards today\'s elapsed sunrise flagged past, on today\'s own date', async () => {
    const onSelectDate = vi.fn();
    await renderMap({
      locations: makeLocations({ [TODAY]: { sunrise: sunrise(TODAY, { rating: 4 }) } }),
      date: TODAY,
      forecastDates: [TODAY],
      onSelectDate,
    });

    await pick(`solar:${TODAY}:SUNRISE`);
    expect(onSelectDate).toHaveBeenLastCalledWith(TODAY, { isNight: false, isPast: true });
  });

  it('forwards a live window with NO isPast key, so the licence is per pick and the call is unchanged', async () => {
    const onSelectDate = vi.fn();
    await renderMap({
      locations: makeLocations({ [YESTERDAY]: { sunset: sunset(YESTERDAY, { rating: 3 }) } }),
      date: YESTERDAY,
      forecastDates: [YESTERDAY, TODAY, TOMORROW],
      onSelectDate,
    });

    await pick(`solar:${TOMORROW}:SUNRISE`);
    expect(onSelectDate).toHaveBeenLastCalledWith(TOMORROW, { isNight: false });
    // `toHaveBeenCalledWith` treats an undefined key as absent; the key itself must not be there.
    expect(onSelectDate.mock.calls.at(-1)[1]).not.toHaveProperty('isPast');
  });
});

describe('ratings on a past window read the forecast row ALONE', () => {
  it('holds for today\'s elapsed sunrise too, through the shipped clock (forecast 3 against briefing 5)', async () => {
    await renderMap({
      locations: makeLocations({ [TODAY]: { sunrise: sunrise(TODAY, { rating: 3 }) } }),
      briefingScores: briefingFor(TODAY, 5, {}, 'SUNRISE'),
      date: TODAY,
      forecastDates: [TODAY],
      autoEventType: 'SUNRISE',
    });
    expect(screen.getByTestId('wf-win-past')).toBeInTheDocument();
    expect(ratings()).toEqual([3, 3]);
  });

  it('shows the forecast row\'s star, not the briefing score index\'s', async () => {
    await renderMap({
      locations: makeLocations({ [YESTERDAY]: { sunset: sunset(YESTERDAY, { rating: 3 }) } }),
      briefingScores: briefingFor(YESTERDAY, 5),
      date: YESTERDAY,
      forecastDates: [YESTERDAY, TODAY],
    });

    expect(ratings()).toEqual([3, 3]);
  });

  it('control: on a SERVED window the briefing score still wins, as today', async () => {
    await renderMap({
      locations: makeLocations({ [TODAY]: { sunset: sunset(TODAY, { rating: 3 }) } }),
      briefingScores: briefingFor(TODAY, 5),
      date: TODAY,
      forecastDates: [TODAY],
    });

    expect(ratings()).toEqual([5, 5]);
  });

  it('does not fall through to the briefing when the forecast row has no rating', async () => {
    // A late batch's rating sits in the briefing index; the forecast row for the passed slot, served
    // as of the event, has none. Unrated is the honest answer — not the late star.
    await renderMap({
      locations: makeLocations({ [YESTERDAY]: { sunset: sunset(YESTERDAY, { rating: null }) } }),
      briefingScores: briefingFor(YESTERDAY, 5),
      date: YESTERDAY,
      forecastDates: [YESTERDAY, TODAY],
    });

    // Unrated spots sit below the default 3★ floor, so none is drawn; none carries the late 5.
    expect(ratings()).not.toContain(5);
    expect(ratings()).toEqual([]);
  });

  it('reads stand-down from the forecast row alone: a forecast triage is a stand-down even beside a briefing rating', async () => {
    await renderMap({
      locations: makeLocations({
        [YESTERDAY]: { sunset: sunset(YESTERDAY, { rating: null, triageReason: 'Overcast' }) },
      }),
      briefingScores: briefingFor(YESTERDAY, 4),
      date: YESTERDAY,
      forecastDates: [YESTERDAY, TODAY],
    });

    // Stood down → hidden behind the stand-down toggle; with the briefing consulted they would be
    // two rated 4★ spots instead.
    expect(labelSpotsSeen.last).toEqual([]);
  });

  it('control: the same pair on a served window is NOT a stand-down (the briefing\'s rating wins)', async () => {
    await renderMap({
      locations: makeLocations({
        [TODAY]: { sunset: sunset(TODAY, { rating: null, triageReason: 'Overcast' }) },
      }),
      briefingScores: briefingFor(TODAY, 4),
      date: TODAY,
      forecastDates: [TODAY],
    });

    expect(ratings()).toEqual([4, 4]);
  });
});

describe('spot.myRating — the reader\'s own record rides labelSpots on a PAST window only (O4b)', () => {
  const myRatings = () => (labelSpotsSeen.last ?? []).map((s) => s.myRating ?? null);
  /** The hook's return value, as far as `MapView` reads it. */
  const ratingsFor = (entries, extra = {}) => ({
    mine: new Map(entries),
    covers: () => true,
    wasRead: () => true,
    readFailed: false,
    loaded: true,
    saving: new Set(),
    saveError: new Set(),
    ...extra,
  });
  const MINE = [
    [`1|${YESTERDAY}|SUNSET`, { rating: 4 }],
    [`2|${YESTERDAY}|SUNSET`, { rating: null }],
    // Another window of the same day, and a served window: neither may leak onto the sunset.
    [`1|${YESTERDAY}|SUNRISE`, { rating: 2 }],
    [`1|${TODAY}|SUNSET`, { rating: 5 }],
  ];
  const past = (extra) => renderMap({
    locations: makeLocations({
      [YESTERDAY]: { sunrise: sunrise(YESTERDAY, { rating: 3 }), sunset: sunset(YESTERDAY, { rating: 3 }) },
    }),
    date: YESTERDAY,
    forecastDates: [YESTERDAY, TODAY],
    autoEventType: 'SUNSET',
    ...extra,
  });

  it('carries a star, NOT_SEEN or nothing per spot — for the window on screen alone', async () => {
    await past({ outcomeRatings: ratingsFor(MINE) });
    expect(myRatings()).toEqual([4, 'NOT_SEEN']);
  });

  it('is absent on a SERVED window even though the same reader has a record for it', async () => {
    await renderMap({
      locations: makeLocations({ [TODAY]: { sunset: sunset(TODAY, { rating: 3 }) } }),
      date: TODAY,
      forecastDates: [TODAY],
      autoEventType: 'SUNSET',
      outcomeRatings: ratingsFor(MINE),
    });
    expect(screen.queryByTestId('wf-win-past')).not.toBeInTheDocument();
    expect(myRatings()).toEqual([null, null]);
  });

  it('is absent after a FAILED read: an empty map there is not "unrated"', async () => {
    await past({ outcomeRatings: ratingsFor(MINE, { readFailed: true }) });
    expect(myRatings()).toEqual([null, null]);
  });

  it('is absent for a date the read has not covered yet', async () => {
    await past({ outcomeRatings: ratingsFor(MINE, { wasRead: () => false }) });
    expect(myRatings()).toEqual([null, null]);
  });

  it('is absent with no ratings at all (a rewind, the overlay)', async () => {
    await past({ outcomeRatings: null });
    expect(myRatings()).toEqual([null, null]);
  });
});

describe('ratedCounts reach the window control (O4b)', () => {
  it('prints "{n} rated" on a past row from the pane\'s map, and nothing for a row the map lacks', async () => {
    await renderMap({
      locations: makeLocations({
        [YESTERDAY]: { sunrise: sunrise(YESTERDAY, { rating: 3 }), sunset: sunset(YESTERDAY, { rating: 3 }) },
      }),
      date: YESTERDAY,
      forecastDates: [YESTERDAY, TODAY],
      autoEventType: 'SUNSET',
      ratedCounts: new Map([[`solar:${YESTERDAY}:SUNSET`, 2]]),
    });
    fireEvent.click(screen.getByTestId('wf-win-pill'));
    const row = (id) => screen.getAllByTestId('wf-win-row').find((r) => r.getAttribute('data-ev-id') === id);
    expect(within(row(`solar:${YESTERDAY}:SUNSET`)).getByTestId('wf-win-rated')).toHaveTextContent('2 rated');
    expect(within(row(`solar:${YESTERDAY}:SUNRISE`)).queryByTestId('wf-win-notrated')).not.toBeInTheDocument();
    expect(within(row(`solar:${YESTERDAY}:SUNRISE`)).queryByTestId('wf-win-rated')).not.toBeInTheDocument();
  });
});
