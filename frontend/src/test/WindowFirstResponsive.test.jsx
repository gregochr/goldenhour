import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import WindowFirstComingUp from '../components/WindowFirstComingUp.jsx';
import WindowSheetDialog from '../components/WindowSheetDialog.jsx';

/**
 * P14 — the responsive pass.
 *
 * WHAT THIS FILE CAN AND CANNOT DO, because getting it wrong is how this suite grows a test that
 * cannot fail (P11 found three, P12 one, P13 two of its own).
 *
 * `vite.config.js` sets `css: false`; `setup.js` imports no stylesheet and nothing here injects
 * one. jsdom therefore parses no CSS and evaluates no `@media` rule AT ALL. Its `matchMedia` stub
 * returns a fixed `{ matches: false }` that ignores both the query and `window.innerWidth`. So:
 *
 *   - Asserting a phone PIXEL value here is impossible. `toHaveStyle({ padding })` reads the empty
 *     string for anything that now lives in `index.css` and passes against every value including
 *     none — the exact shape of a test that cannot fail.
 *   - Resizing and expecting a re-render is impossible for the same reason.
 *
 * What IS honest, and what this file does, is `frontend-test-standards.md:189-191` verbatim:
 * "assert the class or attribute the component sets, and leave the pixel behaviour to the browser
 * check." Every phone rule P14 added hooks onto a class or a `data-`/`aria-` attribute, and this
 * file pins that hook for the components it renders — the shell, both panes and the window popup —
 * so a rename cannot silently orphan a rule.
 *
 * It does NOT cover the whole arm, and the gap is worth naming rather than implying: the heat
 * strip's own `.wf-hstrip` grid is pinned in `WindowFirstHeatStrip.test.jsx`, beside that
 * component's other tests, and the lens bar's `.wf-lens*` classes predate this phase and are
 * covered by `WindowFirstLensBar.test.jsx`. (The day rail's `.rail-scroller` used to be named here;
 * the rail was retired at P2 of the heat-field plan, D1.)
 *
 * The pixel side — gutters, the four-row phone header, the bar that must not wrap — is measured in
 * a browser and recorded in §5i of the redesign plan. Neither half covers the other, and neither
 * should be read as if it did.
 */

const TODAY = '2026-08-09';

function renderShell(props = {}) {
  const handlers = { onOpenSettings: vi.fn(), onSignOut: vi.fn(), ...props };
  render(<WindowFirstShell {...handlers} />);
  return handlers;
}

function card(overrides = {}) {
  return {
    key: `${TODAY}:SUNSET`,
    date: TODAY,
    targetType: 'SUNSET',
    lead: false,
    kicker: null,
    when: 'Tonight',
    time: '20:41',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    bestRating: 4,
    withinReachCount: 3,
    confidence: 'high',
    allBadges: [],
    rows: [],
    pick: null,
    spots: [],
    allSpots: [],
    pool: [],
    ...overrides,
  };
}

/** The popup's field inputs, in the shape the shell builds — nothing here paints. */
const FIELD = {
  eventSummary: null,
  // One catalogue spot, because the popup's grid takes its second column only when there is a field
  // to put in it — an empty catalogue is a real state and it is the OTHER arm of the same test.
  spots: [{
    id: 1, name: 'Bamburgh Beach', lat: 6, lng: 4, regionName: 'Coast', rid: 'Coast', skySubject: true, scores: [4],
  }],
  points: [],
  windows: [],
  series: new Map(),
  reachById: new Map(),
  lens: {},
  onSelectRegion: () => {},
  selectedRegion: null,
  singleRegionScope: false,
  origin: null,
};

const renderPopup = (overrides = {}) => render(
  <WindowSheetDialog
    card={card(overrides)}
    index={0}
    total={6}
    field={FIELD}
    topicIndex={new Map()}
    scopeNames={[]}
    todayStr={TODAY}
    onClose={vi.fn()}
  />,
);

