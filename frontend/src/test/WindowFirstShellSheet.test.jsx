import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { pressMenuRow } from './shellMapDoor.js';

/**
 * The shell's own dialog wiring around a four-day location sheet: the routes into settings, search, and the closer
 * `App` is handed. (The window popup and the drill-down opened from it went in G2b, glance-to-map-plan.md; the Map's
 * own spot list is covered by `WindowFirstShellMapList.test.jsx`.)
 */
const seen = vi.hoisted(() => ({ strip: null }));

vi.mock('../components/WindowFirstHeatStrip.jsx', async (importOriginal) => {
  const actual = await importOriginal();
  const Real = actual.default;
  return { ...actual, default: (props) => { seen.strip = props; return <Real {...props} />; } };
});

const TODAY = '2026-08-08';

function spot(overrides = {}) {
  return {
    key: '1',
    locationId: 1,
    locationName: 'Bamburgh Beach',
    regionName: 'Northumberland & Tyneside',
    rating: 4,
    driveMinutes: 30,
    distanceMiles: 21,
    far: false,
    ...overrides,
  };
}

const NEAR = spot({ key: '1', locationName: 'Bamburgh Beach', rating: 4, driveMinutes: 30 });
const FAR = spot({ key: '2', locationName: 'Wastwater', rating: 5, driveMinutes: 180, far: true });

function card(overrides = {}) {
  const allSpots = overrides.allSpots ?? [NEAR, FAR];
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
    reachTotal: allSpots.length,
    ...overrides,
    allSpots,
  };
}

const LOCATIONS = [
  { id: 1, name: 'Bamburgh Beach', locationType: ['SEASCAPE'] },
  { id: 2, name: 'Wastwater', locationType: ['LANDSCAPE'] },
];

const ctx = (overrides = {}) => {
  const cards = overrides.windowCards ?? [card()];
  return {
    briefing: { generatedAt: `${TODAY}T12:00:00`, hotTopics: [] },
    loading: false,
    // ⚠️ The matrix needs a descriptor and a catalogue since M2, because the drill-down's trigger
    // lives inside the popup a matrix cell opens — an empty catalogue withdraws the matrix and with
    // it every route this file tests. One card and one spot: enough to open, nothing that paints
    // (jsdom has no canvas), so the file stays about the shell's wiring.
    heatStripCards: cards.map((c) => ({
      key: c.key,
      date: c.date,
      targetType: c.targetType,
      dow: 'Sat',
      sunrise: false,
      label: 'Tonight Sunset',
      time: c.time,
      verdict: c.verdict,
      verdictLabel: c.verdictLabel,
      pickKind: c.pick?.kind ?? null,
      away: false,
      confidence: c.confidence,
      pool: c.spots,
      badges: [],
    })),
    heatSpots: [{
      id: 1,
      name: 'Bamburgh Beach',
      lat: 55.61,
      lng: -1.71,
      regionName: 'Northumberland & Tyneside',
      rid: 'Northumberland & Tyneside',
      skySubject: true,
      bortleClass: 3,
      scores: [4],
    }],
    heatPointSets: new Map(),
    windowCards: cards,
    paneItems: cards.map((c) => ({ kind: 'card', key: c.key, card: c })),
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
    // The second axis, as the provider hands it over. Frozen rather than the live hook, for the
    // same reason the reach lens is: these files are about the shell's wiring.
    ratingLens: {
      floor: { id: 'any', min: null, label: 'Any rating' },
      floorId: 'any',
      minRating: null,
      selectFloor: vi.fn(),
    },
    ...overrides,
  };
};

const renderShell = (overrides = {}, extraProps = {}) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  const props = {
    onOpenSettings: vi.fn(),
    onSignOut: vi.fn(),
    onOpenMapTab: vi.fn(),
    locations: LOCATIONS,
    ...extraProps,
  };
  const view = render(<WindowFirstShell {...props} />);
  return { ...props, ...view, props };
};

/** Opens a place's four-day sheet by the Map callout's handoff, which is what puts a layer up on Plan. */
const openSheet = async (view) => {
  view.rerender(<WindowFirstShell {...view.props} locationSheetHandoff={{
    id: 1, name: 'Bamburgh Beach', regionName: 'Northumberland & Tyneside', nonce: 1,
  }} />);
  return screen.findByTestId('location-sheet');
};

