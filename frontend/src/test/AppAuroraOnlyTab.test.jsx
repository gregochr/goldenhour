/**
 * `App.jsx` — the Map tab on an aurora alert alone (overlay convergence C3,
 * `docs/engineering/overlay-convergence-plan.md` §3 C3 and §5 D4).
 *
 * Until C3 `App` withheld the whole Map pane whenever `GET /api/forecast` returned nothing for a
 * visible location, so the aurora banner (shown on every tab) needed the overlay to reach a map at
 * all. Now a REAL MODERATE/STRONG alert offers the tab too (`utils/auroraTab.js`), opening on the
 * night in progress. What must NOT move: a reader with forecast data (the pane's props are the same
 * whether or not an alert is running), a LITE reader, a quiet alert and a simulated one.
 *
 * Built in the shape of `AppOpenMapTabFromPlan.test.jsx`: the shell and the pane are stubs that
 * record the props `App` hands them, so the decision is read straight off the prop.
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import App from '../App.jsx';
import { ukDateStr, ukDateStrOffset } from '../utils/mapDates.js';

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
  getAuroraStatus: vi.fn(),
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
import { getAuroraStatus } from '../api/auroraApi.js';
import { getNlcSighting } from '../api/nlcApi.js';
import { getAstroConditions } from '../api/astroApi.js';
import { getAlmanac } from '../api/almanacApi.js';
import { subscribeToRunNotifications } from '../api/runProgressApi.js';
import { getTodaysLight } from '../api/lightApi.js';

const TOMORROW = ukDateStrOffset(1);
const TONIGHT = ukDateStr();

const LOCATION_META = [
  {
    id: 7, name: 'Bamburgh', lat: 55.608, lon: -1.719, enabled: true,
    locationType: ['SEASCAPE'], tideType: ['HIGH'], solarEventType: ['SUNRISE', 'SUNSET'],
    bortleClass: 4, region: { name: 'Northumberland' },
  },
];
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

const REAL_STRONG = { active: true, level: 'STRONG', currentNightDate: TONIGHT, simulated: false };

const renderApp = (role = 'PRO_USER') => {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', role);
  return render(<App />);
};

/** Lets the forecast chain and the aurora status settle. */
const settle = async () => {
  await act(async () => { await Promise.resolve(); });
  await act(async () => {});
};

beforeEach(() => {
  localStorage.clear();
  window.location.hash = '';
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
  getNlcSighting.mockReset().mockResolvedValue(null);
  getAstroConditions.mockReset().mockResolvedValue(null);
  getAlmanac.mockReset().mockResolvedValue({ entries: [] });
  subscribeToRunNotifications.mockReset().mockReturnValue(() => {});
  getTodaysLight.mockReset().mockResolvedValue(null);
  window.requestIdleCallback = () => 1;
  window.cancelIdleCallback = () => {};
});

afterEach(() => {
  vi.restoreAllMocks();
  delete window.requestIdleCallback;
  delete window.cancelIdleCallback;
});

describe('App — the Map tab without a solar forecast (C3)', () => {
  it('offers the Map pane on a real STRONG alert with no forecast data, opening on the night in progress', async () => {
    getAuroraStatus.mockResolvedValue(REAL_STRONG);
    renderApp('PRO_USER');
    await screen.findByTestId('stub-map-pane');
    expect(MapPaneStub.lastProps.selectedDate).toBe(TONIGHT);
    // No solar dates exist: the pane's window domain stays empty, so the only row it can draw is the night's.
    expect(MapPaneStub.lastProps.dates).toEqual([]);
  });

  it('offers it to an ADMIN too', async () => {
    getAuroraStatus.mockResolvedValue({ ...REAL_STRONG, level: 'MODERATE' });
    renderApp('ADMIN');
    await screen.findByTestId('stub-map-pane');
  });

  it('withholds it with no alert (no status at all)', async () => {
    renderApp('PRO_USER');
    await screen.findByTestId('stub-shell');
    await settle();
    expect(screen.queryByTestId('stub-map-pane')).not.toBeInTheDocument();
  });

  it('withholds it on a quiet alert: inactive, whatever else the status says', async () => {
    getAuroraStatus.mockResolvedValue({ active: false, level: 'QUIET', currentNightDate: TONIGHT });
    renderApp('PRO_USER');
    await screen.findByTestId('stub-shell');
    await settle();
    expect(screen.queryByTestId('stub-map-pane')).not.toBeInTheDocument();
  });

  it('withholds it on an alert below MODERATE: no scores would be fetched, so no row would hold anything', async () => {
    getAuroraStatus.mockResolvedValue({ ...REAL_STRONG, level: 'MINOR' });
    renderApp('PRO_USER');
    await screen.findByTestId('stub-shell');
    await settle();
    expect(screen.queryByTestId('stub-map-pane')).not.toBeInTheDocument();
  });

  it('withholds it on a SIMULATED alert: the simulation is isolated from every user-facing surface', async () => {
    getAuroraStatus.mockResolvedValue({ ...REAL_STRONG, simulated: true });
    renderApp('ADMIN');
    await screen.findByTestId('stub-shell');
    await settle();
    expect(screen.queryByTestId('stub-map-pane')).not.toBeInTheDocument();
  });

  it('leaves a LITE account unchanged: no pane, even if a status somehow arrived', async () => {
    getAuroraStatus.mockResolvedValue(REAL_STRONG);
    renderApp('LITE_USER');
    await screen.findByTestId('stub-shell');
    await settle();
    expect(screen.queryByTestId('stub-map-pane')).not.toBeInTheDocument();
  });

  it('hands the pane the same window props with forecast data whether or not an alert is running', async () => {
    fetchForecasts.mockResolvedValue(FORECASTS);
    const first = renderApp('PRO_USER');
    await screen.findByTestId('stub-map-pane');
    await settle();
    const quiet = MapPaneStub.lastProps;
    first.unmount();
    localStorage.clear();

    MapPaneStub.lastProps = null;
    getAuroraStatus.mockResolvedValue(REAL_STRONG);
    renderApp('PRO_USER');
    await screen.findByTestId('stub-map-pane');
    await settle();
    const alert = MapPaneStub.lastProps;

    // A solar window's date, the domain and the auto event the pane receives do not move.
    expect(quiet.selectedDate).toBeTruthy();
    expect(alert.selectedDate).toBe(quiet.selectedDate);
    expect(alert.dates).toEqual(quiet.dates);
    expect(alert.autoEventType).toBe(quiet.autoEventType);
  });
});
