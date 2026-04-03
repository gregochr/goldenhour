import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ErrorBanner from '../../components/shared/ErrorBanner.jsx';

describe('ErrorBanner', () => {
  it('renders nothing when message is falsy', () => {
    const { container } = render(<ErrorBanner />);
    expect(container.innerHTML).toBe('');
  });

  it('renders nothing when message is empty string', () => {
    const { container } = render(<ErrorBanner message="" />);
    expect(container.innerHTML).toBe('');
  });

  it('renders message in error card', () => {
    render(<ErrorBanner message="Something went wrong" />);
    expect(screen.getByText('Something went wrong')).toBeInTheDocument();
  });

  it('merges className', () => {
    render(<ErrorBanner message="Oops" className="mt-4" data-testid="err" />);
    const el = screen.getByTestId('err');
    expect(el.className).toContain('mt-4');
    expect(el.className).toContain('bg-red-900/20');
  });

  it('forwards data-testid', () => {
    render(<ErrorBanner message="Oops" data-testid="my-error" />);
    expect(screen.getByTestId('my-error')).toBeInTheDocument();
  });
});