beforeEach(() => {
  seen.strip = null;
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

describe('WindowFirstShell — the dialogs around the four-day sheet', () => {
  describe('the reach field wiring', () => {
    const HOME_MAP = new Map([[1, { driveMinutes: 20 }]]);
    const EFFECTIVE_MAP = new Map([[1, { driveMinutes: 200 }]]);

    it('gives the strip the effective map as fieldReachById and keeps reachById home', async () => {
      renderShell({
        reachById: HOME_MAP,
        effectiveReachById: EFFECTIVE_MAP,
        windowCards: [card({ reachLimitMinutes: 45, reachMeasured: true })],
      });
      await screen.findByTestId('wf-heat-strip');
      expect(seen.strip.fieldReachById).toBe(EFFECTIVE_MAP);
      expect(seen.strip.reachById).toBe(HOME_MAP);
    });
  });

  it('⚠️ takes every plan dialog down before opening SETTINGS', async () => {
    // `UserSettingsModal` is a sibling of this shell in `App`: not a `Modal` this shell renders, invisible to
    // `stackedOverLayer`, and taking no `stacked` opt-in. So it cannot be ordered: it can only be arrived at with
    // nothing else open.
    const view = renderShell();
    await openSheet(view);

    pressMenuRow('window-first-settings');
    await act(async () => {});
    expect(view.props.onOpenSettings).toHaveBeenCalled();
    expect(screen.queryAllByRole('dialog')).toHaveLength(0);
  });

  /**
   * ⚠️ Every route into `App`'s settings dialog takes the shell's dialogs down first: a sheet and search.
   *
   * <p>`UserSettingsModal` is a sibling of this shell in `App`, so it cannot be ordered against a shell dialog. It can
   * only open with none of them up; otherwise two `aria-modal="true"` elements stand, and the lower one's Escape
   * listener, still armed, closes the dialog the reader cannot see. `AppSettingsRoutes.test.jsx` counts the modals
   * against the real settings dialog. These are the shell's half, rendered WITHOUT `App`.
   */
  describe('the routes into settings', () => {
    const NUDGE = 'Set a postcode for light and drive times';
    const noHome = { homePlace: null };

    describe('the postcode nudge', () => {
      it('closes a sheet standing over the page first, though it cannot be tabbed to there', async () => {
        // A belt, stated for the reason `onGoHome` states its own: the invariant is written once per route, not once
        // per reachable route.
        const onSetPostcode = vi.fn();
        const view = renderShell(noHome, { onSetPostcode });
        await openSheet(view);

        const nudge = screen.getByRole('button', { name: NUDGE });
        expect(nudge).toHaveAttribute('tabindex', '-1');
        await act(async () => { fireEvent.click(nudge); });
        expect(onSetPostcode).toHaveBeenCalledTimes(1);
        expect(screen.queryAllByRole('dialog')).toHaveLength(0);
      });

      it('closes search, which is a shell dialog like the rest', async () => {
        const onSetPostcode = vi.fn();
        renderShell(noHome, { onSetPostcode });
        await act(async () => { fireEvent.click(screen.getByTestId('window-first-search')); });
        await screen.findByTestId('plan-search');

        await act(async () => { fireEvent.click(screen.getByRole('button', { name: NUDGE })); });
        expect(onSetPostcode).toHaveBeenCalledTimes(1);
        expect(screen.queryAllByRole('dialog')).toHaveLength(0);
      });

      it('closes first on the fallback route too, when no postcode handler was handed over', async () => {
        // `onSetPostcode ?? onOpenSettings` — the nudge can never be a dead end, and it must not be the one route out
        // of the plan that skips the close either.
        const view = renderShell(noHome);
        await openSheet(view);

        await act(async () => { fireEvent.click(screen.getByRole('button', { name: NUDGE })); });
        expect(view.props.onOpenSettings).toHaveBeenCalledTimes(1);
        expect(screen.queryAllByRole('dialog')).toHaveLength(0);
      });

      it('hands the handler the argument the tick line gave it, untouched', () => {
        // What the tick line hands its handler is the tick line's business, so the shell's wrapper must pass it
        // through as it came. Today it is the origin slot's resolver — a function returning the slot's current
        // element, for the settings dialog's close — which makes IDENTITY assertable rather than only the count.
        const onSetPostcode = vi.fn();
        renderShell(noHome, { onSetPostcode });
        const nudge = screen.getByRole('button', { name: NUDGE });

        fireEvent.click(nudge);
        expect(onSetPostcode.mock.calls[0]).toHaveLength(1);
        const [resolver] = onSetPostcode.mock.calls[0];
        expect(typeof resolver).toBe('function');
        expect(resolver(), 'the tick line\'s own resolver: it names the slot the nudge fills').toBe(nudge);
      });
    });

    it('⚠️ the menu\'s Settings row closes SEARCH too — the one dialog its M5 close missed', async () => {
      // The ☰ menu is reachable from an open search box, since the tick line leaves the tab order under it and the menu
      // does not. So Shift+Tab out of the box onto it put search and settings up together.
      const { props } = renderShell();
      await act(async () => { fireEvent.click(screen.getByTestId('window-first-search')); });
      await screen.findByTestId('plan-search');
      expect(screen.getByTestId('masthead-menu-button')).not.toHaveAttribute('tabindex', '-1');

      pressMenuRow('window-first-settings');
    await act(async () => {});
      expect(props.onOpenSettings).toHaveBeenCalledTimes(1);
      expect(screen.queryAllByRole('dialog')).toHaveLength(0);
    });

    /**
     * The route the shell never sees. The Map tab's ⌂ opens settings through the map pane, so `App` reports the dialog
     * as `settingsOpen` and the shell answers the rising edge during render. Driven here by the prop alone — no cog,
     * no nudge — so nothing else can be what closed the dialogs.
     */
    describe('when `App` reports settings open, by a route the shell never saw', () => {
      it('⚠️ takes search down in the render settings opens in', async () => {
        const { props, rerender } = renderShell();
        await act(async () => { fireEvent.click(screen.getByTestId('window-first-search')); });
        await screen.findByTestId('plan-search');
        expect(screen.getAllByRole('dialog')).toHaveLength(1);

        rerender(<WindowFirstShell {...props} settingsOpen />);
        expect(screen.queryAllByRole('dialog')).toHaveLength(0);
      });

      it('takes a four-day sheet down too', async () => {
        const view = renderShell();
        await openSheet(view);
        expect(screen.getAllByRole('dialog')).toHaveLength(1);

        view.rerender(<WindowFirstShell {...view.props} settingsOpen />);
        expect(screen.queryAllByRole('dialog')).toHaveLength(0);
      });

      it('closes again on the next opening, because the edge re-arms when settings closes', async () => {
        // The edge's second job. An edge that latched on the first opening and never reset passed every other test
        // here: each of them opens settings once.
        const view = renderShell();
        view.rerender(<WindowFirstShell {...view.props} settingsOpen />);
        view.rerender(<WindowFirstShell {...view.props} settingsOpen={false} />);
        await openSheet(view);
        expect(screen.getAllByRole('dialog')).toHaveLength(1);

        view.rerender(<WindowFirstShell {...view.props} settingsOpen />);
        expect(screen.queryAllByRole('dialog')).toHaveLength(0);
      });
    });
  });

  it('registers a closer with App that takes every dialog the shell owns down, and unregisters on unmount', async () => {
    const registerCloseDialogs = vi.fn();
    const view = renderShell({}, { registerCloseDialogs });
    await openSheet(view);
    const closer = registerCloseDialogs.mock.calls.at(-1)[0];
    expect(typeof closer).toBe('function');

    act(() => { closer(); });

    expect(screen.queryByTestId('location-sheet')).toBeNull();

    view.unmount();
    expect(registerCloseDialogs).toHaveBeenLastCalledWith(null);
  });
});

describe('WindowFirstShell — the stood set reaches the strip (big-seas F4)', () => {
  const stoodPoint = (key) => ({
    lat: 55, lng: -1, reason: 'HIGH_CLOUD', rid: 'Northumberland & Tyneside', key,
  });

  it('hands the strip heatStoodSets: a thumbnail\'s hidden sentence says places stood down', async () => {
    renderShell({
      heatStoodSets: new Map([[card().key, [stoodPoint('#1'), stoodPoint('#2')]]]),
    });
    const cardEl = await screen.findByTestId('wf-heat-card');
    expect(cardEl.textContent).toContain('2 locations stood down by the weather');
  });
});
