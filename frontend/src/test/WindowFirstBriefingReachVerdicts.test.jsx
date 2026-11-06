import React, { useEffect } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, render, act } from '@testing-library/react';
import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import { getDailyBriefing } from '../api/briefingApi.js';
import { fetchTravelDayRanges } from '../api/travelDayApi.js';
import { getAllEvaluationScores } from '../api/briefingEvaluationApi.js';
import { getReach, getSettings, getReachVerdicts } from '../api/settingsApi.js';
import { fetchRegions, fetchRegionDriveTimes } from '../api/regionApi.js';

vi.mock('../api/briefingApi.js', () => ({ getDailyBriefing: vi.fn() }));
vi.mock('../api/travelDayApi.js', () => ({ fetchTravelDayRanges: vi.fn() }));
vi.mock('../api/briefingEvaluationApi.js', () => ({ getAllEvaluationScores: vi.fn() }));
vi.mock('../api/settingsApi.js', () => ({
  getReach: vi.fn(), getSettings: vi.fn(), getReachVerdicts: vi.fn(),
}));
vi.mock('../api/regionApi.js', () => ({ fetchRegions: vi.fn(), fetchRegionDriveTimes: vi.fn() }));

let mockRole = 'PRO_USER';
let mockUser = 'ann';
vi.mock('../context/AuthContext.jsx', () => ({ useAuth: () => ({ role: mockRole, username: mockUser }) }));

/**
 * The provider's half of the reach-scoped headline (reach-scoped-card-plan R2 step 7): when it asks the server for
 * the tiers' headlines, what it does with the answer, and that a tier tap is a lookup.
 *
 * <p><b>What breaks if these fail:</b> a Lite reader's page calls a Pro endpoint; the same briefing is fetched for
 * on every tier tap; a tap flashes the roster-wide word between two scoped ones; or a response computed from
 * another briefing is worn as this one's headline.
 */

const TODAY = '2026-08-04';
const NOON_ISH = new Date('2026-08-04T09:00:00Z');
const REGION = 'Northumberland';

function briefing(version = 'v1', extra = {}) {
  const slot = {
    locationId: 7,
    locationName: 'Bamburgh',
    solarEventTime: `${TODAY}T20:11:00`,
    claudeRating: 4,
  };
  return {
    generatedAt: `${TODAY}T12:00:00`,
    briefingVersion: version,
    days: [{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: REGION, displayVerdict: 'WORTH_IT', verdict: 'GO', summary: 's', meanRating: 4,
          sampleSufficient: true, slots: [slot],
        }],
        unregioned: [],
        window: {
          verdict: 'WORTH_IT', badges: [], eventTime: `${TODAY}T20:11:00`,
          pick: { kind: 'BEST', regionName: REGION, headline: 'Go here', locationName: 'Bamburgh', locationId: 7 },
        },
      }],
      peak: null,
    }],
    renderedEvents: [{ date: TODAY, targetType: 'SUNSET' }],
    ...extra,
  };
}

function entry(displayVerdict) {
  return {
    date: TODAY, targetType: 'SUNSET', displayVerdict, meanRating: null, topMeanRating: null, confidence: null,
    blanked: false, thinCount: null, pick: null, pickRegion: null, regions: [],
  };
}

function answer(version = 'v1') {
  return {
    measured: true,
    briefingVersion: version,
    tiers: { 45: [entry('STAND_DOWN')], 90: [entry('MAYBE')], 150: [entry('UNRATED')] },
  };
}

const MEASURED = [{ locationId: 7, driveMinutes: 30, distanceMiles: 20, driveStatus: 'MEASURED' }];

const latest = { current: null };
const history = [];
function Probe() {
  const ctx = useWindowFirstBriefing();
  useEffect(() => {
    latest.current = ctx;
    history.push(ctx.windowCards[0]?.verdict ?? null);
  });
  return null;
}

