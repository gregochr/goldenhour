import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { ukDateStr, ukDateStrOffset } from '../utils/mapDates.js';
import { setRewind } from '../utils/rewind.js';

/**
 * The Map's date domain ends at T+3 (whole-day-horizon-plan.md §5), the last day At a Glance shows.
 * `App.allDates` is `colourForecastDates` bounded above at `lastPlanDateStr(today)`; the pane stub here
 * prints the `dates` it is handed. The shape is `AppPastSelection.test.jsx`'s.
 *
 * What is proven: forecast dates after today + 3 are dropped; today + 3 and every earlier date, past
 * dates included, are kept; and the same holds under a rewound clock (today is `appNow()`, never the
 * wall clock) — including when the rewound day makes the cut fall on a different date.
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
vi.mock('../components/WindowFirstMapPane.jsx', () => ({
  default: ({ dates }) => (
    <div data-testid="map-pane-stub">
      <span data-testid="map-pane-dates">{(dates ?? []).join(',')}</span>
    </div>
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

async function mapDates() {
  const tab = await screen.findByRole('tab', { name: 'Map' });
  await act(async () => { fireEvent.click(tab); });
  await screen.findByTestId('map-pane-stub');
  return screen.getByTestId('map-pane-dates').textContent.split(',').filter(Boolean);
}

describe('App — the Map ends at T+3', () => {
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

  it('drops T+4 and T+5, keeps T+3 and every earlier date, past included', async () => {
    const span = servedSpan();
    fetchForecasts.mockResolvedValue(forecastsFor(span));

    renderApp();

    expect(await mapDates()).toEqual(span.slice(0, 6));
  });

  it('keeps a T+3 that is the last served date (nothing to drop)', async () => {
    const span = servedSpan().slice(0, 6);
    fetchForecasts.mockResolvedValue(forecastsFor(span));

    renderApp();

    expect(await mapDates()).toEqual(span);
  });

  it('keeps only the past when every served date is after T+3 (the domain is empty above, not past)', async () => {
    const span = [ukDateStrOffset(-1), ukDateStrOffset(4), ukDateStrOffset(5)];
    fetchForecasts.mockResolvedValue(forecastsFor(span));

    renderApp();

    expect(await mapDates()).toEqual([span[0]]);
  });

  it('reads today from the app clock: a rewound clock moves the cut with it', async () => {
    // A fixed instant far from the real today, so the test cannot pass by reading the wall clock.
    const rewoundTo = '2026-03-10T12:00:00Z';
    act(() => setRewind(rewoundTo));
    const today = ukDateStr();
    expect(today).toBe('2026-03-10');
    const span = [-2, -1, 0, 1, 2, 3, 4, 5].map((n) => ukDateStrOffset(n));
    fetchForecasts.mockResolvedValue(forecastsFor(span));

    renderApp();

    // T-2..T+3 of the rewound day; T+4 and T+5 (2026-03-14, 2026-03-15) are gone.
    expect(await mapDates()).toEqual([
      '2026-03-08', '2026-03-09', '2026-03-10', '2026-03-11', '2026-03-12', '2026-03-13',
    ]);
  });
});
