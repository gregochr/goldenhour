/**
 * `components/map/MapWindowPanel.jsx` — the drilldown's first level, as rendered
 * (`docs/engineering/map-landing-plan.md` §3 L5, `docs/design/map-landing/README.md` §5).
 *
 * <p>Which regions appear, in what order and what they count is decided by
 * `utils/mapDrilldown.js` and pinned in `mapDrilldown.test.js`. This file asserts what that model
 * turns into on screen — the header's four parts, the em dash a night window's rows carry, the
 * unscored degrade, and the two dismissal routes the component itself owns.
 */
import React from 'react';
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import MapWindowPanel from '../components/map/MapWindowPanel.jsx';

const ROW = {
  id: 'solar:2026-01-15:SUNSET',
  kind: 'solar',
  eventType: 'SUNSET',
  label: 'Tonight sunset',
  dayLabel: 'Tonight',
  time: '20:28',
  confidence: 'medium',
  pickKind: 'best',
};

const REGIONS = [
  {
    name: 'The Lakes', tier: 'WORTH_IT', verdictLabel: 'Worth it', meanRating: 4.2, bestRating: 5,
    driveMinutes: 95, driveLabel: '1h 35min', placeCount: 9, atFourPlus: 4,
  },
  {
    name: 'North East', tier: 'STAND_DOWN', verdictLabel: 'Poor', meanRating: 2.1, bestRating: null,
    driveMinutes: null, driveLabel: null, placeCount: 3, atFourPlus: 0,
  },
];

function renderPanel(props = {}) {
  const onClose = vi.fn();
  const onSelectRegion = vi.fn();
  const result = render(
    <MapWindowPanel
      row={ROW}
      verdict={{ tier: 'WORTH_IT' }}
      note="Verdict is the strongest region’s average."
      rows={REGIONS}
      onClose={onClose}
      onSelectRegion={onSelectRegion}
      {...props}
    />,
  );
  return { ...result, onClose, onSelectRegion };
}

let foreignModal = null;
afterEach(() => {
  // Torn down here, not on the last line of a test: an assertion that threw first would leave an
  // `[aria-modal]` in the body and stand every later Escape case in this file down silently.
  foreignModal?.remove();
  foreignModal = null;
});

const plantForeignModal = () => {
  foreignModal = document.createElement('div');
  foreignModal.setAttribute('role', 'dialog');
  foreignModal.setAttribute('aria-modal', 'true');
  document.body.appendChild(foreignModal);
};

describe('MapWindowPanel — the header', () => {
  it('names the window, its time, its confidence and its pick', () => {
    renderPanel();

    expect(screen.getByTestId('wf-win-panel-window')).toHaveTextContent('Tonight sunset');
    const meta = screen.getByTestId('wf-win-panel-meta');
    expect(meta).toHaveTextContent('20:28');
    expect(within(meta).getByTestId('wf-win-panel-pick')).toHaveAttribute('data-pick', 'best');
    expect(screen.getByTestId('wf-win-panel-verdict')).toHaveTextContent('Worth it');
  });

  it('⚠️ prints the confidence LABEL, never a percentage', () => {
    // The only number this app has for confidence is `confidenceScalar` — a FILL OPACITY the heat
    // kernel hazes with — so the spec's "confidence percentage" would render a probability nothing
    // computes (map-landing-plan.md §4 #23).
    renderPanel();

    expect(screen.getByTestId('wf-win-panel-meta')).toHaveTextContent('Medium confidence');
    expect(screen.getByTestId('wf-win-panel-meta').textContent).not.toMatch(/\d+%/);
  });

  it('withholds the verdict chip on a night window, and the medallion when there is no pick', () => {
    renderPanel({
      row: { ...ROW, kind: 'astro', eventType: 'ASTRO', pickKind: null },
      verdict: null,
    });

    expect(screen.queryByTestId('wf-win-panel-verdict')).toBeNull();
    expect(screen.queryByTestId('wf-win-panel-pick')).toBeNull();
    // ⚠️ …and NO confidence label. A night EV row carries a horizon-only inference (no
    // `ConfidenceDeriver` exists for astro or aurora at all), so printing "Medium confidence"
    // directly above a note saying the event is "scored from darkness, clarity and Kp rather than
    // the solar forecast" would be the same defect §4 #23 refused the spec's percentage for.
    expect(screen.getByTestId('wf-win-panel-meta').textContent).not.toMatch(/confidence/i);
  });
});

