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
 * The scores lock (docs/engineering/pro-features-gate-plan.md §5.1, S1).
 *
 * <p><b>What breaks if these fail:</b> a Lite reader's page is handed a Fiery Sky / Golden Hour score
 * or a per-location sentence by one of the context's published forms (the score rows, the name-keyed
 * map, the briefing slots), or a Pro reader's briefing is re-copied on every render so every memo chain
 * downstream re-runs.
 */

const NOON_ISH = new Date('2026-08-04T09:00:00Z');
const TODAY = '2026-08-04';

/** The briefing the server sends today: every slot still carries the potentials and the prose. */
function briefingWithScores() {
  const slot = {
    locationId: 7,
    locationName: 'Bamburgh',
    solarEventTime: `${TODAY}T20:11:00`,
    claudeRating: 4,
    fierySkyPotential: 71,
    goldenHourPotential: 64,
    claudeSummary: 'Open sky to the east.',
    claudeHeadline: 'A good one',
  };
  return {
    generatedAt: `${TODAY}T12:00:00`,
    days: [{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [{
          regionName: 'Northumberland',
          displayVerdict: 'WORTH_IT',
          verdict: 'GO',
          summary: 'clear at 3 of 7',
          glossHeadline: 'Breaking clear',
          glossDetail: 'Low cloud clears.',
          slots: [slot],
        }],
        unregioned: [{ ...slot, locationId: 8, locationName: 'Unregioned' }],
        window: { verdict: 'WORTH_IT', badges: [], eventTime: `${TODAY}T20:11:00` },
      }],
      peak: null,
    }],
    renderedEvents: [{ date: TODAY, targetType: 'SUNSET' }],
  };
}

const SCORE_ROW = {
  locationId: 7,
  regionName: 'Northumberland',
  locationName: 'Bamburgh',
  date: TODAY,
  targetType: 'SUNSET',
  rating: 4,
  displayVerdict: 'WORTH_IT',
  fierySkyPotential: 71,
  goldenHourPotential: 64,
  summary: 'Open sky to the east.',
  triageReason: null,
  triageMessage: null,
  goldenHourStart: `${TODAY}T18:57:00`,
};

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

describe('WindowFirstBriefingProvider — the scores lock', () => {
  beforeEach(() => {
    localStorage.clear();
    latest.current = null;
    getDailyBriefing.mockReset();
    fetchTravelDayRanges.mockReset();
    fetchTravelDayRanges.mockResolvedValue([]);
    getAllEvaluationScores.mockReset();
    getAllEvaluationScores.mockResolvedValue([{ ...SCORE_ROW }]);
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

  describe('a Lite reader', () => {
    beforeEach(() => { mockRole = 'LITE_USER'; });

    it('is locked, and its score rows keep the rating but lose the scores and the sentence', async () => {
      getDailyBriefing.mockResolvedValue(briefingWithScores());
      renderProvider();
      await settle();

      expect(latest.current.scoresLocked).toBe(true);
      expect(latest.current.scoreRows).toHaveLength(1);
      const [row] = latest.current.scoreRows;
      expect(row.rating).toBe(4);
      expect(row.displayVerdict).toBe('WORTH_IT');
      expect(row.goldenHourStart).toBe(`${TODAY}T18:57:00`);
      expect(row).not.toHaveProperty('fierySkyPotential');
      expect(row).not.toHaveProperty('goldenHourPotential');
      expect(row).not.toHaveProperty('summary');
    });

    it('derives the name-keyed map and the index from the stripped rows', async () => {
      getDailyBriefing.mockResolvedValue(briefingWithScores());
      renderProvider();
      await settle();

      const entry = latest.current.evaluationScores.get(`Northumberland|${TODAY}|SUNSET|Bamburgh`);
      expect(entry.rating).toBe(4);
      expect(entry.fierySkyPotential).toBeUndefined();
      expect(entry.goldenHourPotential).toBeUndefined();
      expect(entry.summary).toBeUndefined();
    });

    it('serves a briefing whose slots lack the scores and the prose, region prose intact', async () => {
      getDailyBriefing.mockResolvedValue(briefingWithScores());
      renderProvider();
      await settle();

      const es = latest.current.briefing.days[0].eventSummaries[0];
      const [slot] = es.regions[0].slots;
      expect(slot.claudeRating).toBe(4);
      expect(slot.locationName).toBe('Bamburgh');
      for (const field of ['fierySkyPotential', 'goldenHourPotential', 'claudeSummary', 'claudeHeadline']) {
        expect(slot).not.toHaveProperty(field);
        expect(es.unregioned[0]).not.toHaveProperty(field);
      }
      expect(es.regions[0].glossHeadline).toBe('Breaking clear');
      expect(es.regions[0].glossDetail).toBe('Low cloud clears.');
      expect(es.regions[0].summary).toBe('clear at 3 of 7');
      expect(es.window.verdict).toBe('WORTH_IT');
    });
  });

  describe('a Pro reader', () => {
    beforeEach(() => { mockRole = 'PRO_USER'; });

    it('is unlocked and keeps every score and sentence', async () => {
      getDailyBriefing.mockResolvedValue(briefingWithScores());
      renderProvider();
      await settle();

      expect(latest.current.scoresLocked).toBe(false);
      const [row] = latest.current.scoreRows;
      expect(row.fierySkyPotential).toBe(71);
      expect(row.goldenHourPotential).toBe(64);
      expect(row.summary).toBe('Open sky to the east.');
      const entry = latest.current.evaluationScores.get(`Northumberland|${TODAY}|SUNSET|Bamburgh`);
      expect(entry.fierySkyPotential).toBe(71);
      expect(entry.summary).toBe('Open sky to the east.');
      const [slot] = latest.current.briefing.days[0].eventSummaries[0].regions[0].slots;
      expect(slot.fierySkyPotential).toBe(71);
      expect(slot.claudeSummary).toBe('Open sky to the east.');
      expect(slot.claudeHeadline).toBe('A good one');
    });

    it('publishes the very object that was fetched, so no PRO memo chain re-runs', async () => {
      const payload = briefingWithScores();
      getDailyBriefing.mockResolvedValue(payload);
      renderProvider();
      await settle();

      expect(latest.current.briefing).toBe(payload);
    });

    it('keeps the score rows by identity too', async () => {
      const rows = [{ ...SCORE_ROW }];
      getDailyBriefing.mockResolvedValue(briefingWithScores());
      getAllEvaluationScores.mockResolvedValue(rows);
      renderProvider();
      await settle();

      expect(latest.current.scoreRows).toBe(rows);
    });
  });

  it('an admin is unlocked', async () => {
    mockRole = 'ADMIN';
    getDailyBriefing.mockResolvedValue(briefingWithScores());
    renderProvider();
    await settle();

    expect(latest.current.scoresLocked).toBe(false);
  });

  it('fails closed on an unknown role', async () => {
    mockRole = null;
    getDailyBriefing.mockResolvedValue(briefingWithScores());
    renderProvider();
    await settle();

    expect(latest.current.scoresLocked).toBe(true);
    expect(latest.current.scoreRows[0]).not.toHaveProperty('fierySkyPotential');
  });

  it('a provider-less read is unlocked, so every bare test render stays as it was', () => {
    render(<Probe />);

    expect(latest.current.scoresLocked).toBe(false);
  });
});
