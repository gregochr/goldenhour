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

// Pinned: the dates below are fixed strings and the clock is held on them (`beforeEach`), so nothing
// here depends on the wall clock the suite happens to run on (the fixtures used to read it at module
// load, and a run across UK midnight would have disagreed with itself).
const NOW = '2026-01-15T12:00:00Z';
const TODAY = '2026-01-15';
const YESTERDAY = '2026-01-14';
const TOMORROW = '2026-01-16';

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
  level: 'MODERATE', kpIndex: 6, active: true, simulated: false, currentNightDate: TODAY,
  currentNightEndsAt: '2026-01-16T06:00:00Z', ...extra,
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
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(NOW));
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
  vi.useRealTimers();
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
    expect(MapPaneStub.lastProps.handoff).not.toHaveProperty('focusNames');
  });

  it('names the running night, not the civil date, when the night began yesterday', async () => {
    const yesterday = YESTERDAY;
    getAuroraStatus.mockResolvedValue(alert({
      currentNightDate: yesterday, currentNightEndsAt: '2026-01-15T14:00:00Z',
    }));
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
    expect(window.location.hash).toBe('#manage/metrics');
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

  it('⚠️ a SIMULATED alert with a FORECAST on screen still offers no Map door (admin: Operations; others: a statement)', async () => {
    getAuroraStatus.mockResolvedValue(alert({ simulated: true }));
    renderApp('PRO_USER');

    const banner = await screen.findByTestId('aurora-banner');
    await act(async () => { fireEvent.click(banner); });

    expect(banner).toHaveAttribute('data-actionable', 'false');
    expect(MapPaneStub.lastProps?.handoff ?? null).toBeNull();
    expect(ShellStub.lastProps.tabRequest ?? null).toBeNull();
  });

  it('⚠️ a stale night WITH a forecast on screen has no door: no call to action, and a press changes nothing', async () => {
    // The Map tab IS offered (a forecast), but the status' night has ended: there is no night row, so
    // a door would leave the map on a solar window.
    getAuroraStatus.mockResolvedValue(alert({ currentNightEndsAt: '2026-01-15T05:00:00Z' }));
    renderApp();

    const banner = await screen.findByTestId('aurora-banner');
    await act(async () => { fireEvent.click(banner); });

    expect(banner).toHaveAttribute('data-actionable', 'false');
    expect(screen.queryByTestId('aurora-banner-cta')).toBeNull();
    expect(MapPaneStub.lastProps?.handoff ?? null).toBeNull();
  });

  it('an inactive alert-worthy status WITH a forecast on screen has no door either', async () => {
    getAuroraStatus.mockResolvedValue(alert({ active: false }));
    renderApp();

    const banner = await screen.findByTestId('aurora-banner');

    expect(banner).toHaveAttribute('data-actionable', 'false');
  });

  it('a door pressed from the COMING UP tab names it as where the reader came from', async () => {
    renderApp();
    await screen.findByTestId('aurora-banner');
    // The shell reports the tab in force; the stub's button reports "map"; stand in for Coming up.
    await act(async () => { ShellStub.lastProps.onTabChange('coming-up'); });

    await pressBanner();

    await waitFor(() => expect(MapPaneStub.lastProps.handoff?.night).toBeTruthy());
    expect(MapPaneStub.lastProps.handoff).toMatchObject({ source: 'plan', from: 'coming-up' });
  });

  it('a Plan press says it came from Plan', async () => {
    renderApp();

    await pressBanner();

    await waitFor(() => expect(MapPaneStub.lastProps.handoff?.night).toBeTruthy());
    expect(MapPaneStub.lastProps.handoff.from).toBe('plan');
  });

  it('registers the shell\'s dialog closer and calls it before an in-place (Map tab) press, and not before an ordinary one', async () => {
    renderApp();
    await screen.findByTestId('aurora-banner');
    const closer = vi.fn();
    await act(async () => { ShellStub.lastProps.registerCloseDialogs(closer); });

    await pressBanner();
    expect(closer).not.toHaveBeenCalled();

    await act(async () => { fireEvent.click(screen.getByTestId('on-map-tab')); });
    await pressBanner();
    expect(closer).toHaveBeenCalledTimes(1);
  });
});

describe('App — the breadcrumb\'s return (doors D2, extended by C4)', () => {
  const doorThenReturn = async (from, opener) => {
    renderApp();
    await screen.findByTestId('stub-shell');
    if (from) await act(async () => { ShellStub.lastProps.onTabChange(from); });
    if (opener) opener.focus();
    await pressBanner();
    await waitFor(() => expect(MapPaneStub.lastProps.handoff?.night).toBeTruthy());
    await act(async () => { MapPaneStub.lastProps.onReturnToPlan(from ?? 'plan'); });
  };

  it.each([
    ['plan', 'plan'],
    ['coming-up', 'coming-up'],
    ['operations', 'operations'],
  ])('a door from %s returns to %s', async (from, id) => {
    await doorThenReturn(from);
    expect(ShellStub.lastProps.tabRequest).toMatchObject({ id });
  });

  it('anything that is not a tab a door can come from returns to Plan', async () => {
    renderApp();
    await screen.findByTestId('stub-shell');
    await pressBanner();
    await waitFor(() => expect(MapPaneStub.lastProps.handoff?.night).toBeTruthy());
    await act(async () => { MapPaneStub.lastProps.onReturnToPlan('nowhere'); });
    expect(ShellStub.lastProps.tabRequest).toMatchObject({ id: 'plan' });
    await act(async () => { MapPaneStub.lastProps.onReturnToPlan(); });
    expect(ShellStub.lastProps.tabRequest).toMatchObject({ id: 'plan' });
  });

  it('carries the control that was pressed, once, and none when nothing held focus', async () => {
    const opener = document.createElement('button');
    document.body.appendChild(opener);
    await doorThenReturn('coming-up', opener);
    expect(ShellStub.lastProps.tabRequest.opener).toBe(opener);

    // Spent: a second return from the same door has no stale opener to focus.
    await act(async () => { MapPaneStub.lastProps.onReturnToPlan('coming-up'); });
    expect(ShellStub.lastProps.tabRequest).not.toHaveProperty('opener');
    opener.remove();
  });

  it('the request is nonce\'d: two returns in a row both land', async () => {
    await doorThenReturn('plan');
    const first = ShellStub.lastProps.tabRequest.nonce;
    await act(async () => { MapPaneStub.lastProps.onReturnToPlan('plan'); });
    expect(ShellStub.lastProps.tabRequest.nonce).not.toBe(first);
  });
});

describe('App — a simulated alert\'s banner opens Operations (C4, S3)', () => {
  it('asks for Operations with a fresh nonce on every press, and aims the hash at Job Runs', async () => {
    getAuroraStatus.mockResolvedValue(alert({ simulated: true }));
    renderApp('ADMIN');

    await pressBanner();
    await waitFor(() => expect(ShellStub.lastProps.tabRequest).toMatchObject({ id: 'operations' }));
    const first = ShellStub.lastProps.tabRequest.nonce;
    expect(window.location.hash).toBe('#manage/metrics');
    expect(screen.getByTestId('aurora-banner-cta').textContent).toBe('Operations →');

    // Navigate away (the shell stub reports Plan), then press again: a second request must land.
    await act(async () => { ShellStub.lastProps.onTabChange('plan'); });
    await pressBanner();
    expect(ShellStub.lastProps.tabRequest.nonce).toBeGreaterThan(first);
  });
});
