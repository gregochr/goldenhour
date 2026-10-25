import React, { useEffect } from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { cleanup, render, act, screen } from '@testing-library/react';
import { WindowFirstBriefingProvider, useWindowFirstBriefing } from '../context/WindowFirstBriefingContext.jsx';
import MapWindowPanel from '../components/map/MapWindowPanel.jsx';
import MapRegionPanel from '../components/map/MapRegionPanel.jsx';
import WindowControl from '../components/map/WindowControl.jsx';
import WindowSheetDialog from '../components/WindowSheetDialog.jsx';
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
 * The best-bets lock (docs/engineering/pro-features-gate-plan.md §3, §7.1, P1).
 *
 * <p><b>What breaks if these fail:</b> a Lite reader is shown "Best bet" / "Also good" on the Plan
 * matrix, the window popup, the pick dialog, or the Map's pill and panels, because the one place the
 * pick is derived (`buildWindowCards`) no longer honours the lock, or because a Pro reader loses the
 * pick it should see. The payload here STILL carries the pick (the state before P2 ships), so passing
 * proves the client lock on its own.
 */

const TODAY = '2026-08-04';
const NOON_ISH = new Date('2026-08-04T09:00:00Z');

function briefingWithPick() {
  const slot = {
    locationId: 7,
    locationName: 'Bamburgh',
    solarEventTime: `${TODAY}T20:11:00`,
    claudeRating: 4,
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
          slots: [slot],
        }],
        unregioned: [],
        window: {
          verdict: 'WORTH_IT',
          badges: [],
          eventTime: `${TODAY}T20:11:00`,
          pick: {
            kind: 'BEST',
            regionName: 'Northumberland',
            headline: 'Go here',
            detail: 'Open sky.',
            locationName: 'Bamburgh',
            locationId: 7,
          },
        },
      }],
      peak: null,
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

function resetMocks() {
  localStorage.clear();
  latest.current = null;
  getDailyBriefing.mockReset();
  getDailyBriefing.mockResolvedValue(briefingWithPick());
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
}

async function mount(role) {
  mockRole = role;
  render(<WindowFirstBriefingProvider><Probe /></WindowFirstBriefingProvider>);
  await act(async () => { await vi.advanceTimersByTimeAsync(10); });
}

describe('WindowFirstBriefingProvider — the best-bets lock', () => {
  beforeEach(resetMocks);

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it.each(['LITE_USER', null, 'SOMETHING_NEW'])(
    'locks the picks for %s (fail closed) even though the payload still carries one', async (role) => {
      await mount(role);

      expect(latest.current.picksLocked).toBe(true);
      expect(latest.current.windowCards).toHaveLength(1);
      expect(latest.current.windowCards[0].pick).toBeNull();
      const [strip] = latest.current.heatStripCards;
      expect(strip.pickKind).toBeNull();
      expect(strip.pickRegion).toBeNull();
    },
  );

  it.each(['PRO_USER', 'ADMIN'])('leaves the pick untouched for %s', async (role) => {
    await mount(role);

    expect(latest.current.picksLocked).toBe(false);
    expect(latest.current.windowCards[0].pick).toMatchObject({
      kind: 'best', regionName: 'Northumberland', locationName: 'Bamburgh',
    });
    const [strip] = latest.current.heatStripCards;
    expect(strip.pickKind).toBe('best');
    expect(strip.pickRegion).toBe('Northumberland');
  });
});

describe('the pick surfaces, fed a Lite reader\'s cards', () => {
  let card;
  let strip;

  beforeEach(async () => {
    resetMocks();
    await mount('LITE_USER');
    card = latest.current.windowCards[0];
    [strip] = latest.current.heatStripCards;
    cleanup();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('draws no medallion on the Map window panel header', () => {
    const row = {
      id: `solar:${TODAY}:SUNSET`,
      kind: 'solar',
      eventType: 'SUNSET',
      label: 'Tonight sunset',
      dayLabel: 'Tonight',
      time: '20:11',
      confidence: 'medium',
      pickKind: strip.pickKind,
      pickRegion: strip.pickRegion,
    };
    render(
      <MapWindowPanel
        row={row}
        verdict={{ tier: 'WORTH_IT' }}
        note=""
        rows={[]}
        onClose={vi.fn()}
        onSelectRegion={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('wf-win-panel-pick')).toBeNull();
  });

  it('draws no medallion on the Map region panel header', () => {
    const row = {
      id: `solar:${TODAY}:SUNSET`,
      kind: 'solar',
      eventType: 'SUNSET',
      label: 'Tonight sunset',
      time: '20:11',
      pickKind: strip.pickKind,
      pickRegion: strip.pickRegion,
    };
    const region = {
      name: 'Northumberland',
      tier: 'WORTH_IT',
      verdictLabel: 'Worth it',
      meanRating: 4,
      bestRating: 4,
      driveLabel: null,
      placeCount: 1,
      atFourPlus: 1,
    };
    render(
      <MapRegionPanel
        row={row}
        region={region}
        locations={[]}
        onBack={vi.fn()}
        onClose={vi.fn()}
        onZoomToRegion={vi.fn()}
        onOpenLocationSheet={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('wf-reg-panel-pick')).toBeNull();
  });

  it('draws no medallion on the Map pill or in its menu rows', () => {
    const events = [{
      id: `solar:${TODAY}:SUNSET`,
      kind: 'solar',
      eventType: 'SUNSET',
      date: TODAY,
      label: 'Tonight',
      time: '20:11',
      bestRating: 4,
      scored: true,
      badges: [],
      pickKind: strip.pickKind,
      pickRegion: strip.pickRegion,
    }];
    render(<WindowControl events={events} activeIndex={0} onSelect={vi.fn()} verdicts={new Map()} />);
    expect(screen.queryByTestId('wf-win-pick')).toBeNull();
  });

  it('draws no pick chip in the window popup, so the pick dialog has nothing to open from', () => {
    expect(card.pick).toBeNull();
    render(
      <WindowSheetDialog
        card={{
          ...card,
          rows: [],
          allSpots: [],
          spots: [],
          pool: [],
          bestReach: null,
          reachTotal: 0,
          withinReachCount: 0,
        }}
        index={0}
        total={1}
        field={{
          selectedRegion: null, onSelectRegion: vi.fn(), colourMode: 'verdict', geometry: null,
        }}
        topicIndex={new Map()}
        scopeNames={[]}
        todayStr={TODAY}
        onClose={vi.fn()}
        onStep={vi.fn()}
      />,
    );
    expect(screen.queryByTestId('window-sheet-pick')).toBeNull();
    expect(screen.queryByTestId('window-pick-dialog')).toBeNull();
  });
});
