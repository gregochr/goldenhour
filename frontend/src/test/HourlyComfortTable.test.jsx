/**
 * `components/HourlyComfortTable.jsx` — the one hourly comfort table, shared by the Plan-tab overlay's
 * marker popup and the Map tab's four-day location sheet.
 *
 * <p><b>What breaks if these fail:</b> the table stops being a table to a screen reader (no name, no
 * column or row headers), a missing figure prints as the word "undefined" or as nothing, or the one
 * table the two hosts share drifts into two.
 */
import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import HourlyComfortTable from '../components/HourlyComfortTable.jsx';

// Winter, so the UK clock and the UTC instants agree.
const ROWS = [
  {
    solarEventTime: '2026-02-10T07:00:00', temperatureCelsius: 2.4, apparentTemperatureCelsius: -0.6,
    windSpeed: 3, windDirection: 270, precipitationProbabilityPercent: 10,
  },
  {
    solarEventTime: '2026-02-10T08:00:00', temperatureCelsius: 4, apparentTemperatureCelsius: 1.2,
    windSpeed: 6, windDirection: 45, precipitationProbabilityPercent: 0,
  },
];

describe('HourlyComfortTable', () => {
  it('is a real table with the name it was given', () => {
    render(<HourlyComfortTable rows={ROWS} label="Hourly comfort at Low Barns" />);
    expect(screen.getByRole('table', { name: 'Hourly comfort at Low Barns' })).toBeInTheDocument();
  });

  it('names its four columns, and each hour by its time', () => {
    render(<HourlyComfortTable rows={ROWS} label="Hourly comfort" />);
    expect(screen.getAllByRole('columnheader').map((th) => th.textContent))
      .toEqual(['Time', 'Temperature', 'Wind', 'Rain chance']);
    expect(screen.getAllByRole('rowheader').map((th) => th.textContent)).toEqual(['07:00', '08:00']);
  });

  it('prints each hour\'s temperature with feels-like, wind in mph with its compass point, and rain chance', () => {
    render(<HourlyComfortTable rows={ROWS} label="Hourly comfort" />);
    const [first, second] = screen.getAllByTestId('hourly-comfort-row');
    expect(first).toHaveTextContent('2°C · feels -1°C');
    expect(first).toHaveTextContent('6.7 mph W');
    expect(first).toHaveTextContent('10%');
    expect(second).toHaveTextContent('4°C · feels 1°C');
    expect(second).toHaveTextContent('13.4 mph NE');
    // A real 0 is a figure, not an absence.
    expect(second).toHaveTextContent('0%');
  });

  it('falls back to the air temperature when feels-like is missing', () => {
    render(<HourlyComfortTable rows={[{ ...ROWS[0], apparentTemperatureCelsius: null }]} label="x" />);
    expect(screen.getByTestId('hourly-comfort-row')).toHaveTextContent('2°C · feels 2°C');
  });

  it('marks every missing figure with a dash for the eye and "not forecast" for the ear, never "undefined"', () => {
    render(
      <HourlyComfortTable
        rows={[{
          solarEventTime: '2026-02-10T09:00:00', temperatureCelsius: null, apparentTemperatureCelsius: null,
          windSpeed: null, windDirection: null, precipitationProbabilityPercent: null,
        }]}
        label="x"
      />,
    );
    const rowEl = screen.getByTestId('hourly-comfort-row');
    expect(within(rowEl).getAllByText('not forecast')).toHaveLength(3);
    expect(rowEl.textContent).not.toContain('undefined');
  });

  it('prints a wind speed with no direction as speed alone — no "undefined" compass word', () => {
    render(<HourlyComfortTable rows={[{ ...ROWS[0], windDirection: null }]} label="x" />);
    const rowEl = screen.getByTestId('hourly-comfort-row');
    expect(rowEl).toHaveTextContent('6.7 mph');
    expect(rowEl.textContent).not.toContain('undefined');
  });

  it('hides its column headers visually by default but keeps them in the accessibility tree', () => {
    render(<HourlyComfortTable rows={ROWS} label="x" />);
    expect(screen.getByRole('columnheader', { name: 'Wind' }).firstChild).toHaveClass('sr-only');
  });

  it('shows its column headers when asked', () => {
    render(<HourlyComfortTable rows={ROWS} label="x" headersVisible />);
    expect(screen.getByRole('columnheader', { name: 'Wind' }).firstChild).not.toHaveClass('sr-only');
  });

  it('takes its test id from its caller, so two hosts can be told apart', () => {
    render(<HourlyComfortTable rows={ROWS} label="x" testId="location-sheet-hourly-table" />);
    expect(screen.getByTestId('location-sheet-hourly-table')).toBe(screen.getByRole('table'));
  });
});
