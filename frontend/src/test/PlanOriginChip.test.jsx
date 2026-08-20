import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import React from 'react';
import PlanOriginChip from '../components/PlanOriginChip.jsx';

/**
 * The origin chip (plan §4.8) — where the Plan tab is planning from, and the way to move it.
 *
 * <p><b>What breaks if these fail.</b> The chip is the only permanent statement of the frame of
 * reference: every drive figure, every leave-by time and the whole framing of six thumbnails are
 * measured from what it names. A chip that reads "Home" while the page plans from Keswick is worse
 * than no chip.
 */
describe('PlanOriginChip', () => {
  const ORIGIN = { name: 'Lake District', baseName: 'Keswick' };

  const setup = (props = {}) => {
    const handlers = { onOpenSearch: vi.fn(), onGoHome: vi.fn() };
    render(<PlanOriginChip origin={null} {...handlers} {...props} />);
    return handlers;
  };

  it('names the home place when one is known', () => {
    setup({ homePlace: 'Morpeth' });
    expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent('Home · Morpeth');
  });

  it('reads simply "Home" when no place is known — home is still the origin', () => {
    setup();
    expect(screen.getByTestId('window-first-origin-chip').textContent).toBe('⌂Home');
  });

  it('names the base town when the origin has moved', () => {
    setup({ origin: ORIGIN });
    expect(screen.getByTestId('window-first-origin-chip')).toHaveTextContent('Keswick');
  });

  it('takes the away treatment as a data attribute, so the state is testable and not colour-only', () => {
    setup({ origin: ORIGIN });
    expect(screen.getByTestId('window-first-origin-chip')).toHaveAttribute('data-away', 'true');
  });

  it('opens search from either state — the chip\'s job never changes with the state', () => {
    const home = setup();
    fireEvent.click(screen.getByTestId('window-first-origin-chip'));
    expect(home.onOpenSearch).toHaveBeenCalledTimes(1);
  });

  it('shows no ⌂ control at home — there is nowhere to return to', () => {
    setup({ homePlace: 'Morpeth' });
    expect(screen.queryByTestId('window-first-origin-home')).toBeNull();
  });

  it('⌂ returns the origin home, and is a separate control from the chip', () => {
    const { onGoHome, onOpenSearch } = setup({ origin: ORIGIN });
    fireEvent.click(screen.getByTestId('window-first-origin-home'));
    expect(onGoHome).toHaveBeenCalledTimes(1);
    expect(onOpenSearch).not.toHaveBeenCalled();
  });

  describe('accessible names', () => {
    it('spells the away state out — a screen reader must not hear the glyph', () => {
      setup({ origin: ORIGIN });
      expect(screen.getByRole('button', {
        name: 'Planning from Keswick in Lake District. Search to change it.',
      })).toBeInTheDocument();
    });

    it('⚠️ spells the home state out INCLUDING the place, which the visible text carries', () => {
      // WCAG 2.5.3 Label in Name: the accessible name must contain the visible words. Without the
      // place the chip drew `Home · Morpeth` under a name of "Planning from home", so "Morpeth"
      // appeared in no accessible name at all — a regression against the plain-text line this chip
      // replaced, and a dead end for a speech-input reader saying "click Home Morpeth".
      setup({ homePlace: 'Morpeth' });
      expect(screen.getByRole('button', {
        name: 'Planning from home · Morpeth. Search to change it.',
      })).toBeInTheDocument();
    });

    it('spells the home state out with no place when none is known', () => {
      setup();
      expect(screen.getByRole('button', {
        name: 'Planning from home. Search to change it.',
      })).toBeInTheDocument();
    });

    it('names the ⌂ control for what it does, not for its glyph', () => {
      setup({ origin: ORIGIN });
      expect(screen.getByRole('button', { name: 'Plan from home again' })).toBeInTheDocument();
    });
  });
});
