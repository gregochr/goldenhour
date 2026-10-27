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
 * The structured tide-at-the-light strip (docs/engineering/tide-light-pro-plan.md F1, step 2).
 *
 * <p><b>What breaks if these fail:</b> a Lite reader's page is handed the offset from the tide to the
 * light (a window's {@code tide.nearestOffset}, a spring/king badge's sentence and aligned clock, big
 * seas' gap, event and aligned flags), or a Pro/Admin reader's briefing is re-copied so every memo
 * chain downstream re-runs.
 */

const NOON_ISH = new Date('2026-08-04T09:00:00Z');
const TODAY = '2026-08-04';

function briefing() {
  return {
    generatedAt: `${TODAY}T12:00:00`,
    days: [{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [],
        unregioned: [],
        window: {
          verdict: 'WORTH_IT',
          eventTime: `${TODAY}T20:11:00`,
          tide: {
            state: 'MID', direction: 'FALLING', nearestType: 'HW', nearestTime: '19:28',
            nearestOffset: '43m before sunset', range: '4.9 m',
            lightAlignment: 'HW', nearestHeight: '4.6 m', eventTime: '20:11',
          },
          badges: [
            { type: 'KING_TIDE', label: 'King tide', detail: 'aligned with sunset', eventTime: `${TODAY}T20:11:00` },
            { type: 'SPRING_TIDE', label: 'Spring tide', detail: 'aligned with sunset', eventTime: `${TODAY}T20:11:00` },
            { type: 'AURORA', label: 'Aurora', detail: 'Kp 6 tonight', eventTime: `${TODAY}T23:00:00` },
          ],
        },
      }],
      peak: null,
    }],
    bigSeas: [{
      date: TODAY,
      hwTime: `${TODAY}T19:28:00`,
      alignedEvent: 'SUNSET',
      gapMinutes: 43,
      gapSide: 'BEFORE',
      waveHeightM: 4.1,
      daylightHighWater: true,
      structures: [
        { locationId: 1, label: 'Roker Pier', hwTime: `${TODAY}T19:28:00`, aligned: true },
        { locationId: 2, label: 'Amble piers', hwTime: `${TODAY}T19:40:00`, aligned: false },
      ],
    }],
    renderedEvents: [{ date: TODAY, targetType: 'SUNSET' }],
  };
}

const latest = { current: null };
function Probe() {
  const ctx = useWindowFirstBriefing();
  useEffect(() => { latest.current = ctx; });
  return null;
}

describe('WindowFirstBriefingProvider — the tide-at-the-light strip', () => {
  beforeEach(() => {
    localStorage.clear();
    latest.current = null;
    getDailyBriefing.mockReset();
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

  async function load() {
    render(<WindowFirstBriefingProvider><Probe /></WindowFirstBriefingProvider>);
    await act(async () => { await vi.advanceTimersByTimeAsync(10); });
  }

  it('strips only the offset, the spring/king badge sentence and clock, and the big-seas alignment for Lite', async () => {
    mockRole = 'LITE_USER';
    getDailyBriefing.mockResolvedValue(briefing());
    await load();

    const win = latest.current.briefing.days[0].eventSummaries[0].window;
    expect(win.tide.nearestOffset).toBeNull();
    // The map strip's light-alignment fields go with the offset: they relate the water to the light.
    expect(win.tide.lightAlignment).toBeNull();
    expect(win.tide.nearestHeight).toBeNull();
    expect(win.tide.eventTime).toBeNull();
    // The free tide-table facts stay.
    expect(win.tide).toMatchObject({
      state: 'MID', direction: 'FALLING', nearestType: 'HW', nearestTime: '19:28', range: '4.9 m',
    });
    const [king, spring, aurora] = win.badges;
    for (const b of [king, spring]) {
      expect(b.detail).toBeNull();
      expect(b.eventTime).toBeNull();
      expect(b.label).toMatch(/tide/);
    }
    // A non-tide badge is untouched.
    expect(aurora).toMatchObject({ detail: 'Kp 6 tonight', eventTime: `${TODAY}T23:00:00` });
    expect(win.verdict).toBe('WORTH_IT');

    const [day] = latest.current.briefing.bigSeas;
    expect(day.alignedEvent).toBeNull();
    expect(day.gapMinutes).toBeNull();
    expect(day.gapSide).toBeNull();
    expect(day.structures.map((s) => s.aligned)).toEqual([false, false]);
    // The free big-seas facts stay: waves, the HW clock and the daylight flag.
    expect(day).toMatchObject({ waveHeightM: 4.1, daylightHighWater: true, hwTime: `${TODAY}T19:28:00` });
    expect(day.structures.map((s) => s.hwTime)).toEqual([`${TODAY}T19:28:00`, `${TODAY}T19:40:00`]);
  });

  it.each(['PRO_USER', 'ADMIN'])('hands %s the very object that was fetched (identity, no strip)', async (role) => {
    mockRole = role;
    const payload = briefing();
    getDailyBriefing.mockResolvedValue(payload);
    await load();

    expect(latest.current.briefing).toBe(payload);
    const win = payload.days[0].eventSummaries[0].window;
    expect(win.tide.nearestOffset).toBe('43m before sunset');
    expect(win.tide).toMatchObject({ lightAlignment: 'HW', nearestHeight: '4.6 m', eventTime: '20:11' });
    expect(win.badges[0].detail).toBe('aligned with sunset');
    expect(payload.bigSeas[0].alignedEvent).toBe('SUNSET');
  });

  it('tolerates a briefing with no window, no badges and no bigSeas', async () => {
    mockRole = 'LITE_USER';
    getDailyBriefing.mockResolvedValue({
      generatedAt: `${TODAY}T12:00:00`,
      days: [{ date: TODAY, eventSummaries: [{ targetType: 'SUNSET', regions: [], unregioned: [] }], peak: null }],
      renderedEvents: [],
    });
    await load();

    expect(latest.current.briefing.days[0].eventSummaries[0].targetType).toBe('SUNSET');
    expect(latest.current.briefing.bigSeas).toBeUndefined();
  });
});
