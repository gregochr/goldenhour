import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ProPill from '../../components/shared/ProPill.jsx';

describe('ProPill', () => {
  it('renders "Pro" text', () => {
    render(<ProPill />);
    expect(screen.getByText('Pro')).toBeInTheDocument();
  });

  it('has data-testid', () => {
    render(<ProPill />);
    expect(screen.getByTestId('pro-pill')).toBeInTheDocument();
  });

  it('accepts className prop for margin adjustments', () => {
    render(<ProPill className="ml-2" />);
    expect(screen.getByTestId('pro-pill').className).toContain('ml-2');
  });
});
