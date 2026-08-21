import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import WindowAttributeRow from '../components/WindowAttributeRow.jsx';
import { buildWindowRows } from '../utils/windowFirstRows.js';

/** The tide row exactly as `buildWindowRows` emits one, so the test cannot drift from the source. */
function tideRow(tideOverrides = {}) {
  return buildWindowRows({
    tide: {
      locationName: 'Whitby',
      state: 'MID',
      direction: 'FALLING',
      nearestType: 'HW',
      nearestTime: '19:28',
      nearestOffset: '1h43 before sunset',
      range: '4.9 m',
      rangeAnomaly: '1.2 m above an average tide',
      seas: '0.3 m · smooth',
      curve: [0, 0.5, 1],
      windowPosition: 0.5,
      windowLevel: 0.5,
      ...tideOverrides,
    },
    badges: [],
  })[0];
}

describe('WindowAttributeRow', () => {
  it('states the kicker and every fact, in the order the row derived them', () => {
    render(<WindowAttributeRow row={tideRow()} />);

    expect(screen.getByTestId('window-attribute-kicker')).toHaveTextContent('≈ Tide');
    expect(screen.getAllByTestId('window-attribute-fact').map((f) => f.textContent)).toEqual([
      'mid tide,falling',
      'HW 19:28 ·1h43 before sunset',
      'seas 0.3 m · smooth',
      '4.9 m · 1.2 m above an average tide · at Whitby',
    ]);
  });

  it('marks its emphasised segments so the two facts a reader acts on carry weight', () => {
    // The tone is what `.wf-facts [data-tone="strong"]` styles, and it is the only difference
    // between "falling" and the words around it — asserting the text alone would survive its loss.
    render(<WindowAttributeRow row={tideRow()} />);

    const [first] = screen.getAllByTestId('window-attribute-fact');

    expect(within(first).getByText('mid tide,').dataset.tone).toBe('base');
    expect(within(first).getByText('falling').dataset.tone).toBe('strong');
  });

  it('gives the phone-droppable fact the class the media query keys on', () => {
    render(<WindowAttributeRow row={tideRow()} />);

    const optional = screen.getAllByTestId('window-attribute-fact')
      .filter((f) => f.className === 'opt');

    expect(optional).toHaveLength(1);
    expect(optional[0]).toHaveTextContent('seas 0.3 m · smooth');
  });

  describe('the sparkline', () => {
    it('draws the trace and the window mark from the payload geometry', () => {
      render(<WindowAttributeRow row={tideRow()} />);

      expect(screen.getByTestId('window-tide-trace'))
        .toHaveAttribute('d', 'M0.00 24.00 L52.00 12.00 L104.00 0.00');
      expect(screen.getByTestId('window-tide-mark')).toHaveAttribute('cx', '52');
      expect(screen.getByTestId('window-tide-mark')).toHaveAttribute('cy', '12');
      expect(screen.getByTestId('window-tide-mark-rule')).toHaveAttribute('x1', '52');
    });

    it('is hidden from assistive technology, because the facts beside it are the answer', () => {
      // The licence for the picture. `TideRunRow` makes the same split and its Javadoc says
      // outright: do not hide the words.
      render(<WindowAttributeRow row={tideRow()} />);

      expect(screen.getByTestId('window-tide-sparkline')).toHaveAttribute('aria-hidden', 'true');
    });

    it('keeps the box at exactly its viewBox, so the window mark stays a circle', () => {
      // The design's `preserveAspectRatio="none"` is deliberately not carried over: it is inert at
      // 1:1 and would turn the mark into an ellipse the moment the column stretched.
      render(<WindowAttributeRow row={tideRow()} />);

      const svg = screen.getByTestId('window-tide-svg');

      expect(svg).toHaveAttribute('viewBox', '0 0 104 24');
      expect(svg).toHaveAttribute('width', '104');
      expect(svg).toHaveAttribute('height', '24');
      expect(svg).not.toHaveAttribute('preserveAspectRatio');
    });

    it('draws no mark when the instant could not be placed, and keeps the trace', () => {
      render(<WindowAttributeRow row={tideRow({ windowPosition: null })} />);

      expect(screen.getByTestId('window-tide-trace')).toBeInTheDocument();
      expect(screen.queryByTestId('window-tide-mark')).toBeNull();
      expect(screen.queryByTestId('window-tide-mark-rule')).toBeNull();
    });

    it('is absent entirely when there is no shape to draw, and the row still states its facts', () => {
      render(<WindowAttributeRow row={tideRow({ curve: [] })} />);

      expect(screen.queryByTestId('window-tide-sparkline')).toBeNull();
      expect(screen.getAllByTestId('window-attribute-fact')[0]).toHaveTextContent('mid tide,');
    });
  });

  it('never renders the design\'s action column, which would be a control that does nothing', () => {
    // "61 coastal locations →" is a count of our own data (§6 bans those outright) and "Filter to
    // high ground →" is P11's type control. Both would ship inert, which §6 also bans.
    render(<WindowAttributeRow row={tideRow()} />);

    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByTestId('window-attribute-row').textContent).not.toContain('→');
  });
});
