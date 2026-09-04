import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import WindowComingUpConditions from '../components/WindowComingUpConditions.jsx';

/** A `ComingUpCondition` as `ComingUpConditionsBuilder` actually serves one. */
const condition = (over = {}) => ({
  type: 'COASTAL_TIDES',
  name: 'Coastal tides',
  cadence: 'deterministic',
  interim: false,
  rateLabel: 'a run every 14.8 days · fixed by the ephemeris',
  quantLabel: 'occasional (3.9) · 7 runs in 90 days · typical run 4.7 m, top 10% reach 5.0 m',
  peak: { dateLabel: '26 Nov', valueLabel: '5.2 m', bits: 9.0 },
  occurrences: [],
  ...over,
});

const occ = (over = {}) => ({
  date: '2026-08-30',
  dateLabel: '30 Aug',
  valueLabel: '4.8 m',
  bits: 5.4,
  reason: null,
  status: 'heldBack',
  entryId: null,
  ...over,
});

/** jsdom implements no layout and therefore no `scrollIntoView` — guarded per the project's own
 * documented pattern (see `DateStripToday.test.jsx`). */
const realScrollIntoView = Element.prototype.scrollIntoView;
beforeEach(() => {
  Element.prototype.scrollIntoView = vi.fn();
});
afterEach(() => {
  Element.prototype.scrollIntoView = realScrollIntoView;
});

