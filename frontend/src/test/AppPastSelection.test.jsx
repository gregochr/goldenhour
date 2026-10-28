import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent } from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { ukDateStrOffset } from '../utils/mapDates.js';

/**
 * The past-window provenance flag at the app root (outcome-rating-plan.md §2 #2, §3 O3a step 3):
 * `App.selectDate(date, { isPast })` stores `selectedDateIsPast` beside `selectedDateIsNight`, and
 * `resolveMapDate` honours a date before today only with it — exactly as it honours a night only with
 * `isNight`. The shape is `AppRewind.test.jsx`'s: the authenticated app with every API module mocked
 * and the heavy map pane cut down to a stub, here one that prints the date it was handed and exposes
 * the pane's one outbound call, `onSelectDate`, as buttons.
 *
 * What is proven: a flagged past pick holds the Map on that date; the SAME date unflagged (a reload,
 * a handoff, any other route to `selectDate`) falls through to the today-forward pick; the flag is
 * per pick — a later live pick clears it, and a repeat of the past date without it is refused again;
 * and a flagged date that is not in the forecast domain is refused.
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

/** The dates the stub pane's buttons send, filled in below once the clock-relative dates exist (the `mock` prefix is what lets the hoisted factory close over it). */
const mockDates = { yesterday: '', twoAgo: '', tomorrow: '' };
vi.mock('../components/WindowFirstMapPane.jsx', () => ({
  default: ({ selectedDate, onSelectDate, dates }) => (
    <div data-testid="map-pane-stub" data-selected-date={selectedDate ?? ''}>
      <span data-testid="map-pane-dates">{(dates ?? []).join(',')}</span>
      <button type="button" onClick={() => onSelectDate(mockDates.yesterday, { isNight: false, isPast: true })}>
        pick past yesterday
      </button>
      <button type="button" onClick={() => onSelectDate(mockDates.yesterday)}>
        pick yesterday unflagged
      </button>
      <button type="button" onClick={() => onSelectDate(mockDates.twoAgo, { isNight: false, isPast: true })}>
        pick past two days ago
      </button>
      <button type="button" onClick={() => onSelectDate(mockDates.tomorrow, { isNight: false, isPast: false })}>
        pick live tomorrow
      </button>
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

const YESTERDAY = ukDateStrOffset(-1);
const TWO_AGO = ukDateStrOffset(-2);
const TOMORROW = ukDateStrOffset(1);
mockDates.yesterday = YESTERDAY;
mockDates.twoAgo = TWO_AGO;
mockDates.tomorrow = TOMORROW;

const LOCATION_META = [{
  id: 1, name: 'Derwentwater', lat: 54.58, lon: -3.14, enabled: true,
  locationType: ['LANDSCAPE'], tideType: [], solarEventType: ['SUNRISE', 'SUNSET'],
  bortleClass: 3, region: { name: 'Lake District' },
}];
/** Forecast rows for yesterday and tomorrow only — so TWO_AGO is NOT in the forecast domain. */
const FORECASTS = [YESTERDAY, TOMORROW].flatMap((targetDate) => ['SUNRISE', 'SUNSET'].map((targetType) => ({
  locationName: 'Derwentwater', locationLat: '54.58', locationLon: '-3.14',
  targetDate, targetType, forecastRunAt: '2026-01-01T06:00:00', rating: 3,
})));
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

function renderApp() {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', 'ADMIN');
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  return render(<App />);
}

async function openMapTab() {
  const tab = await screen.findByRole('tab', { name: 'Map' });
  await act(async () => { fireEvent.click(tab); });
  return screen.findByTestId('map-pane-stub');
}

const press = async (name) => {
  await act(async () => { fireEvent.click(screen.getByRole('button', { name })); });
};

describe('App — the past-window selection licence (selectedDateIsPast)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchForecasts.mockResolvedValue(FORECASTS);
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
    localStorage.clear();
    vi.restoreAllMocks();
  });

  it('the fixture\'s forecast domain holds yesterday and tomorrow, not two days ago', async () => {
    renderApp();
    await openMapTab();
    expect(screen.getByTestId('map-pane-dates')).toHaveTextContent(`${YESTERDAY},${TOMORROW}`);
  });

  it('a past pick WITH the flag holds the Map on that past date', async () => {
    renderApp();
    const pane = await openMapTab();
    expect(pane).not.toHaveAttribute('data-selected-date', YESTERDAY);

    await press('pick past yesterday');

    expect(pane).toHaveAttribute('data-selected-date', YESTERDAY);
  });

  it('the SAME past date WITHOUT the flag falls through to the today-forward pick', async () => {
    renderApp();
    const pane = await openMapTab();

    await press('pick yesterday unflagged');

    expect(pane).not.toHaveAttribute('data-selected-date', YESTERDAY);
    expect(pane).toHaveAttribute('data-selected-date', TOMORROW);
  });

  it('the flag is per pick: a live pick afterwards clears it, and the past date is refused again unflagged', async () => {
    renderApp();
    const pane = await openMapTab();

    await press('pick past yesterday');
    expect(pane).toHaveAttribute('data-selected-date', YESTERDAY);

    await press('pick live tomorrow');
    expect(pane).toHaveAttribute('data-selected-date', TOMORROW);

    // A stale pick of the same past date, this time without the flag, does not hold the map.
    await press('pick yesterday unflagged');
    expect(pane).toHaveAttribute('data-selected-date', TOMORROW);
  });

  it('refuses a flagged past date that is not in the forecast domain', async () => {
    renderApp();
    const pane = await openMapTab();

    await press('pick past two days ago');

    expect(pane).not.toHaveAttribute('data-selected-date', TWO_AGO);
    expect(pane).toHaveAttribute('data-selected-date', TOMORROW);
  });
});
