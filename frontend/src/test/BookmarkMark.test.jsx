import React from 'react';
import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import BookmarkMark from '../components/shared/BookmarkMark.jsx';

/**
 * The bookmark mark (screen-spec.md D1): decorative, one ribbon, two forms. `css: false` in vitest, so the lilac
 * `--wf-yours` colour is browser-measured; what a test can pin is the structure the colour and forced-colours modes
 * hang off (`currentColor`, never a literal).
 */
describe('BookmarkMark', () => {
  const mark = (props) => render(<BookmarkMark {...props} />).getByTestId('bookmark-mark');

  it('is decorative: aria-hidden and not focusable', () => {
    const svg = mark();
    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg).toHaveAttribute('focusable', 'false');
    expect(svg.tagName.toLowerCase()).toBe('svg');
  });

  it('draws the filled form by default, with currentColor', () => {
    const svg = mark();
    expect(svg).toHaveAttribute('data-form', 'filled');
    expect(svg).toHaveClass('wf-bookmark');
    expect(svg).not.toHaveClass('wf-bookmark-outline');
    const path = svg.querySelector('path');
    expect(path).toHaveAttribute('fill', 'currentColor');
    expect(path).not.toHaveAttribute('stroke');
  });

  it('draws the outline form with a currentColor stroke and no fill, so forced colours keep the two forms apart', () => {
    const svg = mark({ outline: true });
    expect(svg).toHaveAttribute('data-form', 'outline');
    expect(svg).toHaveClass('wf-bookmark', 'wf-bookmark-outline');
    const path = svg.querySelector('path');
    expect(path).toHaveAttribute('stroke', 'currentColor');
    expect(path).toHaveAttribute('fill', 'none');
  });

  it('carries a host class after its own, and never a text glyph', () => {
    const svg = mark({ className: 'wf-yp-mark' });
    expect(svg).toHaveClass('wf-bookmark', 'wf-yp-mark');
    expect(svg.textContent).toBe('');
  });
});
