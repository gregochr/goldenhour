/**
 * `MapView` — the window list offers no night that is over (map-tab-v2-plan.md §5 D-14).
 *
 * <h2>The defect this pins</h2>
 *
 * <p>Both night lists come from endpoints that answer with every night ever stored —
 * `GET /api/astro/conditions/available-dates` and `GET /api/aurora/forecast/results/available-dates`
 * are each a `SELECT DISTINCT forecastDate`, and nothing prunes either table — and `buildMapEvents`
 * used to offer a row for every one of them. The dropdown opened on that history, each past night
 * under a bare weekday with no month ("Thursday night" under "THU 13"), the `‹` stepper walked back
 * into it, and the callout's "Every event here" strip carried a cell per night. Owner decision,
 * 2026-09-14: a night that is over does not appear.
 *
 * <p>The rules are `mapEvents.test.js`'s and `mapDates.test.js`'s. This file proves `MapView`'s half:
 * which night it names as in progress, which it names as on screen, that the past-dated rows it keeps
 * are in the preview fetch, and how a night ends when the reader is on it.
 *
 * <h2>Two parents, on purpose</h2>
 *
 * <p>Most cases render `MapView` alone with a fixed `date` — a parent that never re-clamps. The
 * night-ending cases need `App`'s real behaviour instead, because since D-14 every night the reader
 * picks is handed to `App` (`mapEvents.isForwardableRow`) and it is `App`'s clamp that moves the map
 * off it when it ends. {@link MapUnderAppClamp} is that clamp and nothing else: `App` itself mounts the
 * whole shell. One case keeps the fixed parent deliberately — the moment between the list noticing a
 * night has ended and `App` re-rendering, which is what D-14's on-screen exception bridges.
 *
 * <p>⚠️ Every case pins the clock in BST, where the UK calendar and the runner's (UTC,
 * `src/test/setup.js`) name different days for an hour after UK midnight — the hour this change is
 * most exposed to.
 */
import React, { useCallback, useState } from 'react';
import PropTypes from 'prop-types';
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
  MapContainer: ({ children }) => <div>{children}</div>,
  TileLayer: () => null,
  Marker: ({ children }) => <div>{children}</div>,
  Popup: ({ children }) => <div>{children}</div>,
  Polyline: () => null,
  useMapEvents: () => null,
  useMap: () => ({
    eachLayer: () => {},
    getContainer: () => ({ clientHeight: 500, clientWidth: 800 }),
    getZoom: () => 9,
    once: () => {},
    off: () => {},
    flyTo: () => {},
    fitBounds: () => {},
  }),
}));
vi.mock('../components/MapHeatLayer.jsx', () => ({ default: () => <div /> }));

/** A chip that selects the fixture's one location, so the callout — and its strip — can mount. */
vi.mock('../components/map/MapLabels.jsx', () => ({
  default: ({ onSelect }) => (
    <button type="button" onClick={() => onSelect('Kielder')}>Select Kielder</button>
  ),
}));

/**
 * The callout, reduced to the one thing this file asks of it: which rows its every-event strip was
 * handed. How it draws them is `MapCallout.test.jsx`'s job.
 */
vi.mock('../components/map/MapCallout.jsx', () => ({
  default: ({ evRows }) => (
    <ul>
      {(evRows ?? []).map((row) => (
        <li key={row.id} data-testid="probe-strip-cell" data-ev-id={row.id} />
      ))}
    </ul>
  ),
}));

const mockUseAuth = vi.fn();
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: (...args) => mockUseAuth(...args) }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => false }));

