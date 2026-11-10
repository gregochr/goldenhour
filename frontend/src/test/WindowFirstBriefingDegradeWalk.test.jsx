/**
 * `WindowFirstBriefingProvider` — the degrade walk (`selectUpcomingEvents`, used while the briefing
 * carries no `renderedEvents`) stops at the last day Plan renders, T+3 (whole-day-horizon-plan.md §5).
 *
 * <p>`renderedEvents` is absent from a payload cached before the field existed and from one served by
 * an older backend mid-deploy, and the briefing carries T+0..T+4 when built, so the walk used to put a
 * T+4 window on screen that the served path would never render. The probe prints the provider's
 * `upcomingEvents`; the served path (a `renderedEvents` list) is the control — the backend's own answer
 * is passed through untouched, which is what keeps the bound off it.
 */
import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, render, screen } from '@testing-library/react';

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getReach: vi.fn(), getSettings: vi.fn() }));
vi.mock('../api/regionApi.js', () => ({
  fetchRegions: vi.fn(),
  fetchRegionDriveTimes: vi.fn(),
}));
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: 'PRO_USER' }) }));

import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { getDailyBriefing } from '../api/briefingApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { fetchRegions, fetchRegionDriveTimes } from '../api/regionApi.js';
import { clearSwrCache } from '../utils/swrCache.js';
import { setRewind } from '../utils/rewind.js';

/** Early morning UK (BST), 2026-08-04: every window on every day is still ahead. */
const NOW = new Date('2026-08-04T03:00:00Z');
const DAYS = ['2026-08-04', '2026-08-05', '2026-08-06', '2026-08-07', '2026-08-08'];

/** A day carrying both events with their times, as the briefing builds it. */
const day = (date) => ({
  date,
  eventSummaries: [
    { targetType: 'SUNRISE', regions: [], unregioned: [], window: { verdict: 'MAYBE', badges: [], eventTime: `${date}T05:30:00` } },
    { targetType: 'SUNSET', regions: [], unregioned: [], window: { verdict: 'MAYBE', badges: [], eventTime: `${date}T20:11:00` } },
  ],
});

const briefing = (days, renderedEvents) => ({
  generatedAt: '2026-08-04T02:00:00',
  days: days.map(day),
  ...(renderedEvents ? { renderedEvents } : {}),
});

function Probe() {
  const { upcomingEvents } = useWindowFirstBriefing();
  return <span data-testid="upcoming">{upcomingEvents.map((e) => `${e.date}:${e.targetType}`).join(' ') || 'none'}</span>;
}

async function mountWith(payload) {
  getDailyBriefing.mockResolvedValue(payload);
  await act(async () => {
    render(<WindowFirstBriefingProvider><Probe /></WindowFirstBriefingProvider>);
  });
  return screen.getByTestId('upcoming').textContent.split(' ').filter((s) => s !== 'none');
}

const both = (dates) => dates.flatMap((d) => [`${d}:SUNRISE`, `${d}:SUNSET`]);

beforeEach(() => {
  localStorage.clear();
  clearSwrCache();
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] });
  vi.setSystemTime(NOW);
  getDailyBriefing.mockReset();
  getAllEvaluationScores.mockReset().mockResolvedValue([]);
  fetchTravelDayRanges.mockReset().mockResolvedValue([]);
  getReach.mockReset().mockResolvedValue([]);
  getSettings.mockReset().mockResolvedValue({ homePostcode: null, homePlaceName: null });
  fetchRegions.mockReset().mockResolvedValue([]);
  fetchRegionDriveTimes.mockReset().mockResolvedValue({});
});

afterEach(() => {
  setRewind(null);
  vi.useRealTimers();
});

describe('WindowFirstBriefingProvider — the degrade walk ends at T+3', () => {
  it('walks T+0..T+3 and never lists T+4 when renderedEvents is absent', async () => {
    const listed = await mountWith(briefing(DAYS));

    expect(listed).toEqual(both(DAYS.slice(0, 4)));
  });

  it('keeps a briefing that stops at T+3 whole', async () => {
    const listed = await mountWith(briefing(DAYS.slice(0, 4)));

    expect(listed).toEqual(both(DAYS.slice(0, 4)));
  });

  it('is not applied to the served path: the backend\'s own renderedEvents list passes through', async () => {
    const served = [{ date: DAYS[3], targetType: 'SUNSET' }, { date: DAYS[4], targetType: 'SUNRISE' }];

    const listed = await mountWith(briefing(DAYS, served));

    expect(listed).toEqual([`${DAYS[3]}:SUNSET`, `${DAYS[4]}:SUNRISE`]);
  });

  it('reads today from the app clock: a rewound clock moves the cut', async () => {
    // Rewound to 2026-08-01, T+3 is 08-04: only the first carried day survives. Read from the wall
    // clock (today in the test run, a different date entirely) the cut would keep none, and from the
    // briefing's own first day it would keep four.
    setRewind('2026-08-01T03:00:00Z');

    const listed = await mountWith(briefing(DAYS));

    expect(listed).toEqual(both(DAYS.slice(0, 1)));
  });
});
