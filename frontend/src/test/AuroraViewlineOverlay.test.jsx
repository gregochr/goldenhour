import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import AuroraViewlineOverlay from '../components/AuroraViewlineOverlay';

vi.mock('react-leaflet', () => ({
  Polygon: ({ pathOptions }) => (
    <div data-testid="viewline-polygon" data-fill-color={pathOptions?.fillColor} data-fill-opacity={pathOptions?.fillOpacity} data-interactive={String(pathOptions?.interactive)} />
  ),
  Polyline: ({ pathOptions, children }) => (
    <div data-testid="viewline-polyline" data-color={pathOptions?.color} data-dash-array={pathOptions?.dashArray} data-interactive={String(pathOptions?.interactive)}>
      {children}
    </div>
  ),
  Tooltip: ({ children }) => <div data-testid="viewline-tooltip">{children}</div>,
}));

const activeViewline = {
  points: [
    { longitude: -5, latitude: 54 },
    { longitude: -3, latitude: 55 },
    { longitude: 0, latitude: 54 },
    { longitude: 2, latitude: 56 },
  ],
  summary: 'Visible as far south as northern England',
  southernmostLatitude: 54,
  forecastTime: '2026-04-01T22:00:00Z',
  active: true,
  isForecast: false,
};

const forecastViewline = {
  points: [
    { longitude: -12, latitude: 54 },
    { longitude: 4, latitude: 54 },
  ],
  summary: 'Visible as far south as northern England',
  southernmostLatitude: 54,
  forecastTime: '2026-04-01T22:00:00Z',
  active: true,
  isForecast: true,
};

describe('AuroraViewlineOverlay', () => {
  it('renders_polygon_when_active', () => {
    render(<AuroraViewlineOverlay viewline={activeViewline} />);
    expect(screen.getByTestId('viewline-polygon')).toBeInTheDocument();
  });

  it('hidden_when_inactive', () => {
    const inactive = { ...activeViewline, active: false };
    const { container } = render(<AuroraViewlineOverlay viewline={inactive} />);
    expect(container.innerHTML).toBe('');
  });

  it('hidden_when_null', () => {
    const { container } = render(<AuroraViewlineOverlay viewline={null} />);
    expect(container.innerHTML).toBe('');
  });

  it('hidden_when_no_points', () => {
    const empty = { ...activeViewline, points: [] };
    const { container } = render(<AuroraViewlineOverlay viewline={empty} />);
    expect(container.innerHTML).toBe('');
  });

  it('non_interactive', () => {
    render(<AuroraViewlineOverlay viewline={activeViewline} />);
    expect(screen.getByTestId('viewline-polygon')).toHaveAttribute('data-interactive', 'false');
    expect(screen.getByTestId('viewline-polyline')).toHaveAttribute('data-interactive', 'false');
  });

  // ---------------------------------------------------------------------------
  // Live viewline (isForecast: false)
  // ---------------------------------------------------------------------------

  it('live line uses aurora green colour', () => {
    render(<AuroraViewlineOverlay viewline={activeViewline} />);
    expect(screen.getByTestId('viewline-polygon')).toHaveAttribute('data-fill-color', '#33ff33');
    expect(screen.getByTestId('viewline-polyline')).toHaveAttribute('data-color', '#33ff33');
  });

  it('live line has no dash array (solid)', () => {
    render(<AuroraViewlineOverlay viewline={activeViewline} />);
    const polyline = screen.getByTestId('viewline-polyline');
    expect(polyline.getAttribute('data-dash-array')).toBeNull();
  });

  it('live line fill opacity is 0.08', () => {
    render(<AuroraViewlineOverlay viewline={activeViewline} />);
    expect(screen.getByTestId('viewline-polygon')).toHaveAttribute('data-fill-opacity', '0.08');
  });

  it('live tooltip shows "Live aurora extent"', () => {
    render(<AuroraViewlineOverlay viewline={activeViewline} />);
    const tooltip = screen.getByTestId('viewline-tooltip');
    expect(tooltip).toHaveTextContent('Live aurora extent');
  });

  // ---------------------------------------------------------------------------
  // Forecast viewline (isForecast: true)
  // ---------------------------------------------------------------------------

  it('forecast line uses amber colour', () => {
    render(<AuroraViewlineOverlay viewline={forecastViewline} />);
    expect(screen.getByTestId('viewline-polygon')).toHaveAttribute('data-fill-color', '#ff9900');
    expect(screen.getByTestId('viewline-polyline')).toHaveAttribute('data-color', '#ff9900');
  });

  it('forecast line is dashed (10, 6)', () => {
    render(<AuroraViewlineOverlay viewline={forecastViewline} />);
    const polyline = screen.getByTestId('viewline-polyline');
    expect(polyline).toHaveAttribute('data-dash-array', '10, 6');
  });

  it('forecast fill opacity is 0.04', () => {
    render(<AuroraViewlineOverlay viewline={forecastViewline} />);
    expect(screen.getByTestId('viewline-polygon')).toHaveAttribute('data-fill-opacity', '0.04');
  });

  it('forecast tooltip shows "Forecast extent" with Kp when provided', () => {
    render(<AuroraViewlineOverlay viewline={forecastViewline} forecastKp={6} />);
    const tooltip = screen.getByTestId('viewline-tooltip');
    expect(tooltip).toHaveTextContent('Forecast extent (Kp 6)');
  });

  it('forecast tooltip shows "Forecast extent" without Kp when not provided', () => {
    render(<AuroraViewlineOverlay viewline={forecastViewline} />);
    const tooltip = screen.getByTestId('viewline-tooltip');
    expect(tooltip).toHaveTextContent('Forecast extent');
    expect(tooltip).not.toHaveTextContent('Kp');
  });

  // ---------------------------------------------------------------------------
  // Backwards compatibility (no isForecast field = live)
  // ---------------------------------------------------------------------------

  it('treats missing isForecast as live (green, solid)', () => {
    const legacyViewline = { ...activeViewline };
    delete legacyViewline.isForecast;
    render(<AuroraViewlineOverlay viewline={legacyViewline} />);
    expect(screen.getByTestId('viewline-polyline')).toHaveAttribute('data-color', '#33ff33');
    expect(screen.getByTestId('viewline-polyline').getAttribute('data-dash-array')).toBeNull();
  });
});