// Swapped per case, but ONE object for the length of a render — `MapView`'s effects key on its
// identity, so a fresh literal per call would re-run them forever.
const { auroraStatusRef, stored, served } = vi.hoisted(() => ({
  auroraStatusRef: { current: null },
  stored: { astro: [], aurora: [] },
  served: { astro: new Map(), aurora: new Map() },
}));
vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: () => ({ status: auroraStatusRef.current }),
}));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn((date) => Promise.resolve(served.aurora.get(date) ?? [])),
  getAuroraForecastAvailableDates: vi.fn(() => Promise.resolve(stored.aurora)),
}));
vi.mock('../api/astroApi.js', () => ({
  getAstroConditions: vi.fn((date) => Promise.resolve(served.astro.get(date) ?? [])),
  getAstroAvailableDates: vi.fn(() => Promise.resolve(stored.astro)),
}));
vi.mock('../api/settingsApi.js', () => ({ getDriveTimes: vi.fn().mockResolvedValue({}) }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn().mockResolvedValue([]) }));
vi.mock('../components/BottomSheet.jsx', () => ({ default: ({ children }) => <div>{children}</div> }));
vi.mock('../components/MarkerPopupContent.jsx', () => ({ default: () => <div /> }));
vi.mock('../components/InfoTip.jsx', () => ({ default: () => null }));
vi.mock('../components/AuroraViewlineOverlay.jsx', () => ({ default: () => null }));
vi.mock('../components/markerUtils.js', () => ({
  buildMarkerSvg: () => '<svg></svg>',
  buildStandDownSvg: () => '<svg></svg>',
  markerLabelAndColour: () => ({ label: '4★', colour: '#E5A00D' }),
  STAND_DOWN_COLOUR: '#501313',
}));

import MapView from '../components/MapView.jsx';
import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { resolveAuroraNight, resolveMapDate, ukDateStr } from '../utils/mapDates.js';

// ── Fixtures ─────────────────────────────────────────────────────────────────

/** Stored months ago and never pruned — the history the list used to open on. */
const LONG_AGO = '2026-04-12';
/** Two nights before FRIDAY. A status that still names it is stale: the backend never would. */
const WEDNESDAY = '2026-08-12';
/** A Thursday. At 02:00 on Friday this is still the night in progress; by midday it is over. */
const THURSDAY = '2026-08-13';
const FRIDAY = '2026-08-14';
const SATURDAY = '2026-08-15';

/** 23:30 BST on Thursday — "Tonight" is Thursday's night, and it has only just begun. */
const THURSDAY_LATE = '2026-08-13T22:30:00Z';
/** 00:30 BST on Friday. UTC still says Thursday; the UK calendar has moved on. */
const FRIDAY_JUST_AFTER_MIDNIGHT = '2026-08-13T23:30:00Z';
/** 02:00 BST on Friday — Thursday's night is still running until dawn. */
const FRIDAY_SMALL_HOURS = '2026-08-14T01:00:00Z';
/** 07:00 BST on Friday — past dawn, so the backend's night in progress has moved on. */
const FRIDAY_AFTER_DAWN = '2026-08-14T06:00:00Z';
/** 13:00 BST on Friday. */
const FRIDAY_MIDDAY = '2026-08-14T12:00:00Z';

const SPOT = { id: 1, name: 'Kielder', lat: 55.23, lng: -2.58, rid: 'Northumberland' };

function makeLocations(dates) {
  return [{
    id: SPOT.id,
    name: SPOT.name,
    lat: SPOT.lat,
    lon: SPOT.lng,
    regionName: SPOT.rid,
    bortleClass: 2,
    locationType: ['LANDSCAPE'],
    forecastsByDate: new Map(dates.map((d) => [d, {
      sunrise: { rating: 4, solarEventTime: `${d}T04:50:00`, fierySkyPotential: 60, goldenHourPotential: 50 },
      sunset: { rating: 4, solarEventTime: `${d}T19:40:00`, fierySkyPotential: 70, goldenHourPotential: 60 },
    }])),
  }];
}

/** Just enough of `WindowFirstMapPane`'s `heat` for the Heat view, and so the label chips, to mount. */
const HEAT = {
  enabled: true,
  hasHome: false,
  spots: [SPOT],
  areaSpots: [SPOT],
  pointsByKey: new Map(),
  windows: [],
  areaBounds: [[54.3, -3.4], [55.7, -1.3]],
  catalogueBounds: [[54.3, -3.4], [56.4, -1.3]],
};

