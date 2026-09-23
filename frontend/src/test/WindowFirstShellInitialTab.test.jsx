import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { getAlmanac } from '../api/almanacApi.js';

/**
 * The device-by-default opening tab (default-tab-by-device-plan.md §4.3) — `App` hands the shell an
 * `initialTab` ('plan'/'map'/absent), and this file is about what the SHELL does with it: model it
 * as a standing preference rather than a one-shot selection, because the Map pane does not exist at
 * first render (`App.jsx`'s `allDates.length > 0` gate), resolve it to Map the moment the pane
 * arrives, and — the rule PR #904's review found missing from an earlier draft — pin the preference
 * dead the instant the reader does ANYTHING with the page, not just when a dialog opens.
 *
 * <p>Scoped the way `WindowFirstShellTabs.test.jsx` scopes itself: neither file owns the whole
 * shell, each owns one join. This one owns initialTab/activeTab/effectiveTab/openedTabs; the other
 * owns which pane a tab controls and what a tab change does elsewhere.
 */
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

vi.mock('../api/almanacApi.js', () => ({
  getAlmanac: vi.fn(),
  ALMANAC_DAYS: 90,
}));

const TODAY = '2026-08-08';
const FEED = { builtFor: TODAY, bands: null, counts: null, conditions: [], entries: [] };

const card = () => ({
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
  spots: [],
  allSpots: [],
  reachTotal: 0,
});

/** Five rated spots — `sheetOffersMore` withholds the "See all" trigger below four. */
const spots = [1, 2, 3, 4, 5].map((n) => ({
  key: String(n),
  locationId: n,
  locationName: `Spot ${n}`,
  regionName: 'Northumberland & Tyneside',
  rating: n === 5 ? 3 : 4,
  driveMinutes: 20 + n,
  distanceMiles: 10 + n,
  far: false,
}));
const windowCard = { ...card(), spots, allSpots: spots, reachTotal: spots.length };

const reachLensSelectTier = vi.fn();
const ratingLensSelectFloor = vi.fn();

const ctx = (overrides = {}) => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [] },
  loading: false,
  heatStripCards: [{
    key: windowCard.key,
    date: windowCard.date,
    targetType: 'SUNSET',
    dow: 'Sat',
    sunrise: false,
    label: 'Tonight Sunset',
    time: '20:41',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    pickKind: null,
    away: false,
    confidence: 'high',
    pool: spots,
    badges: [],
  }],
  heatSpots: [{
    id: 1, name: 'Spot 1', lat: 55.61, lng: -1.71, regionName: 'Northumberland & Tyneside',
    rid: 'Northumberland & Tyneside', skySubject: true, bortleClass: 3, scores: [4],
  }],
  heatPointSets: new Map(),
  windowCards: [windowCard],
  paneItems: [{ kind: 'card', key: windowCard.key, card: windowCard }],
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
    selectTier: reachLensSelectTier,
    resetToDefault: vi.fn(),
  },
  ratingLens: {
    floor: { id: 'any', min: null, label: 'Any rating' },
    floorId: 'any',
    minRating: null,
    selectFloor: ratingLensSelectFloor,
  },
  ...overrides,
});

const MAP_PANE = <p data-testid="map-pane">map pane</p>;

/**
 * Renders the shell with a given `initialTab`/`mapPane`, and hands back a `rerenderWith` that
 * changes PROPS — the late-arrival path needs a prop change (`mapPane` going from absent to
 * present), which `withContext`-style context-only rerenders in the sibling file cannot do.
 */
const renderShell = (extraProps = {}) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  const props = {
    onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(), locations: [],
    ...extraProps,
  };
  const view = render(<WindowFirstShell {...props} />);
  const rerenderWith = (nextProps) => {
    Object.assign(props, nextProps);
    view.rerender(<WindowFirstShell {...props} />);
  };
  return { ...view, props, rerenderWith };
};

const tab = (name) => screen.getByRole('tab', { name });

