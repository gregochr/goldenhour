import React from 'react';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import WindowRegionRail, { shapedAllMeta, shapedRowMeta } from '../components/WindowRegionRail.jsx';

/**
 * The popup's region rail for a body shaped by the reader's sub-region picks (sub-regions-plan §11 S5b-1, handoff
 * task 6, decision 15).
 *
 * <p><b>What breaks if these fail:</b> the All cell prints a window best that the server stripped, or "0 in your
 * sub-regions"; a region the reader has no pick in says "not scored" or a count of places it holds; a picked
 * region prints a star the reader may not see (a client max rather than the served picksBestRating) or a plural for
 * one; the label says "best first" over a list that is picks first; a Pro, Admin or flag-off rail changes at all.
 */

function row(overrides = {}) {
  return {
    name: 'Northumberland & Tyneside',
    verdict: 'WORTH_IT',
    verdictLabel: 'Worth it',
    bestRating: null,
    drawnCount: 21,
    countNoun: 'spots',
    awayLabel: null,
    shaped: true,
    hasPicks: true,
    picksBestRating: 4,
    outsideCount: 0,
    ...overrides,
  };
}

const ROWS = [
  row(),
  row({
    name: 'The Yorkshire Dales', hasPicks: false, drawnCount: 0, outsideCount: 14, picksBestRating: null,
  }),
  row({
    name: 'The Lake District', verdict: 'MAYBE', verdictLabel: 'Maybe', drawnCount: 10, picksBestRating: 4, outsideCount: 3,
  }),
];

function renderRail(props = {}) {
  const onSelect = vi.fn();
  render(
    <WindowRegionRail
      rows={ROWS}
      windowBest={null}
      drawnCount={31}
      countNoun="spots"
      selected={null}
      onSelect={onSelect}
      shaped
      outsideCount={232}
      {...props}
    />,
  );
  return { onSelect };
}

const cell = (name) => screen.getAllByTestId('wf-region-cell').find((c) => c.dataset.region === name);
const meta = (name) => cell(name).querySelector('.wf-rr-m');

describe('WindowRegionRail for a shaped body', () => {
  it('says the order in its label', () => {
    renderRail();

    expect(screen.getByTestId('wf-region-rail-label').textContent).toBe('By region · your sub-regions first');
  });

  it('prints the places in and out on the All cell, with no star', () => {
    renderRail({ windowBest: 5 });

    expect(meta('all').textContent).toBe('31 in your sub-regions · 232 outside');
    expect(meta('all').textContent).not.toMatch(/★/);
  });

  it('prints only the outside count, never "0 in", for a reader with no pick', () => {
    renderRail({ drawnCount: 0 });

    expect(meta('all').textContent).toBe('232 outside your sub-regions');
    expect(screen.getByTestId('wf-region-rail').textContent).not.toMatch(/0 in/);
  });

  it('keeps the verdict of a region the reader has no pick in, and says it is outside', () => {
    renderRail();

    const dales = cell('The Yorkshire Dales');
    expect(dales.querySelector('.wf-rr-v').textContent).toBe('Worth it');
    expect(meta('The Yorkshire Dales').textContent).toBe('outside your sub-regions');
    expect(dales.dataset.outside).toBe('true');
    expect(dales.textContent).not.toMatch(/not scored|not rated|—|\b0\b/i);
  });

  it('prints the served star and the places for a region they hold, and the outside ones after', () => {
    renderRail();

    expect(meta('Northumberland & Tyneside').textContent).toBe('best 4★ · 21 places');
    expect(meta('The Lake District').textContent).toBe('best 4★ · 10 places · 3 outside');
    expect(cell('The Lake District').dataset.outside).toBeUndefined();
  });

  it('leaves the star out when none of the reader\'s places there is rated', () => {
    renderRail({ rows: [row({ picksBestRating: null }), ROWS[1]] });

    expect(meta('Northumberland & Tyneside').textContent).toBe('21 places');
  });

  it('is singular at one', () => {
    expect(shapedRowMeta(row({ drawnCount: 1, outsideCount: 1 }))).toBe('best 4★ · 1 place · 1 outside');
    expect(shapedAllMeta(1, 1)).toBe('1 in your sub-regions · 1 outside');
    expect(shapedAllMeta(1, 0)).toBe('1 in your sub-regions');
    expect(shapedAllMeta(0, 1)).toBe('1 outside your sub-regions');
    expect(shapedAllMeta(0, 0)).toBe('');
  });

  it('names each cell in one sentence for a screen reader, with the same words', () => {
    renderRail();

    const name = (c) => document.getElementById(c.getAttribute('aria-labelledby')).textContent;
    expect(name(cell('all'))).toBe('All 3 regions, Showing everything, 31 in your sub-regions · 232 outside');
    expect(name(cell('The Yorkshire Dales'))).toBe('The Yorkshire Dales, Worth it, outside your sub-regions');
    expect(name(cell('The Lake District'))).toBe('The Lake District, Maybe, best 4★ · 10 places · 3 outside');
  });

  it('selects a region on press, outside or not, and clears it on a second press', () => {
    const { onSelect } = renderRail({ selected: 'The Yorkshire Dales' });

    fireEvent.click(cell('The Yorkshire Dales'));
    fireEvent.click(cell('The Lake District'));

    expect(onSelect).toHaveBeenNthCalledWith(1, null);
    expect(onSelect).toHaveBeenNthCalledWith(2, 'The Lake District');
  });

  it('wraps a long meta line instead of widening its cell, on a phone and everywhere (360px)', () => {
    const css = readFileSync(resolve(__dirname, '../index.css'), 'utf8');
    const metaRule = css.match(/\.wf-rrail\[data-shaped="true"\] \.wf-rr-m \{[^}]*\}/)[0];
    // Scoped to the shaped rail, so every other rail's layout is untouched.
    expect(css.match(/\.wf-rr-m \{[^}]*\}/)[0]).not.toMatch(/overflow-wrap/);

    expect(metaRule).toMatch(/min-width: 0/);
    expect(metaRule).toMatch(/overflow-wrap: anywhere/);
    // The phone's All cell is a wrapping flex row, so a long sentence drops under its name rather than overflowing.
    expect(css).toMatch(/\.wf-rr-all \{[^}]*flex-wrap: wrap/);
  });
});

describe('WindowRegionRail for every other body', () => {
  it('is unchanged: best first, the window best on the All cell, the plain counts', () => {
    render(
      <WindowRegionRail
        rows={[{
          name: 'Coast', verdict: 'WORTH_IT', verdictLabel: 'Worth it', bestRating: 5, drawnCount: 12, countNoun: 'in reach',
        }, {
          name: 'Dales', verdict: 'MAYBE', verdictLabel: 'Maybe', bestRating: 3, drawnCount: 4, countNoun: 'in reach',
        }]}
        windowBest={4}
        drawnCount={16}
        countNoun="in reach"
        selected={null}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByTestId('wf-region-rail-label').textContent).toBe('By region · best first');
    expect(meta('all').textContent).toBe('best 4★ · 16 in reach');
    expect(meta('Coast').textContent).toBe('best 5★ · 12 in reach');
    expect(cell('Coast').dataset.outside).toBeUndefined();
  });
});
