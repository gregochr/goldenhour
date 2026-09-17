/**
 * `components/map/TideFitBlock.jsx` — the tide-fit block (tide-window-plan.md T5, design spec §4).
 *
 * Pure-render coverage: both tier headings exact, the jump calling `onSelectEv` with the row
 * OBJECT it was given (never an index or a copy), the denial's exact wording, `horizonWord`'s
 * default and override, and the unmeasured-facts discipline (no fact → nothing rendered, never a
 * "no tide alignment" line). The jump is queried through its role (`frontend-test-standards.md`:
 * role queries are required for anything interactive) rather than its test-id, since its accessible
 * NAME is the whole point of the control.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import TideFitBlock from '../components/map/TideFitBlock.jsx';

const MATCH = {
  aligned: true,
  state: 'HIGH',
  fitPhrase: 'high water, falling · HW 20:46 · 17m after sunset · 3.9 m',
};

const MISS = {
  aligned: false,
  state: 'LOW',
  shortfall: 'HIGHER',
  fitPhrase: 'wants low water · mid tide, rising at 05:42 · 2.6 m of 4.3 m',
};

const NEXT_FIT_ROW = {
  id: 'solar:2026-06-17:SUNSET', dayLabel: 'Tomorrow', eventType: 'SUNSET', time: '20:25',
};

describe('TideFitBlock — the unmeasured-facts discipline', () => {
  it('renders nothing when fact is null — never a "no tide alignment" line', () => {
    const { container } = render(<TideFitBlock fact={null} />);
    expect(container.textContent).toBe('');
  });

  it('renders nothing when fact carries no fitPhrase (no extremes to build one from)', () => {
    const { container } = render(<TideFitBlock fact={{ aligned: true, fitPhrase: null }} />);
    expect(container.textContent).toBe('');
  });
});

describe('TideFitBlock — the match tier', () => {
  it('renders the exact heading, the served fitPhrase, and the glyph — no jump, no denial', () => {
    render(<TideFitBlock fact={MATCH} want={['HIGH']} />);
    const block = screen.getByTestId('tide-fit-block');
    expect(block).toHaveAttribute('data-tier', 'match');
    expect(block.querySelector('b')).toHaveTextContent('Tide lands on the light');
    expect(block).toHaveTextContent('high water, falling · HW 20:46 · 17m after sunset · 3.9 m');
    expect(block.querySelector('svg')).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByTestId('tide-fit-denial')).toBeNull();
  });

  it('never shows a jump or denial line on a match, even with a nextFitRow supplied', () => {
    // A match tier has nothing to jump TO — the line is miss-only by construction, regardless of
    // what the caller happens to pass for a prop that only matters on the other tier.
    render(<TideFitBlock fact={MATCH} want={['HIGH']} nextFitRow={NEXT_FIT_ROW} />);
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByTestId('tide-fit-denial')).toBeNull();
  });
});

describe('TideFitBlock — the miss tier: the jump', () => {
  it('renders the exact heading, the served fitPhrase, and a jump naming the resolved row', () => {
    render(<TideFitBlock fact={MISS} want={['LOW']} nextFitRow={NEXT_FIT_ROW} />);
    const block = screen.getByTestId('tide-fit-block');
    expect(block).toHaveAttribute('data-tier', 'miss');
    expect(block.querySelector('b')).toHaveTextContent('Wrong water, not wrong light');
    expect(block).toHaveTextContent('wants low water · mid tide, rising at 05:42 · 2.6 m of 4.3 m');
    // The button's ACCESSIBLE NAME is the sentence — the `›` glyph is `aria-hidden` and contributes
    // nothing to it, which is the whole point of hiding it (a screen reader gets the words alone).
    expect(screen.getByRole('button', { name: /Next low water on the light · Tomorrow sunset 20:25/ }))
      .toBeInTheDocument();
    expect(screen.queryByTestId('tide-fit-denial')).toBeNull();
  });

  it('calls onSelectEv with the EXACT row object it was given — never an index, never a copy', () => {
    const onSelectEv = vi.fn();
    render(<TideFitBlock fact={MISS} want={['LOW']} nextFitRow={NEXT_FIT_ROW} onSelectEv={onSelectEv} />);
    fireEvent.click(screen.getByRole('button', { name: /Next low water on the light/ }));
    expect(onSelectEv).toHaveBeenCalledTimes(1);
    // `toBe`, not `toEqual` — the same reference, proving no re-derivation happened in between.
    expect(onSelectEv).toHaveBeenCalledWith(NEXT_FIT_ROW);
    expect(onSelectEv.mock.calls[0][0]).toBe(NEXT_FIT_ROW);
  });

  it('joins a two-value want with "or", in HIGH/MID/LOW order regardless of the array\'s own order', () => {
    render(<TideFitBlock fact={MISS} want={['LOW', 'HIGH']} nextFitRow={NEXT_FIT_ROW} />);
    expect(screen.getByRole('button', { name: /Next high water or low water on the light/ }))
      .toBeInTheDocument();
  });
});

describe('TideFitBlock — the miss tier: the denial', () => {
  it('renders the exact denial when nextFitRow is -1 (nextAlignedRow\'s own "no match" sentinel)', () => {
    render(<TideFitBlock fact={MISS} want={['LOW']} nextFitRow={-1} />);
    expect(screen.getByTestId('tide-fit-denial'))
      .toHaveTextContent('Nothing in these four days puts low water on the light here.');
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('renders the exact denial when nextFitRow is null (the default)', () => {
    render(<TideFitBlock fact={MISS} want={['LOW']} />);
    expect(screen.getByTestId('tide-fit-denial'))
      .toHaveTextContent('Nothing in these four days puts low water on the light here.');
  });

  it('uses a caller-supplied horizonWord instead of the "four days" default', () => {
    render(<TideFitBlock fact={MISS} want={['LOW']} horizonWord="3 days" />);
    expect(screen.getByTestId('tide-fit-denial'))
      .toHaveTextContent('Nothing in these 3 days puts low water on the light here.');
  });

  it('omits the whole jump/denial line when want resolves to nothing — a truthful claim needs something to name', () => {
    render(<TideFitBlock fact={MISS} want={[]} nextFitRow={NEXT_FIT_ROW} />);
    expect(screen.queryByRole('button')).toBeNull();
    expect(screen.queryByTestId('tide-fit-denial')).toBeNull();
    // The heading and body still render — they come from `fact` alone.
    const block = screen.getByTestId('tide-fit-block');
    expect(block).toHaveTextContent('Wrong water, not wrong light');
  });
});
