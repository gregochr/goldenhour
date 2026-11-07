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
  resetLocationFailures: vi.fn(),
  fetchValleyFloorCandidates: vi.fn(),
  fetchValleyFloorCell: vi.fn(),
}));
vi.mock('../api/regionApi', () => ({ fetchRegions: vi.fn() }));
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
vi.mock('../api/tideApi', () => ({ fetchTideStats: vi.fn(), fetchAllTideStats: vi.fn() }));
vi.mock('../api/driveTimesAdminApi', () => ({ fetchUnroutableLocations: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn(() => false) }));

import { fetchLocations, setLocationEnabled } from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';
import { fetchAreas, fetchUncoveredLocations, fetchLocationAliases } from '../api/areaApi';
import { fetchTideStats } from '../api/tideApi';
import { fetchUnroutableLocations } from '../api/driveTimesAdminApi';

const BASE = {
  enabled: true, solarEventType: ['SUNRISE', 'SUNSET'], tideType: [], consecutiveFailures: 0, locationType: ['LANDSCAPE'],
  region: null, subRegionName: null, bortleClass: null,
};
const HELVELLYN = { ...BASE, id: 1, name: 'Helvellyn', lat: 54.527, lon: -3.016 };
const KESWICK = { ...BASE, id: 2, name: 'Keswick', lat: 54.6, lon: -3.13 };
const FLAG = {
  locationId: 1, name: 'Helvellyn', unroutableHomes: 3, judgedHomes: 3, unroutableBases: 2, judgedBases: 4,
};

async function renderLoaded() {
  render(<LocationManagementView onLocationsChanged={() => {}} />);
  await screen.findByTestId('locations');
}

describe('Locations — the "Needs a drive point" flag (admin only)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    fetchLocations.mockResolvedValue([HELVELLYN, KESWICK]);
    fetchRegions.mockResolvedValue([]);
    fetchAreas.mockResolvedValue([]);
    fetchUncoveredLocations.mockResolvedValue([]);
    fetchLocationAliases.mockResolvedValue({});
    fetchTideStats.mockResolvedValue(null);
  });

  it('flags a place with no route in the list and in its panel, and no other place', async () => {
    fetchUnroutableLocations.mockResolvedValue([FLAG]);

    await renderLoaded();

    expect(await screen.findByTestId('location-drive-flag-1'))
      .toHaveTextContent('Needs a drive point · no route from 3 of 3 homes, 2 of 4 bases');
    expect(screen.queryByTestId('location-drive-flag-2')).toBeNull();

    fireEvent.click(screen.getByTestId('locations-row-1'));
    expect(await screen.findByTestId('location-drive-flag-text'))
      .toHaveTextContent('Needs a drive point · no route from 3 of 3 homes, 2 of 4 bases');
    // A partial case must not be described as "none": only some origins could not route there.
    const section = screen.getByTestId('location-drive-flag');
    expect(section).toHaveTextContent('some of them could not be routed there');
    expect(section).not.toHaveTextContent(/none could/i);
  });

  it('shows no drive section on the panel of a place that is not flagged', async () => {
    fetchUnroutableLocations.mockResolvedValue([FLAG]);

    await renderLoaded();
    await screen.findByTestId('location-drive-flag-1');
    fireEvent.click(screen.getByTestId('locations-row-2'));

    await screen.findByTestId('location-view-panel');
    expect(screen.queryByTestId('location-drive-flag')).toBeNull();
  });

  it('flags nothing when the read fails, and the list still renders', async () => {
    fetchUnroutableLocations.mockRejectedValue(new Error('403'));

    await renderLoaded();

    expect(await screen.findByTestId('locations-row-1')).toBeInTheDocument();
    expect(screen.queryByTestId('location-drive-flag-1')).toBeNull();
  });

  it('re-reads the flag after a toggle succeeds, so a fixed place stops being flagged', async () => {
    fetchUnroutableLocations.mockResolvedValueOnce([FLAG]).mockResolvedValue([]);
    setLocationEnabled.mockResolvedValue({ ...HELVELLYN, enabled: false });

    await renderLoaded();
    await screen.findByTestId('location-drive-flag-1');
    fireEvent.click(screen.getByTestId('locations-row-1'));
    fireEvent.click(await screen.findByTestId('toggle-enabled-1'));

    await waitFor(() => expect(screen.queryByTestId('location-drive-flag-1')).toBeNull());
    expect(fetchUnroutableLocations).toHaveBeenCalledTimes(2);
  });
});
