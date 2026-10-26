import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within, waitFor } from '@testing-library/react';
import AreasSection from '../components/AreasSection.jsx';

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

import {
  fetchAreas, fetchUncoveredLocations, createArea, updateArea, setAreaLocations, deleteArea,
} from '../api/areaApi';

const KIELDER = { id: 8, name: 'Kielder', aliases: [], locationIds: [3] };

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

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
    fetchUncoveredLocations.mockResolvedValue([]);
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
    expect(screen.queryByTestId('add-area-btn')).not.toBeInTheDocument();
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

    expect(await screen.findByTestId('area-error')).toHaveTextContent('Failed to delete the area.');
    expect(screen.getByTestId('area-row-7')).toBeInTheDocument();
  });

  it('ticks a box while the picker is filtered, clears the filter, and sends the full set', async () => {
    setAreaLocations.mockResolvedValue(WALL);
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-edit-7'));
    fireEvent.change(screen.getByTestId('area-member-filter'), { target: { value: 'allen' } });
    fireEvent.click(screen.getByTestId('area-member-3'));
    fireEvent.change(screen.getByTestId('area-member-filter'), { target: { value: '' } });
    fireEvent.click(screen.getByTestId('area-save-members'));

    await screen.findByTestId('area-saved');
    expect(setAreaLocations).toHaveBeenCalledWith(7, [1, 2, 3]);
  });

  it('shows a short "Saved" line after saving names and after saving members', async () => {
    updateArea.mockResolvedValue(WALL);
    setAreaLocations.mockResolvedValue(WALL);
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-edit-7'));
    expect(screen.queryByTestId('area-saved')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('area-save-names'));
    expect(await screen.findByTestId('area-saved')).toHaveTextContent('Saved');
    fireEvent.click(screen.getByTestId('area-save-members'));
    await waitFor(() => expect(setAreaLocations).toHaveBeenCalled());
    expect(await screen.findByTestId('area-saved')).toHaveTextContent('Saved');
  });

  it('labels the filter "Filter places" and puts the selected count on the group, not the filter', async () => {
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-edit-7'));

    expect(screen.getByLabelText('Filter places')).toBe(screen.getByTestId('area-member-filter'));
    expect(screen.getByRole('group', { name: 'Places in this area (2 selected)' })).toBeInTheDocument();
  });

  it('disables every other Edit, Delete and Add while a save is in flight, and the late 409 stays on its own area', async () => {
    fetchAreas.mockResolvedValue([WALL, KIELDER]);
    const slow = deferred();
    updateArea.mockReturnValue(slow.promise);
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-edit-7'));
    fireEvent.click(screen.getByTestId('area-save-names'));

    expect(screen.getByTestId('add-area-btn')).toBeDisabled();
    expect(screen.getByTestId('area-edit-8')).toBeDisabled();
    expect(screen.getByTestId('area-delete-7')).toBeDisabled();
    expect(screen.getByTestId('area-delete-8')).toBeDisabled();
    fireEvent.click(screen.getByTestId('area-edit-8'));
    expect(screen.queryByTestId('area-editor-8')).not.toBeInTheDocument();

    slow.reject(clash("'Kielder' is already the named area 'Kielder'"));
    const editor7 = screen.getByTestId('area-editor-7');
    expect(await within(editor7).findByTestId('area-error')).toHaveTextContent('already the named area');
    expect(screen.getByTestId('add-area-btn')).not.toBeDisabled();

    fireEvent.click(screen.getByTestId('area-edit-8'));
    expect(within(screen.getByTestId('area-editor-8')).queryByTestId('area-error')).not.toBeInTheDocument();
  });

  describe('sub-regions', () => {
    const SWALEDALE = {
      id: 21, name: 'Swaledale', aliases: ['Teesdale'], locationIds: [1, 3], kind: 'SUB_REGION',
      regionId: 4, regionName: 'The Yorkshire Dales',
    };

    it('says whether each area is a named area or a sub-region, and which region the sub-region is in', async () => {
      fetchAreas.mockResolvedValue([WALL, SWALEDALE]);
      render(<AreasSection locations={LOCATIONS} />);

      expect(await screen.findByTestId('area-kind-21')).toHaveTextContent('Sub-region of The Yorkshire Dales');
      expect(screen.getByTestId('area-kind-7')).toHaveTextContent('Named area');
      expect(screen.getByTestId('area-count-21')).toHaveTextContent('2 places');
    });

    it('lists a sub-region\'s members read-only, with the note that membership is set on the location', async () => {
      fetchAreas.mockResolvedValue([SWALEDALE]);
      render(<AreasSection locations={LOCATIONS} />);

      fireEvent.click(await screen.findByTestId('area-edit-21'));

      const members = screen.getByTestId('area-readonly-members-21');
      expect(within(members).getByText('Walltown Crags')).toBeInTheDocument();
      expect(within(members).getByText('Allen Banks')).toBeInTheDocument();
      expect(within(members).getByText(/Membership is set on the location/)).toBeInTheDocument();
      expect(screen.queryByTestId('area-save-members')).not.toBeInTheDocument();
      expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
      expect(screen.queryByTestId('area-member-filter')).not.toBeInTheDocument();
    });

    it('still renames a sub-region and says its other names may be one word', async () => {
      fetchAreas.mockResolvedValue([SWALEDALE]);
      updateArea.mockResolvedValue({ ...SWALEDALE, aliases: ['Keld', 'Teesdale'] });
      render(<AreasSection locations={LOCATIONS} />);

      fireEvent.click(await screen.findByTestId('area-edit-21'));
      expect(screen.getByLabelText('Also known as (comma-separated, one word or more each)')).toBeInTheDocument();
      fireEvent.change(screen.getByTestId('area-aliases-input'), { target: { value: 'Keld, Teesdale' } });
      fireEvent.click(screen.getByTestId('area-save-names'));

      expect(await screen.findByTestId('area-saved')).toBeInTheDocument();
      expect(updateArea).toHaveBeenCalledWith(21, { name: 'Swaledale', aliases: ['Keld', 'Teesdale'] });
      expect(setAreaLocations).not.toHaveBeenCalled();
    });

    it('keeps the two-word hint for a named area', async () => {
      render(<AreasSection locations={LOCATIONS} />);

      fireEvent.click(await screen.findByTestId('area-edit-7'));

      expect(screen.getByLabelText('Also known as (comma-separated, each at least two words)')).toBeInTheDocument();
    });

    it('words the delete confirm for a sub-region, and shows the server\'s 409 when it still has members', async () => {
      fetchAreas.mockResolvedValue([SWALEDALE]);
      deleteArea.mockRejectedValue(clash("Sub-region 'Swaledale' still has 2 locations: move them first"));
      render(<AreasSection locations={LOCATIONS} />);

      fireEvent.click(await screen.findByTestId('area-delete-21'));
      expect(screen.getByTestId('area-delete-confirm-21'))
        .toHaveTextContent('Only a sub-region with no locations can be deleted.');
      fireEvent.click(screen.getByTestId('area-delete-yes-21'));

      expect(await screen.findByTestId('area-error')).toHaveTextContent('still has 2 locations');
      expect(screen.getByTestId('area-row-21')).toBeInTheDocument();
    });

    it('names the enabled locations that have no sub-region, with their regions', async () => {
      fetchAreas.mockResolvedValue([SWALEDALE]);
      fetchUncoveredLocations.mockResolvedValue([
        { id: 30, name: 'Bamburgh Castle', regionId: 5, regionName: 'Northumberland & Tyneside' },
        { id: 31, name: 'Cat Bells', regionId: 3, regionName: 'The Lake District' },
      ]);
      render(<AreasSection locations={LOCATIONS} />);

      const line = await screen.findByTestId('areas-uncovered');
      expect(line).toHaveTextContent(
        '2 enabled locations have no sub-region: Bamburgh Castle (Northumberland & Tyneside), '
        + 'Cat Bells (The Lake District)',
      );
      expect(screen.queryByTestId('areas-all-covered')).not.toBeInTheDocument();
    });

    it('uses the singular for one uncovered location, and cuts a long list with a count', async () => {
      fetchAreas.mockResolvedValue([SWALEDALE]);
      fetchUncoveredLocations.mockResolvedValueOnce([
        { id: 30, name: 'Bamburgh Castle', regionId: 5, regionName: 'Northumberland & Tyneside' },
      ]);
      const first = render(<AreasSection locations={LOCATIONS} />);
      expect(await screen.findByTestId('areas-uncovered'))
        .toHaveTextContent('1 enabled location has no sub-region: Bamburgh Castle');
      first.unmount();

      const many = Array.from({ length: 15 }, (_, i) => (
        { id: 100 + i, name: `Crag ${i + 1}`, regionId: 4, regionName: 'The Yorkshire Dales' }
      ));
      fetchUncoveredLocations.mockResolvedValueOnce(many);
      render(<AreasSection locations={LOCATIONS} />);
      const line = await screen.findByTestId('areas-uncovered');
      expect(line).toHaveTextContent('15 enabled locations have no sub-region');
      expect(line).toHaveTextContent('Crag 12 (The Yorkshire Dales), and 3 more');
      expect(line).not.toHaveTextContent('Crag 13');
    });

    it('says every location is covered when sub-regions exist and none is left out, and is silent without sub-regions', async () => {
      fetchAreas.mockResolvedValue([SWALEDALE]);
      const first = render(<AreasSection locations={LOCATIONS} />);
      expect(await screen.findByTestId('areas-all-covered')).toBeInTheDocument();
      expect(screen.queryByTestId('areas-uncovered')).not.toBeInTheDocument();
      first.unmount();

      fetchAreas.mockResolvedValue([WALL]);
      render(<AreasSection locations={LOCATIONS} />);
      await screen.findByTestId('area-row-7');
      expect(screen.queryByTestId('areas-all-covered')).not.toBeInTheDocument();
      expect(screen.queryByTestId('areas-uncovered')).not.toBeInTheDocument();
    });

    it('shows no coverage line when the coverage read fails, and still lists the areas', async () => {
      fetchUncoveredLocations.mockRejectedValue(new Error('boom'));
      render(<AreasSection locations={LOCATIONS} />);

      expect(await screen.findByTestId('area-row-7')).toBeInTheDocument();
      expect(screen.queryByTestId('areas-uncovered')).not.toBeInTheDocument();
      expect(screen.queryByTestId('areas-all-covered')).not.toBeInTheDocument();
    });

    it('reads the areas and the coverage again when reloadKey changes', async () => {
      const { rerender } = render(<AreasSection locations={LOCATIONS} reloadKey={0} />);
      await screen.findByTestId('area-row-7');
      expect(fetchAreas).toHaveBeenCalledTimes(1);
      expect(fetchUncoveredLocations).toHaveBeenCalledTimes(1);

      fetchAreas.mockResolvedValue([WALL, SWALEDALE]);
      rerender(<AreasSection locations={LOCATIONS} reloadKey={1} />);

      expect(await screen.findByTestId('area-row-21')).toBeInTheDocument();
      expect(fetchAreas).toHaveBeenCalledTimes(2);
      expect(fetchUncoveredLocations).toHaveBeenCalledTimes(2);
    });
  });

  it('opening a delete confirm on one row leaves another row\'s unsaved editor alone', async () => {
    fetchAreas.mockResolvedValue([WALL, KIELDER]);
    render(<AreasSection locations={LOCATIONS} />);

    fireEvent.click(await screen.findByTestId('area-edit-7'));
    fireEvent.change(screen.getByTestId('area-name-input'), { target: { value: 'Typed but unsaved' } });
    fireEvent.click(screen.getByTestId('area-delete-8'));

    expect(screen.getByTestId('area-delete-confirm-8')).toBeInTheDocument();
    expect(screen.getByTestId('area-name-input')).toHaveValue('Typed but unsaved');
  });
});
