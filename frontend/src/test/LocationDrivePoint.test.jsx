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
vi.mock('../api/driveTimesAdminApi', () => ({
  fetchUnroutableLocations: vi.fn(),
  fetchDrivePointPlaces: vi.fn(),
}));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn(() => false) }));

import { fetchLocations, updateLocation, addLocation } from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';
import { fetchAreas, fetchUncoveredLocations, fetchLocationAliases } from '../api/areaApi';
import { fetchTideStats } from '../api/tideApi';
import { fetchUnroutableLocations, fetchDrivePointPlaces } from '../api/driveTimesAdminApi';

const BASE = {
  enabled: true, solarEventType: ['SUNRISE', 'SUNSET'], tideType: [], consecutiveFailures: 0,
  locationType: ['LANDSCAPE'], region: null, subRegionName: null, bortleClass: null,
};
const HELVELLYN = {
  ...BASE, id: 1, name: 'Helvellyn', lat: 54.527, lon: -3.016,
  drivePointName: 'Glenridding', drivePointLat: 54.5442, drivePointLon: -2.9505,
};
const KESWICK = { ...BASE, id: 2, name: 'Keswick', lat: 54.6, lon: -3.13 };
const GLENRIDDING = { name: 'Glenridding', detail: 'Village, Cumberland', lat: 54.5442, lon: -2.9505, distanceKm: 4.6 };
const NO_ROUTE = {
  response: {
    status: 422,
    data: {
      error: 'No road route reaches this point. Add a drive point, e.g. the nearest village or car park.',
      code: 'NO_ROAD_ROUTE',
      field: 'drivePoint',
    },
  },
};

function renderView() {
  return render(<LocationManagementView onLocationsChanged={() => {}} />);
}

async function openEdit(id) {
  fireEvent.click(await screen.findByTestId(`locations-row-${id}`));
  fireEvent.click(await screen.findByTestId(`edit-location-${id}`));
  return screen.findByTestId('location-edit-panel');
}

function lookUp(query) {
  fireEvent.change(screen.getByTestId('location-edit-drive-point-query'), { target: { value: query } });
  fireEvent.click(screen.getByTestId('location-edit-drive-point-lookup'));
}

