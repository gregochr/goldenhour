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
import { OPEN } from './subRegionFixtures.js';

/**
 * What the briefing provider publishes for the Plan tab's sub-regions treatment (sub-regions-plan §11 S5b-1, B1 and
 * B2): for a body the server SHAPED by the reader's picks, the cards' pool holds the reader's places only, each
 * window's two grounds are published for the thumbnails, and the per-region picks for the rail. For every other body
 * (Pro, Admin, enforcement off) nothing new is published and the cards are what they always were.
 *
 * <p>Run against the provider itself with the API modules mocked, because the join this file is about (the served
 * list into the cards, the spots and the grounds) lives only there.
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
const KEY = `${TODAY}:SUNSET`;

const LOCATIONS = [
  {
    id: 7, name: 'Bamburgh', lat: 55.6, lon: -1.7, regionName: 'North East', locationType: ['LANDSCAPE'], subRegionId: 1, subRegionName: 'Bamburgh to Howick',
  },
  {
    id: 8, name: 'Craster', lat: 55.47, lon: -1.59, regionName: 'North East', locationType: ['LANDSCAPE'], subRegionId: 2, subRegionName: 'Alnwick',
  },
  {
    id: 9, name: 'Malham', lat: 54.07, lon: -2.16, regionName: 'Dales', locationType: ['LANDSCAPE'], subRegionId: 3, subRegionName: 'Malhamdale',
  },
];

/** A shaped body: only Bamburgh is the reader's; the server gated the rest and stripped the window's best. */
function shapedBody() {
  return {
    generatedAt: `${TODAY}T08:00:00`,
    allowedLocationIds: [7],
    days: [{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET',
        regions: [
          {
            regionName: 'North East', displayVerdict: 'WORTH_IT', verdict: 'GO', scoredLocationCount: 1, meanRating: null, picksBestRating: 4,
            slots: [
              { locationId: 7, locationName: 'Bamburgh', solarEventTime: `${TODAY}T20:11:00`, claudeRating: 4, canopy: false },
              { locationId: 8, locationName: 'Craster', solarEventTime: `${TODAY}T20:12:00`, claudeRating: null, canopy: false, gated: true },
            ],
          },
          {
            regionName: 'Dales', displayVerdict: 'MAYBE', verdict: 'MARGINAL', scoredLocationCount: 1, meanRating: null,
            slots: [{ locationId: 9, locationName: 'Malham', solarEventTime: `${TODAY}T20:15:00`, claudeRating: null, canopy: false, gated: true }],
          },
        ],
        unregioned: [],
        shapedLeadRegion: 'North East',
        window: {
          verdict: 'WORTH_IT', badges: [], eventTime: `${TODAY}T20:11:00`, bestRating: null,
        },
      }],
    }],
    renderedEvents: [{ date: TODAY, targetType: 'SUNSET' }],
  };
}

/** The same body as a Pro reader gets it: nothing shaped, every place rated. */
function openBody() {
  const body = shapedBody();
  delete body.allowedLocationIds;
  const [summary] = body.days[0].eventSummaries;
  summary.window.bestRating = 4;
  summary.regions.forEach((region) => {
    region.meanRating = 3;
    region.slots.forEach((s) => { delete s.gated; s.claudeRating = 3; });
  });
  delete summary.shapedLeadRegion;
  return body;
}

const SCORES = [
  { locationId: 7, locationName: 'Bamburgh', date: TODAY, targetType: 'SUNSET', rating: 4 },
  { locationId: 8, locationName: 'Craster', date: TODAY, targetType: 'SUNSET', rating: null, gated: true },
  { locationId: 9, locationName: 'Malham', date: TODAY, targetType: 'SUNSET', rating: null, gated: true },
];

const onValue = vi.fn();
function Probe() {
  onValue(useWindowFirstBriefing());
  return null;
}

async function mountWith(briefing, scores = SCORES) {
  getDailyBriefing.mockResolvedValue(briefing);
  getAllEvaluationScores.mockResolvedValue(scores);
  await act(async () => {
    render(<WindowFirstBriefingProvider locations={LOCATIONS}><Probe /></WindowFirstBriefingProvider>);
  });
  await act(async () => { await vi.advanceTimersByTimeAsync(0); });
}

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: false });
  vi.setSystemTime(NOON);
  localStorage.clear();
  onValue.mockClear();
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

