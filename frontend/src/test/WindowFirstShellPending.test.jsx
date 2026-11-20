import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { act, render, screen } from '@testing-library/react';
import React from 'react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

// `useComingUpFeed` is called unconditionally at the shell level (`WindowFirstShell.jsx:581`,
// `useComingUpFeed(true, todayStr)`), regardless of which tab is active, so every render here
// would otherwise fire a REAL `getAlmanac()` axios call — mock at the API-module boundary, the
// house rule (frontend-test-standards.md), the same shape `WindowFirstShellInitialTab.test.jsx`
// and `WindowFirstShellBadge.test.jsx` already use. No `settingsApi` mock: unlike Badge's file,
// nothing here exercises `Mark seen`.
vi.mock('../api/almanacApi.js', () => ({
  getAlmanac: vi.fn(),
  ALMANAC_DAYS: 90,
}));

/**
 * The Plan pane's three states while the briefing is in flight — written up after the
 * 2026-09-30 incident: a `GET /api/briefing` request that never completed in production, timing
 * out against Cloudflare's 100s limit (36s was a lab measurement on production-sized data, not a
 * production observation), fixed in #957. The pane below the lens bar rendered NOTHING at all for the
 * whole of that wait, on phone and desktop alike — no matrix, no doors, no count line, not even
 * the existing "No forecast to show." sentence, which is gated on the fetch having already
 * finished — which is what made diagnosing the slow backend a long chain of inference from
 * absence rather than a line on screen.
 *
 * <p>`WindowFirstShell.test.jsx` already covers "says nothing about the FORECAST while the first
 * fetch is in flight" (the pending line makes no claim about the sky) and "says so plainly when
 * there are no windows" (the loaded-empty case). This file is about the third state the fix adds
 * — the slow clause — and about the always-mounted status wrapper the three states share.
 *
 * <p>A request that ultimately FAILS (as the real incident did) still ends on "No forecast to
 * show.": `WindowFirstBriefingContext.fetchBriefing` clears `loading` in its `finally` regardless
 * of outcome, and that one-way, outcome-blind meaning of `loading` is pre-existing and out of
 * scope here — a distinct "could not load" state is a product call this change does not make.
 */

/** The reach lens exactly as `useReachLens` shapes it — copied rather than imported, because
 * `WindowFirstShell.test.jsx` keeps its own copy un-exported (same fixture-per-file style). */
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

/** The bar's second axis, on the same terms. */
const RATING_LENS = {
  floor: { id: 'any', min: null, label: 'Any rating' },
  floorId: 'any',
  minRating: null,
  selectFloor: () => {},
};

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
  pick: null,
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

/**
 * The context shape `useWindowFirstBriefing` hands the shell — a base every test here overrides
 * `loading`/`windowCards`/`paneItems`/`heatStripCards`/`heatSpots`/`heatPointSets` on, since those
 * six are what decide which of the three pane states renders.
 *
 * <p>Returns a FRESH object (and fresh `paneItems`/`travelDayDates`/etc. references) on every
 * call, deliberately: a mount that stays in `loading` for several seconds re-renders many times
 * over real life (a 30s health SSE event, a poll, a focus event) without `loading` itself ever
 * changing, and a test that only ever hands the shell ONE frozen context object could not catch a
 * mutant that re-arms the slow timer on any of those unrelated re-renders.
 */
const baseContext = (overrides = {}) => ({
  briefing: { generatedAt: '2026-08-04T12:00:00' },
  loading: false,
  windowCards: [],
  paneItems: [],
  upcomingEvents: [],
  travelDayDates: new Set(),
  reachById: new Map(),
  isPro: true,
  isLiteUser: false,
  evaluationScores: new Map(),
  heatStripCards: [],
  heatSpots: [],
  heatPointSets: new Map(),
  todayStr: '2026-08-04',
  tomorrowStr: '2026-08-05',
  reachLens: LENS,
  ratingLens: RATING_LENS,
  homePlace: undefined,
  ...overrides,
});

