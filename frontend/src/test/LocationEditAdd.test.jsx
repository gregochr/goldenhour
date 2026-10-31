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

import {
  fetchLocations, addLocation, updateLocation, geocodePlace, enrichLocation,
} from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';
import { fetchAreas, fetchUncoveredLocations, fetchLocationAliases } from '../api/areaApi';
import { fetchTideStats } from '../api/tideApi';

const DURHAM = {
  id: 1, name: 'Durham', lat: 54.77, lon: -1.58, enabled: true, solarEventType: ['SUNRISE', 'SUNSET'],
  locationType: ['LANDSCAPE'], tideType: [], consecutiveFailures: 0,
};
const BERWICK = {
  id: 2, name: 'Berwick-Upon-Tweed', lat: 55.77, lon: -2.0, enabled: true, solarEventType: ['SUNRISE', 'SUNSET'],
  locationType: ['SEASCAPE'], tideType: ['HIGH'], consecutiveFailures: 0,
};
const WOOD = {
  id: 3, name: 'Bluebell Wood', lat: 54.5, lon: -2.0, enabled: true, solarEventType: ['SUNRISE'],
  locationType: ['LANDSCAPE', 'WOODLAND'], tideType: [], consecutiveFailures: 0,
};
const REGIONS = [
  { id: 1, name: 'North East', enabled: true },
  { id: 2, name: 'Lake District', enabled: true },
  { id: 3, name: 'Switched Off Shire', enabled: false },
];

/** Opens a location's panel from its row, then its edit form. */
async function openEdit(id) {
  fireEvent.click(await screen.findByTestId(`locations-row-${id}`));
  fireEvent.click(await screen.findByTestId(`edit-location-${id}`));
  return screen.findByTestId('location-edit-panel');
}

function renderView(props = {}) {
  return render(<LocationManagementView onLocationsChanged={() => {}} {...props} />);
}

