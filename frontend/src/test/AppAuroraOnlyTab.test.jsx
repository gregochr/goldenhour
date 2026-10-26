/**
 * `App.jsx` — the Map tab on an aurora alert alone (overlay convergence C3,
 * `docs/engineering/overlay-convergence-plan.md` §3 C3 and §5 D4).
 *
 * Until C3 `App` withheld the whole Map pane whenever `GET /api/forecast` returned nothing for a
 * visible location, so the aurora banner (shown on every tab) needed the overlay to reach a map at
 * all. Now a REAL MODERATE/STRONG alert offers the tab too (`utils/auroraTab.js`), opening on the
 * night in progress (which, in the small hours, is YESTERDAY's date: the clock is pinned for it).
 * What must NOT move: a reader with forecast data (the pane's WHOLE props are the same whether or not
 * an alert is running), a LITE reader, a quiet alert, a simulated one and a status past its night's end.
 *
 * Built in the shape of `AppOpenMapTabFromPlan.test.jsx`: the shell and the pane are stubs that
 * record the props `App` hands them, so the decision is read straight off the prop.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, waitFor } from '@testing-library/react';
import App from '../App.jsx';

vi.mock('../api/forecastApi.js', () => ({
  fetchForecasts: vi.fn(),
  fetchLocations: vi.fn(),
  clearForecastDetailCache: vi.fn(),
}));
vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({
  getSettings: vi.fn(),
  getReach: vi.fn(),
  getDriveTimes: vi.fn(),
  lookupPostcode: vi.fn(),
  saveHome: vi.fn(),
  refreshDriveTimes: vi.fn(),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraNights: vi.fn().mockResolvedValue({ nights: [], currentNightEndsAt: null }),
  getAuroraStatus: vi.fn(),
  getAuroraAlert: vi.fn().mockResolvedValue(null),
  getAuroraLocations: vi.fn().mockResolvedValue([]),
  getAuroraForecastResults: vi.fn().mockResolvedValue([]),
  getAuroraForecastAvailableDates: vi.fn().mockResolvedValue([]),
  getAuroraForecastViewline: vi.fn().mockResolvedValue(null),
  getAuroraViewline: vi.fn().mockResolvedValue(null),
}));
vi.mock('../api/nlcApi.js', () => ({ getNlcSighting: vi.fn() }));
vi.mock('../api/astroApi.js', () => ({ getAstroConditions: vi.fn() }));
vi.mock('../api/almanacApi.js', () => ({ getAlmanac: vi.fn(), ALMANAC_DAYS: 90 }));
vi.mock('../api/runProgressApi.js', () => ({ subscribeToRunNotifications: vi.fn() }));
vi.mock('../api/lightApi.js', () => ({ getTodaysLight: vi.fn() }));
vi.mock('../api/authApi.js', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
  refreshAccessToken: vi.fn(),
  register: vi.fn(),
  resendVerification: vi.fn(),
  verifyEmail: vi.fn(),
  setPasswordForNewUser: vi.fn(),
  submitWaitlist: vi.fn(),
}));

vi.mock('../components/WindowFirstShell.jsx', () => ({
  default: (props) => <div data-testid="stub-shell">{props.mapPane}</div>,
}));
// The banner is not under test and reads its own score hooks; the pane is the stub that records props.
vi.mock('../components/AuroraBanner.jsx', () => ({ default: () => null }));
const MapPaneStub = { lastProps: null };
vi.mock('../components/WindowFirstMapPane.jsx', () => ({
  default: (props) => { MapPaneStub.lastProps = props; return <div data-testid="stub-map-pane" />; },
}));

import { fetchForecasts, fetchLocations } from '../api/forecastApi.js';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getSettings, getReach, getDriveTimes } from '../api/settingsApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAuroraAlert, getAuroraStatus } from '../api/auroraApi.js';
import { getNlcSighting } from '../api/nlcApi.js';
import { getAstroConditions } from '../api/astroApi.js';
import { getAlmanac } from '../api/almanacApi.js';
import { subscribeToRunNotifications } from '../api/runProgressApi.js';
import { getTodaysLight } from '../api/lightApi.js';

/** 02:00 UK on 14 August: the night in progress began on the 13th and ends at 04:00 UTC. */
const NOW = '2026-08-14T01:00:00Z';
const TODAY = '2026-08-14';
const THE_NIGHT = '2026-08-13';
const TOMORROW = '2026-08-15';

