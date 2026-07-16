import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import VerdictPill from '../../components/shared/VerdictPill.jsx';

describe('VerdictPill', () => {
  it.each([
    ['GO', 'Worth it'],
    ['MARGINAL', 'Maybe'],
    ['STANDDOWN', 'Stand down'],
  ])('maps legacy verdict %s to "%s"', (verdict, text) => {
    render(<VerdictPill verdict={verdict} />);
    expect(screen.getByTestId('verdict-pill')).toHaveTextContent(text);
  });

  it('prefers displayVerdict over the legacy verdict', () => {
    render(<VerdictPill displayVerdict="WORTH_IT" verdict="STANDDOWN" />);
    expect(screen.getByTestId('verdict-pill')).toHaveTextContent('Worth it');
    expect(screen.getByTestId('verdict-pill').className).toContain('bg-green-600');
  });

  it('falls back to Awaiting when neither signal is recognisable', () => {
    render(<VerdictPill />);
    expect(screen.getByTestId('verdict-pill')).toHaveTextContent('Awaiting');
  });

  it('label prop overrides the default text without changing the colour', () => {
    render(<VerdictPill displayVerdict="STAND_DOWN" label="Too unsettled to forecast" />);
    const pill = screen.getByTestId('verdict-pill');
    expect(pill).toHaveTextContent('Too unsettled to forecast');
    expect(pill.className).toContain('bg-red-900/60');
  });
});
