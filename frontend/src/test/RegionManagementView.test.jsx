import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import RegionManagementView from '../components/RegionManagementView.jsx';

vi.mock('../api/regionApi', () => ({
  fetchRegions: vi.fn(),
  addRegion: vi.fn(),
  updateRegion: vi.fn(),
  setRegionEnabled: vi.fn(),
}));

vi.mock('../api/forecastApi', () => ({
  fetchLocations: vi.fn(),
}));

import { fetchRegions, addRegion, updateRegion, setRegionEnabled } from '../api/regionApi';
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
