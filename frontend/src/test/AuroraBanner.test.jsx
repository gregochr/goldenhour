import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import AuroraBanner, { bzStatus, formatDetectedAt } from '../components/AuroraBanner.jsx';

// Mock the useAuroraStatus hook
vi.mock('../hooks/useAuroraStatus.js', () => ({
  useAuroraStatus: vi.fn(),
}));

vi.mock('../hooks/useAuroraViewline.js', () => ({
  useAuroraViewline: vi.fn(),
}));

import { useAuroraStatus } from '../hooks/useAuroraStatus.js';
import { useAuroraViewline } from '../hooks/useAuroraViewline.js';

function renderBanner(status) {
  useAuroraStatus.mockReturnValue({ status, loading: false });
  useAuroraViewline.mockReturnValue({ viewline: null });
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

  it('returns null when level is QUIET', () => {
    const { container } = renderBanner({ level: 'QUIET', active: false, eligibleLocations: 0 });
    expect(container.firstChild).toBeNull();
  });

  it('returns null when level is MINOR', () => {
    const { container } = renderBanner({ level: 'MINOR', active: false, eligibleLocations: 0 });
    expect(container.firstChild).toBeNull();
  });

  // ---------------------------------------------------------------------------
  // Rendered cases
  // ---------------------------------------------------------------------------

  it('renders for MODERATE level', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 5,
    });
    expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
  });

  it('renders for STRONG level', () => {
    renderBanner({
      level: 'STRONG',
      hexColour: '#ff0000',
      description: 'Red alert: aurora likely',
      active: true,
      eligibleLocations: 12,
    });
    expect(screen.getByTestId('aurora-banner')).toBeInTheDocument();
  });

  it('displays the alert description text', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 3,
    });
    expect(screen.getByText(/Amber alert: possible aurora/i)).toBeInTheDocument();
  });

  it('shows "N dark sky locations clear" when triage has run', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 45,
      darkSkyLocationCount: 45,
      clearLocationCount: 12,
    });
    expect(screen.getByText(/12 dark sky locations clear/i)).toBeInTheDocument();
  });

  it('shows singular "location" when clearLocationCount is 1', () => {
    renderBanner({
      level: 'STRONG',
      hexColour: '#ff0000',
      description: 'Red alert: aurora likely',
      active: true,
      eligibleLocations: 10,
      darkSkyLocationCount: 10,
      clearLocationCount: 1,
    });
    expect(screen.getByText(/1 dark sky location clear/i)).toBeInTheDocument();
  });

  it('shows overcast message when all locations are cloudy', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 20,
      darkSkyLocationCount: 20,
      clearLocationCount: 0,
    });
    expect(screen.getByTestId('aurora-banner-overcast')).toBeInTheDocument();
    expect(screen.getByTestId('aurora-banner-overcast').textContent).toMatch(/All locations overcast/i);
  });

  it('shows total dark sky count when triage has not run yet', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 0,
      darkSkyLocationCount: 45,
      clearLocationCount: null,
    });
    expect(screen.getByText(/45 dark sky locations/i)).toBeInTheDocument();
    expect(screen.queryByText(/clear/i)).not.toBeInTheDocument();
  });

  it('omits location count when no Bortle-enriched locations exist', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 0,
      darkSkyLocationCount: 0,
      clearLocationCount: null,
    });
    expect(screen.queryByText(/dark sky/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/location/i)).not.toBeInTheDocument();
  });

  it('uses hexColour from API as background style', () => {
    renderBanner({
      level: 'STRONG',
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
      level: 'MODERATE',
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
      level: 'MODERATE',
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

  it('re-shows after dismissal when level escalates (MODERATE → STRONG)', () => {
    const { rerender } = renderBanner({
      level: 'MODERATE',
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
        level: 'STRONG',
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
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 5,
    });

    fireEvent.click(screen.getByTestId('aurora-banner-dismiss'));

    // Same AMBER level returned
    useAuroraStatus.mockReturnValue({
      status: {
        level: 'MODERATE',
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
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 5,
    });
    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('dismiss button has an accessible label', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 5,
    });
    expect(screen.getByLabelText(/dismiss aurora banner/i)).toBeInTheDocument();
  });

  // ---------------------------------------------------------------------------
  // Kp display — trigger type
  // ---------------------------------------------------------------------------

  it('shows "Kp N forecast tonight" with bold italic tonight when triggerType is forecast', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 3,
      forecastKp: 6.0,
      triggerType: 'forecast',
    });
    const subtitle = screen.getByText(/Kp 6 forecast/i).closest('p');
    expect(subtitle.textContent).toMatch(/Kp 6 forecast\s*tonight/i);
    const strong = subtitle.querySelector('strong.italic');
    expect(strong).not.toBeNull();
    expect(strong.textContent).toBe('tonight');
  });

  it('shows plain "Kp N" when triggerType is realtime', () => {
    renderBanner({
      level: 'STRONG',
      hexColour: '#ff0000',
      description: 'Red alert',
      active: true,
      eligibleLocations: 5,
      forecastKp: 7.3,
      triggerType: 'realtime',
    });
    expect(screen.getByText(/Kp 7\b/i)).toBeInTheDocument();
    expect(screen.queryByText(/forecast tonight/i)).not.toBeInTheDocument();
  });

  it('falls back to kp field when forecastKp is absent', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 2,
      kp: 5.0,
      forecastKp: null,
      triggerType: 'realtime',
    });
    expect(screen.getByText(/Kp 5\b/i)).toBeInTheDocument();
  });

  // ---------------------------------------------------------------------------
  // bzStatus helper — unit tests
  // ---------------------------------------------------------------------------

  describe('bzStatus()', () => {
    it('returns ✅ and "coupling active" for Bz < −1', () => {
      const result = bzStatus(-9.2);
      expect(result.emoji).toBe('✅');
      expect(result.label).toBe('Bz south (-9.2 nT)');
      expect(result.explanation).toBe('solar wind coupling active');
    });

    it('returns ✅ for moderate negative Bz (−3.1)', () => {
      const result = bzStatus(-3.1);
      expect(result.emoji).toBe('✅');
      expect(result.label).toBe('Bz south (-3.1 nT)');
      expect(result.explanation).toBe('solar wind coupling active');
    });

    it('returns ➖ and "neutral" for Bz near zero (−0.5)', () => {
      const result = bzStatus(-0.5);
      expect(result.emoji).toBe('➖');
      expect(result.label).toBe('Bz near zero (-0.5 nT)');
      expect(result.explanation).toBe('neutral');
    });

    it('returns ⚠️ and "not coupling" for Bz north (+3.2)', () => {
      const result = bzStatus(3.2);
      expect(result.emoji).toBe('⚠️');
      expect(result.label).toBe('Bz north (+3.2 nT)');
      expect(result.explanation).toBe('solar wind not coupling');
    });

    it('returns ⚠️ and "not coupling" for Bz firmly north (+7.8)', () => {
      const result = bzStatus(7.8);
      expect(result.emoji).toBe('⚠️');
      expect(result.label).toBe('Bz north (+7.8 nT)');
      expect(result.explanation).toBe('solar wind not coupling');
    });

    it('contains no interpretive severity language', () => {
      // Bz text should be factual only — severity comes from the Kp headline
      for (const bz of [-9.2, -3.1, -0.5, 3.2, 7.8]) {
        const result = bzStatus(bz);
        expect(result.explanation).not.toMatch(/faint|strong|should be visible|aurora possible|aurora unlikely|aurora expected|blocked|borderline/i);
      }
    });
  });

  // ---------------------------------------------------------------------------
  // formatDetectedAt helper — unit tests
  // ---------------------------------------------------------------------------

  describe('formatDetectedAt()', () => {
    it('returns null for null input', () => {
      expect(formatDetectedAt(null)).toBeNull();
    });

    it('returns null for undefined input', () => {
      expect(formatDetectedAt(undefined)).toBeNull();
    });

    it('returns null for invalid date string', () => {
      expect(formatDetectedAt('not-a-date')).toBeNull();
    });

    it('returns time-only for a detection today', () => {
      const now = new Date();
      now.setMinutes(now.getMinutes() - 30);
      const result = formatDetectedAt(now.toISOString());
      expect(result).toMatch(/^\d{2}:\d{2}$/);
    });

    it('returns "yesterday HH:MM" for a detection yesterday', () => {
      const yesterday = new Date();
      yesterday.setDate(yesterday.getDate() - 1);
      yesterday.setHours(21, 34, 0, 0);
      const result = formatDetectedAt(yesterday.toISOString());
      expect(result).toMatch(/^yesterday \d{2}:\d{2}$/);
    });

    it('returns "D Mon HH:MM" for a detection two days ago', () => {
      const twoDaysAgo = new Date();
      twoDaysAgo.setDate(twoDaysAgo.getDate() - 2);
      twoDaysAgo.setHours(14, 22, 0, 0);
      const result = formatDetectedAt(twoDaysAgo.toISOString());
      // Should be like "2 Apr 14:22"
      expect(result).toMatch(/^\d{1,2} [A-Z][a-z]{2} \d{2}:\d{2}$/);
    });
  });

  // ---------------------------------------------------------------------------
  // Detection timestamp in banner headline
  // ---------------------------------------------------------------------------

  it('shows "Detected HH:MM" in headline when detectedAt is present', () => {
    const thirtyMinsAgo = new Date();
    thirtyMinsAgo.setMinutes(thirtyMinsAgo.getMinutes() - 30);
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 3,
      detectedAt: thirtyMinsAgo.toISOString(),
    });
    const el = screen.getByTestId('aurora-banner-detected');
    expect(el).toBeInTheDocument();
    expect(el.textContent).toMatch(/^Detected \d{2}:\d{2}$/);
  });

  it('shows "Detected yesterday HH:MM" when alert persisted from yesterday', () => {
    const yesterday = new Date();
    yesterday.setDate(yesterday.getDate() - 1);
    yesterday.setHours(21, 34, 0, 0);
    renderBanner({
      level: 'STRONG',
      hexColour: '#ff0000',
      description: 'Red alert: aurora likely',
      active: true,
      eligibleLocations: 5,
      detectedAt: yesterday.toISOString(),
    });
    const el = screen.getByTestId('aurora-banner-detected');
    expect(el.textContent).toMatch(/^Detected yesterday \d{2}:\d{2}$/);
  });

  it('does not show detection timestamp when detectedAt is null', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 3,
      detectedAt: null,
    });
    expect(screen.queryByTestId('aurora-banner-detected')).not.toBeInTheDocument();
  });

  it('does not show detection timestamp in simulated mode', () => {
    const thirtyMinsAgo = new Date();
    thirtyMinsAgo.setMinutes(thirtyMinsAgo.getMinutes() - 30);
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert: possible aurora',
      active: true,
      eligibleLocations: 0,
      simulated: true,
      detectedAt: thirtyMinsAgo.toISOString(),
    });
    expect(screen.queryByTestId('aurora-banner-detected')).not.toBeInTheDocument();
  });

  // ---------------------------------------------------------------------------
  // Bz display in banner
  // ---------------------------------------------------------------------------

  it('shows Bz line when bzNanoTesla is present', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 3,
      bzNanoTesla: -9.2,
    });
    expect(screen.getByTestId('aurora-banner-bz')).toBeInTheDocument();
    expect(screen.getByTestId('aurora-banner-bz').textContent).toMatch(/Bz south/);
  });

  it('does not show Bz line when bzNanoTesla is null', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 3,
      bzNanoTesla: null,
    });
    expect(screen.queryByTestId('aurora-banner-bz')).not.toBeInTheDocument();
  });

  // ---------------------------------------------------------------------------
  // Pulse animation
  // ---------------------------------------------------------------------------

  it('applies pulse animation when bzNanoTesla < −1 and triggerType is realtime', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 3,
      bzNanoTesla: -6.0,
      triggerType: 'realtime',
    });
    const banner = screen.getByTestId('aurora-banner');
    expect(banner.style.animation).toMatch(/aurora-pulse/);
  });

  it('does not apply pulse animation when bzNanoTesla is north (+3)', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 3,
      bzNanoTesla: 3.0,
    });
    const banner = screen.getByTestId('aurora-banner');
    expect(banner.style.animation).toBeFalsy();
  });

  it('does not apply pulse animation when bzNanoTesla is borderline (−0.5)', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 3,
      bzNanoTesla: -0.5,
    });
    const banner = screen.getByTestId('aurora-banner');
    expect(banner.style.animation).toBeFalsy();
  });

  it('does not apply pulse animation when bzNanoTesla is null', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 3,
      bzNanoTesla: null,
    });
    const banner = screen.getByTestId('aurora-banner');
    expect(banner.style.animation).toBeFalsy();
  });

  it('does not apply pulse animation when triggerType is forecast even with favourable Bz', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 3,
      bzNanoTesla: -6.0,
      triggerType: 'forecast',
      forecastKp: 6.0,
    });
    const banner = screen.getByTestId('aurora-banner');
    expect(banner.style.animation).toBeFalsy();
  });

  // ---------------------------------------------------------------------------
  // Forecast vs active headline
  // ---------------------------------------------------------------------------

  it('shows "Aurora Forecast" headline when triggerType is forecast', () => {
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 3,
      forecastKp: 6.0,
      triggerType: 'forecast',
    });
    expect(screen.getByText(/Aurora Forecast/)).toBeInTheDocument();
    expect(screen.queryByText(/Aurora Active Now/)).not.toBeInTheDocument();
  });

  it('shows "Aurora Active Now" headline when triggerType is realtime', () => {
    renderBanner({
      level: 'STRONG',
      hexColour: '#ff0000',
      description: 'Red alert',
      active: true,
      eligibleLocations: 5,
      forecastKp: 7.0,
      triggerType: 'realtime',
    });
    expect(screen.getByText(/Aurora Active Now/)).toBeInTheDocument();
    expect(screen.queryByText(/Aurora Forecast/)).not.toBeInTheDocument();
  });

  it('does not show detected timestamp when triggerType is forecast', () => {
    const thirtyMinsAgo = new Date();
    thirtyMinsAgo.setMinutes(thirtyMinsAgo.getMinutes() - 30);
    renderBanner({
      level: 'MODERATE',
      hexColour: '#ff9900',
      description: 'Amber alert',
      active: true,
      eligibleLocations: 3,
      forecastKp: 6.0,
      triggerType: 'forecast',
      detectedAt: thirtyMinsAgo.toISOString(),
    });
    expect(screen.queryByTestId('aurora-banner-detected')).not.toBeInTheDocument();
  });

  // ---------------------------------------------------------------------------
  // Viewline summary
  // ---------------------------------------------------------------------------

  it('shows viewline summary when available', () => {
    useAuroraViewline.mockReturnValue({
      viewline: { active: true, summary: 'Visible as far south as northern England' },
    });
    useAuroraStatus.mockReturnValue({
      status: {
        level: 'MODERATE',
        hexColour: '#ff9900',
        description: 'Amber alert',
        active: true,
        eligibleLocations: 3,
      },
      loading: false,
    });
    render(<AuroraBanner />);
    const el = screen.getByTestId('aurora-banner-viewline');
    expect(el).toBeInTheDocument();
    expect(el.textContent).toContain('Visible as far south as northern England');
  });

  it('hides viewline when not active', () => {
    useAuroraViewline.mockReturnValue({
      viewline: { active: false, summary: 'No aurora' },
    });
    useAuroraStatus.mockReturnValue({
      status: {
        level: 'MODERATE',
        hexColour: '#ff9900',
        description: 'Amber alert',
        active: true,
        eligibleLocations: 3,
      },
      loading: false,
    });
    render(<AuroraBanner />);
    expect(screen.queryByTestId('aurora-banner-viewline')).not.toBeInTheDocument();
  });
});
