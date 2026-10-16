import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import AuthHeading from '../components/auth/AuthHeading.jsx';

describe('AuthHeading', () => {
  it('is an h2 (the lockup owns the h1) in Newsreader 22px bone ink', () => {
    render(<AuthHeading testId="h">Sign in</AuthHeading>);

    const heading = screen.getByRole('heading', { level: 2, name: 'Sign in' });
    expect(heading).toBe(screen.getByTestId('h'));
    expect(heading).toHaveClass(
      'font-serif', 'font-semibold', 'text-[22px]', 'leading-tight', 'tracking-[-0.015em]', 'text-plex-text',
    );
  });

  it('never takes a verdict, status or coral colour, and renders no kicker', () => {
    const { container } = render(<AuthHeading>Sign in</AuthHeading>);

    expect(container.querySelector('h2').className).not.toMatch(/coral|verdict|plex-error|plex-ok/);
    // Exactly one element: the heading, with nothing rendered above it.
    expect(container.children).toHaveLength(1);
  });

  it('renders no data-testid when none is given', () => {
    const { container } = render(<AuthHeading>Sign in</AuthHeading>);
    expect(container.querySelector('h2')).not.toHaveAttribute('data-testid');
  });
});