describe('MapWindowPanel — the region rows', () => {
  it('draws the name, the drive, the count, the word and the ceiling', () => {
    renderPanel();

    const rows = screen.getAllByTestId('wf-win-panel-row');
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent('The Lakes');
    expect(within(rows[0]).getByTestId('wf-win-panel-stat')).toHaveTextContent('1h 35min · 4 of 9 at 4★+');
    // ⚠️ …and the star has a spoken alternative, because NVDA at its default symbol level does not
    // speak U+2605 — "4 of 9 at 4 plus" strips the unit and leaves a dangling plus.
    expect(within(rows[0]).getByTestId('wf-win-panel-stat').textContent).toContain('stars or better');
    expect(within(rows[0]).getByTestId('wf-win-panel-word')).toHaveTextContent('Worth it');
    expect(within(rows[0]).getByTestId('wf-win-panel-best')).toHaveTextContent('5★ best');
  });

  it('omits the drive entirely where none is measured — never a zero', () => {
    renderPanel();

    const stat = within(screen.getAllByTestId('wf-win-panel-row')[1]).getByTestId('wf-win-panel-stat');
    expect(stat).toHaveTextContent('0 of 3 at 4★+');
    expect(stat.textContent).not.toMatch(/·/);
  });

  it('⚠️ says "in reach" nowhere — the count is scope-scoped, not reach-gated', () => {
    // CLAUDE.md's rule: nothing may claim a reach filter ran unless a drive exists to have gated on.
    renderPanel();

    expect(screen.getByTestId('wf-win-panel').textContent).not.toMatch(/in reach|within reach/);
  });

  it('says "not scored" rather than drawing a swatch for a region with no ceiling', () => {
    renderPanel();

    // ⚠️ Queried by test-id, not `querySelector('.wf-win-panel-swatch')` — the standards forbid
    // coupling a behavioural claim to a class name ("If nothing can be queried, add a test-id").
    const row = screen.getAllByTestId('wf-win-panel-row')[1];
    expect(within(row).getByTestId('wf-win-panel-best')).toHaveTextContent('not scored');
    expect(within(row).queryByTestId('wf-win-panel-swatch')).toBeNull();
    expect(within(screen.getAllByTestId('wf-win-panel-row')[0]).getByTestId('wf-win-panel-swatch'))
      .toBeInTheDocument();
  });

  /**
   * ⚠️ L5 shipped these as DIVS, because the level below was unbuilt and a `disabled` button would
   * have announced the regions as *unavailable* — a claim about the regions that was false. L6 built
   * the level, so `onSelectRegion` is required and they are real buttons; a review lens caught the
   * ternary that chose between them still standing with a dead arm.
   */
  it('rows are BUTTONS, named for their region, and open that region', () => {
    const { onSelectRegion } = renderPanel();

    const row = screen.getByRole('button', { name: /The Lakes.*Worth it/ });
    expect(row.tagName).toBe('BUTTON');
    expect(row).toHaveAttribute('data-region', 'The Lakes');
    // The app's own convention for a control that opens a dialog — `RegionsJump`, `FiltersPopover`
    // and `WindowFirstHeatStrip` all carry it, and this one opens `MapRegionPanel`.
    expect(row).toHaveAttribute('aria-haspopup', 'dialog');
    fireEvent.click(row);

    expect(onSelectRegion).toHaveBeenCalledWith(expect.objectContaining({ name: 'The Lakes' }));
  });

  /**
   * ⚠️ **Focus, and it is what makes `Escape` reachable at all.** Returning from the region panel
   * unmounts its back arrow, so without a target focus falls to `<body>` — outside the React
   * subtree `MapView`'s pane-level key handler is bound to. The row the reader stepped in from is
   * both the correct place to land and inside the pane.
   */
  it('returns focus to the row a reader stepped back from', () => {
    renderPanel({ focusRegion: 'North East' });

    expect(document.activeElement).toBe(
      screen.getAllByTestId('wf-win-panel-row').find((r) => r.getAttribute('data-region') === 'North East'),
    );
  });

  it('...falls back to the panel itself when that row is no longer in the list', () => {
    renderPanel({ focusRegion: 'Somewhere Else' });

    expect(document.activeElement).toBe(screen.getByTestId('wf-win-panel'));
  });

  /**
   * ⚠️ **This asserted the OPPOSITE until the PR review, and the old rule was wrong.** L5 had
   * `WindowControl`'s entry row focus the PILL and this panel take nothing — a fix for the same
   * `<body>`-focus problem, but one that leaves focus OUTSIDE a `role="dialog"` that has just
   * appeared, so a screen reader announces nothing and the press reads as a no-op on the only route
   * into the feature. `MapRegionPanel` diagnoses and fixes exactly that one level down; a lens
   * pointed out that L6 established the rule and did not carry it back up.
   */
  it('takes focus itself on a fresh open, so the dialog is announced', () => {
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    outside.focus();

    renderPanel();

    expect(document.activeElement).toBe(screen.getByTestId('wf-win-panel'));
    outside.remove();
  });

  it('says why it is empty rather than rendering a blank panel', () => {
    renderPanel({ rows: [] });

    expect(screen.getByTestId('wf-win-panel-empty')).toHaveTextContent('No region in your area has a sunset answer');
    expect(screen.queryByTestId('wf-win-panel-row')).toBeNull();
  });

  it('...and names the SCOPE, never "the catalogue" it has not looked at', () => {
    // ⚠️ `scopeIsArea` is false both for "Everywhere" AND for an empty scope (an away origin whose
    // region contributes no spots), so the first cut denied a catalogue it had never examined.
    renderPanel({ rows: [], scopeIsArea: false });

    expect(screen.getByTestId('wf-win-panel-empty')).toHaveTextContent('the map’s current scope');
  });

  it('says a NIGHT window has no per-region verdict, rather than blaming the reader\'s area', () => {
    renderPanel({ row: { ...ROW, kind: 'astro', eventType: 'ASTRO' }, verdict: null, rows: [] });

    expect(screen.getByTestId('wf-win-panel-empty'))
      .toHaveTextContent('Astro and aurora carry no per-region verdict');
  });
});

