import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import AreasSection from '../components/AreasSection.jsx';

vi.mock('../api/areaApi', () => ({
  fetchAreas: vi.fn(),
  createArea: vi.fn(),
  updateArea: vi.fn(),
  setAreaLocations: vi.fn(),
  deleteArea: vi.fn(),
  fetchLocationAliases: vi.fn(),
  setLocationAliases: vi.fn(),
}));

import {
  fetchAreas, createArea, updateArea, setAreaLocations, deleteArea,
} from '../api/areaApi';

const LOCATIONS = [
  { id: 1, name: 'Walltown Crags', enabled: true },
  { id: 2, name: 'Cawfields', enabled: true },
  { id: 3, name: 'Allen Banks', enabled: false },
];

const WALL = { id: 7, name: "Hadrian's Wall", aliases: ['Hadrian Wall', 'Roman Wall'], locationIds: [1, 2] };

function clash(message) {
  return Object.assign(new Error('Request failed with status code 409'), {
    response: { status: 409, data: { error: message } },
  });
}

describe('AreasSection', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchAreas.mockResolvedValue([WALL]);
  });

  it('lists each area with its name, other names and member count', async () => {
    render(<AreasSection locations={LOCATIONS} />);

    expect(await screen.findByTestId('area-name-7')).toHaveTextContent("Hadrian's Wall");
    expect(screen.getByTestId('area-aliases-7')).toHaveTextContent('Also: Hadrian Wall, Roman Wall');
    expect(screen.getByTestId('area-count-7')).toHaveTextContent('2 places');
  });

  it('says so when there are no areas, and when the list cannot be loaded', async () => {
    fetchAreas.mockResolvedValueOnce([]);
    const first = render(<AreasSection locations={LOCATIONS} />);
    expect(await screen.findByTestId('areas-empty')).toBeInTheDocument();
    first.unmount();

    fetchAreas.mockRejectedValueOnce(new Error('boom'));
    render(<AreasSection locations={LOCATIONS} />);
    expect(await screen.findByTestId('areas-load-error')).toHaveTextContent('Could not load areas.');
    expect(screen.queryByTestId('areas-empty')).not.toBeInTheDocument();
  });

  it('creates an area from a name and comma-separated aliases, then lists it', async () => {
    fetchAreas.mockResolvedValue([]);
    createArea.mockResolvedValue({ id: 9, name: 'Kielder', aliases: ['Kielder Forest', 'Kielder Water'], locationIds: [] });
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('add-area-btn'));
    fireEvent.change(screen.getByTestId('area-name-input'), { target: { value: 'Kielder' } });
    fireEvent.change(screen.getByTestId('area-aliases-input'), { target: { value: ' Kielder Forest , ,Kielder Water' } });
    fireEvent.click(screen.getByTestId('area-create-btn'));

    expect(await screen.findByTestId('area-row-9')).toBeInTheDocument();
    expect(createArea).toHaveBeenCalledWith({ name: 'Kielder', aliases: ['Kielder Forest', 'Kielder Water'] });
    expect(screen.queryByTestId('area-create-form')).not.toBeInTheDocument();
  });

  it('shows the server text naming a clash and keeps the create form open with what was typed', async () => {
    createArea.mockRejectedValue(clash("'Roman Wall' is already a name for the named area 'Hadrian's Wall'"));
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('add-area-btn'));
    fireEvent.change(screen.getByTestId('area-name-input'), { target: { value: 'Roman Wall' } });
    fireEvent.click(screen.getByTestId('area-create-btn'));

    expect(await screen.findByTestId('area-error')).toHaveTextContent("already a name for the named area");
    expect(screen.getByTestId('area-name-input')).toHaveValue('Roman Wall');
    expect(screen.getAllByTestId('area-row-7')).toHaveLength(1);
  });

  it('renames an area and replaces its aliases through updateArea only, never the members', async () => {
    updateArea.mockResolvedValue({ ...WALL, name: 'Hadrians Wall Country', aliases: ['Wall Country'] });
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-edit-7'));
    expect(screen.getByTestId('area-name-input')).toHaveValue("Hadrian's Wall");
    expect(screen.getByTestId('area-aliases-input')).toHaveValue('Hadrian Wall, Roman Wall');
    fireEvent.change(screen.getByTestId('area-name-input'), { target: { value: 'Hadrians Wall Country' } });
    fireEvent.change(screen.getByTestId('area-aliases-input'), { target: { value: 'Wall Country' } });
    fireEvent.click(screen.getByTestId('area-save-names'));

    expect(await screen.findByText('Hadrians Wall Country')).toBeInTheDocument();
    expect(updateArea).toHaveBeenCalledWith(7, { name: 'Hadrians Wall Country', aliases: ['Wall Country'] });
    expect(setAreaLocations).not.toHaveBeenCalled();
    expect(screen.getByTestId('area-count-7')).toHaveTextContent('2 places');
  });

  it('shows the current members ticked and edits them with the checkbox picker', async () => {
    setAreaLocations.mockResolvedValue({ ...WALL, locationIds: [2, 3] });
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-edit-7'));
    expect(screen.getByTestId('area-member-1')).toBeChecked();
    expect(screen.getByTestId('area-member-2')).toBeChecked();
    expect(screen.getByTestId('area-member-3')).not.toBeChecked();
    expect(screen.getByText('Allen Banks (disabled)')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('area-member-1'));
    fireEvent.click(screen.getByTestId('area-member-3'));
    fireEvent.click(screen.getByTestId('area-save-members'));

    expect(await screen.findByTestId('area-count-7')).toHaveTextContent('2 places');
    expect(setAreaLocations).toHaveBeenCalledWith(7, [2, 3]);
    expect(updateArea).not.toHaveBeenCalled();
  });

  it('filters the member picker by name without losing ticks on hidden rows', async () => {
    setAreaLocations.mockResolvedValue(WALL);
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-edit-7'));
    fireEvent.change(screen.getByTestId('area-member-filter'), { target: { value: 'caw' } });

    const list = screen.getByTestId('area-member-list');
    expect(within(list).getByText('Cawfields')).toBeInTheDocument();
    expect(within(list).queryByText('Walltown Crags')).not.toBeInTheDocument();

    fireEvent.change(screen.getByTestId('area-member-filter'), { target: { value: 'zzz' } });
    expect(within(list).getByText('No places match.')).toBeInTheDocument();

    fireEvent.change(screen.getByTestId('area-member-filter'), { target: { value: '' } });
    fireEvent.click(screen.getByTestId('area-save-members'));
    await screen.findByTestId('area-row-7');
    expect(setAreaLocations).toHaveBeenCalledWith(7, [1, 2]);
  });

  it('shows the error and keeps the picker when saving members fails', async () => {
    setAreaLocations.mockRejectedValue(Object.assign(new Error('bad'), {
      response: { status: 400, data: { error: 'No location with id 99' } },
    }));
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-edit-7'));
    fireEvent.click(screen.getByTestId('area-member-3'));
    fireEvent.click(screen.getByTestId('area-save-members'));

    expect(await screen.findByTestId('area-error')).toHaveTextContent('No location with id 99');
    expect(screen.getByTestId('area-member-3')).toBeChecked();
  });

  it('asks before deleting, and Keep deletes nothing', async () => {
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-delete-7'));
    expect(screen.getByTestId('area-delete-confirm-7')).toHaveTextContent("Delete Hadrian's Wall?");
    expect(deleteArea).not.toHaveBeenCalled();

    fireEvent.click(within(screen.getByTestId('area-delete-confirm-7')).getByText('Keep'));
    expect(screen.queryByTestId('area-delete-confirm-7')).not.toBeInTheDocument();
    expect(deleteArea).not.toHaveBeenCalled();
    expect(screen.getByTestId('area-row-7')).toBeInTheDocument();
  });

  it('deletes after the confirm and removes the row', async () => {
    deleteArea.mockResolvedValue(undefined);
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-delete-7'));
    fireEvent.click(screen.getByTestId('area-delete-yes-7'));

    expect(await screen.findByTestId('areas-empty')).toBeInTheDocument();
    expect(deleteArea).toHaveBeenCalledWith(7);
  });

  it('keeps the row and shows the error when the delete fails', async () => {
    deleteArea.mockRejectedValue(new Error('Network Error'));
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-delete-7'));
    fireEvent.click(screen.getByTestId('area-delete-yes-7'));

    expect(await screen.findByTestId('area-error')).toHaveTextContent('Network Error');
    expect(screen.getByTestId('area-row-7')).toBeInTheDocument();
  });
});