describe('WindowComingUpConditions — the strip', () => {
  it('renders nothing for an empty or absent conditions list', () => {
    const { container: empty } = render(
      <WindowComingUpConditions conditions={[]} onGoToPlan={vi.fn()} />,
    );
    expect(empty).toBeEmptyDOMElement();

    const { container: absent } = render(
      <WindowComingUpConditions conditions={undefined} onGoToPlan={vi.fn()} />,
    );
    expect(absent).toBeEmptyDOMElement();
  });

  it('renders one row per condition with its served name, cadence, rate, peak and quant line', () => {
    render(<WindowComingUpConditions conditions={[condition()]} onGoToPlan={vi.fn()} />);

    expect(screen.getByTestId('condition-name')).toHaveTextContent('Coastal tides');
    expect(screen.getByTestId('condition-cadence')).toHaveTextContent('deterministic');
    expect(screen.getByTestId('condition-rate')).toHaveTextContent('a run every 14.8 days');
    expect(screen.getByTestId('condition-peak')).toHaveTextContent('26 Nov');
    expect(screen.getByTestId('condition-peak')).toHaveTextContent('5.2 m');
    expect(screen.getByTestId('condition-peak')).toHaveTextContent('very rare');
    expect(screen.getByTestId('condition-quant')).toHaveTextContent('typical run 4.7 m, top 10% reach 5.0 m');
  });

  it('the strip\'s own header sub-line carries the "scores provisional" marker while any condition '
      + 'is interim, and omits it once every condition is mature (plan §7)', () => {
    const { rerender } = render(
      <WindowComingUpConditions conditions={[condition({ interim: true })]} onGoToPlan={vi.fn()} />,
    );
    expect(screen.getByTestId('coming-up-provisional')).toHaveTextContent('scores are provisional');

    rerender(<WindowComingUpConditions conditions={[condition({ interim: false })]} onGoToPlan={vi.fn()} />);
    expect(screen.queryByTestId('coming-up-provisional')).toBeNull();
  });

  it('shows a named empty-peak state rather than a blank cell when nothing passes the gate', () => {
    render(
      <WindowComingUpConditions conditions={[condition({ peak: null })]} onGoToPlan={vi.fn()} />,
    );

    expect(screen.getByTestId('condition-peak')).toHaveTextContent('no peak forecast right now');
  });

  it('the panel is collapsed by default and carries BOTH `hidden` and a display class', () => {
    render(<WindowComingUpConditions conditions={[condition()]} onGoToPlan={vi.fn()} />);

    const panel = screen.getByTestId('condition-panel');
    expect(panel).toHaveAttribute('hidden');
    expect(panel.className).not.toContain('wf-cond-occ-open');
    expect(screen.getByTestId('condition-row')).toHaveAttribute('aria-expanded', 'false');
  });

  it('clicking the row opens the panel, rotates the caret state, and clicking again closes it', () => {
    render(<WindowComingUpConditions conditions={[condition()]} onGoToPlan={vi.fn()} />);

    fireEvent.click(screen.getByTestId('condition-row'));
    expect(screen.getByTestId('condition-panel')).not.toHaveAttribute('hidden');
    expect(screen.getByTestId('condition-panel').className).toContain('wf-cond-occ-open');
    expect(screen.getByTestId('condition-row')).toHaveAttribute('aria-expanded', 'true');

    fireEvent.click(screen.getByTestId('condition-row'));
    expect(screen.getByTestId('condition-panel')).toHaveAttribute('hidden');
  });

  it('multiple panels can be open at once — independent, component-local state', () => {
    render(
      <WindowComingUpConditions
        conditions={[condition(), condition({ type: 'DUST', name: 'Saharan dust' })]}
        onGoToPlan={vi.fn()}
      />,
    );

    const rows = screen.getAllByTestId('condition-row');
    fireEvent.click(rows[0]);
    fireEvent.click(rows[1]);

    const panels = screen.getAllByTestId('condition-panel');
    expect(panels[0]).not.toHaveAttribute('hidden');
    expect(panels[1]).not.toHaveAttribute('hidden');
  });

  it('the occurrence header line counts held-back/in-the-list/inside-Plan from the served list', () => {
    render(
      <WindowComingUpConditions
        conditions={[condition({
          occurrences: [
            occ({ status: 'heldBack' }),
            occ({ status: 'promoted', entryId: 'spring-tide:2026-09-12:2026-09-15' }),
            occ({ status: 'promoted', entryId: 'spring-tide:2026-10-01:2026-10-03' }),
            occ({ status: 'insidePlan' }),
          ],
        })]}
        onGoToPlan={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByTestId('condition-row'));

    expect(screen.getByTestId('condition-occurrence-header')).toHaveTextContent(
      '1 not listed, 2 below, 1 on Plan',
    );
  });

  it('the "on Plan" clause is omitted when nothing has that status', () => {
    render(
      <WindowComingUpConditions
        conditions={[condition({ occurrences: [occ({ status: 'heldBack' })] })]}
        onGoToPlan={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByTestId('condition-row'));

    const header = screen.getByTestId('condition-occurrence-header');
    expect(header).toHaveTextContent('1 not listed, 0 below');
    expect(header).not.toHaveTextContent('on Plan');
  });

  it('a held-back occurrence is a plain, non-interactive row', () => {
    render(
      <WindowComingUpConditions
        conditions={[condition({ occurrences: [occ({ status: 'heldBack' })] })]}
        onGoToPlan={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId('condition-row'));

    const row = screen.getByTestId('condition-occurrence');
    expect(row.tagName).toBe('DIV');
    expect(screen.getByTestId('condition-occurrence-status')).toHaveTextContent('not listed');
  });

  it('a reason tag renders only when the server sent one (the max rule applied)', () => {
    render(
      <WindowComingUpConditions
        conditions={[condition({
          occurrences: [occ({ reason: 'max w/ supermoon' }), occ({ date: '2026-09-01', reason: null })],
        })]}
        onGoToPlan={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId('condition-row'));

    const rows = screen.getAllByTestId('condition-occurrence');
    expect(within(rows[0]).getByText('max w/ supermoon')).toBeInTheDocument();
  });

  it('an inside-Plan occurrence is a real button that calls onGoToPlan with its own date', () => {
    const onGoToPlan = vi.fn();
    render(
      <WindowComingUpConditions
        conditions={[condition({
          occurrences: [occ({ status: 'insidePlan', date: '2026-08-31' })],
        })]}
        onGoToPlan={onGoToPlan}
      />,
    );
    fireEvent.click(screen.getByTestId('condition-row'));

    const row = screen.getByTestId('condition-occurrence');
    expect(row.tagName).toBe('BUTTON');
    fireEvent.click(row);

    expect(onGoToPlan).toHaveBeenCalledWith('2026-08-31');
  });

  it('a promoted occurrence is a real button, clickable even though its status TEXT is a leaf span '
      + '(the whole row carries the click handler, per the 760px status-hiding rule)', () => {
    document.body.innerHTML = '<div data-entry-id="spring-tide:2026-09-12:2026-09-15"></div>';
    const entry = document.querySelector('[data-entry-id]');
    entry.scrollIntoView = vi.fn();

    render(
      <WindowComingUpConditions
        conditions={[condition({
          occurrences: [occ({
            status: 'promoted', entryId: 'spring-tide:2026-09-12:2026-09-15',
          })],
        })]}
        onGoToPlan={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId('condition-row'));

    const row = screen.getByTestId('condition-occurrence');
    expect(row.tagName).toBe('BUTTON');
    fireEvent.click(row);

    expect(entry.scrollIntoView).toHaveBeenCalled();
  });

  it('the condition row\'s accessible name keeps a word boundary between every cell — no two '
      + 'phrases glued together for lack of a real text-node separator', () => {
    render(<WindowComingUpConditions conditions={[condition()]} onGoToPlan={vi.fn()} />);

    const row = screen.getByRole('button', {
      name: 'Coastal tides deterministic a run every 14.8 days · fixed by the ephemeris '
        + 'peak 26 Nov · 5.2 m — very rare occasional (3.9) · 7 runs in 90 days · '
        + 'typical run 4.7 m, top 10% reach 5.0 m',
    });
    expect(row).toBeInTheDocument();
  });

  it('a promoted occurrence row\'s accessible name keeps a word boundary between every cell', () => {
    render(
      <WindowComingUpConditions
        conditions={[condition({
          occurrences: [occ({
            status: 'promoted', entryId: 'x', valueLabel: '4.8 m', bits: 5.4, reason: 'max w/ supermoon',
          })],
        })]}
        onGoToPlan={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId('condition-row'));

    expect(screen.getByRole('button', {
      name: '30 Aug 4.8 m uncommon max w/ supermoon see it below →',
    })).toBeInTheDocument();
  });

  it('scrolling to a promoted occurrence with no matching DOM entry is a harmless no-op', () => {
    render(
      <WindowComingUpConditions
        conditions={[condition({
          occurrences: [occ({ status: 'promoted', entryId: 'does-not-exist' })],
        })]}
        onGoToPlan={vi.fn()}
      />,
    );
    fireEvent.click(screen.getByTestId('condition-row'));

    expect(() => fireEvent.click(screen.getByTestId('condition-occurrence'))).not.toThrow();
  });
});

describe('WindowComingUpConditions — the topic glyph (G4, plan §4.2)', () => {
  it('wraps the swatch and the glyph in one `.wf-cond-fam` container, keeping the row a 5-item grid', () => {
    render(<WindowComingUpConditions conditions={[condition({ type: 'COASTAL_TIDES' })]} onGoToPlan={vi.fn()} />);

    const row = screen.getByTestId('condition-row');
    const fam = row.querySelector('.wf-cond-fam');
    expect(fam).toBeInTheDocument();
    expect(fam.children).toHaveLength(2);
    const swatch = fam.querySelector('.wf-cond-sw');
    const glyph = within(fam).getByTestId('condition-glyph');
    expect(swatch).toBeInTheDocument();
    expect(glyph).toBeInTheDocument();
    // Order, not just presence — "after the existing swatch" (plan §4.2) is a DOM-position claim.
    expect(swatch.compareDocumentPosition(glyph) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // Column count pin: fam / name / rate / peak / quant — unchanged from the pre-glyph 5, since
    // the glyph joined the swatch's existing grid cell rather than adding one of its own.
    expect(row.children).toHaveLength(5);
  });

  it.each([
    ['COASTAL_TIDES', '🌊'],
    ['DUST', '🏜️'],
    ['VALLEY_INVERSIONS', '☁️'],
  ])('carries the %s condition\'s family glyph', (type, glyph) => {
    render(<WindowComingUpConditions conditions={[condition({ type })]} onGoToPlan={vi.fn()} />);
    expect(screen.getByTestId('condition-glyph')).toHaveTextContent(glyph);
  });

  it('falls back to the night-sky glyph for a condition type the family map does not know', () => {
    render(<WindowComingUpConditions conditions={[condition({ type: 'SOME_FUTURE_CONDITION' })]} onGoToPlan={vi.fn()} />);
    expect(screen.getByTestId('condition-glyph')).toHaveTextContent('🌌');
  });

  it('hides the glyph from the accessibility tree', () => {
    render(<WindowComingUpConditions conditions={[condition()]} onGoToPlan={vi.fn()} />);
    expect(screen.getByTestId('condition-glyph')).toHaveAttribute('aria-hidden', 'true');
  });
});
