import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WindowRegionRail from '../components/WindowRegionRail.jsx';

/**
 * The open row's region rail — the accessible equivalent of the field map above it.
 *
 * <p>That equivalence is the reason the accessible names are asserted rather than the text: the
 * canvas is {@code aria-hidden} and carries no {@code tabindex}, so these buttons are the whole of
 * what a non-pointer reader gets. A cell that renders its facts but names none of them would leave
 * the region layer reachable by nobody using a screen reader.
 */

function row(overrides = {}) {
  return {
    name: 'Northumberland & Tyneside',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    bestRating: 5,
    drawnCount: 12,
    countNoun: 'in reach',
    awayLabel: null,
    ...overrides,
  };
}

// ⚠️ The window's best (4) is deliberately DIFFERENT from every region's own best (5 and 3), and the
// drawn total (16) is deliberately NOT the sum of the rows' counts (12 + 4). Both were equal in the
// first cut, which made the two rules this file claims to pin — "the All cell states the WINDOW's
// number" and "each cell states its own" — survive a mutation that swapped one for the other. P1
// records that the two levels genuinely diverge (the canopy fallback is per window on one and per
// region on the other), so the fixture has to diverge too.
const TWO_ROWS = [row(), row({ name: 'The Lake District', verdictLabel: 'Maybe', verdict: 'MAYBE', bestRating: 3, drawnCount: 4 })];
const WINDOW_BEST = 4;
const WINDOW_DRAWN = 16;

function renderRail(props = {}) {
  const onSelect = vi.fn();
  render(
    <WindowRegionRail
      rows={TWO_ROWS}
      windowBest={WINDOW_BEST}
      drawnCount={WINDOW_DRAWN}
      countNoun="in reach"
      selected={null}
      onSelect={onSelect}
      {...props}
    />,
  );
  return { onSelect };
}

describe('WindowRegionRail — the All cell', () => {
  it('is the first cell and counts the regions it stands for', () => {
    renderRail();
    const cells = screen.getAllByTestId('wf-region-cell');
    expect(cells[0]).toHaveAttribute('data-region', 'all');
    expect(cells[0]).toHaveTextContent('All 2 regions');
  });

  it('states the WINDOW’s served best, which is the header’s own number', () => {
    // Two levels of "best" are on this screen and they are different populations (P1). They stay in
    // two labelled cells: this one is the window's, each region cell is its own.
    renderRail();
    const all = screen.getAllByTestId('wf-region-cell')[0];
    expect(all).toHaveTextContent('best 4★ · 16 in reach');
    // The rank-1 region's own number, which is NOT the window's — the whole point of two cells.
    expect(screen.getAllByTestId('wf-region-cell')[1]).toHaveTextContent('best 5★ · 12 in reach');
  });

  it('is pressed while no region is selected, so the state is not a tint alone', () => {
    renderRail({ selected: null });
    expect(screen.getAllByTestId('wf-region-cell')[0]).toHaveAttribute('aria-pressed', 'true');
  });

  it('is unpressed once a region is selected, and offers to show everything', () => {
    renderRail({ selected: 'Northumberland & Tyneside' });
    const all = screen.getAllByTestId('wf-region-cell')[0];
    expect(all).toHaveAttribute('aria-pressed', 'false');
    expect(all).toHaveTextContent('Show everything');
  });

  it('clears the selection when pressed', () => {
    const { onSelect } = renderRail({ selected: 'The Lake District' });
    fireEvent.click(screen.getAllByTestId('wf-region-cell')[0]);
    expect(onSelect).toHaveBeenCalledWith(null);
  });
});

