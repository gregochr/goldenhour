import React from 'react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import NlcSightingBanner from '../components/NlcSightingBanner.jsx';

vi.mock('../hooks/useNlcSighting.js', () => ({
  useNlcSighting: vi.fn(),
}));

import { useNlcSighting } from '../hooks/useNlcSighting.js';

function renderBanner(sighting) {
  useNlcSighting.mockReturnValue({ sighting, loading: false });
  return render(<NlcSightingBanner eligible />);
}

describe('NlcSightingBanner sparse sightings', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders a bare sighting: plain heading, no observer, no look direction, no dark-sky line', () => {
    renderBanner({ active: true, description: 'NLC over the Forth' });
    const banner = screen.getByTestId('nlc-sighting-banner');
    expect(banner).toHaveTextContent('Sighting');
    expect(banner).not.toHaveTextContent('Sighting ·');
    expect(banner).not.toHaveTextContent(' via ');
    expect(banner).not.toHaveTextContent('Look');
    expect(banner.style.getPropertyValue('--nlc-accent')).toBe('#8E86D6');
    expect(screen.queryByTestId('nlc-sighting-darksky')).not.toBeInTheDocument();
  });

  it('names just the observer when no source is given', () => {
    renderBanner({
      active: true, description: 'NLC', observerLocation: 'Dumfries', reportedAt: new Date().toISOString(),
    });
    const banner = screen.getByTestId('nlc-sighting-banner');
    expect(banner).toHaveTextContent('Dumfries');
    expect(banner).not.toHaveTextContent('Dumfries via');
    expect(banner).toHaveTextContent('Sighting · just now');
  });

  it('dismissing a sighting with no report time hides it', () => {
    renderBanner({ active: true, description: 'NLC' });
    fireEvent.click(screen.getByTestId('nlc-sighting-dismiss'));
    expect(screen.queryByTestId('nlc-sighting-banner')).not.toBeInTheDocument();
  });
});
