import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, within, cleanup } from '@testing-library/react';
import React from 'react';
import usePlanLayout, { PLAN_LAYOUT_KEY, PLAN_V1, PLAN_V2 } from '../hooks/usePlanLayout.js';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { buildWindowCards } from '../utils/windowFirstCards.js';
import { buildPaneItems } from '../utils/windowFirstAway.js';
import { buildPromotedStrip } from '../utils/windowFirstPromoted.js';

/**
 * A harness rather than a real consumer, deliberately: the only component that reads this hook is
 * `AppInner`, which cannot be rendered without auth, three SSE streams and the whole forecast load —
 * that would test all of that, not the flag. See docs/engineering/frontend-test-standards.md
 * § Structure; if a smaller real consumer appears, render that instead.
 */
function Host() {
  const [layout, setLayout] = usePlanLayout();
  return (
    <div>
      <span data-testid="layout">{layout}</span>
      <button onClick={() => setLayout(PLAN_V2)}>to v2</button>
      <button onClick={() => setLayout('sideways')}>to nonsense</button>
    </div>
  );
}

describe('usePlanLayout', () => {
  beforeEach(() => localStorage.clear());

  it('defaults to the current Plan tab, so the flag ships off', () => {
    render(<Host />);
    expect(screen.getByTestId('layout')).toHaveTextContent(PLAN_V1);
  });

  it('persists a switch under the versioned key', () => {
    render(<Host />);
    fireEvent.click(screen.getByText('to v2'));
    expect(screen.getByTestId('layout')).toHaveTextContent(PLAN_V2);
    expect(JSON.parse(localStorage.getItem(PLAN_LAYOUT_KEY))).toBe(PLAN_V2);
  });

  it('restores a stored layout on mount', () => {
    localStorage.setItem(PLAN_LAYOUT_KEY, JSON.stringify(PLAN_V2));
    render(<Host />);
    expect(screen.getByTestId('layout')).toHaveTextContent(PLAN_V2);
  });

  // The failure this guards is narrow but total: an unrecognised value must not render neither
  // layout. It can arrive from a half-written key or from a build the user has since rolled back.
  it('falls back to v1 when the stored value is not a layout', () => {
    localStorage.setItem(PLAN_LAYOUT_KEY, JSON.stringify('v99'));
    render(<Host />);
    expect(screen.getByTestId('layout')).toHaveTextContent(PLAN_V1);
  });

  it('refuses to store a value that is not a layout', () => {
    render(<Host />);
    fireEvent.click(screen.getByText('to nonsense'));
    expect(screen.getByTestId('layout')).toHaveTextContent(PLAN_V1);
    // The rendered value alone is already guaranteed by the READ guard, so asserting only that
    // leaves this test unable to fail if the write guard is deleted — it was, and it passed.
    // Storage is the only observable that distinguishes the two.
    expect(JSON.parse(localStorage.getItem(PLAN_LAYOUT_KEY))).toBe(PLAN_V1);
  });
});

