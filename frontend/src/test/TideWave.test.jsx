/**
 * `components/map/TideWave.jsx` — the shared tide-alignment glyph.
 *
 * Pure-render coverage: the plain 14×8 wave when neither `shortfall` nor `state` is given, the
 * arrow variant (widened box, second path) for each shortfall direction, and the match-side letter
 * (tide-window-plan.md §4 #21) for each served state — including the rule that an arrow always
 * wins, so a caller that (incorrectly) hands both never draws two marks in one glyph.
 */
import React from 'react';
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import TideWave from '../components/map/TideWave.jsx';

describe('TideWave — the plain wave', () => {
  it('draws the 14×8 box with a single path and no data-wide when given neither shortfall nor state', () => {
    const { container } = render(<TideWave testId="tw" />);
    const svg = container.querySelector('[data-testid="tw"]');
    expect(svg).not.toHaveAttribute('data-wide');
    expect(svg).toHaveAttribute('viewBox', '0 0 14 8');
    expect(svg).toHaveAttribute('width', '14');
    expect(svg.querySelectorAll('path')).toHaveLength(1);
    expect(svg.querySelector('text')).toBeNull();
  });

  it('stays the plain wave when state is explicitly null — the same default as omitting it', () => {
    const { container } = render(<TideWave testId="tw" state={null} shortfall={null} />);
    const svg = container.querySelector('[data-testid="tw"]');
    expect(svg).not.toHaveAttribute('data-wide');
    expect(svg.querySelector('text')).toBeNull();
  });
});

describe('TideWave — the miss arrow', () => {
  it('draws the wide box, two paths and the up-arrow for HIGHER, with no letter even when state is also given', () => {
    const { container } = render(<TideWave testId="tw" shortfall="HIGHER" state="HIGH" />);
    const svg = container.querySelector('[data-testid="tw"]');
    expect(svg).toHaveAttribute('data-wide', 'true');
    expect(svg).toHaveAttribute('viewBox', '0 0 21 8');
    expect(svg.querySelectorAll('path')).toHaveLength(2);
    expect(svg.querySelectorAll('path')[1]).toHaveAttribute('d', expect.stringContaining('M17.5 6.8V1.2'));
    // The arrow always wins — a miss has nothing to letter (§4 #21).
    expect(svg.querySelector('text')).toBeNull();
  });

  it('draws the down-arrow for LOWER, still with no letter', () => {
    const { container } = render(<TideWave testId="tw" shortfall="LOWER" state="LOW" />);
    const svg = container.querySelector('[data-testid="tw"]');
    expect(svg.querySelectorAll('path')[1]).toHaveAttribute('d', expect.stringContaining('M17.5 1.2V6.8'));
    expect(svg.querySelector('text')).toBeNull();
  });
});

describe('TideWave — the match letter (tide-window-plan.md §4 #21)', () => {
  it.each([
    ['HIGH', 'H'],
    ['MID', 'M'],
    ['LOW', 'L'],
  ])('letters a %s match with "%s" in the arrow\'s wide box, drawing no arrow path', (state, letter) => {
    const { container } = render(<TideWave testId="tw" state={state} />);
    const svg = container.querySelector('[data-testid="tw"]');
    expect(svg).toHaveAttribute('data-wide', 'true');
    expect(svg).toHaveAttribute('viewBox', '0 0 21 8');
    // The wave path only — no arrow path alongside the letter.
    expect(svg.querySelectorAll('path')).toHaveLength(1);
    const text = svg.querySelector('text');
    expect(text).toHaveTextContent(letter);
    expect(text).toHaveAttribute('fill', 'currentColor');
  });
});

describe('TideWave — always aria-hidden', () => {
  it('carries aria-hidden on every variant', () => {
    const { container: plain } = render(<TideWave testId="tw" />);
    const { container: letter } = render(<TideWave testId="tw" state="HIGH" />);
    const { container: arrow } = render(<TideWave testId="tw" shortfall="HIGHER" />);
    expect(plain.querySelector('[data-testid="tw"]')).toHaveAttribute('aria-hidden', 'true');
    expect(letter.querySelector('[data-testid="tw"]')).toHaveAttribute('aria-hidden', 'true');
    expect(arrow.querySelector('[data-testid="tw"]')).toHaveAttribute('aria-hidden', 'true');
  });
});