const loadingEmpty = () => baseContext({ briefing: null, loading: true });
const loadedEmpty = () => baseContext({ loading: false, windowCards: [], paneItems: [] });

const loadedWithCards = () => baseContext({
  loading: false,
  windowCards: [CARD],
  paneItems: [{ kind: 'card', key: CARD.key, card: CARD }],
  heatStripCards: [{
    key: CARD.key,
    date: CARD.date,
    targetType: CARD.targetType,
    dow: 'Tue',
    sunrise: false,
    label: 'Tonight Sunset',
    time: '21:11',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    pickKind: null,
    away: false,
    confidence: 'high',
    pool: [],
    bestReach: null,
    badges: [],
  }],
  heatSpots: [
    { id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, regionName: 'Northumberland & Tyneside', rid: 'Northumberland & Tyneside', skySubject: true, bortleClass: 3, scores: [4] },
  ],
  heatPointSets: new Map([
    [CARD.key, [{ id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, rid: 'Northumberland & Tyneside', r: [4] }]],
  ]),
});

const PENDING_TEXT = 'Loading the forecast…';
const SLOW_TEXT = 'Still loading the forecast — taking longer than usual.';
const EMPTY_TEXT = 'No forecast to show.';

const renderWithBriefing = (ctx) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx);
  return render(<WindowFirstShell onOpenSettings={vi.fn()} onSignOut={vi.fn()} />);
};

/** Re-mounts the SAME element with a newly-stubbed context, for the `rerender` tests below. */
const rerenderWithBriefing = (rerender, ctx) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx);
  return rerender(<WindowFirstShell onOpenSettings={vi.fn()} onSignOut={vi.fn()} />);
};

