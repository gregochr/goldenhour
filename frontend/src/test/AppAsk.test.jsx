import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { ukDateStrOffset } from '../utils/mapDates.js';
import { setRewind } from '../utils/rewind.js';
import { installViewport, resetViewport } from './askViewport.js';
import { settings } from './askFixtures.js';

/**
 * Where Ask PhotoCast is mounted: `App.jsx` puts `AskProvider` inside the briefing provider and
 * around the shell, and does NOT mount it under an admin's rewind (F1b). The rule is the app's, so
 * it is tested at the app: a rewound page makes no `GET /api/user/settings/ask` (the axios
 * interceptor would stamp the rewound clock on it) and draws no Ask surface.
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
vi.mock('../api/askApi.js', async (importOriginal) => ({
  ...(await importOriginal()),
  getReady: vi.fn(),
  ask: vi.fn(),
  getAskSettings: vi.fn(),
}));
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
import { getAskSettings, getReady } from '../api/askApi.js';

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

function renderApp(role = 'ADMIN') {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', role);
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  return render(<App />);
}

describe('App — where Ask PhotoCast is mounted', () => {
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
    getAskSettings.mockResolvedValue(settings());
    getReady.mockResolvedValue({ scope: 'all', questions: [] });
    installViewport(390);
  });

  afterEach(() => {
    act(() => setRewind(null));
    localStorage.clear();
    vi.restoreAllMocks();
    resetViewport();
  });

  it('live, the provider is mounted: the settings are read once and the bar appears', async () => {
    renderApp('PRO_USER');
    expect(await screen.findByTestId('ask-bar')).toBeEnabled();
    expect(getAskSettings).toHaveBeenCalledTimes(1);
  });

  it('rewound, the settings read NEVER fires and no Ask surface exists, on the phone', async () => {
    act(() => setRewind(REWIND_TO));
    renderApp('ADMIN');
    await screen.findByRole('tab', { name: 'Map' });
    await act(async () => {});
    expect(getAskSettings).not.toHaveBeenCalled();
    expect(getReady).not.toHaveBeenCalled();
    expect(screen.queryByTestId('ask-bar')).toBeNull();
  });

  it('rewound, no Ask surface exists on the tablet either', async () => {
    installViewport(800);
    act(() => setRewind(REWIND_TO));
    renderApp('ADMIN');
    await screen.findByRole('tab', { name: 'Map' });
    await act(async () => {});
    expect(getAskSettings).not.toHaveBeenCalled();
    expect(screen.queryByTestId('ask-field')).toBeNull();
  });

  it('entering a rewind takes Ask away, and returning to live brings it back with a fresh read', async () => {
    renderApp('ADMIN');
    expect(await screen.findByTestId('ask-bar')).toBeInTheDocument();
    expect(getAskSettings).toHaveBeenCalledTimes(1);

    act(() => setRewind(REWIND_TO));
    await screen.findByTestId('rewind-pill');
    // Settled first: a provider that WAS mounted would only have started its read by now.
    await act(async () => {});
    expect(screen.queryByTestId('ask-bar')).toBeNull();
    expect(getAskSettings).toHaveBeenCalledTimes(1);

    await act(async () => { fireEvent.click(screen.getByTestId('rewind-pill-exit')); });
    expect(await screen.findByTestId('ask-bar')).toBeInTheDocument();
    await waitFor(() => expect(getAskSettings).toHaveBeenCalledTimes(2));
  });

  it('with the sheet open, the footer and the banners are as dead as the shell: the whole app container is inert', async () => {
    renderApp('PRO_USER');
    fireEvent.click(await screen.findByTestId('ask-bar'));
    const sheet = await screen.findByRole('dialog', { name: 'Ask PhotoCast' });

    const footerLink = screen.getByRole('link', { name: 'Instagram' });
    expect(footerLink.closest('[inert]')).not.toBeNull();
    expect(sheet.closest('[inert]')).toBeNull();

    fireEvent.click(screen.getByTestId('bottom-sheet-close'));
    expect(footerLink.closest('[inert]')).toBeNull();
  });
});
