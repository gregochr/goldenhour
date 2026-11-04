import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import AreasSection from '../components/AreasSection.jsx';

vi.mock('../api/areaApi', () => ({
  fetchAreas: vi.fn(),
  fetchUncoveredLocations: vi.fn(),
  createArea: vi.fn(),
  updateArea: vi.fn(),
  setAreaLocations: vi.fn(),
  deleteArea: vi.fn(),
}));

import {
  fetchAreas, fetchUncoveredLocations, updateArea, deleteArea,
} from '../api/areaApi';

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

const LOCATIONS = [
  { id: 1, name: 'Walltown Crags', enabled: true },
  { id: 2, name: 'Cawfields', enabled: true },
];

const WALL = { id: 7, name: "Hadrian's Wall", aliases: [], locationIds: [1, 2] };
const KIELDER = { id: 8, name: 'Kielder', aliases: [], locationIds: [1] };

describe('AreasSection branches', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchUncoveredLocations.mockResolvedValue([]);
  });

  it('ignores a late successful load after unmounting', async () => {
    const areas = deferred();
    const uncovered = deferred();
    fetchAreas.mockReturnValue(areas.promise);
    fetchUncoveredLocations.mockReturnValue(uncovered.promise);
    const { unmount } = render(<AreasSection locations={LOCATIONS} />);
    unmount();
    await act(async () => {
      areas.resolve([WALL]);
      uncovered.resolve([{ name: 'X', regionName: 'Y' }]);
    });
    expect(fetchAreas).toHaveBeenCalledTimes(1);
  });

  it('ignores a late failed load after unmounting', async () => {
    const areas = deferred();
    fetchAreas.mockReturnValue(areas.promise);
    const { unmount } = render(<AreasSection locations={LOCATIONS} />);
    unmount();
    await act(async () => { areas.reject(new Error('down')); });
    expect(fetchAreas).toHaveBeenCalledTimes(1);
  });

  it('renaming one area leaves the other areas in the list untouched', async () => {
    fetchAreas.mockResolvedValue([WALL, KIELDER]);
    updateArea.mockResolvedValue({ ...WALL, name: 'The Wall' });
    render(<AreasSection locations={LOCATIONS} />);
    fireEvent.click(await screen.findByTestId('area-edit-7'));
    fireEvent.change(screen.getByTestId('area-name-input'), { target: { value: 'The Wall' } });
    fireEvent.click(screen.getByTestId('area-save-names'));
    expect(await screen.findByTestId('area-saved')).toBeInTheDocument();
    expect(screen.getByTestId('area-name-7')).toHaveTextContent('The Wall');
    expect(screen.getByTestId('area-name-8')).toHaveTextContent('Kielder');
  });

  it('deleting the area being edited closes its editor', async () => {
    fetchAreas.mockResolvedValue([WALL]);
    deleteArea.mockResolvedValue(undefined);
    render(<AreasSection locations={LOCATIONS} />);
    fireEvent.click(await screen.findByTestId('area-edit-7'));
    expect(screen.getByTestId('area-editor-7')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('area-delete-7'));
    fireEvent.click(screen.getByTestId('area-delete-yes-7'));
    expect(await screen.findByTestId('areas-empty')).toBeInTheDocument();
    expect(screen.queryByTestId('area-editor-7')).not.toBeInTheDocument();
  });

  it('the Close button on an open editor closes it', async () => {
    fetchAreas.mockResolvedValue([WALL]);
    render(<AreasSection locations={LOCATIONS} />);
    fireEvent.click(await screen.findByTestId('area-edit-7'));
    expect(screen.getByTestId('area-edit-7')).toHaveTextContent('Close');
    fireEvent.click(screen.getByTestId('area-edit-7'));
    expect(screen.queryByTestId('area-editor-7')).not.toBeInTheDocument();
  });

  it('a sub-region without a region name, with an unknown and no members, reads sensibly', async () => {
    fetchAreas.mockResolvedValue([
      { id: 20, name: 'North', kind: 'SUB_REGION', aliases: [], locationIds: [99] },
      { id: 21, name: 'South', kind: 'SUB_REGION', regionName: 'Northumberland', aliases: [], locationIds: [] },
    ]);
    render(<AreasSection locations={LOCATIONS} />);
    expect(await screen.findByTestId('area-kind-20')).toHaveTextContent('Sub-region of its region');
    expect(screen.getByTestId('area-kind-21')).toHaveTextContent('Sub-region of Northumberland');
    fireEvent.click(screen.getByTestId('area-edit-20'));
    expect(screen.getByTestId('area-member-readonly-list')).toHaveTextContent('Location 99');
    expect(screen.queryByText('No places yet.')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('area-edit-21'));
    expect(screen.getByText('No places yet.')).toBeInTheDocument();
  });
});
