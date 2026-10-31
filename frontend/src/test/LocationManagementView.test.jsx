import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import LocationManagementView from '../components/LocationManagementView.jsx';

vi.mock('../api/forecastApi', () => ({
  fetchLocations: vi.fn(),
  addLocation: vi.fn(),
  updateLocation: vi.fn(),
  setLocationEnabled: vi.fn(),
  geocodePlace: vi.fn(),
  enrichLocation: vi.fn(),
  resetLocationFailures: vi.fn(),
  fetchValleyFloorCandidates: vi.fn(),
  fetchValleyFloorCell: vi.fn(),
}));

vi.mock('../api/regionApi', () => ({
  fetchRegions: vi.fn(),
}));

vi.mock('../api/areaApi', () => ({
  fetchAreas: vi.fn(),
  fetchUncoveredLocations: vi.fn(),
  createArea: vi.fn(),
  updateArea: vi.fn(),
  setAreaLocations: vi.fn(),
  deleteArea: vi.fn(),
  fetchLocationAliases: vi.fn(),
  setLocationAliases: vi.fn(),
}));

vi.mock('../api/tideApi', () => ({
  fetchTideStats: vi.fn(),
  fetchAllTideStats: vi.fn(),
}));

import {
  fetchLocations, addLocation, updateLocation, geocodePlace, enrichLocation, resetLocationFailures,
  fetchValleyFloorCandidates, fetchValleyFloorCell,
} from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';
import { fetchAreas, fetchUncoveredLocations, fetchLocationAliases, setLocationAliases } from '../api/areaApi';
import { fetchTideStats } from '../api/tideApi';

const MOCK_LOCATIONS = [
  { id: 1, name: 'Durham', lat: 54.77, lon: -1.58, enabled: true, solarEventType: ['SUNRISE', 'SUNSET'], locationType: ['LANDSCAPE'], tideType: [] },
];

const MOCK_SEASCAPE_LOCATIONS = [
  { id: 2, name: 'Berwick-Upon-Tweed', lat: 55.77, lon: -2.00, enabled: true, solarEventType: ['SUNRISE', 'SUNSET'], locationType: ['SEASCAPE'], tideType: ['HIGH'] },
];

