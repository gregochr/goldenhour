import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { ukDateStrOffset } from '../utils/mapDates.js';
import { setRewind } from '../utils/rewind.js';

/**
 * The shell's `forecastDates` prop (what the Coming up and day-tile Map doors withhold on) is the SAME
 * bounded list the Map pane gets (whole-day-horizon-plan.md §5), not the unbounded forecast dates. The
 * shell is stubbed to print the prop; the shape is `AppMapHorizon.test.jsx`'s. Handed the unbounded
 * dates, the shell would carry T+4 and T+5 and a door to them would open onto no row.
 */

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
  saveMapColourPreferences: vi.fn(),
  markComingUpSeen: vi.fn(),
}));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../api/auroraApi.js', () => ({
  getAuroraStatus: vi.fn(),
  getAuroraAlert: vi.fn().mockResolvedValue(null),
}));
vi.mock('../api/nlcApi.js', () => ({ getNlcSighting: vi.fn() }));
vi.mock('../api/astroApi.js', () => ({ getAstroConditions: vi.fn() }));
vi.mock('../api/almanacApi.js', () => ({ getAlmanac: vi.fn(), ALMANAC_DAYS: 90 }));
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
vi.mock('../components/MapView.jsx', () => ({
  default: () => <div data-testid="overlay-map-stub" />,
}));
vi.mock('../hooks/useHealthStatus.js', () => ({
  useHealthStatus: () => ({ status: 'UP', degraded: false, services: [], checkedAt: null }),
}));
vi.mock('../components/ManageView.jsx', () => ({
  default: () => <div data-testid="manage-stub" />,
}));
vi.mock('../components/WindowFirstShell.jsx', () => ({
  default: ({ forecastDates }) => (
    <span data-testid="shell-forecast-dates">
      {forecastDates === null ? 'null' : forecastDates.join(',')}
    </span>
  ),
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
import { getTodaysLight } from '../api/lightApi.js';

const LOCATION_META = [{
  id: 1, name: 'Derwentwater', lat: 54.58, lon: -3.14, enabled: true,
  locationType: ['LANDSCAPE'], tideType: [], solarEventType: ['SUNRISE', 'SUNSET'],
  bortleClass: 3, region: { name: 'Lake District' },
}];
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

/** Sunrise and sunset rows for each date, as `GET /api/forecast` serves them. */
const forecastsFor = (dates) => dates.flatMap((targetDate) => ['SUNRISE', 'SUNSET'].map((targetType) => ({
  locationName: 'Derwentwater', locationLat: '54.58', locationLon: '-3.14',
  targetDate, targetType, forecastRunAt: '2026-01-01T06:00:00', rating: 3,
})));

/** T-2 .. T+5 relative to the app clock: the served span, past days included. */
const servedSpan = () => [-2, -1, 0, 1, 2, 3, 4, 5].map((n) => ukDateStrOffset(n));

function renderApp() {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', 'ADMIN');
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  return render(<App />);
}

async function shellDates() {
  const el = await screen.findByTestId('shell-forecast-dates');
  await act(async () => {});
  return el.textContent.split(',').filter(Boolean);
}

describe('App — the shell\'s forecastDates is the bounded list', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchLocations.mockResolvedValue(LOCATION_META);
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
    getTodaysLight.mockResolvedValue(null);
  });

  afterEach(() => {
    act(() => setRewind(null));
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('hands the shell the BOUNDED list: no T+4 or T+5 reaches the Coming up and day-tile Map doors', async () => {
    const span = servedSpan();
    fetchForecasts.mockResolvedValue(forecastsFor(span));

    renderApp();

    // The unbounded `colourForecastDates` would end at T+5; the doors withhold on membership.
    expect(await shellDates()).toEqual(span.slice(0, 6));
  });

  it('is null (not known yet) rather than an empty list while the forecast is outstanding', async () => {
    fetchForecasts.mockReturnValue(new Promise(() => {}));

    renderApp();

    expect(await screen.findByTestId('shell-forecast-dates')).toHaveTextContent('null');
  });
});