/**
 * `App`'s half of the date handoff and nothing more: it holds the reader's choice and whether it
 * named a night, and shows `resolveMapDate`'s answer, reading the same aurora status `App` reads —
 * `App.jsx`'s `selectDate` and `effectiveDate`. A bespoke parent rather than `App` because `App`
 * mounts the whole shell, and what these cases need from it is exactly this: the clamp that moves
 * the map off a night once it is over.
 */
function MapUnderAppClamp({ forecastDates, onSelectDate, ...rest }) {
  const [choice, setChoice] = useState({ date: null, isNight: false });
  const { status } = useAuroraStatus();
  const date = resolveMapDate({
    selectedDate: choice.date,
    selectedIsNight: choice.isNight,
    autoDate: null,
    allDates: forecastDates,
    todayStr: ukDateStr(),
    nightDate: resolveAuroraNight(status),
  });
  const select = useCallback((next, { isNight = false } = {}) => {
    onSelectDate(next, { isNight });
    setChoice({ date: next, isNight });
  }, [onSelectDate]);
  return <MapView {...rest} forecastDates={forecastDates} date={date} onSelectDate={select} />;
}

MapUnderAppClamp.propTypes = {
  forecastDates: PropTypes.arrayOf(PropTypes.string).isRequired,
  onSelectDate: PropTypes.func.isRequired,
};

/**
 * Every prop built ONCE per case and passed by the same reference to each render, so a rerender
 * changes only what the case itself changed — the clock, or the backend's night in progress.
 */
function baseProps(overrides = {}) {
  return {
    locations: makeLocations([THURSDAY, FRIDAY, SATURDAY]),
    date: FRIDAY,
    forecastDates: [FRIDAY, SATURDAY],
    heat: HEAT,
    autoEventType: null,
    onSelectDate: vi.fn(),
    ...overrides,
  };
}

async function renderMap(props, Component = MapView) {
  let result;
  await act(async () => { result = render(<Component {...props} resizeNonce={0} />); });
  return result;
}

/** A new `resizeNonce` gets past `React.memo`; nothing else about the element changes. */
async function rerenderMap(result, props, nonce, Component = MapView) {
  await act(async () => { result.rerender(<Component {...props} resizeNonce={nonce} />); });
}

async function openWindowList() {
  await act(async () => { fireEvent.click(screen.getByTestId('wf-win-pill')); });
  return within(screen.getByRole('listbox', { name: 'Choose an event' }));
}

const optionIds = (list) => list.getAllByRole('option').map((o) => o.getAttribute('data-ev-id'));
const nightIdsOf = (ids) => ids.filter((id) => !id.startsWith('solar:'));
const optionFor = (list, id) => list.getAllByRole('option').find((o) => o.getAttribute('data-ev-id') === id);

async function pick(list, id) {
  const option = optionFor(list, id);
  expect(option?.getAttribute('data-ev-id')).toBe(id);
  await act(async () => { fireEvent.click(option); });
}

/**
 * The window pill, found by what a screen reader hears — its accessible name — rather than a test
 * id, because the claim these cases make is that the pill NAMES the night on screen.
 */
function expectPillNaming(pattern) {
  expect(screen.getByRole('button', { name: pattern })).toHaveAttribute('aria-haspopup', 'listbox');
}

/** What a PRO reader is offered at Friday midday: nothing dated before Friday, of either kind. */
const FRIDAY_MIDDAY_LIST = [
  `solar:${FRIDAY}:SUNRISE`, `solar:${FRIDAY}:SUNSET`, `astro:${FRIDAY}:ASTRO`, `aur:${FRIDAY}:AURORA`,
  `solar:${SATURDAY}:SUNRISE`, `solar:${SATURDAY}:SUNSET`, `astro:${SATURDAY}:ASTRO`,
];

