import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import SessionExpiryBanner from '../components/SessionExpiryBanner.jsx';

const mockRefreshSession = vi.fn();

vi.mock('../context/AuthContext.jsx', () => ({
  useAuth: vi.fn(),
}));

import { useAuth } from '../context/AuthContext.jsx';

function renderBanner(sessionDaysRemaining, refreshSession = mockRefreshSession) {
  useAuth.mockReturnValue({ sessionDaysRemaining, refreshSession });
  return render(<SessionExpiryBanner />);
}

describe('SessionExpiryBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('returns null when sessionDaysRemaining is null', () => {
    const { container } = renderBanner(null);
    expect(container.firstChild).toBeNull();
  });

  it('returns null when sessionDaysRemaining > 7', () => {
    const { container } = renderBanner(8);
    expect(container.firstChild).toBeNull();
  });

  it('renders an amber banner at 7 days', () => {
    renderBanner(7);
    const banner = screen.getByTestId('session-expiry-banner');
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveClass('bg-amber-950');
    expect(screen.getByText(/expires in 7 days/)).toBeInTheDocument();
  });

  it('renders a red banner at 1 day', () => {
    renderBanner(1);
    const banner = screen.getByTestId('session-expiry-banner');
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveClass('bg-red-950');
    expect(screen.getByText(/expires in 1 day$/)).toBeInTheDocument();
  });

  it('shows "expires today" at 0 days', () => {
    renderBanner(0);
    const banner = screen.getByTestId('session-expiry-banner');
    expect(banner).toHaveClass('bg-red-950');
    expect(screen.getByText('Your session expires today')).toBeInTheDocument();
  });

  it('shows singular "day" at 1 day remaining', () => {
    renderBanner(1);
    expect(screen.getByText(/expires in 1 day$/)).toBeInTheDocument();
  });

  it('shows plural "days" at 5 days remaining', () => {
    renderBanner(5);
    expect(screen.getByText(/expires in 5 days/)).toBeInTheDocument();
  });

  it('renders with role="alert"', () => {
    renderBanner(3);
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('clicking refresh button calls refreshSession', async () => {
    mockRefreshSession.mockResolvedValue(undefined);
    renderBanner(5);

    const button = screen.getByTestId('session-refresh-button');
    expect(button).toBeInTheDocument();
    fireEvent.click(button);

    await waitFor(() => {
      expect(mockRefreshSession).toHaveBeenCalledTimes(1);
    });
  });

  it('shows "Refreshing..." while refresh is in progress', async () => {
    mockRefreshSession.mockReturnValue(new Promise(() => {})); // never resolves
    renderBanner(5);

    fireEvent.click(screen.getByTestId('session-refresh-button'));

    await waitFor(() => {
      expect(screen.getByText('Refreshing…')).toBeInTheDocument();
    });
  });
});