describe('WindowFirstShell — the Plan pane\'s pending state', () => {
  afterEach(() => vi.restoreAllMocks());

  it('shows the pending line, inside the status wrapper, while loading with nothing yet', () => {
    renderWithBriefing(loadingEmpty());

    // Asserted on the WRAPPER, not only on the inner testid — the accessibility point of this
    // change is that the status role itself announces the text, so a mutant that moved the line
    // to a sibling OUTSIDE the wrapper (still passing every testid-scoped query) must fail here.
    expect(screen.getByTestId('window-first-pane-status')).toHaveTextContent(PENDING_TEXT);
    expect(screen.queryByTestId('window-first-pane-empty')).toBeNull();
    expect(screen.getByTestId('window-first-pane-status')).toHaveAttribute('role', 'status');
  });

  it('shows the empty line, inside the status wrapper, once loaded with nothing to show', () => {
    renderWithBriefing(loadedEmpty());

    expect(screen.getByTestId('window-first-pane-status')).toHaveTextContent(EMPTY_TEXT);
    expect(screen.queryByTestId('window-first-pane-pending')).toBeNull();
  });

  it('shows neither line once loaded with cards, but keeps the status wrapper mounted and empty', async () => {
    renderWithBriefing(loadedWithCards());

    // Awaited because the strip sits behind a lazy() boundary — see WindowFirstShell's own note.
    await screen.findByTestId('wf-heat-strip');

    const status = screen.getByTestId('window-first-pane-status');
    expect(screen.queryByTestId('window-first-pane-pending')).toBeNull();
    expect(screen.queryByTestId('window-first-pane-empty')).toBeNull();
    // Always-mounted is the point (`docs/engineering/window-first-redesign-plan.md` §5f, the
    // WindowSpotSheet result-count precedent): a status role added only once there is something
    // to announce is not reliably announced at all.
    expect(status).toBeInTheDocument();
    expect(status).toBeEmptyDOMElement();
    // `wf-pane-status` is what cancels the flex gap this empty-but-mounted wrapper would
    // otherwise add on top of `.wf-body`'s own (index.css, round-1 review finding 1) — see
    // `windowFirstPaneStatusGap.test.js` for the CSS-level pin of the two rules' magnitudes.
    expect(status).toHaveClass('wf-pane-status');
  });

  describe('the slow clause', () => {
    // Plain fake timers, not `shouldAdvanceTime` — none of these tests await a `findBy*` (the
    // pending line renders synchronously, since it sits outside the heat strip's `Suspense`
    // boundary), so there is nothing here that needs real wall-clock time to leak into the fake
    // clock. The 10s pending→slow transition is driven entirely by `vi.advanceTimersByTime`.
    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    it('stays on the plain pending line just under 10s', async () => {
      renderWithBriefing(loadingEmpty());
      await act(async () => { vi.advanceTimersByTime(9_999); });

      expect(screen.getByTestId('window-first-pane-status')).toHaveTextContent(PENDING_TEXT);
    });

    it('admits it is taking longer than usual at 10s, REPLACING the plain line rather than appending to it', async () => {
      renderWithBriefing(loadingEmpty());
      await act(async () => { vi.advanceTimersByTime(10_000); });

      const status = screen.getByTestId('window-first-pane-status');
      expect(status).toHaveTextContent(SLOW_TEXT);
      // `toHaveTextContent` is a substring match, so a mutant that APPENDS the slow sentence
      // after the plain one (rather than switching between them) would still satisfy the line
      // above. This is the discriminator — case-sensitive, since the slow sentence's own
      // lowercase "loading" would otherwise satisfy a case-insensitive match too.
      expect(status).not.toHaveTextContent(PENDING_TEXT);
    });

    it('does not re-arm the slow timer on an ordinary re-render while still loading', async () => {
      // The provider re-renders its consumers often while a fetch is in flight — a 30s health SSE
      // event, a poll, a focus handler — without `loading` itself ever changing. A mutant that
      // added a per-render-changing dependency (e.g. the `paneItems` array) would restart the 10s
      // clock on each such render and the slow sentence would never show — the exact hazard
      // `WindowFirstShell.jsx`'s `PREFERRED_TAB_GRACE_MS` timer documents for its own, differently
      // -shaped grace timer. `loadingEmpty()` is called twice here for exactly that reason: same
      // `loading: true`, but a NEW `paneItems` reference each time — this test's own `baseContext`
      // builds one fresh, unlike the provider's real `paneItems` (memoised on
      // `[upcomingEvents, windowCards, travelDayDates]`, `WindowFirstBriefingContext.jsx`), to
      // model one of those memo's own inputs changing independently of `loading` — e.g. the
      // travel-day data landing mid-load from its own, separate fetch.
      const { rerender } = renderWithBriefing(loadingEmpty());
      await act(async () => { vi.advanceTimersByTime(5_000); });

      rerenderWithBriefing(rerender, loadingEmpty());
      await act(async () => { vi.advanceTimersByTime(5_000); });

      expect(screen.getByTestId('window-first-pane-status')).toHaveTextContent(SLOW_TEXT);
    });

    it('clears the still-pending timer once loading ends, rather than leaving it armed', async () => {
      const { rerender } = renderWithBriefing(loadingEmpty());
      await act(async () => { vi.advanceTimersByTime(5_000); });

      const before = vi.getTimerCount();
      rerenderWithBriefing(rerender, loadedEmpty());
      // The timeout this effect armed is cleared by its own cleanup the instant `loading` goes
      // false — provably, via the fake-timer count, rather than merely by the DOM already having
      // moved past the state the cleared timer would have affected (round-1 review finding 2:
      // advancing to 10s BEFORE re-rendering would let the timer fire on its own first, so
      // deleting the cleanup would survive unnoticed).
      expect(vi.getTimerCount()).toBe(before - 1);

      // This second half is NOT itself evidence of the clear — with `clearTimeout` deleted, the
      // late `setPendingSlow(true)` still never reaches the DOM here, because the pending/slow
      // text is gated on `loading`, which is already false. Only the `vi.getTimerCount()`
      // assertion above kills that mutant; this one just checks the visible state stays settled.
      await act(async () => { vi.advanceTimersByTime(60_000); });
      const status = screen.getByTestId('window-first-pane-status');
      expect(status).toHaveTextContent(EMPTY_TEXT);
      expect(status).not.toHaveTextContent(SLOW_TEXT);
    });
  });
});
