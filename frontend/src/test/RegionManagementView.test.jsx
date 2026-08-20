import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import RegionManagementView from '../components/RegionManagementView.jsx';

vi.mock('../api/regionApi', () => ({
  fetchRegions: vi.fn(),
  addRegion: vi.fn(),
  updateRegion: vi.fn(),
  setRegionEnabled: vi.fn(),
  setRegionBase: vi.fn(),
}));

vi.mock('../api/forecastApi', () => ({
  fetchLocations: vi.fn(),
}));

import {
  fetchRegions, addRegion, updateRegion, setRegionEnabled, setRegionBase,
} from '../api/regionApi';
import { fetchLocations } from '../api/forecastApi';

const MOCK_REGIONS = [
  { id: 1, name: 'North East', enabled: true, createdAt: '2026-01-15T10:00:00' },
  { id: 2, name: 'Lake District', enabled: true, createdAt: '2026-02-01T10:00:00' },
  { id: 3, name: 'Scotland', enabled: false, createdAt: '2026-03-01T10:00:00' },
];

const MOCK_LOCATIONS = [
  { id: 1, name: 'Durham', region: { id: 1, name: 'North East' } },
  { id: 2, name: 'Bamburgh', region: { id: 1, name: 'North East' } },
  { id: 3, name: 'Keswick', region: { id: 2, name: 'Lake District' } },
  { id: 4, name: 'No Region', region: null },
];

function makeMockRegions(count) {
  return Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    name: `Region ${i + 1}`,
    enabled: true,
    createdAt: '2026-01-01T10:00:00',
  }));
}

