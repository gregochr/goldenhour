import React from 'react';
import {
  describe, it, expect, vi, beforeEach, afterEach,
} from 'vitest';
import { act, cleanup, render } from '@testing-library/react';
import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { getDailyBriefing } from '../api/briefingApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getReach, getSettings } from '../api/settingsApi.js';
import { fetchRegions, fetchRegionDriveTimes } from '../api/regionApi.js';

/**
 * The briefing context publishes the served `allowedLocationIds` (sub-regions-plan S5a-1): the one marker
 * the Map reads to know a body was shaped by the reader's picks. An array, `[]` included, is shaped; the
 * field being absent is not. The role is never consulted, and no membership test is made against the list.
 */

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({ getReach: vi.fn(), getSettings: vi.fn() }));
vi.mock('../api/regionApi.js', () => ({ fetchRegions: vi.fn(), fetchRegionDriveTimes: vi.fn() }));

let mockRole = 'LITE_USER';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole }) }));

const NOON = new Date('2026-08-04T09:00:00Z');
const TODAY = '2026-08-04';

function body(extra = {}) {
  return {
    generatedAt: `${TODAY}T08:00:00`,
    days: [{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'North East',
          displayVerdict: 'WORTH_IT',
          verdict: 'GO',
          scoredLocationCount: 1,
          slots: [{ locationId: 7, locationName: 'Bamburgh', solarEventTime: `${TODAY}T20:11:00`, claudeRating: 4 }],
        }],
        unregioned: [],
        window: { verdict: 'WORTH_IT', badges: [], eventTime: `${TODAY}T20:11:00` },
      }],
    }],
    renderedEvents: [{ date: TODAY, targetType: 'SUNSET' }],
    ...extra,
  };
}

/** Every context value the probe saw; the last one is the settled state. */
const onValue = vi.fn();
const seen = { get last() { return onValue.mock.calls.at(-1)?.[0] ?? null; } };

function Probe() {
  onValue(useWindowFirstBriefing());
  return null;
}

async function mountWith(briefing, scores = []) {
  getDailyBriefing.mockResolvedValue(briefing);
  getAllEvaluationScores.mockResolvedValue(scores);
  await act(async () => {
    render(<WindowFirstBriefingProvider locations={LOCATIONS}><Probe /></WindowFirstBriefingProvider>);
  });
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
}

const LOCATIONS = [
  {
    id: 7, name: 'Bamburgh', lat: 55.6, lon: -1.7, regionName: 'North East', locationType: ['LANDSCAPE'],
  },
  {
    id: 8, name: 'Craster', lat: 55.47, lon: -1.59, regionName: 'North East', locationType: ['LANDSCAPE'],
  },
];

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: false });
  vi.setSystemTime(NOON);
  localStorage.clear();
  onValue.mockClear();
  mockRole = 'LITE_USER';
  getDailyBriefing.mockReset();
  fetchTravelDayRanges.mockReset().mockResolvedValue([]);
  getAllEvaluationScores.mockReset().mockResolvedValue([]);
  getReach.mockReset().mockResolvedValue([]);
  getSettings.mockReset().mockResolvedValue({ homePostcode: null, homePlaceName: null });
  fetchRegions.mockReset().mockResolvedValue([]);
  fetchRegionDriveTimes.mockReset().mockResolvedValue({});
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  localStorage.clear();
});

describe('WindowFirstBriefingContext — allowedLocationIds', () => {
  it('publishes the served list as it came, so a reader holding picks is shaped', async () => {
    await mountWith(body({ allowedLocationIds: [7, 9] }));

    expect(seen.last.allowedLocationIds).toEqual([7, 9]);
  });

  it('publishes an empty array as an empty array: shaped, with zero picks', async () => {
    await mountWith(body({ allowedLocationIds: [] }));

    expect(Array.isArray(seen.last.allowedLocationIds)).toBe(true);
    expect(seen.last.allowedLocationIds).toHaveLength(0);
  });

  it('publishes null when the body carries none: Pro, Admin and enforcement off are not shaped', async () => {
    mockRole = 'PRO_USER';
    await mountWith(body());

    expect(seen.last.allowedLocationIds).toBeNull();
  });

  it('does not key on the role: a LITE reader whose body is unshaped (enforcement off) reads null', async () => {
    mockRole = 'LITE_USER';
    await mountWith(body());

    expect(seen.last.allowedLocationIds).toBeNull();
  });

  it('keeps the list when the role is Admin, because the server said so and the role is not asked', async () => {
    mockRole = 'ADMIN';
    await mountWith(body({ allowedLocationIds: [7] }));

    expect(seen.last.allowedLocationIds).toEqual([7]);
  });

  it('publishes null before any briefing has landed', async () => {
    getDailyBriefing.mockResolvedValue(null);
    await act(async () => {
      render(<WindowFirstBriefingProvider><Probe /></WindowFirstBriefingProvider>);
    });

    expect(seen.last.allowedLocationIds).toBeNull();
  });
});

describe('WindowFirstBriefingContext — a gated place is outside the colour pool and the hatch', () => {
  const scores = [
    {
      locationId: 7, locationName: 'Bamburgh', regionName: 'North East', date: TODAY, targetType: 'SUNSET', rating: 4,
    },
    {
      locationId: 8, locationName: 'Craster', regionName: 'North East', date: TODAY, targetType: 'SUNSET', rating: null, gated: true,
    },
  ];

  it('marks the place gated, leaves it out of the points and the unrated sets', async () => {
    await mountWith(body({ allowedLocationIds: [7] }), scores);

    const [bamburgh, craster] = seen.last.heatSpots;
    expect(bamburgh.states).toEqual(['rated']);
    expect(craster.states).toEqual(['gated']);
    const key = `${TODAY}:SUNSET`;
    expect(seen.last.heatPointSets.get(key).map((p) => p.name)).toEqual(['Bamburgh']);
    expect(seen.last.heatUnratedSets.get(key)).toEqual([]);
  });
});