function resetMocks() {
  localStorage.clear();
  latest.current = null;
  history.length = 0;
  getDailyBriefing.mockReset();
  getDailyBriefing.mockResolvedValue(briefing());
  fetchTravelDayRanges.mockReset();
  fetchTravelDayRanges.mockResolvedValue([]);
  getAllEvaluationScores.mockReset();
  getAllEvaluationScores.mockResolvedValue([]);
  getReach.mockReset();
  getReach.mockResolvedValue(MEASURED);
  getSettings.mockReset();
  getSettings.mockResolvedValue({ homePostcode: null, homePlaceName: null });
  getReachVerdicts.mockReset();
  getReachVerdicts.mockResolvedValue(answer());
  fetchRegions.mockReset();
  fetchRegions.mockResolvedValue([]);
  fetchRegionDriveTimes.mockReset();
  fetchRegionDriveTimes.mockResolvedValue({});
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(NOON_ISH);
}

async function settle() {
  await act(async () => { await vi.advanceTimersByTimeAsync(10); });
}

async function mount(role, props = {}) {
  mockRole = role;
  const view = render(<WindowFirstBriefingProvider {...props}><Probe /></WindowFirstBriefingProvider>);
  await settle();
  return view;
}

describe('WindowFirstBriefingProvider — the reach-scoped headline', () => {
  beforeEach(resetMocks);
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it.each(['PRO_USER', 'ADMIN'])('asks once for %s and wears the active tier\'s headline, scoped', async (role) => {
    await mount(role);

    expect(getReachVerdicts).toHaveBeenCalledTimes(1);
    expect(getReachVerdicts).toHaveBeenCalledWith(null);
    const [card] = latest.current.windowCards;
    expect(card.verdict).toBe('STAND_DOWN');
    expect(card.verdictScope).toEqual({ tierMinutes: 45 });
    expect(card.regionVerdict).toBe('WORTH_IT');
    expect(latest.current.heatStripCards[0].verdictScope).toEqual({ tierMinutes: 45 });
  });

  it('shows today\'s served word, without scope, until a matching answer is in hand', async () => {
    let release;
    getReachVerdicts.mockReturnValue(new Promise((resolve) => { release = resolve; }));
    await mount('PRO_USER');

    expect(latest.current.windowCards[0].verdict).toBe('WORTH_IT');
    expect(latest.current.windowCards[0]).not.toHaveProperty('verdictScope');

    await act(async () => { release(answer()); });
    expect(latest.current.windowCards[0].verdict).toBe('STAND_DOWN');
  });

  it('a tier tap is a lookup: no request, and no intermediate word between two scoped ones', async () => {
    await mount('PRO_USER');
    expect(getReachVerdicts).toHaveBeenCalledTimes(1);
    history.length = 0;

    await act(async () => { latest.current.reachLens.selectTier('90'); });
    expect(latest.current.windowCards[0].verdict).toBe('MAYBE');
    expect(latest.current.windowCards[0].verdictScope).toEqual({ tierMinutes: 90 });
    await act(async () => { latest.current.reachLens.selectTier('150'); });
    expect(latest.current.windowCards[0].verdict).toBe('UNRATED');

    expect(getReachVerdicts).toHaveBeenCalledTimes(1);
    // Every committed render in between wore a tier's word, never the roster-wide WORTH_IT.
    expect(history.length).toBeGreaterThan(0);
    expect(history).not.toContain('WORTH_IT');
    expect(new Set(history)).toEqual(new Set(['MAYBE', 'UNRATED']));
  });

  it('under Any the card is the served one, with no scope, and nothing more is asked', async () => {
    await mount('PRO_USER');

    await act(async () => { latest.current.reachLens.selectTier('any'); });

    expect(latest.current.windowCards[0].verdict).toBe('WORTH_IT');
    expect(latest.current.windowCards[0]).not.toHaveProperty('verdictScope');
    expect(getReachVerdicts).toHaveBeenCalledTimes(1);
  });

  it('never asks a Lite reader (the endpoint is 403 for them)', async () => {
    await mount('LITE_USER');

    expect(getReachVerdicts).not.toHaveBeenCalled();
    expect(latest.current.windowCards[0].verdict).toBe('WORTH_IT');
  });

  it('never asks about a body the server shaped by sub-region picks', async () => {
    getDailyBriefing.mockResolvedValue(briefing('v1', { allowedLocationIds: [7] }));

    await mount('PRO_USER');

    expect(getReachVerdicts).not.toHaveBeenCalled();
  });

  it('never asks when the reader has no measured drive for the origin', async () => {
    getReach.mockResolvedValue([{ locationId: 7, driveMinutes: null, driveStatus: 'PENDING' }]);

    await mount('PRO_USER');

    expect(latest.current.reachMeasured).toBe(false);
    expect(getReachVerdicts).not.toHaveBeenCalled();
  });

  it('never asks of a briefing that carries no version', async () => {
    getDailyBriefing.mockResolvedValue({ ...briefing(), briefingVersion: undefined });

    await mount('PRO_USER');

    expect(getReachVerdicts).not.toHaveBeenCalled();
  });

  it('discards an answer computed from another briefing and asks once more', async () => {
    getReachVerdicts.mockResolvedValueOnce(answer('older')).mockResolvedValueOnce(answer('v1'));

    await mount('PRO_USER');

    expect(getReachVerdicts).toHaveBeenCalledTimes(2);
    expect(latest.current.windowCards[0].verdict).toBe('STAND_DOWN');
    expect(history).not.toContain(undefined);
  });

  it('keeps the served word when the second answer is stale too', async () => {
    getReachVerdicts.mockResolvedValue(answer('older'));

    await mount('PRO_USER');

    expect(getReachVerdicts).toHaveBeenCalledTimes(2);
    expect(latest.current.windowCards[0].verdict).toBe('WORTH_IT');
    expect(latest.current.windowCards[0]).not.toHaveProperty('verdictScope');
  });

  it('asks again when the next briefing carries a new version, and not before', async () => {
    const view = await mount('PRO_USER');
    expect(getReachVerdicts).toHaveBeenCalledTimes(1);
    getDailyBriefing.mockResolvedValue(briefing('v2'));
    getReachVerdicts.mockResolvedValue(answer('v2'));

    view.rerender(
      <WindowFirstBriefingProvider subRegionsVersion={1}><Probe /></WindowFirstBriefingProvider>,
    );
    await settle();

    expect(getReachVerdicts).toHaveBeenCalledTimes(2);
    expect(latest.current.windowCards[0].verdictScope).toEqual({ tierMinutes: 45 });
    expect(latest.current.briefing.briefingVersion).toBe('v2');
  });

  it('asks again when the saved home or the drive times change, though the briefing has not', async () => {
    const view = await mount('PRO_USER');
    expect(getReachVerdicts).toHaveBeenCalledTimes(1);

    view.rerender(
      <WindowFirstBriefingProvider homeSettingsVersion={1}><Probe /></WindowFirstBriefingProvider>,
    );
    await settle();
    expect(getReachVerdicts).toHaveBeenCalledTimes(2);

    view.rerender(
      <WindowFirstBriefingProvider homeSettingsVersion={1} driveTimesVersion={1}><Probe /></WindowFirstBriefingProvider>,
    );
    await settle();
    expect(getReachVerdicts).toHaveBeenCalledTimes(3);
  });

  it('asks again for another signed-in user on the same page', async () => {
    const view = await mount('PRO_USER');
    mockUser = 'bob';

    view.rerender(<WindowFirstBriefingProvider><Probe /></WindowFirstBriefingProvider>);
    await settle();
    mockUser = 'ann';

    expect(getReachVerdicts).toHaveBeenCalledTimes(2);
  });

  it('asks for the away origin\'s region when the origin moves, from the base\'s measured drives', async () => {
    fetchRegions.mockResolvedValue([{
      id: 3, name: REGION, baseName: 'Alnwick', baseLat: 55.4, baseLon: -1.7, drivesMeasuredAt: '2026-08-03T00:00:00Z',
    }]);
    fetchRegionDriveTimes.mockResolvedValue({ 3: { 7: 25 } });
    await mount('PRO_USER');
    expect(getReachVerdicts).toHaveBeenCalledTimes(1);

    await act(async () => { latest.current.setOrigin(latest.current.regions[0]); });
    await settle();

    expect(getReachVerdicts).toHaveBeenCalledTimes(2);
    expect(getReachVerdicts).toHaveBeenLastCalledWith(3);
  });
});
