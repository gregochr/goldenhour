import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  render, screen, fireEvent, act, cleanup, waitFor, within,
} from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { SubRegionStatusProvider } from '../context/SubRegionStatusContext.jsx';
import { buildGroundSets } from '../utils/pickScope.js';
import { regionPickSummaries } from '../utils/pickGeography.js';
import { OPEN } from './subRegionFixtures.js';

vi.mock('../api/subRegionApi.js', () => ({ getSubRegions: vi.fn(), saveSubRegions: vi.fn() }));
import { getSubRegions } from '../api/subRegionApi.js';

/**
 * The shell's half of the popup's sub-regions rail (sub-regions-plan §11 S5b-1, handoff task 6).
 *
 * <p><b>What breaks if these fail:</b> the rail's S4 action opens Settings OVER the popup (three dialogs once its
 * confirm opens) instead of closing the popup first; the popup is handed a body that is not shaped when it is (or
 * the reverse); the strip's picks facts leak to a Pro, Admin or flag-off reader; the lens bar counts places outside
 * the picks.
 *
 * <p>Scoped like {@code WindowFirstShellRegion}: the components take everything as props, so what only the shell can
 * be wrong about is the joins, and they are asserted here against the real strip and the real popup.
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
const KEY = `${TODAY}:SUNSET`;
const EVENTS = [{ date: TODAY, targetType: 'SUNSET' }];

const REGIONS = [
  {
    regionName: 'Coast', displayVerdict: 'WORTH_IT', summary: 'A clean eastern horizon.', meanRating: null, bestRating: null,
    picksBestRating: 4, slots: [{ canopy: false }],
  },
  {
    regionName: 'Dales', displayVerdict: 'MAYBE', summary: 'A thin high veil.', meanRating: null, bestRating: null,
    slots: [{ canopy: false }],
  },
];

const spotOf = (id, name, regionName, rating, gated) => ({
  key: String(id), locationId: id, locationName: name, regionName, rating, driveMinutes: null, ...(gated ? { gated: true } : {}),
});
const SPOTS = [
  spotOf(1, 'Bamburgh', 'Coast', 4, false),
  spotOf(2, 'Malham', 'Dales', null, true),
  spotOf(3, 'Gordale', 'Dales', null, true),
];

const card = {
  key: KEY, date: TODAY, targetType: 'SUNSET', lead: true, kicker: 'Tonight', when: 'Sunset', time: '20:41',
  verdict: 'WORTH_IT', verdictLabel: 'Worth it', bestRating: null, confidence: 'high', badges: [], rows: [],
  pick: null, spots: SPOTS, allSpots: SPOTS, pool: [SPOTS[0]], bestReach: SPOTS[0], reachMeasured: false,
  reachTotal: 1, reachedTotal: 1, outsideCount: 2, picksScoped: true,
};

const HEAT_SPOTS = [
  {
    id: 1, name: 'Bamburgh', lat: 6, lng: 4, regionName: 'Coast', rid: 'Coast', skySubject: true, scores: [4], states: ['rated'],
  },
  {
    id: 2, name: 'Malham', lat: 6, lng: 24, regionName: 'Dales', rid: 'Dales', skySubject: true, scores: [null], states: ['gated'],
  },
  {
    id: 3, name: 'Gordale', lat: 26, lng: 24, regionName: 'Dales', rid: 'Dales', skySubject: true, scores: [null], states: ['gated'],
  },
];

const ctx = (overrides = {}) => ({
  briefing: {
    generatedAt: `${TODAY}T12:00:00`,
    hotTopics: [],
    allowedLocationIds: [1],
    days: [{
      date: TODAY,
      eventSummaries: [{
        targetType: 'SUNSET', regions: REGIONS, unregioned: [], shapedLeadRegion: 'Coast',
      }],
    }],
  },
  loading: false,
  allowedLocationIds: [1],
  heatStripCards: [{
    key: KEY, date: TODAY, targetType: 'SUNSET', dow: 'Sat', sunrise: false, label: 'Tonight Sunset', time: '20:41',
    verdict: 'WORTH_IT', verdictLabel: 'Worth it', bestRating: null, pool: [SPOTS[0]], bestReach: SPOTS[0],
    picksScoped: true, outsideCount: 2, reachMeasured: false, badges: [], away: false, confidence: 'high',
  }],
  heatSpots: HEAT_SPOTS,
  heatPointSets: new Map([[KEY, [{ lat: 6, lng: 4, rid: 'Coast', r: [4] }]]]),
  heatGroundSets: buildGroundSets(HEAT_SPOTS, EVENTS, true),
  regionPicks: regionPickSummaries(HEAT_SPOTS, [1]),
  regionSeries: new Map(),
  windowCards: [card],
  paneItems: [{ kind: 'card', key: KEY, card }],
  upcomingEvents: [],
  travelDayDates: new Set(),
  evaluationScores: new Map(),
  scoreIndex: new Map(),
  reachById: new Map(),
  todayStr: TODAY,
  tomorrowStr: '2026-08-09',
  homePlace: 'Newcastle',
  isPro: false,
  isLiteUser: true,
  reachLens: {
    tier: { id: 'any', label: 'Any', limitMinutes: null },
    tierId: 'any',
    defaultTier: { id: 'any', label: 'Any', limitMinutes: null },
    defaultTierId: 'any',
    weekend: false,
    overridden: false,
    locked: true,
    selectTier: vi.fn(),
    resetToDefault: vi.fn(),
  },
  ratingLens: {
    floor: { id: 'any', min: null, label: 'Any rating' }, floorId: 'any', minRating: null, selectFloor: vi.fn(),
  },
  ...overrides,
});

const openSettings = vi.fn();
let originalGetContext;

async function renderShell(overrides = {}, { opener = openSettings, enabled = true } = {}) {
  originalGetContext = HTMLCanvasElement.prototype.getContext;
  HTMLCanvasElement.prototype.getContext = () => ({});
  getSubRegions.mockResolvedValue(OPEN);
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  await import('../components/WindowSheetDialog.jsx');
  await act(async () => {
    render(
      <SubRegionStatusProvider enabled={enabled} onOpen={opener}>
        <WindowFirstShell
          onOpenSettings={vi.fn()}
          onSignOut={vi.fn()}
          onShowOnMap={vi.fn()}
          locations={[]}
        />
      </SubRegionStatusProvider>,
    );
  });
}

afterEach(() => {
  if (originalGetContext) HTMLCanvasElement.prototype.getContext = originalGetContext;
  cleanup();
  vi.restoreAllMocks();
  openSettings.mockClear();
});

async function openWindow() {
  await screen.findByTestId('wf-heat-strip');
  await act(async () => { fireEvent.click(screen.getByTestId('wf-heat-card')); });
  return screen.findByTestId('window-sheet');
}

describe('WindowFirstShell: the sub-regions rail of the popup', () => {
  it('hands the strip the picks facts, so its key and note draw for a shaped body', async () => {
    await renderShell();
    await screen.findByTestId('wf-heat-strip');

    expect(screen.getByTestId('wf-heat-picks-note'))
      .toHaveTextContent('Colour shows the 1 place in your sub-regions. Pro rates all 3.');
  });

  it('hands the strip nothing for a Pro, Admin or flag-off body', async () => {
    await renderShell({ allowedLocationIds: null, heatGroundSets: new Map(), regionPicks: null });
    await screen.findByTestId('wf-heat-strip');

    expect(screen.queryByTestId('wf-heat-picks')).toBeNull();
  });

  it('builds the popup\'s rail from the shaped rows: picks first, no window star, and the label says so', async () => {
    await renderShell();
    const sheet = await openWindow();

    expect(within(sheet).getByTestId('wf-region-rail-label').textContent).toBe('By region · your sub-regions first');
    expect(within(sheet).getAllByTestId('wf-region-cell').map((c) => c.dataset.region)).toEqual(['all', 'Coast', 'Dales']);
    expect(within(sheet).getAllByTestId('wf-region-cell')[0]).toHaveTextContent('1 in your sub-regions · 2 outside');
    expect(within(sheet).getAllByTestId('wf-region-cell')[2]).toHaveTextContent('outside your sub-regions');
  });

  it('closes the popup FIRST when the rail\'s action runs, so Settings is the second dialog, never a third', async () => {
    await renderShell();
    const sheet = await openWindow();
    await act(async () => {
      fireEvent.click(within(sheet).getAllByTestId('wf-region-cell')[2]);
    });

    const action = await screen.findByTestId('wf-region-picks-action');
    expect(screen.getAllByRole('dialog')).toHaveLength(1);
    await act(async () => { fireEvent.click(action); });

    expect(screen.queryByTestId('window-sheet')).toBeNull();
    expect(screen.queryAllByRole('dialog')).toHaveLength(0);
    expect(openSettings).toHaveBeenCalledTimes(1);
    // The return-focus fallback is the selected tab's node, as the location sheet's route hands it.
    expect(typeof openSettings.mock.calls[0][0]).toBe('function');
  });

  it('draws the S4 action nowhere when the app gave no way to open Settings', async () => {
    await renderShell({}, { opener: null });
    const sheet = await openWindow();
    await act(async () => {
      fireEvent.click(within(sheet).getAllByTestId('wf-region-cell')[2]);
    });

    await waitFor(() => expect(getSubRegions).toHaveBeenCalled());
    expect(screen.getByTestId('wf-prose-outside')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-region-picks-action')).toBeNull();
  });

  it('counts the lens bar over the reader\'s places only', async () => {
    await renderShell();
    await screen.findByTestId('wf-heat-strip');

    // Three spots are drawn and one is the reader's: the bar says one place, never three.
    const count = document.querySelector('.wf-lens-res, .wf-lens-count');
    expect(count).toHaveTextContent(/\b1\b/);
    expect(count).not.toHaveTextContent(/\b3\b/);
  });

  it('Escape closes the popup one layer at a time, the region door first', async () => {
    await renderShell();
    const sheet = await openWindow();
    await act(async () => {
      fireEvent.click(within(sheet).getAllByTestId('wf-region-cell')[2]);
    });

    fireEvent.click(await screen.findByTestId('wf-subregions-region-upsell-trigger'));
    expect(screen.getByTestId('wf-subregions-region-upsell-popover')).toBeInTheDocument();
    fireEvent.keyDown(screen.getByTestId('wf-subregions-region-upsell-popover'), { key: 'Escape' });

    expect(screen.queryByTestId('wf-subregions-region-upsell-popover')).toBeNull();
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByTestId('window-sheet')).toBeNull());
  });
});
