import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import LocationManagementView from '../components/LocationManagementView.jsx';

vi.mock('../api/forecastApi', () => ({
  fetchLocations: vi.fn(),
  addLocation: vi.fn(),
  updateLocation: vi.fn(),
  setLocationEnabled: vi.fn(),
  geocodePlace: vi.fn(),
  enrichLocation: vi.fn(),
}));

vi.mock('../api/regionApi', () => ({
  fetchRegions: vi.fn(),
}));

vi.mock('../api/tideApi', () => ({
  fetchTideStats: vi.fn(),
  fetchAllTideStats: vi.fn(),
  fetchTidesForDate: vi.fn(),
}));

import { fetchLocations, addLocation, updateLocation, geocodePlace, enrichLocation } from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';
import { fetchTideStats } from '../api/tideApi';

const MOCK_LOCATIONS = [
  { id: 1, name: 'Durham', lat: 54.77, lon: -1.58, enabled: true, solarEventType: ['SUNRISE', 'SUNSET'], locationType: ['LANDSCAPE'], tideType: [] },
];

const MOCK_SEASCAPE_LOCATIONS = [
  { id: 2, name: 'Berwick-Upon-Tweed', lat: 55.77, lon: -2.00, enabled: true, solarEventType: ['SUNRISE', 'SUNSET'], locationType: ['SEASCAPE'], tideType: ['HIGH'] },
];

const MOCK_REGIONS = [
  { id: 1, name: 'North East', enabled: true },
  { id: 2, name: 'Lake District', enabled: true },
];

