import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from '../App.jsx';

/**
 * P2d: the auth pages are a lazy chunk, and a chunk that fails to load (a stale tab after a deploy, an
 * offline first visit) shows the page-sized `ChunkErrorBoundary` with a Reload instead of unmounting
 * the root to a blank page.
 */
vi.mock('../components/auth/AuthPages.jsx', () => {
  throw new Error('Failed to fetch dynamically imported module');
});
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

describe('a failed auth chunk', () => {
  beforeEach(() => {
    localStorage.clear();
    window.history.replaceState({}, '', '/');
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('shows the error boundary with a Reload, not a blank page', async () => {
    render(<App />);
    expect(await screen.findByTestId('chunk-error')).toBeInTheDocument();
    expect(screen.getByTestId('chunk-error-reload')).toBeInTheDocument();
    expect(screen.queryByTestId('chunk-error-close')).toBeNull();
  });
});
