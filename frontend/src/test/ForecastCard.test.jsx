import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ForecastCard from '../components/ForecastCard.jsx';

// Mock CloudCoverBars — Recharts uses ResizeObserver which is stubbed but
// renders nothing useful in JSDOM.
vi.mock('../components/CloudCoverBars.jsx', () => ({
  default: () => <div data-testid="cloud-cover-bars" />,
}));

const baseForecast = {
  rating: 3,
  summary: 'Moderate conditions with some mid cloud.',
  lowCloud: 10,
  midCloud: 50,
  highCloud: 30,
  windSpeed: '5.5',
  windDirection: 225,
  visibility: 15000,
  precipitation: 0,
};

// Use today's date so the "Record outcome" button is visible
const today = new Date().toISOString().slice(0, 10);

const baseProps = {
  type: 'SUNSET',
  date: today,
  locationLat: 54.7753,
  locationLon: -1.5849,
  locationName: 'Durham UK',
  onOutcomeSaved: vi.fn(),
  onRerun: vi.fn(),
};

describe('ForecastCard', () => {
  it('renders the forecast rating', () => {
    render(<ForecastCard {...baseProps} forecast={baseForecast} />);
    expect(screen.getByTestId('sunset-rating')).toBeInTheDocument();
  });

  it('renders the summary text', () => {
    render(<ForecastCard {...baseProps} forecast={baseForecast} />);
    expect(screen.getByText('Moderate conditions with some mid cloud.')).toBeInTheDocument();
  });

  it('renders cloud cover bars', () => {
    render(<ForecastCard {...baseProps} forecast={baseForecast} />);
    expect(screen.getByTestId('cloud-cover-bars')).toBeInTheDocument();
  });

  it('renders wind indicator', () => {
    render(<ForecastCard {...baseProps} forecast={baseForecast} />);
    expect(screen.getByTestId('wind-indicator')).toBeInTheDocument();
  });

  it('renders visibility indicator', () => {
    render(<ForecastCard {...baseProps} forecast={baseForecast} />);
    expect(screen.getByTestId('visibility-indicator')).toBeInTheDocument();
  });

  it('shows "Record outcome" button when forecast exists', () => {
    render(<ForecastCard {...baseProps} forecast={baseForecast} />);
    expect(screen.getByTestId('record-outcome-button')).toBeInTheDocument();
  });

  it('shows outcome modal on button click', async () => {
    const user = userEvent.setup();
    render(<ForecastCard {...baseProps} forecast={baseForecast} />);
    await user.click(screen.getByTestId('record-outcome-button'));
    expect(screen.getByTestId('outcome-form')).toBeInTheDocument();
  });

  it('renders "No forecast available" when forecast is null', () => {
    render(<ForecastCard {...baseProps} forecast={null} />);
    expect(screen.getByText(/no forecast available/i)).toBeInTheDocument();
  });

  it('shows actual scores when outcome is provided', () => {
    const outcome = { fierySkyActual: 70, goldenHourActual: 80, recordedAt: '2026-02-20T20:00:00Z' };
    const sonnetForecast = { ...baseForecast, rating: null, fierySkyPotential: 65, goldenHourPotential: 72 };
    render(<ForecastCard {...baseProps} forecast={sonnetForecast} outcome={outcome} />);
    expect(screen.getByTestId('sunset-fiery-sky-actual')).toBeInTheDocument();
  });

  it('shows sunrise accent for SUNRISE type', () => {
    render(<ForecastCard {...baseProps} type="SUNRISE" forecast={baseForecast} />);
    expect(screen.getByText(/sunrise/i)).toBeInTheDocument();
  });

  it('does not show precipitation line when zero', () => {
    render(<ForecastCard {...baseProps} forecast={baseForecast} />);
    expect(screen.queryByText(/precipitation/i)).not.toBeInTheDocument();
  });

  it('shows precipitation line when above zero', () => {
    render(
      <ForecastCard {...baseProps} forecast={{ ...baseForecast, precipitation: 1.2 }} />
    );
    expect(screen.getByText(/precipitation.*1\.2 mm/i)).toBeInTheDocument();
  });

  it('displays solar event time when solarEventTime is present', () => {
    const forecast = { ...baseForecast, solarEventTime: '2026-02-20T16:45:00' };
    render(<ForecastCard {...baseProps} forecast={forecast} />);
    expect(screen.getByTestId('sunset-time')).toBeInTheDocument();
    expect(screen.getByTestId('sunset-time')).toHaveTextContent('16:45');
  });

  it('does not display time element when solarEventTime is absent', () => {
    render(<ForecastCard {...baseProps} forecast={baseForecast} />);
    expect(screen.queryByTestId('sunset-time')).not.toBeInTheDocument();
  });
});
