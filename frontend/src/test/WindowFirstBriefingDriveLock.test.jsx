import React, { useEffect } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, render, act } from '@testing-library/react';
import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { getDailyBriefing } from '../api/briefingApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { fetchRegions, fetchRegionDriveTimes } from '../api/regionApi.js';

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getReach: vi.fn(), getSettings: vi.fn() }));
vi.mock('../api/regionApi.js', () => ({ fetchRegions: vi.fn(), fetchRegionDriveTimes: vi.fn() }));

let mockRole = 'LITE_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));

/**
 * The drive-time lock (docs/engineering/pro-features-gate-plan.md §9 D1).
 *
 * <p><b>What breaks if these fail:</b> a Lite reader's page asks for `/api/user/settings/reach` (a 403
 * after D2) or gets drive times drawn from a map it should not have; a Pro reader loses theirs; or a
 * stale demoted client (still Pro locally) shows an error or retries after the server's 403.
 */

const NOON_ISH = new Date('2026-08-04T09:00:00Z');
const TODAY = '2026-08-04';
const BRIEFING = { generatedAt: `${TODAY}T12:00:00`, days: [], renderedEvents: [] };
const REACH = [{ locationId: 7, driveMinutes: 42, distanceMiles: 18 }];

/** The latest context value the probe saw; written in an effect, never during render. */
const latest = { current: null };
function Probe() {
  const ctx = useWindowFirstBriefing();
  useEffect(() => { latest.current = ctx; });
  return null;
}

const renderProvider = () => render(
  <WindowFirstBriefingProvider><Probe /></WindowFirstBriefingProvider>,
);