describe('RegionManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchRegions.mockResolvedValue(MOCK_REGIONS);
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);
  });

  it('renders region list with names', async () => {
    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByText('North East')).toBeInTheDocument();
    });
    expect(screen.getByText('Lake District')).toBeInTheDocument();
    expect(screen.getByText('Scotland')).toBeInTheDocument();
  });

  it('shows location count per region', async () => {
    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('region-location-count-1')).toHaveTextContent('2');
    });
    expect(screen.getByTestId('region-location-count-2')).toHaveTextContent('1');
    expect(screen.getByTestId('region-location-count-3')).toHaveTextContent('0');
  });

  it('shows 0 for regions with no locations', async () => {
    fetchLocations.mockResolvedValue([]);

    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('region-location-count-1')).toHaveTextContent('0');
    });
    expect(screen.getByTestId('region-location-count-2')).toHaveTextContent('0');
  });

  it('shows Add New Region form when button clicked', async () => {
    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('add-region-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-region-btn'));

    expect(screen.getByText('Add New Region')).toBeInTheDocument();
    expect(screen.getByTestId('region-name-input')).toBeInTheDocument();
  });

  it('calls addRegion on save in add mode', async () => {
    addRegion.mockResolvedValue({});

    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('add-region-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-region-btn'));
    fireEvent.change(screen.getByTestId('region-name-input'), { target: { value: 'Yorkshire' } });
    fireEvent.click(screen.getByTestId('save-region-btn'));

    await waitFor(() => {
      expect(addRegion).toHaveBeenCalledWith({ name: 'Yorkshire' });
    });
  });

  it('shows edit form with current name when Edit clicked', async () => {
    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-region-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-region-1'));

    expect(screen.getByText('Edit Region: North East')).toBeInTheDocument();
    expect(screen.getByTestId('region-name-input')).toHaveValue('North East');
  });

  it('calls updateRegion on save in edit mode', async () => {
    updateRegion.mockResolvedValue({});

    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('edit-region-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('edit-region-1'));
    fireEvent.change(screen.getByTestId('region-name-input'), { target: { value: 'North East England' } });
    fireEvent.click(screen.getByTestId('save-region-btn'));

    await waitFor(() => {
      expect(updateRegion).toHaveBeenCalledWith(1, { name: 'North East England' });
    });
  });

  it('disables save button when name is blank', async () => {
    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('add-region-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-region-btn'));
    fireEvent.change(screen.getByTestId('region-name-input'), { target: { value: '   ' } });

    expect(screen.getByTestId('save-region-btn')).toBeDisabled();
  });

  it('Cancel returns to list mode', async () => {
    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('add-region-btn')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('add-region-btn'));
    expect(screen.getByText('Add New Region')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Cancel'));

    expect(screen.getByText('Region Management')).toBeInTheDocument();
    expect(screen.getByTestId('regions-table')).toBeInTheDocument();
  });

  it('shows empty state when no regions', async () => {
    fetchRegions.mockResolvedValue([]);

    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByText('No regions configured. Add one to get started.')).toBeInTheDocument();
    });
  });

  it('toggle enabled calls setRegionEnabled', async () => {
    setRegionEnabled.mockResolvedValue({});

    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('toggle-region-enabled-1')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId('toggle-region-enabled-1'));

    await waitFor(() => {
      expect(setRegionEnabled).toHaveBeenCalledWith(1, false);
    });
  });

  // --- Pagination tests ---

  it('paginates regions when more than page size', async () => {
    const manyRegions = makeMockRegions(15);
    fetchRegions.mockResolvedValue(manyRegions);

    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('pagination')).toBeInTheDocument();
    });

    // Alphabetical sort: Region 1, 10, 11, 12, 13, 14, 15, 2, 3, 4 on page 1
    expect(screen.getByText('Region 1')).toBeInTheDocument();
    expect(screen.getByText('Region 4')).toBeInTheDocument();
    expect(screen.queryByText('Region 5')).not.toBeInTheDocument();
    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('Showing 1-10 of 15');

    // Navigate to page 2
    fireEvent.click(screen.getByTestId('pagination-next'));

    await waitFor(() => {
      expect(screen.getByText('Region 5')).toBeInTheDocument();
    });
    expect(screen.queryByText('Region 1')).not.toBeInTheDocument();
    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('Showing 11-15 of 15');
  });

  it('hides pagination when all regions fit on one page', async () => {
    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByText('North East')).toBeInTheDocument();
    });

    expect(screen.queryByTestId('pagination')).not.toBeInTheDocument();
  });

  it('spacer rows fill partial last page', async () => {
    const manyRegions = makeMockRegions(12);
    fetchRegions.mockResolvedValue(manyRegions);

    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('pagination')).toBeInTheDocument();
    });

    // Navigate to page 2 (2 data rows + 8 spacers)
    fireEvent.click(screen.getByTestId('pagination-next'));

    await waitFor(() => {
      expect(screen.getByTestId('pagination-summary')).toHaveTextContent('Showing 11-12 of 12');
    });

    const table = screen.getByTestId('regions-table');
    const rows = table.querySelectorAll('tbody tr');
    // 2 data rows + 8 spacer rows = 10
    expect(rows.length).toBe(10);
  });

  it('page size change works', async () => {
    const manyRegions = makeMockRegions(30);
    fetchRegions.mockResolvedValue(manyRegions);

    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('pagination')).toBeInTheDocument();
    });

    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('Showing 1-10 of 30');

    // Change to 25 per page
    fireEvent.click(screen.getByTestId('pagination-size-25'));

    await waitFor(() => {
      expect(screen.getByTestId('pagination-summary')).toHaveTextContent('Showing 1-25 of 30');
    });
  });

  // --- Sorting tests ---

  it('sorting by name toggles direction', async () => {
    render(<RegionManagementView />);

    await waitFor(() => {
      expect(screen.getByText('North East')).toBeInTheDocument();
    });

    // Default: name ascending — Lake District, North East, Scotland
    const rows = screen.getByTestId('regions-table').querySelectorAll('tbody tr');
    expect(rows[0]).toHaveTextContent('Lake District');
    expect(rows[1]).toHaveTextContent('North East');
    expect(rows[2]).toHaveTextContent('Scotland');

    // Click name header to reverse
    fireEvent.click(screen.getByText('Name ▲'));

    const rowsDesc = screen.getByTestId('regions-table').querySelectorAll('tbody tr');
    expect(rowsDesc[0]).toHaveTextContent('Scotland');
    expect(rowsDesc[1]).toHaveTextContent('North East');
    expect(rowsDesc[2]).toHaveTextContent('Lake District');
  });
});

