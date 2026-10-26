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
}));
vi.mock('../api/regionApi', () => ({ fetchRegions: vi.fn() }));
vi.mock('../api/tideApi', () => ({
  fetchTideStats: vi.fn(),
  fetchAllTideStats: vi.fn(),
  fetchTidesForDate: vi.fn(),
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

import { fetchLocations, updateLocation } from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';
import { fetchAreas, fetchUncoveredLocations, fetchLocationAliases } from '../api/areaApi';

const DALES = { id: 4, name: 'The Yorkshire Dales', enabled: true };
const LAKES = { id: 3, name: 'The Lake District', enabled: true };
const TEESDALE = { id: 6, name: 'Teesdale', enabled: true };

const FORCE = {
  id: 1, name: 'High Force', lat: 54.65, lon: -2.14, enabled: true, region: DALES,
  solarEventType: ['SUNRISE', 'SUNSET'], locationType: ['LANDSCAPE'], tideType: [],
};
const LOOSE = { ...FORCE, id: 2, name: 'Loose Crag' };
const NO_OPTIONS = { ...FORCE, id: 3, name: 'Quarry', region: TEESDALE };

function subRegion(id, name, region, locationIds) {
  return {
    id, name, aliases: [], locationIds, kind: 'SUB_REGION', regionId: region.id, regionName: region.name,
  };
}

const SWALEDALE = subRegion(21, 'Swaledale', DALES, [1]);
const WENSLEYDALE = subRegion(22, 'Wensleydale', DALES, []);
const KESWICK = subRegion(31, 'Keswick & Borrowdale', LAKES, []);
const WALL = {
  id: 7, name: "Hadrian's Wall", aliases: [], locationIds: [1], kind: 'NAMED', regionId: null, regionName: null,
};

async function openEditor(id = 1) {
  fireEvent.click(await screen.findByTestId(`edit-location-${id}`));
  return screen.findByTestId('inline-edit-region');
}

describe('LocationManagementView: the sub-region select', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchLocations.mockResolvedValue([FORCE, LOOSE, NO_OPTIONS]);
    fetchRegions.mockResolvedValue([DALES, LAKES, TEESDALE]);
    fetchAreas.mockResolvedValue([WALL, SWALEDALE, WENSLEYDALE, KESWICK]);
    fetchUncoveredLocations.mockResolvedValue([]);
    fetchLocationAliases.mockResolvedValue({});
    updateLocation.mockResolvedValue({});
  });

  it('offers only the sub-regions of the location\'s region, with its current one selected', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await openEditor(1);
    const select = await screen.findByRole('combobox', { name: 'Sub-region' });

    expect(select).toHaveValue('21');
    const options = within(select).getAllByRole('option').map((o) => o.textContent);
    expect(options).toEqual(['Swaledale', 'Wensleydale']);
  });

  it('offers "No sub-region" and starts on it for a location that has none', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await openEditor(2);
    const select = await screen.findByRole('combobox', { name: 'Sub-region' });

    expect(select).toHaveValue('');
    expect(within(select).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['— No sub-region —', 'Swaledale', 'Wensleydale']);
  });

  it('has no sub-region select for a region with no sub-regions, or before the areas have loaded', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await openEditor(3);
    await waitFor(() => expect(fetchAreas).toHaveBeenCalled());

    expect(screen.queryByRole('combobox', { name: 'Sub-region' })).not.toBeInTheDocument();
  });

  it('shows the select once the areas land for an editor opened before them', async () => {
    let land;
    fetchAreas.mockReturnValue(new Promise((resolve) => { land = resolve; }));
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await openEditor(1);
    expect(screen.queryByRole('combobox', { name: 'Sub-region' })).not.toBeInTheDocument();
    land([WALL, SWALEDALE, WENSLEYDALE]);

    expect(await screen.findByRole('combobox', { name: 'Sub-region' })).toHaveValue('21');
  });

  it('does not send subRegionId when it was not changed', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await openEditor(1);
    await screen.findByRole('combobox', { name: 'Sub-region' });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => expect(updateLocation).toHaveBeenCalledTimes(1));
    expect(updateLocation.mock.calls[0][1]).not.toHaveProperty('subRegionId');
  });

  it('sends the chosen subRegionId with the location\'s own save, then reads the areas again', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await openEditor(1);
    fireEvent.change(await screen.findByRole('combobox', { name: 'Sub-region' }), { target: { value: '22' } });
    fetchAreas.mockClear();
    fetchAreas.mockResolvedValue([WALL, subRegion(21, 'Swaledale', DALES, []), subRegion(22, 'Wensleydale', DALES, [1])]);
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => expect(updateLocation).toHaveBeenCalledTimes(1));
    expect(updateLocation).toHaveBeenCalledWith(1, expect.objectContaining({ regionId: 4, subRegionId: 22 }));
    await waitFor(() => expect(fetchAreas.mock.calls.length).toBeGreaterThanOrEqual(1));
  });

  it('sends a sub-region picked for a location that had none', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await openEditor(2);
    fireEvent.change(await screen.findByRole('combobox', { name: 'Sub-region' }), { target: { value: '21' } });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => expect(updateLocation).toHaveBeenCalledWith(2, expect.objectContaining({ subRegionId: 21 })));
  });

  it('cannot clear a sub-region it already has: there is no empty option while the region is unchanged', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await openEditor(1);
    const select = await screen.findByRole('combobox', { name: 'Sub-region' });

    expect(within(select).queryByRole('option', { name: '— No sub-region —' })).not.toBeInTheDocument();
  });

  it('follows the region: the options become the new region\'s, and the old pick is dropped', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    fireEvent.change(await openEditor(1), { target: { value: '3' } });

    const select = await screen.findByRole('combobox', { name: 'Sub-region' });
    expect(within(select).getAllByRole('option').map((o) => o.textContent))
      .toEqual(['— No sub-region —', 'Keswick & Borrowdale']);
    expect(select).toHaveValue('');

    fireEvent.click(screen.getByTestId('inline-edit-save'));
    await waitFor(() => expect(updateLocation).toHaveBeenCalledTimes(1));
    expect(updateLocation.mock.calls[0][1]).toMatchObject({ regionId: 3 });
    expect(updateLocation.mock.calls[0][1]).not.toHaveProperty('subRegionId');
  });

  it('puts the original sub-region back when the region is changed back', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    const region = await openEditor(1);
    fireEvent.change(region, { target: { value: '3' } });
    fireEvent.change(region, { target: { value: '4' } });

    expect(await screen.findByRole('combobox', { name: 'Sub-region' })).toHaveValue('21');
  });

  it('sends the new region and a sub-region of it together', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    fireEvent.change(await openEditor(1), { target: { value: '3' } });
    fireEvent.change(await screen.findByRole('combobox', { name: 'Sub-region' }), { target: { value: '31' } });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => expect(updateLocation).toHaveBeenCalledWith(
      1, expect.objectContaining({ regionId: 3, subRegionId: 31 }),
    ));
  });

  it('passes the Areas section a new reloadKey after a location is saved, so its members are read again', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await screen.findByTestId('areas-section');
    await waitFor(() => expect(fetchUncoveredLocations).toHaveBeenCalledTimes(1));
    await openEditor(1);
    await screen.findByRole('combobox', { name: 'Sub-region' });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => expect(fetchUncoveredLocations).toHaveBeenCalledTimes(2));
  });
});
