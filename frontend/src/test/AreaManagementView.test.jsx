import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import AreaManagementView from '../components/AreaManagementView.jsx';

vi.mock('../api/forecastApi.js', () => ({ fetchLocations: vi.fn() }));
vi.mock('../api/areaApi', () => ({
  fetchAreas: vi.fn(),
  fetchUncoveredLocations: vi.fn(),
  createArea: vi.fn(),
  updateArea: vi.fn(),
  setAreaLocations: vi.fn(),
  deleteArea: vi.fn(),
}));

import { fetchLocations } from '../api/forecastApi.js';
import { fetchAreas, fetchUncoveredLocations } from '../api/areaApi';

const LOCATIONS = [
  { id: 1, name: 'Walltown Crags', enabled: true },
  { id: 2, name: 'Cawfields', enabled: true },
];
const WALL = { id: 7, name: "Hadrian's Wall", aliases: [], locationIds: [1, 2] };

describe('AreaManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchLocations.mockResolvedValue(LOCATIONS);
    fetchAreas.mockResolvedValue([WALL]);
    fetchUncoveredLocations.mockResolvedValue([]);
  });

  it('shows a loading line, then the areas section once the locations are read', async () => {
    render(<AreaManagementView />);

    expect(screen.getByText('Loading areas...')).toBeInTheDocument();
    expect(await screen.findByTestId('areas-section')).toBeInTheDocument();
    expect(await screen.findByTestId('area-row-7')).toBeInTheDocument();
    expect(screen.queryByText('Loading areas...')).not.toBeInTheDocument();
    expect(fetchLocations).toHaveBeenCalledTimes(1);
  });

  it('shows the error banner and no areas section when the locations cannot be read', async () => {
    fetchLocations.mockRejectedValue(new Error('boom'));
    render(<AreaManagementView />);

    expect(await screen.findByTestId('areas-load-error')).toHaveTextContent("Couldn't load locations.");
    expect(screen.queryByTestId('areas-section')).not.toBeInTheDocument();
    expect(fetchAreas).not.toHaveBeenCalled();
  });

  it('does not set state after it unmounts', async () => {
    let resolve;
    fetchLocations.mockReturnValue(new Promise((res) => { resolve = res; }));
    const errorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { unmount } = render(<AreaManagementView />);
    unmount();
    resolve(LOCATIONS);

    await waitFor(() => expect(fetchLocations).toHaveBeenCalled());
    expect(errorSpy).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });
});
