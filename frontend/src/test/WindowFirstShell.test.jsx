import { describe, it, expect, afterEach, vi } from 'vitest';
import { act, render, screen, fireEvent, within } from '@testing-library/react';
import React from 'react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { buildPaneItems } from '../utils/windowFirstAway.js';

describe('WindowFirstShell', () => {
  const renderShell = (props = {}) => {
    const handlers = { onOpenSettings: vi.fn(), onSignOut: vi.fn(), ...props };
    render(<WindowFirstShell {...handlers} />);
    return handlers;
  };

  it('carries the wordmark as the page heading, because it is the app\'s only header', () => {
    // App renders no <header> of its own — this shell is it — so if the masthead did not carry
    // the wordmark the signed-in app would have no h1 at all. src/test/e2e/forecast.spec.js:46
    // finds the app with getByRole('heading', { name: /PhotoCast/ }).
    renderShell();
    expect(screen.getByRole('heading', { level: 1, name: 'PhotoCast' })).toBeInTheDocument();
  });

  it('carries the cog and Sign out — the masthead\'s route to either', () => {
    // Both are lifted handlers, not new state. Losing either would strand a user with no route to
    // settings or to signing out while the Plan is healthy — the crash fallback offers its own
    // Sign-out separately (PlanErrorBoundary).
    const { onOpenSettings, onSignOut } = renderShell();

    // By ROLE AND NAME, not test-id: a test-id keeps passing while the accessible name rots.
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

  it('carries exactly the four masthead controls, so no build/health pill creeps in', () => {
    // The mock shows "● UP v2.17.7" unconditionally; §7 drops it, because build version and
    // service health are not a pilot user's business and HealthIndicator is admin-only. Asserting
    // the ABSENCE of that string would pass whether or not anything was ever built — this names
    // what the masthead actually offers instead, so an added control has to be argued for.
    //
    // ⚠️ Four since M3, not two: the tick line put the origin button and the search affordance in
    // the same band. Named by testid rather than by visible text, because two of the four have
    // labels that vary with state (the origin's place name, the nudge's swap) and a text list would
    // then be a fixture detail rather than an inventory.
    renderShell();
    const masthead = screen.getByTestId('window-first-masthead');
    const ids = within(masthead).getAllByRole('button').map((b) => b.getAttribute('data-testid'));
    expect(ids).toEqual(['window-first-settings', 'window-first-signout',
      'window-first-origin-chip', 'window-first-search']);
  });

  it('renders at the design\'s 1080px frame', () => {
    // One of P4a's two deliverables, and nothing else pinned it.
    renderShell();
    expect(screen.getByTestId('window-first-shell')).toHaveStyle({ maxWidth: '1080px' });
  });

  it('greys the pane when the backend is DOWN, but never the way out', () => {
    // The masthead is inside the shell: gating the whole subtree would strand a user on a dead
    // page with no cog and no Sign out — exactly when they most need one. Only the pane below the
    // masthead takes the treatment.
    renderShell({ contentDisabled: true });

    expect(screen.getByTestId('window-first-pane').className).toContain('pointer-events-none');
    expect(screen.getByTestId('window-first-masthead').className)
      .not.toContain('pointer-events-none');
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }));
  });

  it('never greys the tab bar, which is navigation rather than forecast data', () => {
    // The day rail used to need this treatment applied to it EXPLICITLY, because it sat outside the
    // pane. Its replacement — the heat strip — is inside the pane and inherits it (plan D1, §1.1),
    // so what is left to pin here is the boundary: the treatment stops at the chrome.
    renderShell({ contentDisabled: true });

    expect(screen.getByTestId('window-first-tabs').className).not.toContain('pointer-events-none');
    expect(screen.getByTestId('window-first-tickline').className)
      .not.toContain('pointer-events-none');
  });

  it('shows no forecast age anywhere until there is a forecast to age', () => {
    // The age states a fact about the payload. With no payload the honest thing is silence — an
    // empty "forecast run" line is a claim with nothing behind it. M3 moved the age from the rail
    // footer to the strip (one age per screen, Rule 7).
    //
    // ⚠️ Asserted over the whole rendered TEXT, not by two `queryByTestId` calls. The strip is
    // `React.lazy` behind a `Suspense fallback={null}` and this describe renders synchronously, so
    // a testid query for anything inside it is satisfied by a subtree that never mounted — it
    // cannot fail for the reason its name gives.
    renderShell();
    expect(document.body.textContent).not.toMatch(/forecast run/i);
    expect(document.body.textContent).not.toMatch(/ago/i);
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

describe('WindowFirstShell — the strip it hosts', () => {
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
    // are about the strip and the pane; the two files that are about the doors supply their own.
    upcomingEvents: [],
    travelDayDates: new Set(),
    reachById: new Map(),
    isPro: true,
    isLiteUser: false,
    evaluationScores,
    // The heat strip's thumbnails, as the provider hands them over. They replaced the day rail's
    // tiles at P2 (plan D1) — see `WindowFirstHeatStrip.test.jsx` for the strip's own behaviour;
    // these files are about the shell's wiring.
    heatStripCards: [{
      key: '2026-08-04:SUNSET',
      date: '2026-08-04',
      targetType: 'SUNSET',
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
    // ⚠️ The matrix needs a catalogue to draw and withdraws entirely without one — and since M2 the
    // matrix IS the plan, so a fixture without it renders a pane with no windows in it at all. One
    // spot and one point set: enough for every cell and every popup below, and not enough to make
    // these tests about a canvas (jsdom paints none).
    heatSpots: [
      { id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, regionName: 'Northumberland & Tyneside', rid: 'Northumberland & Tyneside', skySubject: true, bortleClass: 3, scores: [4] },
    ],
    heatPointSets: new Map([
      ['2026-08-04:SUNSET', [{ id: 1, name: 'Bamburgh Beach', lat: 55.61, lng: -1.71, rid: 'Northumberland & Tyneside', r: [4] }]],
    ]),
    todayStr: '2026-08-04',
    tomorrowStr: '2026-08-05',
    // The lens as the provider hands it over. A frozen value rather than the live hook: these
    // tests are about the shell's wiring — where the bar sits, what it is fed and what it is not
    // dimmed by — and the hook's own behaviour has its own file.
    reachLens: LENS,
    ratingLens: RATING_LENS,
    homePlace: undefined,
  });

  /**
   * An alias kept for the tests that are explicitly ABOUT the catalogue.
   *
   * <p>It was a separate fixture while the pane had a card list that rendered without one. Since M2
   * the matrix is the plan, so the catalogue moved into `briefingWith` itself and this is a
   * pass-through — kept named so the tests below still say which ones care.
   */
  const briefingWithSpots = (generatedAt, extra = {}) => ({
    ...briefingWith(generatedAt),
    ...extra,
  });

  /** Opens the first window's popup, awaiting the matrix's own `lazy()` boundary first. */
  const openPopup = async (nth = 0) => {
    await screen.findByTestId('wf-heat-strip');
    await act(async () => { fireEvent.click(screen.getAllByTestId('wf-heat-card')[nth]); });
    return screen.findByTestId('window-sheet');
  };

  const renderWithBriefing = (ctx, props = {}) => {
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx);
    const handlers = {
      onOpenSettings: vi.fn(), onSignOut: vi.fn(), onShowOnMap: vi.fn(), ...props,
    };
    render(<WindowFirstShell {...handlers} />);
    return handlers;
  };

  afterEach(() => vi.restoreAllMocks());

  it('puts the heat strip INSIDE the Plan pane, below the tab bar — the recorded reversal', async () => {
    // Until P2 a day rail sat ABOVE the tab bar and this file pinned it there, on the reasoning
    // that the rail was the whole screen's date context rather than one pane's. Decision D1
    // (owner-confirmed 2026-08-18, plan §1.1) reverses that: the strip is a Plan-pane element,
    // rendered under the lens bar whose label explains it, and the other tabs have since grown
    // their own date context — the Map pane its `DateStrip`, every Coming-up row its dates.
    //
    // ⚠️ If this assertion is ever inverted back, §1.1's rejected alternative 2 is the argument to
    // read first: moving the strip above the tab bar detaches it from the lens and forces
    // Plan-row-opening click semantics onto tabs with no window rows.
    renderWithBriefing(briefingWithSpots('2026-08-04T12:00:00'));

    // Awaited because the strip sits behind a `lazy()` boundary — see `WindowFirstShell`'s note on
    // why (a static import would put `d3-geo` in the entry chunk for every reader).
    const strip = await screen.findByTestId('wf-heat-strip');
    const pane = screen.getByTestId('window-first-pane');
    const tabs = screen.getByTestId('window-first-tabs');
    expect(pane).toContainElement(strip);
    expect(tabs.compareDocumentPosition(strip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('⚠️ draws NOTHING between the matrix and the doors', async () => {
    // The promoted strip stood here until M5 (plan D-1), and the test it replaces was named "draws
    // the strip above the promoted strip, which is a different thing that also stays". Both are now
    // false, so the assertion is about the gap rather than about the thing that filled it: every
    // topic is named on its own card and the doors follow the matrix directly. Written as a DOM
    // adjacency rather than a `queryByTestId(...).toBeNull()`, because a testid check passes just as
    // well against a strip that was renamed as against one that was deleted.
    renderWithBriefing(briefingWithSpots('2026-08-04T12:00:00'));

    const heat = await screen.findByTestId('wf-heat-strip');
    const doors = screen.getByTestId('window-first-doors');
    const pane = screen.getByTestId('window-first-pane');
    const children = [...pane.children];
    // Both must be the pane's OWN children for the adjacency to mean anything — an index of -1
    // would make the assertion vacuously satisfiable from either end.
    expect(children).toContain(heat);
    expect(children).toContain(doors);
    expect(children[children.indexOf(heat) + 1]).toBe(doors);
    // ⚠️ Scoped to a pane WITH CARDS, which is the state the strip occupied. The empty-pane line
    // ("No windows to show.") renders in exactly this slot and is conditional on there being no
    // pane items at all, so it is not something the strip's deletion could have left behind — and
    // this fixture, which has cards, is the one where the gap has to be empty.
    expect(screen.queryByTestId('window-first-pane-empty')).toBeNull();
  });

  it('states the forecast\'s age, and never the model that produced it', () => {
    // §7: the model name is admin-only today and is not a pilot user's business, so the design's
    // "forecast 52m ago by Sonnet" ships as the age alone.
    vi.setSystemTime(new Date('2026-08-04T12:34:00Z'));
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

    // M3: beside the change line rather than in the deleted rail footer. This fixture has no
    // movement basis, so the plain run line is what renders — see the strip's own branch comment
    // for why the two forms are mutually exclusive.
    const line = screen.getByTestId('wf-heat-runage');
    expect(line).toHaveTextContent('Last forecast run 34m ago');
    expect(line.textContent).not.toMatch(/sonnet|haiku|opus/i);
    vi.useRealTimers();
  });

  it('reads the age as UTC, which is how the backend writes it', () => {
    // Parsing the zone-less instant as local time made a 34-minute-old forecast read "1h ago"
    // through a British summer. The shared formatter appends the Z; a local copy did not.
    vi.setSystemTime(new Date('2026-08-04T12:05:00Z'));
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

    expect(screen.getByTestId('wf-heat-runage')).toHaveTextContent('Last forecast run 5m ago');
    vi.useRealTimers();
  });

  it('says nothing about days while the first fetch is still in flight', () => {
    // "No windows to show" during a cold load is a claim about the forecast made before anyone has
    // asked it. Silence until the answer arrives — and the strip is absent too, rather than six
    // empty coastlines under a header claiming to summarise them.
    renderWithBriefing({
      briefing: null,
      loading: true,
      heatStripCards: [],
      heatSpots: [],
      heatPointSets: new Map(),
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
    expect(screen.queryByTestId('wf-heat-strip')).toBeNull();
    expect(screen.queryByTestId('window-first-pane-empty')).toBeNull();
  });

  it('renders one matrix cell per window in the Plan pane', async () => {
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    await screen.findByTestId('wf-heat-strip');
    expect(screen.getAllByTestId('wf-heat-card')).toHaveLength(1);
    expect(screen.getByTestId('wf-heat-time')).toHaveTextContent('21:11');
  });

  describe('the window popup', () => {
    const SECOND = { ...CARD, key: '2026-08-05:SUNRISE', date: '2026-08-05', targetType: 'SUNRISE', lead: false, kicker: null, when: 'Tomorrow sunrise' };
    const STRIP_SECOND = {
      key: '2026-08-05:SUNRISE', date: '2026-08-05', targetType: 'SUNRISE', dow: 'Wed', sunrise: true, label: 'Tomorrow sunrise', time: '05:20', verdict: 'WORTH_IT', verdictLabel: 'Worth it', pickKind: null, away: false, confidence: 'high', pool: [], bestReach: null, badges: [],
    };
    const twoWindows = () => {
      const ctx = briefingWith('2026-08-04T12:00:00', new Map(), [CARD, SECOND]);
      return { ...ctx, heatStripCards: [...ctx.heatStripCards, STRIP_SECOND] };
    };

    it('⚠️ opens nothing until a cell is pressed', async () => {
      // The lead-open default went with the accordion. Six cards were open-one-collapse-five
      // because they were all on the page at once; a dialog is not, and opening one on first paint
      // would put a scrim over the plan a reader has just arrived at.
      renderWithBriefing(twoWindows());
      await screen.findByTestId('wf-heat-strip');
      expect(screen.queryByTestId('window-sheet')).toBeNull();
      expect(screen.getAllByTestId('wf-heat-card').map((c) => c.dataset.open))
        .toEqual([undefined, undefined]);
    });

    it('opens on the window whose cell was pressed, and marks that cell', async () => {
      renderWithBriefing(twoWindows());
      await openPopup(1);
      expect(screen.getByTestId('window-sheet-title')).toHaveTextContent('Tomorrow sunrise');
      expect(screen.getAllByTestId('wf-heat-card').map((c) => c.dataset.open))
        .toEqual([undefined, 'true']);
    });

    it('says which of the six it is, and steps to the next one', async () => {
      renderWithBriefing(twoWindows());
      await openPopup(0);
      expect(screen.getByTestId('window-sheet-of')).toHaveTextContent('1/2');

      await act(async () => { fireEvent.click(screen.getByTestId('window-sheet-next')); });
      expect(screen.getByTestId('window-sheet-of')).toHaveTextContent('2/2');
      expect(screen.getByTestId('window-sheet-title')).toHaveTextContent('Tomorrow sunrise');
    });

    it('⚠️ wraps at both ends, as its own control does', async () => {
      // Six windows on a ring. A disabled arrow at each end would be two controls that do nothing
      // on the two windows a reader is most often in.
      renderWithBriefing(twoWindows());
      await openPopup(0);
      await act(async () => { fireEvent.click(screen.getByTestId('window-sheet-prev')); });
      expect(screen.getByTestId('window-sheet-of')).toHaveTextContent('2/2');
    });

    it('closes on its own esc control, leaving the plan where it was', async () => {
      renderWithBriefing(twoWindows());
      await openPopup(0);
      fireEvent.click(screen.getByTestId('window-sheet-close'));
      expect(screen.queryByTestId('window-sheet')).toBeNull();
      expect(screen.getAllByTestId('wf-heat-card')).toHaveLength(2);
    });

    describe('the arrow keys step it, and only while it is topmost', () => {
      it('steps forward on ArrowRight and back on ArrowLeft', async () => {
        renderWithBriefing(twoWindows());
        await openPopup(0);
        await act(async () => { fireEvent.keyDown(document, { key: 'ArrowRight' }); });
        expect(screen.getByTestId('window-sheet-of')).toHaveTextContent('2/2');
        await act(async () => { fireEvent.keyDown(document, { key: 'ArrowLeft' }); });
        expect(screen.getByTestId('window-sheet-of')).toHaveTextContent('1/2');
      });

      it('wraps, exactly as the visible control does', async () => {
        renderWithBriefing(twoWindows());
        await openPopup(0);
        await act(async () => { fireEvent.keyDown(document, { key: 'ArrowLeft' }); });
        expect(screen.getByTestId('window-sheet-of')).toHaveTextContent('2/2');
      });

      it('⚠️ leaves a modified arrow alone, because Alt+Left is the browser\'s Back', async () => {
        renderWithBriefing(twoWindows());
        await openPopup(0);
        await act(async () => { fireEvent.keyDown(document, { key: 'ArrowRight', altKey: true }); });
        expect(screen.getByTestId('window-sheet-of')).toHaveTextContent('1/2');
      });

      it('⚠️ leaves a text field\'s caret keys alone', async () => {
        renderWithBriefing(twoWindows());
        await openPopup(0);
        const input = document.createElement('input');
        document.body.appendChild(input);
        await act(async () => { fireEvent.keyDown(input, { key: 'ArrowRight' }); });
        expect(screen.getByTestId('window-sheet-of')).toHaveTextContent('1/2');
        input.remove();
      });

      it('⚠️ does not step the window under a dialog stacked over it', async () => {
        // The stacked surface has its own arrow behaviour, and stepping the popup underneath it
        // would move a page the reader cannot see.
        renderWithBriefing(twoWindows());
        await openPopup(0);
        fireEvent.click(screen.getByTestId('window-sheet-pick'));
        await act(async () => { fireEvent.keyDown(document, { key: 'ArrowRight' }); });
        expect(screen.getByTestId('window-sheet-of')).toHaveTextContent('1/2');
      });

      it('does nothing at all while the popup is shut', async () => {
        renderWithBriefing(twoWindows());
        await screen.findByTestId('wf-heat-strip');
        await act(async () => { fireEvent.keyDown(document, { key: 'ArrowRight' }); });
        expect(screen.queryByTestId('window-sheet')).toBeNull();
      });
    });

    describe('Escape closes one layer per press, topmost first', () => {
      it('takes the stacked sheet first and leaves the popup standing', async () => {
        // ⚠️ `Modal` installs a document-level Escape listener PER INSTANCE, so two open dialogs
        // both close on one press unless the lower one declines the key. This is that guard.
        renderWithBriefing(twoWindows());
        await openPopup(0);
        fireEvent.click(screen.getByTestId('window-sheet-pick'));
        expect(screen.getByTestId('window-pick-dialog')).toBeInTheDocument();

        await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }); });
        expect(screen.queryByTestId('window-pick-dialog')).toBeNull();
        expect(screen.getByTestId('window-sheet')).toBeInTheDocument();

        await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }); });
        expect(screen.queryByTestId('window-sheet')).toBeNull();
      });

      /**
       * The whole walk, with SEARCH on top — the rung that was wired at M2 and dormant until M3.
       *
       * <p>M2 built `escapeEnabled` on all three lower layers and recorded that the ordering's
       * first rung could not be reached: `/` was refused while any dialog was open, and
       * `PlanSearch` closes itself on every pick, so search could never be open OVER anything. M3
       * anchors search to the masthead — a surface the popup is drawn over rather than inside — so
       * `/` is now permitted with the popup open, and this is the first test that can walk all
       * three layers down.
       */
      it('⚠️ walks search → sheet → popup, one layer per press', async () => {
        renderWithBriefing(twoWindows());
        await openPopup(0);
        fireEvent.click(screen.getByTestId('window-sheet-pick'));
        await act(async () => { fireEvent.keyDown(document, { key: '/' }); });
        // Search over a sheet over the popup would be three layers; the guard refuses it, so the
        // stack under test is search over the popup. Close the sheet first, then open search.
        expect(screen.queryByTestId('plan-search')).toBeNull();

        await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }); });
        expect(screen.queryByTestId('window-pick-dialog')).toBeNull();

        await act(async () => { fireEvent.keyDown(document, { key: '/' }); });
        expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
        expect(screen.getByTestId('window-sheet')).toBeInTheDocument();

        // One press, one layer: search goes and the popup is still standing behind it.
        await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }); });
        expect(screen.queryByTestId('plan-search')).toBeNull();
        expect(screen.getByTestId('window-sheet')).toBeInTheDocument();

        await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }); });
        expect(screen.queryByTestId('window-sheet')).toBeNull();
      });

      it('⚠️ refuses the arrow keys while search is over the popup', async () => {
        // `← →` step windows, and a reader typing "wed" into the box must not also be walking the
        // plan behind it. The guard existed before search could be open over the popup at all,
        // which means nothing could exercise it until now.
        renderWithBriefing(twoWindows());
        await openPopup(0);
        const before = screen.getByTestId('window-sheet-title').textContent;

        await act(async () => { fireEvent.keyDown(document, { key: '/' }); });
        await screen.findByTestId('plan-search');
        await act(async () => { fireEvent.keyDown(document, { key: 'ArrowRight' }); });

        expect(screen.getByTestId('window-sheet-title').textContent).toBe(before);
      });
    });

    describe('the / shortcut against the popup', () => {
      it('opens search over an open popup — the stack M3 exists to allow', async () => {
        renderWithBriefing(twoWindows());
        await openPopup(0);

        await act(async () => { fireEvent.keyDown(document, { key: '/' }); });

        expect(await screen.findByTestId('plan-search')).toBeInTheDocument();
        // The popup stays MOUNTED underneath, which is what makes the Escape order meaningful —
        // a search that closed the popup on the way in would need no ordering at all.
        expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
      });

      it('⚠️ still refuses it over a layer stacked ON the popup', async () => {
        // Those are already stacked; a third layer has nowhere to go, and the reader's place in
        // the sheet would be lost behind a box they cannot see the list of.
        renderWithBriefing(twoWindows());
        await openPopup(0);
        fireEvent.click(screen.getByTestId('window-sheet-pick'));

        await act(async () => { fireEvent.keyDown(document, { key: '/' }); });

        expect(screen.queryByTestId('plan-search')).toBeNull();
        expect(screen.getByTestId('window-pick-dialog')).toBeInTheDocument();
      });
    });
  });

  describe('away days', () => {
    // Built through the real deriver rather than hand-written, because M5 deleted the away block's
    // rendered payload (`label`, `note`, `windowCount`) along with the promoted strip that read it —
    // and a hand-written fixture carrying those three fields would go on passing while claiming a
    // shape the provider no longer produces.
    const [AWAY_ITEM] = buildPaneItems(
      [{ date: '2026-08-05', targetType: 'SUNRISE' }, { date: '2026-08-05', targetType: 'SUNSET' }],
      [],
      new Set(['2026-08-05']),
    );

    // ⚠️ The away ROW went with the card list at M2 — a travel day is a cell of the matrix now, and
    // `WindowFirstHeatStrip.test.jsx` owns its treatment (a div, never a button). What is still the
    // shell's is the empty-state line's denominator, which is `paneItems` and not the cards: the
    // derivation survives the rendering.
    it('keeps the empty-state line off a pane whose only item is an away day', async () => {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [], [AWAY_ITEM]));
      await screen.findByTestId('wf-heat-strip');
      expect(screen.queryByTestId('window-first-pane-empty')).toBeNull();
    });

    it('draws no away row of its own any more', async () => {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [CARD],
        [{ kind: 'card', key: CARD.key, card: CARD }, AWAY_ITEM]));
      await screen.findByTestId('wf-heat-strip');
      expect(screen.queryByTestId('window-away-row')).toBeNull();
    });
  });

  describe('the two doors', () => {
    it('sits at the foot of the pane, below the matrix', async () => {
      // Where the design puts them, and inside the pane rather than beside it: they open forecast
      // content, so they take the DOWN treatment the pane carries.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
      await screen.findByTestId('wf-heat-strip');

      const pane = screen.getByTestId('window-first-pane');
      const doors = screen.getByTestId('window-first-doors');
      expect(pane).toContainElement(doors);
      expect(screen.getByTestId('wf-heat-strip').compareDocumentPosition(doors)
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

  /**
   * ⚠️ The hazard notice, which had NO test at all until M5's review found the hole.
   *
   * <p>It is a "do not look at the sun without a filter" class of warning, and it is page-level for
   * a documented reason: the card list that used to guarantee a topic's warning was on screen died
   * at M2, and the promoted strip that carried a second copy died at M5 — whose deleted suite
   * included "keeps the warning when no route is offered at all" and "offers no dismiss control".
   * Both M2 and M5 cite this element as what made those deletions safe, and neither left anything
   * pinning it. Salvaged by behaviour here, on the surface that now owns it.
   */
  describe('the hazard notice', () => {
    const HAZARD = 'Never look at the sun without a certified filter.';
    const eclipse = (extra = {}) => ({
      type: 'ECLIPSE', label: 'Partial eclipse', detail: null, facts: [], eventTime: null,
      rarityRank: 1, safetyNote: HAZARD, ...extra,
    });

    it('states a topic’s safety note once, above the matrix, naming its window', async () => {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(),
        [{ ...CARD, allBadges: [eclipse()] }]));
      await screen.findByTestId('wf-heat-strip');

      const note = screen.getByTestId('window-first-safety');
      expect(note).toHaveTextContent(HAZARD);
      expect(note).toHaveTextContent('Tonight Sunset');
      // ONE line, whatever the badge list holds — a warning is about the hazard, not about the chip.
      expect(screen.getAllByTestId('window-first-safety')).toHaveLength(1);
    });

    it('is not suppressible, and sits ABOVE the matrix rather than behind a click', async () => {
      // The whole argument for deleting the two surfaces that used to carry it. A dismiss control,
      // or a position below the six cards, would put a hazard notice somewhere a reader can miss.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(),
        [{ ...CARD, allBadges: [eclipse()] }]));
      const heat = await screen.findByTestId('wf-heat-strip');
      const note = screen.getByTestId('window-first-safety');

      expect(note.compareDocumentPosition(heat) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
      expect(within(note).queryByRole('button')).toBeNull();
    });

    it('says nothing when no topic carries one', async () => {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(),
        [{ ...CARD, allBadges: [eclipse({ safetyNote: null })] }]));
      await screen.findByTestId('wf-heat-strip');
      expect(screen.queryByTestId('window-first-safety')).toBeNull();
    });
  });

  it('carries no placeholder prose above the cards', () => {
    // The pane shipped an explanatory paragraph while it was a stub. That is an annotation card by
    // any reading of §6, and it must not survive the phase that gives the pane real content.
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    expect(screen.getByTestId('window-first-pane').textContent)
      .not.toMatch(/arrive in later phases|Window-first Plan/);
  });

  it('lets a reader close the pick dialog without going anywhere', async () => {
    // Every control left in that dialog navigates off the Plan screen, so a cancel-less dialog
    // forces an unwanted map handoff. `onClose` was wired but nothing pinned it: replacing it with
    // a no-op left the whole suite green.
    const { onShowOnMap } = renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    await openPopup();
    fireEvent.click(screen.getByTestId('window-sheet-pick'));

    fireEvent.click(screen.getByTestId('window-pick-dialog-backdrop'));
    // The pick goes; the popup it was opened from stays — closing one layer must not take the one
    // underneath it, which is the same rule Escape follows.
    expect(screen.queryByTestId('window-pick-dialog')).toBeNull();
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
    expect(onShowOnMap).not.toHaveBeenCalled();
  });

  it('hands the dialog the window it belongs to, in the right order', async () => {
    // The header reads "<when> · <time>". Swapping the two props left the suite green and the
    // header reading "21:11 · Sunset".
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    await openPopup();
    fireEvent.click(screen.getByTestId('window-sheet-pick'));

    expect(screen.getByTestId('window-pick-dialog')).toHaveTextContent('Sunset · 21:11');
  });

  it('opens the pick dialog from the popup and routes both of its destinations', async () => {
    const { onShowOnMap } = renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    await openPopup();

    fireEvent.click(screen.getByTestId('window-sheet-pick'));
    expect(screen.getByTestId('window-pick-dialog')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('window-pick-dialog-region'));
    expect(onShowOnMap).toHaveBeenCalledWith({
      region: 'Northumberland & Tyneside', date: '2026-08-04', eventType: 'SUNSET',
    });
    // Opening a destination closes the dialog — leaving it over the map it just opened would hide
    // the thing the user asked to see.
    expect(screen.queryByTestId('window-pick-dialog')).toBeNull();
    // ⚠️ AND THE POPUP, which this comment used to call "the tab change's job". It is not: this
    // route never goes through `selectTab`, so nothing else clears `openWindowKey`. `MapOverlay` is
    // itself an `aria-modal` dialog with an unconditional document Escape listener and it is NOT a
    // `Modal`, so it takes no `stacked` opt-in — and the instant the pick dialog goes,
    // `stackedOverPopup` goes false and the popup re-arms its own listener and re-takes
    // `aria-modal`. Two modals, the lower one fully tab-reachable under the overlay, one press
    // closing both. M4 fixed this on the two sheet routes and missed the third; an adversarial
    // review of M5 found it.
    expect(screen.queryByTestId('window-sheet')).toBeNull();
  });

  it('routes the pick\'s location as a location, not as a region', async () => {
    // The two calls take different shapes and open different things: a positional
    // (date, eventType, locationName) focuses one spot, an object focuses a region.
    const { onShowOnMap } = renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    await openPopup();

    fireEvent.click(screen.getByTestId('window-sheet-pick'));
    fireEvent.click(screen.getByTestId('window-pick-dialog-location-action'));

    expect(onShowOnMap).toHaveBeenCalledWith('2026-08-04', 'SUNSET', 'Bamburgh Beach');
    // The same close-everything rule as the region route above — see its note for why the popup
    // may not survive a handoff to the map.
    expect(screen.queryByTestId('window-pick-dialog')).toBeNull();
    expect(screen.queryByTestId('window-sheet')).toBeNull();
  });

  it('⚠️ routes a spot card to that place\'s own sheet, OVER the popup — not to the map', async () => {
    // M4 (D-3) retargeted this click. Until then it opened the map and closed the popup, and the
    // rule at the seam was "arriving at a destination ends the browsing". A sheet is not a
    // destination — it is one place's four days, opened from the window still being read — so the
    // popup stays underneath and the map moves one tap further, into the sheet's own footer.
    const { onShowOnMap } = renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    await openPopup();

    fireEvent.click(screen.getByTestId('window-spot'));

    const sheet = await screen.findByTestId('location-sheet');
    expect(within(sheet).getByTestId('location-sheet-title')).toHaveTextContent('Bamburgh Beach');
    // The window popup is STILL THERE. This is the whole point of the retarget and the reason the
    // Escape order has three rungs.
    expect(screen.getByTestId('window-sheet')).toBeInTheDocument();
    expect(onShowOnMap).not.toHaveBeenCalled();
  });

  it('says so plainly when there are no windows', () => {
    renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [], []));
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

  // ── The shell's own wiring into the strip ──
  describe('what the shell hands the matrix', () => {
    it('opens the popup for the window a cell names', async () => {
      // The cell's only job beyond the picture. Deleting `onOpenWindow={openWindow}` leaves six
      // inert buttons, and every other test in this file passes — which is exactly what the retired
      // rail's own "hands a day to the map" test used to catch one level up.
      const second = {
        ...CARD, key: '2026-08-05:SUNRISE', date: '2026-08-05', targetType: 'SUNRISE', lead: false, kicker: null, when: 'Tomorrow sunrise',
      };
      const base = briefingWith('2026-08-04T12:00:00', new Map(), [CARD, second]);
      renderWithBriefing({
        ...base,
        heatStripCards: [...base.heatStripCards, {
          key: '2026-08-05:SUNRISE', date: '2026-08-05', targetType: 'SUNRISE', dow: 'Wed', sunrise: true, label: 'Tomorrow sunrise', time: '05:20', verdict: 'WORTH_IT', verdictLabel: 'Worth it', pickKind: null, away: false, confidence: 'high', pool: [], bestReach: null, badges: [],
        }],
      });
      const strip = await screen.findByTestId('wf-heat-strip');

      await act(async () => {
        fireEvent.click(within(strip).getByRole('button', { name: /Tomorrow sunrise/ }));
      });

      expect(screen.getByTestId('window-sheet-title')).toHaveTextContent('Tomorrow sunrise');
      // And the cell says so in the accessibility tree, not only in the stylesheet.
      expect(within(strip).getAllByTestId('wf-heat-card')[1])
        .toHaveAttribute('aria-expanded', 'true');
    });

    it('⚠️ announces itself as a dialog trigger rather than an in-place disclosure', async () => {
      // M1 gave the cell `aria-expanded` + `aria-controls` pointing at the row it revealed. The row
      // is gone, and an `aria-controls` naming an id no longer in the document is announced as
      // nothing — so both had to change rather than be re-pointed.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
      await screen.findByTestId('wf-heat-strip');
      const cell = screen.getByTestId('wf-heat-card');
      expect(cell).toHaveAttribute('aria-haspopup', 'dialog');
      expect(cell).not.toHaveAttribute('aria-controls');
      expect(cell).toHaveAttribute('aria-expanded', 'false');
    });
  });

  describe('the pick dialog, after the rail chip went', () => {
    it('⚠️ hands the matrix the served hot topics, or the scope filter never runs in the app', () => {
    // The one wiring the A8 filter depends on, and its failure is SILENT: with `hotTopics`
    // undefined the index is empty, every badge joins to nothing, and a badge with no topic is
    // deliberately KEPT — byte-identical output to a healthy payload whose topics are all in scope.
    // So `windowFirstTopics.js`'s whole rule would be inert in production with every unit test
    // green. Asserted through a topic the scope must DROP, because that is the only observable
    // difference between "wired" and "not wired".
    renderWithBriefing({
      ...briefingWithSpots('2026-08-04T12:00:00'),
      origin: { name: 'The Lake District', baseName: 'Keswick' },
      heatSpots: [{
        id: 9,
        name: 'Derwentwater',
        lat: 54.58,
        lng: -3.14,
        regionName: 'The Lake District',
        rid: 'The Lake District',
        skySubject: true,
        bortleClass: 3,
        scores: [4],
      }],
      heatStripCards: [{
        key: '2026-08-04:SUNSET',
        date: '2026-08-04',
        targetType: 'SUNSET',
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
        badges: [{ type: 'KING_TIDE', label: 'King tide', rarityRank: 4 }],
      }],
      briefing: {
        generatedAt: '2026-08-04T12:00:00',
        hotTopics: [{
          type: 'KING_TIDE',
          label: 'King tide',
          date: '2026-08-04',
          eventType: 'SUNSET',
          regions: ['Northumberland & Tyneside'],
          rarityRank: 4,
        }],
      },
    });

    expect(screen.getByTestId('wf-heat-strip')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-heat-topic')).toBeNull();
  });

  it('opens the pick prose from the popup header badge', async () => {
      renderWithBriefing(briefingWithSpots('2026-08-04T12:00:00'));
      await openPopup();
      fireEvent.click(screen.getByTestId('window-sheet-pick'));

      const dialog = screen.getByTestId('window-pick-dialog').textContent;
      expect(dialog).toContain('Breaking clear');
      expect(dialog).toContain('Low cloud clears.');
    });

    it('offers no second trigger on the matrix — the pick legend is passive', async () => {
      // The rule the plan states and the exit criterion the review checks: no nested interactive
      // control inside the card button. `within(button).queryAllByRole('button')` would find the
      // button itself, so the legend is queried directly and its tag asserted.
      //
      // M1 replaced the strip's BEST BET flag with a border legend carrying BOTH pick kinds; the
      // passivity rule is unchanged, and the dialog stays reachable from the window row's own pick
      // badge (asserted directly above).
      renderWithBriefing({
        ...briefingWithSpots('2026-08-04T12:00:00'),
        heatStripCards: [{
          key: '2026-08-04:SUNSET',
          date: '2026-08-04',
          targetType: 'SUNSET',
          dow: 'Tue',
          sunrise: false,
          label: 'Tonight Sunset',
          time: '21:11',
          verdict: 'WORTH_IT',
          verdictLabel: 'Worth it',
          pickKind: 'best',
          away: false,
          confidence: 'high',
          pool: [],
          bestReach: null,
          badges: [],
        }],
      });

      await screen.findByTestId('wf-heat-strip');
      const legend = screen.getByTestId('wf-heat-legend');
      expect(legend.tagName).toBe('SPAN');
      expect(legend).toHaveTextContent('Best bet');
      fireEvent.click(legend);
      expect(screen.queryByTestId('window-pick-dialog')).toBeNull();
    });
  });

  it('lifts the seasonal features too, so the overlay map\'s Bluebell chip reflects the briefing', () => {
    // The sibling lift nine lines up. Pinned here because the prop is optional and
    // optional-called, so deleting it from App would drop the chip with the whole suite still
    // green.
    const onSeasonalFeaturesChange = vi.fn();
    const briefing = briefingWith('2026-08-04T12:00:00');
    renderWithBriefing(
      { ...briefing, briefing: { generatedAt: '2026-08-04T12:00:00', seasonalFeatures: ['BLUEBELL'] } },
      { onSeasonalFeaturesChange },
    );

    expect(onSeasonalFeaturesChange).toHaveBeenCalledWith(['BLUEBELL']);
  });

  it('lifts an empty list when the briefing names no season, rather than nothing at all', () => {
    // The negative half, and it is not cosmetic: never calling would leave a stale value from an
    // earlier briefing standing, which is the exact staleness this lift exists to remove.
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
      // The pane takes the greying because it is forecast data. The lens is a
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

    it('⚠️ drops "within reach" from the page’s ONE count when nothing measured a drive', () => {
      // §4 A7 names this readout as the one count statement on the page, which makes it the most
      // load-bearing place the reach claim could have been wrong. Same two numbers, no claim about
      // which set they count. Found by an adversarial review of M5 after the popup was fixed.
      const first = {
        ...CARD, reachTotal: 12, reachedTotal: 5, reachMeasured: false,
      };
      const second = {
        ...CARD, key: '2026-08-05:SUNRISE', spots: [CARD.spots[0], CARD.spots[0]],
        reachTotal: 18, reachedTotal: 4, reachMeasured: false,
      };
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [first, second]));

      const readout = screen.getByTestId('window-first-lens-readout');
      expect(readout).toHaveTextContent('3 of 9 spots across 2 windows');
      expect(readout).not.toHaveTextContent('within reach');
    });

    it('states what the floor cost, and only once it has cost something', () => {
      // The handoff's rule — "whenever a filter is on, the strip must state what it cost" — with
      // this project's own qualifier from `browseCountLine`: with nothing trimmed, `3 of 3` is a
      // count dressed as a comparison. `reachedTotal` is the denominator, so the two cards below
      // drew 3 of the 9 that reach left.
      // `reachMeasured` is what licenses the words "within reach" on the readout — the reader has a
      // postcode and drive times, so the tier really did gate. Without it the count is the same and
      // the clause is not, which is the §6 clause 7 fix M5 made across six surfaces.
      const first = {
        ...CARD, reachTotal: 12, reachedTotal: 5, reachMeasured: true,
      };
      const second = {
        ...CARD, key: '2026-08-05:SUNRISE', spots: [CARD.spots[0], CARD.spots[0]],
        reachTotal: 18, reachedTotal: 4, reachMeasured: true,
      };
      renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [first, second]));

      expect(screen.getByTestId('window-first-lens-readout'))
        .toHaveTextContent('3 of 9 spots within reach across 2 windows');
    });

    it('renders the plan\'s own conflict message, built where both thresholds are known', async () => {
      // The per-card ladder went with the card list; its plan-wide job is `planConflicts.js` and its
      // per-window job is the popup's quiet sentence. Pinned here because the shell is what wires
      // the derivation to the lens.
      const gated = {
        ...CARD,
        spots: [],
        pool: [],
        allSpots: [{
          key: '9', locationId: 9, locationName: 'Wastwater', regionName: 'Lakes', rating: 4, driveMinutes: 180,
        }],
        reachTotal: 1,
        reachedTotal: 0,
      };
      renderWithBriefing({
        ...briefingWith('2026-08-04T12:00:00', new Map(), [gated]),
        homePlace: 'Durham',
      });
      await screen.findByTestId('wf-heat-strip');

      expect(screen.getByTestId('window-first-conflict-head'))
        .toHaveTextContent('Nothing within 45 min of Durham.');
      expect(screen.getByTestId('window-first-conflict-body'))
        .toHaveTextContent('1 location in your regions, and the closest is Wastwater at 3h.');
    });

    it('moves the page-wide lens when the message offers the way out', async () => {
      const selectTier = vi.fn();
      const gated = {
        ...CARD,
        spots: [],
        pool: [],
        allSpots: [{
          key: '9', locationId: 9, locationName: 'Wastwater', regionName: 'Lakes', rating: 4, driveMinutes: 80,
        }],
        reachTotal: 1,
        reachedTotal: 0,
      };
      renderWithBriefing({
        ...briefingWith('2026-08-04T12:00:00', new Map(), [gated]),
        reachLens: { ...LENS, selectTier },
      });
      await screen.findByTestId('wf-heat-strip');
      fireEvent.click(screen.getByTestId('window-first-conflict-act'));

      // The BAR's control, not a per-window override: a filter that means something different on
      // each of six windows cannot be read off a sticky bar.
      expect(selectTier).toHaveBeenCalledWith('90');
    });

    it('moves the RATING floor when that is the axis the message named', async () => {
      // The other arm of the same handler, and it needs its own test: with the `kind === 'rating'`
      // branch deleted, "Or drop the floor to 3★+" renders, takes focus, and does nothing when
      // pressed — the inert control §6 bans. The reach test above cannot see it.
      const selectFloor = vi.fn();
      const gated = {
        ...CARD,
        spots: [],
        pool: [{
          key: '9', locationId: 9, locationName: 'Wastwater', regionName: 'Lakes', rating: 3, driveMinutes: 20,
        }],
        allSpots: [{
          key: '9', locationId: 9, locationName: 'Wastwater', regionName: 'Lakes', rating: 3, driveMinutes: 20,
        }],
        reachTotal: 1,
        reachedTotal: 1,
      };
      renderWithBriefing({
        ...briefingWith('2026-08-04T12:00:00', new Map(), [gated]),
        ratingLens: { ...RATING_LENS, floorId: '4', minRating: 4, floor: { id: '4', min: 4, label: '4★+' }, selectFloor },
      });
      await screen.findByTestId('wf-heat-strip');
      const actions = screen.getAllByTestId('window-first-conflict-act');
      fireEvent.click(actions.at(-1));

      expect(selectFloor).toHaveBeenCalledWith('3');
    });

    it('does not leave a keyboard reader at the top of the page after loosening', async () => {
      // Every offered action unmounts the message the button sits in, so without somewhere to send
      // focus `document.activeElement` becomes `<body>` — the reader asked to be shown something
      // and was dropped at the document root. Focus goes to the matrix's first card, which is what
      // they have just been shown.
      const gated = {
        ...CARD,
        spots: [],
        pool: [{
          key: '9', locationId: 9, locationName: 'Wastwater', regionName: 'Lakes', rating: 3, driveMinutes: 20,
        }],
        allSpots: [{
          key: '9', locationId: 9, locationName: 'Wastwater', regionName: 'Lakes', rating: 3, driveMinutes: 20,
        }],
        reachTotal: 1,
        reachedTotal: 1,
      };
      renderWithBriefing({
        ...briefingWith('2026-08-04T12:00:00', new Map(), [gated]),
        ratingLens: { ...RATING_LENS, floorId: '4', minRating: 4, floor: { id: '4', min: 4, label: '4★+' }, selectFloor: vi.fn() },
      });
      await screen.findByTestId('wf-heat-strip');

      const raf = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => { cb(); return 0; });
      try {
        fireEvent.click(screen.getAllByTestId('window-first-conflict-act')[0]);
        // The fixture is frozen, so the message does not actually clear — which is exactly what
        // lets the assertion be about WHERE focus was sent rather than about React's re-render.
        expect(document.activeElement).toBe(screen.getAllByTestId('wf-heat-card')[0]);
      } finally {
        raf.mockRestore();
      }
    });
  });

  describe('the tick line\'s home prompt', () => {
    // ⚠️ P7 moved the `Home · <place>` LINE onto the origin chip, which states the same fact and
    // can be acted on (plan §4.8); M3 moved the chip into the masthead's tick line and folded the
    // separate "Home not set" prompt into its empty state. The three-state rule is unchanged
    // through both moves and is what is pinned here: a place is named only when one is known, and
    // the prompt still fires only on an answered-with-no-home response.
    it('names the home the reach figures are measured from', () => {
      renderWithBriefing({ ...briefingWith('2026-08-04T12:00:00'), homePlace: 'Morpeth' });
      expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent('Home · Morpeth');
    });

    it('says so when the settings response came back with no home', () => {
      // The normal first-run state, and the reason the bar itself is never suppressed: the lens
      // stays a visible no-op and the prompt that fixes it lives here.
      renderWithBriefing({ ...briefingWith('2026-08-04T12:00:00'), homePlace: null });
      expect(screen.getByTestId('masthead-set-postcode')).toHaveTextContent('Set a postcode');
    });

    it('names no PLACE while it does not know, and makes no claim about the setting', () => {
      // Telling a user who HAS a home that they have not set one, on the strength of a request
      // that never came back, is a false claim where silence costs nothing. Plan §2.5 forbids a
      // second source of truth for this, which is what makes the third state necessary.
      //
      // The chip still reads "Home", and that is not the claim this test guards: home is the
      // ORIGIN — where the page is planning from — and it is home whether or not a postcode is
      // saved. What must not appear is a place name, or the "not set" prompt.
      renderWithBriefing({ ...briefingWith('2026-08-04T12:00:00'), homePlace: undefined });
      expect(screen.queryByTestId('masthead-set-postcode')).toBeNull();
      // The pin is an SVG rather than a glyph since M3, so the button's text is the label alone.
      expect(screen.getByTestId('window-first-origin-chip').textContent).toBe('Home');
      expect(screen.getByTestId('window-first-tickline').textContent)
        .not.toMatch(/not set|·/i);
    });

    it('offers exactly ONE route to the settings that set it, and the nudge shares it', () => {
      // ⚠️ M3 deleted "Edit reach". It was not a loss — the link opened the same modal the ⚙ two
      // rows up already opens, so the route survives and only the DUPLICATE went. That is the claim
      // worth pinning, and it needs both halves: the label is gone, and the two controls that
      // remain (the cog, and the tick line's empty-state nudge) both reach the same handler.
      const { onOpenSettings } = renderWithBriefing({
        ...briefingWith('2026-08-04T12:00:00'), homePlace: null,
      });
      expect(screen.queryByText('Edit reach')).toBeNull();

      fireEvent.click(screen.getByRole('button', { name: 'Settings' }));
      fireEvent.click(screen.getByTestId('masthead-set-postcode'));
      expect(onOpenSettings).toHaveBeenCalledTimes(2);
    });

    it('keeps that route working when the backend is DOWN', () => {
      // ⚠️ Asserted STRUCTURALLY, not by clicking. The DOWN treatment is `pointer-events: none`
      // on an ancestor, and jsdom applies no CSS — so a `fireEvent.click` fires through it and
      // passes whether the button is greyed or not. The footer lived inside the greyed rail
      // region when this test was written and the click assertion passed anyway; only the
      // containment check failed. The one control that fixes an empty lens must not go inert
      // exactly when a user is most likely to be poking at it. (The rail went at P2; the pane is
      // now the greyed region, and the masthead must still sit outside it.)
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'), { contentDisabled: true });

      expect(screen.getByTestId('window-first-pane').className)
        .toContain('pointer-events-none');
      // Both survivors, so this cannot pass on a masthead the tick line has fallen out of.
      expect(dimmedAncestorOf(screen.getByRole('button', { name: 'Settings' }))).toBeNull();
      expect(dimmedAncestorOf(screen.getByTestId('window-first-tickline'))).toBeNull();
    });

    it('⚠️ M3 TRADE: the forecast age is now inside the DOWN greying, and there is only one of it', () => {
      // The deleted rail footer's own comment argued the opposite — "the forecast's AGE is the one
      // fact that becomes more useful when the backend is down, not less" — and it was right about
      // the fact. What it could not see is that the SAME age was already printed a second time, on
      // the strip's change line, so the page carried two of them and §6's copy rule forbids that.
      //
      // M3 resolves it the way the plan directs (M3.5: "forecast age → beside the change line, one
      // age, Rule 7"), and the cost is that the age now inherits the pane's `opacity-50`. It is
      // still drawn and still readable — the treatment is not removal — and it is pinned here as a
      // deliberate trade rather than left to be rediscovered as a defect. Reversing it means moving
      // the age into the tick line AND stripping `runAge` from the change line, never adding a
      // second copy back.
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'), { contentDisabled: true });

      const age = screen.getByTestId('wf-heat-runage');
      expect(dimmedAncestorOf(age)).not.toBeNull();
      // The half that makes the trade worth it: exactly one age on the page.
      expect(screen.queryAllByText(/forecast run/i)).toHaveLength(1);
    });
  });
});