describe('Locations — the drive point (admin)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    fetchLocations.mockResolvedValue([HELVELLYN, KESWICK]);
    fetchRegions.mockResolvedValue([]);
    fetchAreas.mockResolvedValue([]);
    fetchUncoveredLocations.mockResolvedValue([]);
    fetchLocationAliases.mockResolvedValue({});
    fetchTideStats.mockResolvedValue(null);
    fetchUnroutableLocations.mockResolvedValue([]);
    updateLocation.mockResolvedValue({});
    addLocation.mockResolvedValue({ id: 9 });
  });

  describe('the location page', () => {
    it('shows "Drive to" with the drive point in the Place section, and its Edit opens the form', async () => {
      renderView();
      fireEvent.click(await screen.findByTestId('locations-row-1'));

      expect(await screen.findByTestId('location-drive-to-name')).toHaveTextContent('Glenridding');
      expect(screen.getByTestId('location-drive-to')).toHaveTextContent('Glenridding');

      fireEvent.click(screen.getByTestId('location-drive-to-edit'));
      expect(await screen.findByTestId('location-edit-panel')).toBeInTheDocument();
      expect(screen.getByTestId('location-edit-drive-point-name')).toHaveTextContent('Glenridding');
    });

    it('shows no "Drive to" row for a place with no drive point', async () => {
      renderView();
      fireEvent.click(await screen.findByTestId('locations-row-2'));
      await screen.findByTestId('location-place');

      expect(screen.queryByTestId('location-drive-to')).toBeNull();
    });
  });

  describe('the edit form', () => {
    it('shows the stored drive point and sends no drive keys when nothing about it changed', async () => {
      renderView();
      await openEdit(1);
      expect(screen.getByTestId('location-edit-drive-point-name')).toHaveTextContent('Glenridding');
      expect(screen.getByTestId('location-edit-drive-point-help')).toHaveTextContent('Drive times are measured to it');

      fireEvent.change(screen.getByTestId('location-edit-name'), { target: { value: 'Helvellyn Summit' } });
      fireEvent.click(screen.getByTestId('location-edit-save'));

      await waitFor(() => expect(updateLocation).toHaveBeenCalled());
      const payload = updateLocation.mock.calls[0][1];
      expect(payload).not.toHaveProperty('drivePointName');
      expect(payload).not.toHaveProperty('drivePointLat');
      expect(payload).not.toHaveProperty('drivePointLon');
      expect(payload).not.toHaveProperty('clearDrivePoint');
    });

    it('looks up a village by name from the location, lists the nearest matches, and sends the one picked', async () => {
      fetchDrivePointPlaces.mockResolvedValue([
        GLENRIDDING,
        { name: 'Patterdale', detail: 'Village, Cumberland', lat: 54.5, lon: -2.95, distanceKm: 8.0 },
      ]);
      renderView();
      await openEdit(2);
      expect(screen.getByTestId('location-edit-drive-point-none')).toBeInTheDocument();
      expect(screen.getByTestId('location-edit-drive-point-lookup')).toBeDisabled();

      lookUp('Glen');

      await waitFor(() => expect(fetchDrivePointPlaces).toHaveBeenCalledWith('Glen', 54.6, -3.13));
      expect(await screen.findByTestId('location-edit-drive-point-result-0'))
        .toHaveTextContent('Glenridding, Village, Cumberland · 4.6 km');
      expect(screen.getByTestId('location-edit-drive-point-result-1')).toHaveTextContent('Patterdale');

      fireEvent.click(screen.getByTestId('location-edit-drive-point-result-0'));
      expect(screen.getByTestId('location-edit-drive-point-name')).toHaveTextContent('Glenridding');
      expect(screen.queryByTestId('location-edit-drive-point-results')).toBeNull();

      fireEvent.click(screen.getByTestId('location-edit-save'));
      await waitFor(() => expect(updateLocation).toHaveBeenCalled());
      expect(updateLocation.mock.calls[0][0]).toBe(2);
      expect(updateLocation.mock.calls[0][1]).toMatchObject({
        drivePointName: 'Glenridding', drivePointLat: 54.5442, drivePointLon: -2.9505,
      });
      expect(updateLocation.mock.calls[0][1]).not.toHaveProperty('clearDrivePoint');
    });

    it('the Enter key looks up too, and a postcode is searched as typed', async () => {
      fetchDrivePointPlaces.mockResolvedValue([GLENRIDDING]);
      renderView();
      await openEdit(2);

      const input = screen.getByTestId('location-edit-drive-point-query');
      fireEvent.change(input, { target: { value: 'CA11 0PA' } });
      fireEvent.keyDown(input, { key: 'Enter' });

      await waitFor(() => expect(fetchDrivePointPlaces).toHaveBeenCalledWith('CA11 0PA', 54.6, -3.13));
    });

    it('Enter with nothing typed does not look up', async () => {
      renderView();
      await openEdit(2);

      fireEvent.keyDown(screen.getByTestId('location-edit-drive-point-query'), { key: 'Enter' });
      fireEvent.keyDown(screen.getByTestId('location-edit-drive-point-query'), { key: 'a' });

      expect(fetchDrivePointPlaces).not.toHaveBeenCalled();
    });

    it('says so when no place matches, and shows the server sentence when the lookup fails', async () => {
      fetchDrivePointPlaces.mockResolvedValueOnce([]);
      renderView();
      await openEdit(2);

      lookUp('Zzzz');
      expect(await screen.findByTestId('location-edit-drive-point-status'))
        .toHaveTextContent('No matching place within 25 km');

      fetchDrivePointPlaces.mockRejectedValueOnce({ response: { data: { error: 'Postcode lookup is unavailable' } } });
      lookUp('Glen');
      await waitFor(() => expect(screen.getByTestId('location-edit-drive-point-status'))
        .toHaveTextContent('Postcode lookup is unavailable'));

      fetchDrivePointPlaces.mockRejectedValueOnce(new Error('network'));
      lookUp('Glen');
      await waitFor(() => expect(screen.getByTestId('location-edit-drive-point-status'))
        .toHaveTextContent('Could not look up places; try again shortly.'));
    });

    it('drops a lookup reply that lands after a pick or a clear', async () => {
      let land;
      fetchDrivePointPlaces.mockReturnValue(new Promise((resolve) => { land = resolve; }));
      renderView();
      await openEdit(1);

      lookUp('Glen');
      fireEvent.click(screen.getByTestId('location-edit-drive-point-clear'));
      land([GLENRIDDING]);

      await waitFor(() => expect(screen.getByTestId('location-edit-drive-point-lookup')).not.toBeDisabled());
      expect(screen.queryByTestId('location-edit-drive-point-results')).toBeNull();
      expect(screen.getByTestId('location-edit-drive-point-none')).toBeInTheDocument();
    });

    it('Clear sends clearDrivePoint, and nothing else about the drive point', async () => {
      renderView();
      await openEdit(1);

      fireEvent.click(screen.getByTestId('location-edit-drive-point-clear'));
      expect(screen.getByTestId('location-edit-drive-point-none')).toBeInTheDocument();
      fireEvent.click(screen.getByTestId('location-edit-save'));

      await waitFor(() => expect(updateLocation).toHaveBeenCalled());
      const payload = updateLocation.mock.calls[0][1];
      expect(payload.clearDrivePoint).toBe(true);
      expect(payload).not.toHaveProperty('drivePointName');
    });

    it('a refused save (422 NO_ROAD_ROUTE) shows the drive point as required, with the sentence, and stays open',
      async () => {
        updateLocation.mockRejectedValueOnce(NO_ROUTE);
        renderView();
        await openEdit(2);

        fireEvent.change(screen.getByTestId('location-edit-lat'), { target: { value: '54.7' } });
        fireEvent.click(screen.getByTestId('location-edit-save'));

        const error = await screen.findByTestId('location-edit-error');
        expect(error).toHaveTextContent('No road route reaches this point. Add a drive point');
        expect(screen.getByTestId('location-edit-drive-point')).toHaveAttribute('data-required', 'true');
        expect(screen.getByText('Drive point (required)')).toBeInTheDocument();
        expect(screen.getByTestId('location-edit-drive-point-help'))
          .toHaveTextContent('Choose the nearest village or car park');
        expect(screen.getByTestId('location-edit-panel')).toBeInTheDocument();

        // The admin picks a village and saves again: the retry carries it.
        fetchDrivePointPlaces.mockResolvedValue([GLENRIDDING]);
        lookUp('Glen');
        fireEvent.click(await screen.findByTestId('location-edit-drive-point-result-0'));
        fireEvent.click(screen.getByTestId('location-edit-save'));

        await waitFor(() => expect(updateLocation).toHaveBeenCalledTimes(2));
        expect(updateLocation.mock.calls[1][1]).toMatchObject({ lat: 54.7, drivePointName: 'Glenridding' });
      });

    it('any other refusal stays an unplaced message and does not mark the drive point required', async () => {
      updateLocation.mockRejectedValueOnce({ response: { status: 409, data: { error: 'Turn Northumberland on.' } } });
      renderView();
      await openEdit(2);

      fireEvent.click(screen.getByTestId('location-edit-save'));

      expect(await screen.findByTestId('location-edit-error')).toHaveTextContent('Turn Northumberland on.');
      expect(screen.getByTestId('location-edit-drive-point')).not.toHaveAttribute('data-required');
    });
  });

  describe('the add form', () => {
    async function openAddManual() {
      renderView();
      fireEvent.click(await screen.findByTestId('add-location-btn'));
      await screen.findByTestId('location-add-panel');
      expect(screen.queryByTestId('location-add-drive-point')).toBeNull();
      fireEvent.click(screen.getByTestId('manual-entry-btn'));
      fireEvent.change(screen.getByTestId('manual-name-input'), { target: { value: 'Striding Edge' } });
      fireEvent.change(screen.getByTestId('manual-lat-input'), { target: { value: '54.53' } });
      fireEvent.change(screen.getByTestId('manual-lon-input'), { target: { value: '-3.01' } });
    }

    it('offers an optional drive point once there are coordinates, and sends the one picked', async () => {
      fetchDrivePointPlaces.mockResolvedValue([GLENRIDDING]);
      await openAddManual();
      expect(await screen.findByTestId('location-add-drive-point-none')).toBeInTheDocument();

      fireEvent.change(screen.getByTestId('location-add-drive-point-query'), { target: { value: 'Glen' } });
      fireEvent.click(screen.getByTestId('location-add-drive-point-lookup'));
      await waitFor(() => expect(fetchDrivePointPlaces).toHaveBeenCalledWith('Glen', 54.53, -3.01));
      fireEvent.click(await screen.findByTestId('location-add-drive-point-result-0'));
      fireEvent.click(screen.getByTestId('location-add-submit'));

      await waitFor(() => expect(addLocation).toHaveBeenCalled());
      expect(addLocation.mock.calls[0][0]).toMatchObject({
        name: 'Striding Edge', drivePointName: 'Glenridding', drivePointLat: 54.5442, drivePointLon: -2.9505,
      });
    });

    it('sends no drive point when none is chosen', async () => {
      await openAddManual();
      fireEvent.click(screen.getByTestId('location-add-submit'));

      await waitFor(() => expect(addLocation).toHaveBeenCalled());
      expect(addLocation.mock.calls[0][0]).not.toHaveProperty('drivePointName');
    });

    it('a refused create (422 NO_ROAD_ROUTE) makes the drive point required and keeps the form open', async () => {
      addLocation.mockRejectedValueOnce(NO_ROUTE);
      await openAddManual();

      fireEvent.click(screen.getByTestId('location-add-submit'));

      const error = await screen.findByTestId('location-add-error');
      expect(error).toHaveTextContent('No road route reaches this point. Add a drive point');
      expect(screen.getByTestId('location-add-drive-point')).toHaveAttribute('data-required', 'true');
      expect(screen.getByText('Drive point (required)')).toBeInTheDocument();
      expect(screen.getByTestId('location-add-panel')).toBeInTheDocument();
    });
  });
});
