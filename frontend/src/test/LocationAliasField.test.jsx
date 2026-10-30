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
}));
vi.mock('../api/regionApi', () => ({ fetchRegions: vi.fn() }));
vi.mock('../api/tideApi', () => ({
  fetchTideStats: vi.fn(),
  fetchAllTideStats: vi.fn(),
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
import {
  fetchAreas, fetchUncoveredLocations, fetchLocationAliases, setLocationAliases,
} from '../api/areaApi';

const HEADLAND = {
  id: 1, name: 'Hartlepool Headland', lat: 54.69, lon: -1.17, enabled: true,
  solarEventType: ['SUNRISE', 'SUNSET'], locationType: ['LANDSCAPE'], tideType: [],
};

async function openEditor() {
  fireEvent.click(await screen.findByTestId('edit-location-1'));
  return screen.getByTestId('inline-edit-aliases');
}

describe('LocationManagementView — "Also known as"', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchLocations.mockResolvedValue([HEADLAND]);
    fetchRegions.mockResolvedValue([]);
    fetchAreas.mockResolvedValue([]);
    fetchUncoveredLocations.mockResolvedValue([]);
    fetchLocationAliases.mockResolvedValue({ 1: ['The Heugh', 'Heugh Battery'] });
    updateLocation.mockResolvedValue({});
    setLocationAliases.mockResolvedValue(['Heugh Lighthouse']);
  });

  it('starts the field with the location\'s current other names', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => expect(fetchLocationAliases).toHaveBeenCalled());
    const field = await openEditor();

    await waitFor(() => expect(field).toHaveValue('The Heugh, Heugh Battery'));
  });

  it('saves the location FIRST, then its aliases, then closes the editor', async () => {
    const order = [];
    updateLocation.mockImplementation(async () => { order.push('location'); return {}; });
    setLocationAliases.mockImplementation(async () => { order.push('aliases'); return ['Heugh Lighthouse']; });
    const onChanged = vi.fn();
    render(<LocationManagementView onLocationsChanged={onChanged} />);

    await waitFor(() => expect(fetchLocationAliases).toHaveBeenCalled());
    const field = await openEditor();
    await waitFor(() => expect(field).toHaveValue('The Heugh, Heugh Battery'));
    fireEvent.change(field, { target: { value: ' Heugh Lighthouse ,, ' } });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => expect(screen.queryByTestId('inline-edit-save')).not.toBeInTheDocument());
    expect(order).toEqual(['location', 'aliases']);
    expect(setLocationAliases).toHaveBeenCalledWith(1, ['Heugh Lighthouse']);
    expect(onChanged).toHaveBeenCalled();
  });

  it('does not send aliases when the field was not edited', async () => {
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => expect(fetchLocationAliases).toHaveBeenCalled());
    const field = await openEditor();
    await waitFor(() => expect(field).toHaveValue('The Heugh, Heugh Battery'));
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => expect(screen.queryByTestId('inline-edit-save')).not.toBeInTheDocument());
    expect(updateLocation).toHaveBeenCalledTimes(1);
    expect(setLocationAliases).not.toHaveBeenCalled();
  });

  it('sends an emptied field as an empty list, which clears the names', async () => {
    setLocationAliases.mockResolvedValue([]);
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => expect(fetchLocationAliases).toHaveBeenCalled());
    const field = await openEditor();
    await waitFor(() => expect(field).toHaveValue('The Heugh, Heugh Battery'));
    fireEvent.change(field, { target: { value: '' } });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => expect(setLocationAliases).toHaveBeenCalledWith(1, []));
  });

  it('never sends aliases when the location save fails, and shows that error', async () => {
    updateLocation.mockRejectedValue(Object.assign(new Error('bad'), {
      response: { status: 400, data: { error: "'Hadrian's Wall' contains the named area 'Hadrian's Wall'" } },
    }));
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => expect(fetchLocationAliases).toHaveBeenCalled());
    const field = await openEditor();
    await waitFor(() => expect(field).toHaveValue('The Heugh, Heugh Battery'));
    fireEvent.change(field, { target: { value: 'Heugh Lighthouse' } });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    expect(await screen.findByTestId('inline-edit-error')).toHaveTextContent('contains the named area');
    expect(setLocationAliases).not.toHaveBeenCalled();
    expect(screen.getByTestId('inline-edit-aliases')).toHaveValue('Heugh Lighthouse');
  });

  it('says "Location saved; its other names were not" and keeps the typed aliases when only the aliases fail', async () => {
    setLocationAliases.mockRejectedValue(Object.assign(new Error('conflict'), {
      response: { status: 409, data: { error: "'The Heugh' is already the location 'X'" } },
    }));
    const onChanged = vi.fn();
    render(<LocationManagementView onLocationsChanged={onChanged} />);

    await waitFor(() => expect(fetchLocationAliases).toHaveBeenCalled());
    const field = await openEditor();
    await waitFor(() => expect(field).toHaveValue('The Heugh, Heugh Battery'));
    fireEvent.change(field, { target: { value: 'The Heugh, Brand New Name' } });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    const message = await screen.findByTestId('inline-edit-error');
    expect(message).toHaveTextContent('Location saved; its other names were not');
    expect(screen.getByTestId('inline-edit-alias-detail')).toHaveTextContent('already the location');
    expect(screen.getByTestId('inline-edit-aliases')).toHaveValue('The Heugh, Brand New Name');
    expect(screen.getByTestId('inline-edit-save')).toBeInTheDocument();
    expect(updateLocation).toHaveBeenCalledTimes(1);
    expect(onChanged).toHaveBeenCalled();
  });

  it('disables the field, and sends no aliases, when the other names could not be loaded', async () => {
    fetchLocationAliases.mockRejectedValue(new Error('403'));
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => expect(fetchLocationAliases).toHaveBeenCalled());
    const field = await openEditor();

    expect(field).toBeDisabled();
    expect(field).toHaveAttribute('placeholder', 'Other names could not be loaded');
    fireEvent.click(screen.getByTestId('inline-edit-save'));
    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    expect(setLocationAliases).not.toHaveBeenCalled();
  });

  it('an editor opened BEFORE the names load starts disabled, then fills in, and an unrelated edit never wipes them', async () => {
    let land;
    fetchLocationAliases.mockReturnValue(new Promise((resolve) => { land = resolve; }));
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    const field = await openEditor();
    expect(field).toBeDisabled();
    expect(field).toHaveAttribute('placeholder', 'Loading other names...');
    fireEvent.change(screen.getByTestId('inline-edit-lat'), { target: { value: '54.7' } });

    land({ 1: ['The Heugh', 'Heugh Battery'] });
    await waitFor(() => expect(screen.getByTestId('inline-edit-aliases')).toHaveValue('The Heugh, Heugh Battery'));
    expect(screen.getByTestId('inline-edit-aliases')).not.toBeDisabled();

    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => expect(updateLocation).toHaveBeenCalled());
    await waitFor(() => expect(screen.queryByTestId('inline-edit-save')).not.toBeInTheDocument());
    expect(setLocationAliases).not.toHaveBeenCalled();
  });

  it('an editor opened before the names load can then edit them, against the landed baseline', async () => {
    let land;
    fetchLocationAliases.mockReturnValue(new Promise((resolve) => { land = resolve; }));
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await openEditor();
    land({ 1: ['The Heugh'] });
    await waitFor(() => expect(screen.getByTestId('inline-edit-aliases')).toHaveValue('The Heugh'));
    fireEvent.change(screen.getByTestId('inline-edit-aliases'), { target: { value: 'The Heugh, Heugh Battery' } });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    await waitFor(() => expect(setLocationAliases).toHaveBeenCalledWith(1, ['The Heugh', 'Heugh Battery']));
  });

  it('shows no transport text for an alias failure with no server message', async () => {
    setLocationAliases.mockRejectedValue(new Error('Request failed with status code 500'));
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    await waitFor(() => expect(fetchLocationAliases).toHaveBeenCalled());
    const field = await openEditor();
    await waitFor(() => expect(field).toHaveValue('The Heugh, Heugh Battery'));
    fireEvent.change(field, { target: { value: 'Heugh Lighthouse' } });
    fireEvent.click(screen.getByTestId('inline-edit-save'));

    const message = await screen.findByTestId('inline-edit-error');
    expect(message).toHaveTextContent('Location saved; its other names were not');
    expect(message).not.toHaveTextContent('status code');
    expect(screen.queryByTestId('inline-edit-alias-detail')).not.toBeInTheDocument();
  });
});
