import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('../utils/dawnRace.js', async () => {
  const actual = await vi.importActual('../utils/dawnRace.js');
  return {
    ...actual,
    // A model whose position() cannot place the umbra or hatch instants: the component must fall
    // back to the track's left edge and draw a zero-width band rather than NaN.
    raceModel: vi.fn(() => ({
      trackStart: 0,
      trackEnd: 100,
      position: () => null,
      umbra: { from: 10, to: 20 },
      hatch: { from: 30, to: 40 },
      markers: [{ key: 'MAXIMUM', role: 'maximum', t: 50, label: 'max', hidePhone: false }],
      ticks: [{ key: 'CIVIL_DUSK', t: 60, label: 'civil' }],
      gradient: 'linear-gradient(to right, #000, #fff)',
    })),
    raceSentence: vi.fn(() => 'A sentence.'),
  };
});

import DawnRace from '../components/DawnRace.jsx';

describe('DawnRace unplaceable instants', () => {
  it('draws the umbra and hatch as zero-width bands at the left edge', () => {
    render(<DawnRace sight={{ race: 'DUSK' }} spotName="Bamburgh" />);
    const umbra = screen.getByTestId('dawn-race-umbra');
    expect(umbra.style.left).toBe('0%');
    expect(umbra.style.width).toBe('0%');
    const hatch = screen.getByTestId('dawn-race-hatch');
    expect(hatch.style.left).toBe('0%');
    expect(hatch.style.width).toBe('0%');
    expect(screen.getByTestId('dawn-race-tick').style.left).toBe('0%');
    expect(screen.getByTestId('dawn-race-marker').style.left).toBe('0%');
    expect(screen.getByText(/the darkening sky · Bamburgh/)).toBeInTheDocument();
  });
});
