import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * The Regional planner's two map doors, driven through the WHOLE chain a press travels
 * (overlay convergence C4): the real `HeatmapGrid` drill-down -> `WindowFirstRegionalPanel` ->
 * `WindowFirstDoors` -> the shell's `showOnMap` -> `onOpenMapTab`. Nothing in the chain is stubbed,
 * so each link has a test that fails when it is cut: a dropped `es.targetType` in the grid, a dropped
 * `onShowOnMap` prop in the panel or the doors, or a deleted `onShowOnMap={showOnMap}` at the shell.
 *
 * <p>The clock is pinned: the grid decides whether a window has passed from it.
 */

vi.mock('../utils/heatField.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    load: vi.fn(() => Promise.resolve({ type: 'FeatureCollection', features: [] })),
    land: vi.fn(() => ({ type: 'FeatureCollection', features: [] })),
    drawGeo: vi.fn(() => ([lng, lat]) => [lng * 10, lat * 10]),
  };
});

const TODAY = '2026-08-08';
const NEXT = '2026-08-09';
const KEY = `${NEXT}:SUNSET`;

const SLOT = {
  locationName: 'Bamburgh', locationId: 7, verdict: 'GO', claudeRating: 4, canopy: false,
  solarEventTime: `${NEXT}T19:30:00`,
};

const DAYS = [{
  date: NEXT,
  eventSummaries: [{
    targetType: 'SUNSET',
    regions: [{
      regionName: 'North East', verdict: 'GO', displayVerdict: 'WORTH_IT', summary: 'Clear skies',
      meanRating: 4, slots: [SLOT],
    }],
  }],
}];

const CARD = {
  key: KEY, date: NEXT, targetType: 'SUNSET', lead: true, kicker: null, when: 'Tomorrow sunset',
  time: '19:30', verdict: 'WORTH_IT', verdictLabel: 'Worth it', bestRating: 4, confidence: 'high',
  badges: [], rows: [], pick: null, spots: [], allSpots: [], reachMeasured: false, reachTotal: 0,
};

const ctx = () => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [], days: DAYS },
  loading: false,
  heatStripCards: [],
  heatSpots: [],
  heatPointSets: new Map(),
  regionSeries: new Map(),
  windowCards: [CARD],
  paneItems: [{ kind: 'card', key: CARD.key, card: CARD }],
  upcomingEvents: [{ date: NEXT, targetType: 'SUNSET' }],
  travelDayDates: new Set(),
  evaluationScores: new Map(),
  scoreIndex: new Map(),
  reachById: new Map(),
  effectiveReachById: new Map(),
  todayStr: TODAY,
  tomorrowStr: NEXT,
  homePlace: 'Newcastle',
  isPro: true,
  isLiteUser: false,
  reachLens: {
    tier: { id: '60', label: '1h', limitMinutes: 60 },
    tierId: '60',
    defaultTier: { id: '60', label: '1h', limitMinutes: 60 },
    defaultTierId: '60',
    weekend: false, overridden: false, locked: false,
    selectTier: vi.fn(), resetToDefault: vi.fn(),
  },
  ratingLens: {
    floor: { id: '4', min: 4, label: '4★+' }, floorId: '4', minRating: 4, selectFloor: vi.fn(),
  },
});

async function renderShell(props = {}) {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  const onOpenMapTab = vi.fn();
  await act(async () => {
    render(
      <WindowFirstShell
        onOpenSettings={vi.fn()}
        onSignOut={vi.fn()}
        onOpenMapTab={onOpenMapTab}
        locations={[{ id: 7, name: 'Bamburgh', locationType: 'SEASCAPE' }]}
        {...props}
      />,
    );
  });
  return { onOpenMapTab };
}

/** Opens the Regional planner door and drills into the one cell. */
async function drillIn() {
  await act(async () => { fireEvent.click(await screen.findByTestId('window-first-door-regional')); });
  await act(async () => { fireEvent.click(await screen.findByTestId('heatmap-cell')); });
  return screen.findByTestId('drill-down-panel');
}

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${TODAY}T10:00:00Z`));
});

afterEach(() => {
  vi.useRealTimers();
  cleanup();
  vi.restoreAllMocks();
});

describe('WindowFirstShell — the Regional planner\'s doors, through the REAL chain (C4)', () => {
  it('the drill-down pin sends an `event` trigger naming the row\'s window and carrying the live Plan lens', async () => {
    const { onOpenMapTab } = await renderShell();
    await drillIn();

    await act(async () => { fireEvent.click(screen.getByTestId('show-on-map-btn')); });

    expect(onOpenMapTab).toHaveBeenCalledExactlyOnceWith({
      kind: 'event', date: NEXT, eventType: 'SUNSET',
      inPlace: false, from: 'plan', minRating: 4, limitMinutes: 60, outlierKeys: expect.any(Set),
    });
  });

  it('a location name in the drill-down sends a `location` trigger', async () => {
    const { onOpenMapTab } = await renderShell();
    await drillIn();

    await act(async () => { fireEvent.click(screen.getByTestId('slot-location-link')); });

    expect(onOpenMapTab).toHaveBeenCalledExactlyOnceWith({
      kind: 'location', locationName: 'Bamburgh', date: NEXT, eventType: 'SUNSET',
      inPlace: false, from: 'plan', minRating: 4, limitMinutes: 60, outlierKeys: expect.any(Set),
    });
  });

  it('draws neither control when the shell has no map door', async () => {
    const { onOpenMapTab } = await renderShell({ onOpenMapTab: undefined });
    await drillIn();

    expect(screen.queryByTestId('show-on-map-btn')).toBeNull();
    expect(screen.queryByTestId('slot-location-link')).toBeNull();
    expect(onOpenMapTab).not.toHaveBeenCalled();
  });
});
