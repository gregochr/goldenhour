import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { registerMapDoor } from './shellMapDoor.js';

/**
 * The shell's half of the collapsing lens (screen-spec.md D6): the shell hands the This week section to the lens bar, whose
 * `useCollapsingLens` watches it to know it has been scrolled past. The lens's own state machine
 * is `WindowFirstLensBarCollapse.test.jsx`; this file pins the wiring, and that desktop and tablet draw none of it.
 */
const TODAY = '2026-08-08';

let mockIsMobile = false;
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: () => mockIsMobile }));

const ctx = () => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [] },
  loading: false,
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
});

let observers;

beforeEach(() => {
  localStorage.clear();
  mockIsMobile = false;
  observers = [];
  vi.stubGlobal('IntersectionObserver', class {
    constructor(callback, options) {
      this.callback = callback;
      this.options = options;
      observers.push(this);
    }

    observe(element) { this.element = element; }

    disconnect() {}
  });
  vi.stubGlobal('ResizeObserver', class {
    observe() {}

    unobserve() {}

    disconnect() {}
  });
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

const renderShell = () => render(
  <WindowFirstShell
    onOpenSettings={vi.fn()}
    onSignOut={vi.fn()}
    onShowOnMap={vi.fn()}
    locations={[]}
    registerOpenMap={registerMapDoor}
  />,
);

const observerOf = (testId) => observers.findLast((o) => o.element?.dataset?.testid === testId);
const report = (testId, entry) => act(() => { observerOf(testId).callback([entry]); });

describe('WindowFirstShell — the This week section as the collapse trigger', () => {
  it('watches the section itself, so a jump over it is never missed (a 1px marker below the fold would be)', () => {
    mockIsMobile = true;
    renderShell();
    expect(observerOf('this-week')).toBeDefined();
    expect(screen.queryByTestId('this-week-end-sentinel')).toBeNull();
  });

  it('collapses the lens once the end of the week has scrolled ABOVE the viewport, and not before it is reached', () => {
    mockIsMobile = true;
    renderShell();
    // Below the viewport (not reached yet): not intersecting, but not passed either.
    report('this-week', {
      isIntersecting: false, boundingClientRect: { top: 900, bottom: 2400 }, rootBounds: { top: 0 },
    });
    report('window-first-lens-sentinel', { isIntersecting: false });
    expect(screen.getByTestId('window-first-lens')).not.toHaveClass('wf-lens-collapsed');
    // The lens is stuck (scrolled) and the sentinel has passed above the viewport: one line.
    report('this-week', {
      isIntersecting: false, boundingClientRect: { top: -1500, bottom: -4 }, rootBounds: { top: 0 },
    });
    expect(screen.getByTestId('window-first-lens')).toHaveClass('wf-lens-collapsed');
    expect(screen.getByTestId('window-first-lens-summary')).toHaveTextContent('Drive 45 min · any rating');
    // Scrolled back up through the week: it is full again.
    report('this-week', {
      isIntersecting: true, boundingClientRect: { top: -300, bottom: 1200 }, rootBounds: { top: 0 },
    });
    expect(screen.getByTestId('window-first-lens')).not.toHaveClass('wf-lens-collapsed');
  });

  it('desktop and tablet draw no summary and no spacer, do not even watch the section, and never collapse', () => {
    renderShell();
    report('window-first-lens-sentinel', { isIntersecting: false });
    expect(observerOf('this-week')).toBeUndefined();
    expect(screen.queryByTestId('window-first-lens-summary')).toBeNull();
    expect(screen.queryByTestId('window-first-lens-spacer')).toBeNull();
    expect(screen.getByTestId('window-first-lens')).not.toHaveClass('wf-lens-collapsed');
  });
});
