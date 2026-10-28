import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from '../App.jsx';
import { ASK_DOCK_ID, ASK_SHEET_LABEL } from '../components/ask/askIds.js';

/**
 * P2d (performance plan): the sign-in pages are a lazy route chunk. A signed-out reader still reaches
 * the login form (found asynchronously, once the chunk arrives), and the page-sized fallback keeps the
 * layout in the meantime. The authenticated surfaces that went lazy with it (the settings dialog, the
 * Ask dock and sheet) are covered by `AppSettingsRoutes`, `AskShellEntry` and the other Ask shell suites.
 */

vi.mock('../api/authApi.js', () => ({
  login: vi.fn(),
  logout: vi.fn(),
  changePassword: vi.fn(),
  refreshAccessToken: vi.fn(),
  register: vi.fn(),
  resendVerification: vi.fn(),
  verifyEmail: vi.fn(),
  setPasswordForNewUser: vi.fn(),
  submitWaitlist: vi.fn(),
}));

describe('lazy entry code', () => {
  beforeEach(() => {
    localStorage.clear();
    window.history.replaceState({}, '', '/');
  });

  it('shows a page-sized fallback, then the login form, to a signed-out reader', async () => {
    render(<App />);
    expect(screen.getByTestId('auth-fallback')).toHaveClass('min-h-screen');
    expect(await screen.findByLabelText(/password/i, { selector: 'input' })).toBeInTheDocument();
    expect(screen.queryByTestId('auth-fallback')).toBeNull();
  });

  it('keeps the Ask identifiers in a module the shell can read without the components', () => {
    expect(ASK_DOCK_ID).toBe('wf-ask-dock');
    expect(ASK_SHEET_LABEL).toBe('Ask PhotoCast');
  });
});
