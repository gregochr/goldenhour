/**
 * The Met Office impact matrix graphic (weather-warnings-plan.md section 12.1).
 *
 * <p>What breaks if these fail: the tick lands on the wrong cell, the axis labels drift from the Met Office's own
 * words, the text alternative is missing or invents words, or a bad value draws a misleading grid.
 */
import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import WarningMatrix from '../components/WarningMatrix.jsx';

describe('WarningMatrix', () => {
  it('draws the 4 by 4 grid in the Met Office palette', () => {
    render(<WarningMatrix likelihood={3} impact={2} />);
    const cells = screen.getAllByTestId('warning-matrix-cell');
    expect(cells).toHaveLength(16);
    const colourAt = (l, i) => cells.find((c) => c.dataset.likelihood === String(l) && c.dataset.impact === String(i));
    expect(colourAt(4, 4)).toHaveAttribute('fill', '#ca0a37');
    expect(colourAt(4, 4)).toHaveAttribute('data-colour', 'red');
    expect(colourAt(3, 3)).toHaveAttribute('fill', '#ff9900');
    expect(colourAt(3, 2)).toHaveAttribute('fill', '#ffe923');
    expect(colourAt(1, 1)).toHaveAttribute('fill', '#c4e90c');
  });

  it('ticks exactly the warning\'s own cell', () => {
    render(<WarningMatrix likelihood={3} impact={2} />);
    const ticked = screen.getAllByTestId('warning-matrix-cell').filter((c) => c.dataset.ticked === 'true');
    expect(ticked).toHaveLength(1);
    expect(ticked[0]).toHaveAttribute('data-likelihood', '3');
    expect(ticked[0]).toHaveAttribute('data-impact', '2');
    expect(screen.getAllByTestId('warning-matrix-tick')).toHaveLength(1);
  });

  it('moves the tick with the values', () => {
    render(<WarningMatrix likelihood={4} impact={4} />);
    const ticked = screen.getAllByTestId('warning-matrix-cell').find((c) => c.dataset.ticked === 'true');
    expect(ticked).toHaveAttribute('data-colour', 'red');
  });

  it('carries the text alternative as its accessible name', () => {
    render(<WarningMatrix likelihood={3} impact={2} />);
    expect(screen.getByRole('img', { name: 'Likelihood 3 of 4, impact 2 of 4' })).toBeInTheDocument();
  });

  it('labels the axes exactly "Likelihood" and "Impact" and prints no invented words', () => {
    render(<WarningMatrix likelihood={3} impact={2} />);
    const svg = screen.getByTestId('warning-matrix');
    expect(within(svg).getByText('Likelihood')).toBeInTheDocument();
    expect(within(svg).getByText('Impact')).toBeInTheDocument();
    expect(svg.textContent).not.toMatch(/low|medium|high|minor|major|severe/i);
  });

  it.each([
    [null, 2], [3, null], [0, 2], [3, 5], [2.5, 2], ['3', 2],
  ])('draws nothing for likelihood %s and impact %s', (likelihood, impact) => {
    const { container } = render(<WarningMatrix likelihood={likelihood} impact={impact} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('draws nothing with no props', () => {
    const { container } = render(<WarningMatrix />);
    expect(container).toBeEmptyDOMElement();
  });

  it('accepts extra classes', () => {
    render(<WarningMatrix likelihood={1} impact={1} className="extra" />);
    expect(screen.getByTestId('warning-matrix')).toHaveClass('extra');
  });
});
