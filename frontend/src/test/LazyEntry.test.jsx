import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import App from '../App.jsx';
import { ASK_DOCK_ID, ASK_SHEET_LABEL } from '../components/ask/askIds.js';
import { resetLinkTokenForTests } from '../utils/oneTimeLinkToken.js';
import { verifyEmail } from '../api/authApi.js';
import ChunkErrorBoundary from '../components/shared/ChunkErrorBoundary.jsx';

/**
 * P2d (performance plan): the sign-in pages are a lazy route chunk. A signed-out reader still reaches
 * the login form (found asynchronously, once the chunk arrives), the emailed reset and verify links
 * still reach their pages through it, and the page-sized fallback keeps the layout in the meantime.
 * The settings dialog and Ask's dock and sheet have their own files (`LazyEntrySettings`,
 * `LazyEntryAsk`, `LazyEntryFailure`).
 */

vi.mock('../api/authApi.js', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
  refreshAccessToken: vi.fn(),
  register: vi.fn(),
  resendVerification: vi.fn(),
  verifyEmail: vi.fn(() => new Promise(() => {})),
  setPasswordForNewUser: vi.fn(),
  submitWaitlist: vi.fn(),
}));

describe('lazy entry code', () => {
  beforeEach(() => {
    localStorage.clear();
    resetLinkTokenForTests();
    window.history.replaceState({}, '', '/');
  });

  it('shows a page-sized fallback, then the login form, to a signed-out reader', async () => {
    render(<App />);
    expect(screen.getByTestId('auth-fallback')).toHaveClass('min-h-screen');
    expect(await screen.findByLabelText(/password/i, { selector: 'input' })).toBeInTheDocument();
    expect(screen.queryByTestId('auth-fallback')).toBeNull();
  });

  it('opens the emailed reset link on the reset page, through the lazy chunk', async () => {
    window.history.replaceState({}, '', '/reset#token=abc123');
    render(<App />);
    expect(await screen.findByText('Set a new password')).toBeInTheDocument();
  });

  it('opens the emailed verify link on the verify step, through the lazy chunk', async () => {
    window.history.replaceState({}, '', '/verify#token=abc123');
    render(<App />);
    expect(await screen.findByTestId('verify-step')).toBeInTheDocument();
    expect(verifyEmail).toHaveBeenCalledWith('abc123');
  });

  it('keeps the Ask identifiers in a module the shell can read without the components', () => {
    expect(ASK_DOCK_ID).toBe('wf-ask-dock');
    expect(ASK_SHEET_LABEL).toBe('Ask PhotoCast');
  });
});

describe('ChunkErrorBoundary', () => {
  /** Throws on render, the way a rejected lazy import is rethrown. */
  function Boom() {
    throw new Error('Failed to fetch dynamically imported module');
  }

  beforeEach(() => { vi.spyOn(console, 'error').mockImplementation(() => {}); });

  it('renders its children when nothing failed', () => {
    render(<ChunkErrorBoundary><p>fine</p></ChunkErrorBoundary>);
    expect(screen.getByText('fine')).toBeInTheDocument();
    expect(screen.queryByTestId('chunk-error')).toBeNull();
  });

  it('offers Reload, and Close only when asked to', () => {
    const reload = vi.fn();
    const onClose = vi.fn();
    const { rerender } = render(
      <ChunkErrorBoundary variant="overlay" label="Settings" reload={reload} onClose={onClose}><Boom /></ChunkErrorBoundary>,
    );
    expect(screen.getByRole('dialog', { name: 'Settings' })).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('chunk-error-reload'));
    expect(reload).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByTestId('chunk-error-close'));
    expect(onClose).toHaveBeenCalledTimes(1);

    rerender(<ChunkErrorBoundary key="b" variant="page" reload={reload}><Boom /></ChunkErrorBoundary>);
    expect(screen.getByTestId('chunk-error')).toBeInTheDocument();
    expect(screen.queryByTestId('chunk-error-close')).toBeNull();
  });
});