const LOCATION_META = [
  {
    id: 7, name: 'Bamburgh', lat: 55.608, lon: -1.719, enabled: true,
    locationType: ['SEASCAPE'], tideType: ['HIGH'], solarEventType: ['SUNRISE', 'SUNSET'],
    bortleClass: 4, region: { name: 'Northumberland' },
  },
];
const HIDE_META = {
  id: 21, name: 'Gosforth Nature Reserve', lat: 55.0, lon: -1.62, enabled: true,
  locationType: ['WILDLIFE'], tideType: [], solarEventType: [],
  bortleClass: 5, region: { name: 'Northumberland' },
};
const forecastRow = (meta, targetType) => ({
  locationName: meta.name,
  locationLat: String(meta.lat),
  locationLon: String(meta.lon),
  targetDate: TOMORROW,
  targetType,
  forecastRunAt: '2026-01-01T06:00:00',
  rating: 3,
});
const FORECASTS = LOCATION_META.flatMap((m) => [forecastRow(m, 'SUNRISE'), forecastRow(m, 'SUNSET')]);
const hourlyRow = (date) => ({
  locationName: HIDE_META.name,
  locationLat: String(HIDE_META.lat),
  locationLon: String(HIDE_META.lon),
  targetDate: date,
  targetType: 'HOURLY',
  solarEventTime: `${date}T09:00:00`,
  forecastRunAt: '2026-01-01T06:00:00',
  temperatureCelsius: 8,
});

/** A real STRONG alert whose night (the 13th) is still running at NOW. */
const REAL_STRONG = {
  active: true, level: 'STRONG', currentNightDate: THE_NIGHT,
  currentNightEndsAt: '2026-08-14T04:00:00Z', simulated: false,
};

const renderApp = (role = 'PRO_USER') => {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', role);
  return render(<App />);
};

/** Lets the forecast chain settle. */
const settle = async () => {
  await act(async () => { await Promise.resolve(); });
  await act(async () => {});
};

/**
 * Waits until the status request has been made AND its answer applied. The negatives below assert an
 * ABSENCE, which is only a verdict once the status has landed: an absent pane two ticks after mount
 * would read the same if the answer simply had not arrived yet.
 */
const statusLanded = async () => {
  await waitFor(() => expect(getAuroraStatus).toHaveBeenCalled());
  await act(async () => { await getAuroraStatus.mock.results[0].value; });
  await settle();
};

/** The pane's props without the function-valued ones (fresh closures each render). */
const dataProps = (props) => Object.fromEntries(
  Object.entries(props).filter(([, v]) => typeof v !== 'function'),
);
const functionKeys = (props) => Object.keys(props).filter((k) => typeof props[k] === 'function').sort();

beforeEach(() => {
  localStorage.clear();
  window.location.hash = '';
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
  MapPaneStub.lastProps = null;
  fetchForecasts.mockReset().mockResolvedValue([]);
  fetchLocations.mockReset().mockResolvedValue(LOCATION_META);
  getDailyBriefing.mockReset().mockResolvedValue(null);
  getAllEvaluationScores.mockReset().mockResolvedValue([]);
  getSettings.mockReset().mockResolvedValue({});
  getReach.mockReset().mockResolvedValue([]);
  getDriveTimes.mockReset().mockResolvedValue({});
  fetchTravelDayRanges.mockReset().mockResolvedValue([]);
  getAuroraStatus.mockReset().mockResolvedValue(null);
  getAuroraAlert.mockReset().mockResolvedValue(null);
  getNlcSighting.mockReset().mockResolvedValue(null);
  getAstroConditions.mockReset().mockResolvedValue(null);
  getAlmanac.mockReset().mockResolvedValue({ entries: [] });
  subscribeToRunNotifications.mockReset().mockReturnValue(() => {});
  getTodaysLight.mockReset().mockResolvedValue(null);
  window.requestIdleCallback = () => 1;
  window.cancelIdleCallback = () => {};
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  delete window.requestIdleCallback;
  delete window.cancelIdleCallback;
});

