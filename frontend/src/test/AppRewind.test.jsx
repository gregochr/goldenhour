import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { ukDateStr, ukDateStrOffset } from '../utils/mapDates.js';
import { getRewind, setRewind } from '../utils/rewind.js';

/**
 * The rewind at the app root (`RewindGate` in `App.jsx`): the pill, who sees it, what a change
 * does to the mounted app, and the window the Map tab is handed.
 *
 * Mocks follow `AppSettingsRoutes.test.jsx`: every API module the authenticated app fetches from,
 * the briefing context (its real provider is exercised by its own suites) and the heavy map pane,
 * cut down to a stub that prints the two props this file reads.
 */

vi.mock('../api/forecastApi.js', () => ({
  fetchForecasts: vi.fn(),
  fetchLocations: vi.fn(),
  fetchAllOutcomes: vi.fn(),
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
  saveMapColourPreferences: vi.fn(),
  markComingUpSeen: vi.fn(),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({ getAuroraStatus: vi.fn() }));
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
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));
vi.mock('../components/MapView.jsx', () => ({
  default: () => <div data-testid="overlay-map-stub" />,
}));
// The health stream and the Operations pane are the admin-only subtrees this file never reads.
vi.mock('../hooks/useHealthStatus.js', () => ({
  useHealthStatus: () => ({ status: 'UP', degraded: false, services: [], checkedAt: null }),
}));
vi.mock('../components/ManageView.jsx', () => ({
  default: () => <div data-testid="manage-stub" />,
}));
/** The Map pane, printing the two props the rewind seeds: the handoff and the selected date. */
vi.mock('../components/WindowFirstMapPane.jsx', () => ({
  default: ({ handoff, selectedDate }) => (
    <div data-testid="map-pane-stub" data-selected-date={selectedDate ?? ''}>
      <span data-testid="map-pane-handoff">{handoff ? JSON.stringify(handoff) : 'none'}</span>
    </div>
  ),
}));

import { fetchForecasts, fetchLocations, fetchAllOutcomes } from '../api/forecastApi.js';
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
const LOCATION_META = [{
  id: 1, name: 'Derwentwater', lat: 54.58, lon: -3.14, enabled: true,
  locationType: ['LANDSCAPE'], tideType: [], solarEventType: ['SUNRISE', 'SUNSET'],
  bortleClass: 3, region: { name: 'Lake District' },
}];
const FORECASTS = ['SUNRISE', 'SUNSET'].map((targetType) => ({
  locationName: 'Derwentwater', locationLat: '54.58', locationLon: '-3.14',
  targetDate: TOMORROW, targetType, forecastRunAt: '2026-01-01T06:00:00', rating: 3,
}));
const SETTINGS = {
  role: 'ADMIN', homePostcode: null, homePlaceName: null, homeLatitude: null,
  homeLongitude: null, driveTimesCalculatedAt: null, mapColourScale: 'temp',
  comingUpLastSeenDate: null,
};
const LENS = {
  tier: { id: '45', limitMinutes: 45, label: '45 min' }, tierId: '45',
  defaultTier: { id: '45', limitMinutes: 45, label: '45 min' }, defaultTierId: '45',
  weekend: false, overridden: false, locked: false, selectTier: vi.fn(), resetToDefault: vi.fn(),
};
const ctx = () => ({
  briefing: { generatedAt: '2026-08-14T12:00:00', days: [] },
  loading: false, windowCards: [], paneItems: [], upcomingEvents: [], travelDayDates: new Set(),
  reachById: new Map(), effectiveReachById: new Map(), isPro: true, isLiteUser: false,
  evaluationScores: new Map(), scoresLoaded: true, scoreRows: [], scoreIndex: new Map(),
  heatStripCards: [], heatSpots: [], heatPointSets: new Map(), regionSeries: new Map(),
  todayStr: '2026-08-14', tomorrowStr: '2026-08-15', reachLens: LENS,
  ratingLens: { floor: { id: 'any', min: null, label: 'Any rating' }, floorId: 'any', minRating: null, selectFloor: vi.fn() },
  homePlace: null, origin: null, setOrigin: vi.fn(), regions: [],
  comingUpLastSeenDate: '2026-08-14', setComingUpLastSeenAt: vi.fn(),
});

const REWIND_TO = '2026-10-04T04:58:00Z';
/**
 * YESTERDAY on the UK calendar, and noon that day as the rewind instant: a date `resolveMapDate`'s
 * never-past rule refuses on the live clock and admits only because `todayStr` is rewound. (A
 * rewind to any date on or after the real today would pass that rule trivially and prove nothing.)
 */
const YESTERDAY = ukDateStrOffset(-1);
const YESTERDAY_NOON = `${YESTERDAY}T12:00:00Z`;
const YESTERDAY_FORECASTS = ['SUNRISE', 'SUNSET'].map((targetType) => ({
  locationName: 'Derwentwater', locationLat: '54.58', locationLon: '-3.14',
  targetDate: YESTERDAY, targetType, forecastRunAt: '2026-01-01T06:00:00', rating: 3,
}));

function renderApp(role) {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', role);
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  return render(<App />);
}

