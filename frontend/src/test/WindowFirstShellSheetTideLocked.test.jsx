/**
 * The Four-days sheet is gated at the shell (docs/engineering/tide-pro-gate-plan.md §2, Q2): the shell
 * builds the sheet's tide index from `briefing.days`, and a locked (LITE) reader's sheet is handed
 * none, so it can draw no `TideFitBlock` and no next-fit line. The sheet is stubbed; every assertion
 * is about the `tideAlignmentIndex` prop the shell hands it.
 */
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

const SheetStub = { lastProps: null };
vi.mock('../components/LocationFourDaySheet.jsx', () => ({
  default: (props) => {
    SheetStub.lastProps = props;
    return <div data-testid="location-sheet-stub" />;
  },
}));
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

const TODAY = '2026-08-08';

const card = () => ({
  key: `${TODAY}:SUNSET`, date: TODAY, targetType: 'SUNSET', lead: true, kicker: 'Tonight', when: 'Sunset',
  time: '20:41', verdict: 'WORTH_IT', verdictLabel: 'Worth it', bestRating: 5, confidence: 'high',
  badges: [], rows: [], pick: null, spots: [], allSpots: [], reachTotal: 0,
});

const DAYS = [{
  date: TODAY,
  eventSummaries: [{
    targetType: 'SUNSET',
    regions: [],
    unregioned: [],
    window: {
      tideFacts: [{
        locationId: 42, locationName: 'Bamburgh', tideState: 'HIGH', tideAligned: false, tideShortfall: 'LOW',
        tideFitPhrase: 'Wrong water',
      }],
    },
  }],
}];

const ctx = (overrides = {}) => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [], days: DAYS },
  loading: false,
  heatStripCards: [],
  heatSpots: [],
  heatPointSets: new Map(),
  windowCards: [card()],
  paneItems: [{ kind: 'card', key: card().key, card: card() }],
  upcomingEvents: [],
  travelDayDates: new Set(),
  evaluationScores: new Map(),
  scoreIndex: new Map(),
  scoreRows: [],
  reachById: new Map(),
  todayStr: TODAY,
  tomorrowStr: '2026-08-09',
  homePlace: 'Newcastle',
  isPro: true,
  tideLocked: false,
  isLiteUser: false,
  reachLens: {
    tier: { id: '45', label: '45 min', limitMinutes: 45 },
    tierId: '45',
    defaultTier: { id: '45', label: '45 min', limitMinutes: 45 },
    defaultTierId: '45',
    weekend: false,
    overridden: false,
    locked: false,
    selectTier: vi.fn(),
    resetToDefault: vi.fn(),
  },
  ratingLens: {
    floor: { id: 'any', min: null, label: 'Any rating' },
    floorId: 'any',
    minRating: null,
    selectFloor: vi.fn(),
  },
  ...overrides,
});

async function openSheet(overrides) {
  SheetStub.lastProps = null;
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  const base = {
    onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(),
    locations: [], mapPane: <p data-testid="map-pane">map pane</p>,
  };
  const view = render(<WindowFirstShell {...base} />);
  fireEvent.click(screen.getByRole('tab', { name: 'Map' }));
  await act(async () => {
    view.rerender(<WindowFirstShell {...base} locationSheetHandoff={{ id: 42, name: 'Bamburgh', regionName: 'North East', nonce: 1 }} />);
  });
  await screen.findByTestId('location-sheet-stub');
  return view;
}

afterEach(() => vi.restoreAllMocks());

describe('WindowFirstShell — the Four-days sheet carries no tide match for a locked reader', () => {
  it('LITE: the sheet is handed no tide alignment index', async () => {
    await openSheet({ isPro: false, tideLocked: true });
    expect(SheetStub.lastProps.tideAlignmentIndex).toBeNull();
  });

  it('PRO (the positive half): the sheet is handed the index built from the briefing', async () => {
    await openSheet({ isPro: true, tideLocked: false });
    expect(SheetStub.lastProps.tideAlignmentIndex).not.toBeNull();
    expect(SheetStub.lastProps.tideAlignmentIndex.byId.size).toBeGreaterThan(0);
  });
});