const MOCK_MULTITYPE_LOCATIONS = [
  {
    id: 3, name: 'Bluebell Wood', lat: 54.5, lon: -2.0, enabled: true,
    solarEventType: ['SUNRISE'], locationType: ['LANDSCAPE', 'WOODLAND'], tideType: [],
  },
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
    fetchAreas.mockResolvedValue([]);
    fetchUncoveredLocations.mockResolvedValue([]);
    fetchLocationAliases.mockResolvedValue({});
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
        locationTypes: ['LANDSCAPE'],
        tideTypes: [],
        regionId: null,
        mistProne: false,
      });
    });

    await waitFor(() => {
      expect(onChanged).toHaveBeenCalled();
    });
  });

  it('inline edit shows the mist checkbox unchecked for an unflagged place and submits a tick', async () => {
    updateLocation.mockResolvedValue({});
    fetchLocations
      .mockResolvedValueOnce(MOCK_LOCATIONS)
      .mockResolvedValueOnce(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('edit-location-1'));

    const mist = screen.getByRole('checkbox', { name: /Mist forms here: valley floor, riverside, lake shore/ });
    expect(mist).toBe(screen.getByTestId('inline-edit-mist-prone'));
    expect(mist).not.toBeChecked();
    fireEvent.click(mist);
    expect(mist).toBeChecked();
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => {
      expect(updateLocation).toHaveBeenCalledWith(1, expect.objectContaining({ mistProne: true }));
    });
  });

  it('inline edit starts checked for a flagged place and submits the untick', async () => {
    updateLocation.mockResolvedValue({});
    const flagged = [{ ...MOCK_LOCATIONS[0], mistProne: true }, ...MOCK_LOCATIONS.slice(1)];
    fetchLocations.mockResolvedValueOnce(flagged).mockResolvedValueOnce(flagged);

    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });
    expect(screen.getByTestId('mist-prone-badge-1')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('edit-location-1'));

    const mist = screen.getByTestId('inline-edit-mist-prone');
    expect(mist).toBeChecked();
    fireEvent.click(mist);
    expect(mist).not.toBeChecked();
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => {
      expect(updateLocation).toHaveBeenCalledWith(1, expect.objectContaining({ mistProne: false }));
    });
  });

  it('shows no mist badge on an unflagged place', async () => {
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('edit-location-1')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('mist-prone-badge-1')).not.toBeInTheDocument();
  });

  it('editing a multi-typed location keeps every type, not just the first', async () => {
    // The data-loss bug: the editor collapsed locationType to set[0] on open and the backend
    // full-replaced from that scalar, so renaming a wood typed [LANDSCAPE, WOODLAND] silently
    // dropped WOODLAND and put it back on sky scoring.
    updateLocation.mockResolvedValue({});
    fetchLocations.mockResolvedValue(MOCK_MULTITYPE_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await waitFor(() => expect(screen.getByTestId('edit-location-3')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('edit-location-3'));

    fireEvent.change(screen.getByTestId('inline-edit-name'), { target: { value: 'Bluebell Wood Renamed' } });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => {
      expect(updateLocation).toHaveBeenCalledWith(3, expect.objectContaining({
        name: 'Bluebell Wood Renamed',
        locationTypes: expect.arrayContaining(['LANDSCAPE', 'WOODLAND']),
      }));
    });
    expect(updateLocation.mock.calls[0][1].locationTypes).toHaveLength(2);
  });

  it('opening a multi-typed location shows every type chip as selected', async () => {
    fetchLocations.mockResolvedValue(MOCK_MULTITYPE_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await waitFor(() => expect(screen.getByTestId('edit-location-3')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('edit-location-3'));

    expect(screen.getByTestId('type-chip-LANDSCAPE')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('type-chip-WOODLAND')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('type-chip-SEASCAPE')).toHaveAttribute('aria-pressed', 'false');
  });

  it('the last remaining type chip cannot be deselected', async () => {
    updateLocation.mockResolvedValue({});
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await waitFor(() => expect(screen.getByTestId('edit-location-1')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('edit-location-1'));

    // Durham is [LANDSCAPE] only — clicking its sole chip must not untype it.
    fireEvent.click(screen.getByTestId('type-chip-LANDSCAPE'));
    expect(screen.getByTestId('type-chip-LANDSCAPE')).toHaveAttribute('aria-pressed', 'true');

    fireEvent.click(screen.getByTestId('inline-edit-save'));
    await waitFor(() => {
      expect(updateLocation).toHaveBeenCalledWith(1, expect.objectContaining({
        locationTypes: ['LANDSCAPE'],
      }));
    });
  });

  it('adding a non-coastal type to a coastal site keeps its tide preferences', async () => {
    // Re-seeding tides on every type change would wipe a curated preference the moment an
    // unrelated chip was added.
    updateLocation.mockResolvedValue({});
    fetchLocations.mockResolvedValue(MOCK_SEASCAPE_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await waitFor(() => expect(screen.getByTestId('edit-location-2')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('edit-location-2'));

    fireEvent.click(screen.getByTestId('type-chip-WOODLAND'));
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => {
      expect(updateLocation).toHaveBeenCalledWith(2, expect.objectContaining({
        locationTypes: expect.arrayContaining(['SEASCAPE', 'WOODLAND']),
        tideTypes: ['HIGH'],
      }));
    });
  });

  it('offers Woodland as a selectable type', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await waitFor(() => expect(screen.getByTestId('edit-location-1')).toBeInTheDocument());
    fireEvent.click(screen.getByTestId('edit-location-1'));

    expect(screen.getByTestId('type-chip-WOODLAND')).toBeInTheDocument();
    expect(screen.getByTestId('type-chip-WOODLAND')).toHaveAttribute('title', 'Woodland');
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

  it('add (manual): a coastal site with seeded tides passes review without error', async () => {
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

    // Select SEASCAPE — this auto-selects all three tide types. The type control is a
    // multi-select chip group, so LANDSCAPE is deselected explicitly afterwards.
    fireEvent.click(screen.getByTestId('type-chip-SEASCAPE'));
    fireEvent.click(screen.getByTestId('type-chip-LANDSCAPE'));

    // Selecting SEASCAPE seeds all three tide types, so the coastal validation must pass.
    fireEvent.click(screen.getByTestId('review-confirm-btn'));

    // Assert POSITIVELY that review advanced. The previous version only asserted the absence of
    // an error via an exact-match queryByText against a prefix of the real message
    // ('...tide preference' vs the rendered '...tide preference (High, Mid, or Low).'), so it
    // returned null whether or not the error was on screen and could never fail.
    await waitFor(() => {
      expect(screen.getByTestId('confirm-save-btn')).toBeInTheDocument();
    });
    expect(screen.queryByText(/Coastal locations require at least one tide preference/)).not.toBeInTheDocument();
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

  it('add form offers the mist checkbox beside overlooks water, unchecked by default', async () => {
    geocodePlace.mockResolvedValue({ lat: 54.43, lon: -3.03, displayName: 'Brathay, UK' });
    enrichLocation.mockResolvedValue({
      bortleClass: 3, skyBrightnessSqm: 21.7, elevationMetres: 54, gridLat: 54.43, gridLng: -3.03,
    });
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('add-location-btn'));
    fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: 'Brathay' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));
    await waitFor(() => {
      expect(screen.getByTestId('manual-toggles-panel')).toBeInTheDocument();
    });

    const mist = screen.getByTestId('mist-prone-checkbox');
    expect(mist).not.toBeChecked();
    expect(mist.closest('label')).toHaveTextContent(
      'Mist forms here: valley floor, riverside, lake shore',
    );
    fireEvent.click(mist);
    expect(mist).toBeChecked();
  });

  it('a ticked mist checkbox reaches the confirm dialog and the addLocation payload', async () => {
    geocodePlace.mockResolvedValue({ lat: 54.43, lon: -3.03, displayName: 'Brathay, UK' });
    enrichLocation.mockResolvedValue({
      bortleClass: 3, skyBrightnessSqm: 21.7, elevationMetres: 54, gridLat: 54.43, gridLng: -3.03,
    });
    addLocation.mockResolvedValue({});
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('add-location-btn'));
    fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: 'Brathay' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));
    await waitFor(() => {
      expect(screen.getByTestId('mist-prone-checkbox')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('mist-prone-checkbox'));
    fireEvent.click(screen.getByTestId('review-confirm-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('confirm-mist-prone')).toHaveTextContent('Yes');
    });
    fireEvent.click(screen.getByTestId('confirm-save-btn'));

    await waitFor(() => {
      expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({ mistProne: true }));
    });
  });

  it('an unticked mist checkbox sends mistProne false and the confirm dialog says No', async () => {
    geocodePlace.mockResolvedValue({ lat: 54.43, lon: -3.03, displayName: 'Brathay, UK' });
    enrichLocation.mockResolvedValue({
      bortleClass: 3, skyBrightnessSqm: 21.7, elevationMetres: 54, gridLat: 54.43, gridLng: -3.03,
    });
    addLocation.mockResolvedValue({});
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);

    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await waitFor(() => {
      expect(screen.getByTestId('add-location-btn')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('add-location-btn'));
    fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: 'Brathay' } });
    fireEvent.click(screen.getByTestId('geocode-btn'));
    await waitFor(() => {
      expect(screen.getByTestId('mist-prone-checkbox')).toBeInTheDocument();
    });
    fireEvent.click(screen.getByTestId('review-confirm-btn'));

    await waitFor(() => {
      expect(screen.getByTestId('confirm-mist-prone')).toHaveTextContent('No');
    });
    fireEvent.click(screen.getByTestId('confirm-save-btn'));

    await waitFor(() => {
      expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({ mistProne: false }));
    });
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

describe('LocationManagementView — a place the pipeline auto-disabled', () => {
  const REASON = 'Auto-disabled after 3 consecutive failed scheduled runs '
    + '(last 2026-10-02: weather data could not be fetched).';

  // Counter already reset to 0 elsewhere: only the reason marks it as auto-disabled.
  const AUTO_DISABLED_PLACE = {
    id: 7, name: 'Bamburgh Castle', lat: 55.6, lon: -1.7, enabled: false,
    consecutiveFailures: 0, lastFailureAt: '2026-10-02T03:00:00', disabledReason: REASON,
    solarEventType: ['SUNRISE', 'SUNSET'], locationType: ['LANDSCAPE'], tideType: [],
  };
  const RE_ENABLED_PLACE = {
    ...AUTO_DISABLED_PLACE, enabled: true, lastFailureAt: null, disabledReason: null,
  };

  beforeEach(() => {
    vi.clearAllMocks();
    fetchRegions.mockResolvedValue([]);
  });

  it('is alerted, reads as auto-disabled in the table, and Re-enable puts it back and clears the alert', async () => {
    fetchLocations.mockResolvedValueOnce([AUTO_DISABLED_PLACE]).mockResolvedValue([RE_ENABLED_PLACE]);
    resetLocationFailures.mockResolvedValue(undefined);
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    expect(await screen.findByTestId('location-alert-Bamburgh Castle')).toHaveTextContent(REASON);
    expect(screen.getByTestId('toggle-enabled-7')).toHaveTextContent('Disabled');
    expect(screen.getByTestId('auto-disabled-7')).toHaveTextContent('auto');

    fireEvent.click(screen.getByRole('button', { name: 'Re-enable Bamburgh Castle' }));

    await waitFor(() => {
      expect(screen.queryByTestId('location-alert-Bamburgh Castle')).toBeNull();
    });
    expect(resetLocationFailures).toHaveBeenCalledWith('Bamburgh Castle');
    expect(screen.getByTestId('toggle-enabled-7')).toHaveTextContent('Enabled');
    expect(screen.queryByTestId('auto-disabled-7')).toBeNull();
  });

  it('the auto badge\'s info popover carries the stored reason', async () => {
    fetchLocations.mockResolvedValue([AUTO_DISABLED_PLACE]);
    render(<LocationManagementView onLocationsChanged={() => {}} />);
    const badge = await screen.findByTestId('auto-disabled-7');

    fireEvent.click(within(badge).getByRole('button', { name: 'More info' }));

    expect(await screen.findByTestId('infotip-popover')).toHaveTextContent(REASON);
  });

  it('a place an admin disabled by hand (no reason) is not alerted and carries no auto-disabled badge', async () => {
    fetchLocations.mockResolvedValue([
      { ...AUTO_DISABLED_PLACE, consecutiveFailures: 0, lastFailureAt: null, disabledReason: null },
    ]);
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => {
      expect(screen.getByTestId('toggle-enabled-7')).toHaveTextContent('Disabled');
    });
    expect(screen.queryByTestId('location-alert-Bamburgh Castle')).toBeNull();
    expect(screen.queryByTestId('auto-disabled-7')).toBeNull();
  });
});

describe('LocationManagementView — heights in the inline editor', () => {
  const PLACE = {
    id: 9,
    name: 'Latrigg',
    lat: 54.6,
    lon: -3.1,
    enabled: true,
    solarEventType: ['SUNRISE', 'SUNSET'],
    locationType: ['LANDSCAPE'],
    tideType: [],
    overlooksWater: true,
    elevationMetres: 190,
    valleyFloorMetres: 80,
    valleyFloorLat: 54.58,
    valleyFloorLng: -3.12,
  };
  const BARE = {
    ...PLACE,
    id: 10,
    name: 'Bare',
    overlooksWater: false,
    elevationMetres: null,
    valleyFloorMetres: null,
    valleyFloorLat: null,
    valleyFloorLng: null,
  };
  const CANDIDATES = {
    siteSampleMetres: 190,
    candidates: [
      { metres: 60, lat: 54.57, lng: -3.15, distanceKm: 3.4, bearing: 'SW' },
      { metres: 100, lat: 54.61, lng: -3.05, distanceKm: 2.1, bearing: 'E' },
    ],
  };
  const CELL = { cellLat: 54.575, cellLng: -3.152, cellElevationMetres: 80.4, cellDistanceKm: 0.4 };

  beforeEach(() => {
    vi.clearAllMocks();
    fetchLocations.mockResolvedValue([PLACE, BARE]);
    fetchRegions.mockResolvedValue([]);
    fetchAreas.mockResolvedValue([]);
    fetchUncoveredLocations.mockResolvedValue([]);
    fetchLocationAliases.mockResolvedValue({});
    updateLocation.mockResolvedValue({});
  });

  async function openEditor(id = 9) {
    render(<LocationManagementView onLocationsChanged={() => {}} />);
    fireEvent.click(await screen.findByTestId(`edit-location-${id}`));
    return screen.findByTestId('inline-edit-elevation');
  }

  function save() {
    fireEvent.click(screen.getByTestId('inline-edit-save'));
  }

  it('an unchanged save sends none of the height keys', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('inline-edit-name'), { target: { value: 'Latrigg Fell' } });
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload).not.toHaveProperty('elevationMetres');
    expect(payload).not.toHaveProperty('valleyFloorMetres');
    expect(payload).not.toHaveProperty('valleyFloorLat');
    expect(payload).not.toHaveProperty('valleyFloorLng');
    expect(payload).not.toHaveProperty('clearValleyFloor');
  });

  it('shows the stored elevation and floor', async () => {
    const input = await openEditor();
    expect(input).toHaveValue(190);
    expect(screen.getByTestId('inline-edit-valley-floor-height')).toHaveValue(80);
    expect(screen.getByTestId('inline-edit-valley-floor')).toHaveTextContent('54.5800, -3.1200');
  });

  it('an elevation edit sends a rounded number', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('inline-edit-elevation'), { target: { value: '231.6' } });
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    expect(updateLocation.mock.calls[0][1].elevationMetres).toBe(232);
  });

  it('a new elevation on a place with none is sent', async () => {
    await openEditor(10);
    fireEvent.change(screen.getByTestId('inline-edit-elevation'), { target: { value: '45' } });
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    expect(updateLocation.mock.calls[0][1].elevationMetres).toBe(45);
  });

  it('emptying a stored elevation blocks Save with a message', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('inline-edit-elevation'), { target: { value: '' } });
    save();

    expect(await screen.findByTestId('inline-edit-error')).toHaveTextContent("Elevation can't be cleared; enter a value");
    expect(updateLocation).not.toHaveBeenCalled();
  });

  it('an out-of-range elevation blocks Save', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('inline-edit-elevation'), { target: { value: '1501' } });
    save();

    expect(await screen.findByTestId('inline-edit-error')).toHaveTextContent('between -50 and 1,500');
    expect(updateLocation).not.toHaveBeenCalled();
  });

  it('warns when an edit crosses 200 m on an overlooks-water site, and not otherwise', async () => {
    await openEditor();
    expect(screen.queryByTestId('inline-edit-elevation-warning')).toBeNull();

    fireEvent.change(screen.getByTestId('inline-edit-elevation'), { target: { value: '200' } });
    expect(screen.getByTestId('inline-edit-elevation-warning')).toHaveTextContent('eligible');

    fireEvent.change(screen.getByTestId('inline-edit-elevation'), { target: { value: '150' } });
    expect(screen.queryByTestId('inline-edit-elevation-warning')).toBeNull();
  });

  it('does not warn on a site that does not overlook water', async () => {
    await openEditor(10);
    fireEvent.change(screen.getByTestId('inline-edit-elevation'), { target: { value: '300' } });
    expect(screen.queryByTestId('inline-edit-elevation-warning')).toBeNull();
  });

  it('Suggest lists candidates for the EDITED coordinates and a pick fills the floor and fetches its cell', async () => {
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    fetchValleyFloorCell.mockResolvedValue(CELL);
    await openEditor();
    fireEvent.change(screen.getByTestId('inline-edit-lat'), { target: { value: '54.61' } });

    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-suggest'));

    const first = await screen.findByTestId('inline-edit-valley-floor-candidate-0');
    expect(fetchValleyFloorCandidates).toHaveBeenCalledWith(54.61, -3.1);
    expect(first).toHaveTextContent('60 m, 3.4 km SW');
    expect(screen.getByTestId('inline-edit-valley-floor-candidate-1')).toHaveTextContent('100 m, 2.1 km E');

    fireEvent.click(first);

    await waitFor(() => expect(fetchValleyFloorCell).toHaveBeenCalledWith(54.57, -3.15, 60));
    expect(screen.getByTestId('inline-edit-valley-floor-height')).toHaveValue(60);
    expect(await screen.findByTestId('inline-edit-valley-floor-cell'))
      .toHaveTextContent('Model cell 54.575, -3.152 (0.4 km from the floor)');

    save();
    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload.valleyFloorMetres).toBe(60);
    expect(payload.valleyFloorLat).toBe(54.57);
    expect(payload.valleyFloorLng).toBe(-3.15);
    expect(payload).not.toHaveProperty('clearValleyFloor');
  });

  it('editing the height keeps the floor coordinates and sends all three', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('inline-edit-valley-floor-height'), { target: { value: '95' } });
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload.valleyFloorMetres).toBe(95);
    expect(payload.valleyFloorLat).toBe(54.58);
    expect(payload.valleyFloorLng).toBe(-3.12);
  });

  it('says so when no candidate qualifies, and leaves the values alone', async () => {
    fetchValleyFloorCandidates.mockResolvedValue({ siteSampleMetres: 190, candidates: [] });
    await openEditor();

    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-suggest'));

    expect(await screen.findByTestId('inline-edit-valley-floor-status'))
      .toHaveTextContent('No valley floor 30 m below within 5 km');
    expect(screen.getByTestId('inline-edit-valley-floor-height')).toHaveValue(80);
  });

  it('shows the server message when the lookup fails, and leaves the values alone', async () => {
    fetchValleyFloorCandidates.mockRejectedValue({ response: { data: { error: 'Could not look up the surrounding heights' } } });
    await openEditor();

    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-suggest'));

    expect(await screen.findByTestId('inline-edit-valley-floor-status'))
      .toHaveTextContent('Could not look up the surrounding heights');
    expect(screen.getByTestId('inline-edit-valley-floor-height')).toHaveValue(80);
  });

  it('Suggest is disabled while the edited coordinates are invalid', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('inline-edit-lat'), { target: { value: '95' } });
    expect(screen.getByTestId('inline-edit-valley-floor-suggest')).toBeDisabled();
    fireEvent.change(screen.getByTestId('inline-edit-lat'), { target: { value: '54.6' } });
    expect(screen.getByTestId('inline-edit-valley-floor-suggest')).not.toBeDisabled();
  });

  it('a reply that lands after Cancel is ignored by the next editor', async () => {
    let resolve;
    fetchValleyFloorCandidates.mockReturnValue(new Promise((r) => { resolve = r; }));
    await openEditor();
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-suggest'));
    expect(screen.getByTestId('inline-edit-save')).toBeDisabled();

    fireEvent.click(screen.getByTestId('inline-edit-cancel'));
    fireEvent.click(screen.getByTestId('edit-location-9'));
    await screen.findByTestId('inline-edit-elevation');
    resolve(CANDIDATES);
    await new Promise((r) => setTimeout(r, 0));

    expect(screen.queryByTestId('inline-edit-valley-floor-candidate-0')).toBeNull();
    expect(screen.getByTestId('inline-edit-save')).not.toBeDisabled();
  });

  it('a reply for one row is ignored after switching to another', async () => {
    let resolve;
    fetchValleyFloorCandidates.mockReturnValue(new Promise((r) => { resolve = r; }));
    await openEditor();
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-suggest'));

    fireEvent.click(screen.getByTestId('edit-location-10'));
    await waitFor(() => expect(screen.getByTestId('inline-edit-elevation')).toHaveValue(null));
    resolve(CANDIDATES);
    await new Promise((r) => setTimeout(r, 0));

    expect(screen.queryByTestId('inline-edit-valley-floor-candidate-0')).toBeNull();
  });

  it('Clear on a stored floor sends clearValleyFloor and no values', async () => {
    await openEditor();
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-clear'));
    expect(screen.getByTestId('inline-edit-valley-floor-none')).toBeInTheDocument();
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload.clearValleyFloor).toBe(true);
    expect(payload).not.toHaveProperty('valleyFloorMetres');
    expect(payload).not.toHaveProperty('valleyFloorLat');
  });

  it('Clear on a picked-but-unsaved floor sends nothing about the floor', async () => {
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    fetchValleyFloorCell.mockResolvedValue(CELL);
    await openEditor(10);
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-suggest'));
    fireEvent.click(await screen.findByTestId('inline-edit-valley-floor-candidate-0'));
    await screen.findByTestId('inline-edit-valley-floor-cell');
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-clear'));
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload).not.toHaveProperty('clearValleyFloor');
    expect(payload).not.toHaveProperty('valleyFloorMetres');
  });

  it('a pick after Clear sends the new floor and no clear flag', async () => {
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    fetchValleyFloorCell.mockResolvedValue(CELL);
    await openEditor();
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-clear'));
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-suggest'));
    fireEvent.click(await screen.findByTestId('inline-edit-valley-floor-candidate-1'));
    await screen.findByTestId('inline-edit-valley-floor-cell');
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload).not.toHaveProperty('clearValleyFloor');
    expect(payload.valleyFloorMetres).toBe(100);
  });

  it('shows the coordinate hint only while the coordinates differ from the stored ones', async () => {
    await openEditor();
    expect(screen.queryByTestId('inline-edit-coords-hint')).toBeNull();

    fireEvent.change(screen.getByTestId('inline-edit-lon'), { target: { value: '-3.2' } });
    expect(screen.getByTestId('inline-edit-coords-hint'))
      .toHaveTextContent('Coordinates changed: check elevation and valley floor');

    fireEvent.change(screen.getByTestId('inline-edit-lon'), { target: { value: '-3.1' } });
    expect(screen.queryByTestId('inline-edit-coords-hint')).toBeNull();
  });

  it('warns when an edit crosses 200 m downwards on an overlooks-water site', async () => {
    fetchLocations.mockResolvedValue([{ ...PLACE, elevationMetres: 210 }, BARE]);
    await openEditor();
    expect(screen.queryByTestId('inline-edit-elevation-warning')).toBeNull();

    fireEvent.change(screen.getByTestId('inline-edit-elevation'), { target: { value: '190' } });

    expect(screen.getByTestId('inline-edit-elevation-warning')).toHaveTextContent('out of inversion scoring');
  });

  it('a rejected model-cell lookup sets the status and keeps the pick', async () => {
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    fetchValleyFloorCell.mockRejectedValue({ response: { data: { error: 'no cell' } } });
    await openEditor();
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-suggest'));
    fireEvent.click(await screen.findByTestId('inline-edit-valley-floor-candidate-0'));

    expect(await screen.findByTestId('inline-edit-valley-floor-status')).toHaveTextContent('no cell');
    expect(screen.getByTestId('inline-edit-valley-floor-height')).toHaveValue(60);
    expect(screen.queryByTestId('inline-edit-valley-floor-cell')).toBeNull();
  });

  it('Save is disabled while a pick\'s cell lookup is running', async () => {
    let resolve;
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    fetchValleyFloorCell.mockReturnValue(new Promise((r) => { resolve = r; }));
    await openEditor();
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-suggest'));
    fireEvent.click(await screen.findByTestId('inline-edit-valley-floor-candidate-0'));

    expect(screen.getByTestId('inline-edit-save')).toBeDisabled();
    resolve(CELL);
    await screen.findByTestId('inline-edit-valley-floor-cell');
    expect(screen.getByTestId('inline-edit-save')).not.toBeDisabled();
  });

  it('an empty floor height blocks Save', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('inline-edit-valley-floor-height'), { target: { value: '' } });
    save();

    expect(await screen.findByTestId('inline-edit-error')).toHaveTextContent('Valley floor height');
    expect(updateLocation).not.toHaveBeenCalled();
  });

  it('editing the floor height drops a cell reply still in flight for the old height', async () => {
    let resolve;
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    fetchValleyFloorCell.mockReturnValue(new Promise((r) => { resolve = r; }));
    await openEditor();
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-suggest'));
    fireEvent.click(await screen.findByTestId('inline-edit-valley-floor-candidate-0'));
    fireEvent.change(screen.getByTestId('inline-edit-valley-floor-height'), { target: { value: '70' } });
    resolve(CELL);
    await new Promise((r) => setTimeout(r, 0));

    expect(screen.queryByTestId('inline-edit-valley-floor-cell')).toBeNull();
  });

  it('editing the coordinates clears candidates fetched for the old ones', async () => {
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    await openEditor();
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-suggest'));
    await screen.findByTestId('inline-edit-valley-floor-candidate-0');

    fireEvent.change(screen.getByTestId('inline-edit-lon'), { target: { value: '-3.2' } });

    expect(screen.queryByTestId('inline-edit-valley-floor-candidate-0')).toBeNull();
  });

  it('after a save whose other-names write fails, the heights and coordinates just saved are the new baselines', async () => {
    setLocationAliases.mockRejectedValue({ response: { data: { error: 'alias clash' } } });
    await openEditor();
    await waitFor(() => expect(screen.getByTestId('inline-edit-aliases')).not.toBeDisabled());
    fireEvent.change(screen.getByTestId('inline-edit-elevation'), { target: { value: '250' } });
    fireEvent.click(screen.getByTestId('inline-edit-valley-floor-clear'));
    fireEvent.change(screen.getByTestId('inline-edit-lon'), { target: { value: '-3.2' } });
    fireEvent.change(screen.getByTestId('inline-edit-aliases'), { target: { value: 'The Fell' } });
    save();

    expect(await screen.findByTestId('inline-edit-error')).toHaveTextContent('Location saved; its other names were not');
    const first = updateLocation.mock.calls[0][1];
    expect(first.elevationMetres).toBe(250);
    expect(first.clearValleyFloor).toBe(true);
    // The saved values are now the baseline: no hint, no warning, nothing re-sent.
    expect(screen.queryByTestId('inline-edit-coords-hint')).toBeNull();
    expect(screen.queryByTestId('inline-edit-elevation-warning')).toBeNull();

    save();
    await waitFor(() => expect(updateLocation).toHaveBeenCalledTimes(2));
    const second = updateLocation.mock.calls[1][1];
    expect(second).not.toHaveProperty('elevationMetres');
    expect(second).not.toHaveProperty('clearValleyFloor');
  });
});