describe('WindowFirstShell', () => {
  const renderShell = (props = {}) => {
    const handlers = { onExit: vi.fn(), onOpenSettings: vi.fn(), onSignOut: vi.fn(), ...props };
    render(<WindowFirstShell {...handlers} />);
    return handlers;
  };

  // The shorter of the two routes back — the masthead's ⚙ opens the settings modal, which owns the
  // toggle. This one exists because the arm below it is empty while the shell is a stub.
  it('offers a way back to the current Plan', () => {
    const { onExit } = renderShell();
    fireEvent.click(screen.getByRole('button', { name: /back to the current plan/i }));
    expect(onExit).toHaveBeenCalledTimes(1);
  });

  it('carries the wordmark as the page heading, because it replaces the app header', () => {
    // App suppresses its own <header> for this arm, so if the masthead did not carry the wordmark
    // the signed-in app would have no h1 at all — and src/test/e2e/forecast.spec.js:46 finds the
    // app with getByRole('heading', { name: /PhotoCast/ }). That e2e would break the moment the
    // flag default flips at P15, which is far too late to notice.
    renderShell();
    expect(screen.getByRole('heading', { level: 1, name: 'PhotoCast' })).toBeInTheDocument();
  });

  it('carries the cog and Sign out the suppressed header used to own', () => {
    // Both are lifted handlers, not new state. Losing either would strand a v2 user with no route
    // to settings — which is the only route back once the temporary exit button goes.
    const { onOpenSettings, onSignOut } = renderShell();

    // By ROLE AND NAME, not test-id: a test-id keeps passing while the accessible name rots, and
    // these two are the only route out of the arm once the temporary exit button goes.
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }));
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));

    expect(onOpenSettings).toHaveBeenCalledTimes(1);
    expect(onSignOut).toHaveBeenCalledTimes(1);
  });

  it('renders two tabs, Plan selected, and no tab whose pane does not exist', () => {
    // The design draws four. Coming up landed at P13 WITH its pane; Map and Manage arrive when this
    // subtree takes over view state. A tab that renders nothing is a demo control and §6 bans those
    // from the shipped build — so this pins that each tab lands with its pane rather than ahead of
    // it, and it is the count that does the pinning: an added tab has to be argued for here.
    renderShell();

    const tabs = screen.getAllByRole('tab');
    expect(tabs).toHaveLength(2);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    expect(tabs[1]).toHaveAttribute('aria-selected', 'false');
    // Exactly "Plan": the ◉ is decorative and must not leak into the accessible name, which is the
    // rule ViewToggle:56 already follows for its own glyphs.
    expect(tabs[0]).toHaveAccessibleName('Plan');
    expect(tabs[1]).toHaveAccessibleName('Coming up');
  });

  it('carries exactly two masthead controls, so no build/health pill creeps in', () => {
    // The mock shows "● UP v2.17.7" unconditionally; §7 drops it, because build version and
    // service health are not a pilot user's business and HealthIndicator is admin-only. Asserting
    // the ABSENCE of that string would pass whether or not anything was ever built — this counts
    // what the masthead actually offers instead, so an added control has to be argued for.
    renderShell();
    const masthead = screen.getByTestId('window-first-masthead');
    const names = within(masthead).getAllByRole('button').map((b) => b.textContent.trim());
    expect(names).toEqual(['⚙', 'Sign out']);
  });

  it('renders at the design\'s 1080px frame, not the v1 arm\'s 896px column', () => {
    // One of P4a's two deliverables, and nothing else pinned it. The v1 arm is max-w-4xl (896px);
    // a shell that inherited that would be ~200px under the frame every later phase is drawn to.
    renderShell();
    expect(screen.getByTestId('window-first-shell')).toHaveStyle({ maxWidth: '1080px' });
  });

  it('greys the pane when the backend is DOWN, but never the way out', () => {
    // The v1 header sits OUTSIDE the element carrying the DOWN treatment, so it has never been
    // able to disable Settings or Sign out. Here the masthead is inside the shell: gating the
    // whole subtree would strand a user on a dead page with no cog, no Sign out and no exit —
    // exactly when they most need one.
    renderShell({ contentDisabled: true });

    expect(screen.getByTestId('window-first-pane').className).toContain('pointer-events-none');
    expect(screen.getByTestId('window-first-masthead').className)
      .not.toContain('pointer-events-none');
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
  });

  it('greys the day rail with the pane, because the rail is forecast data too', () => {
    // The rail sits outside the pane (it is the whole screen's date context, not one pane's
    // content), so it needs the treatment applied to it explicitly. A DOWN backend leaving a
    // live-looking rail beside a greyed pane says the days are current when they may not be.
    renderShell({ contentDisabled: true });

    expect(screen.getByTestId('window-first-rail-region').className).toContain('pointer-events-none');
    expect(screen.getByTestId('window-first-tabs').className).not.toContain('pointer-events-none');
  });

  it('says so plainly when there are no days to show', () => {
    // The provider's default value is an empty rail, which is also what a 204 and a failed first
    // fetch produce. Rendering nothing at all leaves a gap above the tabs that reads as a broken
    // layout rather than as an absent forecast.
    renderShell();
    expect(screen.getByTestId('window-first-rail-empty')).toBeInTheDocument();
  });

  it('shows no forecast age until there is a forecast to age', () => {
    // The footer states a fact about the payload. With no payload the honest thing is silence —
    // an empty "forecast" line is a claim with nothing behind it. The footer itself now survives,
    // because P8 gave it a second half (the home prompt and "Edit reach") that is true regardless
    // of whether a briefing has ever arrived.
    renderShell();
    expect(screen.queryByTestId('window-first-age')).toBeNull();
    expect(screen.getByTestId('window-first-railfoot')).not.toHaveTextContent('forecast');
  });
});

/**
 * The nearest ancestor carrying the DOWN treatment, or null.
 *
 * <p>`pointer-events: none` inherits, so "is this control still live" is a question about the whole
 * chain rather than about one className — and a test that asked only the element passed on a
 * version wrapped in a greyed div.
 */
function dimmedAncestorOf(el) {
  for (let node = el; node; node = node.parentElement) {
    if (typeof node.className === 'string' && node.className.includes('pointer-events-none')) {
      return node;
    }
  }
  return null;
}

/** The reach lens exactly as `useReachLens` shapes it, sitting on a weekday default. */
const LENS = {
  tier: { id: '45', limitMinutes: 45, label: '45 min' },
  tierId: '45',
  defaultTier: { id: '45', limitMinutes: 45, label: '45 min' },
  defaultTierId: '45',
  weekend: false,
  overridden: false,
  locked: false,
  selectTier: () => {},
  resetToDefault: () => {},
};

/** The bar's second axis, on the same terms — frozen where the hook's own file exercises it. */
const RATING_LENS = {
  floor: { id: 'any', min: null, label: 'Any rating' },
  floorId: 'any',
  minRating: null,
  selectFloor: () => {},
};

