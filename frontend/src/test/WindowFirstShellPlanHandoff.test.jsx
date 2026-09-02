import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * The Map tab callout's "Open in Plan" handoff (map-tab-v2-plan.md §3 P9) — `App.jsx`'s
 * `openLocationInPlan`, `openFullMapTab`'s shape in reverse. Scoped like `WindowFirstShellTabs.
 * test.jsx`'s own "a tab requested from outside the bar" block, which this handoff's `tabRequest`
 * half reuses verbatim — this file is about the SECOND half: routing through `selectTab` so the
 * sheet lands as the ONLY dialog layer, and the nonce guard that keeps a handoff already in flight
 * at mount (or repeated verbatim) from replaying.
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

const MAP = <p data-testid="map-pane">map pane</p>;

/** Mirrors `WindowFirstShellTabs.test.jsx`'s own `renderWithRequest`, extended with
 * `planLocationHandoff` so a test can move it independently of `tabRequest`. */
function renderWithHandoff(extra = {}) {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
  const base = {
    onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(),
    locations: [], mapPane: MAP, ...extra,
  };
  const view = render(<WindowFirstShell {...base} />);
  return {
    ...view,
    setHandoff: (planLocationHandoff) => view.rerender(<WindowFirstShell {...base} planLocationHandoff={planLocationHandoff} />),
  };
}

afterEach(() => vi.restoreAllMocks());

describe('WindowFirstShell — the Map callout\'s "Open in Plan" handoff', () => {
  it('switches to Plan and opens that location\'s four-day sheet', async () => {
    const { setHandoff } = renderWithHandoff();
    fireEvent.click(screen.getByRole('tab', { name: 'Map' }));
    expect(screen.getByRole('tab', { name: 'Map' })).toHaveAttribute('aria-selected', 'true');

    act(() => setHandoff({
      id: 42, name: 'Bamburgh', regionName: 'North East', nonce: 1,
    }));

    expect(screen.getByRole('tab', { name: 'Plan' })).toHaveAttribute('aria-selected', 'true');
    const sheet = await screen.findByTestId('location-sheet');
    expect(screen.getByTestId('location-sheet-title')).toHaveTextContent('Bamburgh');
    expect(sheet).toBeInTheDocument();
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

    act(() => setHandoff({ id: 1, name: 'Spot 1', regionName: 'Northumberland & Tyneside', nonce: 1 }));

    const dialogs = await screen.findAllByRole('dialog');
    expect(dialogs).toHaveLength(1);
    expect(screen.getByTestId('location-sheet')).toBeInTheDocument();
    expect(screen.queryByTestId('window-sheet')).toBeNull();
  });

  it('does not replay a handoff that was already in flight when the shell mounted (hidden-pane rule)', () => {
    // Mirrors `tabRequest`'s own identical guard: `App` holds `planLocationHandoff` and never
    // clears it, so a null-seeded ref on any remount would replay the last handoff — landing the
    // reader on a sheet for a place they never asked to see this session, from a tab (the Map pane)
    // that may not even be the one they are looking at.
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
    render(
      <WindowFirstShell
        onOpenSettings={vi.fn()} onSignOut={vi.fn()} onShowOnMap={vi.fn()}
        locations={[]} mapPane={MAP}
        planLocationHandoff={{ id: 1, name: 'Spot 1', regionName: 'North East', nonce: 4 }}
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

  it('never leaves focus stranded at <body>', async () => {
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
    act(() => setHandoff({ id: 1, name: 'Spot 1', regionName: 'North East', nonce: 1 }));

    const dialog = await screen.findByTestId('location-sheet');
    expect(document.activeElement).not.toBe(document.body);
    const onTab = document.activeElement === screen.getByRole('tab', { name: 'Plan' });
    const onDialog = dialog.contains(document.activeElement);
    expect(onTab || onDialog).toBe(true);
    raf.mockRestore();
  });
});
