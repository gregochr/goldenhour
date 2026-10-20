/**
 * The aurora banner's door (overlay convergence C4, `docs/engineering/overlay-convergence-plan.md`
 * section 3 C4 and section 4 #4, #7): the banner is mounted above `<main>` on every tab, and its
 * "View on map" now takes the structured `source:'plan'` door instead of opening the retired
 * overlay. Driven through the real `App` and the real banner; the shell and the Map pane are stubs
 * so the payload `App` builds (`mapTabHandoff`, `tabRequest`) can be read off them.
 *
 * <ul>
 *   <li>a REAL alert, pressed off the Map tab: a `plan` door naming the night, carrying the Plan's
 *       live lens, with a `map` tab request - and no overlay anywhere;</li>
 *   <li>the same press ON the Map tab: a `map`-sourced night, no lens, no tab request (section 6);</li>
 *   <li>a SIMULATED alert, which has no map: an admin's press asks for Operations, a non-admin's
 *       banner has no door at all - never a dead press, never the Map tab;</li>
 *   <li>a real alert with nothing to map (no forecast, a stale night): the banner has no door.</li>
 * </ul>
 */
import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import App from '../App.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { ukDateStrOffset } from '../utils/mapDates.js';

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
  getAuroraLocations: vi.fn(() => Promise.resolve([])),
  getAuroraForecastResults: vi.fn(() => Promise.resolve([])),
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
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../components/AuroraFieldThumb.jsx', () => ({
  default: () => <div data-testid="aurora-field-stub" />,
}));

/** The shell, stubbed to the props under test plus a button that reports "the Map tab is in force". */
const ShellStub = { lastProps: null };
vi.mock('../components/WindowFirstShell.jsx', () => ({
  default: (props) => {
    ShellStub.lastProps = props;
    return (
      <div data-testid="stub-shell">
        <button type="button" data-testid="on-map-tab" onClick={() => props.onTabChange?.('map')}>
          map tab in force
        </button>
        {props.mapPane}
      </div>
    );
  },
}));
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

const TODAY = ukDateStrOffset(0);
const TOMORROW = ukDateStrOffset(1);

const LOCATION_META = [
  {
    id: 7, name: 'Bamburgh', lat: 55.608, lon: -1.719, enabled: true,
    locationType: ['SEASCAPE'], tideType: ['HIGH'], solarEventType: ['SUNRISE', 'SUNSET'],
    bortleClass: 4, region: { name: 'Northumberland' },
  },
];
const forecastRow = (targetType) => ({
  locationName: 'Bamburgh', locationLat: '55.608', locationLon: '-1.719', targetDate: TOMORROW,
  targetType, forecastRunAt: '2026-01-01T06:00:00', rating: 3,
});

/** A live MODERATE alert whose night is `night`. */
const alert = (extra = {}) => ({
  level: 'MODERATE', kpIndex: 6, active: true, simulated: false, currentNightDate: TODAY, ...extra,
});

/** The Plan's live lens, as the banner reads it from the briefing context. */
const LENS_CONTEXT = {
  heatSpots: [],
  reachById: new Map(),
  effectiveReachById: new Map(),
  origin: null,
  ratingLens: { minRating: 4 },
  reachLens: { tier: { limitMinutes: 90 } },
};

const renderApp = (role = 'PRO_USER') => {
  localStorage.setItem('goldenhour_token', 'test-token');
  localStorage.setItem('goldenhour_role', role);
  render(<App />);
};

/** Presses the banner (its whole body is the activation surface). */
const pressBanner = async () => {
  const banner = await screen.findByTestId('aurora-banner');
  await act(async () => { fireEvent.click(banner); });
};

beforeEach(() => {
  localStorage.clear();
  window.location.hash = '';
  ShellStub.lastProps = null;
  MapPaneStub.lastProps = null;
  fetchForecasts.mockReset().mockResolvedValue([forecastRow('SUNRISE'), forecastRow('SUNSET')]);
  fetchLocations.mockReset().mockResolvedValue(LOCATION_META);
  getDailyBriefing.mockReset().mockResolvedValue(null);
  getAllEvaluationScores.mockReset().mockResolvedValue([]);
  getSettings.mockReset().mockResolvedValue({});
  getReach.mockReset().mockResolvedValue([]);
  getDriveTimes.mockReset().mockResolvedValue({});
  fetchTravelDayRanges.mockReset().mockResolvedValue([]);
  getAuroraStatus.mockReset().mockResolvedValue(alert());
  getNlcSighting.mockReset().mockResolvedValue(null);
  getAstroConditions.mockReset().mockResolvedValue(null);
  getAlmanac.mockReset().mockResolvedValue({ entries: [] });
  subscribeToRunNotifications.mockReset().mockReturnValue(() => {});
  getTodaysLight.mockReset().mockResolvedValue(null);
  window.requestIdleCallback = () => 1;
  window.cancelIdleCallback = () => {};
  const real = briefingContext.useWindowFirstBriefing;
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockImplementation(() => ({
    ...real(), ...LENS_CONTEXT,
  }));
});

afterEach(() => {
  vi.restoreAllMocks();
  delete window.requestIdleCallback;
  delete window.cancelIdleCallback;
});

