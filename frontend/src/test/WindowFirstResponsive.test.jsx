import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import WindowFirstShell from '../components/WindowFirstShell.jsx';

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
 * file pins that hook for the components it renders — the shell, both panes —
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

function renderShell(props = {}) {
  const handlers = { onOpenSettings: vi.fn(), onSignOut: vi.fn(), ...props };
  render(<WindowFirstShell {...handlers} />);
  return handlers;
}

describe('P14 responsive hooks — shell chrome', () => {
  // Each of these carries a `@media (max-width: 639px)` rule in index.css. The class IS the hook;
  // renaming one in the JSX without the stylesheet silently drops that element's phone layout, and
  // nothing else in the suite would notice.
  it.each([
    ['window-first-shell', 'wf-shell'],
    ['window-first-masthead', 'wf-mast'],
    ['window-first-tickline', 'wf-tick'],
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
    ['window-first-masthead', 'window-first-tickline', 'window-first-pane']
      .forEach((testId) => expect(shell).toContainElement(screen.getByTestId(testId)));
  });

  // The tabs and their classes are gone (one-screen-plan.md O3); what the phone rules still hang off is the shell and the pane.
  it('draws no tab, so no `.wf-tab` rule has an element to select', () => {
    renderShell();
    expect(screen.queryAllByRole('tab')).toHaveLength(0);
    expect(document.querySelector('.wf-tab, .wf-tabs, .wf-tabrow')).toBeNull();
  });

  // §5h handed P14 a literal `14px 18px 20px` duplicated across two files with a comment asking the
  // next reader to keep them in step. One class replaces the comment; this is the test that makes
  // the coupling real, and it fails if either pane drops it.
  it('draws Further ahead inside the Plan pane, so one inset frames both sections and the frame cannot move', () => {
    renderShell();
    const pane = screen.getByTestId('window-first-pane');
    expect(pane).toHaveClass('wf-body');
    expect(pane).toContainElement(screen.getByTestId('further-ahead'));
    expect(pane).toContainElement(screen.getByTestId('this-week'));
  });

  // The mock hides ⚙ and Sign out on phone (`.wrap.mob .ghost{display:none}`). This arm declines
  // that, because they are its only route to settings and its only route out — see §5i (they are the ☰ menu's rows now, and
  // the one menu button is drawn at every width). The rule is unconditional on purpose: a CSS-only hide would leave this green while pinning nothing, so if
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
      .toEqual(['masthead-menu-button', 'window-first-origin-chip', 'window-first-search']);
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