beforeEach(() => {
  localStorage.clear();
  mockUseAuth.mockReturnValue({ role: 'PRO_USER' });
  auroraStatusRef.current = { level: 'QUIET', currentNightDate: FRIDAY };
  stored.astro = [LONG_AGO, THURSDAY, FRIDAY, SATURDAY];
  stored.aurora = [LONG_AGO, THURSDAY, FRIDAY];
  served.astro = new Map();
  served.aurora = new Map();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(FRIDAY_MIDDAY));
});
afterEach(() => {
  vi.useRealTimers();
  localStorage.clear();
});

describe('MapView — D-14: the window list offers no night that is over', () => {
  it('lists no night that is over, however long ago it was stored', async () => {
    // Thursday's night ended at dawn and LONG_AGO's months back; both kinds were stored for both.
    await renderMap(baseProps());
    expect(optionIds(await openWindowList())).toEqual(FRIDAY_MIDDAY_LIST);
  });

  it('stops the ‹ stepper at today\'s first window instead of walking back into history', async () => {
    await renderMap(baseProps());
    await pick(await openWindowList(), `solar:${FRIDAY}:SUNSET`);

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Previous event' })); });

    expectPillNaming(/Sunrise/);
    expect(screen.getByRole('button', { name: 'Previous event' })).toBeDisabled();
  });

  it('hands the callout\'s every-event strip the same clipped list', async () => {
    await renderMap(baseProps());
    fireEvent.click(await screen.findByRole('button', { name: 'Select Kielder' }));

    const cells = await screen.findAllByTestId('probe-strip-cell');
    expect(cells.map((c) => c.getAttribute('data-ev-id'))).toEqual(FRIDAY_MIDDAY_LIST);
  });

  it('offers the night in progress in the small hours — the one past date still on', async () => {
    vi.setSystemTime(new Date(FRIDAY_SMALL_HOURS));
    auroraStatusRef.current = { level: 'QUIET', currentNightDate: THURSDAY };
    await renderMap(baseProps());

    expect(nightIdsOf(optionIds(await openWindowList()))).toEqual([
      `astro:${THURSDAY}:ASTRO`, `aur:${THURSDAY}:AURORA`,
      `astro:${FRIDAY}:ASTRO`, `aur:${FRIDAY}:AURORA`, `astro:${SATURDAY}:ASTRO`,
    ]);
  });

  it('gives the night in progress its best and its time, both kinds — it is in the preview fetch', async () => {
    // The preview is bounded to the solar horizon, which starts at today; D-14 adds the past-dated
    // nights it still offers. Without that the row read "—" with no time even while selected. Both
    // kinds, because each has its own preview memo to wire.
    vi.setSystemTime(new Date(FRIDAY_SMALL_HOURS));
    auroraStatusRef.current = { level: 'QUIET', currentNightDate: THURSDAY };
    served.aurora.set(THURSDAY, [{ locationName: 'Kielder', stars: 3, nightStart: `${THURSDAY}T20:40:00` }]);
    served.astro.set(THURSDAY, [{ locationName: 'Kielder', stars: 2, nightStart: `${THURSDAY}T20:55:00` }]);
    await renderMap(baseProps());

    const list = await openWindowList();
    const aurora = optionFor(list, `aur:${THURSDAY}:AURORA`);
    expect(aurora).toHaveTextContent('3★ best');
    expect(aurora).toHaveTextContent('21:40'); // 20:40 UTC is 21:40 BST
    expect(optionFor(list, `astro:${THURSDAY}:ASTRO`)).toHaveTextContent('2★ best');
  });

  it('gives LITE the calendar, having no night in progress to go on — Thursday\'s night is gone from UK midnight', async () => {
    // Aurora status is PRO/ADMIN-only and folds LITE's 403 to null, so `resolveAuroraNight` falls
    // back to the UK date. A real loss against the unclipped list, accepted by the owner (D-14,
    // exit §6 O-21) — and LITE sees no aurora rows at all.
    mockUseAuth.mockReturnValue({ role: 'LITE_USER' });
    auroraStatusRef.current = null;
    vi.setSystemTime(new Date(FRIDAY_SMALL_HOURS));
    await renderMap(baseProps());

    expect(nightIdsOf(optionIds(await openWindowList()))).toEqual([
      `astro:${FRIDAY}:ASTRO`, `astro:${SATURDAY}:ASTRO`,
    ]);
  });

  it('does not let a stale aurora status bring an old night back', async () => {
    // The status provider keeps the last status when a later fetch fails, so one can outlive its
    // night by days. The backend only ever names today or yesterday, and so does the rule now.
    stored.aurora = [WEDNESDAY, THURSDAY, FRIDAY];
    auroraStatusRef.current = { level: 'QUIET', currentNightDate: WEDNESDAY };
    await renderMap(baseProps());

    expect(nightIdsOf(optionIds(await openWindowList()))).toEqual([
      `astro:${FRIDAY}:ASTRO`, `aur:${FRIDAY}:AURORA`, `astro:${SATURDAY}:ASTRO`,
    ]);
  });
});

