/**
 * The aurora banner's door (overlay convergence C4, `docs/engineering/overlay-convergence-plan.md`
 * section 3 C4 and section 4 #4, #7): the banner is mounted above `<main>` on every tab, and its
 * "View on map" now takes the structured `source:'plan'` door instead of opening the retired
 * overlay. Driven through the real `App` and the real banner; the shell and the Map pane are stubs
 * so the payload `App` builds (`mapTabHandoff`, `operationsOpen`) can be read off them.
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
import { registerBackLayer, __resetForTests as resetDialogHistory } from '../utils/dialogHistory.js';

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
  getAuroraLocations: vi.fn(() => Promise.resolve([])),
  getAuroraForecastResults: vi.fn(() => Promise.resolve([])),
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
vi.mock('../hooks/useAuroraViewline.js', () => ({ useAuroraViewline: () => ({ viewline: null }) }));
vi.mock('../components/AuroraFieldThumb.jsx', () => ({
  default: () => <div data-testid="aurora-field-stub" />,
}));

/** The shell, stubbed to the props under test plus a button that reports "the map view is open". It registers
 *  `openMap` / `closeMap` spies the way the real shell does (`registerOpenMap`). */
const ShellStub = { lastProps: null, doors: null };
vi.mock('../components/WindowFirstShell.jsx', () => ({
  default: function ShellDouble(props) {
    ShellStub.lastProps = props;
    const { registerOpenMap } = props;
    const [boom, setBoom] = React.useState(false);
    if (boom) throw new Error('shell crashed with the map view open');
    React.useEffect(() => {
      ShellStub.doors = { open: vi.fn(), close: vi.fn() };
      registerOpenMap?.(ShellStub.doors);
      return () => registerOpenMap?.(null);
    }, [registerOpenMap]);
    return (
      <div data-testid="stub-shell">
        <button type="button" data-testid="on-map-tab" onClick={() => props.onScreenChange?.('map')}>
          map tab in force
        </button>
        <button type="button" data-testid="crash-shell" onClick={() => setBoom(true)}>crash</button>
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
import { getAuroraAlert, getAuroraStatus } from '../api/auroraApi.js';
import { getNlcSighting } from '../api/nlcApi.js';
import { getAstroConditions } from '../api/astroApi.js';
import { getAlmanac } from '../api/almanacApi.js';
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
  // Operations is open exactly while the address says `#manage`: no test starts on another's address.
  window.history.replaceState(null, '', '/');
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
  getAuroraAlert.mockReset().mockResolvedValue(null);
  getNlcSighting.mockReset().mockResolvedValue(null);
  getAstroConditions.mockReset().mockResolvedValue(null);
  getAlmanac.mockReset().mockResolvedValue({ entries: [] });
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
  it('a real alert pressed with the map view closed: a plan door naming the night, with the Plan lens, and the shell asked to show the view', async () => {
    renderApp();

    await pressBanner();

    await waitFor(() => expect(MapPaneStub.lastProps.handoff?.night).toBeTruthy());
    expect(MapPaneStub.lastProps.handoff).toMatchObject({
      source: 'plan',
      eventType: 'AURORA',
      date: TODAY,
      night: { kind: 'aurora', date: TODAY },
      minRating: 4,
      limitMinutes: 90,
    });
    expect(ShellStub.doors.open).toHaveBeenCalledTimes(1);
    expect(ShellStub.lastProps.operationsOpen ?? false).toBe(false);
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

  it('⚠️ pressed with the map view OPEN it only selects the night: source map, no lens, no second view', async () => {
    renderApp();
    await screen.findByTestId('aurora-banner');
    await act(async () => { fireEvent.click(screen.getByTestId('on-map-tab')); });
    const openBefore = ShellStub.lastProps.operationsOpen ?? false;

    await pressBanner();

    await waitFor(() => expect(MapPaneStub.lastProps.handoff?.night).toBeTruthy());
    const { handoff } = MapPaneStub.lastProps;
    expect(handoff).toMatchObject({ source: 'map', eventType: 'AURORA', night: { kind: 'aurora', date: TODAY } });
    expect(handoff).not.toHaveProperty('minRating');
    expect(handoff).not.toHaveProperty('limitMinutes');
    expect(handoff).not.toHaveProperty('region');
    expect(handoff).not.toHaveProperty('title');
    expect(ShellStub.lastProps.operationsOpen ?? false).toEqual(openBefore);
    expect(ShellStub.doors.open).not.toHaveBeenCalled();
  });

  it('a SIMULATED alert pressed by an admin asks for Operations and never for the Map tab', async () => {
    getAuroraStatus.mockResolvedValue(alert({ simulated: true }));
    renderApp('ADMIN');

    await pressBanner();

    await waitFor(() => expect(ShellStub.lastProps.operationsOpen).toBe(true));
    expect(MapPaneStub.lastProps?.handoff ?? null).toBeNull();
    expect(window.location.hash).toBe('#manage/runs/start');
  });

  it('a SIMULATED alert for a non-admin has no door: no call to action, no tab request, no handoff', async () => {
    getAuroraStatus.mockResolvedValue(alert({ simulated: true }));
    renderApp('PRO_USER');
    const banner = await screen.findByTestId('aurora-banner');
    expect(banner).toHaveAttribute('data-actionable', 'false');
    expect(screen.getByTestId('aurora-banner-cta').textContent).toMatch(/Test/);

    await act(async () => { fireEvent.click(banner); });

    expect(ShellStub.lastProps.operationsOpen ?? false).toBe(false);
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

  it('a LITE reader gets the Lite banner from /alert, with no Map door and no /status request', async () => {
    getAuroraAlert.mockResolvedValue({
      level: 'MODERATE', active: true, triggerType: 'forecast', allOvercast: false,
      currentNightDate: TODAY, currentNightEndsAt: '2026-01-16T06:00:00Z',
    });
    renderApp('LITE_USER');

    const banner = await screen.findByTestId('aurora-banner');
    await act(async () => { fireEvent.click(banner); });

    expect(banner).toHaveAttribute('data-actionable', 'false');
    expect(screen.getByTestId('aurora-banner-upsell-trigger')).toBeInTheDocument();
    expect(screen.queryByTestId('aurora-banner-cta')).toBeNull();
    expect(getAuroraStatus).not.toHaveBeenCalled();
    expect(MapPaneStub.lastProps?.handoff ?? null).toBeNull();
    expect(ShellStub.lastProps.operationsOpen ?? false).toBe(false);
  });

  it('a LITE reader with a quiet alert has no banner', async () => {
    getAuroraAlert.mockResolvedValue({ level: 'QUIET', active: false, triggerType: null, allOvercast: false });
    renderApp('LITE_USER');
    await waitFor(() => expect(getAuroraAlert).toHaveBeenCalled());
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
    expect(ShellStub.lastProps.operationsOpen ?? false).toBe(false);
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

describe('App — a simulated alert\'s banner opens Operations (C4, S3)', () => {
  it('opens Operations on every press, and aims the hash at Start a run', async () => {
    getAuroraStatus.mockResolvedValue(alert({ simulated: true }));
    renderApp('ADMIN');

    await pressBanner();
    await waitFor(() => expect(ShellStub.lastProps.operationsOpen).toBe(true));
    expect(window.location.hash).toBe('#manage/runs/start');
    expect(screen.getByTestId('aurora-banner-cta').textContent).toBe('Operations →');

    // The reader leaves Operations ("‹ PhotoCast", or Back), then presses again: it opens again.
    await act(async () => { ShellStub.lastProps.onCloseOperations(); });
    await waitFor(() => expect(ShellStub.lastProps.operationsOpen).toBe(false));
    await pressBanner();
    await waitFor(() => expect(ShellStub.lastProps.operationsOpen).toBe(true));
    expect(window.location.hash).toBe('#manage/runs/start');
  });

  it('hands the shell no way into Operations to a non-admin, whatever the address says', async () => {
    getAuroraStatus.mockResolvedValue(alert({ simulated: false }));
    renderApp('PRO_USER');
    await screen.findByTestId('aurora-banner');
    expect(ShellStub.lastProps.operationsPane ?? null).toBeNull();
    expect(ShellStub.lastProps.onOpenOperations).toBeUndefined();
    await act(async () => { window.location.hash = 'manage/runs'; });
    await waitFor(() => expect(window.location.hash).toBe(''));
    expect(ShellStub.lastProps.operationsOpen ?? false).toBe(false);
  });

  it('with the map view open it closes the view first and sets the hash only once the history has settled (D4, D9)', async () => {
    getAuroraStatus.mockResolvedValue(alert({ simulated: true }));
    renderApp('ADMIN');
    await screen.findByTestId('aurora-banner');
    await act(async () => { fireEvent.click(screen.getByTestId('on-map-tab')); });

    await pressBanner();

    await waitFor(() => expect(ShellStub.lastProps.operationsOpen).toBe(true));
    expect(ShellStub.doors.close).toHaveBeenCalledWith({ restoreFocus: false });
    expect(window.location.hash).toBe('#manage/runs/start');
  });
});

describe('App — the NLC sighting request follows the backend role gate (P2f)', () => {
  it('a Lite reader makes no NLC request (the endpoint is a 403 for them)', async () => {
    renderApp('LITE_USER');
    // Control: the app really mounted and ran its startup requests before the negative is read.
    await waitFor(() => expect(fetchLocations).toHaveBeenCalled());
    await act(async () => { await Promise.resolve(); });
    expect(getNlcSighting).not.toHaveBeenCalled();
  });

  it.each(['PRO_USER', 'ADMIN'])('%s still asks for the sighting', async (role) => {
    renderApp(role);
    await waitFor(() => expect(getNlcSighting).toHaveBeenCalled());
  });
});


describe('App — the REAL dialog-history engine under Operations\' door (D4, D9, review round 1)', () => {
  const settleReal = () => act(async () => { await new Promise((resolve) => { setTimeout(resolve, 60); }); });

  beforeEach(() => {
    Object.defineProperty(window.navigator, 'userActivation', { configurable: true, value: { isActive: true } });
    resetDialogHistory();
  });

  afterEach(() => {
    delete window.navigator.userActivation;
    resetDialogHistory();
    window.history.replaceState(null, '', '/');
  });

  it('the view\'s history entry is unwound BEFORE #manage is written, so Back from Operations is not a stale #map', async () => {
    getAuroraStatus.mockResolvedValue(alert({ simulated: true }));
    renderApp('ADMIN');
    await screen.findByTestId('aurora-banner');
    // The shell's own layer, the real engine's, with its address: what an open map view holds.
    const layer = registerBackLayer({ onBack: () => {}, url: '#map/2026-01-15/sunset' });
    await settleReal();
    expect(window.location.hash).toBe('#map/2026-01-15/sunset');
    ShellStub.doors.close = vi.fn(() => layer.release());
    await act(async () => { fireEvent.click(screen.getByTestId('on-map-tab')); });

    const order = [];
    const goSpy = vi.spyOn(window.history, 'go').mockImplementation(function go(...args) {
      order.push('go');
      return Object.getPrototypeOf(window.history).go.apply(this, args);
    });
    window.addEventListener('hashchange', () => order.push('hash'));

    await pressBanner();
    await settleReal();

    expect(ShellStub.doors.close).toHaveBeenCalledWith({ restoreFocus: false });
    expect(window.location.hash).toBe('#manage/runs/start');
    expect(order.indexOf('go')).toBeGreaterThanOrEqual(0);
    expect(order.indexOf('go')).toBeLessThan(order.indexOf('hash'));

    // Back from Operations lands on the bare page, never on the closed view's old entry.
    window.history.back();
    await settleReal();
    expect(window.location.hash).toBe('');
    goSpy.mockRestore();
  });
});

describe('App — what stays behind the map view (review round 1)', () => {
  it('makes the banner block and the footer inert while the view is open, and not otherwise', async () => {
    renderApp();
    await screen.findByTestId('aurora-banner');
    expect(screen.getByTestId('app-banner-block')).not.toHaveAttribute('inert');
    expect(document.querySelector('footer')).not.toHaveAttribute('inert');

    await act(async () => { fireEvent.click(screen.getByTestId('on-map-tab')); });

    expect(screen.getByTestId('app-banner-block')).toHaveAttribute('inert');
    expect(document.querySelector('footer')).toHaveAttribute('inert');
  });

  it('keeps the session notice outside the inert block, fixed above the view (z 49), so it stays usable', async () => {
    renderApp();
    await screen.findByTestId('aurora-banner');
    await act(async () => { fireEvent.click(screen.getByTestId('on-map-tab')); });

    const slot = screen.getByTestId('session-banner-slot');
    expect(slot).not.toHaveAttribute('inert');
    expect(slot.closest('[inert]')).toBeNull();
    expect(slot.className).toMatch(/fixed/);
    expect(slot.className).toMatch(/z-\[49\]/);
    expect(screen.getByTestId('app-banner-block').contains(slot)).toBe(false);
  });

  it('leaves the session notice in the page\'s flow when the view is closed', async () => {
    renderApp();
    await screen.findByTestId('aurora-banner');
    expect(screen.getByTestId('session-banner-slot').className).not.toMatch(/fixed/);
  });

  it('a crash under the error boundary with the view open does not leave the footer and banners inert', async () => {
    const quiet = vi.spyOn(console, 'error').mockImplementation(() => {});
    renderApp();
    await screen.findByTestId('aurora-banner');
    await act(async () => { fireEvent.click(screen.getByTestId('on-map-tab')); });
    expect(document.querySelector('footer')).toHaveAttribute('inert');

    await act(async () => { fireEvent.click(screen.getByTestId('crash-shell')); });

    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(document.querySelector('footer')).not.toHaveAttribute('inert');
    quiet.mockRestore();
  });

  it('starts the view below the pinned session notice: the root property follows the slot\'s height and clears on close', async () => {
    const height = vi.spyOn(HTMLElement.prototype, 'offsetHeight', 'get').mockImplementation(function measure() {
      return this.dataset?.testid === 'session-banner-slot' ? 44 : 0;
    });
    renderApp();
    await screen.findByTestId('aurora-banner');
    expect(document.documentElement.style.getPropertyValue('--wf-session-banner-h')).toBe('');

    await act(async () => { fireEvent.click(screen.getByTestId('on-map-tab')); });
    expect(document.documentElement.style.getPropertyValue('--wf-session-banner-h')).toBe('44px');

    await act(async () => { ShellStub.lastProps.onScreenChange('plan'); });
    expect(document.documentElement.style.getPropertyValue('--wf-session-banner-h')).toBe('');
    height.mockRestore();
  });
});
