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
import { getSubRegions } from '../api/subRegionApi.js';
import { useSubRegionStatus } from '../context/SubRegionStatusContext.jsx';
import { OPEN } from './subRegionFixtures.js';

/**
 * The briefing provider's S4 status read (sub-regions-plan §11 S5a-3, decision 9): made only while the served body is
 * shaped (`allowedLocationIds` an array), read again when `subRegionsVersion` moves, and handed the app's opener for
 * Settings. The role is never consulted.
 */

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));
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

const onStatus = vi.fn();
function Probe() {
  onValue(useWindowFirstBriefing());
  onStatus(useSubRegionStatus());
  return null;
}

async function mountWith(briefing, props = {}) {
  getDailyBriefing.mockResolvedValue(briefing);
  getAllEvaluationScores.mockResolvedValue([]);
  let view;
  await act(async () => {
    view = render(<WindowFirstBriefingProvider locations={LOCATIONS} {...props}><Probe /></WindowFirstBriefingProvider>);
  });
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
  return view;
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
  onStatus.mockClear();
  getSubRegions.mockReset().mockResolvedValue(OPEN);
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

const lastStatus = () => onStatus.mock.calls.at(-1)[0];

describe('WindowFirstBriefingProvider — the shared S4 status read', () => {
  it('reads the status when the served body is shaped, and publishes it', async () => {
    await mountWith(body({ allowedLocationIds: [7] }));

    expect(getSubRegions).toHaveBeenCalledTimes(1);
    expect(lastStatus().state).toEqual(OPEN);
  });

  it('reads once more for a reader with no pick (an empty list is shaped)', async () => {
    await mountWith(body({ allowedLocationIds: [] }));

    expect(getSubRegions).toHaveBeenCalledTimes(1);
  });

  it.each(['LITE_USER', 'PRO_USER', 'ADMIN'])('%s: reads nothing for a body that is not shaped', async (role) => {
    mockRole = role;
    await mountWith(body());

    expect(getSubRegions).not.toHaveBeenCalled();
    expect(lastStatus().state).toBeNull();
  });

  it('reads again when subRegionsVersion moves, and not before', async () => {
    const view = await mountWith(body({ allowedLocationIds: [7] }), { subRegionsVersion: 0 });
    expect(getSubRegions).toHaveBeenCalledTimes(1);

    await act(async () => {
      view.rerender(
        <WindowFirstBriefingProvider locations={LOCATIONS} subRegionsVersion={1}><Probe /></WindowFirstBriefingProvider>,
      );
    });
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });

    expect(getSubRegions.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it('hands on the opener the app gave it', async () => {
    const onOpenSubRegions = vi.fn();
    await mountWith(body({ allowedLocationIds: [7] }), { onOpenSubRegions });

    lastStatus().openSubRegions();

    expect(onOpenSubRegions).toHaveBeenCalledTimes(1);
  });

  it('has no opener when the app gave none', async () => {
    await mountWith(body({ allowedLocationIds: [7] }));

    expect(lastStatus().openSubRegions).toBeNull();
  });
});
