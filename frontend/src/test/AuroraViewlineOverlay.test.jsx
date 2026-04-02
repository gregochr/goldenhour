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
};

describe('AuroraViewlineOverlay', () => {
  it('renders_polygon_when_active', () => {
    render(<AuroraViewlineOverlay viewline={activeViewline} />);
    expect(screen.getByTestId('viewline-polygon')).toBeInTheDocument();
  });

  it('renders_polyline_with_dashed_style', () => {
    render(<AuroraViewlineOverlay viewline={activeViewline} />);
    const polyline = screen.getByTestId('viewline-polyline');
    expect(polyline).toHaveAttribute('data-dash-array', '8, 4');
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

  it('colour_green_when_south_of_55N', () => {
    const viewline = { ...activeViewline, southernmostLatitude: 54 };
    render(<AuroraViewlineOverlay viewline={viewline} />);
    expect(screen.getByTestId('viewline-polygon')).toHaveAttribute('data-fill-color', '#33ff33');
    expect(screen.getByTestId('viewline-polyline')).toHaveAttribute('data-color', '#33ff33');
  });

  it('colour_amber_when_55_to_58N', () => {
    const viewline = { ...activeViewline, southernmostLatitude: 56 };
    render(<AuroraViewlineOverlay viewline={viewline} />);
    expect(screen.getByTestId('viewline-polygon')).toHaveAttribute('data-fill-color', '#ff9900');
    expect(screen.getByTestId('viewline-polyline')).toHaveAttribute('data-color', '#ff9900');
  });

  it('colour_grey_when_north_of_58N', () => {
    const viewline = { ...activeViewline, southernmostLatitude: 60 };
    render(<AuroraViewlineOverlay viewline={viewline} />);
    expect(screen.getByTestId('viewline-polygon')).toHaveAttribute('data-fill-color', '#888888');
    expect(screen.getByTestId('viewline-polyline')).toHaveAttribute('data-color', '#888888');
  });

  it('non_interactive', () => {
    render(<AuroraViewlineOverlay viewline={activeViewline} />);
    expect(screen.getByTestId('viewline-polygon')).toHaveAttribute('data-interactive', 'false');
    expect(screen.getByTestId('viewline-polyline')).toHaveAttribute('data-interactive', 'false');
  });

  it('shows_summary_in_tooltip', () => {
    render(<AuroraViewlineOverlay viewline={activeViewline} />);
    const tooltip = screen.getByTestId('viewline-tooltip');
    expect(tooltip).toHaveTextContent('Visible as far south as northern England');
  });
});