describe('App — the aurora banner takes the structured Map door (overlay convergence C4)', () => {
  it('a real alert pressed off the Map tab: a plan door naming the night, with the Plan lens, and a map tab request', async () => {
    renderApp();
    const requestBefore = ShellStub.lastProps?.tabRequest ?? null;

    await pressBanner();

    await waitFor(() => expect(MapPaneStub.lastProps.handoff?.night).toBeTruthy());
    expect(MapPaneStub.lastProps.handoff).toMatchObject({
      source: 'plan',
      eventType: 'AURORA',
      date: TODAY,
      night: { kind: 'aurora', date: TODAY },
      title: 'Aurora tonight',
      minRating: 4,
      limitMinutes: 90,
    });
    expect(ShellStub.lastProps.tabRequest).toMatchObject({ id: 'map' });
    expect(ShellStub.lastProps.tabRequest).not.toEqual(requestBefore);
    // No overlay of any kind, and the Plan tab was handed no date.
    expect(screen.queryByRole('dialog', { name: 'Aurora tonight' })).toBeNull();
    expect(screen.queryByTestId('map-overlay-close')).toBeNull();
    expect(MapPaneStub.lastProps.handoff).not.toHaveProperty('focusNames');
  });

  it('names the running night, not the civil date, when the night began yesterday', async () => {
    const yesterday = ukDateStrOffset(-1);
    getAuroraStatus.mockResolvedValue(alert({ currentNightDate: yesterday }));
    renderApp();

    await pressBanner();

    await waitFor(() => expect(MapPaneStub.lastProps.handoff?.night).toBeTruthy());
    expect(MapPaneStub.lastProps.handoff.night).toEqual({ kind: 'aurora', date: yesterday });
    // (`App.resolveMapDate` only honours a night that is among the forecast's dates; one that is not
    // is kept local by `MapView`'s night-follow, which `MapViewPlanHandoff.test.jsx` pins.)
  });

  it('⚠️ pressed ON the Map tab it only selects the night: source map, no lens, no tab request', async () => {
    renderApp();
    await screen.findByTestId('aurora-banner');
    await act(async () => { fireEvent.click(screen.getByTestId('on-map-tab')); });
    const requestBefore = ShellStub.lastProps.tabRequest;

    await pressBanner();

    await waitFor(() => expect(MapPaneStub.lastProps.handoff?.night).toBeTruthy());
    const { handoff } = MapPaneStub.lastProps;
    expect(handoff).toMatchObject({ source: 'map', eventType: 'AURORA', night: { kind: 'aurora', date: TODAY } });
    expect(handoff).not.toHaveProperty('minRating');
    expect(handoff).not.toHaveProperty('limitMinutes');
    expect(handoff).not.toHaveProperty('region');
    expect(handoff).not.toHaveProperty('title');
    expect(ShellStub.lastProps.tabRequest).toEqual(requestBefore);
  });

  it('a SIMULATED alert pressed by an admin asks for Operations and never for the Map tab', async () => {
    getAuroraStatus.mockResolvedValue(alert({ simulated: true }));
    renderApp('ADMIN');

    await pressBanner();

    await waitFor(() => expect(ShellStub.lastProps.tabRequest).toMatchObject({ id: 'operations' }));
    expect(MapPaneStub.lastProps?.handoff ?? null).toBeNull();
    expect(window.location.hash).toBe('');
  });

  it('a SIMULATED alert for a non-admin has no door: no call to action, no tab request, no handoff', async () => {
    getAuroraStatus.mockResolvedValue(alert({ simulated: true }));
    renderApp('PRO_USER');
    const banner = await screen.findByTestId('aurora-banner');
    expect(banner).toHaveAttribute('data-actionable', 'false');
    expect(screen.getByTestId('aurora-banner-cta').textContent).toMatch(/Test/);

    await act(async () => { fireEvent.click(banner); });

    expect(ShellStub.lastProps.tabRequest ?? null).toBeNull();
    expect(MapPaneStub.lastProps?.handoff ?? null).toBeNull();
  });

  it('a real alert with NOTHING to map (no forecast, its night over) has no door', async () => {
    fetchForecasts.mockResolvedValue([]);
    getAuroraStatus.mockResolvedValue(alert({ currentNightEndsAt: '2020-01-01T05:00:00Z' }));
    renderApp();

    const banner = await screen.findByTestId('aurora-banner');

    expect(banner).toHaveAttribute('data-actionable', 'false');
    expect(screen.queryByTestId('aurora-banner-cta')).toBeNull();
  });

  it('a real alert with NO forecast but a live night still has its door (the Map tab is offered, C3)', async () => {
    fetchForecasts.mockResolvedValue([]);
    renderApp();

    await pressBanner();

    await waitFor(() => expect(MapPaneStub.lastProps.handoff?.night).toBeTruthy());
    expect(MapPaneStub.lastProps.handoff).toMatchObject({ source: 'plan', eventType: 'AURORA' });
  });

  it('a LITE reader has no aurora status, so no banner and therefore no door', async () => {
    getAuroraStatus.mockResolvedValue(null);
    renderApp('LITE_USER');
    await waitFor(() => expect(screen.getByTestId('stub-shell')).toBeInTheDocument());

    expect(screen.queryByTestId('aurora-banner')).toBeNull();
  });
});
