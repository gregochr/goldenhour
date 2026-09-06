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
import { describe, it, expect, vi } from 'vitest';
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
  const result = render(
    <MapWindowPanel
      row={ROW}
      verdict={{ tier: 'WORTH_IT' }}
      note="Verdict is the strongest region’s average."
      rows={REGIONS}
      onClose={onClose}
      {...props}
    />,
  );
  return { ...result, onClose };
}

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

  it('⚠️ rows are a DIV until L6 gives them somewhere to go — never a disabled button', () => {
    // CLAUDE.md's own matrix rule ("a travel day is a div, never a button"). `disabled` announced
    // them as *unavailable*, a claim about these regions that is false — the feature is unbuilt —
    // and took every row out of the tab order, leaving the ✕ as the panel's only stop.
    renderPanel();

    const rows = screen.getAllByTestId('wf-win-panel-row');
    expect(rows[0].tagName).toBe('DIV');
    expect(screen.queryAllByRole('button', { name: /The Lakes/ })).toHaveLength(0);
  });

  it('...and a BUTTON, named for its region, once it does', () => {
    const onSelectRegion = vi.fn();
    renderPanel({ onSelectRegion });

    const row = screen.getByRole('button', { name: /The Lakes.*Worth it/ });
    expect(row).toHaveAttribute('data-region', 'The Lakes');
    fireEvent.click(row);

    expect(onSelectRegion).toHaveBeenCalledWith(expect.objectContaining({ name: 'The Lakes' }));
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
