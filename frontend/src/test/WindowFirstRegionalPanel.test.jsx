import React from 'react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import WindowFirstRegionalPanel from '../components/WindowFirstRegionalPanel.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { getAstroConditions } from '../api/astroApi.js';

vi.mock('../api/astroApi.js', () => ({ getAstroConditions: vi.fn() }));

// The grid is a 900-line v1 component with its own suite. What this file protects is the WIRING —
// the props the re-parenting has to get right, several of which have no other guard because this is
// the grid's only second call site.
vi.mock('../components/HeatmapGrid.jsx', () => ({
  default: (props) => {
    HeatmapGrid.lastProps = props;
    return <div data-testid="stub-heatmap" />;
  },
}));
const HeatmapGrid = { lastProps: null };

const LOCATIONS = [
  { id: 1, name: 'Bamburgh Castle', locationType: 'SEASCAPE' },
  { id: 2, name: 'Simonside', locationType: 'LANDSCAPE' },
  { id: 3, name: 'Buttermere', locationType: 'LANDSCAPE' },
];

const REACH = new Map([
  [1, { driveMinutes: 66, distanceMiles: 47 }],
  // Simonside is in the roster but has no drive time — the normal state for a user with no home.
  [2, { driveMinutes: null, distanceMiles: null }],
]);

const DAYS = [{
  date: '2026-08-04',
  eventSummaries: [{
    targetType: 'SUNSET',
    regions: [
      { regionName: 'The Lake District', verdict: 'STANDDOWN', slots: [] },
      { regionName: 'Northumberland & Tyneside', verdict: 'GO', slots: [] },
      { regionName: 'North York Moors & Coast', verdict: 'GO', slots: [] },
    ],
  }],
}];

const ctx = (overrides = {}) => ({
  briefing: { days: DAYS },
  upcomingEvents: [{ date: '2026-08-04', targetType: 'SUNSET' }],
  travelDayDates: new Set(['2026-08-06']),
  evaluationScores: new Map([['k', { rating: 4 }]]),
  reachById: REACH,
  isPro: true,
  todayStr: '2026-08-04',
  tomorrowStr: '2026-08-05',
  ...overrides,
});

const renderPanel = (overrides = {}, props = {}) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  return render(<WindowFirstRegionalPanel locations={LOCATIONS} {...props} />);
};

beforeEach(() => {
  HeatmapGrid.lastProps = null;
  // `restoreAllMocks` does not touch a `vi.fn()` created in a module factory, so the call count
  // carries across tests — which silently turned "asks once per date" into "has ever been asked".
  getAstroConditions.mockReset();
  getAstroConditions.mockResolvedValue([]);
  localStorage.clear();
});
afterEach(() => vi.restoreAllMocks());

