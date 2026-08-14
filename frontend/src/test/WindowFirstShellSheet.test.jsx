import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';

/**
 * The drill-down's wiring, which lives in the shell and nowhere else.
 *
 * <p>Scoped to the sheet rather than being a general shell suite — the shell has never had one, and
 * the four rules below cannot be reached from any component's own test because each of them is a
 * decision about how the shell joins two things: which card the sheet reads, when a trigger is worth
 * offering, what the sheet is told about the reach lock, and what a modal does to the peek.
 *
 * <p>The two doors are stubbed for the reason {@code WindowFirstDoors.test.jsx} gives for stubbing
 * the regional panel: mounting them fires an astro request per visible date, and this file is about
 * neither.
 */
vi.mock('../components/WindowFirstDoors.jsx', () => ({
  default: () => <div data-testid="stub-doors" />,
}));

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
    railTiles: [],
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

const renderShell = (overrides = {}) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  const props = {
    onExit: vi.fn(),
    onOpenSettings: vi.fn(),
    onSignOut: vi.fn(),
    onShowOnMap: vi.fn(),
    locations: LOCATIONS,
  };
  const view = render(<WindowFirstShell {...props} />);
  return { ...props, ...view };
};

const openSheet = () => fireEvent.click(screen.getByTestId('window-spot-all'));

