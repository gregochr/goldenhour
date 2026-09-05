import React from 'react';
import { describe, it, expect, vi, afterEach, beforeAll } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import warmPlanChunks from './warmPlanChunks.js';

/**
 * The Map tab callout's four-day-sheet handoff (map-tab-v2-plan.md §3 P9) — `App.jsx`'s
 * `openLocationSheet`, `openFullMapTab`'s shape in reverse. Scoped like `WindowFirstShellTabs.
 * test.jsx`'s own "a tab requested from outside the bar" block — this file is about routing through
 * `selectTab` so the sheet lands as the ONLY dialog layer, the `inPlan` flag that decides whether
 * the TAB moves with it, and the nonce guard that keeps a handoff already in flight at mount (or
 * repeated verbatim) from replaying.
 *
 * <p>⚠️ `inPlan` is the callout's two routes into one sheet. `Open in Plan` names the Plan tab and
 * goes there; the clamped prose's `Four days here ›` is a peek that leaves the reader wherever they
 * were — on the Map tab, with the map still behind the sheet — so dismissing it returns them to the
 * selection they opened it from. A handoff that moved the tab on BOTH routes is the defect this
 * flag exists to prevent, and it shipped once.
 *
 * `WindowFirstDoors` is stubbed for the reason `WindowFirstShellTabs.test.jsx` gives: mounting it
 * fires an astro request per visible date, which this file is not about.
 */
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

const TODAY = '2026-08-08';

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

