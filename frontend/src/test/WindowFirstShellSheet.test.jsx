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

const renderShell = (overrides = {}) => {
  vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx(overrides));
  const props = {
    onOpenSettings: vi.fn(),
    onSignOut: vi.fn(),
    onShowOnMap: vi.fn(),
    locations: LOCATIONS,
  };
  const view = render(<WindowFirstShell {...props} />);
  return { ...props, ...view };
};

/**
 * Opens the first window's popup, where the drill-down's trigger now lives.
 *
 * <p>Awaits the matrix's own `lazy()` boundary before clicking, so the first test in the file
 * behaves like the rest rather than like a race the module cache happens to win.
 */
const openPopup = async () => {
  await screen.findByTestId('wf-heat-strip');
  await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[0]); });
  return screen.findByTestId('window-sheet');
};

/**
 * Opens the drill-down over that popup.
 *
 * <p>⚠️ <b>The bare click is deliberate, and the asymmetry with {@link openPopup} above is the
 * point.</b> The popup awaits because {@code WindowSheetDialog} is {@code lazy()} and on the
 * file's FIRST open genuinely is not there yet (from the second, its payload is fulfilled and
 * that {@code findBy*} resolves synchronously too); this sheet is {@code WindowSpotSheet}, a
 * STATIC import behind a plain
 * {@code useState}, so Testing Library's act-wrapped {@code fireEvent} flushes it in the same
 * commit. Measured rather than assumed, because it reads like an omission and has been refiled as
 * one: over 30 invocations idle and again under a 20-process CPU load, the sheet's testid and a
 * dialog carrying its accessible name were both present SYNCHRONOUSLY on the line after this
 * click — every time, including the first, where the popup's own wait had just cost 858 ms. (That
 * was measured BEFORE the warm-up hook below, so it is a figure no run of this file will produce
 * again; what it shows is that even an 858 ms wait immediately before this click left the sheet
 * no tick behind.) A {@code findBy*} here would wait for nothing and imply a race that does not
 * exist.
 */