describe('WindowFirstRegionalPanel', () => {
  it('renders the grid it re-parents', () => {
    renderPanel();
    expect(screen.getByTestId('stub-heatmap')).toBeInTheDocument();
  });

  describe('the reach join', () => {
    it('keys drive times by location NAME, which is the contract the grid declares', () => {
      // This arm holds reach keyed by location id — plan §2.2's separate contract, kept off the
      // ETagged briefing payload. The grid wants names. Getting the join backwards renders a grid
      // with no drive times at all and nothing else notices.
      renderPanel();
      expect(HeatmapGrid.lastProps.driveMap.get('Bamburgh Castle')).toBe(66);
    });

    it('omits a location whose drive time is unknown rather than entering a zero', () => {
      // §2.5 rule 1: an unknown drive time is unknown, not "close". A 0 would sort it first.
      renderPanel();
      expect(HeatmapGrid.lastProps.driveMap.has('Simonside')).toBe(false);
    });

    it('omits a location the reach response did not mention at all', () => {
      renderPanel();
      expect(HeatmapGrid.lastProps.driveMap.has('Buttermere')).toBe(false);
    });

    it('hands over an empty map when no reach has arrived, which is the first-run state', () => {
      renderPanel({ reachById: new Map() });
      expect(HeatmapGrid.lastProps.driveMap.size).toBe(0);
    });
  });

  it('maps each location to its type, for the grid\'s type icons', () => {
    renderPanel();
    expect(HeatmapGrid.lastProps.typeMap.get('Bamburgh Castle')).toBe('SEASCAPE');
    expect(HeatmapGrid.lastProps.typeMap.get('Simonside')).toBe('LANDSCAPE');
  });

  it('orders the region rows best verdict first, then alphabetically', () => {
    // The two GO regions come before the STANDDOWN one, and tie-break A–Z. A grid that lost the
    // sort would still render — it would just bury tonight's best region below a dead one.
    renderPanel();
    expect(HeatmapGrid.lastProps.sortedRegions).toEqual([
      'North York Moors & Coast',
      'Northumberland & Tyneside',
      'The Lake District',
    ]);
  });

  it('pins the quality tier where the v1 arm pins it', () => {
    // The slider was retired and the tier is a seam. A different value here would make the same
    // grid show a different number of rows in the two arms — which is the comparison §4 is running.
    renderPanel();
    expect(HeatmapGrid.lastProps.qualityTier).toBe(5);
  });

  it('passes the travel days through, so the grid drops their columns as it does in v1', () => {
    renderPanel();
    expect(HeatmapGrid.lastProps.travelDayDates.has('2026-08-06')).toBe(true);
  });

  it('passes the batch scores through, which is what fills the cells', () => {
    renderPanel();
    expect(HeatmapGrid.lastProps.evaluationScores.get('k')).toEqual({ rating: 4 });
  });

  it('passes a boolean for the paid tier, never a role', () => {
    // Plan §5c: `role` enters this arm at the provider and stops there.
    renderPanel({ isPro: false });
    expect(HeatmapGrid.lastProps.isPro).toBe(false);
    expect(Object.keys(HeatmapGrid.lastProps)).not.toContain('role');
    expect(Object.keys(HeatmapGrid.lastProps)).not.toContain('isLiteUser');
  });

  describe('astro conditions', () => {
    it('fetches one date per rendered day and indexes the scores by location name', async () => {
      getAstroConditions.mockResolvedValue([{ locationName: 'Buttermere', score: 8 }]);
      renderPanel();

      await waitFor(() => expect(HeatmapGrid.lastProps.astroScoresByDate['2026-08-04']).toBeDefined());
      expect(getAstroConditions).toHaveBeenCalledWith('2026-08-04');
      expect(HeatmapGrid.lastProps.astroScoresByDate['2026-08-04'].Buttermere).toEqual({
        locationName: 'Buttermere', score: 8,
      });
    });

    it('asks once per DATE, not once per window', async () => {
      // Two events on one day is the normal case — a sunrise and a sunset — and astro is nightly.
      renderPanel({
        upcomingEvents: [
          { date: '2026-08-04', targetType: 'SUNRISE' },
          { date: '2026-08-04', targetType: 'SUNSET' },
        ],
      });

      await waitFor(() => expect(getAstroConditions).toHaveBeenCalled());
      expect(getAstroConditions).toHaveBeenCalledTimes(1);
    });

    it('leaves the grid drawing when a date\'s request rejects', async () => {
      // The same degradation v1 has: an empty astro column, not a blank panel.
      getAstroConditions.mockRejectedValue(new Error('502'));
      renderPanel();

      await waitFor(() => expect(HeatmapGrid.lastProps.astroScoresByDate['2026-08-04']).toEqual({}));
      expect(screen.getByTestId('stub-heatmap')).toBeInTheDocument();
    });

    it('asks for nothing when there are no dates to ask about', () => {
      renderPanel({ upcomingEvents: [] });
      expect(getAstroConditions).not.toHaveBeenCalled();
      expect(HeatmapGrid.lastProps.astroScoresByDate).toEqual({});
    });
  });

  it('shares the stand-down display preference with the v1 arm, rather than resetting it', () => {
    // Same localStorage key. A second key would silently reset the reader's choice the moment the
    // flag default flips, which is the one transition the whole redesign is building towards.
    localStorage.setItem('showStanddownLocations', 'true');
    renderPanel();
    expect(HeatmapGrid.lastProps.showAllLocations).toBe(true);
  });

  it('tolerates a briefing with no days yet', () => {
    renderPanel({ briefing: null });
    expect(HeatmapGrid.lastProps.briefingDays).toEqual([]);
    expect(HeatmapGrid.lastProps.sortedRegions).toEqual([]);
  });
});
