import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import AzimuthIndicator from '../components/AzimuthIndicator.jsx';

describe('AzimuthIndicator', () => {
  it('renders the testid container', () => {
    render(<AzimuthIndicator azimuthDeg={67} isSunrise={true} />);
    expect(screen.getByTestId('azimuth-indicator')).toBeInTheDocument();
  });

  it('shows "Rises" label for sunrise', () => {
    render(<AzimuthIndicator azimuthDeg={90} isSunrise={true} />);
    expect(screen.getByText(/rises/i)).toBeInTheDocument();
  });

  it('shows "Sets" label for sunset', () => {
    render(<AzimuthIndicator azimuthDeg={270} isSunrise={false} />);
    expect(screen.getByText(/sets/i)).toBeInTheDocument();
  });

  it('shows the compass direction for a sunrise azimuth', () => {
    render(<AzimuthIndicator azimuthDeg={67} isSunrise={true} />);
    expect(screen.getByText('ENE')).toBeInTheDocument();
  });

  it('shows the numeric bearing', () => {
    render(<AzimuthIndicator azimuthDeg={67} isSunrise={true} />);
    expect(screen.getByText('(67°)')).toBeInTheDocument();
  });

  it('shows NW for a typical winter sunset azimuth', () => {
    render(<AzimuthIndicator azimuthDeg={315} isSunrise={false} />);
    expect(screen.getByText('NW')).toBeInTheDocument();
  });
});
