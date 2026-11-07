import React from 'react';
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import LocationSheetFacts from '../components/map/LocationSheetFacts.jsx';

describe('LocationSheetFacts draws nothing when there is nothing to show', () => {
  it('renders nothing without a forecast row', () => {
    const { container } = render(<LocationSheetFacts forecast={null} eventType="SUNSET" isPro />);

    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing for a row with no badges, no temperature and no generated time', () => {
    const { container } = render(
      <LocationSheetFacts forecast={{ lowCloud: 10 }} eventType="SUNRISE" isPro={false} />,
    );

    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByTestId('location-sheet-facts')).toBeNull();
  });

  it('still draws the block when only the generated footer is available', () => {
    render(
      <LocationSheetFacts
        forecast={{ lowCloud: 10, forecastRunAt: '2026-08-14T05:00:00' }}
        eventType="SUNRISE"
        isPro
      />,
    );

    expect(screen.getByTestId('location-sheet-generated')).toHaveTextContent('Forecast generated:');
    expect(screen.queryByTestId('location-sheet-comfort')).toBeNull();
  });
});