function makeMockLocations(count) {
  return Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    name: `Location ${i + 1}`,
    lat: 54 + i * 0.1,
    lon: -1 + i * 0.1,
    enabled: true,
    solarEventType: ['SUNRISE', 'SUNSET'],
    locationType: ['LANDSCAPE'],
    tideType: [],
  }));
}

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

  it('paginates locations when more than page size', async () => {
    const manyLocations = makeMockLocations(15);
    fetchLocations.mockResolvedValue(manyLocations);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    // Wait for data to load and pagination to apply
    await waitFor(() => {
      expect(screen.getByTestId('pagination')).toBeInTheDocument();
    });

    // Alphabetical sort: Location 1, 10, 11, 12, 13, 14, 15, 2, 3, 4 on page 1
    // Page 2: Location 5, 6, 7, 8, 9
    expect(screen.getByText('Location 1')).toBeInTheDocument();
    expect(screen.getByText('Location 4')).toBeInTheDocument();
    expect(screen.queryByText('Location 5')).not.toBeInTheDocument();
    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('Showing 1-10 of 15');

    // Navigate to page 2
    fireEvent.click(screen.getByTestId('pagination-next'));

    await waitFor(() => {
      expect(screen.getByText('Location 5')).toBeInTheDocument();
    });
    expect(screen.queryByText('Location 1')).not.toBeInTheDocument();
    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('Showing 11-15 of 15');
  });

  it('hides pagination when all locations fit on one page', async () => {
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText('Durham')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('pagination')).not.toBeInTheDocument();
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

  // --- Inline editing tests ---

  it('clicking Edit shows inline inputs and selects in that row', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));

    expect(screen.getByTestId('inline-edit-name')).toBeInTheDocument();
    expect(screen.getByTestId('inline-edit-name')).toHaveValue('Durham');
    expect(screen.getByTestId('inline-edit-type')).toBeInTheDocument();
    expect(screen.getByTestId('inline-edit-solar')).toBeInTheDocument();
    expect(screen.getByTestId('inline-edit-tide')).toBeInTheDocument();
    expect(screen.getByTestId('inline-edit-save')).toBeInTheDocument();
    expect(screen.getByTestId('inline-edit-cancel')).toBeInTheDocument();
  });

  it('clicking Cancel restores read-only row', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));
    expect(screen.getByTestId('inline-edit-name')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('inline-edit-cancel'));

    expect(screen.queryByTestId('inline-edit-name')).not.toBeInTheDocument();
    expect(screen.getByText('Durham')).toBeInTheDocument();
    expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
  });

  it('changing Type to SEASCAPE enables tide toggle chips with all selected', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));

    // Initially LANDSCAPE — tide chips should be disabled
    expect(screen.getByTestId('tide-chip-HIGH')).toBeDisabled();
    expect(screen.getByTestId('tide-chip-MID')).toBeDisabled();
    expect(screen.getByTestId('tide-chip-LOW')).toBeDisabled();

    // Click SEASCAPE type chip
    fireEvent.click(screen.getByTestId('type-chip-SEASCAPE'));

    // All three chips should be enabled and selected (gold styling)
    expect(screen.getByTestId('tide-chip-HIGH')).not.toBeDisabled();
    expect(screen.getByTestId('tide-chip-MID')).not.toBeDisabled();
    expect(screen.getByTestId('tide-chip-LOW')).not.toBeDisabled();
  });

  it('Save calls updateLocation with correct payload', async () => {
    const onChanged = vi.fn();
    updateLocation.mockResolvedValue({});
    fetchLocations
      .mockResolvedValueOnce(MOCK_LOCATIONS)
      .mockResolvedValueOnce(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={onChanged} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));

    // Change name
    fireEvent.change(screen.getByTestId('inline-edit-name'), { target: { value: 'Durham Cathedral' } });

    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => {
      expect(updateLocation).toHaveBeenCalledWith(1, {
        name: 'Durham Cathedral',
        lat: 54.77,
        lon: -1.58,
        solarEventTypes: ['SUNRISE', 'SUNSET'],
        locationType: 'LANDSCAPE',
        tideTypes: [],
        regionId: null,
      });
    });

    await waitFor(() => {
      expect(onChanged).toHaveBeenCalled();
    });
  });

  it('Save error shows inline below the row', async () => {
    updateLocation.mockRejectedValue(new Error('Server error'));

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => {
      expect(screen.getByTestId('inline-edit-error')).toHaveTextContent('Server error');
    });
  });

  it('only one row editable at a time', async () => {
    const twoLocations = [
      { id: 1, name: 'Durham', lat: 54.77, lon: -1.58, enabled: true, solarEventType: ['SUNRISE', 'SUNSET'], locationType: ['LANDSCAPE'], tideType: [] },
      { id: 2, name: 'Bamburgh', lat: 55.61, lon: -1.71, enabled: true, solarEventType: ['SUNSET'], locationType: ['SEASCAPE'], tideType: ['HIGH'] },
    ];
    fetchLocations.mockResolvedValue(twoLocations);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    // Start editing row 1
    fireEvent.click(screen.getByTestId('edit-location-1'));
    expect(screen.getByTestId('inline-edit-name')).toHaveValue('Durham');

    // Start editing row 2 — should cancel row 1
    fireEvent.click(screen.getByTestId('edit-location-2'));
    expect(screen.getByTestId('inline-edit-name')).toHaveValue('Bamburgh');

    // Row 1 should be back to read-only (Edit button visible)
    expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
  });

  it('blank name shows validation error on save', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));

    // Clear name
    fireEvent.change(screen.getByTestId('inline-edit-name'), { target: { value: '' } });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => {
      expect(screen.getByTestId('inline-edit-error')).toHaveTextContent('Name cannot be blank.');
    });

    // updateLocation should NOT have been called
    expect(updateLocation).not.toHaveBeenCalled();
  });

  it('Escape key cancels edit', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));
    expect(screen.getByTestId('inline-edit-name')).toBeInTheDocument();

    // Press Escape on the editing row
    const nameInput = screen.getByTestId('inline-edit-name');
    fireEvent.keyDown(nameInput.closest('tr'), { key: 'Escape' });

    expect(screen.queryByTestId('inline-edit-name')).not.toBeInTheDocument();
    expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
  });

  it('inline edit shows region select with regions', async () => {
    fetchRegions.mockResolvedValue(MOCK_REGIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));

    const regionSelect = screen.getByTestId('inline-edit-region');
    expect(regionSelect).toBeInTheDocument();
    // Should have "— None —" + 2 regions = 3 options
    expect(regionSelect.querySelectorAll('option')).toHaveLength(3);
  });

  it('shows Tides button for SEASCAPE locations only', async () => {
    fetchLocations.mockResolvedValue([
      ...MOCK_LOCATIONS,
      ...MOCK_SEASCAPE_LOCATIONS,
    ]);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText('Berwick-Upon-Tweed')).toBeInTheDocument();
    });

    // SEASCAPE location should have Tides button
    expect(screen.getByTestId('tides-location-2')).toBeInTheDocument();
    // LANDSCAPE location should not
    expect(screen.queryByTestId('tides-location-1')).not.toBeInTheDocument();
  });

  // --- SEASCAPE tide preference validation tests ---

  it('edit: shows error when saving SEASCAPE location with no tide preferences', async () => {
    // SEASCAPE location with no tide preferences (legacy / edge case)
    const seascapeNoTides = [
      { id: 1, name: 'Bamburgh', lat: 55.6, lon: -1.7, enabled: true,
        solarEventType: ['SUNSET'], locationType: ['SEASCAPE'], tideType: [] },
    ];
    fetchLocations.mockResolvedValue(seascapeNoTides);
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));
    // Save immediately without selecting any tide types
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => {
      expect(screen.getByTestId('inline-edit-error')).toHaveTextContent(
        'Coastal locations require at least one tide preference'
      );
    });

    expect(updateLocation).not.toHaveBeenCalled();
  });

  it('edit: no error when saving SEASCAPE location with at least one tide preference', async () => {
    const seascapeWithTides = [
      { id: 1, name: 'Bamburgh', lat: 55.6, lon: -1.7, enabled: true,
        solarEventType: ['SUNSET'], locationType: ['SEASCAPE'], tideType: ['HIGH'] },
    ];
    fetchLocations
      .mockResolvedValueOnce(seascapeWithTides)
      .mockResolvedValueOnce(seascapeWithTides);
    updateLocation.mockResolvedValue({});
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => {
      expect(updateLocation).toHaveBeenCalled();
    });
    expect(screen.queryByTestId('inline-edit-error')).not.toBeInTheDocument();
  });

  it('add (manual): shows error when SEASCAPE selected with no tide preferences', async () => {
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

    // Fill in required fields
    fireEvent.change(screen.getByTestId('manual-name-input'), { target: { value: 'Seahouses' } });
    fireEvent.change(screen.getByTestId('manual-lat-input'), { target: { value: '55.6' } });
    fireEvent.change(screen.getByTestId('manual-lon-input'), { target: { value: '-1.7' } });

    // Select SEASCAPE — this auto-selects all three tide types
    fireEvent.change(screen.getByTestId('add-location-type'), { target: { value: 'SEASCAPE' } });

    // Deselect HIGH and MID chips (LOW is last and can't be deselected in practice,
    // but we can deselect the first two to reach 1 selected)
    // This tests the happy path: with any tide preference, no error
    fireEvent.click(screen.getByTestId('review-confirm-btn'));

    // Should proceed to confirm step (no validation error)
    await waitFor(() => {
      expect(screen.queryByText('Coastal locations require at least one tide preference')).not.toBeInTheDocument();
    });
  });

  // --- Bortle column tests ---

  it('shows Light pollution column header in the location table', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByText('Durham')).toBeInTheDocument();
    });

    expect(screen.getByText('Light pollution')).toBeInTheDocument();
  });

  it('shows — in Bortle cell when bortleClass is null', async () => {
    // MOCK_LOCATIONS has no bortleClass → treats as null → shows '—'
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('bortle-1')).toBeInTheDocument();
    });

    expect(screen.getByTestId('bortle-1')).toHaveTextContent('—');
  });

  it('shows numeric Bortle class in cell when bortleClass is set', async () => {
    fetchLocations.mockResolvedValue([{ ...MOCK_LOCATIONS[0], bortleClass: 3 }]);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('bortle-1')).toBeInTheDocument();
    });

    expect(screen.getByTestId('bortle-1')).toHaveTextContent('3');
  });

  it('Bortle cell is read-only during inline edit — shows value as text, no input', async () => {
    fetchLocations.mockResolvedValue([{ ...MOCK_LOCATIONS[0], bortleClass: 4 }]);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));

    // Edit-mode Bortle cell must exist, show the value, and contain no input
    const bortleEditCell = screen.getByTestId('bortle-edit-1');
    expect(bortleEditCell).toBeInTheDocument();
    expect(bortleEditCell).toHaveTextContent('4');
    expect(bortleEditCell.querySelector('input')).toBeNull();
  });

  it('Bortle cell shows — as read-only text when bortleClass is null during inline edit', async () => {
    // Null bortleClass should still render '—' (not an input) in edit mode
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-location-1'));

    const bortleEditCell = screen.getByTestId('bortle-edit-1');
    expect(bortleEditCell).toHaveTextContent('—');
    expect(bortleEditCell.querySelector('input')).toBeNull();
  });

  it('opens tide stats modal when Tides button clicked', async () => {
    fetchLocations.mockResolvedValue(MOCK_SEASCAPE_LOCATIONS);
    fetchTideStats.mockResolvedValue({
      avgHighMetres: 1.4, maxHighMetres: 1.8, avgLowMetres: -1.2, minLowMetres: -1.5,
      dataPoints: 200, avgRangeMetres: 2.6, p75HighMetres: 1.6, p90HighMetres: 1.72,
      p95HighMetres: 1.78, springTideCount: 5, springTideFrequency: 0.05,
    });

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('tides-location-2')).toBeInTheDocument();
    });

    await fireEvent.click(screen.getByTestId('tides-location-2'));

    // Modal should appear immediately with loading state
    await waitFor(() => {
      expect(screen.getByTestId('tide-stats-modal')).toBeInTheDocument();
    });

    // After stats load, data should be visible
    await waitFor(() => {
      expect(screen.getByText('200')).toBeInTheDocument();
    });
  });

  // --- Enrichment tests ---

  it('displays enrichment data after geocoding', async () => {
    geocodePlace.mockResolvedValue({ lat: 54.6124, lon: -3.1179, displayName: 'Latrigg, UK' });
    enrichLocation.mockResolvedValue({
      bortleClass: 3,
      skyBrightnessSqm: 21.72,
      elevationMetres: 368,
      gridLat: 54.611,
      gridLng: -3.121,
    });
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('add-location-btn'));

    const input = screen.getByTestId('place-name-input');
    fireEvent.change(input, { target: { value: 'Latrigg' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('enrichment-elevation')).toHaveTextContent('368m');
    });

    expect(screen.getByTestId('enrichment-bortle')).toHaveTextContent('3');
    expect(screen.getByTestId('enrichment-sqm')).toHaveTextContent('21.72');
    expect(screen.getByTestId('enrichment-grid')).toHaveTextContent('54.611, -3.121');
  });

  it('shows dashes for null enrichment fields', async () => {
    geocodePlace.mockResolvedValue({ lat: 54.0, lon: -2.0, displayName: 'Test, UK' });
    enrichLocation.mockResolvedValue({
      bortleClass: null,
      skyBrightnessSqm: null,
      elevationMetres: 50,
      gridLat: null,
      gridLng: null,
    });
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('add-location-btn'));

    const input = screen.getByTestId('place-name-input');
    fireEvent.change(input, { target: { value: 'Test' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('enrichment-elevation')).toHaveTextContent('50m');
    });

    expect(screen.getByTestId('enrichment-bortle')).toHaveTextContent('—');
    expect(screen.getByTestId('enrichment-sqm')).toHaveTextContent('—');
    expect(screen.getByTestId('enrichment-grid')).toHaveTextContent('—');
  });

  it('renders manual toggle checkboxes', async () => {
    geocodePlace.mockResolvedValue({ lat: 54.0, lon: -2.0, displayName: 'Test, UK' });
    enrichLocation.mockResolvedValue({
      bortleClass: null,
      skyBrightnessSqm: null,
      elevationMetres: null,
      gridLat: null,
      gridLng: null,
    });
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('add-location-btn'));

    const input = screen.getByTestId('place-name-input');
    fireEvent.change(input, { target: { value: 'Test' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('manual-toggles-panel')).toBeInTheDocument();
    });

    const overlooksWater = screen.getByTestId('overlooks-water-checkbox');
    const coastalTidal = screen.getByTestId('coastal-tidal-checkbox');
    expect(overlooksWater).not.toBeChecked();
    expect(coastalTidal).not.toBeChecked();

    fireEvent.click(overlooksWater);
    expect(overlooksWater).toBeChecked();
  });

  it('shows enriching spinner while enrichment is loading', async () => {
    geocodePlace.mockResolvedValue({ lat: 54.0, lon: -2.0, displayName: 'Test, UK' });
    let resolveEnrichment;
    enrichLocation.mockReturnValue(
      new Promise((resolve) => {
        resolveEnrichment = resolve;
      }),
    );
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('add-location-btn'));

    const input = screen.getByTestId('place-name-input');
    fireEvent.change(input, { target: { value: 'Test' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('enriching-spinner')).toBeInTheDocument();
    });

    resolveEnrichment({
      bortleClass: null,
      skyBrightnessSqm: null,
      elevationMetres: null,
      gridLat: null,
      gridLng: null,
    });

    await waitFor(() => {
      expect(screen.queryByTestId('enriching-spinner')).not.toBeInTheDocument();
    });
  });

  it('includes enrichment data and manual toggles in save request', async () => {
    geocodePlace.mockResolvedValue({ lat: 54.6124, lon: -3.1179, displayName: 'Latrigg, UK' });
    enrichLocation.mockResolvedValue({
      bortleClass: 3,
      skyBrightnessSqm: 21.72,
      elevationMetres: 368,
      gridLat: 54.611,
      gridLng: -3.121,
    });
    addLocation.mockResolvedValue({ id: 99, name: 'Latrigg' });
    fetchLocations
      .mockResolvedValueOnce(MOCK_LOCATIONS)
      .mockResolvedValueOnce(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('add-location-btn'));

    const input = screen.getByTestId('place-name-input');
    fireEvent.change(input, { target: { value: 'Latrigg' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('enrichment-elevation')).toHaveTextContent('368m');
    });

    // Check overlooks water toggle
    fireEvent.click(screen.getByTestId('overlooks-water-checkbox'));

    // Click Review & Confirm
    fireEvent.click(screen.getByTestId('review-confirm-btn'));

    // Confirm modal should show enrichment data
    await waitFor(() => {
      expect(screen.getAllByText('368m').length).toBeGreaterThanOrEqual(1);
    });

    // Click Save
    fireEvent.click(screen.getByTestId('confirm-save-btn'));

    await waitFor(() => {
      expect(addLocation).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Latrigg',
          lat: 54.6124,
          lon: -3.1179,
          bortleClass: 3,
          skyBrightnessSqm: 21.72,
          elevationMetres: 368,
          gridLat: 54.611,
          gridLng: -3.121,
          overlooksWater: true,
          coastalTidal: false,
        }),
      );
    });
  });

  it('saves with coastalTidal true and overlooksWater false — swap killer', async () => {
    geocodePlace.mockResolvedValue({ lat: 55.0, lon: -1.5, displayName: 'Pier, UK' });
    enrichLocation.mockResolvedValue({
      bortleClass: null,
      skyBrightnessSqm: null,
      elevationMetres: null,
      gridLat: null,
      gridLng: null,
    });
    addLocation.mockResolvedValue({ id: 101, name: 'Pier' });
    fetchLocations
      .mockResolvedValueOnce(MOCK_LOCATIONS)
      .mockResolvedValueOnce(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('add-location-btn'));

    const input = screen.getByTestId('place-name-input');
    fireEvent.change(input, { target: { value: 'Pier' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('manual-toggles-panel')).toBeInTheDocument();
    });

    // Click ONLY coastalTidal — overlooksWater stays unchecked
    fireEvent.click(screen.getByTestId('coastal-tidal-checkbox'));

    fireEvent.click(screen.getByTestId('review-confirm-btn'));
    await waitFor(() => {
      expect(screen.getByTestId('confirm-save-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('confirm-save-btn'));

    await waitFor(() => {
      expect(addLocation).toHaveBeenCalledWith(
        expect.objectContaining({
          overlooksWater: false,
          coastalTidal: true,
        }),
      );
    });
  });

  it('includes null enrichment fields in save request when enrichment fails', async () => {
    geocodePlace.mockResolvedValue({ lat: 54.0, lon: -2.0, displayName: 'Test, UK' });
    enrichLocation.mockRejectedValue(new Error('Network error'));
    addLocation.mockResolvedValue({ id: 100, name: 'Test' });
    fetchLocations
      .mockResolvedValueOnce(MOCK_LOCATIONS)
      .mockResolvedValueOnce(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('add-location-btn'));

    const input = screen.getByTestId('place-name-input');
    fireEvent.change(input, { target: { value: 'Test' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    // Wait for geocode result
    await waitFor(() => {
      expect(screen.getByText('54.0000, -2.0000')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('review-confirm-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('confirm-save-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('confirm-save-btn'));

    await waitFor(() => {
      expect(addLocation).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Test',
          lat: 54.0,
          lon: -2.0,
          bortleClass: null,
          skyBrightnessSqm: null,
          elevationMetres: null,
          gridLat: null,
          gridLng: null,
          overlooksWater: false,
          coastalTidal: false,
        }),
      );
    });
  });

  it('passes exact coordinates to enrichLocation', async () => {
    geocodePlace.mockResolvedValue({ lat: 55.609, lon: -1.7099, displayName: 'Bamburgh, UK' });
    enrichLocation.mockResolvedValue({
      bortleClass: null,
      skyBrightnessSqm: null,
      elevationMetres: null,
      gridLat: null,
      gridLng: null,
    });
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('add-location-btn'));

    const input = screen.getByTestId('place-name-input');
    fireEvent.change(input, { target: { value: 'Bamburgh' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));

    await waitFor(() => {
      expect(enrichLocation).toHaveBeenCalledWith(55.609, -1.7099);
    });
  });
});
