import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import ForecastTypeSelector from '../components/ForecastTypeSelector.jsx';

function renderSelector(overrides = {}) {
  const props = {
    eventType: 'SUNSET',
    onChange: vi.fn(),
    showAurora: true,
    auroraAvailable: true,
    ...overrides,
  };
  return { ...render(<ForecastTypeSelector {...props} />), onChange: props.onChange };
}

describe('ForecastTypeSelector', () => {
  describe('button visibility', () => {
    it('always renders Sunrise and Sunset buttons', () => {
      renderSelector({ showAurora: false });
      expect(screen.getByTestId('forecast-type-sunrise')).toBeInTheDocument();
      expect(screen.getByTestId('forecast-type-sunset')).toBeInTheDocument();
    });

    it('renders Aurora button for ADMIN/PRO when showAurora=true', () => {
      renderSelector({ showAurora: true });
      expect(screen.getByTestId('forecast-type-aurora')).toBeInTheDocument();
    });

    it('shows Aurora button for LITE_USER but disabled with Pro pill', () => {
      renderSelector({ showAurora: false });
      const aurora = screen.getByTestId('forecast-type-aurora');
      expect(aurora).toBeInTheDocument();
      expect(aurora).toBeDisabled();
      expect(screen.getByTestId('pro-pill')).toBeInTheDocument();
    });
  });

  describe('Aurora button enabled/disabled state', () => {
    it('Aurora button is enabled when auroraAvailable=true', () => {
      renderSelector({ showAurora: true, auroraAvailable: true });
      expect(screen.getByTestId('forecast-type-aurora')).not.toBeDisabled();
    });

    it('Aurora button is disabled when auroraAvailable=false (no stored results, no live alert)', () => {
      renderSelector({ showAurora: true, auroraAvailable: false });
      expect(screen.getByTestId('forecast-type-aurora')).toBeDisabled();
    });

    it('disabled Aurora button shows tooltip explaining why', () => {
      renderSelector({ showAurora: true, auroraAvailable: false });
      expect(screen.getByTestId('forecast-type-aurora')).toHaveAttribute(
        'title',
        'No aurora forecast results available',
      );
    });

    it('locked Aurora button shows Pro upgrade tooltip', () => {
      renderSelector({ showAurora: false, auroraAvailable: false });
      expect(screen.getByTestId('forecast-type-aurora')).toHaveAttribute(
        'title',
        'Upgrade to Pro for aurora forecasts',
      );
    });

    it('enabled Aurora button has no title', () => {
      renderSelector({ showAurora: true, auroraAvailable: true });
      expect(screen.getByTestId('forecast-type-aurora')).not.toHaveAttribute('title');
    });

    it('Sunrise and Sunset buttons are never disabled', () => {
      renderSelector({ showAurora: true, auroraAvailable: false });
      expect(screen.getByTestId('forecast-type-sunrise')).not.toBeDisabled();
      expect(screen.getByTestId('forecast-type-sunset')).not.toBeDisabled();
    });

    it('does not show Pro pill when showAurora=true', () => {
      renderSelector({ showAurora: true, auroraAvailable: true });
      expect(screen.queryByTestId('pro-pill')).not.toBeInTheDocument();
    });
  });

  describe('active state styling', () => {
    it('marks the active eventType button as selected', () => {
      renderSelector({ eventType: 'SUNRISE' });
      const sunrise = screen.getByTestId('forecast-type-sunrise');
      expect(sunrise.className).toMatch(/plex-gold/);
    });

    it('inactive buttons do not have active styling', () => {
      renderSelector({ eventType: 'SUNRISE' });
      const sunset = screen.getByTestId('forecast-type-sunset');
      expect(sunset.className).not.toMatch(/plex-gold\/20/);
    });
  });

  describe('interactions', () => {
    it('calls onChange with clicked type', () => {
      const { onChange } = renderSelector({ eventType: 'SUNSET' });
      fireEvent.click(screen.getByTestId('forecast-type-sunrise'));
      expect(onChange).toHaveBeenCalledWith('SUNRISE');
    });

    it('does not call onChange when disabled Aurora button is clicked', () => {
      const { onChange } = renderSelector({ showAurora: true, auroraAvailable: false });
      fireEvent.click(screen.getByTestId('forecast-type-aurora'));
      expect(onChange).not.toHaveBeenCalled();
    });

    it('does not call onChange when locked Aurora button is clicked', () => {
      const { onChange } = renderSelector({ showAurora: false, auroraAvailable: false });
      fireEvent.click(screen.getByTestId('forecast-type-aurora'));
      expect(onChange).not.toHaveBeenCalled();
    });

    it('calls onChange with AURORA when enabled Aurora button is clicked', () => {
      const { onChange } = renderSelector({ showAurora: true, auroraAvailable: true });
      fireEvent.click(screen.getByTestId('forecast-type-aurora'));
      expect(onChange).toHaveBeenCalledWith('AURORA');
    });
  });
});