describe('WindowRegionRail — a region cell', () => {
  it('names its region, its verdict word and its served best in one accessible name', () => {
    // Comma-separated real text, NOT name-from-contents over the visible spans: the cell is a flex
    // container, so in a browser those spans are joined with no whitespace at all — see the
    // component's own note, and the identical defect the heat strip shipped and fixed at P2.
    renderRail();
    expect(screen.getByRole('button', {
      name: 'Northumberland & Tyneside, Worth it, best 5★ · 12 in reach',
    })).toBeInTheDocument();
  });

  it('takes the verdict word from the row, never from the rating beside it', () => {
    renderRail({
      rows: [row({ verdict: 'STAND_DOWN', verdictLabel: 'Poor', bestRating: 5 }), TWO_ROWS[1]],
    });
    expect(screen.getAllByTestId('wf-region-cell')[1]).toHaveTextContent('Poor');
  });

  it('states its own selected state, so it is not a tint alone', () => {
    // Without this, `aria-pressed={false}` as a constant survives every other assertion in the file
    // and a screen-reader user cannot tell which of five regions is filtering the list below.
    renderRail({ selected: 'Northumberland & Tyneside' });
    const cells = screen.getAllByTestId('wf-region-cell');
    expect(cells[1]).toHaveAttribute('aria-pressed', 'true');
    expect(cells[2]).toHaveAttribute('aria-pressed', 'false');
  });

  it('selects itself when pressed', () => {
    const { onSelect } = renderRail();
    fireEvent.click(screen.getAllByTestId('wf-region-cell')[1]);
    expect(onSelect).toHaveBeenCalledWith('Northumberland & Tyneside');
  });

  it('clears when the already-selected cell is pressed again', () => {
    // The map's same-region rule, in the rail: one control, one behaviour, whichever surface you
    // reach it from.
    const { onSelect } = renderRail({ selected: 'Northumberland & Tyneside' });
    fireEvent.click(screen.getAllByTestId('wf-region-cell')[1]);
    expect(onSelect).toHaveBeenCalledWith(null);
  });
});

describe('WindowRegionRail — the visible layer', () => {
  it('renders the words on screen, not only in the hidden sentence', () => {
    // `toHaveTextContent` reads the `sr-only` name too, so every other assertion here passes with
    // the three visible spans deleted and the rail rendering blank. These pin the visible half.
    renderRail();
    const cell = screen.getAllByTestId('wf-region-cell')[1];
    const visible = [...cell.children].filter((n) => !n.className.includes('sr-only'));
    expect(visible.map((n) => n.textContent))
      .toEqual(['Northumberland & Tyneside', 'Worth it', 'best 5★ · 12 in reach']);
  });

  it('hides the visible spans from the accessible name, so nothing is announced twice', () => {
    renderRail();
    const cell = screen.getAllByTestId('wf-region-cell')[1];
    const visible = [...cell.children].filter((n) => !n.className.includes('sr-only'));
    visible.forEach((n) => expect(n).toHaveAttribute('aria-hidden', 'true'));
  });
});

describe('WindowRegionRail — what a cell says when it draws nothing', () => {
  it('states its distance when reach is what emptied it', () => {
    renderRail({
      rows: [row({ drawnCount: 0, awayLabel: '2h 38min away' }), TWO_ROWS[1]],
    });
    expect(screen.getAllByTestId('wf-region-cell')[1]).toHaveTextContent('best 5★ · 2h 38min away');
  });

  it('prints no zero when nothing was drawn and no distance was measured', () => {
    // "0 spots" is a fact about two controls the reader set, not about the region.
    renderRail({ rows: [row({ drawnCount: 0, awayLabel: null }), TWO_ROWS[1]] });
    const cell = screen.getAllByTestId('wf-region-cell')[1];
    expect(cell).toHaveTextContent('best 5★');
    expect(cell.textContent).not.toContain('0');
  });

  it('omits the star entirely when nothing in the region is rated', () => {
    // A null best means nothing there is rated, which is a different statement from a low one — the
    // window header's own rule.
    renderRail({ rows: [row({ bestRating: null, drawnCount: 3, countNoun: 'spots' }), TWO_ROWS[1]] });
    const cell = screen.getAllByTestId('wf-region-cell')[1];
    expect(cell).toHaveTextContent('3 spots');
    expect(cell.textContent).not.toContain('★');
  });

  it('drops the word "reach" when the count noun says it may not be used', () => {
    renderRail({ rows: [row({ countNoun: 'spots' }), TWO_ROWS[1]] });
    expect(screen.getAllByTestId('wf-region-cell')[1]).toHaveTextContent('12 spots');
  });
});

describe('WindowRegionRail — when there is nothing to choose between', () => {
  it('withdraws entirely on a single region, because a one-choice filter is not a control', () => {
    render(
      <WindowRegionRail rows={[row()]} windowBest={5} drawnCount={12} countNoun="in reach" />,
    );
    expect(screen.queryByTestId('wf-region-rail')).toBeNull();
  });

  it('withdraws entirely when the window has no regions at all', () => {
    render(<WindowRegionRail rows={[]} windowBest={null} drawnCount={0} countNoun="spots" />);
    expect(screen.queryByTestId('wf-region-rail')).toBeNull();
  });
});
