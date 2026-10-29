import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import {
  act, render, screen, fireEvent, waitFor,
} from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * Health status off the app tree (performance-review-2026-10-plan.md P3c, finding F14). The real
 * `useHealthStatus` and `HealthStatusProvider` run; only the SSE transport is replaced, so the test
 * can push status events. The shell is a render-counting stub: it re-renders exactly when `AppInner`
 * does, so its count is `App`'s count.
 *
 * What is proven: a tick that changes only `checkedAt` re-renders the pill and not the app; a flip
 * to DOWN re-renders the app, shows the banner and passes `contentDisabled` to the shell.
 */

const shell = { renders: 0 };
vi.mock('../components/WindowFirstShell.jsx', () => ({
  default: ({ healthPill, contentDisabled }) => {
    shell.renders += 1;
    return (
      <div data-testid="shell-stub" data-content-disabled={String(Boolean(contentDisabled))}>
        {healthPill}
      </div>
    );
  },
}));

const streams = {};
vi.mock('../utils/createEventSource.js', () => ({
  default: (path, params, handlers) => {
    streams[path] = handlers;
    return () => {};
  },
}));

vi.mock('../api/forecastApi.js', () => ({
  fetchForecasts: vi.fn(),
  fetchForecastsForDate: vi.fn(),
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
vi.mock('../components/ManageView.jsx', () => ({
  default: () => <div data-testid="manage-stub" />,
}));

import {
  fetchForecasts, fetchLocations,
} from '../api/forecastApi.js';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getSettings, getReach, getDriveTimes } from '../api/settingsApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAuroraStatus } from '../api/auroraApi.js';
import { getNlcSighting } from '../api/nlcApi.js';
import { getAstroConditions } from '../api/astroApi.js';
import { getAlmanac } from '../api/almanacApi.js';
import { getTodaysLight } from '../api/lightApi.js';

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

/** Pushes one status event down the health stream, as the backend does every ~30 s. */
const tick = (status = 'UP') => act(async () => {
  streams['/api/status/stream'].status({ status, degraded: [] });
});

describe('App — health status stays off the app tree', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    shell.renders = 0;
    Object.keys(streams).forEach((k) => delete streams[k]);
    fetchForecasts.mockResolvedValue([]);
    fetchLocations.mockResolvedValue([]);
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
    localStorage.setItem('goldenhour_token', 'test-token');
    localStorage.setItem('goldenhour_role', 'ADMIN');
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  });

  afterEach(() => {
    localStorage.clear();
    vi.restoreAllMocks();
  });

  async function mountWithStream() {
    render(<App />);
    await screen.findByTestId('shell-stub');
    // The stream opens after first paint, so wait for the provider to subscribe.
    await waitFor(() => expect(streams['/api/status/stream']).toBeDefined());
  }

  it('a checkedAt-only tick re-renders the pill but not the app', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    try {
      vi.setSystemTime(new Date('2026-08-14T12:00:00Z'));
      await mountWithStream();
      await tick();
      fireEvent.click(await screen.findByTestId('health-indicator'));
      const first = screen.getByTestId('health-checked-at').textContent;
      const rendersBefore = shell.renders;

      vi.setSystemTime(new Date('2026-08-14T12:00:30Z'));
      await tick();

      expect(screen.getByTestId('health-checked-at').textContent).not.toBe(first);
      expect(shell.renders).toBe(rendersBefore);
    } finally {
      vi.useRealTimers();
    }
  });

  it('a flip to DOWN re-renders the app, shows the banner and disables the content', async () => {
    await mountWithStream();
    await tick();
    expect(screen.queryByTestId('backend-down-banner')).toBeNull();
    expect(screen.getByTestId('shell-stub')).toHaveAttribute('data-content-disabled', 'false');
    const rendersBefore = shell.renders;

    await tick('DOWN');

    expect(screen.getByTestId('backend-down-banner')).toBeInTheDocument();
    expect(screen.getByTestId('shell-stub')).toHaveAttribute('data-content-disabled', 'true');
    expect(shell.renders).toBeGreaterThan(rendersBefore);

    await tick('UP');
    expect(screen.queryByTestId('backend-down-banner')).toBeNull();
    expect(screen.getByTestId('shell-stub')).toHaveAttribute('data-content-disabled', 'false');
  });
});