describe('P14 responsive hooks — shell chrome', () => {
  // Each of these carries a `@media (max-width: 639px)` rule in index.css. The class IS the hook;
  // renaming one in the JSX without the stylesheet silently drops that element's phone layout, and
  // nothing else in the suite would notice.
  it.each([
    ['window-first-shell', 'wf-shell'],
    ['window-first-masthead', 'wf-mast'],
    ['window-first-tickline', 'wf-tick'],
    ['window-first-tabs', 'wf-tabs'],
    ['window-first-pane', 'wf-body'],
  ])('%s carries %s, the class its phone rule selects', (testId, className) => {
    renderShell();
    expect(screen.getByTestId(testId)).toHaveClass(className);
  });

  // `--wf-gutter` is declared on `.wf-shell` and consumed by six descendants. If the shell loses the
  // class the variable is undefined everywhere and every padding using it collapses to nothing —
  // a total layout failure with no other symptom in this suite.
  it('declares the gutter on an ancestor of everything that consumes it', () => {
    renderShell();
    const shell = screen.getByTestId('window-first-shell');
    ['window-first-masthead', 'window-first-tickline', 'window-first-tabs', 'window-first-pane']
      .forEach((testId) => expect(shell).toContainElement(screen.getByTestId(testId)));
  });

  it('gives every tab the class its phone type scale selects', () => {
    renderShell();
    const tabs = within(screen.getByTestId('window-first-tabs')).getAllByRole('tab');
    expect(tabs).toHaveLength(2);
    tabs.forEach((tab) => expect(tab).toHaveClass('wf-tab'));
  });

  // Both halves, because `.wf-tab[aria-selected='true']` is now the ONLY carrier of the selected
  // weight and the gold top rule — a state that used to be an inline ternary. Asserting only the
  // true half would pass if the attribute were hard-coded.
  it('marks exactly one tab selected, which is what the selected paint hangs off', () => {
    renderShell();
    const tabs = within(screen.getByTestId('window-first-tabs')).getAllByRole('tab');
    const selected = tabs.filter((t) => t.getAttribute('aria-selected') === 'true');
    const unselected = tabs.filter((t) => t.getAttribute('aria-selected') === 'false');
    expect(selected).toHaveLength(1);
    expect(unselected).toHaveLength(1);
    expect(selected[0]).toHaveAttribute('data-testid', 'window-first-tab-plan');
  });

  // §5h handed P14 a literal `14px 18px 20px` duplicated across two files with a comment asking the
  // next reader to keep them in step. One class replaces the comment; this is the test that makes
  // the coupling real, and it fails if either pane drops it.
  it('gives both panes the same inset class, so the frame cannot move on a tab change', () => {
    renderShell();
    expect(screen.getByTestId('window-first-pane')).toHaveClass('wf-body');
    expect(screen.getByTestId('window-first-coming-up')).toHaveClass('wf-body');
  });

  it('keeps the inset class on the Coming up pane when it is the hidden one', () => {
    render(
      <WindowFirstComingUp
        id="p"
        labelledBy="t"
        hidden
        status="ready"
        events={[]}
        todayStr={TODAY}
        onRetry={vi.fn()}
        onGoToPlan={vi.fn()}
        onShowOnMap={vi.fn()}
      />,
    );
    const pane = screen.getByTestId('window-first-coming-up', { hidden: true });
    expect(pane).toHaveClass('wf-body');
    expect(pane).toHaveClass('hidden');
  });

  // The mock hides ⚙ and Sign out on phone (`.wrap.mob .ghost{display:none}`). This arm declines
  // that, because they are its only route to settings and its only route out — see §5i. The rule is
  // unconditional on purpose: a CSS-only hide would leave this green while pinning nothing, so if
  // this ever needs to become "two at desktop" it must be a JS branch, and this test must fail.
  //
  // ⚠️ M3 added the tick line's two controls to the same band, so the row is asserted as an EXACT
  // list of testids rather than as a count or as `toContain`. A count passes when one control is
  // swapped for another; a `toContain` passes when a phone-only extra is added — which is exactly
  // the axis this file exists to guard.
  it('keeps the masthead\'s whole control row at every width', () => {
    renderShell();
    const masthead = screen.getByTestId('window-first-masthead');
    expect(within(masthead).getAllByRole('button').map((b) => b.getAttribute('data-testid')))
      .toEqual(['window-first-settings', 'window-first-signout',
        'window-first-origin-chip', 'window-first-search']);
  });

  // Same argument one row down. "Edit reach" was the rail footer's escape hatch and M3 deleted the
  // row; the route it opened is the ⚙ above, which this file already pins at every width. What is
  // left to guard here is the control that REPLACED it as the phone's most squeezable element: the
  // search affordance is the only way to move the origin with a pointer, and the design hides only
  // its keyboard chip on a phone, never the button.
  it('keeps the search affordance at every width', () => {
    renderShell();
    const tick = screen.getByTestId('window-first-tickline');
    expect(within(tick).getByTestId('window-first-search')).toBeInTheDocument();
  });
});

describe('P14 responsive hooks — the window popup', () => {
  // ⚠️ Every rule below lives in a `@media` block index.css owns, and jsdom evaluates none of them
  // — so the CLASS is the hook and the pixels are a browser claim. What is asserted is the hook
  // itself on a NAMED element, never `container.querySelector` (the standards forbid that form) and
  // never the bare presence of the dialog, which every other file already pins.
  it('carries the card class its full-screen phone rule selects', () => {
    renderPopup();
    expect(screen.getByTestId('window-sheet-card')).toHaveClass('wf-wsh');
  });

  it('gives the nav its own class, so the phone rule can move it to its own row', () => {
    renderPopup();
    expect(screen.getByTestId('window-sheet-nav')).toHaveClass('wf-wsh-nav');
  });

  // ⚠️ The two-column grid is DATA-driven as well as media-driven, and the data half is testable:
  // `.wf-wsh-grid` is one column by default and takes its second only when there is a field to put
  // in it. Both arms, because a hard-coded attribute would satisfy either one alone.
  it('asks for two columns only when there is a field to draw', () => {
    const { unmount } = renderPopup();
    expect(screen.getByTestId('window-sheet-grid')).toHaveAttribute('data-field', 'true');
    unmount();

    render(
      <WindowSheetDialog
        card={card()}
        index={0}
        total={6}
        field={{ ...FIELD, spots: [] }}
        topicIndex={new Map()}
        scopeNames={[]}
        todayStr={TODAY}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByTestId('window-sheet-grid')).not.toHaveAttribute('data-field');
  });

  // The popup opens with no catalogue at all — the state before `/api/locations` resolves. Gating
  // the dialog on it made every matrix cell a control with no visible effect.
  it('opens without a field, rather than not opening', () => {
    render(
      <WindowSheetDialog
        card={card()}
        index={0}
        total={6}
        field={{ ...FIELD, spots: [] }}
        topicIndex={new Map()}
        scopeNames={[]}
        todayStr={TODAY}
        onClose={vi.fn()}
      />,
    );
    expect(screen.getByTestId('window-sheet-title')).toBeInTheDocument();
    expect(screen.queryByTestId('wf-row-map')).toBeNull();
  });
});
