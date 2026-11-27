import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import StarFigure from '../components/shared/StarFigure.jsx';

/** A served rating inside a line of words: {@code 4★} for the eye, "4 stars" for a screen reader. */

describe('StarFigure', () => {
  it('prints the number and the glyph, hides the glyph, and speaks the plural', () => {
    render(<button type="button"><StarFigure n={4} /></button>);
    const button = screen.getByRole('button');
    expect(button.textContent).toBe('4★ stars');
    expect(button.querySelector('[aria-hidden="true"]')).toHaveTextContent('★');
    expect(button).toHaveAccessibleName('4 stars');
  });

  it('speaks the singular for one star', () => {
    render(<button type="button"><StarFigure n={1} /></button>);
    expect(screen.getByRole('button')).toHaveAccessibleName('1 star');
  });
});
