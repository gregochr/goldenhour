import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import AuroraBanner from '../components/AuroraBanner.jsx';

// Mock the useAuroraStatus hook
vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: vi.fn(),
}));

import { useAuroraStatus } from '../hooks/useAuroraStatus.js';

function renderBanner(status) {
  useAuroraStatus.mockReturnValue({ status, loading: false });
  return render(<AuroraBanner />);
}

describe('AuroraBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Reset hash
    window.location.hash = '';
  });

  // ---------------------------------------------------------------------------
  // Not rendered cases
  // ---------------------------------------------------------------------------

  it('returns null when status is null (free-tier / 403)', () => {
    const { container } = renderBanner(null);
    expect(container.firstChild).toBeNull();
  });

  it('returns null when level is GREEN', () => {
    const { container } = renderBanner({ level: 'GREEN', active: false, eligibleLocations: 0 });
    expect(container.firstChild).toBeNull();
  });

  it('returns null when level is YELLOW', () => {
    const { container } = renderBanner({ level: 'YELLOW', active: false, eligibleLocations: 0 });
    expect(container.firstChild).toBeNull();
  });

  // ---------------------------------------------------------------------------
  // Rendered cases
  // ---------------------------------------------------------------------------

  it('renders for AMBER level', () => {
    renderBanner({
      level: 'AMBER',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 5,
    });
    expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
  });

  it('renders for RED level', () => {
    renderBanner({
      level: 'RED',
      hexColour: '#ff0000',
      description: 'Red alert: aurora likely',
      active: true,
      eligibleLocations: 12,
    });
    expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
  });

  it('displays the alert description text', () => {
    renderBanner({
      level: 'AMBER',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 3,
    });
    expect(screen.getByText(/Amber alert: possible aurora/i)).toBeInTheDocument();
  });

  it('shows location count when eligible locations > 0', () => {
    renderBanner({
      level: 'AMBER',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 7,
    });
    expect(screen.getByText(/7 locations available/i)).toBeInTheDocument();
  });

  it('shows singular "location" when eligibleLocations is 1', () => {
    renderBanner({
      level: 'RED',
      hexColour: '#ff0000',
      description: 'Red alert: aurora likely',
      active: true,
      eligibleLocations: 1,
    });
    expect(screen.getByText(/1 location available/i)).toBeInTheDocument();
  });

  it('does not show location count when eligibleLocations is 0', () => {
    renderBanner({
      level: 'AMBER',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 0,
    });
    expect(screen.queryByText(/available/i)).not.toBeInTheDocument();
  });

  it('uses hexColour from API as background style', () => {
    renderBanner({
      level: 'RED',
      hexColour: '#ff0000',
      description: 'Red alert',
      active: true,
      eligibleLocations: 5,
    });
    const banner = screen.getByTestId('aurora-banner');
    expect(banner.style.backgroundColor).toBe('rgb(255, 0, 0)');
  });

  // ---------------------------------------------------------------------------
  // Dismiss behaviour
  // ---------------------------------------------------------------------------

  it('dismiss button hides the banner at the current level', () => {
    renderBanner({
      level: 'AMBER',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 5,
    });

    const dismissBtn = screen.getByTestId('aurora-banner-dismiss');
    fireEvent.click(dismissBtn);

    expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
  });

  it('dismiss button click does not propagate to banner (no hash navigation)', () => {
    const originalHash = window.location.hash;
    renderBanner({
      level: 'AMBER',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 5,
    });

    const dismissBtn = screen.getByTestId('aurora-banner-dismiss');
    fireEvent.click(dismissBtn);

    // Hash should not have been changed by the dismiss action
    expect(window.location.hash).toBe(originalHash);
  });

  it('re-shows after dismissal when level escalates (AMBER → RED)', () => {
    const { rerender } = renderBanner({
      level: 'AMBER',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 5,
    });

    // Dismiss at AMBER
    fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));
    expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();

    // Escalate to RED
    useAuroraStatus.mockReturnValue({
      status: {
        level: 'RED',
        hexColour: '#ff0000',
        description: 'Red alert: aurora likely',
        active: true,
        eligibleLocations: 10,
      },
      loading: false,
    });
    rerender(<AuroraBanner />);

    // RED ≠ AMBER (the dismissed level), so banner reappears
    expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
  });

  it('stays hidden after dismiss when the level remains the same', () => {
    const { rerender } = renderBanner({
      level: 'AMBER',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 5,
    });

    fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));

    // Same AMBER level returned
    useAuroraStatus.mockReturnValue({
      status: {
        level: 'AMBER',
        hexColour: '#ff9900',
        description: 'Amber alert',
        active: true,
        eligibleLocations: 5,
      },
      loading: false,
    });
    rerender(<AuroraBanner />);

    expect(screen.queryByTestId('aurora-banner')).not.toBeInTheDocument();
  });

  // ---------------------------------------------------------------------------
  // Accessibility
  // ---------------------------------------------------------------------------

  it('has role="alert" for screen reader support', () => {
    renderBanner({
      level: 'AMBER',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 5,
    });
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('dismiss button has an accessible label', () => {
    renderBanner({
      level: 'AMBER',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 5,
    });
    expect(screen.getByLabelText(/dismiss aurora banner/i)).toBeInTheDocument();
  });
});
