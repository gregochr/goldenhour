import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import NlcSightingBanner, { formatReportedAt } from '../components/NlcSightingBanner.jsx';

// Mock the useNlcSighting hook
vi.mock('../hooks/useNlcSighting.js', () => ({
  useNlcSighting: vi.fn(),
}));

import { useNlcSighting } from '../hooks/useNlcSighting.js';

function renderBanner(sighting) {
  useNlcSighting.mockReturnValue({ sighting, loading: false });
  return render(<NlcSightingBanner />);
}

/** Minimal valid "active + clear" sighting; spread + override per test. */
function activeSighting(overrides = {}) {
  const twoHoursAgo = new Date();
  twoHoursAgo.setHours(twoHoursAgo.getHours() - 2);
  return {
    active: true,
    clearTonight: true,
    reportedAt: twoHoursAgo.toISOString(),
    observerLocation: 'Dumfries',
    source: 'NLCNET',
    lookDirection: 'N–NW',
    darkSkyLocationCount: 64,
    hexColour: '#8E86D6',
    description: 'Noctilucent cloud reported over southern Scotland',
    ...overrides,
  };
}

describe('NlcSightingBanner', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
  });

  // ---------------------------------------------------------------------------
  // Not rendered cases
  // ---------------------------------------------------------------------------

  it('returns null when sighting is null (free-tier / 403 / no data)', () => {
    const { container } = renderBanner(null);
    expect(container.firstChild).toBeNull();
  });

  it('returns null when there is no active sighting (active: false)', () => {
    const { container } = renderBanner(activeSighting({ active: false }));
    expect(container.firstChild).toBeNull();
  });

  it('returns null when skies are not clear (clearTonight: false)', () => {
    const { container } = renderBanner(activeSighting({ clearTonight: false }));
    expect(container.firstChild).toBeNull();
  });

  // ---------------------------------------------------------------------------
  // Rendered cases
  // ---------------------------------------------------------------------------

  it('renders when a sighting is active and skies are clear', () => {
    renderBanner(activeSighting());
    expect(screen.getByTestId('nlc-sighting-banner')).toBeInTheDocument();
  });

  it('renders when clearTonight is omitted (backend already gated)', () => {
    const s = activeSighting();
    delete s.clearTonight;
    renderBanner(s);
    expect(screen.getByTestId('nlc-sighting-banner')).toBeInTheDocument();
  });

  it('displays the sighting description', () => {
    renderBanner(activeSighting());
    expect(screen.getByText(/reported over southern Scotland/i)).toBeInTheDocument();
  });

  it('shows the relative "Xh ago" report label', () => {
    renderBanner(activeSighting());
    expect(screen.getByText(/Sighting · 2h ago/i)).toBeInTheDocument();
  });

  it('shows the observer location and source', () => {
    renderBanner(activeSighting());
    expect(screen.getByText(/Dumfries via NLCNET/i)).toBeInTheDocument();
  });

  it('shows the look direction', () => {
    renderBanner(activeSighting());
    expect(screen.getByText(/Look N–NW, low/i)).toBeInTheDocument();
  });

  it('shows the dark-sky clear-count line when present', () => {
    renderBanner(activeSighting({ darkSkyLocationCount: 64 }));
    const el = screen.getByTestId('nlc-sighting-darksky');
    expect(el).toBeInTheDocument();
    expect(el.textContent).toMatch(/64 dark-sky sites clear/i);
  });

  it('uses singular "site" when the dark-sky count is 1', () => {
    renderBanner(activeSighting({ darkSkyLocationCount: 1 }));
    expect(screen.getByTestId('nlc-sighting-darksky').textContent).toMatch(/1 dark-sky site clear/i);
  });

  it('omits the dark-sky line when the count is zero', () => {
    renderBanner(activeSighting({ darkSkyLocationCount: 0 }));
    expect(screen.queryByTestId('nlc-sighting-darksky')).not.toBeInTheDocument();
  });

  it('drives the accent colour from the API hexColour', () => {
    renderBanner(activeSighting({ hexColour: '#a78bfa' }));
    const banner = screen.getByTestId('nlc-sighting-banner');
    expect(banner.style.getPropertyValue('--nlc-accent')).toBe('#a78bfa');
  });

  it('falls back to the default violet accent when hexColour is absent', () => {
    const s = activeSighting();
    delete s.hexColour;
    renderBanner(s);
    expect(screen.getByTestId('nlc-sighting-banner').style.getPropertyValue('--nlc-accent')).toBe('#8E86D6');
  });

  // ---------------------------------------------------------------------------
  // Dismiss behaviour
  // ---------------------------------------------------------------------------

  it('dismiss button hides the banner for the current report', () => {
    renderBanner(activeSighting());
    fireEvent.click(screen.getByTestId('nlc-sighting-dismiss'));
    expect(screen.queryByTestId('nlc-sighting-banner')).not.toBeInTheDocument();
  });

  it('dismiss click does not propagate to the banner (no hash navigation)', () => {
    const originalHash = window.location.hash;
    renderBanner(activeSighting());
    fireEvent.click(screen.getByTestId('nlc-sighting-dismiss'));
    expect(window.location.hash).toBe(originalHash);
  });

  it('stays hidden after dismiss when the same report is returned', () => {
    const s = activeSighting();
    const { rerender } = renderBanner(s);
    fireEvent.click(screen.getByTestId('nlc-sighting-dismiss'));

    useNlcSighting.mockReturnValue({ sighting: s, loading: false });
    rerender(<NlcSightingBanner />);
    expect(screen.queryByTestId('nlc-sighting-banner')).not.toBeInTheDocument();
  });

  it('re-shows after dismiss when a NEWER report arrives', () => {
    const { rerender } = renderBanner(activeSighting());
    fireEvent.click(screen.getByTestId('nlc-sighting-dismiss'));
    expect(screen.queryByTestId('nlc-sighting-banner')).not.toBeInTheDocument();

    const newer = new Date().toISOString();
    useNlcSighting.mockReturnValue({
      sighting: activeSighting({ reportedAt: newer }),
      loading: false,
    });
    rerender(<NlcSightingBanner />);
    expect(screen.getByTestId('nlc-sighting-banner')).toBeInTheDocument();
  });

  // ---------------------------------------------------------------------------
  // Navigation
  // ---------------------------------------------------------------------------

  it('navigates to the map when the banner is clicked', () => {
    renderBanner(activeSighting());
    fireEvent.click(screen.getByTestId('nlc-sighting-banner'));
    expect(window.location.hash).toBe('#map');
  });

  // ---------------------------------------------------------------------------
  // Accessibility
  // ---------------------------------------------------------------------------

  it('has role="alert" for screen reader support', () => {
    renderBanner(activeSighting());
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('dismiss button has an accessible label', () => {
    renderBanner(activeSighting());
    expect(screen.getByLabelText(/dismiss noctilucent sighting banner/i)).toBeInTheDocument();
  });

  // ---------------------------------------------------------------------------
  // formatReportedAt helper — unit tests
  // ---------------------------------------------------------------------------

  describe('formatReportedAt()', () => {
    it('returns null for null input', () => {
      expect(formatReportedAt(null)).toBeNull();
    });

    it('returns null for undefined input', () => {
      expect(formatReportedAt(undefined)).toBeNull();
    });

    it('returns null for an invalid date string', () => {
      expect(formatReportedAt('not-a-date')).toBeNull();
    });

    it('returns "just now" for a report under a minute old', () => {
      const now = new Date();
      now.setSeconds(now.getSeconds() - 20);
      expect(formatReportedAt(now.toISOString())).toBe('just now');
    });

    it('returns "Nm ago" for a report under an hour old', () => {
      const now = new Date();
      now.setMinutes(now.getMinutes() - 45);
      expect(formatReportedAt(now.toISOString())).toMatch(/^4[0-9]m ago$/);
    });

    it('returns "Nh ago" for a report earlier today', () => {
      const now = new Date();
      now.setHours(now.getHours() - 3);
      // Guard against crossing midnight during the test run
      if (now.getDate() === new Date().getDate()) {
        expect(formatReportedAt(now.toISOString())).toMatch(/^\dh ago$/);
      }
    });

    it('returns "yesterday HH:MM" for a report from yesterday', () => {
      const yesterday = new Date();
      yesterday.setDate(yesterday.getDate() - 1);
      yesterday.setHours(23, 10, 0, 0);
      expect(formatReportedAt(yesterday.toISOString())).toMatch(/^yesterday \d{2}:\d{2}$/);
    });

    it('returns "D Mon HH:MM" for a report two days ago', () => {
      const twoDaysAgo = new Date();
      twoDaysAgo.setDate(twoDaysAgo.getDate() - 2);
      twoDaysAgo.setHours(23, 10, 0, 0);
      expect(formatReportedAt(twoDaysAgo.toISOString())).toMatch(/^\d{1,2} [A-Z][a-z]{2} \d{2}:\d{2}$/);
    });
  });
});
