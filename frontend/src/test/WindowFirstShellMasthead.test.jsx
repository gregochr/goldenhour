import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * The masthead the window-first arm renders in place of the app header, and specifically the status
 * pill it lost on the way across.
 *
 * <p>Scoped its own file the way {@code WindowFirstShellTabs} and {@code WindowFirstShellSheet} are:
 * every rule here is about how the shell JOINS the pill to the masthead — that it is a slot rather
 * than a component, where it sits among the two buttons, and that a dead backend cannot take it with
 * the pane. None of those is reachable from {@code HealthIndicator}'s own test, which knows nothing
 * about a masthead.
 *
 * <p>The doors are stubbed for the reason both sibling files give: mounting them fires an astro
 * request per visible date, and this file is about neither.
 */
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

const TODAY = '2026-08-08';

/** Stands in for the real pill — this file's subject is the slot, not what fills it. */
const PILL = <span data-testid="health-indicator">UP</span>;

const ctx = () => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [] },
  loading: false,
  // The heat strip's thumbnails replaced the day rail's tiles at P2 (plan D1). Empty here, with an
  // empty catalogue beside it: the strip withdraws entirely without spots to draw, which keeps
  // these files about the shell's wiring rather than about a canvas.
  heatStripCards: [],
  heatSpots: [],
  heatPointSets: new Map(),
  windowCards: [],
  paneItems: [],
  upcomingEvents: [],
  travelDayDates: new Set(),
  evaluationScores: new Map(),
  scoreIndex: new Map(),
  reachById: new Map(),
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
  // The third axis. It gates nothing — it re-ranks the pane — so the shell's wiring tests sit on
  // the chronological default, and `windowFirstOrder.test.js` owns the ranking itself.
  orderLens: {
    order: { id: 'when', label: 'When' },
    orderId: 'when',
    selectOrder: vi.fn(),
  },
});

const renderShell = (extraProps = {}) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  const props = {
    onExit: vi.fn(), onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(), locations: [],
    ...extraProps,
  };
  return { ...props, ...render(<WindowFirstShell {...props} />) };
};

beforeEach(() => {
  localStorage.clear();
});
afterEach(() => vi.restoreAllMocks());

describe('WindowFirstShell — the masthead status pill', () => {
  // The admin gate in full, and it is the same one the Operations tab uses: `App` holds `isAdmin`
  // and withholds the node, so nothing role-shaped crosses this boundary (plan §5c). A pilot user
  // has no use for a build id or a WorldTides latency, which is why the arm shipped without it.
  it('renders no pill when no node was handed over', () => {
    renderShell();
    expect(screen.queryByTestId('health-indicator')).toBeNull();
    // The rest of the masthead is unaffected — the gap collapses on its own.
    expect(screen.getByTestId('window-first-settings')).toBeInTheDocument();
    expect(screen.getByTestId('window-first-signout')).toBeInTheDocument();
  });

  it('renders the pill in the masthead when one is handed over', () => {
    renderShell({ healthPill: PILL });
    const masthead = screen.getByTestId('window-first-masthead');
    expect(masthead).toContainElement(screen.getByTestId('health-indicator'));
  });

  // Reading first, then the two controls — the order the v1 header put the same three in. A pill
  // dropped between the cog and Sign out splits a pair the reader treats as one group.
  it('places it ahead of the settings cog and Sign out', () => {
    renderShell({ healthPill: PILL });
    const pill = screen.getByTestId('health-indicator');
    const cog = screen.getByTestId('window-first-settings');
    expect(pill.compareDocumentPosition(cog) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBe(Node.DOCUMENT_POSITION_FOLLOWING);
  });

  /**
   * The case the pill exists for. `contentDisabled` is `opacity-50 pointer-events-none`, and the
   * masthead is deliberately outside it so a DOWN backend cannot strand a reader with no working
   * control — this file's own component says so at length about the cog and Sign out. The pill is
   * the one element in that row whose whole purpose is the DOWN state: greying it would make the
   * status readout unreadable and unopenable at exactly the moment it is the only thing worth
   * reading.
   */
  it('stays outside the greying a DOWN backend applies to the pane', () => {
    renderShell({ healthPill: PILL, contentDisabled: true });

    const masthead = screen.getByTestId('window-first-masthead');
    expect(masthead.className).not.toContain('pointer-events-none');
    expect(masthead.className).not.toContain('opacity-50');
    // And the pane it is NOT part of does take the treatment, so this is a statement about where
    // the boundary is rather than about the treatment having been dropped.
    expect(screen.getByTestId('window-first-pane').className).toContain('pointer-events-none');
  });
});