describe('LocationManagementView — tide point in the inline editor', () => {
  const COAST = {
    id: 21,
    name: 'Cullercoats',
    lat: 55.03,
    lon: -1.43,
    enabled: true,
    solarEventType: ['SUNRISE', 'SUNSET'],
    locationType: ['SEASCAPE'],
    tideType: ['HIGH'],
    tideLat: 55.0,
    tideLng: -1.4,
  };
  const COAST_BARE = { ...COAST, id: 22, name: 'Plain Coast', tideLat: null, tideLng: null };
  const INLAND = { ...COAST, id: 23, name: 'Inland', locationType: ['LANDSCAPE'], tideType: [], tideLat: null, tideLng: null };

  const COAST_MIXED = { ...COAST, id: 24, name: 'Mixed Coast', locationType: ['SEASCAPE', 'LANDSCAPE'] };

  beforeEach(() => {
    vi.clearAllMocks();
    fetchLocations.mockResolvedValue([COAST, COAST_BARE, INLAND, COAST_MIXED]);
    fetchRegions.mockResolvedValue([]);
    fetchAreas.mockResolvedValue([]);
    fetchUncoveredLocations.mockResolvedValue([]);
    fetchLocationAliases.mockResolvedValue({});
    updateLocation.mockResolvedValue({});
  });

  async function openEditor(id) {
    render(<LocationManagementView onLocationsChanged={() => {}} />);
    fireEvent.click(await screen.findByTestId(`edit-location-${id}`));
    return screen.findByTestId('inline-edit-name');
  }

  function save() {
    fireEvent.click(screen.getByTestId('inline-edit-save'));
  }

  it('fills the pair from the DTO and shows the explanation', async () => {
    await openEditor(21);
    expect(screen.getByTestId('inline-edit-tide-lat')).toHaveValue(55);
    expect(screen.getByTestId('inline-edit-tide-lng')).toHaveValue(-1.4);
    expect(screen.getByTestId('inline-edit-tide-point-help'))
      .toHaveTextContent("Where tide times are read from, when the pin's own sea area is unreliable");
  });

  it('is hidden for a location that is not coastal', async () => {
    await openEditor(23);
    expect(screen.queryByTestId('inline-edit-tide-point')).toBeNull();
  });

  it('an unchanged save sends no tide-point keys', async () => {
    await openEditor(21);
    fireEvent.change(screen.getByTestId('inline-edit-name'), { target: { value: 'Cullercoats Bay' } });
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload).not.toHaveProperty('tideLat');
    expect(payload).not.toHaveProperty('tideLng');
    expect(payload).not.toHaveProperty('clearTidePoint');
  });

  it('sets a new tide point as a number pair', async () => {
    await openEditor(22);
    fireEvent.change(screen.getByTestId('inline-edit-tide-lat'), { target: { value: '55.01' } });
    fireEvent.change(screen.getByTestId('inline-edit-tide-lng'), { target: { value: '-1.41' } });
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload.tideLat).toBe(55.01);
    expect(payload.tideLng).toBe(-1.41);
    expect(payload).not.toHaveProperty('clearTidePoint');
  });

  it('Clear on a stored point sends clearTidePoint and no values', async () => {
    await openEditor(21);
    fireEvent.click(screen.getByTestId('inline-edit-tide-point-clear'));
    expect(screen.getByTestId('inline-edit-tide-lat')).toHaveValue(null);
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload.clearTidePoint).toBe(true);
    expect(payload).not.toHaveProperty('tideLat');
    expect(payload).not.toHaveProperty('tideLng');
  });

  it('Clear is disabled when there is no point, and a blank pair sends nothing', async () => {
    await openEditor(22);
    expect(screen.getByTestId('inline-edit-tide-point-clear')).toBeDisabled();
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    expect(updateLocation.mock.calls[0][1]).not.toHaveProperty('clearTidePoint');
  });

  it('refuses a half-filled pair and an out-of-range value without saving', async () => {
    await openEditor(22);
    fireEvent.change(screen.getByTestId('inline-edit-tide-lat'), { target: { value: '55.01' } });
    save();
    expect(await screen.findByTestId('inline-edit-error')).toHaveTextContent('needs both latitude and longitude');

    fireEvent.change(screen.getByTestId('inline-edit-tide-lng'), { target: { value: '-181' } });
    save();
    await waitFor(() => expect(screen.getByTestId('inline-edit-error')).toHaveTextContent('between -90 and 90'));
    expect(updateLocation).not.toHaveBeenCalled();
  });

  it('shows the server message when it rejects the point', async () => {
    updateLocation.mockRejectedValue({ response: { data: { error: 'The tide point must be within 25 km of the location' } } });
    await openEditor(22);
    fireEvent.change(screen.getByTestId('inline-edit-tide-lat'), { target: { value: '56' } });
    fireEvent.change(screen.getByTestId('inline-edit-tide-lng'), { target: { value: '-1.4' } });
    save();

    expect(await screen.findByTestId('inline-edit-error')).toHaveTextContent('within 25 km');
  });

  it('sends no tide keys and is not blocked when SEASCAPE is unticked with a half-blanked point', async () => {
    await openEditor(24);
    fireEvent.change(screen.getByTestId('inline-edit-tide-lat'), { target: { value: '' } });
    fireEvent.click(within(screen.getByTestId('inline-edit-type')).getByTestId('type-chip-SEASCAPE'));
    expect(screen.queryByTestId('inline-edit-tide-point')).toBeNull();
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload).not.toHaveProperty('tideLat');
    expect(payload).not.toHaveProperty('clearTidePoint');
  });

  it('after an alias-write failure the saved tide point is the baseline and a second save resends nothing', async () => {
    setLocationAliases.mockRejectedValue({ response: { data: { error: 'alias clash' } } });
    await openEditor(22);
    await waitFor(() => expect(screen.getByTestId('inline-edit-aliases')).not.toBeDisabled());
    fireEvent.change(screen.getByTestId('inline-edit-tide-lat'), { target: { value: '55.01' } });
    fireEvent.change(screen.getByTestId('inline-edit-tide-lng'), { target: { value: '-1.41' } });
    fireEvent.change(screen.getByTestId('inline-edit-aliases'), { target: { value: 'The Bay' } });
    save();

    expect(await screen.findByTestId('inline-edit-error')).toHaveTextContent('Location saved; its other names were not');
    expect(updateLocation.mock.calls[0][1].tideLat).toBe(55.01);

    save();
    await waitFor(() => expect(updateLocation).toHaveBeenCalledTimes(2));
    const second = updateLocation.mock.calls[1][1];
    expect(second).not.toHaveProperty('tideLat');
    expect(second).not.toHaveProperty('clearTidePoint');
  });
});
