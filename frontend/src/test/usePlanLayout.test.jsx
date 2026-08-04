import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import React from 'react';
import usePlanLayout, { PLAN_LAYOUT_KEY, PLAN_V1, PLAN_V2 } from '../hooks/usePlanLayout.js';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

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

  it('renders one tab, selected, and no tab whose pane does not exist', () => {
    // The design draws four. Coming up is P13; Map and Manage arrive when this subtree takes over
    // view state. A tab that renders nothing is a demo control and §6 bans those from the shipped
    // build — so this pins that each tab lands WITH its pane rather than ahead of it.
    renderShell();

    const tabs = screen.getAllByRole('tab');
    expect(tabs).toHaveLength(1);
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
    // Exactly "Plan": the ◉ is decorative and must not leak into the accessible name, which is the
    // rule ViewToggle:56 already follows for its own glyphs.
    expect(tabs[0]).toHaveAccessibleName('Plan');
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
    // an empty "forecast" line is a claim with nothing behind it.
    renderShell();
    expect(screen.queryByTestId('window-first-railfoot')).toBeNull();
  });
});

describe('WindowFirstShell — the rail it hosts', () => {
  const briefingWith = (generatedAt, evaluationScores = new Map()) => ({
    briefing: generatedAt ? { generatedAt } : null,
    loading: false,
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
      evaluationScores: new Map(),
      todayStr: '2026-08-04',
      tomorrowStr: '2026-08-05',
    });
    expect(screen.queryByTestId('window-first-rail-empty')).toBeNull();
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
});