describe('MapWindowPanel — its own dismissal routes', () => {
  it('the close control closes it, and names what it closes', () => {
    const { onClose } = renderPanel();

    expect(screen.getByRole('button', { name: 'Close this panel' })).toBe(screen.getByTestId('wf-win-panel-close'));
    fireEvent.click(screen.getByTestId('wf-win-panel-close'));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('Escape from inside it closes it', () => {
    const { onClose } = renderPanel();

    fireEvent.keyDown(screen.getByTestId('wf-win-panel'), { key: 'Escape' });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  /**
   * ⚠️ The defect `map-landing-plan.md` §4 #37 recorded and O-20 carried: with the four-day sheet
   * open over the map, one `Escape` reaching this panel operated it BEHIND the sheet.
   *
   * <p>Both handlers run on one press — neither calls {@code stopPropagation} — so `MapView`'s
   * pane-level rule stood down correctly while this one did not, and this one acted. The fix is not
   * a new guard: it reads the same {@code foreignModalOver} predicate, against the same pane root,
   * that the pane handler and the landing card's document listener already read.
   */
  it('⚠️ Escape stands down while a dialog from outside the pane is over it', () => {
    const handlers = renderPanel();
    plantForeignModal();

    fireEvent.keyDown(screen.getByTestId('wf-win-panel'), { key: 'Escape' });

    expect(handlers.onClose, 'the panel must not act behind a modal').not.toHaveBeenCalled();
    expect(handlers.onSelectRegion).not.toHaveBeenCalled();
  });

  it('and leaves the press for the layer above rather than consuming it', () => {
    // The stand-down returns BEFORE `preventDefault`. If it did not, the sheet over this panel
    // would get a press already marked handled and the reader would need a second Escape to close
    // the thing they are actually looking at.
    renderPanel();
    plantForeignModal();

    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true });
    screen.getByTestId('wf-win-panel').dispatchEvent(event);

    expect(event.defaultPrevented).toBe(false);
  });

  it('and acts as before once nothing is over the pane', () => {
    const handlers = renderPanel();

    fireEvent.keyDown(screen.getByTestId('wf-win-panel'), { key: 'Escape' });

    expect(handlers.onClose).toHaveBeenCalledTimes(1);
  });


  it('a press outside dismisses it — L3\'s panel rule, NOT the landing card\'s', () => {
    // ⚠️ The card forbids an outside tap; a panel does not. `useOutsideDismiss` carries the panel
    // rule and its own doc names the card as the exception, so the two must not be confused.
    const { onClose } = renderPanel();

    fireEvent.mouseDown(document.body);

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('names itself for the window it is about', () => {
    renderPanel();

    expect(screen.getByRole('dialog', { name: 'Tonight sunset, region by region' }))
      .toBe(screen.getByTestId('wf-win-panel'));
  });
});
