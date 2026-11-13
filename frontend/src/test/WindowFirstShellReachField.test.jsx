import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * The shell's half of the reach field (reach-field plan): under an away origin the strip and the popup's
 * row map get the EFFECTIVE reach map as {@code fieldReachById} (the one {@code card.pool} was gated with)
 * while {@code reachById} (framing, rings) stays the home map. Both children are stubbed to print what
 * they were handed.
 */
const seen = { strip: null, rowMap: null };

vi.mock('../components/WindowFirstHeatStrip.jsx', async (importOriginal) => ({
  ...(await importOriginal()),
  default: (props) => {
    seen.strip = props;
    return (
      <div data-testid="wf-heat-strip">
        {props.cards.map((c) => (
          <button type="button" key={c.key} data-testid="wf-heat-card" onClick={() => props.onOpenWindow(c.key)}>
            {c.label}
          </button>
        ))}
      </div>
    );
  },
}));

vi.mock('../components/WindowRowFieldMap.jsx', async (importOriginal) => ({
  ...(await importOriginal()),
  default: (props) => {
    seen.rowMap = props;
    return <div data-testid="stub-row-map" />;
  },
}));

const TODAY = '2026-08-08';
const HOME_MAP = new Map([[1, { driveMinutes: 20 }]]);
const EFFECTIVE_MAP = new Map([[1, { driveMinutes: 200 }]]);

const NEAR = {
  key: '1', locationId: 1, locationName: 'Bamburgh Beach', regionName: 'Northumberland & Tyneside',
  rating: 4, driveMinutes: 30, distanceMiles: 21, far: false,
};

function card() {
  return {
    key: `${TODAY}:SUNSET`,
    date: TODAY,
    targetType: 'SUNSET',
    lead: true,
    kicker: 'Tonight',
    when: 'Sunset',
    time: '20:41',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    bestRating: 5,
    confidence: 'high',
    badges: [],
    rows: [],
    pick: null,
    spots: [NEAR],
    allSpots: [NEAR],
    reachTotal: 1,
    reachMeasured: true,
    reachLimitMinutes: 45,
  };
}

const ctx = () => {
  const c = card();
  return {
    briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [] },
    loading: false,
    heatStripCards: [{
      key: c.key, date: c.date, targetType: c.targetType, dow: 'Sat', sunrise: false, label: 'Tonight Sunset',
      time: c.time, verdict: c.verdict, verdictLabel: c.verdictLabel, pickKind: null, away: false,
      confidence: c.confidence, pool: c.spots, badges: [],
    }],
    heatSpots: [{
      id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, regionName: 'Northumberland & Tyneside',
      rid: 'Northumberland & Tyneside', skySubject: true, bortleClass: 3, scores: [4],
    }],
    heatPointSets: new Map(),
    windowCards: [c],
    paneItems: [{ kind: 'card', key: c.key, card: c }],
    upcomingEvents: [],
    travelDayDates: new Set(),
    evaluationScores: new Map(),
    scoreIndex: new Map(),
    reachById: HOME_MAP,
    effectiveReachById: EFFECTIVE_MAP,
    todayStr: TODAY,
    tomorrowStr: '2026-08-09',
    homePlace: 'Newcastle',
    isPro: true,
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
  };
};

beforeEach(() => {
  seen.strip = null;
  seen.rowMap = null;
  localStorage.clear();
  window.matchMedia = (query) => ({
    matches: false,
    media: query,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    onchange: null,
    dispatchEvent: () => false,
  });
});
afterEach(() => vi.restoreAllMocks());

describe('WindowFirstShell — the reach field wiring', () => {
  it('gives the strip the effective map as fieldReachById and keeps reachById home', async () => {
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
    render(<WindowFirstShell onOpenSettings={vi.fn()} onSignOut={vi.fn()} onOpenMapTab={vi.fn()} locations={[]} />);
    await screen.findByTestId('wf-heat-strip');
    expect(seen.strip.fieldReachById).toBe(EFFECTIVE_MAP);
    expect(seen.strip.reachById).toBe(HOME_MAP);
  });

  it('gives the popup\'s row map the effective map and the card\'s tier', async () => {
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
    render(<WindowFirstShell onOpenSettings={vi.fn()} onSignOut={vi.fn()} onOpenMapTab={vi.fn()} locations={[]} />);
    await screen.findByTestId('wf-heat-strip');
    await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]); });
    await screen.findByTestId('stub-row-map');
    expect(seen.rowMap.fieldReachById).toBe(EFFECTIVE_MAP);
    expect(seen.rowMap.reachById).toBe(HOME_MAP);
    expect(seen.rowMap.reachLimitMinutes).toBe(45);
    expect(seen.rowMap.reachMeasured).toBe(true);
  });
});