const last = () => onValue.mock.calls.at(-1)[0];

describe('WindowFirstBriefingProvider: a shaped body on the Plan', () => {
  it('builds the cards\' pool from the reader\'s places, the window best stripped (B1 and B2 together)', async () => {
    await mountWith(shapedBody());

    const [card] = last().windowCards;
    expect(card.bestRating).toBeNull();
    expect(card.pool.map((s) => s.locationName)).toEqual(['Bamburgh']);
    expect(card.allSpots.map((s) => s.locationName)).toEqual(['Bamburgh', 'Craster', 'Malham']);
    expect(card.outsideCount).toBe(2);
    expect(card.reachTotal).toBe(1);
    expect(card.bestReach).toMatchObject({ locationName: 'Bamburgh', rating: 4 });
    expect(last().heatStripCards[0]).toMatchObject({ picksScoped: true, outsideCount: 2 });
  });

  it('publishes each window\'s two grounds once the scores have loaded, and that Bamburgh is rated', async () => {
    await mountWith(shapedBody());

    const ground = last().heatGroundSets.get(KEY);
    expect(ground.outside.map((p) => p.id).sort()).toEqual([8, 9]);
    expect(ground.allowed.map((p) => p.id)).toEqual([7]);
    expect(ground.rated).toBe(true);
  });

  it('knows nothing about rating until the scores have loaded', async () => {
    getDailyBriefing.mockResolvedValue(shapedBody());
    getAllEvaluationScores.mockReturnValue(new Promise(() => {}));
    await act(async () => {
      render(<WindowFirstBriefingProvider locations={LOCATIONS}><Probe /></WindowFirstBriefingProvider>);
    });
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });

    const ground = last().heatGroundSets.get(KEY);
    expect(ground.rated).toBeNull();
    // The two grounds do not depend on scores: they are the served list's decision.
    expect(ground.outside).toHaveLength(2);
  });

  it('publishes the picks across each region for the rail', async () => {
    await mountWith(shapedBody());

    const { regionPicks } = last();
    expect(regionPicks.get('North East')).toMatchObject({ allowedPlaces: 1, places: 2 });
    expect(regionPicks.get('Dales')).toMatchObject({ allowedPlaces: 0, places: 1 });
  });

  it('gates a place the served list leaves out even when its row carries no marker', async () => {
    const body = shapedBody();
    delete body.days[0].eventSummaries[0].regions[0].slots[1].gated;
    body.days[0].eventSummaries[0].regions[0].slots[1].claudeRating = 5;
    await mountWith(body);

    expect(last().windowCards[0].pool.map((s) => s.locationName)).toEqual(['Bamburgh']);
  });

  it('is shaped for a reader with no pick: every place outside, none in the pool', async () => {
    const body = shapedBody();
    body.allowedLocationIds = [];
    await mountWith(body);

    const [card] = last().windowCards;
    expect(card.pool).toEqual([]);
    expect(card.outsideCount).toBe(3);
    expect(last().heatGroundSets.get(KEY).allowed).toEqual([]);
    expect(last().heatGroundSets.get(KEY).outside).toHaveLength(3);
  });
});

describe('WindowFirstBriefingProvider: every other body is untouched', () => {
  it.each(['PRO_USER', 'ADMIN'])('%s: no grounds, no region picks, and cards without the shaped fields', async (role) => {
    mockRole = role;
    await mountWith(openBody(), SCORES.map((s) => ({ ...s, rating: 3, gated: undefined })));

    expect(last().heatGroundSets.size).toBe(0);
    expect(last().regionPicks).toBeNull();
    const [card] = last().windowCards;
    expect(card).not.toHaveProperty('outsideCount');
    expect(card).not.toHaveProperty('picksScoped');
    expect(card.pool).toEqual(card.allSpots);
    expect(card.allSpots.every((s) => !('gated' in s))).toBe(true);
    expect(card.bestRating).toBe(4);
  });
});