const openSheet = async () => {
  await openPopup();
  fireEvent.click(screen.getByTestId('window-spot-all'));
};

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
  it('opens on the window whose trigger was pressed', async () => {
    renderShell();
    await openSheet();
    expect(screen.getByRole('dialog', { name: 'All spots — Tonight Sunset' })).toBeInTheDocument();
  });

  it('hands the sheet the UNGATED list, so its reach control has something to reveal', async () => {
    renderShell();
    await openSheet();
    // The strip drew one; the sheet opens on the same tier and draws the same one, and widening
    // finds the second. A sheet handed `card.spots` could never do that.
    fireEvent.click(within(screen.getByTestId('window-spot-sheet-reach')).getByRole('button', { name: 'Any' }));
    expect(screen.getByTestId('window-spot-sheet-count')).toHaveTextContent('2 spots');
  });

  it('joins the roster by name, so the type control has words to offer', async () => {
    renderShell();
    await openSheet();
    const chips = within(screen.getByTestId('window-spot-sheet-type')).getAllByRole('button');
    expect(chips.map((c) => c.textContent)).toEqual(['Any type', 'Landscape', 'Seascape']);
  });

  it('offers no type control when the roster never arrived', async () => {
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx());
    render(
      <WindowFirstShell
        onOpenSettings={vi.fn()}
        onSignOut={vi.fn()}
        onShowOnMap={vi.fn()}
      />,
    );
    await openSheet();
    expect(screen.queryByTestId('window-spot-sheet-type')).toBeNull();
  });

  it('passes the lens lock down, so a LITE user cannot narrow reach here either', async () => {
    renderShell({
      reachLens: { ...ctx().reachLens, locked: true, tierId: 'any', tier: { id: 'any', label: 'Any', limitMinutes: null } },
    });
    await openSheet();
    within(screen.getByTestId('window-spot-sheet-reach')).getAllByRole('button')
      .forEach((b) => expect(b).toBeDisabled());
  });

  describe('it holds the window by key, not the card object', () => {
    it('follows the live card when the poll rebuilds it', async () => {
      const { rerender } = renderShell();
      await openSheet();
      expect(screen.getByTestId('window-spot-sheet-count')).toHaveTextContent('1 of 2');
      // The same window, rebuilt with a third spot — what a poll, a resolved reach fetch or a lens
      // change all produce. A held object would still be describing two.
      const grown = card({ allSpots: [NEAR, FAR, spot({ key: '3', locationName: 'Simonside', rating: 3, driveMinutes: 41 })] });
      vi.spyOn(briefingContext, 'useWindowFirstBriefing')
        .mockReturnValue(ctx({ windowCards: [grown] }));
      rerender(<WindowFirstShell onOpenSettings={vi.fn()} onSignOut={vi.fn()} locations={LOCATIONS} />);
      expect(screen.getByTestId('window-spot-sheet-count')).toHaveTextContent('2 of 3');
    });

    it('closes itself when the window it describes has passed', async () => {
      const { rerender } = renderShell();
      await openSheet();
      expect(screen.getByTestId('window-spot-sheet')).toBeInTheDocument();
      vi.spyOn(briefingContext, 'useWindowFirstBriefing')
        .mockReturnValue(ctx({ windowCards: [] }));
      rerender(<WindowFirstShell onOpenSettings={vi.fn()} onSignOut={vi.fn()} locations={LOCATIONS} />);
      expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
    });
  });

  describe('the trigger appears only where the sheet can differ', () => {
    it('is absent on three unrated spots of one type that the lens never touched', async () => {
      const flat = [1, 2, 3].map((n) => spot({
        key: String(n), locationId: n, locationName: 'Wastwater', rating: null, driveMinutes: null,
      }));
      renderShell({ windowCards: [card({ allSpots: flat, spots: flat, reachTotal: 3 })] });
      await openPopup();
      expect(screen.queryByTestId('window-spot-all')).toBeNull();
    });

    it('is present as soon as one of them is rated', async () => {
      const flat = [1, 2, 3].map((n) => spot({
        key: String(n), locationId: n, locationName: 'Wastwater', rating: n === 1 ? 3 : null, driveMinutes: null,
      }));
      renderShell({ windowCards: [card({ allSpots: flat, spots: flat, reachTotal: 3 })] });
      await openPopup();
      expect(screen.getByTestId('window-spot-all')).toBeInTheDocument();
    });
  });

  describe('⚠️ Escape closes one layer per press, and the drill-down is the layer this phase adds', () => {
    // `Modal` installs a document-level Escape listener PER INSTANCE, so two open dialogs both close
    // on a single press unless the lower one declines the key. `stackedOverPopup`'s `sheetCard`
    // operand is that guard, and this is the only test that drives it: dropping the operand leaves
    // the whole suite green with one press closing both.
    it('takes the drill-down first and leaves the popup standing', async () => {
      renderShell();
      await openSheet();
      expect(screen.getAllByRole('dialog')).toHaveLength(2);

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
      expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
    });

    /**
     * ⚠️ M5's containment, and it is the SAME predicate as the Escape order above.
     *
     * <p>The layer that answers Escape is the layer that is not {@code inert}. Both consequences are
     * derived from one {@code escapeEnabled} prop inside each dialog for exactly that reason, so a
     * future caller cannot set one and forget the other — and this pair of tests is what would catch
     * them coming apart.
     *
     * <p>What jsdom can see is the ATTRIBUTE ({@code 'inert' in HTMLElement.prototype} is
     * {@code false} here, so the behaviour is a no-op). The behaviour was measured in Chromium: with
     * two layers open, twenty-four Tab presses from the top one never entered the layer beneath, and
     * before the fix the seventeenth press reached the masthead's search button while three
     * {@code aria-modal} dialogs stood open at once.
     */
    it('⚠️ makes the popup inert while the drill-down is over it, and the only modal once it goes', async () => {
      renderShell();
      await openSheet();

      const popup = screen.getByTestId('window-sheet');
      const sheet = screen.getByTestId('window-spot-sheet');
      expect(popup).toHaveAttribute('inert');
      expect(popup).not.toHaveAttribute('aria-modal');
      expect(sheet).not.toHaveAttribute('inert');
      expect(sheet).toHaveAttribute('aria-modal', 'true');

      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.getByTestId('window-sheet')).not.toHaveAttribute('inert');
      expect(screen.getByTestId('window-sheet')).toHaveAttribute('aria-modal', 'true');
    });

    it('leaves exactly ONE element claiming to be the modal, whatever is stacked', async () => {
      // The property, stated as a count rather than per element: three dialogs can be open at once
      // on this surface (popup, sheet, search) and the defect this replaces was that all three said
      // `aria-modal="true"`. A screen reader resolves the stack from that attribute, so two of them
      // is not a smaller version of the same thing — it is an unanswerable question.
      renderShell();
      await openSheet();
      expect(screen.getAllByRole('dialog')).toHaveLength(2);
      // Filtered off the ROLE query rather than `document.querySelectorAll('[aria-modal]')`: the
      // standards' rule, and it also fails usefully — a raw selector says "0 found", this says
      // which dialogs are on screen and which of them claims the attribute.
      expect(screen.getAllByRole('dialog').filter((d) => d.getAttribute('aria-modal') === 'true'))
        .toHaveLength(1);
    });

      /**
     * ⚠️ The two masthead controls that could open a FOURTH modal, both found by an adversarial
     * review of M5's first cut and both measured in a browser before being fixed here.
     *
     * <p>Neither is reachable by pointer while a dialog is up — a backdrop covers them — and both
     * are reachable by Tab, because {@code useDialogFocus} is deliberately not a trap. The search
     * button was reached on the seventeenth press and the settings cog on the forty-second.
     */
    it('⚠️ makes the DRILL-DOWN sheet inert in its turn, when search sits over it', async () => {
      // The same predicate on a different component. `stacked={!escapeEnabled}` is derived inside
      // each of the four dialogs, so each derivation is its own line and each can be broken on its
      // own — an adversarial review found three of the four unpinned. This is `WindowSpotSheet`'s.
      renderShell();
      await openSheet();
      await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }); });
      await openPopup();
      await act(async () => { fireEvent.click(screen.getByTestId('window-first-search')); });
      await screen.findByTestId('plan-search');

      expect(screen.getByTestId('window-sheet')).toHaveAttribute('inert');
      expect(screen.getAllByRole('dialog').filter((d) => d.getAttribute('aria-modal') === 'true'))
        .toHaveLength(1);
    });

    it('⚠️ refuses to open search while a layer is stacked, and leaves the tab order', async () => {
      // The `/` shortcut has refused this since M3, with a comment saying why ("a third layer has
      // nowhere to go"); the BUTTON beside it did not. What that bought, measured: `Modal` gives
      // every dialog `fixed inset-0 z-50`, so with equal z-index paint order is DOM order, and the
      // sheet — which renders after search — painted its scrim and its whole card over the search
      // panel. The reader typed into a box behind a dead, dimmed sheet.
      renderShell();
      await openSheet();

      const search = screen.getByTestId('window-first-search');
      expect(search.tabIndex).toBe(-1);
      await act(async () => { fireEvent.click(search); });

      // ⚠️ Asserted on the SHEET's own state, not on search's absence. `PlanSearch` is `lazy()`, so
      // a `queryByTestId(...).toBeNull()` immediately after the click is satisfied by the chunk not
      // having resolved yet and survives the guard being deleted — measured. `stacked` is a plain
      // prop on a dialog that is already mounted, so it flips in the same commit as `searchSeed`
      // and there is nothing to wait for: if the guard goes, the sheet goes inert right here.
      expect(screen.getByTestId('window-spot-sheet')).not.toHaveAttribute('inert');
      expect(screen.getByTestId('window-spot-sheet')).toHaveAttribute('aria-modal', 'true');
      expect(screen.queryByTestId('plan-search')).toBeNull();
    });

    it('still opens search over the popup ALONE, which is the stack M3 designed for', async () => {
      // The other half, and the reason the guard is `stackedOverPopup` rather than "any dialog":
      // search is anchored to the masthead, which the popup is drawn OVER rather than inside, so
      // this pair is the one stack this arm supports. A guard that broke it would be a regression
      // dressed as a fix.
      renderShell();
      await openPopup();

      const search = screen.getByTestId('window-first-search');
      expect(search.tabIndex).not.toBe(-1);
      await act(async () => { fireEvent.click(search); });
      expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
      expect(screen.getAllByRole('dialog').filter((d) => d.getAttribute('aria-modal') === 'true'))
        .toHaveLength(1);
    });

    it('⚠️ takes every plan dialog down before opening SETTINGS', async () => {
      // `UserSettingsModal` is a sibling of this shell in `App`: not a `Modal` this shell renders,
      // invisible to `stackedOverPopup`, and taking no `stacked` opt-in. So it cannot be ordered —
      // it can only be arrived at with nothing else open. Measured before the fix: two
      // `aria-modal="true"` elements with neither inert, and one Escape press closing the POPUP
      // underneath while the settings dialog stayed up.
      const { onOpenSettings } = renderShell();
      await openSheet();

      await act(async () => { fireEvent.click(screen.getByTestId('window-first-settings')); });
      expect(onOpenSettings).toHaveBeenCalled();
      expect(screen.queryAllByRole('dialog')).toHaveLength(0);
    });

  it('takes the popup on the second press', async () => {
      renderShell();
      await openSheet();
      fireEvent.keyDown(document, { key: 'Escape' });
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryAllByRole('dialog')).toHaveLength(0);
    });
  });

  it('closes the sheet before the map overlay opens, so two dialogs are never stacked', async () => {
    // `MapOverlay` is itself `aria-modal`. Leaving the sheet mounted underneath puts two dialogs on
    // the page, gives Escape two listeners, and holds the reader's place in a list they have just
    // navigated away from.
    renderShell();
    await openSheet();
    fireEvent.click(within(screen.getByTestId('window-spot-sheet-list')).getAllByTestId('window-spot')[0]);
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
  });

  describe('a window the lens emptied', () => {
    // Reach emptied it: both spots were beyond the tier, which `reachedTotal: 0` is the record of.
    // The popup then draws its quiet sentence in place of the ranked strip — and the trigger beside
    // it, for the reason the deleted card's empty state carried one.
    const gated = () => card({
      allSpots: [NEAR, FAR],
      spots: [],
      reachTotal: 2,
      reachedTotal: 0,
    });

    it('offers the trigger beside the quiet sentence, where the number is otherwise unactionable', async () => {
      renderShell({ windowCards: [gated()] });
      await openPopup();
      expect(screen.getByTestId('window-sheet-empty')).toBeInTheDocument();
      expect(screen.getByTestId('window-sheet-see-all')).toBeInTheDocument();
    });

    it('opens WIDENED, rather than onto the tier that emptied it', async () => {
      // Inheriting here would open a dialog whose entire content is "nothing matches" — a door
      // onto a wall. The header says so, and the widening still dies with the sheet.
      renderShell({ windowCards: [gated()] });
      await openPopup();
      fireEvent.click(screen.getByTestId('window-sheet-see-all'));
      expect(within(screen.getByTestId('window-spot-sheet-reach')).getByRole('button', { name: 'Any' }))
        .toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByTestId('window-spot-sheet-widened')).toBeInTheDocument();
      expect(screen.getAllByTestId('window-spot')).toHaveLength(2);
    });

    it('opens a populated window on the bar\'s tier, unchanged', async () => {
      renderShell();
      await openSheet();
      expect(within(screen.getByTestId('window-spot-sheet-reach')).getByRole('button', { name: '45 min' }))
        .toHaveAttribute('aria-pressed', 'true');
      expect(screen.queryByTestId('window-spot-sheet-widened')).toBeNull();
    });

    // The RATING-emptied twin of the case above, and the one the widening must NOT fire for. The
    // fixture differs in a single field — `reachedTotal` is 2 rather than 0 — because that is the
    // whole distinction: both spots cleared the drive limit, and the floor is what removed them.
    //
    // Widening reach here gates nothing. It discards the reader's tier, claims a widening that
    // removed nothing, and still opens onto an empty list, because the sheet inherits the floor
    // that did the emptying. Guarding on `spots.length === 0` — which stopped meaning "reach
    // emptied it" the moment the floor was composed in — did exactly that.
    const ratingGated = () => card({
      allSpots: [NEAR, FAR],
      spots: [],
      reachTotal: 2,
      reachedTotal: 2,
    });

    it('opens a RATING-emptied window on the bar\'s own tier, and claims no widening', async () => {
      renderShell({ windowCards: [ratingGated()] });
      await openPopup();
      fireEvent.click(screen.getByTestId('window-sheet-see-all'));

      expect(within(screen.getByTestId('window-spot-sheet-reach')).getByRole('button', { name: '45 min' }))
        .toHaveAttribute('aria-pressed', 'true');
      expect(screen.queryByTestId('window-spot-sheet-widened')).toBeNull();
    });
  });

  it('names the day in the trigger, because a lead card\'s title is the bare event', async () => {
    // "See all spots in Sunset" names no day on the one card most likely to be read — the same
    // defect the sheet's own header had.
    renderShell();
    await openPopup();
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

    // `shouldAdvanceTime`, because the matrix and the popup are both behind `lazy()` boundaries and
    // RTL's `findBy*` polls on a timer: frozen fake timers never resolve either. The peek's own
    // delay is still driven explicitly by `advanceTimersByTime`. The sibling file records the same
    // pairing for the same reason.
    beforeEach(() => vi.useFakeTimers({ shouldAdvanceTime: true }));
    afterEach(() => vi.useRealTimers());

    it('opens one with the popup topmost — the control case the next two rest on', async () => {
      // ⚠️ The popup itself does NOT suppress the peek: the cards are inside it, so a panel opened
      // from one is about the surface the reader is on. What suppresses it is another dialog OVER
      // the popup, which is what the next two drive.
      withScores();
      await openPopup();
      hoverFirstStripCard();
      expect(screen.getByTestId('wf-peek')).toBeInTheDocument();
    });

    it('opens none while the drill-down is up', async () => {
      withScores();
      await openSheet();
      settle();
      hoverFirstStripCard();
      expect(screen.queryByTestId('wf-peek')).toBeNull();
    });

    it('opens none while the PICK dialog is up', async () => {
      withScores({
        windowCards: [card({ pick: { kind: 'best', regionName: 'N&T', headline: 'Breaking clear' } })],
      });
      await openPopup();
      fireEvent.click(screen.getByTestId('window-sheet-pick'));
      settle();
      hoverFirstStripCard();
      expect(screen.queryByTestId('wf-peek')).toBeNull();
    });
  });

  it('opens the map on the spot clicked inside the sheet, not on its region', async () => {
    const { onShowOnMap } = renderShell();
    await openSheet();
    fireEvent.click(within(screen.getByTestId('window-spot-sheet-list')).getAllByTestId('window-spot')[0]);
    // The POSITIONAL form, which centres one location — the object form opens a whole region.
    expect(onShowOnMap).toHaveBeenCalledWith(TODAY, 'SUNSET', 'Bamburgh Beach');
  });

  it('⚠️ takes the WINDOW POPUP down with it, not just this sheet', async () => {
    // `MapOverlay` is itself an `aria-modal` dialog with its own unconditional document Escape
    // listener, and the popup re-arms its own the moment nothing is stacked on it — so a
    // sheet-only close leaves two dialogs on the page and makes one press take both. The sheet's
    // own comment has always stated that rule; until M4 it closed only the sheet. Its sibling in
    // `locationSheetShell.test.jsx` pins the same line for the four-day sheet's footer.
    renderShell();
    await openSheet();
    fireEvent.click(within(screen.getByTestId('window-spot-sheet-list')).getAllByTestId('window-spot')[0]);
    expect(screen.queryByTestId('window-spot-sheet')).toBeNull();
    expect(screen.queryByTestId('window-sheet')).toBeNull();
  });

  it('keeps naming the MAP on its own cards, which is what the caller opt-in is for', async () => {
    // M4 retargeted the popup's copy of the ranked strip to the location sheet and gave the card's
    // destination wording to the caller (plan §3 rule 10). This sheet did not retarget, so its
    // cards must read exactly what they always did — the default, unchanged.
    renderShell();
    await openSheet();
    expect(within(screen.getByTestId('window-spot-sheet-list')).getAllByTestId('window-spot')[0])
      .toHaveTextContent('Open on map');
  });
});