describe('WindowFirstBriefingProvider — the drive-time lock', () => {
  beforeEach(() => {
    localStorage.clear();
    latest.current = null;
    getDailyBriefing.mockReset();
    getDailyBriefing.mockResolvedValue(BRIEFING);
    fetchTravelDayRanges.mockReset();
    fetchTravelDayRanges.mockResolvedValue([]);
    getAllEvaluationScores.mockReset();
    getAllEvaluationScores.mockResolvedValue([]);
    getReach.mockReset();
    getReach.mockResolvedValue([]);
    getSettings.mockReset();
    getSettings.mockResolvedValue({ homePostcode: null, homePlaceName: null });
    fetchRegions.mockReset();
    fetchRegions.mockResolvedValue([]);
    fetchRegionDriveTimes.mockReset();
    fetchRegionDriveTimes.mockResolvedValue({});
    vi.useFakeTimers({ shouldAdvanceTime: true });
    vi.setSystemTime(NOON_ISH);
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  async function settle() {
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
  }


  it('a demotion inside the session empties the reach a Pro answer left behind', async () => {
    mockRole = 'PRO_USER';
    getReach.mockResolvedValue(REACH);
    const view = renderProvider();
    await settle();
    expect(latest.current.reachById.size).toBe(1);

    mockRole = 'LITE_USER';
    view.rerender(<WindowFirstBriefingProvider><Probe /></WindowFirstBriefingProvider>);
    await settle();

    expect(latest.current.driveLocked).toBe(true);
    expect(latest.current.reachById.size).toBe(0);
    expect(getReach).toHaveBeenCalledTimes(1);
  });

  it.each(['LITE_USER', null])('a reader with role %s is driveLocked and never asks for /reach', async (role) => {
    mockRole = role;
    getReach.mockResolvedValue(REACH);
    renderProvider();
    await settle();

    expect(latest.current.driveLocked).toBe(true);
    expect(latest.current.reachLens.locked).toBe(true);
    expect(getReach).not.toHaveBeenCalled();
    expect(latest.current.reachById.size).toBe(0);
    expect(latest.current.effectiveReachById.size).toBe(0);
  });

  it.each(['PRO_USER', 'ADMIN'])('%s asks for /reach and keeps the answer', async (role) => {
    mockRole = role;
    getReach.mockResolvedValue(REACH);
    renderProvider();
    await settle();

    expect(latest.current.driveLocked).toBe(false);
    expect(latest.current.reachLens.locked).toBe(false);
    expect(getReach).toHaveBeenCalledTimes(1);
    expect(latest.current.reachById.get(7)).toEqual({
      driveMinutes: 42, distanceMiles: 18, driveStatus: null,
    });
  });

  it('keeps the served drive status, and reads a reader with a measured drive as measured', async () => {
    mockRole = 'PRO_USER';
    getReach.mockResolvedValue([
      { locationId: 7, driveMinutes: 42, distanceMiles: 18, driveStatus: 'MEASURED' },
      { locationId: 8, distanceMiles: 61, driveStatus: 'UNROUTABLE' },
    ]);
    renderProvider();
    await settle();

    expect(latest.current.reachById.get(8)).toEqual({
      driveMinutes: null, distanceMiles: 61, driveStatus: 'UNROUTABLE',
    });
    expect(latest.current.reachMeasured).toBe(true);
  });

  it('keeps the served drive point name as driveTo, and adds nothing for a place without one', async () => {
    mockRole = 'PRO_USER';
    getReach.mockResolvedValue([
      {
        locationId: 7, driveMinutes: 80, distanceMiles: 20, driveStatus: 'MEASURED', driveTo: 'Glenridding',
      },
      { locationId: 8, driveMinutes: 30, distanceMiles: 9, driveStatus: 'MEASURED' },
    ]);
    renderProvider();
    await settle();

    expect(latest.current.reachById.get(7)).toEqual({
      driveMinutes: 80, distanceMiles: 20, driveStatus: 'MEASURED', driveTo: 'Glenridding',
    });
    expect(latest.current.reachById.get(8)).toEqual({
      driveMinutes: 30, distanceMiles: 9, driveStatus: 'MEASURED',
    });
  });

  it('reads a reader whose every drive is pending as NOT measured, so no tier acts', async () => {
    mockRole = 'PRO_USER';
    getReach.mockResolvedValue([{ locationId: 7, distanceMiles: 18, driveStatus: 'PENDING' }]);
    renderProvider();
    await settle();

    expect(latest.current.reachMeasured).toBe(false);
  });

  it('is not measured for a locked reader, and before anything has loaded', async () => {
    mockRole = 'LITE_USER';
    renderProvider();
    await settle();
    expect(latest.current.reachMeasured).toBe(false);
  });

  it('derives the away status from the base\'s drivesMeasuredAt and each place\'s driveTargetChangedAt', async () => {
    mockRole = 'PRO_USER';
    const region = {
      id: 3, name: 'Lake District', enabled: true, baseName: 'Keswick', baseLat: 54.6, baseLon: -3.13,
      drivesMeasuredAt: '2026-11-05T02:00:00Z',
    };
    fetchRegions.mockResolvedValue([region]);
    fetchRegionDriveTimes.mockResolvedValue({ 3: { 1: 25 } });
    const locations = [
      { id: 1, name: 'Near', driveTargetChangedAt: '2026-03-01T12:00:00' },
      { id: 2, name: 'Helvellyn', driveTargetChangedAt: '2026-03-01T12:00:00' },
      { id: 4, name: 'Moved today', driveTargetChangedAt: '2026-11-05T09:00:00' },
    ];
    render(<WindowFirstBriefingProvider locations={locations}><Probe /></WindowFirstBriefingProvider>);
    await settle();

    act(() => { latest.current.setOrigin(region); });
    await settle();

    const away = latest.current.effectiveReachById;
    expect(away.get(1)).toMatchObject({ driveMinutes: 25, driveStatus: 'MEASURED' });
    expect(away.get(2)).toMatchObject({ driveMinutes: null, driveStatus: 'UNROUTABLE' });
    expect(away.get(4)).toMatchObject({ driveMinutes: null, driveStatus: 'PENDING' });
    expect(latest.current.reachMeasured).toBe(true);
  });

  it('a stale client that still thinks it is Pro gets a 403: no drive times, no error, one request, no retry', async () => {
    mockRole = 'PRO_USER';
    getReach.mockRejectedValue({ response: { status: 403 } });
    renderProvider();
    await settle();
    await act(async () => { await vi.advanceTimersByTimeAsync(60000); });

    expect(getReach).toHaveBeenCalledTimes(1);
    expect(latest.current.reachById.size).toBe(0);
    expect(latest.current.effectiveReachById.size).toBe(0);
    expect(latest.current.briefing).not.toBeNull();
  });
});