describe('App - the Map tab without a solar forecast (C3)', () => {
  it('offers the Map pane on a real STRONG alert with no forecast data, opening on the night IN PROGRESS', async () => {
    getAuroraStatus.mockResolvedValue(REAL_STRONG);
    renderApp('PRO_USER');
    await screen.findByTestId('stub-map-pane');
    // In the small hours that night is YESTERDAY's date, not the civil today: a date taken from the
    // calendar (`todayStr`) would be the 14th and name a night the alert is not about.
    expect(MapPaneStub.lastProps.selectedDate).toBe(THE_NIGHT);
    expect(MapPaneStub.lastProps.selectedDate).not.toBe(TODAY);
  });

  it('offers it to an ADMIN too', async () => {
    getAuroraStatus.mockResolvedValue({ ...REAL_STRONG, level: 'MODERATE' });
    renderApp('ADMIN');
    await screen.findByTestId('stub-map-pane');
  });

  it('withholds it with no alert (no status at all)', async () => {
    renderApp('PRO_USER');
    await screen.findByTestId('stub-shell');
    await statusLanded();
    expect(screen.queryByTestId('stub-map-pane')).not.toBeInTheDocument();
  });

  it('withholds it on a quiet alert: inactive, whatever else the status says', async () => {
    getAuroraStatus.mockResolvedValue({ ...REAL_STRONG, active: false, level: 'QUIET' });
    renderApp('PRO_USER');
    await screen.findByTestId('stub-shell');
    await statusLanded();
    expect(screen.queryByTestId('stub-map-pane')).not.toBeInTheDocument();
  });

  it('withholds it on an alert below MODERATE: no scores would be fetched, so no row would hold anything', async () => {
    getAuroraStatus.mockResolvedValue({ ...REAL_STRONG, level: 'MINOR' });
    renderApp('PRO_USER');
    await screen.findByTestId('stub-shell');
    await statusLanded();
    expect(screen.queryByTestId('stub-map-pane')).not.toBeInTheDocument();
  });

  it('withholds it on a SIMULATED alert: the simulation is isolated from every user-facing surface', async () => {
    getAuroraStatus.mockResolvedValue({ ...REAL_STRONG, simulated: true });
    renderApp('ADMIN');
    await screen.findByTestId('stub-shell');
    await statusLanded();
    expect(screen.queryByTestId('stub-map-pane')).not.toBeInTheDocument();
  });

  it('withholds it on a status whose night has ENDED (a stale answer the provider is still holding)', async () => {
    getAuroraStatus.mockResolvedValue({ ...REAL_STRONG, currentNightEndsAt: '2026-08-14T00:30:00Z' });
    renderApp('PRO_USER');
    await screen.findByTestId('stub-shell');
    await statusLanded();
    expect(screen.queryByTestId('stub-map-pane')).not.toBeInTheDocument();
  });

  it('leaves a LITE account unchanged: no pane, even while a real alert is running (it reads /alert, never /status)', async () => {
    getAuroraAlert.mockResolvedValue({
      level: 'STRONG', active: true, triggerType: 'realtime', allOvercast: false, currentNightEndsAt: null,
    });
    renderApp('LITE_USER');
    await screen.findByTestId('stub-shell');
    await waitFor(() => expect(getAuroraAlert).toHaveBeenCalled());
    await act(async () => { await getAuroraAlert.mock.results[0].value; });
    await settle();
    expect(getAuroraStatus).not.toHaveBeenCalled();
    expect(screen.queryByTestId('stub-map-pane')).not.toBeInTheDocument();
  });

  it('hands the pane exactly the same props with forecast data whether or not an alert is running', async () => {
    fetchForecasts.mockResolvedValue(FORECASTS);
    const first = renderApp('PRO_USER');
    await screen.findByTestId('stub-map-pane');
    await statusLanded();
    const quiet = MapPaneStub.lastProps;
    first.unmount();
    localStorage.clear();

    MapPaneStub.lastProps = null;
    getAuroraStatus.mockReset().mockResolvedValue(REAL_STRONG);
    renderApp('PRO_USER');
    await screen.findByTestId('stub-map-pane');
    await statusLanded();
    const alert = MapPaneStub.lastProps;

    // The WHOLE props object (function-valued props named, not compared: each render closes over fresh ones).
    expect(Object.keys(alert).sort()).toEqual(Object.keys(quiet).sort());
    expect(functionKeys(alert)).toEqual(functionKeys(quiet));
    expect(dataProps(alert)).toEqual(dataProps(quiet));
    // And the date is the solar one, not the night.
    expect(alert.selectedDate).not.toBe(THE_NIGHT);
  });

  it('a hides-only forecast keeps its own date (today), not the alert\'s night: forecast data wins the fallback', async () => {
    fetchLocations.mockResolvedValue([HIDE_META]);
    fetchForecasts.mockResolvedValue([hourlyRow(TOMORROW)]);
    getAuroraStatus.mockResolvedValue(REAL_STRONG);
    renderApp('PRO_USER');
    await screen.findByTestId('stub-map-pane');
    await statusLanded();
    // `hasForecastData` is true (hourly rows exist) with no window dates: the pane's date falls through
    // to today. Were the alert's night tested first, it would be THE_NIGHT.
    expect(MapPaneStub.lastProps.dates).toEqual([]);
    expect(MapPaneStub.lastProps.selectedDate).toBe(TODAY);
  });
});
