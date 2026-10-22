import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import AuthShell from '../components/auth/AuthShell.jsx';

describe('AuthShell', () => {
  it('wraps its children in the .auth-shell page and the max-w-sm column', () => {
    render(
      <AuthShell>
        <p data-testid="card">card</p>
      </AuthShell>,
    );

    const shell = screen.getByTestId('auth-shell');
    // The safe-area padding and the dynamic-viewport height live in the stylesheet rule; the
    // wrapper must not re-state them as utilities that would outrank it.
    expect(shell.className).toBe('auth-shell');

    const column = screen.getByTestId('card').parentElement;
    expect(column).toHaveClass('w-full', 'max-w-sm');
    expect(shell.contains(column)).toBe(true);
  });

  it('puts the auth lockup (the page h1) above the children, with mb-8 between', () => {
    render(
      <AuthShell>
        <p data-testid="card">card</p>
      </AuthShell>,
    );

    const lockup = screen.getByTestId('brand-lockup');
    expect(lockup).toHaveAttribute('data-variant', 'auth');
    expect(screen.getByRole('heading', { level: 1, name: 'PhotoCast' })).toBeInTheDocument();
    expect(lockup.parentElement).toHaveClass('mb-8');
    // Document order: the lockup precedes the card.
    expect(
      lockup.compareDocumentPosition(screen.getByTestId('card')) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });
});
