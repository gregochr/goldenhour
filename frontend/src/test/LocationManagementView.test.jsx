import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import LocationManagementView from '../components/LocationManagementView.jsx';

vi.mock('../api/forecastApi', () => ({
  fetchLocations: vi.fn(),
  addLocation: vi.fn(),
  updateLocation: vi.fn(),
  setLocationEnabled: vi.fn(),
  geocodePlace: vi.fn(),
}));

vi.mock('../api/regionApi', () => ({
  fetchRegions: vi.fn(),
}));

import { fetchLocations, addLocation, geocodePlace } from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';

const MOCK_LOCATIONS = [
  { id: 1, name: 'Durham', lat: 54.77, lon: -1.58, enabled: true, goldenHourType: 'BOTH_TIMES', locationType: ['LANDSCAPE'], tideType: ['NOT_COASTAL'] },
];

describe('LocationManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);
    fetchRegions.mockResolvedValue([]);
  });

  it('renders location list', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText('Durham')).toBeInTheDocument();
    });
  });

  it('shows Add New Location form when button clicked', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-location-btn'));

    expect(screen.getByText('Add New Location')).toBeInTheDocument();
    expect(screen.getByTestId('place-name-input')).toBeInTheDocument();
  });

  it('disables Review & Confirm button in place search mode without geocode result', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-location-btn'));

    const reviewBtn = screen.getByTestId('review-confirm-btn');
    expect(reviewBtn).toBeDisabled();
  });

  it('shows hint message when in place search mode without geocode result', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-location-btn'));

    expect(screen.getByText(/Search for a place above/)).toBeInTheDocument();
  });

  it('enables Review & Confirm button after successful geocode', async () => {
    geocodePlace.mockResolvedValue({ lat: 55.6, lon: -1.7, displayName: 'Bamburgh, UK' });

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-location-btn'));
    fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: 'Bamburgh' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('review-confirm-btn')).not.toBeDisabled();
    });

    // Hint should disappear
    expect(screen.queryByText(/Search for a place above/)).not.toBeInTheDocument();
  });

  it('disables Review & Confirm in manual mode when fields are empty', async () => {
    geocodePlace.mockRejectedValue(new Error('Not found'));

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-location-btn'));
    // Trigger geocode error to reveal manual entry link
    fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: 'Nowhere' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('manual-entry-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('manual-entry-btn'));

    const reviewBtn = screen.getByTestId('review-confirm-btn');
    expect(reviewBtn).toBeDisabled();
  });

  it('enables Review & Confirm in manual mode when all fields filled', async () => {
    geocodePlace.mockRejectedValue(new Error('Not found'));

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-location-btn'));
    fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: 'Nowhere' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('manual-entry-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('manual-entry-btn'));

    fireEvent.change(screen.getByTestId('manual-name-input'), { target: { value: 'Test Location' } });
    fireEvent.change(screen.getByTestId('manual-lat-input'), { target: { value: '54.5' } });
    fireEvent.change(screen.getByTestId('manual-lon-input'), { target: { value: '-1.5' } });

    expect(screen.getByTestId('review-confirm-btn')).not.toBeDisabled();
  });

  it('hides place search hint in manual entry mode', async () => {
    geocodePlace.mockRejectedValue(new Error('Not found'));

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-location-btn'));
    // Hint visible in place search mode
    expect(screen.getByText(/Search for a place above/)).toBeInTheDocument();

    fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: 'Nowhere' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('manual-entry-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('manual-entry-btn'));

    // Hint should not be visible in manual mode
    expect(screen.queryByText(/Search for a place above/)).not.toBeInTheDocument();
  });
});