describe('MapView — D-14: how a night ends while the reader is on it', () => {
  it.each([
    ['at 23:30 on Thursday, as "Tonight"', THURSDAY_LATE, /Tonight/],
    ['at 02:00 on Friday, as "Thursday night"', FRIDAY_SMALL_HOURS, /Thursday night/],
  ])('a night picked %s is handed to App, and App moves the map on at dawn', async (_, pickedAt, pillBefore) => {
    // One ending, whichever side of midnight the night was picked. Until D-14's forwarding change
    // the 02:00 pick was kept local and stayed on screen, ended, while the 23:30 pick moved on.
    auroraStatusRef.current = { level: 'QUIET', currentNightDate: THURSDAY };
    vi.setSystemTime(new Date(pickedAt));
    const onSelectDate = vi.fn();
    const props = baseProps({ forecastDates: [THURSDAY, FRIDAY, SATURDAY], onSelectDate });
    delete props.date;
    const result = await renderMap(props, MapUnderAppClamp);
    await pick(await openWindowList(), `aur:${THURSDAY}:AURORA`);

    expect(onSelectDate).toHaveBeenCalledWith(THURSDAY, { isNight: true });
    expectPillNaming(pillBefore);

    auroraStatusRef.current = { level: 'QUIET', currentNightDate: FRIDAY };
    vi.setSystemTime(new Date(FRIDAY_AFTER_DAWN));
    await rerenderMap(result, props, 1, MapUnderAppClamp);

    // App refused the ended night and fell back to today, so the map is on Friday's aurora night.
    expectPillNaming(/Tonight/);
    expect(nightIdsOf(optionIds(await openWindowList()))).not.toContain(`aur:${THURSDAY}:AURORA`);
  });

  it('moves LITE off the night at UK midnight, not dawn — the accepted loss', async () => {
    // LITE's night in progress is the calendar date, so App's clamp refuses Thursday's night the
    // first time it renders after midnight. D-14 cannot soften this: it is App's rule, and it
    // predates D-14 (#803). What D-14 adds is that the row is then gone from the list as well.
    mockUseAuth.mockReturnValue({ role: 'LITE_USER' });
    auroraStatusRef.current = null;
    vi.setSystemTime(new Date(THURSDAY_LATE));
    const onSelectDate = vi.fn();
    const props = baseProps({ forecastDates: [THURSDAY, FRIDAY], onSelectDate });
    delete props.date;
    const result = await renderMap(props, MapUnderAppClamp);
    await pick(await openWindowList(), `astro:${THURSDAY}:ASTRO`);
    expectPillNaming(/Tonight/);

    vi.setSystemTime(new Date(FRIDAY_JUST_AFTER_MIDNIGHT));
    await rerenderMap(result, props, 1, MapUnderAppClamp);

    expectPillNaming(/Tonight/); // Friday's night now
    expect(nightIdsOf(optionIds(await openWindowList()))).toEqual([
      `astro:${FRIDAY}:ASTRO`, `astro:${SATURDAY}:ASTRO`,
    ]);
  });

  /**
   * The bridge. LITE at 23:30 on Thursday picks "Tonight"; the parent holds Thursday. After UK
   * midnight the list knows that night is over — but this parent never re-clamps, which is the state
   * a real `App` is in until its next render (within about 30 seconds, while its health stream is
   * connected). D-14's on-screen exception exists for exactly that window.
   */
  async function liteReaderOnThursdayNightAcrossMidnight() {
    mockUseAuth.mockReturnValue({ role: 'LITE_USER' });
    auroraStatusRef.current = null;
    vi.setSystemTime(new Date(THURSDAY_LATE));
    const props = baseProps({ date: THURSDAY, forecastDates: [THURSDAY] });
    const result = await renderMap(props);
    await pick(await openWindowList(), `astro:${THURSDAY}:ASTRO`);
    expectPillNaming(/Tonight/);

    vi.setSystemTime(new Date(FRIDAY_JUST_AFTER_MIDNIGHT));
    await rerenderMap(result, props, 1);
    return { props, result };
  }

  it('holds the pill on the night on screen until the parent re-clamps — never "No forecast"', async () => {
    await liteReaderOnThursdayNightAcrossMidnight();

    // Not "No forecast": the pill still names the night the map is painting, now by its weekday.
    expect(screen.queryByTestId('wf-win-no-match')).toBeNull();
    expectPillNaming(/Thursday night/);
  });

  it('keeps that ended night in the preview, so its row keeps its best', async () => {
    served.astro.set(THURSDAY, [{ locationName: 'Kielder', stars: 4, nightStart: `${THURSDAY}T20:10:00` }]);
    await liteReaderOnThursdayNightAcrossMidnight();

    expect(optionFor(await openWindowList(), `astro:${THURSDAY}:ASTRO`)).toHaveTextContent('4★ best');
  });

  it('keeps an ended AURORA night in the preview too — the bridge at dawn, for the kind LITE never sees', async () => {
    // PRO, the parent holding Thursday's night, and dawn passing before it re-clamps: for PRO that
    // gap is shorter still (the dawn status reaches App in the same render), but the aurora memo is
    // its own wiring and nothing else would notice it going missing.
    auroraStatusRef.current = { level: 'QUIET', currentNightDate: THURSDAY };
    vi.setSystemTime(new Date(FRIDAY_SMALL_HOURS));
    served.aurora.set(THURSDAY, [{ locationName: 'Kielder', stars: 5, nightStart: `${THURSDAY}T20:40:00` }]);
    const props = baseProps({ date: THURSDAY, forecastDates: [THURSDAY, FRIDAY] });
    const result = await renderMap(props);
    await pick(await openWindowList(), `aur:${THURSDAY}:AURORA`);

    auroraStatusRef.current = { level: 'QUIET', currentNightDate: FRIDAY };
    vi.setSystemTime(new Date(FRIDAY_AFTER_DAWN));
    await rerenderMap(result, props, 1);

    expect(optionFor(await openWindowList(), `aur:${THURSDAY}:AURORA`)).toHaveTextContent('5★ best');
  });

  it('lets that night go once the reader steps off it, and never offers it back', async () => {
    await liteReaderOnThursdayNightAcrossMidnight();
    // Friday is outside this fixture's forecast dates, so the pick is kept local and the parent's
    // date stays on Thursday — the night on screen changes all the same.
    await pick(await openWindowList(), `astro:${FRIDAY}:ASTRO`);

    expect(nightIdsOf(optionIds(await openWindowList()))).toEqual([
      `astro:${FRIDAY}:ASTRO`, `astro:${SATURDAY}:ASTRO`,
    ]);
    expect(screen.getByRole('button', { name: 'Previous event' })).toBeDisabled();
  });
});