describe('LocationManagementView — the edit form', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    fetchLocations.mockResolvedValue([DURHAM]);
    fetchRegions.mockResolvedValue([]);
    fetchAreas.mockResolvedValue([]);
    fetchUncoveredLocations.mockResolvedValue([]);
    fetchLocationAliases.mockResolvedValue({});
    fetchTideStats.mockResolvedValue(null);
    updateLocation.mockResolvedValue({});
  });

  it('opens in the panel with every basic field filled from the location, and a pinned footer', async () => {
    renderView();
    await openEdit(1);

    expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Edit Durham');
    expect(screen.getByTestId('location-edit-name')).toHaveValue('Durham');
    expect(screen.getByTestId('location-edit-lat')).toHaveValue(54.77);
    expect(screen.getByTestId('location-edit-lon')).toHaveValue(-1.58);
    expect(screen.getByTestId('type-chip-LANDSCAPE')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('solar-chip-SUNRISE')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('solar-chip-ALLDAY')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('location-edit-save')).toHaveTextContent('Save changes');
    expect(screen.getByTestId('location-edit-cancel')).toBeInTheDocument();
    expect(screen.getByTestId('location-panel-footer')).toContainElement(screen.getByTestId('location-edit-save'));
    expect(screen.getByText('Seascapes need at least one.')).toBeInTheDocument();
  });

  it('moves focus to the panel title when it opens, and again when it returns to the view', async () => {
    renderView();
    await openEdit(1);
    expect(screen.getByTestId('location-panel-title')).toHaveFocus();

    fireEvent.click(screen.getByTestId('location-edit-cancel'));
    await screen.findByTestId('location-view-panel');
    expect(screen.getByTestId('location-panel-title')).toHaveFocus();
  });

  it('has no Bortle field: the sky facts are read-only here and set only when a location is added', async () => {
    renderView();
    await openEdit(1);

    expect(screen.queryByLabelText(/bortle/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/sky brightness/i)).not.toBeInTheDocument();
  });

  it('Cancel goes back to the view with nothing saved', async () => {
    renderView();
    await openEdit(1);
    fireEvent.change(screen.getByTestId('location-edit-name'), { target: { value: 'Changed' } });

    fireEvent.click(screen.getByTestId('location-edit-cancel'));

    expect(await screen.findByTestId('location-view-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('location-edit-name')).not.toBeInTheDocument();
    expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Durham');
    expect(updateLocation).not.toHaveBeenCalled();
  });

  it('a fresh edit after Cancel starts from the stored values, not the abandoned draft', async () => {
    renderView();
    await openEdit(1);
    fireEvent.change(screen.getByTestId('location-edit-name'), { target: { value: 'Abandoned' } });
    fireEvent.click(screen.getByTestId('location-edit-cancel'));

    await openEdit(1);

    expect(screen.getByTestId('location-edit-name')).toHaveValue('Durham');
  });

  it('Save sends the changed values, returns to the view and tells the app', async () => {
    const onChanged = vi.fn();
    fetchLocations.mockResolvedValueOnce([DURHAM]).mockResolvedValue([{ ...DURHAM, name: 'Durham Cathedral' }]);
    renderView({ onLocationsChanged: onChanged });
    await openEdit(1);
    fireEvent.change(screen.getByTestId('location-edit-name'), { target: { value: 'Durham Cathedral' } });

    fireEvent.click(screen.getByTestId('location-edit-save'));

    await waitFor(() => {
      expect(updateLocation).toHaveBeenCalledWith(1, {
        name: 'Durham Cathedral',
        lat: 54.77,
        lon: -1.58,
        solarEventTypes: ['SUNRISE', 'SUNSET'],
        locationTypes: ['LANDSCAPE'],
        tideTypes: [],
        regionId: null,
        mistProne: false,
      });
    });
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(await screen.findByTestId('location-view-panel')).toBeInTheDocument();
    expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Durham Cathedral');
  });

  it('sends a changed light choice', async () => {
    renderView();
    await openEdit(1);

    fireEvent.click(screen.getByTestId('solar-chip-ALLDAY'));
    fireEvent.click(screen.getByTestId('solar-chip-SUNRISE'));
    fireEvent.click(screen.getByTestId('location-edit-save'));

    await waitFor(() => expect(updateLocation).toHaveBeenCalledWith(1, expect.objectContaining({
      solarEventTypes: ['SUNSET', 'ALLDAY'],
    })));
  });

  it('the last remaining light choice cannot be turned off', async () => {
    renderView();
    await openEdit(1);

    fireEvent.click(screen.getByTestId('solar-chip-SUNRISE'));
    fireEvent.click(screen.getByTestId('solar-chip-SUNSET'));

    expect(screen.getByTestId('solar-chip-SUNSET')).toHaveAttribute('aria-pressed', 'true');
  });

  describe('mist-prone', () => {
    it('starts unticked for an unflagged place and submits a tick', async () => {
      renderView();
      await openEdit(1);

      const mist = screen.getByRole('checkbox', { name: /Mist forms here: valley floor, riverside, lake shore/ });
      expect(mist).toBe(screen.getByTestId('location-edit-mist-prone'));
      expect(mist).not.toBeChecked();
      fireEvent.click(mist);
      expect(mist).toBeChecked();
      fireEvent.click(screen.getByTestId('location-edit-save'));

      await waitFor(() => expect(updateLocation).toHaveBeenCalledWith(1, expect.objectContaining({ mistProne: true })));
    });

    it('starts ticked for a flagged place and submits the untick', async () => {
      fetchLocations.mockResolvedValue([{ ...DURHAM, mistProne: true }]);
      renderView();
      await openEdit(1);

      const mist = screen.getByTestId('location-edit-mist-prone');
      expect(mist).toBeChecked();
      fireEvent.click(mist);
      fireEvent.click(screen.getByTestId('location-edit-save'));

      await waitFor(() => expect(updateLocation).toHaveBeenCalledWith(1, expect.objectContaining({ mistProne: false })));
    });
  });

  describe('type', () => {
    it('keeps every type of a multi-typed location, not just the first, and shows each as pressed', async () => {
      fetchLocations.mockResolvedValue([WOOD]);
      renderView();
      await openEdit(3);
      expect(screen.getByTestId('type-chip-LANDSCAPE')).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByTestId('type-chip-WOODLAND')).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByTestId('type-chip-SEASCAPE')).toHaveAttribute('aria-pressed', 'false');

      fireEvent.change(screen.getByTestId('location-edit-name'), { target: { value: 'Bluebell Wood Renamed' } });
      fireEvent.click(screen.getByTestId('location-edit-save'));

      await waitFor(() => expect(updateLocation).toHaveBeenCalledWith(3, expect.objectContaining({
        name: 'Bluebell Wood Renamed',
        locationTypes: expect.arrayContaining(['LANDSCAPE', 'WOODLAND']),
      })));
      expect(updateLocation.mock.calls[0][1].locationTypes).toHaveLength(2);
    });

    it('the last remaining type cannot be turned off', async () => {
      renderView();
      await openEdit(1);

      fireEvent.click(screen.getByTestId('type-chip-LANDSCAPE'));
      expect(screen.getByTestId('type-chip-LANDSCAPE')).toHaveAttribute('aria-pressed', 'true');

      fireEvent.click(screen.getByTestId('location-edit-save'));
      await waitFor(() => expect(updateLocation).toHaveBeenCalledWith(1, expect.objectContaining({ locationTypes: ['LANDSCAPE'] })));
    });

    it('offers the five display types, Woodland among them, and not Bluebell', async () => {
      renderView();
      await openEdit(1);

      ['LANDSCAPE', 'WILDLIFE', 'SEASCAPE', 'WOODLAND', 'WATERFALL'].forEach((t) => {
        expect(screen.getByTestId(`type-chip-${t}`)).toBeInTheDocument();
      });
      expect(screen.getByTestId('type-chip-WOODLAND')).toHaveAttribute('title', 'Woodland');
      expect(screen.queryByTestId('type-chip-BLUEBELL')).not.toBeInTheDocument();
    });

    it('choosing Seascape enables the tide choices with all three selected, and dropping it clears them', async () => {
      renderView();
      await openEdit(1);
      ['HIGH', 'MID', 'LOW'].forEach((t) => expect(screen.getByTestId(`tide-chip-${t}`)).toBeDisabled());

      fireEvent.click(screen.getByTestId('type-chip-SEASCAPE'));

      ['HIGH', 'MID', 'LOW'].forEach((t) => {
        expect(screen.getByTestId(`tide-chip-${t}`)).not.toBeDisabled();
        expect(screen.getByTestId(`tide-chip-${t}`)).toHaveAttribute('aria-pressed', 'true');
      });

      // Landscape off leaves Seascape alone, so it is the last type and cannot be turned off; add Woodland first.
      fireEvent.click(screen.getByTestId('type-chip-LANDSCAPE'));
      fireEvent.click(screen.getByTestId('type-chip-SEASCAPE'));
      expect(screen.getByTestId('type-chip-SEASCAPE')).toHaveAttribute('aria-pressed', 'true');
      fireEvent.click(screen.getByTestId('type-chip-WOODLAND'));
      fireEvent.click(screen.getByTestId('type-chip-SEASCAPE'));
      expect(screen.getByTestId('type-chip-SEASCAPE')).toHaveAttribute('aria-pressed', 'false');
      ['HIGH', 'MID', 'LOW'].forEach((t) => expect(screen.getByTestId(`tide-chip-${t}`)).toBeDisabled());
      fireEvent.click(screen.getByTestId('location-edit-save'));
      await waitFor(() => expect(updateLocation).toHaveBeenCalledWith(1, expect.objectContaining({ tideTypes: [] })));
    });

    it('adding a non-coastal type to a coastal site keeps its tide preferences', async () => {
      fetchLocations.mockResolvedValue([BERWICK]);
      renderView();
      await openEdit(2);

      fireEvent.click(screen.getByTestId('type-chip-WOODLAND'));
      fireEvent.click(screen.getByTestId('location-edit-save'));

      await waitFor(() => expect(updateLocation).toHaveBeenCalledWith(2, expect.objectContaining({
        locationTypes: expect.arrayContaining(['SEASCAPE', 'WOODLAND']),
        tideTypes: ['HIGH'],
      })));
    });
  });

  describe('validation, shown under each field', () => {
    it('a blank name shows under Name and saves nothing', async () => {
      const onChanged = vi.fn();
      renderView({ onLocationsChanged: onChanged });
      await openEdit(1);

      fireEvent.change(screen.getByTestId('location-edit-name'), { target: { value: '   ' } });
      fireEvent.click(screen.getByTestId('location-edit-save'));

      const error = await screen.findByTestId('location-edit-error');
      expect(error).toHaveTextContent('Name cannot be blank.');
      expect(screen.getByTestId('location-edit-name').parentElement).toContainElement(error);
      expect(screen.getByTestId('location-edit-name')).toHaveAttribute('aria-invalid', 'true');
      expect(updateLocation).not.toHaveBeenCalled();
      expect(onChanged).not.toHaveBeenCalled();
    });

    it.each([
      ['location-edit-lat', '91', 'Latitude must be between -90 and 90.'],
      ['location-edit-lat', '-91', 'Latitude must be between -90 and 90.'],
      ['location-edit-lon', '181', 'Longitude must be between -180 and 180.'],
      ['location-edit-lon', '-181', 'Longitude must be between -180 and 180.'],
    ])('%s of %s shows %s under that field', async (testId, value, message) => {
      renderView();
      await openEdit(1);

      fireEvent.change(screen.getByTestId(testId), { target: { value } });
      fireEvent.click(screen.getByTestId('location-edit-save'));

      const error = await screen.findByTestId('location-edit-error');
      expect(error).toHaveTextContent(message);
      expect(screen.getByTestId(testId).parentElement).toContainElement(error);
      expect(updateLocation).not.toHaveBeenCalled();
    });

    it('a seascape with no tide preference shows the message under the tide choices and saves nothing', async () => {
      fetchLocations.mockResolvedValue([{ ...BERWICK, tideType: [] }]);
      renderView();
      await openEdit(2);

      fireEvent.click(screen.getByTestId('location-edit-save'));

      const error = await screen.findByTestId('location-edit-error');
      expect(error).toHaveTextContent('Coastal locations require at least one tide preference');
      expect(screen.getByTestId('tide-chips').parentElement).toContainElement(error);
      expect(updateLocation).not.toHaveBeenCalled();
    });

    it('a seascape with a tide preference saves without error', async () => {
      fetchLocations.mockResolvedValue([BERWICK]);
      renderView();
      await openEdit(2);

      fireEvent.click(screen.getByTestId('location-edit-save'));

      await waitFor(() => expect(updateLocation).toHaveBeenCalled());
      expect(screen.queryByTestId('location-edit-error')).not.toBeInTheDocument();
    });

    it('a refused save shows the server\'s sentence under the form and keeps the form open', async () => {
      updateLocation.mockRejectedValue(Object.assign(new Error('bad'), {
        response: { status: 409, data: { error: 'Turn Lake District on first.' } },
      }));
      renderView();
      await openEdit(1);

      fireEvent.click(screen.getByTestId('location-edit-save'));

      expect(await screen.findByTestId('location-edit-error')).toHaveTextContent('Turn Lake District on first.');
      expect(screen.getByTestId('location-edit-save')).not.toBeDisabled();
      expect(screen.getByTestId('location-edit-name')).toBeInTheDocument();
    });

    it('a plain failure shows its message', async () => {
      updateLocation.mockRejectedValue(new Error('Server error'));
      renderView();
      await openEdit(1);

      fireEvent.click(screen.getByTestId('location-edit-save'));

      expect(await screen.findByTestId('location-edit-error')).toHaveTextContent('Server error');
    });
  });

  it('offers the enabled regions plus the location\'s own, even when that one is switched off', async () => {
    fetchRegions.mockResolvedValue(REGIONS);
    fetchLocations.mockResolvedValue([{ ...DURHAM, region: REGIONS[2] }, { ...WOOD, region: REGIONS[0] }]);
    renderView();
    await openEdit(1);

    const options = within(screen.getByTestId('location-edit-region')).getAllByRole('option').map((o) => o.textContent);
    expect(options).toEqual(['— None —', 'North East', 'Lake District', 'Switched Off Shire']);
    expect(screen.getByTestId('location-edit-region')).toHaveValue('3');

    fireEvent.click(screen.getByTestId('location-edit-cancel'));
    await openEdit(3);
    const other = within(screen.getByTestId('location-edit-region')).getAllByRole('option').map((o) => o.textContent);
    expect(other).toEqual(['— None —', 'North East', 'Lake District']);
  });

  it('opening another row leaves the draft behind: only one location is edited at a time', async () => {
    fetchLocations.mockResolvedValue([DURHAM, BERWICK]);
    renderView();
    await openEdit(1);
    fireEvent.change(screen.getByTestId('location-edit-name'), { target: { value: 'Half typed' } });

    fireEvent.click(screen.getByTestId('locations-row-2'));

    expect(await screen.findByTestId('location-view-panel')).toBeInTheDocument();
    expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Berwick-Upon-Tweed');
    fireEvent.click(screen.getByTestId('edit-location-2'));
    expect(await screen.findByTestId('location-edit-name')).toHaveValue('Berwick-Upon-Tweed');
    fireEvent.click(screen.getByTestId('location-edit-cancel'));

    fireEvent.click(screen.getByTestId('locations-row-1'));
    await screen.findByTestId('location-view-panel');
    expect(screen.queryByTestId('location-edit-name')).not.toBeInTheDocument();
  });

  describe('a save still in flight when the admin moves on', () => {
    it('never touches the draft of the location opened meanwhile', async () => {
      let finish;
      updateLocation.mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }));
      fetchLocations.mockResolvedValue([DURHAM, BERWICK]);
      const onChanged = vi.fn();
      renderView({ onLocationsChanged: onChanged });
      await openEdit(1);
      fireEvent.change(screen.getByTestId('location-edit-name'), { target: { value: 'Durham renamed' } });
      fireEvent.click(screen.getByTestId('location-edit-save'));

      await openEdit(2);
      fireEvent.change(screen.getByTestId('location-edit-name'), { target: { value: 'Berwick draft' } });
      finish({});

      await waitFor(() => expect(onChanged).toHaveBeenCalled());
      expect(screen.getByTestId('location-edit-name')).toHaveValue('Berwick draft');
      expect(screen.getByTestId('location-edit-save')).not.toBeDisabled();
      expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Edit Berwick-Upon-Tweed');
    });

    it('never writes its failure onto the draft opened meanwhile', async () => {
      let refuse;
      updateLocation.mockReturnValueOnce(new Promise((_, reject) => { refuse = reject; }));
      fetchLocations.mockResolvedValue([DURHAM, BERWICK]);
      renderView();
      await openEdit(1);
      fireEvent.click(screen.getByTestId('location-edit-save'));
      await openEdit(2);

      refuse(new Error('Server error'));
      await new Promise((resolve) => { setTimeout(resolve, 0); });

      expect(screen.queryByTestId('location-edit-error')).not.toBeInTheDocument();
      expect(screen.getByTestId('location-edit-save')).not.toBeDisabled();
    });
  });

  describe('on a phone', () => {
    it('gives both forms a 44px Cancel', async () => {
      renderView();
      await openEdit(1);
      expect(screen.getByTestId('location-edit-cancel')).toHaveClass('max-[639px]:min-h-11');

      fireEvent.click(screen.getByTestId('add-location-btn'));
      expect(await screen.findByTestId('location-add-cancel')).toHaveClass('max-[639px]:min-h-11');
    });
  });

  describe('Escape', () => {
    it('in a field of the edit form cancels the edit, back to the view', async () => {
      renderView();
      await openEdit(1);

      fireEvent.keyDown(screen.getByTestId('location-edit-name'), { key: 'Escape' });

      expect(await screen.findByTestId('location-view-panel')).toBeInTheDocument();
      expect(screen.queryByTestId('location-edit-name')).not.toBeInTheDocument();
    });

    it('steps back from the edit form first, and a second press closes the panel', async () => {
      renderView();
      await openEdit(1);

      fireEvent.keyDown(document, { key: 'Escape' });
      await screen.findByTestId('location-view-panel');
      fireEvent.keyDown(document, { key: 'Escape' });

      expect(screen.queryByTestId('location-view-panel')).not.toBeInTheDocument();
    });

    it('in the list search box does not close the panel: a text field outside the form keeps its Escape', async () => {
      renderView();
      await openEdit(1);
      const search = screen.getByTestId('locations-search');
      search.focus();

      fireEvent.keyDown(search, { key: 'Escape' });

      expect(screen.getByTestId('location-edit-name')).toBeInTheDocument();
    });
  });

  describe('Add location', () => {
    beforeEach(() => {
      fetchRegions.mockResolvedValue(REGIONS);
      geocodePlace.mockResolvedValue({ lat: 54.6124, lon: -3.1179, displayName: 'Latrigg, UK' });
      enrichLocation.mockResolvedValue({
        bortleClass: 3, skyBrightnessSqm: 21.72, elevationMetres: 368, gridLat: 54.611, gridLng: -3.121,
      });
      addLocation.mockResolvedValue({ id: 99, name: 'Latrigg' });
    });

    async function openAdd() {
      renderView();
      fireEvent.click(await screen.findByTestId('add-location-btn'));
      return screen.findByTestId('location-add-panel');
    }

    async function lookUp(name = 'Latrigg') {
      fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: name } });
      fireEvent.click(screen.getByTestId('geocode-btn'));
      await screen.findByTestId('location-geocode-result');
    }

    async function enterManually(name = 'Keld Force', lat = '54.4', lon = '-2.1') {
      fireEvent.click(screen.getByTestId('manual-entry-btn'));
      fireEvent.change(screen.getByTestId('manual-name-input'), { target: { value: name } });
      fireEvent.change(screen.getByTestId('manual-lat-input'), { target: { value: lat } });
      fireEvent.change(screen.getByTestId('manual-lon-input'), { target: { value: lon } });
    }

    it('opens in the panel with no confirm dialog, and Add location disabled until there is a result', async () => {
      await openAdd();

      expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Add location');
      expect(screen.getByTestId('location-add-submit')).toBeDisabled();
      expect(screen.getByTestId('location-add-submit')).toHaveTextContent('Add location');
      expect(screen.getByTestId('location-add-hint')).toHaveTextContent('Search for a place above');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('enables Add location after a lookup, shows its coordinates and display name, and drops the hint', async () => {
      await openAdd();
      await lookUp();

      expect(screen.getByTestId('location-geocode-result')).toHaveTextContent('54.6124, -3.1179');
      expect(screen.getByTestId('location-geocode-result')).toHaveTextContent('Latrigg, UK');
      expect(screen.getByTestId('location-add-submit')).not.toBeDisabled();
      expect(screen.queryByTestId('location-add-hint')).not.toBeInTheDocument();
    });

    it('Enter in the place name looks it up', async () => {
      await openAdd();
      fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: 'Latrigg' } });

      fireEvent.keyDown(screen.getByTestId('place-name-input'), { key: 'Enter' });

      await waitFor(() => expect(geocodePlace).toHaveBeenCalledWith('Latrigg'));
    });

    it('asks for a place name when the lookup is pressed empty, and shows a failed lookup\'s message', async () => {
      await openAdd();
      fireEvent.click(screen.getByTestId('geocode-btn'));
      expect(await screen.findByTestId('location-geocode-error')).toHaveTextContent('Please enter a place name.');

      geocodePlace.mockRejectedValue(new Error('No results found for "Nowhere"'));
      fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: 'Nowhere' } });
      fireEvent.click(screen.getByTestId('geocode-btn'));
      await waitFor(() => expect(screen.getByTestId('location-geocode-error')).toHaveTextContent('No results found'));
      expect(screen.getByTestId('location-add-submit')).toBeDisabled();
    });

    it('passes the exact coordinates to the enrichment lookup', async () => {
      geocodePlace.mockResolvedValue({ lat: 55.609, lon: -1.7099, displayName: 'Bamburgh, UK' });
      await openAdd();
      await lookUp('Bamburgh');

      await waitFor(() => expect(enrichLocation).toHaveBeenCalledWith(55.609, -1.7099));
    });

    it('shows what was auto-detected, with units', async () => {
      await openAdd();
      await lookUp();

      await waitFor(() => expect(screen.getByTestId('enrichment-elevation')).toHaveTextContent('368 m'));
      expect(screen.getByTestId('enrichment-bortle')).toHaveTextContent('3');
      expect(screen.getByTestId('enrichment-sqm')).toHaveTextContent('21.72 SQM');
      expect(screen.getByTestId('enrichment-grid')).toHaveTextContent('54.611, -3.121');
    });

    it('shows a dash for each detected value that is null', async () => {
      enrichLocation.mockResolvedValue({
        bortleClass: null, skyBrightnessSqm: null, elevationMetres: 50, gridLat: null, gridLng: null,
      });
      await openAdd();
      await lookUp();

      await waitFor(() => expect(screen.getByTestId('enrichment-elevation')).toHaveTextContent('50 m'));
      expect(screen.getByTestId('enrichment-bortle')).toHaveTextContent('—');
      expect(screen.getByTestId('enrichment-sqm')).toHaveTextContent('—');
      expect(screen.getByTestId('enrichment-grid')).toHaveTextContent('—');
    });

    it('shows a pulsing Detecting line while the enrichment runs, then the facts', async () => {
      let land;
      enrichLocation.mockReturnValue(new Promise((resolve) => { land = resolve; }));
      await openAdd();
      await lookUp();

      expect(screen.getByTestId('enriching-spinner')).toHaveTextContent('Detecting elevation, Bortle, grid cell…');
      land({ bortleClass: 2, skyBrightnessSqm: 22, elevationMetres: 5, gridLat: 1, gridLng: 2 });

      await waitFor(() => expect(screen.queryByTestId('enriching-spinner')).not.toBeInTheDocument());
      expect(screen.getByTestId('enrichment-panel')).toBeInTheDocument();
    });

    it('sends what was detected and the ticked boxes, with no confirm step', async () => {
      await openAdd();
      await lookUp();
      await waitFor(() => expect(screen.getByTestId('enrichment-elevation')).toBeInTheDocument());
      fireEvent.click(screen.getByTestId('overlooks-water-checkbox'));

      fireEvent.click(screen.getByTestId('location-add-submit'));

      await waitFor(() => expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({
        name: 'Latrigg',
        lat: 54.6124,
        lon: -3.1179,
        bortleClass: 3,
        skyBrightnessSqm: 21.72,
        elevationMetres: 368,
        gridLat: 54.611,
        gridLng: -3.121,
        overlooksWater: true,
        coastalTidal: false,
        mistProne: false,
      })));
      expect(addLocation.mock.calls[0][0]).not.toHaveProperty('subRegionId');
    });

    it('sends coastalTidal true and overlooksWater false when only coastal tidal is ticked', async () => {
      await openAdd();
      await lookUp();
      fireEvent.click(screen.getByTestId('coastal-tidal-checkbox'));

      fireEvent.click(screen.getByTestId('location-add-submit'));

      await waitFor(() => expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({
        overlooksWater: false, coastalTidal: true,
      })));
    });

    it('offers the mist box beside the others, unticked, and sends a tick', async () => {
      await openAdd();
      await lookUp();
      const mist = screen.getByTestId('mist-prone-checkbox');
      expect(mist).not.toBeChecked();
      expect(mist.closest('label')).toHaveTextContent('Mist-prone');
      expect(mist).toHaveAccessibleDescription('Mist forms here: valley floor, riverside, lake shore');

      fireEvent.click(mist);
      fireEvent.click(screen.getByTestId('location-add-submit'));

      await waitFor(() => expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({ mistProne: true })));
    });

    it('sends null for every detected value when the enrichment failed', async () => {
      enrichLocation.mockRejectedValue(new Error('Network error'));
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
      await openAdd();
      await lookUp();
      await waitFor(() => expect(screen.queryByTestId('enriching-spinner')).not.toBeInTheDocument());

      fireEvent.click(screen.getByTestId('location-add-submit'));

      await waitFor(() => expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({
        bortleClass: null, skyBrightnessSqm: null, elevationMetres: null, gridLat: null, gridLng: null,
      })));
      consoleError.mockRestore();
    });

    describe('entering coordinates instead', () => {
      it('is offered from the start, and swaps to Name, Latitude and Longitude with the hint gone', async () => {
        await openAdd();
        expect(screen.getByTestId('manual-entry-btn')).toHaveTextContent('Enter coordinates instead');

        fireEvent.click(screen.getByTestId('manual-entry-btn'));

        expect(screen.getByTestId('manual-name-input')).toBeInTheDocument();
        expect(screen.queryByTestId('place-name-input')).not.toBeInTheDocument();
        expect(screen.queryByTestId('location-add-hint')).not.toBeInTheDocument();
        expect(screen.getByTestId('location-add-submit')).toBeDisabled();
      });

      it('carries the typed place name over as the name, and Back to place lookup returns', async () => {
        await openAdd();
        fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: '  Nowhere ' } });

        fireEvent.click(screen.getByTestId('manual-entry-btn'));
        expect(screen.getByTestId('manual-name-input')).toHaveValue('Nowhere');

        fireEvent.click(screen.getByTestId('back-to-geocode-btn'));
        expect(screen.getByTestId('place-name-input')).toBeInTheDocument();
      });

      it('enables Add location once the name and both coordinates are filled, and sends them with no detected values', async () => {
        await openAdd();
        await enterManually('Keld Force', '54.4', '-2.1');
        expect(screen.getByTestId('location-add-submit')).not.toBeDisabled();

        fireEvent.click(screen.getByTestId('location-add-submit'));

        await waitFor(() => expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({
          name: 'Keld Force', lat: 54.4, lon: -2.1, bortleClass: null, elevationMetres: null, gridLat: null,
        })));
        expect(enrichLocation).not.toHaveBeenCalled();
      });

      it('refuses an out-of-range latitude or longitude with the message under that field', async () => {
        await openAdd();
        await enterManually('Keld Force', '95', '-2.1');
        fireEvent.click(screen.getByTestId('location-add-submit'));
        const latError = await screen.findByTestId('location-add-error');
        expect(latError).toHaveTextContent('Latitude must be between -90 and 90.');
        expect(screen.getByTestId('manual-lat-input').parentElement).toContainElement(latError);

        fireEvent.change(screen.getByTestId('manual-lat-input'), { target: { value: '54' } });
        fireEvent.change(screen.getByTestId('manual-lon-input'), { target: { value: '200' } });
        fireEvent.click(screen.getByTestId('location-add-submit'));
        await waitFor(() => expect(screen.getByTestId('location-add-error')).toHaveTextContent('Longitude must be between -180 and 180.'));
        expect(screen.getByTestId('manual-lon-input').parentElement).toContainElement(screen.getByTestId('location-add-error'));
        expect(addLocation).not.toHaveBeenCalled();
      });

      it('a coastal site with its seeded tides passes validation', async () => {
        await openAdd();
        await enterManually('Seahouses', '55.6', '-1.7');
        fireEvent.click(screen.getByTestId('type-chip-SEASCAPE'));
        fireEvent.click(screen.getByTestId('type-chip-LANDSCAPE'));

        fireEvent.click(screen.getByTestId('location-add-submit'));

        await waitFor(() => expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({
          locationTypes: ['SEASCAPE'], tideTypes: ['HIGH', 'MID', 'LOW'],
        })));
        expect(screen.queryByTestId('location-add-error')).not.toBeInTheDocument();
      });
    });

    it('offers only the enabled regions', async () => {
      await openAdd();

      const options = within(screen.getByTestId('add-region')).getAllByRole('option').map((o) => o.textContent);
      expect(options).toEqual(['— None —', 'North East', 'Lake District']);
    });

    it('on success refreshes, tells the app and selects the new location, showing its panel', async () => {
      const onChanged = vi.fn();
      fetchLocations
        .mockResolvedValueOnce([DURHAM])
        .mockResolvedValue([DURHAM, { ...DURHAM, id: 99, name: 'Latrigg', lat: 54.6124, lon: -3.1179 }]);
      renderView({ onLocationsChanged: onChanged });
      fireEvent.click(await screen.findByTestId('add-location-btn'));
      await lookUp();

      fireEvent.click(screen.getByTestId('location-add-submit'));

      await waitFor(() => expect(onChanged).toHaveBeenCalled());
      expect(await screen.findByTestId('location-view-panel')).toBeInTheDocument();
      expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Latrigg');
      expect(window.location.hash).toBe('#manage/locations/99');
      expect(screen.queryByTestId('location-add-panel')).not.toBeInTheDocument();
      expect(screen.getByTestId('locations-row-99')).toHaveAttribute('aria-current', 'true');
    });

    it('still selects the new location when the re-read fails, from the server\'s reply', async () => {
      fetchLocations.mockResolvedValueOnce([DURHAM]).mockRejectedValue(new Error('down'));
      addLocation.mockResolvedValue({ ...DURHAM, id: 99, name: 'Latrigg' });
      renderView();
      fireEvent.click(await screen.findByTestId('add-location-btn'));
      await lookUp();

      fireEvent.click(screen.getByTestId('location-add-submit'));

      expect(await screen.findByTestId('location-view-panel')).toBeInTheDocument();
      expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Latrigg');
    });

    it('a refused add shows the server\'s sentence, keeps the form and tells nobody', async () => {
      addLocation.mockRejectedValue({ response: { status: 409, data: { error: 'Turn North East on first.' } } });
      const onChanged = vi.fn();
      renderView({ onLocationsChanged: onChanged });
      fireEvent.click(await screen.findByTestId('add-location-btn'));
      await lookUp();

      fireEvent.click(screen.getByTestId('location-add-submit'));

      expect(await screen.findByTestId('location-add-error')).toHaveTextContent('Turn North East on first.');
      expect(screen.getByTestId('location-add-submit')).not.toBeDisabled();
      expect(onChanged).not.toHaveBeenCalled();
    });

    it('shows the form\'s heading copy, and the detected Bortle with what it means', async () => {
      await openAdd();
      expect(screen.getByTestId('location-panel-subtitle')).toHaveTextContent('Look up a place, then check the details.');

      await lookUp();

      await waitFor(() => expect(screen.getByTestId('enrichment-bortle')).toHaveTextContent('3 Moderate, aurora-friendly'));
    });

    it('keeps the edited name but the looked-up coordinates, and the result box says what will be saved', async () => {
      await openAdd();
      await lookUp('Latrigg');
      fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: 'Latrigg Fell' } });

      expect(screen.getByTestId('location-add-save-name')).toHaveTextContent('Added as Latrigg Fell');
      expect(screen.getByTestId('location-geocode-result')).toHaveTextContent('54.6124, -3.1179');
      await waitFor(() => expect(screen.getByTestId('enrichment-elevation')).toBeInTheDocument());
      fireEvent.click(screen.getByTestId('location-add-submit'));

      await waitFor(() => expect(addLocation).toHaveBeenCalledWith(expect.objectContaining({
        name: 'Latrigg Fell', lat: 54.6124, lon: -3.1179,
      })));
    });

    it('disables Add location while the enrichment is still running', async () => {
      let land;
      enrichLocation.mockReturnValue(new Promise((resolve) => { land = resolve; }));
      await openAdd();
      await lookUp();

      expect(screen.getByTestId('location-add-submit')).toBeDisabled();
      land({ bortleClass: 2, skyBrightnessSqm: 22, elevationMetres: 5, gridLat: 1, gridLng: 2 });

      await waitFor(() => expect(screen.getByTestId('location-add-submit')).not.toBeDisabled());
    });

    it('ignores an enrichment reply that belongs to an earlier lookup', async () => {
      let landFirst;
      enrichLocation
        .mockReturnValueOnce(new Promise((resolve) => { landFirst = resolve; }))
        .mockResolvedValueOnce({ bortleClass: 1, skyBrightnessSqm: 22, elevationMetres: 111, gridLat: 5, gridLng: 6 });
      await openAdd();
      await lookUp('First');

      geocodePlace.mockResolvedValue({ lat: 50.5, lon: -2.5, displayName: 'Second, UK' });
      fireEvent.change(screen.getByTestId('place-name-input'), { target: { value: 'Second' } });
      fireEvent.click(screen.getByTestId('geocode-btn'));
      await waitFor(() => expect(screen.getByTestId('enrichment-elevation')).toHaveTextContent('111 m'));

      landFirst({ bortleClass: 9, skyBrightnessSqm: 18, elevationMetres: 999, gridLat: 9, gridLng: 9 });
      await new Promise((resolve) => { setTimeout(resolve, 0); });

      expect(screen.getByTestId('enrichment-elevation')).toHaveTextContent('111 m');
      expect(screen.getByTestId('location-add-submit')).not.toBeDisabled();
    });

    it('does not select the new location when the admin opened another row while it was being added', async () => {
      let finish;
      addLocation.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
      const onChanged = vi.fn();
      fetchLocations.mockResolvedValue([DURHAM, { ...DURHAM, id: 99, name: 'Latrigg' }]);
      renderView({ onLocationsChanged: onChanged });
      fireEvent.click(await screen.findByTestId('add-location-btn'));
      await lookUp();
      fireEvent.click(screen.getByTestId('location-add-submit'));

      fireEvent.click(screen.getByTestId('locations-row-1'));
      await waitFor(() => expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Durham'));
      finish({ id: 99, name: 'Latrigg' });

      await waitFor(() => expect(onChanged).toHaveBeenCalled());
      expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Durham');
      expect(window.location.hash).toBe('#manage/locations/1');
    });

    it('does not select the new location when the admin closed the form while it was being added', async () => {
      let finish;
      addLocation.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
      const onChanged = vi.fn();
      renderView({ onLocationsChanged: onChanged });
      fireEvent.click(await screen.findByTestId('add-location-btn'));
      await lookUp();
      fireEvent.click(screen.getByTestId('location-add-submit'));
      fireEvent.click(screen.getByTestId('location-panel-close'));

      finish({ id: 99, name: 'Latrigg' });

      await waitFor(() => expect(onChanged).toHaveBeenCalled());
      expect(screen.queryByTestId('location-view-panel')).not.toBeInTheDocument();
      expect(window.location.hash).not.toBe('#manage/locations/99');
    });

    it('Cancel and Escape close the form, and a reopened form starts empty', async () => {
      await openAdd();
      await lookUp();

      fireEvent.click(screen.getByTestId('location-add-cancel'));
      expect(screen.queryByTestId('location-add-panel')).not.toBeInTheDocument();

      fireEvent.click(screen.getByTestId('add-location-btn'));
      expect(await screen.findByTestId('place-name-input')).toHaveValue('');
      expect(screen.queryByTestId('location-geocode-result')).not.toBeInTheDocument();

      fireEvent.keyDown(screen.getByTestId('place-name-input'), { key: 'Escape' });
      expect(screen.queryByTestId('location-add-panel')).not.toBeInTheDocument();
    });

    it('opening Add location while editing drops the edit, and opening a row while adding drops the form', async () => {
      renderView();
      await openEdit(1);
      fireEvent.click(screen.getByTestId('add-location-btn'));

      expect(await screen.findByTestId('location-add-panel')).toBeInTheDocument();
      expect(screen.queryByTestId('location-edit-name')).not.toBeInTheDocument();

      fireEvent.click(screen.getByTestId('locations-row-1'));
      expect(await screen.findByTestId('location-view-panel')).toBeInTheDocument();
      expect(screen.queryByTestId('location-add-panel')).not.toBeInTheDocument();
    });
  });
});
