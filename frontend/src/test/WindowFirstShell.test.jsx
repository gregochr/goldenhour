import { describe, it, expect, afterEach, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import React from 'react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import * as briefingContext from '../context/WindowFirstBriefingContext.jsx';
import { buildPaneItems } from '../utils/windowFirstAway.js';
import { pressMenuRow } from './shellMapDoor.js';

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

  it('carries Settings and Sign out in the ☰ menu — the masthead\'s route to either', () => {
    // Both are lifted handlers, not new state. Losing either would strand a user with no route to
    // settings or to signing out while the Plan is healthy — the crash fallback offers its own
    // Sign-out separately (PlanErrorBoundary).
    const { onOpenSettings, onSignOut } = renderShell();

    // By ROLE AND NAME, not test-id: a test-id keeps passing while the accessible name rots.
    pressMenuRow('window-first-settings');
    pressMenuRow('window-first-signout');

    expect(onOpenSettings).toHaveBeenCalledTimes(1);
    expect(onSignOut).toHaveBeenCalledTimes(1);
  });

  it('renders no tab at all: one screen, This week over Further ahead (one-screen-plan.md O3)', () => {
    // The tab bar is gone, and so is the tab-count argument it carried: a view arrives with its pane (the map, Operations),
    // and neither is a tab. This pins that nothing tab-shaped creeps back.
    renderShell();

    expect(screen.queryAllByRole('tab')).toHaveLength(0);
    expect(screen.queryByRole('tablist')).toBeNull();
    expect(screen.getByTestId('this-week-heading')).toBeInTheDocument();
    expect(screen.getByTestId('further-ahead')).toBeInTheDocument();
  });

  it('carries exactly the three masthead controls, so no build/health pill creeps in', () => {
    // The mock shows "● UP v2.17.7" unconditionally; §7 drops it, because build version and
    // service health are not a pilot user's business and HealthIndicator is admin-only. Asserting
    // the ABSENCE of that string would pass whether or not anything was ever built — this names
    // what the masthead actually offers instead, so an added control has to be argued for.
    //
    // ⚠️ Four since M3, not two: the tick line put the origin button and the search affordance in
    // the same band. Named by testid rather than by visible text, because two of the four have
    // labels that vary with state (the origin's place name, the nudge's swap) and a text list would
    // then be a fixture detail rather than an inventory.
    //
    // ⚠️ Three since one-screen-plan.md D14: the cog, the `?` and Sign out folded into the ☰ menu button.
    renderShell();
    const masthead = screen.getByTestId('window-first-masthead');
    const ids = within(masthead).getAllByRole('button').map((b) => b.getAttribute('data-testid'));
    expect(ids).toEqual(['masthead-menu-button', 'window-first-origin-chip', 'window-first-search']);
  });

  it('renders the masthead + tab bar at the design\'s 1080px frame', () => {
    // One of P4a's two deliverables, and nothing else pinned it.
    //
    // ⚠️ Moved off `window-first-shell` itself at map-tab-v2-plan.md §3 P7: the shell root no
    // longer carries a width constraint of its own — only the masthead+tab-bar wrapper does. Since
    // O-17 (bundle rev 2, 2026-09-03) the panel region's own wrapper (further down) applies the
    // SAME constraint on every tab including Map, so within THIS component neither wrapper's own
    // `maxWidth` ever moves on a tab switch. That is not the app's whole width story, though: this
    // file renders the shell in isolation, and `App.jsx`'s `<main>` — outside the shell entirely —
    // still carries a different padding class per tab below `sm` (640px), for P12's full-bleed
    // phone chrome (`App.test.jsx`'s own full-frame describe block covers that half).
    // `window-first-shell`'s own width is asserted separately, below.
    renderShell();
    const masthead = screen.getByTestId('window-first-masthead');
    expect(masthead.parentElement).toHaveStyle({ maxWidth: '1080px' });
  });

  it('the shell root itself carries no width constraint (map-tab-v2-plan.md §3 P7)', () => {
    renderShell();
    expect(screen.getByTestId('window-first-shell')).not.toHaveStyle({ maxWidth: '1080px' });
  });

  it('greys the pane when the backend is DOWN, but never the way out', () => {
    // The masthead is inside the shell: gating the whole subtree would strand a user on a dead
    // page with no cog and no Sign out — exactly when they most need one. Only the pane below the
    // masthead takes the treatment.
    renderShell({ contentDisabled: true });

    expect(screen.getByTestId('window-first-pane').className).toContain('pointer-events-none');
    expect(screen.getByTestId('window-first-masthead').className)
      .not.toContain('pointer-events-none');
    pressMenuRow('window-first-signout');
  });

  it('never greys the masthead, which is navigation rather than forecast data', () => {
    // The day rail used to need this treatment applied to it EXPLICITLY, because it sat outside the
    // pane. Its replacement — the heat strip — is inside the pane and inherits it (plan D1, §1.1),
    // so what is left to pin here is the boundary: the treatment stops at the chrome.
    renderShell({ contentDisabled: true });

    expect(screen.getByTestId('window-first-masthead').className).not.toContain('pointer-events-none');
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
    // The shell reads these (the Ask entry prompt); empty keeps that out of the strip and pane tests.
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

  const renderWithBriefing = (ctx, props = {}) => {
    vi.spyOn(briefingContext, 'useWindowFirstBriefing').mockReturnValue(ctx);
    const handlers = {
      onOpenSettings: vi.fn(), onSignOut: vi.fn(), onOpenMapTab: vi.fn(), ...props,
    };
    render(<WindowFirstShell {...handlers} />);
    return handlers;
  };

  afterEach(() => vi.restoreAllMocks());

  it('puts the heat strip INSIDE the screen\'s pane, below the masthead — the recorded reversal', async () => {
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
    const masthead = screen.getByTestId('window-first-masthead');
    expect(pane).toContainElement(strip);
    expect(masthead.compareDocumentPosition(strip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('wires picksLocked from the context to the strip: LITE gets the Best bets door in the key row, PRO none', async () => {
    renderWithBriefing({ ...briefingWithSpots('2026-08-04T12:00:00'), picksLocked: true });
    const foot = (await screen.findByTestId('wf-heat-foot'));
    expect(within(foot).getByTestId('wf-heat-bestbets-upsell-door')).toBeInTheDocument();
  });

  it('draws no Best bets door when the context does not lock the picks', async () => {
    renderWithBriefing({ ...briefingWithSpots('2026-08-04T12:00:00'), picksLocked: false });
    await screen.findByTestId('wf-heat-foot');
    expect(screen.queryByTestId('wf-heat-bestbets-upsell-door')).toBeNull();
  });

  it('⚠️ draws NOTHING VISIBLE after the matrix', async () => {
    // The promoted strip stood here until M5 (plan D-1), and the test it replaces was named "draws
    // the strip above the promoted strip, which is a different thing that also stays". Both are now
    // false, so the assertion is about the gap rather than about the thing that filled it: every
    // topic is named on its own card and nothing follows the matrix but the status wrapper. Written as a DOM
    // adjacency rather than a `queryByTestId(...).toBeNull()`, because a testid check passes just as
    // well against a strip that was renamed as against one that was deleted.
    //
    // ⚠️ Since 2026-10-01 the gap is no longer LITERALLY empty: `window-first-pane-status` (the
    // pending/empty wrapper, mirroring the Coming up pane's own always-mounted status region) now
    // sits directly after the strip permanently, because a live region inserted only once
    // there is something to announce is not reliably announced at all. So the rule this test
    // protects is now "nothing VISIBLE", not "nothing in the DOM" — the wrapper itself must render
    // empty whenever there are cards to show, and its own `wf-pane-status:empty` CSS rule
    // (index.css, beside `.wf-body`'s `gap: 10px`) cancels the flex gap it would otherwise add on
    // top of `.wf-body`'s own, so no stray 10px gap follows the matrix — see `windowFirstPaneStatusGap.test.js` for the CSS-level pin.
    renderWithBriefing(briefingWithSpots('2026-08-04T12:00:00'));

    const heat = await screen.findByTestId('wf-heat-strip');
    const status = screen.getByTestId('window-first-pane-status');
    // The first section's own children: Further ahead is a second section after it (one-screen-plan.md O2).
    const pane = screen.getByTestId('this-week');
    const children = [...pane.children];
    // Both must be the section's OWN children for the adjacency to mean anything — an index of
    // -1 would make the assertion vacuously satisfiable from either end.
    expect(children).toContain(heat);
    expect(children).toContain(status);
    expect(children[children.indexOf(heat) + 1]).toBe(status);
    expect(children.indexOf(status)).toBe(children.length - 1);
    expect(status).toBeEmptyDOMElement();
    // ⚠️ Scoped to a pane WITH CARDS, which is the state the strip occupied. The empty-pane and
    // pending lines render in exactly this wrapper and are conditional on `loading`/pane-item
    // count, so neither is something the strip's deletion could have left behind — and this
    // fixture, which is loaded with cards, is the one where both have to be absent.
    expect(screen.queryByTestId('window-first-pane-empty')).toBeNull();
    expect(screen.queryByTestId('window-first-pane-pending')).toBeNull();
  });

  it('has no conflict card or slot in the pane any more — the verdict lives on the lens bar', async () => {
    // The card (`window-first-conflict`) and its always-mounted live-region slot went with
    // lens-verdict-line-plan.md. The slot's duty moved to the bar's always-mounted status span, which
    // must exist and be empty whenever there is no verdict; and with the slot gone nothing in the
    // pane is a leading empty flex child, so there is no first-child gap left to cancel.
    renderWithBriefing(briefingWithSpots('2026-08-04T12:00:00'));

    await screen.findByTestId('wf-heat-strip');
    expect(screen.queryByTestId('window-first-conflict-slot')).toBeNull();
    expect(screen.queryByTestId('window-first-conflict')).toBeNull();
    const status = screen.getByTestId('window-first-lens-verdict-status');
    expect(status).toBeEmptyDOMElement();
    expect(screen.getByTestId('window-first-lens')).toContainElement(status);
    expect(screen.getByTestId('window-first-lens-readout')).not.toHaveAttribute('data-variant', 'verdict');
  });

  it('states the forecast\'s age, and never the model that produced it', () => {
    // §7: the model name is admin-only today and is not a pilot user's business, so the design's
    // "forecast 52m ago by Sonnet" ships as the age alone.
    vi.setSystemTime(new Date('2026-08-04T12:34:00Z'));
    try {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

      // M3: beside the change line rather than in the deleted rail footer. This fixture has no
      // movement basis, so the plain run line is what renders — see the strip's own branch comment
      // for why the two forms are mutually exclusive.
      const line = screen.getByTestId('wf-heat-runage');
      expect(line).toHaveTextContent('Last forecast run 34m ago');
      expect(line.textContent).not.toMatch(/sonnet|haiku|opus/i);
    } finally {
      // Restored here, not on a bare last line: an assertion above that throws must still hand
      // the real clock back before the next test. This file's `afterEach(() => vi.restoreAllMocks())`
      // does not reach a clock `setSystemTime` installed — see frontend-test-standards.md,
      // "What NOT to do".
      vi.useRealTimers();
    }
  });

  it('reads the age as UTC, which is how the backend writes it', () => {
    // Parsing the zone-less instant as local time made a 34-minute-old forecast read "1h ago"
    // through a British summer. The shared formatter appends the Z; a local copy did not.
    vi.setSystemTime(new Date('2026-08-04T12:05:00Z'));
    try {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

      expect(screen.getByTestId('wf-heat-runage')).toHaveTextContent('Last forecast run 5m ago');
    } finally {
      vi.useRealTimers();
    }
  });

  it('says nothing about the FORECAST while the first fetch is still in flight', () => {
    // "No forecast to show." during a cold load is a claim about the forecast made before anyone
    // has asked it. Silence about the forecast until the answer arrives — the strip is absent too,
    // rather than six empty coastlines under a header claiming to summarise them — but the pane
    // now says something about the REQUEST (`window-first-pane-pending`, below): that line makes
    // no claim about the sky, only about whether the briefing has arrived yet, which is exactly
    // what was missing on 2026-09-30, when a `GET /api/briefing` request that never completed in
    // production (fixed in #957) left this pane rendering nothing at all.
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
    expect(screen.getByTestId('window-first-pane-pending')).toHaveTextContent('Loading the forecast…');
  });

  it('renders one matrix cell per window in the Plan pane', async () => {
    renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
    await screen.findByTestId('wf-heat-strip');
    expect(screen.getAllByTestId('wf-heat-card')).toHaveLength(1);
    expect(screen.getByTestId('wf-heat-time')).toHaveTextContent('21:11');
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

  describe('the Regional planner door is gone (removed 2026-10-27)', () => {
    it('draws no door, tile or panel at the foot of the pane', async () => {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
      await screen.findByTestId('wf-heat-strip');

      expect(screen.queryByTestId('window-first-doors')).toBeNull();
      expect(screen.queryByTestId('window-first-door-regional')).toBeNull();
      expect(screen.queryByTestId('window-first-panel-regional')).toBeNull();
      expect(within(screen.getByTestId('window-first-pane')).queryByText(/Regional planner/i)).toBeNull();
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

  it('says so plainly when there are no windows', () => {
    renderWithBriefing(briefingWith('2026-08-04T12:00:00', new Map(), [], []));
    expect(screen.getByTestId('window-first-pane-empty')).toBeInTheDocument();
  });

  it('lifts the batch scores to App, which is what the map handoff reads', () => {
    // Nothing in the rail renders these. They feed MapView's
    // visibility filter — and MapView treats a location with no rating as stood down and hides it,
    // so without the lift a tile that says "Worth it" opens the Map tab on an all-but-empty map.
    const scores = new Map([['N&T|2026-08-04|SUNSET|Bamburgh', { rating: 4 }]]);
    const onEvaluationScoresChange = vi.fn();
    renderWithBriefing(briefingWith('2026-08-04T12:00:00', scores), { onEvaluationScoresChange });

    expect(onEvaluationScoresChange).toHaveBeenCalledWith(scores);
  });

  // ── The shell's own wiring into the strip ──
  describe('what the shell hands the matrix', () => {
    it('⚠️ is a plain button since G2a: it opens the Map, so it is neither a dialog trigger nor a disclosure', async () => {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'));
      await screen.findByTestId('wf-heat-strip');
      const cell = screen.getByTestId('wf-heat-card-button');
      expect(cell).not.toHaveAttribute('aria-haspopup');
      expect(cell).not.toHaveAttribute('aria-controls');
      expect(cell).not.toHaveAttribute('aria-expanded');
    });
  });

  describe('the strip’s wiring, after the rail chip went', () => {
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
      expect(screen.queryByRole('dialog')).toBeNull();
    });
  });

  it('lifts the seasonal features too, so the Map tab\'s Bluebell chip reflects the briefing', () => {
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
    it('sits between the masthead and the pane, where the design puts it', () => {
      renderWithBriefing(briefingWith('2026-08-04T12:00:00'));

      const rule = screen.getByTestId('window-first-masthead');
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
      });
      await screen.findByTestId('wf-heat-strip');

      const readout = screen.getByTestId('window-first-lens-readout');
      expect(readout).toHaveAttribute('data-variant', 'verdict');
      expect(readout).toHaveTextContent('0 within 45 min · nearest is Wastwater, 3h');
      expect(screen.getByTestId('window-first-lens-verdict-act')).toHaveTextContent('Show any distance');
      // The card is gone, and the announcement is one plain sentence in the bar's live region.
      expect(screen.queryByTestId('window-first-conflict')).toBeNull();
      expect(screen.getByTestId('window-first-lens-verdict-status'))
        .toHaveTextContent('Nothing within 45 min. Nearest is Wastwater, 3h.');
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
      fireEvent.click(screen.getByTestId('window-first-lens-verdict-act'));

      // The BAR's control, not a per-window override: a filter that means something different on
      // each of six windows cannot be read off a sticky bar.
      expect(selectTier).toHaveBeenCalledWith('90');
    });

    it('moves the RATING floor when that is the axis the message named', async () => {
      // The other arm of the same handler, and it needs its own test: with the `kind === 'rating'`
      // branch deleted, "Drop to 3★+" renders, takes focus, and does nothing when
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
      expect(screen.getByTestId('window-first-lens-verdict-act')).toHaveTextContent('Drop to 3');
      fireEvent.click(screen.getByTestId('window-first-lens-verdict-act'));

      expect(selectFloor).toHaveBeenCalledWith('3');
    });

    it('does not leave a keyboard reader at the top of the page after loosening', async () => {
      // Every offered action unmounts the verdict the button sits in, so without somewhere to send
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
        fireEvent.click(screen.getByTestId('window-first-lens-verdict-act'));
        // The fixture is frozen, so the verdict does not actually clear — which is exactly what
        // lets the assertion be about WHERE focus was sent rather than about React's re-render.
        // The first card's inner BUTTON (the card is a `div`): `applyConflictAction` looks it up by
        // `wf-heat-card-button`, so reverting that selector sends focus nowhere and fails here.
        expect(document.activeElement).toBe(screen.getAllByTestId('wf-heat-card-button')[0]);
      } finally {
        raf.mockRestore();
      }
    });
    it('falls back to the new verdict button, then the readout, when no card exists to focus', async () => {
      // A rating fix that reveals a reach verdict leaves the plan empty, so there is no
      // `wf-heat-card-button`; focus must still not fall to <body>.
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
      const selectFloor = vi.fn();
      renderWithBriefing({
        ...briefingWith('2026-08-04T12:00:00', new Map(), [gated]),
        ratingLens: { ...RATING_LENS, floorId: '4', minRating: 4, floor: { id: '4', min: 4, label: '4★+' }, selectFloor },
      });
      await screen.findByTestId('wf-heat-strip');
      const raf = vi.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => { cb(); return 0; });
      try {
        screen.getAllByTestId('wf-heat-card-button').forEach((node) => node.remove());
        fireEvent.click(screen.getByTestId('window-first-lens-verdict-act'));
        expect(document.activeElement).toBe(screen.getByTestId('window-first-lens-verdict-act'));

        // And with the verdict button gone too, the readout (tabIndex -1 while a verdict shows).
        selectFloor.mockImplementation(() => screen.getByTestId('window-first-lens-verdict-act').remove());
        document.body.focus();
        fireEvent.click(screen.getByTestId('window-first-lens-verdict-act'));
        expect(document.activeElement).toBe(screen.getByTestId('window-first-lens-readout'));
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

      pressMenuRow('window-first-settings');
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
      expect(dimmedAncestorOf(screen.getByTestId('masthead-menu-button'))).toBeNull();
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