/**
 * Region bases — the origin the Plan tab can plan from (heat-field plan §4.8, P7).
 *
 * <p><b>What breaks if these fail.</b> Two things, and the second is destructive. A base that is
 * half-saved cannot be routed from, so the region silently stops being an origin with nothing
 * saying why. And a base save that fires on an unchanged form throws away that region's whole
 * shared drive-time matrix — an ORS sweep — every time an admin re-saves a name.
 */
describe('RegionManagementView — the base town', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchRegions.mockResolvedValue([
      {
        id: 1, name: 'Lake District', enabled: true, createdAt: '2026-01-15T10:00:00',
        baseName: 'Keswick', baseLat: 54.6013, baseLon: -3.1347,
      },
      {
        id: 2, name: 'North East', enabled: true, createdAt: '2026-02-01T10:00:00',
        baseName: null, baseLat: null, baseLon: null,
      },
    ]);
    fetchLocations.mockResolvedValue([]);
    updateRegion.mockResolvedValue({});
    setRegionBase.mockResolvedValue({});
  });

  const openEdit = async (id) => {
    fireEvent.click(await screen.findByTestId(`edit-region-${id}`));
  };

  it('lists each region\'s base, and an em dash where there is none', async () => {
    render(<RegionManagementView />);
    expect(await screen.findByTestId('region-base-1')).toHaveTextContent('Keswick');
    expect(screen.getByTestId('region-base-2')).toHaveTextContent('—');
  });

  it('loads the stored base into the edit form', async () => {
    render(<RegionManagementView />);
    await openEdit(1);
    expect(screen.getByTestId('region-base-name-input')).toHaveValue('Keswick');
    expect(screen.getByTestId('region-base-lat-input')).toHaveValue('54.6013');
    expect(screen.getByTestId('region-base-lon-input')).toHaveValue('-3.1347');
  });

  it('opens empty for a region with no base', async () => {
    render(<RegionManagementView />);
    await openEdit(2);
    expect(screen.getByTestId('region-base-name-input')).toHaveValue('');
    expect(screen.getByTestId('region-base-lat-input')).toHaveValue('');
  });

  it('offers no base fields when ADDING — a region is named first and based afterwards', async () => {
    render(<RegionManagementView />);
    fireEvent.click(await screen.findByTestId('add-region-btn'));
    expect(screen.queryByTestId('region-base-fields')).toBeNull();
  });

  it('saves a new base through its own endpoint, with the coordinates as numbers', async () => {
    render(<RegionManagementView />);
    await openEdit(2);
    fireEvent.change(screen.getByTestId('region-base-name-input'), { target: { value: 'Alnwick' } });
    fireEvent.change(screen.getByTestId('region-base-lat-input'), { target: { value: '55.4137' } });
    fireEvent.change(screen.getByTestId('region-base-lon-input'), { target: { value: '-1.7060' } });
    fireEvent.click(screen.getByTestId('save-region-btn'));

    await waitFor(() => expect(setRegionBase).toHaveBeenCalledWith(2, {
      baseName: 'Alnwick', baseLat: 55.4137, baseLon: -1.706,
    }));
  });

  it('⚠️ does NOT touch the base when only the name was edited', async () => {
    // Every base save discards that region's whole drive-time matrix. Re-saving a region name must
    // not cost an ORS sweep.
    render(<RegionManagementView />);
    await openEdit(1);
    fireEvent.change(screen.getByTestId('region-name-input'), { target: { value: 'The Lakes' } });
    fireEvent.click(screen.getByTestId('save-region-btn'));

    await waitFor(() => expect(updateRegion).toHaveBeenCalledWith(1, { name: 'The Lakes' }));
    expect(setRegionBase).not.toHaveBeenCalled();
  });

  it('clears the base when all three fields are emptied', async () => {
    render(<RegionManagementView />);
    await openEdit(1);
    fireEvent.change(screen.getByTestId('region-base-name-input'), { target: { value: '' } });
    fireEvent.change(screen.getByTestId('region-base-lat-input'), { target: { value: '' } });
    fireEvent.change(screen.getByTestId('region-base-lon-input'), { target: { value: '' } });
    fireEvent.click(screen.getByTestId('save-region-btn'));

    await waitFor(() => expect(setRegionBase).toHaveBeenCalledWith(1, {
      baseName: null, baseLat: null, baseLon: null,
    }));
  });

  it('does not send a clear for a region that had no base to start with', async () => {
    render(<RegionManagementView />);
    await openEdit(2);
    fireEvent.change(screen.getByTestId('region-name-input'), { target: { value: 'The North East' } });
    fireEvent.click(screen.getByTestId('save-region-btn'));

    await waitFor(() => expect(updateRegion).toHaveBeenCalled());
    expect(setRegionBase).not.toHaveBeenCalled();
  });

  it('⚠️ refuses a partial base beside the fields, rather than as a 400 after a round trip', async () => {
    render(<RegionManagementView />);
    await openEdit(2);
    fireEvent.change(screen.getByTestId('region-base-name-input'), { target: { value: 'Alnwick' } });
    fireEvent.click(screen.getByTestId('save-region-btn'));

    expect(await screen.findByText(/needs a name, a latitude and a longitude/i)).toBeInTheDocument();
    expect(setRegionBase).not.toHaveBeenCalled();
    expect(updateRegion).not.toHaveBeenCalled();
  });

  it('refuses a coordinate that is not a number', async () => {
    render(<RegionManagementView />);
    await openEdit(2);
    fireEvent.change(screen.getByTestId('region-base-name-input'), { target: { value: 'Alnwick' } });
    fireEvent.change(screen.getByTestId('region-base-lat-input'), { target: { value: 'north' } });
    fireEvent.change(screen.getByTestId('region-base-lon-input'), { target: { value: '-1.7' } });
    fireEvent.click(screen.getByTestId('save-region-btn'));

    expect(await screen.findByText(/needs a name, a latitude and a longitude/i)).toBeInTheDocument();
    expect(setRegionBase).not.toHaveBeenCalled();
  });

  it('⚠️ re-reads the list when the base call fails after the rename committed', async () => {
    // The save is two calls. The backend bounds-checks coordinates and this form does not, so the
    // rename can land and the base can 400 — leaving the table showing the old name for a rename
    // that has already persisted, under an error about something else.
    setRegionBase.mockRejectedValue({ response: { data: { error: 'Base coordinates are out of range' } } });
    render(<RegionManagementView />);
    await openEdit(2);
    fireEvent.change(screen.getByTestId('region-name-input'), { target: { value: 'The North East' } });
    fireEvent.change(screen.getByTestId('region-base-name-input'), { target: { value: 'Alnwick' } });
    fireEvent.change(screen.getByTestId('region-base-lat-input'), { target: { value: '545.4' } });
    fireEvent.change(screen.getByTestId('region-base-lon-input'), { target: { value: '-1.7' } });
    fireEvent.click(screen.getByTestId('save-region-btn'));

    expect(await screen.findByText(/out of range/i)).toBeInTheDocument();
    // Twice: the mount, and the re-read after the failure.
    await waitFor(() => expect(fetchRegions).toHaveBeenCalledTimes(2));
    // And the form stays open on the field that failed, rather than dropping the reader back to a
    // list whose state they cannot explain.
    expect(screen.getByTestId('region-base-lat-input')).toHaveValue('545.4');
  });

  it('keeps a base at zero longitude — Greenwich is a place, and `||` would blank it', async () => {
    fetchRegions.mockResolvedValue([{
      id: 1, name: 'Home Counties', enabled: true, createdAt: '2026-01-15T10:00:00',
      baseName: 'Greenwich', baseLat: 51.4826, baseLon: 0,
    }]);
    render(<RegionManagementView />);
    await openEdit(1);
    expect(screen.getByTestId('region-base-lon-input')).toHaveValue('0');
  });
});