beforeEach(() => {
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

describe('WindowFirstShell — the drill-down', () => {
  it('opens on the window whose trigger was pressed', () => {
    renderShell();
    openSheet();
    expect(screen.getByRole('dialog', { name: 'All spots — Tonight Sunset' })).toBeInTheDocument();
  });

  it('hands the sheet the UNGATED list, so its reach control has something to reveal', () => {
    renderShell();
    openSheet();
    // The strip drew one; the sheet opens on the same tier and draws the same one, and widening
    // finds the second. A sheet handed `card.spots` could never do that.
    fireEvent.click(within(screen.getByTestId('window-spot-sheet-reach')).getByRole('button', { name: 'Any' }));
    expect(screen.getByTestId('window-spot-sheet-count')).toHaveTextContent('2 spots');
  });

  it('joins the roster by name, so the type control has words to offer', () => {
    renderShell();
    openSheet();
    const chips = within(screen.getByTestId('window-spot-sheet-type')).getAllByRole('button');
    expect(chips.map((c) => c.textContent)).toEqual(['Any type', 'Landscape', 'Seascape']);
  });

  it('offers no type control when the roster never arrived', () => {
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
    render(
      <WindowFirstShell
        onExit={vi.fn()}
        onOpenSettings={vi.fn()}
        onSignOut={vi.fn()}
        onShowOnMap={vi.fn()}
      />,
    );
    openSheet();
    expect(screen.queryByTestId('window-spot-sheet-type')).toBeNull();
  });

  it('passes the lens lock down, so a LITE user cannot narrow reach here either', () => {
    renderShell({
      reachLens: { ...ctx().reachLens, locked: true, tierId: 'any', tier: { id: 'any', label: 'Any', limitMinutes: null } },
    });
    openSheet();
    within(screen.getByTestId('window-spot-sheet-reach')).getAllByRole('button')
      .forEach((b) => expect(b).toBeDisabled());
  });

  describe('it holds the window by key, not the card object', () => {
    it('follows the live card when the poll rebuilds it', () => {
      const { rerender } = renderShell();
      openSheet();
      expect(screen.getByTestId('window-spot-sheet-count')).toHaveTextContent('1 of 2');
      // The same window, rebuilt with a third spot — what a poll, a resolved reach fetch or a lens
      // change all produce. A held object would still be describing two.
      const grown = card({ allSpots: [NEAR, FAR, spot({ key: '3', locationName: 'Simonside', rating: 3, driveMinutes: 41 })] });
      vi.spyOn(briefingContext, 'useWindowFirstBriefing')
        .mockReturnValue(ctx({ windowCards: [grown] }));
      rerender(<WindowFirstShell onExit={vi.fn()} onOpenSettings={vi.fn()} onSignOut={vi.fn()} locations={LOCATIONS} />);
      expect(screen.getByTestId('window-spot-sheet-count')).toHaveTextContent('2 of 3');
    });

    it('closes itself when the window it describes has passed', () => {
      const { rerender } = renderShell();
      openSheet();
      expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();
      vi.spyOn(briefingContext, 'useWindowFirstBriefing')
        .mockReturnValue(ctx({ windowCards: [] }));
      rerender(<WindowFirstShell onExit={vi.fn()} onOpenSettings={vi.fn()} onSignOut={vi.fn()} locations={LOCATIONS} />);
      expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
    });
  });

  describe('the trigger appears only where the sheet can differ', () => {
    it('is absent on three unrated spots of one type that the lens never touched', () => {
      const flat = [1, 2, 3].map((n) => spot({
        key: String(n), locationId: n, locationName: 'Wastwater', rating: null, driveMinutes: null,
      }));
      renderShell({ windowCards: [card({ allSpots: flat, spots: flat, reachTotal: 3 })] });
      expect(screen.queryByTestId('window-spot-all')).toBeNull();
    });

    it('is present as soon as one of them is rated', () => {
      const flat = [1, 2, 3].map((n) => spot({
        key: String(n), locationId: n, locationName: 'Wastwater', rating: n === 1 ? 3 : null, driveMinutes: null,
      }));
      renderShell({ windowCards: [card({ allSpots: flat, spots: flat, reachTotal: 3 })] });
      expect(screen.getByTestId('window-spot-all')).toBeInTheDocument();
    });
  });

  it('closes the sheet before the map overlay opens, so two dialogs are never stacked', () => {
    // `MapOverlay` is itself `aria-modal`. Leaving the sheet mounted underneath puts two dialogs on
    // the page, gives Escape two listeners, and holds the reader's place in a list they have just
    // navigated away from.
    renderShell();
    openSheet();
    fireEvent.click(within(screen.getByTestId('window-spot-sheet-list')).getAllByTestId('window-spot')[0]);
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
  });

  describe('a window the lens emptied', () => {
    // `lensEmpty` is what the card now keys its emptied state on — the descriptor
    // `buildWindowCards` derives once, where both thresholds are known. A fixture without it draws
    // no empty state at all, which is exactly the "window the lens never touched" case.
    const gated = () => card({
      allSpots: [NEAR, FAR],
      spots: [],
      reachTotal: 2,
      reachedTotal: 0,
      lensEmpty: {
        headline: 'Nothing within 45 min in this window.',
        body: '2 spots are further out.',
        actions: [],
      },
    });

    it('offers the trigger on its own line, where the number is otherwise unactionable', () => {
      renderShell({ windowCards: [gated()] });
      expect(screen.getByTestId('window-card-lens-all')).toBeInTheDocument();
    });

    it('opens WIDENED, rather than onto the tier that emptied it', () => {
      // Inheriting here would open a dialog whose entire content is "nothing matches" — a door
      // onto a wall. The header says so, and the widening still dies with the sheet.
      renderShell({ windowCards: [gated()] });
      fireEvent.click(screen.getByTestId('window-card-lens-all'));
      expect(within(screen.getByTestId('window-spot-sheet-reach')).getByRole('button', { name: 'Any' }))
        .toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByTestId('window-spot-sheet-widened')).toBeInTheDocument();
      expect(screen.getAllByTestId('window-spot')).toHaveLength(2);
    });

    it('opens a populated window on the bar\'s tier, unchanged', () => {
      renderShell();
      openSheet();
      expect(within(screen.getByTestId('window-spot-sheet-reach')).getByRole('button', { name: '45 min' }))
        .toHaveAttribute('aria-pressed', 'true');
      expect(screen.queryByTestId('window-spot-sheet-widened')).toBeNull();
    });
  });

  it('names the day in the trigger, because a lead card\'s title is the bare event', () => {
    // "See all spots in Sunset" names no day on the one card most likely to be read — the same
    // defect the sheet's own header had.
    renderShell();
    // The strip's scroll arrows take the same label and are not asserted here: they render only
    // while the strip overflows, which jsdom has no layout to produce.
    expect(screen.getByRole('button', { name: 'See all spots in Tonight Sunset' })).toBeInTheDocument();
  });

  describe('a dialog on screen suppresses the spot peek', () => {
    // `Modal` renders inside Tailwind's `z-50` and `.wf-peek` is portalled to the body at 60, while
    // `useDialogFocus` is explicitly not a trap — so from either dialog a keyboard user can Tab back
    // onto a card behind the backdrop and paint a panel over it. Both operands are pinned, because
    // the pick half is a defect that predates this phase and a revert to `sheetCard != null` would
    // silently restore it.
    const SCORES = new Map([[`${TODAY}|SUNSET|Bamburgh Beach`, {
      locationName: 'Bamburgh Beach', fierySkyPotential: 68, goldenHourPotential: 74,
      summary: 'Mid-level cloud should catch the last light.',
    }]]);
    const OPEN_DELAY = 180;
    const withScores = (overrides = {}) => renderShell({ scoreIndex: SCORES, ...overrides });
    /**
     * Lets the dialog's focus move land before the hover.
     *
     * <p>Load-bearing, and found by a mutation sweep: `useDialogFocus` moves focus on a frame, and
     * the peek's own `focusin` listener dismisses a panel whose anchor is not the focused element.
     * Hovering before that settles produced a green test under `modalOpen = false` — it was pinning
     * the focus rule, not the suppression, and could not fail.
     */
    const settle = () => act(() => vi.advanceTimersByTime(OPEN_DELAY * 2));
    const hoverFirstStripCard = () => {
      const strip = screen.getByTestId('window-spot-scroller');
      fireEvent.mouseEnter(within(strip).getAllByTestId('window-spot')[0]);
      act(() => vi.advanceTimersByTime(OPEN_DELAY * 2));
    };

    beforeEach(() => vi.useFakeTimers());
    afterEach(() => vi.useRealTimers());

    it('opens one with no dialog up — the control case the next two rest on', () => {
      withScores();
      hoverFirstStripCard();
      expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
    });

    it('opens none while the drill-down is up', () => {
      withScores();
      openSheet();
      settle();
      hoverFirstStripCard();
      expect(screen.queryByTestId('wf-peek')).toBeNull();
    });

    it('opens none while the PICK dialog is up', () => {
      withScores({
        windowCards: [card({ pick: { kind: 'best', regionName: 'N&T', headline: 'Breaking clear' } })],
      });
      fireEvent.click(screen.getByTestId('window-card-pick'));
      settle();
      hoverFirstStripCard();
      expect(screen.queryByTestId('wf-peek')).toBeNull();
    });
  });

  it('opens the map on the spot clicked inside the sheet, not on its region', () => {
    const { onShowOnMap } = renderShell();
    openSheet();
    fireEvent.click(within(screen.getByTestId('window-spot-sheet-list')).getAllByTestId('window-spot')[0]);
    // The POSITIONAL form, which centres one location — the object form opens a whole region.
    expect(onShowOnMap).toHaveBeenCalledWith(TODAY, 'SUNSET', 'Bamburgh Beach');
  });
});