async function openMapTab() {
  const tab = await screen.findByRole('tab', { name: 'Map' });
  await act(async () => { fireEvent.click(tab); });
  return screen.findByTestId('map-pane-stub');
}

describe('App — the admin rewind', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchForecasts.mockResolvedValue(FORECASTS);
    fetchLocations.mockResolvedValue(LOCATION_META);
    fetchAllOutcomes.mockResolvedValue([]);
    getDailyBriefing.mockResolvedValue(null);
    getAllEvaluationScores.mockResolvedValue([]);
    getSettings.mockResolvedValue(SETTINGS);
    getReach.mockResolvedValue({ reach: [] });
    getDriveTimes.mockResolvedValue([]);
    fetchTravelDayRanges.mockResolvedValue([]);
    getAuroraStatus.mockResolvedValue(null);
    getNlcSighting.mockResolvedValue(null);
    getAstroConditions.mockResolvedValue(null);
    getAlmanac.mockResolvedValue({ entries: [], bands: null });
    subscribeToRunNotifications.mockReturnValue(() => {});
    getTodaysLight.mockResolvedValue(null);
  });

  afterEach(() => {
    act(() => setRewind(null));
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('live, there is no pill', async () => {
    renderApp('ADMIN');
    await screen.findByRole('tab', { name: 'Map' });
    expect(screen.queryByTestId('rewind-pill')).toBeNull();
  });

  it('rewound, an admin sees the pill naming the moment, and Back to live clears the rewind and remounts the app', async () => {
    renderApp('ADMIN');
    await screen.findByRole('tab', { name: 'Map' });
    const forecastFetchesBefore = fetchForecasts.mock.calls.length;

    act(() => setRewind(REWIND_TO, { date: TOMORROW, eventType: 'SUNRISE' }));

    const pill = await screen.findByTestId('rewind-pill');
    expect(pill).toHaveTextContent('Rewound to Sun 4 Oct, 05:58 UK');
    // In the banner block above the page, never floating over the map frame.
    expect(pill.className).not.toMatch(/\bfixed\b/);
    expect(pill.parentElement).toContainElement(screen.getByTestId('rewind-pill'));
    // The remount is the mechanism: a fresh `useForecasts` fetches again (and so does everything
    // else that reads the clock once at mount).
    await waitFor(() => expect(fetchForecasts.mock.calls.length).toBeGreaterThan(forecastFetchesBefore));

    await act(async () => { fireEvent.click(screen.getByTestId('rewind-pill-exit')); });

    expect(getRewind()).toBeNull();
    await waitFor(() => expect(screen.queryByTestId('rewind-pill')).toBeNull());
  });

  it('the pill stays above the Operations tab — the tab the rewind is set from, and the way back from it', async () => {
    act(() => setRewind(REWIND_TO));
    renderApp('ADMIN');
    const operations = await screen.findByRole('tab', { name: 'Operations' });
    await act(async () => { fireEvent.click(operations); });

    await screen.findByTestId('manage-stub');
    expect(screen.getByTestId('rewind-pill')).toHaveTextContent('Rewound to Sun 4 Oct, 05:58 UK');
    expect(screen.getByTestId('rewind-pill-exit')).toBeInTheDocument();
  });

  it('the rewind\'s window — yesterday\'s sunrise — is handed to the Map tab as an explicit, lens-free selection on a date the live clock would refuse', async () => {
    // Sanity: on the live clock yesterday IS past, so the never-past rule would drop it.
    expect(YESTERDAY < ukDateStr(new Date())).toBe(true);
    fetchForecasts.mockResolvedValue(YESTERDAY_FORECASTS);
    act(() => setRewind(YESTERDAY_NOON, { date: YESTERDAY, eventType: 'SUNRISE' }));
    renderApp('ADMIN');

    const pane = await openMapTab();
    const handoff = JSON.parse(screen.getByTestId('map-pane-handoff').textContent);
    expect(handoff).toEqual({
      source: 'map', eventType: 'SUNRISE', date: YESTERDAY, locationName: null, nonce: -1,
    });
    expect(handoff).not.toHaveProperty('minRating');
    expect(handoff).not.toHaveProperty('region');
    expect(pane).toHaveAttribute('data-selected-date', YESTERDAY);
  });

  it('a rewind ends with the authenticated tree — unmounting the app clears it', async () => {
    act(() => setRewind(REWIND_TO));
    const { unmount } = renderApp('ADMIN');
    await screen.findByTestId('rewind-pill');

    unmount();

    expect(getRewind()).toBeNull();
  });

  it('a rewind with no focus hands the Map tab nothing', async () => {
    act(() => setRewind(REWIND_TO));
    renderApp('ADMIN');

    await openMapTab();
    expect(screen.getByTestId('map-pane-handoff')).toHaveTextContent('none');
  });

  it('a non-admin never sees the pill, whatever the store holds', async () => {
    act(() => setRewind(REWIND_TO));
    renderApp('PRO_USER');
    await screen.findByRole('tab', { name: 'Map' });
    expect(screen.queryByTestId('rewind-pill')).toBeNull();
  });
});