const ctx = (overrides = {}) => ({
  briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [] },
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

/** A real focusable control inside the stubbed pane, so the peek route's return address can be
 *  driven and asserted the way the callout button drives it in the app. */
const MAP = (
  <p data-testid="map-pane">
    map pane
    <button type="button" data-testid="map-pane-trigger">Four days here</button>
  </p>
);

/** Mirrors `WindowFirstShellTabs.test.jsx`'s own `renderWithRequest`, extended with
 * `locationSheetHandoff` so a test can move it independently of `tabRequest`. */
function renderWithHandoff(extra = {}) {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  const base = {
    onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(),
    locations: [], mapPane: MAP, ...extra,
  };
  const view = render(<WindowFirstShell {...base} />);
  return {
    ...view,
    setHandoff: (locationSheetHandoff) => view.rerender(<WindowFirstShell {...base} locationSheetHandoff={locationSheetHandoff} />),
  };
}

afterEach(() => vi.restoreAllMocks());

// Pays the shell's four `lazy()` boundaries once per FILE, in a hook with its own budget, rather
// than inside whichever test happens to run first. See `warmPlanChunks.js` for the measurements
// and for the full-suite reproduction that made this necessary.
beforeAll(warmPlanChunks);

describe('WindowFirstShell — the Map callout\'s four-day-sheet handoff', () => {
  it('switches to Plan and opens that location\'s four-day sheet when `inPlan` is set', async () => {
    const { setHandoff } = renderWithHandoff();
    fireEvent.click(screen.getByRole('tab', { name: 'Map' }));
    expect(screen.getByRole('tab', { name: 'Map' })).toHaveAttribute('aria-selected', 'true');

    act(() => setHandoff({
      id: 42, name: 'Bamburgh', regionName: 'North East', inPlan: true, nonce: 1,
    }));

    expect(screen.getByRole('tab', { name: 'Plan' })).toHaveAttribute('aria-selected', 'true');
    const sheet = await screen.findByTestId('location-sheet');
    expect(screen.getByTestId('location-sheet-title')).toHaveTextContent('Bamburgh');
    expect(sheet).toBeInTheDocument();
  });

  it('WITHOUT `inPlan`, opens the sheet over the MAP and leaves the tab exactly where it was', async () => {
    // The owner ask this flag exists for: "I'd like it to stay with the map behind, then I can back
    // track on my user journey." The map pane must still be MOUNTED and VISIBLE underneath — a tab
    // switch would have hidden it, and `hidden` is what the assertion below actually catches.
    const { setHandoff } = renderWithHandoff();
    fireEvent.click(screen.getByRole('tab', { name: 'Map' }));

    act(() => setHandoff({
      id: 42, name: 'Bamburgh', regionName: 'North East', nonce: 1,
    }));

    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
    expect(screen.getByTestId('location-sheet-title')).toHaveTextContent('Bamburgh');
    expect(screen.getByRole('tab', { name: 'Map' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('tab', { name: 'Plan' })).toHaveAttribute('aria-selected', 'false');
    expect(screen.getByTestId('window-first-panel-map')).not.toHaveAttribute('hidden');
    expect(screen.getByTestId('map-pane')).toBeInTheDocument();
  });

  it('hands focus BACK to the presser when the peek closes — the return address, not the Plan tab', async () => {
    // ⚠️ This asserts the CAPTURE by its consequence, and that is the whole design of the test.
    // A version asserting the steady state right after the sheet opens ("focus is not the Plan
    // tab") passes with the peek's focus guard deleted, because `Modal`'s own focus-on-open then
    // claims the dialog a moment later and hides the difference — mutation-proven, and the reason
    // this one closes the sheet before it looks. `useDialogFocus` records `document.activeElement`
    // at MOUNT and restores it on unmount, so if the shell moves focus to the Plan tab on this
    // route the reader is handed back to a tab they never pressed, which is exactly the owner ask
    // ("back track on my user journey") failing.
    const raf = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => { cb(); return 0; });
    const { setHandoff } = renderWithHandoff();
    fireEvent.click(screen.getByRole('tab', { name: 'Map' }));
    const trigger = screen.getByTestId('map-pane-trigger');
    trigger.focus();
    expect(document.activeElement).toBe(trigger);

    act(() => setHandoff({ id: 42, name: 'Bamburgh', regionName: 'North East', nonce: 1 }));
    await screen.findByTestId('location-sheet');

    fireEvent.click(screen.getByTestId('location-sheet-close'));
    expect(screen.queryByTestId('location-sheet')).toBeNull();
    expect(document.activeElement).toBe(trigger);
    raf.mockRestore();
  });

  it('lands the sheet as the ONLY dialog layer, taking any open window popup down with it', async () => {
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
    const withSpots = { ...card(), spots, allSpots: spots, reachTotal: spots.length };
    const { setHandoff } = renderWithHandoff();
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx({
      windowCards: [withSpots],
      heatStripCards: [{
        key: withSpots.key,
        date: withSpots.date,
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
        id: 1, name: 'Spot 1', lat: 55.61, lng: -1.71, regionName: 'Northumberland & Tyneside', rid: 'Northumberland & Tyneside', skySubject: true, bortleClass: 3, scores: [4],
      }],
    }));
    // Force the new context to actually apply — the shell has to re-render once for the strip to
    // exist before it can be clicked.
    fireEvent.click(screen.getByRole('tab', { name: 'Plan' }));

    await screen.findByTestId('wf-heat-strip');
    await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]); });
    await screen.findByTestId('window-sheet');
    fireEvent.click(screen.getByTestId('window-spot-all'));
    expect(screen.getAllByRole('dialog').length).toBe(2);

    act(() => setHandoff({
      id: 1, name: 'Spot 1', regionName: 'Northumberland & Tyneside', inPlan: true, nonce: 1,
    }));

    const dialogs = await screen.findAllByRole('dialog');
    expect(dialogs).toHaveLength(1);
    expect(screen.getByTestId('location-sheet')).toBeInTheDocument();
    expect(screen.queryByTestId('window-sheet')).toBeNull();
  });

  it('lands as the only layer on the PEEK route TOO — the route that never calls selectTab(\'plan\')', async () => {
    // ⚠️ The tab-moving twin above cannot cover this. That one clears through `selectTab('plan')`;
    // the peek clears through `selectTab(effectiveTab)`, and replacing that call with
    // `if (toPlanTab) selectTab('plan')` leaves the whole file green without this test —
    // mutation-proven. What it pins is the reason the peek routes through `selectTab` at all:
    // there is exactly ONE definition of "take every dialog this shell owns down", and both routes
    // are it.
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
    const withSpots = { ...card(), spots, allSpots: spots, reachTotal: spots.length };
    const { setHandoff } = renderWithHandoff();
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx({
      windowCards: [withSpots],
      heatStripCards: [{
        key: withSpots.key,
        date: withSpots.date,
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
        id: 1, name: 'Spot 1', lat: 55.61, lng: -1.71, regionName: 'Northumberland & Tyneside', rid: 'Northumberland & Tyneside', skySubject: true, bortleClass: 3, scores: [4],
      }],
    }));
    fireEvent.click(screen.getByRole('tab', { name: 'Plan' }));

    await screen.findByTestId('wf-heat-strip');
    await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]); });
    await screen.findByTestId('window-sheet');
    expect(screen.getAllByRole('dialog').length).toBe(1);

    // No `inPlan` — the peek. The popup must still come down with it.
    act(() => setHandoff({ id: 1, name: 'Spot 1', regionName: 'Northumberland & Tyneside', nonce: 1 }));

    const dialogs = await screen.findAllByRole('dialog');
    expect(dialogs).toHaveLength(1);
    expect(screen.getByTestId('location-sheet')).toBeInTheDocument();
    expect(screen.queryByTestId('window-sheet')).toBeNull();
    // And the tab did not move on the way — the whole point of this route.
    expect(screen.getByRole('tab', { name: 'Plan' })).toHaveAttribute('aria-selected', 'true');
  });

  it('stamps inPlace on the sheet footer\'s map door, from the tab in force', async () => {
    // ⚠️ Door or not-a-door is decided HERE, because only the shell knows which tab is up. Pressed
    // from a sheet over the MAP the same footer must not carry a door payload: `App`'s in-place
    // branch records what a door would silently undo there (the map's own rating floor, its reach
    // tier, its scope and its camera, plus a breadcrumb claiming the reader came from the Plan tab).
    const spots = [{
      key: '1',
      locationId: 42,
      locationName: 'Bamburgh',
      regionName: 'North East',
      rating: 4,
      driveMinutes: 25,
      distanceMiles: 12,
      far: false,
    }];
    const onOpenMapTab = vi.fn();
    const { setHandoff } = renderWithHandoff({ onOpenMapTab });
    // The footer's map button needs a WINDOW to name, which needs `heatStripCards` — the same
    // re-mock-then-force-a-render idiom the only-layer tests above use.
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx({
      heatStripCards: [{
        key: `${TODAY}:SUNSET`,
        date: TODAY,
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
    }));
    fireEvent.click(screen.getByRole('tab', { name: 'Map' }));
    expect(screen.getByRole('tab', { name: 'Map' })).toHaveAttribute('aria-selected', 'true');

    act(() => setHandoff({
      id: 42, name: 'Bamburgh', regionName: 'North East', date: TODAY, targetType: 'SUNSET', nonce: 1,
    }));
    await screen.findByTestId('location-sheet');

    await act(async () => { fireEvent.click(screen.getByTestId('location-sheet-map')); });

    expect(onOpenMapTab).toHaveBeenCalledTimes(1);
    expect(onOpenMapTab).toHaveBeenCalledWith(expect.objectContaining({ inPlace: true }));
  });

  it('does not replay a handoff that was already in flight when the shell mounted (hidden-pane rule)', () => {
    // Mirrors `tabRequest`'s own identical guard: `App` holds `locationSheetHandoff` and never
    // clears it, so a null-seeded ref on any remount would replay the last handoff — landing the
    // reader on a sheet for a place they never asked to see this session, from a tab (the Map pane)
    // that may not even be the one they are looking at.
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
    render(
      <WindowFirstShell
        onOpenSettings={vi.fn()} onSignOut={vi.fn()} onShowOnMap={vi.fn()}
        locations={[]} mapPane={MAP}
        locationSheetHandoff={{ id: 1, name: 'Spot 1', regionName: 'North East', nonce: 4 }}
      />,
    );
    expect(screen.getByRole('tab', { name: 'Plan' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.queryByTestId('location-sheet')).toBeNull();
  });

  it('does not reopen a closed sheet when the SAME nonce arrives again', async () => {
    const { setHandoff } = renderWithHandoff();
    act(() => setHandoff({ id: 1, name: 'Spot 1', regionName: 'North East', nonce: 1 }));
    await screen.findByTestId('location-sheet');

    fireEvent.click(screen.getByTestId('location-sheet-close'));
    expect(screen.queryByTestId('location-sheet')).toBeNull();

    // The identical object reference AND the identical nonce — a naive prop-identity check would
    // still be tempted to re-fire on the second `setHandoff` call below since it is a fresh render;
    // only the nonce comparison stops it.
    act(() => setHandoff({ id: 1, name: 'Spot 1', regionName: 'North East', nonce: 1 }));
    expect(screen.queryByTestId('location-sheet')).toBeNull();
  });

  it('fires again on a REPEAT ask for the SAME location, because the nonce moved', async () => {
    const { setHandoff } = renderWithHandoff();
    act(() => setHandoff({ id: 1, name: 'Spot 1', regionName: 'North East', nonce: 1 }));
    await screen.findByTestId('location-sheet');
    fireEvent.click(screen.getByTestId('location-sheet-close'));

    act(() => setHandoff({ id: 1, name: 'Spot 1', regionName: 'North East', nonce: 2 }));
    expect(await screen.findByTestId('location-sheet')).toBeInTheDocument();
  });

  it('never leaves focus stranded at <body> on the tab-moving route', async () => {
    // The shell's own `requestAnimationFrame`-deferred move (mirroring `tabRequest`'s identical
    // focus rule) targets the Plan tab; `LocationFourDaySheet`'s lazy chunk resolves fast enough in
    // this suite (already imported by an earlier test in this file) that `Modal`'s own focus-on-open
    // typically claims focus onto the dialog itself before this assertion ever runs, which is the
    // MORE correct destination — the reader arrived at a specific place, not merely a tab. Either
    // outcome is a pass; <body> is the one failure this handoff exists to rule out (`App.jsx`'s own
    // comment on the map-hatch's identical focus problem, applied in reverse).
    const raf = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => { cb(); return 0; });
    const { setHandoff } = renderWithHandoff();
    fireEvent.click(screen.getByRole('tab', { name: 'Map' }));
    act(() => setHandoff({
      id: 1, name: 'Spot 1', regionName: 'North East', inPlan: true, nonce: 1,
    }));

    const dialog = await screen.findByTestId('location-sheet');
    expect(document.activeElement).not.toBe(document.body);
    const onTab = document.activeElement === screen.getByRole('tab', { name: 'Plan' });
    const onDialog = dialog.contains(document.activeElement);
    expect(onTab || onDialog).toBe(true);
    raf.mockRestore();
  });
});
