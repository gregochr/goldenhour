import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';
import WindowFirstComingUp from '../components/WindowFirstComingUp.jsx';
import WindowFirstWindowCard from '../components/WindowFirstWindowCard.jsx';

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
 * file pins that hook for the components it renders — the shell, both panes and the window card —
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
  const handlers = { onExit: vi.fn(), onOpenSettings: vi.fn(), onSignOut: vi.fn(), ...props };
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
    badges: [],
    rows: [],
    pick: null,
    spots: [],
    ...overrides,
  };
}

const renderCard = (overrides = {}, props = {}) => render(
  <WindowFirstWindowCard card={card(overrides)} todayStr={TODAY} open={false} {...props} />,
);

describe('P14 responsive hooks — shell chrome', () => {
  // Each of these carries a `@media (max-width: 639px)` rule in index.css. The class IS the hook;
  // renaming one in the JSX without the stylesheet silently drops that element's phone layout, and
  // nothing else in the suite would notice.
  it.each([
    ['window-first-shell', 'wf-shell'],
    ['window-first-masthead', 'wf-mast'],
    ['window-first-railfoot', 'wf-railfoot'],
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
    ['window-first-masthead', 'window-first-railfoot', 'window-first-tabs', 'window-first-pane']
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
  it('keeps both masthead controls at every width', () => {
    renderShell();
    const masthead = screen.getByTestId('window-first-masthead');
    const names = within(masthead).getAllByRole('button').map((b) => b.textContent.trim());
    expect(names).toEqual(['⚙', 'Sign out']);
  });

  // Same argument one row down: `.wrap.mob .railfoot .b{display:none}` would take both of these, and
  // the shell's own comment calls "Edit reach" the only route to fixing an empty lens.
  it('keeps the rail footer escape hatch at every width', () => {
    renderShell();
    const foot = screen.getByTestId('window-first-railfoot');
    expect(within(foot).getByTestId('window-first-edit-reach')).toBeInTheDocument();
  });
});

describe('P14 responsive hooks — window card header', () => {
  it('carries the header class its phone rule selects', () => {
    renderCard();
    expect(screen.getByTestId('window-card-head')).toHaveClass('wf-wh');
  });

  // The padding differs between the two states and now lives in `.wf-wh[data-open]`. Both halves,
  // because a hard-coded attribute would satisfy either one alone.
  it('publishes its open state to the stylesheet', () => {
    const { unmount } = renderCard({}, { open: false });
    expect(screen.getByTestId('window-card-head')).toHaveAttribute('data-open', 'false');
    unmount();

    renderCard({}, { open: true });
    expect(screen.getByTestId('window-card-head')).toHaveAttribute('data-open', 'true');
  });

  it('gives the badge group the class its phone row selects', () => {
    renderCard();
    expect(screen.getByTestId('window-card-badges')).toHaveClass('wf-wh-badges');
  });

  // The spacer the phone header drops. It is aria-hidden and has no testid, so the class is the
  // only handle a test has on it — and without the class the phone header grows a stray hairline
  // between two rows that no longer share a line.
  it('carries the spacer rule the phone header hides', () => {
    renderCard();
    const rule = screen.getByTestId('window-card-head').querySelector('.wf-wh-rule');
    expect(rule).not.toBeNull();
    expect(rule).toHaveAttribute('aria-hidden', 'true');
  });

  // The group exists so ONE `flex-basis: 100%` governs both meta clauses and they share one phone
  // row, as the mock's single `.best` span does. If they were siblings again they would take a row
  // each and the header would be four rows deep.
  it('wraps both meta clauses in one group, so they share a row', () => {
    renderCard({ bestRating: 4, withinReachCount: 3 });
    const meta = screen.getByTestId('window-card-meta');
    expect(meta).toHaveClass('wf-wh-meta');
    expect(within(meta).getByTestId('window-card-best')).toHaveTextContent('best spot 4★');
    expect(within(meta).getByTestId('window-card-within-reach')).toHaveTextContent('3 within reach');
  });

  // Count boundary: one clause, either one. The group must still form, or the surviving clause
  // rejoins the title run and stops taking its own row.
  it.each([
    ['only a rating', { bestRating: 4, withinReachCount: null }, 'window-card-best'],
    ['only a reach count', { bestRating: null, withinReachCount: 3 }, 'window-card-within-reach'],
  ])('still forms the group with %s', (_label, overrides, presentTestId) => {
    renderCard(overrides);
    const meta = screen.getByTestId('window-card-meta');
    expect(within(meta).getByTestId(presentTestId)).toBeInTheDocument();
    expect(within(meta).getAllByText(/./)).toHaveLength(1);
  });

  // The negative, and the more valuable half: an empty group would still be a flex item and would
  // still spend one of the header's gaps on nothing.
  it('renders no group at all when neither clause has a value', () => {
    renderCard({ bestRating: null, withinReachCount: null });
    expect(screen.queryByTestId('window-card-meta')).toBeNull();
    expect(screen.queryByTestId('window-card-best')).toBeNull();
    expect(screen.queryByTestId('window-card-within-reach')).toBeNull();
  });
});
