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

import { fetchLocations, updateLocation } from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';
import { fetchTideStats } from '../api/tideApi';
import { fetchAreas, fetchUncoveredLocations, fetchLocationAliases, setLocationAliases } from '../api/areaApi';

/** Opens a location's panel from its row, then its edit form. */
async function openEdit(id) {
  fireEvent.click(await screen.findByTestId(`locations-row-${id}`));
  fireEvent.click(await screen.findByTestId(`edit-location-${id}`));
}

describe('LocationManagementView — tide point in the edit form', () => {
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
    fetchTideStats.mockResolvedValue(null);
    updateLocation.mockResolvedValue({});
  });

  async function openEditor(id) {
    render(<LocationManagementView onLocationsChanged={() => {}} />);
    await openEdit(id);
    return screen.findByTestId('location-edit-name');
  }

  function save() {
    fireEvent.click(screen.getByTestId('location-edit-save'));
  }

  it('fills the pair from the DTO and shows the explanation', async () => {
    await openEditor(21);
    expect(screen.getByTestId('location-edit-tide-lat')).toHaveValue(55);
    expect(screen.getByTestId('location-edit-tide-lng')).toHaveValue(-1.4);
    expect(screen.getByTestId('location-edit-tide-point-help'))
      .toHaveTextContent("Where tide times are read from, when the pin's own sea area is unreliable");
  });

  it('is hidden for a location that is not coastal', async () => {
    await openEditor(23);
    expect(screen.queryByTestId('location-edit-tide-point')).toBeNull();
  });

  it('an unchanged save sends no tide-point keys', async () => {
    await openEditor(21);
    fireEvent.change(screen.getByTestId('location-edit-name'), { target: { value: 'Cullercoats Bay' } });
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload).not.toHaveProperty('tideLat');
    expect(payload).not.toHaveProperty('tideLng');
    expect(payload).not.toHaveProperty('clearTidePoint');
  });

  it('sets a new tide point as a number pair', async () => {
    await openEditor(22);
    fireEvent.change(screen.getByTestId('location-edit-tide-lat'), { target: { value: '55.01' } });
    fireEvent.change(screen.getByTestId('location-edit-tide-lng'), { target: { value: '-1.41' } });
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload.tideLat).toBe(55.01);
    expect(payload.tideLng).toBe(-1.41);
    expect(payload).not.toHaveProperty('clearTidePoint');
  });

  it('Clear on a stored point sends clearTidePoint and no values', async () => {
    await openEditor(21);
    fireEvent.click(screen.getByTestId('location-edit-tide-point-clear'));
    expect(screen.getByTestId('location-edit-tide-lat')).toHaveValue(null);
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload.clearTidePoint).toBe(true);
    expect(payload).not.toHaveProperty('tideLat');
    expect(payload).not.toHaveProperty('tideLng');
  });

  it('Clear is disabled when there is no point, and a blank pair sends nothing', async () => {
    await openEditor(22);
    expect(screen.getByTestId('location-edit-tide-point-clear')).toBeDisabled();
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    expect(updateLocation.mock.calls[0][1]).not.toHaveProperty('clearTidePoint');
  });

  it('refuses a half-filled pair and an out-of-range value without saving', async () => {
    await openEditor(22);
    fireEvent.change(screen.getByTestId('location-edit-tide-lat'), { target: { value: '55.01' } });
    save();
    expect(await screen.findByTestId('location-edit-error')).toHaveTextContent('needs both latitude and longitude');

    fireEvent.change(screen.getByTestId('location-edit-tide-lng'), { target: { value: '-181' } });
    save();
    await waitFor(() => expect(screen.getByTestId('location-edit-error')).toHaveTextContent('between -90 and 90'));
    expect(updateLocation).not.toHaveBeenCalled();
  });

  it('shows the server message when it rejects the point', async () => {
    updateLocation.mockRejectedValue({ response: { data: { error: 'The tide point must be within 25 km of the location' } } });
    await openEditor(22);
    fireEvent.change(screen.getByTestId('location-edit-tide-lat'), { target: { value: '56' } });
    fireEvent.change(screen.getByTestId('location-edit-tide-lng'), { target: { value: '-1.4' } });
    save();

    expect(await screen.findByTestId('location-edit-error')).toHaveTextContent('within 25 km');
  });

  it('sends no tide keys and is not blocked when SEASCAPE is unticked with a half-blanked point', async () => {
    await openEditor(24);
    fireEvent.change(screen.getByTestId('location-edit-tide-lat'), { target: { value: '' } });
    fireEvent.click(screen.getByTestId('type-chip-SEASCAPE'));
    expect(screen.queryByTestId('location-edit-tide-point')).toBeNull();
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload).not.toHaveProperty('tideLat');
    expect(payload).not.toHaveProperty('clearTidePoint');
  });

  it('after an alias-write failure the saved tide point is the baseline and a second save resends nothing', async () => {
    setLocationAliases.mockRejectedValue({ response: { data: { error: 'alias clash' } } });
    await openEditor(22);
    await waitFor(() => expect(screen.getByTestId('location-edit-aliases')).not.toBeDisabled());
    fireEvent.change(screen.getByTestId('location-edit-tide-lat'), { target: { value: '55.01' } });
    fireEvent.change(screen.getByTestId('location-edit-tide-lng'), { target: { value: '-1.41' } });
    fireEvent.change(screen.getByTestId('location-edit-aliases'), { target: { value: 'The Bay' } });
    save();

    expect(await screen.findByTestId('location-edit-error')).toHaveTextContent('Location saved; its other names were not');
    expect(updateLocation.mock.calls[0][1].tideLat).toBe(55.01);

    save();
    await waitFor(() => expect(updateLocation).toHaveBeenCalledTimes(2));
    const second = updateLocation.mock.calls[1][1];
    expect(second).not.toHaveProperty('tideLat');
    expect(second).not.toHaveProperty('clearTidePoint');
  });
});