describe('WindowFirstShell — the rail it hosts', () => {
  const CARD = {
    key: '2026-08-04:SUNSET',
    date: '2026-08-04',
    targetType: 'SUNSET',
    lead: true,
    kicker: 'Tonight',
    when: 'Sunset',
    time: '21:11',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    bestRating: 4,
    confidence: 'high',
    badges: [],
    rows: [],
    pick: {
      kind: 'best',
      regionName: 'Northumberland & Tyneside',
      headline: 'Breaking clear',
      detail: 'Low cloud clears.',
      locationName: 'Bamburgh Beach',
      locationId: 1,
    },
    spots: [{
      key: '1',
      locationId: 1,
      locationName: 'Bamburgh Beach',
      regionName: 'Northumberland & Tyneside',
      rating: 4,
      driveMinutes: 66,
      distanceMiles: 47,
    }],
  };

  const briefingWith = (generatedAt, evaluationScores = new Map(), windowCards = [CARD],
    paneItems = null) => ({
    briefing: generatedAt ? { generatedAt } : null,
    loading: false,
    windowCards,
    // What `buildPaneItems` produces when no day is a travel day — the shell renders items, and a
    // fixture that only supplied cards would exercise a path the provider never hands it. Callers
    // that care about away rows pass their own.
    paneItems: paneItems || windowCards.map((card) => ({ kind: 'card', key: card.key, card })),
    // The doors read these. `upcomingEvents` empty keeps the regional door out of the tests that
    // are about the rail and the pane; the two files that are about the doors supply their own.
    upcomingEvents: [],
    travelDayDates: new Set(),
    reachById: new Map(),
    isPro: true,
    isLiteUser: false,
    evaluationScores,
    railTiles: [{
      date: '2026-08-04',
      isToday: true,
      targetType: 'SUNSET',
      dow: 'Tue',
      dayNum: '4',
      dayLabel: 'Today',
      sunriseTime: '05:15',
      sunsetTime: '21:11',
      peak: 'go',
      peakLabel: 'Worth it · sunset',
      countLabel: null,
      pick: null,
      regions: [{
        regionName: 'Northumberland & Tyneside', shortName: 'N&T', targetType: 'SUNSET',
        verdictLabel: 'Worth it sunset', wx: '', summary: 's', glossHeadline: '', glossDetail: '',
        pickKind: null,
      }],
      ratedCount: 1,
      isAway: false,
      confidence: 'high',
    }],
    todayStr: '2026-08-04',
    tomorrowStr: '2026-08-05',
    // The lens as the provider hands it over. A frozen value rather than the live hook: these
    // tests are about the shell's wiring — where the bar sits, what it is fed and what it is not
    // dimmed by — and the hook's own behaviour has its own file.
    reachLens: LENS,
    ratingLens: RATING_LENS,
    homePlace: undefined,
  });

  const renderWithBriefing = (ctx, props = {}) => {
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx);
    const handlers = {
      onExit: vi.fn(), onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(), ...props,
    };
    render(<WindowFirstShell {...handlers} />);
    return handlers;
  };

  afterEach(() => vi.restoreAllMocks());

  it('places the rail above the tab bar, where every tab can still see it', () => {
    // The rail is the screen's date context, not the Plan pane's content: Coming up and Map ask
    // about the same days. Inside the pane it would vanish on a tab that still needs it.
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

    const rail = screen.getByTestId('window-first-day-rail');
    const tabs = screen.getByTestId('window-first-tabs');
    expect(rail.compareDocumentPosition(tabs) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('hands a day to the map with that day and its best event', () => {
    const { onShowOnMap } = renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    fireEvent.click(screen.getByTestId('rail-day-show-on-map'));
    expect(onShowOnMap).toHaveBeenCalledWith('2026-08-04', 'SUNSET');
  });

  it('hands a region chip to the map as a region, not as a day', () => {
    // `onShowOnMap` reads a positional (date, eventType) OR a {region, …} object, and the two open
    // different things. Passing the tile's handler for both would silently drop the region the
    // user pointed at and open the whole day instead.
    const { onShowOnMap } = renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    fireEvent.click(screen.getByTestId('rail-region-chip'));

    expect(onShowOnMap).toHaveBeenCalledWith({
      region: 'Northumberland & Tyneside', date: '2026-08-04', eventType: 'SUNSET',
    });
  });

  it('states the forecast\'s age, and never the model that produced it', () => {
    // §7: the model name is admin-only today and is not a pilot user's business, so the design's
    // "forecast 52m ago by Sonnet" ships as the age alone.
    vi.setSystemTime(new Date('2026-08-04T12:34:00Z'));
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

    const foot = screen.getByTestId('window-first-railfoot');
    expect(foot).toHaveTextContent('forecast 34m ago');
    expect(foot.textContent).not.toMatch(/sonnet|haiku|opus/i);
    vi.useRealTimers();
  });

  it('reads the age as UTC, which is how the backend writes it', () => {
    // Parsing the zone-less instant as local time made a 34-minute-old forecast read "1h ago"
    // through a British summer. The shared formatter appends the Z; a local copy did not.
    vi.setSystemTime(new Date('2026-08-04T12:05:00Z'));
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

    expect(screen.getByTestId('window-first-railfoot')).toHaveTextContent('forecast 5m ago');
    vi.useRealTimers();
  });

  it('drops the empty-state line once there are days', () => {
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    expect(screen.queryByTestId('window-first-rail-empty')).toBeNull();
  });

  it('says nothing about days while the first fetch is still in flight', () => {
    // "No forecast days to show yet" during a cold load is a claim about the forecast made before
    // anyone has asked it. Silence until the answer arrives.
    renderWithBriefing({
      briefing: null,
      loading: true,
      railTiles: [],
      windowCards: [],
      paneItems: [],
      upcomingEvents: [],
      travelDayDates: new Set(),
      reachById: new Map(),
      isPro: true,
      isLiteUser: false,
      evaluationScores: new Map(),
      todayStr: '2026-08-04',
      tomorrowStr: '2026-08-05',
    });
    expect(screen.queryByTestId('window-first-rail-empty')).toBeNull();
    expect(screen.queryByTestId('window-first-pane-empty')).toBeNull();
  });

  it('renders one card per window in the Plan pane', () => {
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    expect(screen.getAllByTestId('window-card')).toHaveLength(1);
    expect(screen.getByTestId('window-card-when')).toHaveTextContent('Sunset');
  });

  describe('the collapse default', () => {
    const SECOND = { ...CARD, key: '2026-08-05:SUNRISE', date: '2026-08-05', lead: false, kicker: null, when: 'Tomorrow sunrise' };
    const THIRD = { ...CARD, key: '2026-08-05:SUNSET', date: '2026-08-05', lead: false, kicker: null, when: 'Tomorrow sunset' };

    it('opens the first card and collapses the rest', () => {
      // Plan §5a settled it on measured heights: six open cards run to 2.74 viewports, against the
      // 2,600px §3 names as the failure the whole redesign exists to undo.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [CARD, SECOND, THIRD]));

      const cards = screen.getAllByTestId('window-card');
      expect(cards.map((c) => c.dataset.open)).toEqual(['true', 'false', 'false']);
    });

    it('opens the first card even when no card is the lead one', () => {
      // `lead` is `index === 0 && date === todayStr`, so after today's last window has passed there
      // is no lead card at all — and a rule keyed on it would leave every card collapsed, every
      // evening, which is exactly when someone is checking tomorrow's dawn.
      const noLead = [{ ...SECOND }, { ...THIRD }];
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), noLead));

      expect(screen.getAllByTestId('window-card').map((c) => c.dataset.open))
        .toEqual(['true', 'false']);
    });

    it('collapses the open card when its expander is pressed', () => {
      // The flip is written against the EFFECTIVE state. Against the map's own default the first
      // press on the open lead card would set it to open — a control that does nothing the one
      // time it is most likely to be pressed.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [CARD, SECOND]));

      fireEvent.click(screen.getAllByTestId('window-card-expander')[0]);
      expect(screen.getAllByTestId('window-card').map((c) => c.dataset.open))
        .toEqual(['false', 'false']);
    });

    it('opens a collapsed card without closing the one already open', () => {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [CARD, SECOND]));

      fireEvent.click(screen.getAllByTestId('window-card-expander')[1]);
      expect(screen.getAllByTestId('window-card').map((c) => c.dataset.open))
        .toEqual(['true', 'true']);
    });
  });

  describe('away days in the pane', () => {
    const AWAY_ITEM = {
      kind: 'away', key: 'away:2026-08-05', dates: ['2026-08-05'], label: 'Wed 5',
      note: 'Business trip', windowCount: 2,
    };

    it('draws the away row in the place the pane items put it', () => {
      // The shell chooses which component draws which item and nothing else — the ordering is
      // `buildPaneItems`', so the two can never disagree about which days exist.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [CARD],
        [{ kind: 'card', key: CARD.key, card: CARD }, AWAY_ITEM]));

      const pane = screen.getByTestId('window-first-pane');
      const card = screen.getByTestId('window-card');
      const away = screen.getByTestId('window-away-row');
      expect(pane).toContainElement(away);
      expect(card.compareDocumentPosition(away) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(away).toHaveTextContent('Wed 5 · away — 2 windows not forecast');
    });

    it('says nothing about missing windows when no day is a travel day', () => {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
      expect(screen.queryByTestId('window-away-row')).toBeNull();
    });

    it('keeps the empty-state line for a pane with no items of either kind', () => {
      // An away row is an item, so a fortnight away must NOT also print "No windows to show".
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [], [AWAY_ITEM]));
      expect(screen.queryByTestId('window-first-pane-empty')).toBeNull();
      expect(screen.getByTestId('window-away-row')).toBeInTheDocument();
    });
  });

  describe('the two doors', () => {
    it('sits at the foot of the pane, below every window', () => {
      // Where the design puts them, and inside the pane rather than beside it: they open forecast
      // content, so they take the DOWN treatment the pane carries. The exit button below them is
      // the one thing that must stay outside it.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

      const pane = screen.getByTestId('window-first-pane');
      const doors = screen.getByTestId('window-first-doors');
      expect(pane).toContainElement(doors);
      expect(screen.getByTestId('window-card').compareDocumentPosition(doors)
        & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('is absent when neither door has anything behind it', () => {
      // No windows to plan over and no hot topics — an entirely-away horizon, or a briefing whose
      // events have all elapsed. The regional door gates on `windowCards`, which is the set the
      // travel filter has already run over.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), []));
      expect(screen.queryByTestId('window-first-doors')).toBeNull();
    });
  });

  it('carries no placeholder prose above the cards', () => {
    // The pane shipped an explanatory paragraph while it was a stub. That is an annotation card by
    // any reading of §6, and it must not survive the phase that gives the pane real content.
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    expect(screen.getByTestId('window-first-pane').textContent)
      .not.toMatch(/arrive in later phases|Window-first Plan/);
  });

  it('keeps the way back working when the backend is DOWN', () => {
    // The DOWN treatment is `pointer-events: none`, and the exit button used to live INSIDE the
    // pane that carries it — so a dead backend made the visible route back inert. That is the same
    // trap P4a fixed one level up, re-created inside the pane.
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'), { contentDisabled: true });

    expect(screen.getByTestId('window-first-pane').className).toContain('pointer-events-none');
    const exit = screen.getByTestId('window-first-exit');
    expect(screen.getByTestId('window-first-pane')).not.toContainElement(exit);
    fireEvent.click(exit);
  });

  it('lets a reader close the pick dialog without going anywhere', () => {
    // Every control left in that dialog navigates off the Plan screen, so a cancel-less dialog
    // forces an unwanted map handoff. `onClose` was wired but nothing pinned it: replacing it with
    // a no-op left the whole suite green.
    const { onShowOnMap } = renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    fireEvent.click(screen.getByTestId('window-card-pick'));

    fireEvent.click(screen.getByTestId('window-pick-dialog-backdrop'));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(onShowOnMap).not.toHaveBeenCalled();
  });

  it('hands the dialog the window it belongs to, in the right order', () => {
    // The header reads "<when> · <time>". Swapping the two props left the suite green and the
    // header reading "21:11 · Sunset".
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    fireEvent.click(screen.getByTestId('window-card-pick'));

    expect(screen.getByTestId('window-pick-dialog')).toHaveTextContent('Sunset · 21:11');
  });

  it('opens the pick dialog from a card and routes both of its destinations', () => {
    const { onShowOnMap } = renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

    fireEvent.click(screen.getByTestId('window-card-pick'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('window-pick-dialog-region'));
    expect(onShowOnMap).toHaveBeenCalledWith({
      region: 'Northumberland & Tyneside', date: '2026-08-04', eventType: 'SUNSET',
    });
    // Opening a destination closes the dialog — leaving it over the map it just opened would hide
    // the thing the user asked to see.
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('routes the pick\'s location as a location, not as a region', () => {
    // The two calls take different shapes and open different things: a positional
    // (date, eventType, locationName) focuses one spot, an object focuses a region.
    const { onShowOnMap } = renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

    fireEvent.click(screen.getByTestId('window-card-pick'));
    fireEvent.click(screen.getByTestId('window-pick-dialog-location-action'));

    expect(onShowOnMap).toHaveBeenCalledWith('2026-08-04', 'SUNSET', 'Bamburgh Beach');
  });

  it('routes a spot card to the map as a location in its own window', () => {
    // Same positional form as the pick's location, and deliberately not the object form the rail's
    // region chip uses — a spot card names one place and must land on it, not on its region.
    const { onShowOnMap } = renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

    fireEvent.click(screen.getByTestId('window-spot'));

    expect(onShowOnMap).toHaveBeenCalledWith('2026-08-04', 'SUNSET', 'Bamburgh Beach');
  });

  it('says so plainly when there are no windows', () => {
    renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), []));
    expect(screen.getByTestId('window-first-pane-empty')).toBeInTheDocument();
  });

  it('lifts the batch scores to App, which is what the map handoff reads', () => {
    // Nothing in the rail renders these. They feed `buildMapOverlay`'s narrative and MapView's
    // visibility filter — and MapView treats a location with no rating as stood down and hides it,
    // so without the lift a tile that says "Worth it" opens an overlay over an all-but-empty map.
    const scores = new Map([['N&T|2026-08-04|SUNSET|Bamburgh', { rating: 4 }]]);
    const onEvaluationScoresChange = vi.fn();
    renderWithBriefing(briefingWith('2026-08-04T12:00:00', scores), { onEvaluationScoresChange });

    expect(onEvaluationScoresChange).toHaveBeenCalledWith(scores);
  });

  // ── The rail's pick chip and the card's pick badge are one control with two triggers ──
  //
  // They carry the same words in the same accent, so a reader who taps one and then the other must
  // not be shown two different things. This is the assertion that proves it, and it is the reason
  // the chip opens the EXISTING dialog rather than a second surface of its own.
  describe('the rail pick chip', () => {
    // TWO sunset cards on different dates, and that is the whole point of the fixture rather than
    // incidental setup. With one card the shell's lookup passes on `targetType` alone, so the `date`
    // half is pinned by nothing — verified by mutation: deleting `c.date === date` left all 3028
    // tests green. Two same-event cards is also the real shape, not a contrivance: the picks are
    // forecast-wide rank-1 and rank-2, so a BEST sunset on one day and an ALSO sunset on another
    // puts exactly this in the list. The chip points at the LATER card, so a date-blind lookup
    // returns the earlier one and the prose assertion catches it.
    const OTHER_CARD = {
      ...CARD,
      key: '2026-08-06:SUNSET',
      date: '2026-08-06',
      lead: false,
      kicker: null,
      pick: {
        kind: 'also',
        regionName: 'Yorkshire Dales',
        headline: 'A different sky entirely',
        detail: 'High cloud thins from the south.',
        locationName: 'Malham Cove',
        locationId: 2,
      },
    };

    const withRailPick = () => {
      const base = briefingWith('2026-08-04T12:00:00', new Map(), [CARD, OTHER_CARD]);
      return {
        ...base,
        railTiles: [{
          ...base.railTiles[0],
          // The tile is the 6th, not the 4th: same targetType as the other card, different date.
          date: '2026-08-06',
          dayLabel: 'Thursday',
          isToday: false,
          pick: { kind: 'also', event: 'sunset', targetType: 'SUNSET' },
        }],
      };
    };

    it('opens the same prose the window card opens, word for word', () => {
      renderWithBriefing(withRailPick());
      fireEvent.click(screen.getByTestId('rail-pick-flag'));
      const fromRail = screen.getByTestId('window-pick-dialog').textContent;
      // The LATER card's prose specifically. A lookup that ignored the date would return the first
      // sunset card and this would read "Breaking clear" instead — which is the mutation that
      // walked through the whole suite before this fixture carried two cards.
      expect(fromRail).toContain('A different sky entirely');
      expect(fromRail).toContain('High cloud thins from the south.');
      expect(fromRail).not.toContain('Breaking clear');
      // `renderWithBriefing` returns the harness's handlers, not RTL's result, so the second
      // render needs an explicit teardown or both dialogs would be in the document at once.
      cleanup();

      renderWithBriefing(withRailPick());
      // The badge on the card the chip pointed at — the second one, since both cards carry a pick.
      fireEvent.click(screen.getAllByTestId('window-card-pick')[1]);
      expect(screen.getByTestId('window-pick-dialog').textContent).toBe(fromRail);
    });

    // The shell half of the same guard: the rail only knows to suppress if it is TOLD. Dropping
    // `peeksSuppressed={modalOpen}` passed every other test in the suite.
    it('silences the rail gloss while the pick dialog it opened is up', () => {
      renderWithBriefing(withRailPick());
      fireEvent.click(screen.getByTestId('rail-pick-flag'));
      expect(screen.getByTestId('window-pick-dialog')).toBeInTheDocument();

      fireEvent.mouseEnter(screen.getByTestId('rail-region-chip'));
      expect(screen.queryByTestId('popover-host')).toBeNull();
    });

    it('opens nothing when no window matches, rather than an empty dialog', () => {
      // The guard, exercised through a rail tile flagged for an event no card carries. The lookup
      // is total by construction today; this pins that a future divergence degrades to inert rather
      // than to a dialog about nothing.
      const base = briefingWith('2026-08-04T12:00:00');
      renderWithBriefing({
        ...base,
        railTiles: [{ ...base.railTiles[0], pick: { kind: 'best', event: 'sunrise', targetType: 'SUNRISE' } }],
      });

      fireEvent.click(screen.getByTestId('rail-pick-flag'));
      expect(screen.queryByTestId('window-pick-dialog')).toBeNull();
    });
  });

  it('lifts the seasonal features too, so the map does not depend on which arm you came from', () => {
    // The sibling lift nine lines up, and the reason this one exists is the flag seam rather than
    // the map: `seasonalFeatures` was written by the v1 arm ONLY, so the overlay map's Bluebell chip
    // appeared or not depending on whether the session had ever rendered v1 — the same night's data
    // drawing two different maps. Pinned here because the prop is optional and optional-called, so
    // deleting it from App would drop the chip with the whole suite still green.
    const onSeasonalFeaturesChange = vi.fn();
    const briefing = briefingWith('2026-08-04T12:00:00');
    renderWithBriefing(
      { ...briefing, briefing: { generatedAt: '2026-08-04T12:00:00', seasonalFeatures: ['BLUEBELL'] } },
      { onSeasonalFeaturesChange },
    );

    expect(onSeasonalFeaturesChange).toHaveBeenCalledWith(['BLUEBELL']);
  });

  it('lifts an empty list when the briefing names no season, rather than nothing at all', () => {
    // The negative half, and it is not cosmetic: never calling would leave whatever the OTHER arm
    // last wrote standing, which is the exact staleness this lift exists to remove.
    const onSeasonalFeaturesChange = vi.fn();
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'), { onSeasonalFeaturesChange });

    expect(onSeasonalFeaturesChange).toHaveBeenCalledWith([]);
  });

  describe('the reach lens bar', () => {
    it('sits between the tab rule and the pane, where the design puts it', () => {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

      const rule = screen.getByTestId('window-first-tabrule');
      const lens = screen.getByTestId('window-first-lens');
      const pane = screen.getByTestId('window-first-pane');
      expect(rule.compareDocumentPosition(lens) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(lens.compareDocumentPosition(pane) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    });

    it('stays live when the backend is DOWN, because it filters what is already in memory', () => {
      // The pane and the rail take the greying because they are forecast data. The lens is a
      // client-side filter over data already fetched, so `pointer-events: none` would make a
      // working control look broken in order to say nothing true.
      //
      // ⚠️ Checked up the ANCESTOR CHAIN, not on the element. `pointer-events: none` inherits, so
      // asserting only the bar's own className passes on a version wrapped in a greyed div — a
      // mutation doing exactly that survived the first draft of this test.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'), { contentDisabled: true });

      expect(screen.getByTestId('window-first-pane').className).toContain('pointer-events-none');
      expect(dimmedAncestorOf(screen.getByTestId('window-first-lens'))).toBeNull();
    });

    it('counts the spots the cards actually drew, never the set the lens chose from', () => {
      // §6: "no count describes a set that was never filtered", and its mirror — no count
      // describes a set the page is not showing. `reachTotal` is deliberately much larger than
      // what each card drew here, so summing the wrong field gives 30 rather than 3; without
      // that gap the two readings are numerically identical and the test cannot fail.
      const first = { ...CARD, reachTotal: 12 };
      const second = {
        ...CARD, key: '2026-08-05:SUNRISE', spots: [CARD.spots[0], CARD.spots[0]], reachTotal: 18,
      };
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [first, second]));

      expect(screen.getByTestId('window-first-lens-readout'))
        .toHaveTextContent('45 min · weekday default · any rating · 3 spots across 2 windows');
    });

    it('states the rating floor as well, so the summary names both controls', () => {
      // A summary that names one of two lenses reads as though the page had only one — which was
      // true until the floor arrived, and is exactly the misreading worth the twelve characters.
      renderWithBriefing({
        ...briefingWith('2026-08-04T12:00:00'),
        ratingLens: { ...RATING_LENS, floorId: '4', minRating: 4, floor: { id: '4', min: 4, label: '4★+' } },
      });

      expect(screen.getByTestId('window-first-lens-readout')).toHaveTextContent('4★+');
    });

    it('states what the floor cost, and only once it has cost something', () => {
      // The handoff's rule — "whenever a filter is on, the strip must state what it cost" — with
      // this project's own qualifier from `browseCountLine`: with nothing trimmed, `3 of 3` is a
      // count dressed as a comparison. `reachedTotal` is the denominator, so the two cards below
      // drew 3 of the 9 that reach left.
      const first = { ...CARD, reachTotal: 12, reachedTotal: 5 };
      const second = {
        ...CARD, key: '2026-08-05:SUNRISE', spots: [CARD.spots[0], CARD.spots[0]],
        reachTotal: 18, reachedTotal: 4,
      };
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [first, second]));

      expect(screen.getByTestId('window-first-lens-readout')).toHaveTextContent('3 of 9 spots across 2 windows');
    });

    it('renders the emptied window\'s own sentence, built where both thresholds are known', () => {
      // The card no longer composes this line — with two gates it cannot say which one emptied it,
      // so `buildWindowCards` hands over a descriptor and the card renders it. Pinned here because
      // the shell is what wires the two together.
      const gated = {
        ...CARD,
        spots: [],
        reachTotal: 4,
        reachedTotal: 0,
        lensEmpty: {
          headline: 'Nothing within 45 min in this window.',
          body: '4 spots are further out.',
          actions: [{ kind: 'reach', id: '90', label: 'Try 1h 30min' }],
        },
      };
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [gated]));

      expect(screen.getByTestId('window-card-lens-empty-head'))
        .toHaveTextContent('Nothing within 45 min in this window.');
      expect(screen.getByTestId('window-card-lens-empty-body'))
        .toHaveTextContent('4 spots are further out.');
    });

    it('moves the page-wide lens when an emptied window offers the way out', () => {
      const selectTier = vi.fn();
      const gated = {
        ...CARD,
        spots: [],
        reachTotal: 4,
        reachedTotal: 0,
        lensEmpty: {
          headline: 'Nothing within 45 min in this window.',
          body: '4 spots are further out.',
          actions: [{ kind: 'reach', id: '90', label: 'Try 1h 30min' }],
        },
      };
      renderWithBriefing({
        ...briefingWith('2026-08-04T12:00:00', new Map(), [gated]),
        reachLens: { ...LENS, selectTier },
      });
      fireEvent.click(screen.getByTestId('window-card-lens-loosen'));

      // The BAR's control, not a per-window override: a filter that means something different on
      // each of six cards cannot be read off a sticky bar.
      expect(selectTier).toHaveBeenCalledWith('90');
    });
  });

  describe('the rail footer\'s home prompt', () => {
    it('names the home the reach figures are measured from', () => {
      renderWithBriefing({ ...briefingWith('2026-08-04T12:00:00'), homePlace: 'Morpeth' });
      expect(screen.getByTestId('window-first-home')).toHaveTextContent('Home · Morpeth');
    });

    it('says so when the settings response came back with no home', () => {
      // The normal first-run state, and the reason the bar itself is never suppressed: the lens
      // stays a visible no-op and the prompt that fixes it lives here.
      renderWithBriefing({ ...briefingWith('2026-08-04T12:00:00'), homePlace: null });
      expect(screen.getByTestId('window-first-home')).toHaveTextContent('Home not set');
    });

    it('says nothing at all while it does not know', () => {
      // Telling a user who HAS a home that they have not set one, on the strength of a request
      // that never came back, is a false claim where silence costs nothing. Plan §2.5 forbids a
      // second source of truth for this, which is what makes the third state necessary.
      renderWithBriefing({ ...briefingWith('2026-08-04T12:00:00'), homePlace: undefined });
      expect(screen.queryByTestId('window-first-home')).toBeNull();
      expect(screen.getByTestId('window-first-railfoot').textContent).not.toMatch(/home/i);
    });

    it('offers a route to the settings that set it', () => {
      const { onOpenSettings } = renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
      fireEvent.click(screen.getByRole('button', { name: 'Edit reach' }));
      expect(onOpenSettings).toHaveBeenCalledTimes(1);
    });

    it('keeps that route working when the backend is DOWN', () => {
      // ⚠️ Asserted STRUCTURALLY, not by clicking. The DOWN treatment is `pointer-events: none`
      // on an ancestor, and jsdom applies no CSS — so a `fireEvent.click` fires through it and
      // passes whether the button is greyed or not. The footer lived inside the greyed rail
      // region when this test was written and the click assertion passed anyway; only the
      // containment check failed. The one control that fixes an empty lens must not go inert
      // exactly when a user is most likely to be poking at it.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'), { contentDisabled: true });

      expect(screen.getByTestId('window-first-rail-region').className)
        .toContain('pointer-events-none');
      expect(dimmedAncestorOf(screen.getByTestId('window-first-edit-reach'))).toBeNull();
    });

    it('keeps the forecast\'s age readable when the backend is DOWN', () => {
      // The age becomes MORE useful with a dead backend, not less — it is the only thing on
      // screen saying how stale what you are reading is.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'), { contentDisabled: true });
      expect(dimmedAncestorOf(screen.getByTestId('window-first-age'))).toBeNull();
    });
  });
  describe('the promoted strip', () => {
    const TOMORROW = '2026-08-05';

    /** A badge as `BriefingWindow.Badge` serialises one. */
    const topic = (type, label, rarityRank) => ({
      type,
      label,
      detail: null,
      facts: [{ key: 'k', value: 'v', dir: null, emphasis: true, optional: false }],
      eventTime: null,
      rarityRank,
    });

    /**
     * A context built through the REAL derivation chain from a payload, so what the shell is handed
     * is what the provider would hand it. Only the fetch is stubbed.
     *
     * <p>Both days carry a coincidence by default. That is the point: §6 clause 3 is "renders when a
     * coincidence exists, AND never more than one", and its own wording warns that the second half
     * "passes vacuously on a page that never built the strip at all". A page with one coincidence
     * could not tell those apart.
     */
    const paneWith = (windowsByDate) => {
      const days = Object.entries(windowsByDate).map(([date, window]) => ({
        date,
        eventSummaries: [{
          targetType: 'SUNSET', regions: [], unregioned: [], window: { verdict: 'WORTH_IT', ...window },
        }],
      }));
      const upcoming = days.map((d) => ({ date: d.date, targetType: 'SUNSET' }));
      const cards = buildWindowCards(upcoming, days, '2026-08-04', TOMORROW, new Set(), new Map());
      const paneItems = buildPaneItems(upcoming, cards, new Set(), []);
      return {
        ...briefingWith('2026-08-04T12:00:00', new Map(), cards, paneItems),
        promotedStrip: buildPromotedStrip(paneItems),
      };
    };

    const TWO_COINCIDENCES = {
      // Rank 8 — the loser, and deliberately the EARLIER day, so a selection that simply took the
      // first coincidence it found would pick this one.
      '2026-08-04': {
        badges: [topic('SNOW_TOPS', 'Snow on the fells', 8), topic('SNOW_FRESH', 'Fresh snow', 10)],
        topRarityRank: 8,
      },
      // Rank 3 — the winner.
      [TOMORROW]: {
        badges: [topic('KING_TIDE', 'King tide', 3), topic('AURORA', 'Aurora', 4)],
        topRarityRank: 3,
      },
    };

    it('renders exactly one strip when two windows carry a coincidence, and it is the rarest', () => {
      renderWithBriefing(paneWith(TWO_COINCIDENCES));

      expect(screen.getAllByTestId('window-first-promo')).toHaveLength(1);
      expect(screen.getByTestId('window-first-promo-when')).toHaveTextContent('Tomorrow sunset');
    });

    it('renders no strip when no window carries a coincidence', () => {
      renderWithBriefing(paneWith({
        '2026-08-04': { badges: [topic('AURORA', 'Aurora', 4)], topRarityRank: 4 },
        [TOMORROW]: { badges: [] },
      }));
      expect(screen.queryByTestId('window-first-promo')).toBeNull();
    });

    it('sits inside the pane, above every window card', () => {
      renderWithBriefing(paneWith(TWO_COINCIDENCES));

      const pane = screen.getByTestId('window-first-pane');
      const strip = screen.getByTestId('window-first-promo');
      expect(pane).toContainElement(strip);
      // Nothing precedes it in the pane — the assertion the existing DOM-order tests do not make,
      // because until now nothing sat above the first card.
      expect(pane.firstElementChild).toBe(strip);
      screen.getAllByTestId('window-card').forEach((card) => {
        expect(strip.compareDocumentPosition(card) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      });
    });

    // Forecast content, so it takes the treatment that marks a dead backend — unlike the masthead,
    // the rail footer and the exit hatch, which are routes and must keep working.
    it('takes the pane\'s DOWN treatment', () => {
      renderWithBriefing(paneWith(TWO_COINCIDENCES), { contentDisabled: true });
      expect(dimmedAncestorOf(screen.getByTestId('window-first-promo'))).not.toBeNull();
    });

    it('opens the promoted window\'s card and leaves the reader on it', () => {
      renderWithBriefing(paneWith(TWO_COINCIDENCES));

      // Precondition: the promoted window is the SECOND card, and the lead-open default has left it
      // collapsed. Without this the test could pass against a card that was already open.
      const expanders = screen.getAllByTestId('window-card-expander');
      expect(expanders[1]).toHaveAttribute('aria-expanded', 'false');

      fireEvent.click(screen.getByTestId('window-first-promo-go'));

      expect(screen.getAllByTestId('window-card-expander')[1])
        .toHaveAttribute('aria-expanded', 'true');
      // Focus lands on that card's own control, whose name repeats the window just asked for.
      expect(document.activeElement).toHaveAccessibleName('Collapse Tomorrow sunset');
    });

    // The lead card is open by default and the strip sits directly above it, so a route would scroll
    // to the element immediately beneath — a control with no visible effect.
    it('offers no route when the promoted window is the pane\'s first card', () => {
      renderWithBriefing(paneWith({
        '2026-08-04': {
          badges: [topic('KING_TIDE', 'King tide', 3), topic('AURORA', 'Aurora', 4)],
          topRarityRank: 3,
        },
      }));
      expect(screen.getByTestId('window-first-promo')).toBeInTheDocument();
      expect(screen.queryByTestId('window-first-promo-go')).toBeNull();
    });
  });
});
