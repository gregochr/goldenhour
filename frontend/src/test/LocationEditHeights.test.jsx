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

import {
  fetchLocations, updateLocation, fetchValleyFloorCandidates, fetchValleyFloorCell,
} from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';
import { fetchAreas, fetchUncoveredLocations, fetchLocationAliases, setLocationAliases } from '../api/areaApi';

/** Opens a location's panel from its row, then its edit form. */
async function openEdit(id) {
  fireEvent.click(await screen.findByTestId(`locations-row-${id}`));
  fireEvent.click(await screen.findByTestId(`edit-location-${id}`));
}

describe('LocationManagementView — heights in the edit form', () => {
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
    await openEdit(id);
    return screen.findByTestId('location-edit-elevation');
  }

  function save() {
    fireEvent.click(screen.getByTestId('location-edit-save'));
  }

  it('an unchanged save sends none of the height keys', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('location-edit-name'), { target: { value: 'Latrigg Fell' } });
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
    expect(screen.getByTestId('location-edit-valley-floor-height')).toHaveValue(80);
    expect(screen.getByTestId('location-edit-valley-floor')).toHaveTextContent('54.5800, -3.1200');
  });

  it('an elevation edit sends a rounded number', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('location-edit-elevation'), { target: { value: '231.6' } });
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    expect(updateLocation.mock.calls[0][1].elevationMetres).toBe(232);
  });

  it('a new elevation on a place with none is sent', async () => {
    await openEditor(10);
    fireEvent.change(screen.getByTestId('location-edit-elevation'), { target: { value: '45' } });
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    expect(updateLocation.mock.calls[0][1].elevationMetres).toBe(45);
  });

  it('emptying a stored elevation blocks Save with a message', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('location-edit-elevation'), { target: { value: '' } });
    save();

    expect(await screen.findByTestId('location-edit-error')).toHaveTextContent("Elevation can't be cleared; enter a value");
    expect(updateLocation).not.toHaveBeenCalled();
  });

  it('an out-of-range elevation blocks Save', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('location-edit-elevation'), { target: { value: '1501' } });
    save();

    expect(await screen.findByTestId('location-edit-error')).toHaveTextContent('between -50 and 1,500');
    expect(updateLocation).not.toHaveBeenCalled();
  });

  it('warns when an edit crosses 200 m on an overlooks-water site, and not otherwise', async () => {
    await openEditor();
    expect(screen.queryByTestId('location-edit-elevation-warning')).toBeNull();

    fireEvent.change(screen.getByTestId('location-edit-elevation'), { target: { value: '200' } });
    expect(screen.getByTestId('location-edit-elevation-warning')).toHaveTextContent('eligible');

    fireEvent.change(screen.getByTestId('location-edit-elevation'), { target: { value: '150' } });
    expect(screen.queryByTestId('location-edit-elevation-warning')).toBeNull();
  });

  it('does not warn on a site that does not overlook water', async () => {
    await openEditor(10);
    fireEvent.change(screen.getByTestId('location-edit-elevation'), { target: { value: '300' } });
    expect(screen.queryByTestId('location-edit-elevation-warning')).toBeNull();
  });

  it('Suggest lists candidates for the EDITED coordinates and a pick fills the floor and fetches its cell', async () => {
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    fetchValleyFloorCell.mockResolvedValue(CELL);
    await openEditor();
    fireEvent.change(screen.getByTestId('location-edit-lat'), { target: { value: '54.61' } });

    fireEvent.click(screen.getByTestId('location-edit-valley-floor-suggest'));

    const first = await screen.findByTestId('location-edit-valley-floor-candidate-0');
    expect(fetchValleyFloorCandidates).toHaveBeenCalledWith(54.61, -3.1);
    expect(first).toHaveTextContent('60 m, 3.4 km SW');
    expect(screen.getByTestId('location-edit-valley-floor-candidate-1')).toHaveTextContent('100 m, 2.1 km E');

    fireEvent.click(first);

    await waitFor(() => expect(fetchValleyFloorCell).toHaveBeenCalledWith(54.57, -3.15, 60));
    expect(screen.getByTestId('location-edit-valley-floor-height')).toHaveValue(60);
    expect(await screen.findByTestId('location-edit-valley-floor-cell'))
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
    fireEvent.change(screen.getByTestId('location-edit-valley-floor-height'), { target: { value: '95' } });
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

    fireEvent.click(screen.getByTestId('location-edit-valley-floor-suggest'));

    expect(await screen.findByTestId('location-edit-valley-floor-status'))
      .toHaveTextContent('No valley floor 30 m below within 5 km');
    expect(screen.getByTestId('location-edit-valley-floor-height')).toHaveValue(80);
  });

  it('shows the server message when the lookup fails, and leaves the values alone', async () => {
    fetchValleyFloorCandidates.mockRejectedValue({ response: { data: { error: 'Could not look up the surrounding heights' } } });
    await openEditor();

    fireEvent.click(screen.getByTestId('location-edit-valley-floor-suggest'));

    expect(await screen.findByTestId('location-edit-valley-floor-status'))
      .toHaveTextContent('Could not look up the surrounding heights');
    expect(screen.getByTestId('location-edit-valley-floor-height')).toHaveValue(80);
  });

  it('Suggest is disabled while the edited coordinates are invalid', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('location-edit-lat'), { target: { value: '95' } });
    expect(screen.getByTestId('location-edit-valley-floor-suggest')).toBeDisabled();
    fireEvent.change(screen.getByTestId('location-edit-lat'), { target: { value: '54.6' } });
    expect(screen.getByTestId('location-edit-valley-floor-suggest')).not.toBeDisabled();
  });

  it('a reply that lands after Cancel is ignored by the next editor', async () => {
    let resolve;
    fetchValleyFloorCandidates.mockReturnValue(new Promise((r) => { resolve = r; }));
    await openEditor();
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-suggest'));
    expect(screen.getByTestId('location-edit-save')).toBeDisabled();

    fireEvent.click(screen.getByTestId('location-edit-cancel'));
    await openEdit(9);
    await screen.findByTestId('location-edit-elevation');
    resolve(CANDIDATES);
    await new Promise((r) => setTimeout(r, 0));

    expect(screen.queryByTestId('location-edit-valley-floor-candidate-0')).toBeNull();
    expect(screen.getByTestId('location-edit-save')).not.toBeDisabled();
  });

  it('a reply for one row is ignored after switching to another', async () => {
    let resolve;
    fetchValleyFloorCandidates.mockReturnValue(new Promise((r) => { resolve = r; }));
    await openEditor();
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-suggest'));

    await openEdit(10);
    await waitFor(() => expect(screen.getByTestId('location-edit-elevation')).toHaveValue(null));
    resolve(CANDIDATES);
    await new Promise((r) => setTimeout(r, 0));

    expect(screen.queryByTestId('location-edit-valley-floor-candidate-0')).toBeNull();
  });

  it('Clear on a stored floor sends clearValleyFloor and no values', async () => {
    await openEditor();
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-clear'));
    expect(screen.getByTestId('location-edit-valley-floor-none')).toBeInTheDocument();
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
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-suggest'));
    fireEvent.click(await screen.findByTestId('location-edit-valley-floor-candidate-0'));
    await screen.findByTestId('location-edit-valley-floor-cell');
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-clear'));
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
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-clear'));
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-suggest'));
    fireEvent.click(await screen.findByTestId('location-edit-valley-floor-candidate-1'));
    await screen.findByTestId('location-edit-valley-floor-cell');
    save();

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    const payload = updateLocation.mock.calls[0][1];
    expect(payload).not.toHaveProperty('clearValleyFloor');
    expect(payload.valleyFloorMetres).toBe(100);
  });

  it('shows the coordinate hint only while the coordinates differ from the stored ones', async () => {
    await openEditor();
    expect(screen.queryByTestId('location-edit-coords-hint')).toBeNull();

    fireEvent.change(screen.getByTestId('location-edit-lon'), { target: { value: '-3.2' } });
    expect(screen.getByTestId('location-edit-coords-hint'))
      .toHaveTextContent('Coordinates changed: check elevation and valley floor');

    fireEvent.change(screen.getByTestId('location-edit-lon'), { target: { value: '-3.1' } });
    expect(screen.queryByTestId('location-edit-coords-hint')).toBeNull();
  });

  it('warns when an edit crosses 200 m downwards on an overlooks-water site', async () => {
    fetchLocations.mockResolvedValue([{ ...PLACE, elevationMetres: 210 }, BARE]);
    await openEditor();
    expect(screen.queryByTestId('location-edit-elevation-warning')).toBeNull();

    fireEvent.change(screen.getByTestId('location-edit-elevation'), { target: { value: '190' } });

    expect(screen.getByTestId('location-edit-elevation-warning')).toHaveTextContent('out of inversion scoring');
  });

  it('a rejected model-cell lookup sets the status and keeps the pick', async () => {
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    fetchValleyFloorCell.mockRejectedValue({ response: { data: { error: 'no cell' } } });
    await openEditor();
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-suggest'));
    fireEvent.click(await screen.findByTestId('location-edit-valley-floor-candidate-0'));

    expect(await screen.findByTestId('location-edit-valley-floor-status')).toHaveTextContent('no cell');
    expect(screen.getByTestId('location-edit-valley-floor-height')).toHaveValue(60);
    expect(screen.queryByTestId('location-edit-valley-floor-cell')).toBeNull();
  });

  it('Save is disabled while a pick\'s cell lookup is running', async () => {
    let resolve;
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    fetchValleyFloorCell.mockReturnValue(new Promise((r) => { resolve = r; }));
    await openEditor();
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-suggest'));
    fireEvent.click(await screen.findByTestId('location-edit-valley-floor-candidate-0'));

    expect(screen.getByTestId('location-edit-save')).toBeDisabled();
    resolve(CELL);
    await screen.findByTestId('location-edit-valley-floor-cell');
    expect(screen.getByTestId('location-edit-save')).not.toBeDisabled();
  });

  it('an empty floor height blocks Save', async () => {
    await openEditor();
    fireEvent.change(screen.getByTestId('location-edit-valley-floor-height'), { target: { value: '' } });
    save();

    expect(await screen.findByTestId('location-edit-error')).toHaveTextContent('Valley floor height');
    expect(updateLocation).not.toHaveBeenCalled();
  });

  it('editing the floor height drops a cell reply still in flight for the old height', async () => {
    let resolve;
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    fetchValleyFloorCell.mockReturnValue(new Promise((r) => { resolve = r; }));
    await openEditor();
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-suggest'));
    fireEvent.click(await screen.findByTestId('location-edit-valley-floor-candidate-0'));
    fireEvent.change(screen.getByTestId('location-edit-valley-floor-height'), { target: { value: '70' } });
    resolve(CELL);
    await new Promise((r) => setTimeout(r, 0));

    expect(screen.queryByTestId('location-edit-valley-floor-cell')).toBeNull();
  });

  it('editing the coordinates clears candidates fetched for the old ones', async () => {
    fetchValleyFloorCandidates.mockResolvedValue(CANDIDATES);
    await openEditor();
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-suggest'));
    await screen.findByTestId('location-edit-valley-floor-candidate-0');

    fireEvent.change(screen.getByTestId('location-edit-lon'), { target: { value: '-3.2' } });

    expect(screen.queryByTestId('location-edit-valley-floor-candidate-0')).toBeNull();
  });

  it('after a save whose other-names write fails, the heights and coordinates just saved are the new baselines', async () => {
    setLocationAliases.mockRejectedValue({ response: { data: { error: 'alias clash' } } });
    await openEditor();
    await waitFor(() => expect(screen.getByTestId('location-edit-aliases')).not.toBeDisabled());
    fireEvent.change(screen.getByTestId('location-edit-elevation'), { target: { value: '250' } });
    fireEvent.click(screen.getByTestId('location-edit-valley-floor-clear'));
    fireEvent.change(screen.getByTestId('location-edit-lon'), { target: { value: '-3.2' } });
    fireEvent.change(screen.getByTestId('location-edit-aliases'), { target: { value: 'The Fell' } });
    save();

    expect(await screen.findByTestId('location-edit-error')).toHaveTextContent('Location saved; its other names were not');
    const first = updateLocation.mock.calls[0][1];
    expect(first.elevationMetres).toBe(250);
    expect(first.clearValleyFloor).toBe(true);
    // The saved values are now the baseline: no hint, no warning, nothing re-sent.
    expect(screen.queryByTestId('location-edit-coords-hint')).toBeNull();
    expect(screen.queryByTestId('location-edit-elevation-warning')).toBeNull();

    save();
    await waitFor(() => expect(updateLocation).toHaveBeenCalledTimes(2));
    const second = updateLocation.mock.calls[1][1];
    expect(second).not.toHaveProperty('elevationMetres');
    expect(second).not.toHaveProperty('clearValleyFloor');
  });
});
