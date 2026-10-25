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


  it('a Lite reader is driveLocked and never asks for /reach', async () => {
    mockRole = 'LITE_USER';
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
    expect(latest.current.reachById.get(7)).toEqual({ driveMinutes: 42, distanceMiles: 18 });
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
