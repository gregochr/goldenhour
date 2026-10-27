/**
 * The Four-days sheet for a place outside a Lite reader's sub-regions, at the shell (sub-regions-plan §11 S5a-3,
 * decisions 10 and 12): the shell hands the sheet `outsidePicks` from the briefing's served `allowedLocationIds`
 * and nothing else, and the sheet's "Choose / Change / Add" button closes the sheet (and any popup under it) before
 * Settings opens, so no third dialog stacks up. The sheet is stubbed; every assertion is about the props the shell
 * hands it and about what is on screen after its opener runs.
 */
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { SubRegionStatusProvider } from '../context/SubRegionStatusContext.jsx';

const SheetStub = { lastProps: null };
vi.mock('../components/LocationFourDaySheet.jsx', () => ({
  default: (props) => {
    SheetStub.lastProps = props;
    return <div data-testid="location-sheet-stub" />;
  },
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

const openSettings = vi.fn();

async function openSheet(overrides) {
  SheetStub.lastProps = null;
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  const base = {
    onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(),
    locations: [], mapPane: <p data-testid="map-pane">map pane</p>,
  };
  const wrap = (shell) => (
    <SubRegionStatusProvider enabled={false} onOpen={openSettings}>{shell}</SubRegionStatusProvider>
  );
  const view = render(wrap(<WindowFirstShell {...base} />));
  fireEvent.click(screen.getByRole('tab', { name: 'Map' }));
  await act(async () => {
    view.rerender(wrap(<WindowFirstShell {...base} locationSheetHandoff={{ id: 42, name: 'Bamburgh', regionName: 'North East', nonce: 1 }} />));
  });
  await screen.findByTestId('location-sheet-stub');
  return view;
}

afterEach(() => { vi.restoreAllMocks(); openSettings.mockClear(); });

describe('WindowFirstShell — the Four-days sheet for a place outside the reader\'s sub-regions', () => {
  it('is handed outsidePicks when the served list is shaped and leaves the place out', async () => {
    await openSheet({ allowedLocationIds: [1, 2] });

    expect(SheetStub.lastProps.outsidePicks).toBe(true);
  });

  it('is handed outsidePicks for a reader with no pick at all (an empty list is shaped)', async () => {
    await openSheet({ allowedLocationIds: [] });

    expect(SheetStub.lastProps.outsidePicks).toBe(true);
  });

  it('is not when the served list names the place', async () => {
    await openSheet({ allowedLocationIds: [42] });

    expect(SheetStub.lastProps.outsidePicks).toBe(false);
  });

  it('is not for a body that is not shaped (Pro, Admin, flag off): no list, no claim', async () => {
    await openSheet({ allowedLocationIds: null });
    expect(SheetStub.lastProps.outsidePicks).toBe(false);
  });

  it('is not when the context carries no list at all', async () => {
    await openSheet({});

    expect(SheetStub.lastProps.outsidePicks).toBe(false);
  });

  it('the sheet\'s opener closes the sheet first, then opens Settings on the picker', async () => {
    await openSheet({ allowedLocationIds: [1] });

    await act(async () => { SheetStub.lastProps.onOpenSubRegions(); });

    expect(screen.queryByTestId('location-sheet-stub')).toBeNull();
    expect(openSettings).toHaveBeenCalledTimes(1);
  });

  it('hands Settings a focus fallback, so closing it never drops focus to <body> when the sheet is gone', async () => {
    await openSheet({ allowedLocationIds: [1] });

    await act(async () => { SheetStub.lastProps.onOpenSubRegions(); });

    const fallback = openSettings.mock.calls[0][0];
    expect(typeof fallback).toBe('function');
    expect(fallback()).toBeInstanceOf(HTMLElement);
    expect(fallback().isConnected).toBe(true);
  });

  it('hands no opener when the app gave the status provider none', async () => {
    SheetStub.lastProps = null;
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx({ allowedLocationIds: [1] }));
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

    expect(SheetStub.lastProps.onOpenSubRegions).toBeNull();
  });
});