beforeEach(() => {
  getAlmanac.mockReset();
  getAlmanac.mockResolvedValue(FEED);
  reachLensSelectTier.mockClear();
  ratingLensSelectFloor.mockClear();
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
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('WindowFirstShell — no initialTab prop', () => {
  it('opens on Plan, pinning the default every other shell suite relies on', () => {
    renderShell();
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
  });
});

describe('WindowFirstShell — initialTab="plan"', () => {
  it('opens on Plan even with a map pane already present at mount', () => {
    renderShell({ initialTab: 'plan', mapPane: MAP_PANE });
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByTestId('map-pane')).toBeNull();
  });
});

describe('WindowFirstShell — initialTab="map"', () => {
  it('opens on Map, with the Map pane actually mounted, when the pane is present at mount', () => {
    renderShell({ initialTab: 'map', mapPane: MAP_PANE });
    expect(tab('Map')).toHaveAttribute('aria-selected', 'true');
    // Content from the pane, not just the tab state — a mount check that only reads
    // `aria-selected` would pass against a blank panel (the exact `openedTabs` gap this feature
    // could reintroduce).
    expect(screen.getByTestId('map-pane')).toBeInTheDocument();
  });

  it('opens on Plan when no map pane exists yet, then moves to Map once it arrives', () => {
    // The one genuinely tricky part of this change (plan §2): `App.jsx` withholds `mapPane` until
    // `GET /api/forecast` has returned rows, so the shell renders before the Map pane exists even
    // when the device prefers it.
    const { rerenderWith } = renderShell({ initialTab: 'map' });
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');

    act(() => rerenderWith({ mapPane: MAP_PANE }));

    expect(tab('Map')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('map-pane')).toBeInTheDocument();
  });

  it('calls onTabChange with plan then map on the late-arrival path', () => {
    const onTabChange = vi.fn();
    const { rerenderWith } = renderShell({ initialTab: 'map', onTabChange });
    expect(onTabChange).toHaveBeenLastCalledWith('plan');

    act(() => rerenderWith({ mapPane: MAP_PANE }));

    expect(onTabChange).toHaveBeenLastCalledWith('map');
  });

  it('stays on Plan, with the popup still open, when the reader opened a window before the map pane arrived', async () => {
    const { rerenderWith } = renderShell({ initialTab: 'map' });
    await screen.findByTestId('wf-heat-strip');
    // `fireEvent.click` alone dispatches only a `click`, never the `pointerdown` a real press
    // fires first — and it is the `pointerdown` the commit rule listens for (the shell root's
    // `onPointerDownCapture`), so a realistic press needs both.
    await act(async () => {
      const heatCard = screen.getAllByTestId('wf-heat-card')[0];
      fireEvent.pointerDown(heatCard);
      fireEvent.click(heatCard);
    });
    await screen.findByTestId('window-sheet');

    act(() => rerenderWith({ mapPane: MAP_PANE }));

    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
  });

  it('stays on Plan when the reader changed the Drive or Rating lens — no dialog involved at all', () => {
    // The case PR #904's review found: an earlier draft enumerated the dialog states that commit
    // the preference, and the lens bar calls `reachLens.selectTier`/`ratingLens.selectFloor`
    // directly, never `selectTab`. This must fail if the commit is keyed on dialog state rather
    // than on the interaction itself.
    const { rerenderWith } = renderShell({ initialTab: 'map' });
    const option = screen.getAllByTestId('window-first-lens-tiers-option')[0];
    fireEvent.pointerDown(option);
    fireEvent.click(option);
    expect(reachLensSelectTier).toHaveBeenCalled();

    act(() => rerenderWith({ mapPane: MAP_PANE }));

    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
  });

  it('stays on Plan after a bare keydown on the pane, with no tab focused', () => {
    const { rerenderWith } = renderShell({ initialTab: 'map' });
    fireEvent.keyDown(screen.getByTestId('window-first-pane'), { key: 'a' });

    act(() => rerenderWith({ mapPane: MAP_PANE }));

    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
  });

  it('stays on Plan after a wheel event on the pane', () => {
    const { rerenderWith } = renderShell({ initialTab: 'map' });
    fireEvent.wheel(screen.getByTestId('window-first-pane'));

    act(() => rerenderWith({ mapPane: MAP_PANE }));

    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
  });

  it('stays on Coming up when the reader chose it before the map pane arrived', () => {
    const { rerenderWith } = renderShell({ initialTab: 'map' });
    fireEvent.click(tab('Coming up'));
    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');

    act(() => rerenderWith({ mapPane: MAP_PANE }));

    expect(tab('Coming up')).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByTestId('map-pane')).toBeNull();
  });

  it('does not move focus when the preference resolves to Map on its own', async () => {
    // Only an explicit `tabRequest` moves focus (`:745`'s own note) — the preference-driven switch
    // must leave `document.activeElement` exactly where it was.
    const { rerenderWith } = renderShell({ initialTab: 'map' });
    const plan = tab('Plan');
    plan.focus();
    expect(document.activeElement).toBe(plan);

    act(() => rerenderWith({ mapPane: MAP_PANE }));
    // Force TWO animation frames, not a microtask — this file's own deferred-frame focus moves
    // (`tabRequest`'s effect, the location-sheet handoff) both move focus inside a
    // `requestAnimationFrame`, because the element they focus may be rendering for the first time
    // on that very commit (frontend-test-standards.md, "Waiting for a deferred effect"). A bare
    // `await Promise.resolve()` never reaches that callback at all, so a focus move added to the
    // preference-driven switch later would pass this assertion undetected.
    await act(async () => {
      await new Promise((resolve) => {
        requestAnimationFrame(() => requestAnimationFrame(resolve));
      });
    });

    expect(tab('Map')).toHaveAttribute('aria-selected', 'true');
    expect(document.activeElement).toBe(plan);
  });

  describe('the grace timer', () => {
    beforeEach(() => { vi.useFakeTimers(); });

    it('commits Plan once PREFERRED_TAB_GRACE_MS has passed with no interaction, so a later map pane does not move it', () => {
      const { rerenderWith } = renderShell({ initialTab: 'map' });
      act(() => { vi.advanceTimersByTime(1500); });

      act(() => rerenderWith({ mapPane: MAP_PANE }));

      expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');
    });

    it('resolves to Map when the pane arrives just under the grace boundary, before the timer has fired at all', () => {
      // Distinct from the test below: here the timer NEVER fires while `activeTab` is still null —
      // the pane arrives first, at 1499ms, one tick before the 1500ms deadline, and nothing further
      // advances the clock. This is the "ordinary" resolve path; it says nothing about what happens
      // if the timer fires AFTER the preference has already resolved, which is the next test.
      const { rerenderWith } = renderShell({ initialTab: 'map' });
      act(() => { vi.advanceTimersByTime(1499); });

      act(() => rerenderWith({ mapPane: MAP_PANE }));

      expect(tab('Map')).toHaveAttribute('aria-selected', 'true');
    });

    it('⚠️ stays on Map once resolved, even once the grace timer goes on to fire — the stale-closure regression', () => {
      // Found in review: the timer is armed once at MOUNT, when `effectiveTab` is necessarily still
      // Plan (the Map pane does not exist yet — App.jsx's `allDates.length > 0` gate). A
      // `commitTabInForce` that closed over that mount-render value, instead of reading the tab in
      // force AT FIRE TIME, would commit the STALE 'plan' when the timer's 1500ms elapsed — even
      // though the pane had already arrived and the preference had already resolved to Map. This
      // test fails against that implementation: the pane arrives at 500ms (well under the grace
      // boundary, so the earlier "just under" test alone cannot catch this), the clock then crosses
      // the 1500ms mark where the stale timer WOULD fire, and the tab must still read Map.
      const { rerenderWith } = renderShell({ initialTab: 'map' });
      act(() => { vi.advanceTimersByTime(500); });
      act(() => rerenderWith({ mapPane: MAP_PANE }));
      expect(tab('Map')).toHaveAttribute('aria-selected', 'true');

      // Cross the 1500ms-from-mount mark — the moment the stale-closure bug fired.
      act(() => { vi.advanceTimersByTime(1000); });
      expect(tab('Map')).toHaveAttribute('aria-selected', 'true');

      // And a later, unrelated re-render (a prop change, same as a poll or a lens read landing)
      // does not reopen the question either — the preference is settled, not merely lucky timing.
      act(() => rerenderWith({ locations: [] }));
      expect(tab('Map')).toHaveAttribute('aria-selected', 'true');
    });
  });

  it('keeps the Map pane mounted after leaving and returning, the sticky rule', () => {
    // The preference resolves to Map via `openedTabs`' render-time addition, never through
    // `selectTab` — so without the sticky rule holding for THAT route too, a reader who presses
    // Plan and then Map again would find a remounted, blank Map pane.
    renderShell({ initialTab: 'map', mapPane: MAP_PANE });
    expect(screen.getByTestId('map-pane')).toBeInTheDocument();

    fireEvent.click(tab('Plan'));
    expect(tab('Plan')).toHaveAttribute('aria-selected', 'true');

    fireEvent.click(tab('Map'));
    expect(screen.getByTestId('map-pane')).toBeInTheDocument();
  });
});
