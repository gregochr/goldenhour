import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import RegionManagementView from '../components/RegionManagementView.jsx';

vi.mock('../api/regionApi', () => ({
  fetchRegions: vi.fn(),
  addRegion: vi.fn(),
  updateRegion: vi.fn(),
  setRegionEnabled: vi.fn(),
  setRegionBase: vi.fn(),
}));
vi.mock('../api/forecastApi', () => ({ fetchLocations: vi.fn() }));
vi.mock('../api/areaApi', () => ({ fetchAreas: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn(() => false) }));

import {
  fetchRegions, addRegion, updateRegion, setRegionEnabled, setRegionBase,
} from '../api/regionApi';
import { fetchLocations } from '../api/forecastApi';
import { fetchAreas } from '../api/areaApi';

const MOCK_REGIONS = [
  { id: 1, name: 'North East', enabled: true, createdAt: '2026-01-15T10:00:00' },
  { id: 2, name: 'Lake District', enabled: true, createdAt: '2026-02-01T10:00:00' },
  { id: 3, name: 'Scotland', enabled: false, createdAt: '2026-03-01T10:00:00' },
];

// North East has 2 locations (1 on, 1 off), Lake District 1 (on), Scotland 1 (off, as a switched-off region must hold).
const MOCK_LOCATIONS = [
  { id: 1, name: 'Durham', enabled: true, region: { id: 1, name: 'North East' } },
  { id: 2, name: 'Bamburgh', enabled: false, region: { id: 1, name: 'North East' } },
  { id: 3, name: 'Keswick', enabled: true, region: { id: 2, name: 'Lake District' } },
  { id: 5, name: 'Skye', enabled: false, region: { id: 3, name: 'Scotland' } },
  { id: 4, name: 'No Region', enabled: true, region: null },
];

const MOCK_AREAS = [
  { id: 10, name: 'Northumberland coast', kind: 'SUB_REGION', regionId: 1, locationIds: [1, 2] },
  { id: 11, name: 'Durham dales', kind: 'SUB_REGION', regionId: 1, locationIds: [] },
  { id: 12, name: 'Western fells', kind: 'SUB_REGION', regionId: 2, locationIds: [3] },
  { id: 13, name: 'A named area', kind: 'NAMED', regionId: null, locationIds: [1] },
];

function makeMockRegions(count) {
  return Array.from({ length: count }, (_, i) => ({
    id: i + 1,
    name: `Region ${i + 1}`,
    enabled: true,
    createdAt: '2026-01-01T10:00:00',
  }));
}

/** The region names in the grid, in row order. */
function listedNames() {
  return screen.getAllByTestId(/^regions-row-/)
    .map((row) => row.querySelector('[data-testid^="regions-cell-region-"] span').textContent);
}

async function renderLoaded() {
  render(<RegionManagementView />);
  await screen.findByTestId('regions');
}

async function openPanel(id) {
  fireEvent.click(await screen.findByTestId(`regions-row-${id}`));
  return screen.findByTestId('region-reader-panel');
}

describe('RegionManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    fetchRegions.mockResolvedValue(MOCK_REGIONS);
    fetchLocations.mockResolvedValue(MOCK_LOCATIONS);
    fetchAreas.mockResolvedValue(MOCK_AREAS);
  });

  describe('the list', () => {
    it('renders the header with the on and off split and Add region', async () => {
      await renderLoaded();
      expect(screen.getByRole('heading', { name: /^Regions/ })).toBeInTheDocument();
      expect(screen.getByTestId('region-total')).toHaveTextContent('2 on · 1 off');
      expect(screen.getByTestId('add-region-btn')).toHaveTextContent('Add region');
    });

    it('lists the regions that are on, alphabetically, and keeps the off ones out of sight', async () => {
      await renderLoaded();
      expect(listedNames()).toEqual(['Lake District', 'North East']);
      expect(screen.queryByText('Scotland')).not.toBeInTheDocument();
    });

    it('shows a collapsed "Off · n" group header that opens to reveal the off regions', async () => {
      await renderLoaded();
      const header = screen.getByTestId('regions-group-off');
      expect(header).toHaveTextContent('Off · 1');
      expect(header).toHaveAttribute('aria-expanded', 'false');

      fireEvent.click(header);

      expect(screen.getByTestId('regions-group-off')).toHaveAttribute('aria-expanded', 'true');
      expect(listedNames()).toEqual(['Lake District', 'North East', 'Scotland']);
      // The header sits between the two groups.
      const order = screen.getAllByTestId(/^regions-(row|group)-/).map((el) => el.dataset.testid);
      expect(order).toEqual(['regions-row-2', 'regions-row-1', 'regions-group-off', 'regions-row-3']);

      fireEvent.click(screen.getByTestId('regions-group-off'));
      expect(screen.queryByText('Scotland')).not.toBeInTheDocument();
    });

    it('opens and closes the group from the keyboard', async () => {
      const user = userEvent.setup();
      await renderLoaded();
      screen.getByTestId('regions-group-off').focus();
      await user.keyboard('{Enter}');
      expect(screen.getByTestId('regions-group-off')).toHaveAttribute('aria-expanded', 'true');
      await user.keyboard(' ');
      expect(screen.getByTestId('regions-group-off')).toHaveAttribute('aria-expanded', 'false');
    });

    it('has no group header when every region is on', async () => {
      fetchRegions.mockResolvedValue(MOCK_REGIONS.slice(0, 2));
      await renderLoaded();
      expect(screen.queryByTestId('regions-group-off')).not.toBeInTheDocument();
      expect(screen.getByTestId('region-total')).toHaveTextContent('2 on · 0 off');
    });

    it('opens the Off group when the hash selects an off region', async () => {
      window.location.hash = '#manage/regions/3';
      await renderLoaded();
      expect(screen.getByTestId('regions-group-off')).toHaveAttribute('aria-expanded', 'true');
      expect(screen.getByTestId('regions-row-3')).toHaveAttribute('aria-current', 'true');
      expect(await screen.findByTestId('region-reader-panel')).toBeInTheDocument();
    });

    it('counts sub-regions (SUB_REGION areas of the region) and every location in it', async () => {
      await renderLoaded();
      expect(screen.getByTestId('region-sub-region-count-1')).toHaveTextContent('2');
      expect(screen.getByTestId('region-sub-region-count-2')).toHaveTextContent('1');
      // Both of North East's locations count, switched on or not.
      expect(screen.getByTestId('region-location-count-1')).toHaveTextContent('2');
      expect(screen.getByTestId('region-location-count-2')).toHaveTextContent('1');
    });

    it('shows 0 for regions with no locations or sub-regions', async () => {
      fetchLocations.mockResolvedValue([]);
      fetchAreas.mockResolvedValue([]);
      await renderLoaded();
      expect(screen.getByTestId('region-location-count-1')).toHaveTextContent('0');
      expect(screen.getByTestId('region-sub-region-count-1')).toHaveTextContent('0');
    });

    it('formats the created date with the shared date helper', async () => {
      await renderLoaded();
      expect(screen.getByTestId('regions-cell-created-1')).toHaveTextContent('15 Jan 2026');
    });

    it('puts the counts on the second card line and gives each row a full accessible name', async () => {
      await renderLoaded();
      expect(screen.getByTestId('regions-cell-card-summary-1')).toHaveTextContent('2 sub-regions · 2 locations');
      expect(screen.getByTestId('regions-cell-card-summary-2')).toHaveTextContent('1 sub-region · 1 location');
      expect(screen.getByTestId('regions-row-1'))
        .toHaveAccessibleName('North East, enabled, 2 sub-regions · 2 locations, created 15 Jan 2026');
    });

    it('holds no interactive element inside a row', async () => {
      await renderLoaded();
      expect(within(screen.getByTestId('regions-row-1')).queryAllByRole('button')).toHaveLength(0);
    });

    it('shows the empty state when there are no regions', async () => {
      fetchRegions.mockResolvedValue([]);
      render(<RegionManagementView />);
      expect(await screen.findByText('No regions configured. Add one to get started.')).toBeInTheDocument();
    });

    it('shows an error and no list when the regions fail to load', async () => {
      fetchRegions.mockRejectedValue(new Error('down'));
      render(<RegionManagementView />);
      expect(await screen.findByTestId('regions-load-error')).toHaveTextContent("Couldn't load regions.");
      expect(screen.queryByTestId('regions')).not.toBeInTheDocument();
    });

    it('still lists the regions, with a notice, when locations or sub-regions fail to load', async () => {
      fetchLocations.mockRejectedValue(new Error('down'));
      await renderLoaded();
      expect(listedNames()).toEqual(['Lake District', 'North East']);
      expect(screen.getByTestId('region-notice')).toBeInTheDocument();
    });

    it('sorts by a header and reverses on a second press', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('regions-sort-name'));
      expect(listedNames()).toEqual(['North East', 'Lake District']);
      fireEvent.click(screen.getByTestId('regions-sort-locations'));
      expect(listedNames()).toEqual(['Lake District', 'North East']);
    });

    it('does not offer to sort by status (the groups already split it)', async () => {
      await renderLoaded();
      expect(screen.queryByTestId('regions-sort-status')).not.toBeInTheDocument();
    });
  });

  describe('pagination', () => {
    it('paginates regions when more than page size', async () => {
      fetchRegions.mockResolvedValue(makeMockRegions(15));
      await renderLoaded();

      expect(screen.getByText('Region 1')).toBeInTheDocument();
      expect(screen.getByText('Region 4')).toBeInTheDocument();
      expect(screen.queryByText('Region 5')).not.toBeInTheDocument();
      expect(screen.getByTestId('pagination-summary')).toHaveTextContent('1–10 of 15');

      fireEvent.click(screen.getByTestId('pagination-next'));
      expect(await screen.findByText('Region 5')).toBeInTheDocument();
      expect(screen.queryByText('Region 1')).not.toBeInTheDocument();
      expect(screen.getByTestId('pagination-summary')).toHaveTextContent('11–15 of 15');
    });

    it('hides pagination when all regions fit on one page', async () => {
      await renderLoaded();
      expect(screen.queryByTestId('pagination')).not.toBeInTheDocument();
    });

    it('page size change works', async () => {
      fetchRegions.mockResolvedValue(makeMockRegions(30));
      await renderLoaded();
      expect(screen.getByTestId('pagination-summary')).toHaveTextContent('1–10 of 30');
      fireEvent.click(screen.getByTestId('pagination-size-25'));
      await waitFor(() => {
        expect(screen.getByTestId('pagination-summary')).toHaveTextContent('1–25 of 30');
      });
    });

    it('puts the collapsed Off group header after the last on row, on the last page', async () => {
      fetchRegions.mockResolvedValue([...makeMockRegions(12), { id: 99, name: 'Zed', enabled: false, createdAt: '2026-01-01T10:00:00' }]);
      await renderLoaded();
      expect(screen.queryByTestId('regions-group-off')).not.toBeInTheDocument();
      fireEvent.click(screen.getByTestId('pagination-next'));
      expect(await screen.findByTestId('regions-group-off')).toBeInTheDocument();
      expect(screen.getByTestId('pagination-summary')).toHaveTextContent('11–12 of 12');
    });
  });

  describe('the region panel', () => {
    it('opens on a row, writes the hash and shows the meta line', async () => {
      await renderLoaded();
      await openPanel(1);
      expect(window.location.hash).toBe('#manage/regions/1');
      expect(screen.getByTestId('region-panel-title')).toHaveTextContent('North East');
      expect(screen.getByTestId('region-panel-subtitle')).toHaveTextContent('On · created 15 Jan 2026');
    });

    it('shows the base, or "Not set" for a region without one', async () => {
      fetchRegions.mockResolvedValue([
        { ...MOCK_REGIONS[0], baseName: 'Alnwick', baseLat: 55.4137, baseLon: -1.706 },
        MOCK_REGIONS[1],
      ]);
      await renderLoaded();
      await openPanel(1);
      expect(screen.getByTestId('region-base-1')).toHaveTextContent('Alnwick');
      expect(screen.getByTestId('region-base-1')).toHaveTextContent('55.4137, -1.706');
    });

    it('says "Not set" where there is no base', async () => {
      await renderLoaded();
      await openPanel(1);
      expect(screen.getByTestId('region-base-1')).toHaveTextContent('Not set');
    });

    it('lists the sub-regions with their location counts and a summary on the right', async () => {
      await renderLoaded();
      await openPanel(1);
      expect(screen.getByTestId('region-sub-regions-summary')).toHaveTextContent('2 · 2 locations');
      expect(screen.getByTestId('region-sub-region-11')).toHaveTextContent('Durham dales');
      expect(screen.getByTestId('region-sub-region-11')).toHaveTextContent('0 locations');
      expect(screen.getByTestId('region-sub-region-10')).toHaveTextContent('Northumberland coast');
      expect(screen.getByTestId('region-sub-region-10')).toHaveTextContent('2 locations');
      // The named area and the other region's sub-region are not listed.
      expect(screen.queryByText('A named area')).not.toBeInTheDocument();
      expect(screen.queryByText('Western fells')).not.toBeInTheDocument();
      // No picks (D2).
      expect(screen.getByTestId('region-sub-regions')).not.toHaveTextContent(/pick/i);
    });

    it('says so when the region has no sub-regions', async () => {
      fetchAreas.mockResolvedValue([]);
      await renderLoaded();
      await openPanel(1);
      expect(screen.getByTestId('region-no-sub-regions')).toBeInTheDocument();
    });

    it('closes with the × and clears the hash', async () => {
      await renderLoaded();
      await openPanel(1);
      fireEvent.click(screen.getByTestId('region-panel-close'));
      expect(screen.queryByTestId('region-reader-panel')).not.toBeInTheDocument();
      expect(window.location.hash).toBe('#manage/regions');
    });

    it('closes on Escape', async () => {
      await renderLoaded();
      await openPanel(1);
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByTestId('region-reader-panel')).not.toBeInTheDocument();
    });

    it('leaves the bare tab address when the hash names no region', async () => {
      window.location.hash = '#manage/regions/999';
      await renderLoaded();
      await waitFor(() => expect(window.location.hash).toBe('#manage/regions'));
      expect(screen.queryByTestId('region-reader-panel')).not.toBeInTheDocument();
    });

    it('makes the panel an aside, never a dialog', async () => {
      await renderLoaded();
      await openPanel(1);
      expect(screen.getByRole('complementary', { name: 'Region North East' })).toBeInTheDocument();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  describe('Region actions', () => {
    it('turns a region with no switched-on locations off at once, with no dialog', async () => {
      setRegionEnabled.mockResolvedValue({});
      // Lake District's one location is switched off here.
      fetchLocations.mockResolvedValue([{ id: 3, name: 'Keswick', enabled: false, region: { id: 2, name: 'Lake District' } }]);
      await renderLoaded();
      await openPanel(2);
      expect(screen.getByTestId('region-actions'))
        .toHaveTextContent("It leaves the region pickers, Ask and the Plan's starting points.");

      fireEvent.click(screen.getByTestId('toggle-region-enabled-2'));

      await waitFor(() => expect(setRegionEnabled).toHaveBeenCalledWith(2, false));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });

    it('disables Turn off while locations are switched on, says how many, and links to them', async () => {
      await renderLoaded();
      await openPanel(1);
      // Two locations in North East, ONE switched on: the count is of switched-on ones only.
      expect(screen.getByTestId('region-actions')).toHaveTextContent('Turn its 1 location off first.');
      expect(screen.getByTestId('toggle-region-enabled-1')).toBeDisabled();
      expect(screen.getByTestId('region-show-locations')).toHaveAttribute('href', '#manage/locations?region=1');

      fireEvent.click(screen.getByTestId('toggle-region-enabled-1'));
      expect(setRegionEnabled).not.toHaveBeenCalled();
    });

    it('counts only switched-on locations, plural', async () => {
      fetchLocations.mockResolvedValue([
        { id: 1, enabled: true, region: { id: 1 } },
        { id: 2, enabled: true, region: { id: 1 } },
        { id: 3, enabled: false, region: { id: 1 } },
      ]);
      await renderLoaded();
      await openPanel(1);
      expect(screen.getByTestId('region-actions')).toHaveTextContent('Turn its 2 locations off first.');
    });

    it('offers Turn on for an off region and calls the endpoint', async () => {
      setRegionEnabled.mockResolvedValue({});
      window.location.hash = '#manage/regions/3';
      await renderLoaded();
      expect(screen.getByTestId('region-actions'))
        .toHaveTextContent("It comes back to the region pickers, Ask and the Plan's starting points.");
      expect(screen.queryByTestId('region-show-locations')).not.toBeInTheDocument();
      expect(screen.getByTestId('region-panel-subtitle')).toHaveTextContent('Off · created 1 Mar 2026');

      fireEvent.click(screen.getByTestId('toggle-region-enabled-3'));
      await waitFor(() => expect(setRegionEnabled).toHaveBeenCalledWith(3, true));
    });

    it('moves a region from the Off group to the list once it is turned on', async () => {
      setRegionEnabled.mockResolvedValue({});
      window.location.hash = '#manage/regions/3';
      fetchRegions
        .mockResolvedValueOnce(MOCK_REGIONS)
        .mockResolvedValue(MOCK_REGIONS.map((r) => (r.id === 3 ? { ...r, enabled: true } : r)));
      await renderLoaded();

      fireEvent.click(screen.getByTestId('toggle-region-enabled-3'));

      await waitFor(() => expect(screen.getByTestId('region-total')).toHaveTextContent('3 on · 0 off'));
      expect(screen.queryByTestId('regions-group-off')).not.toBeInTheDocument();
      expect(listedNames()).toContain('Scotland');
    });

    it('reverts a refused toggle and shows the server sentence under the action', async () => {
      // A race: the locations list was stale, so the button was live, and the server refused.
      setRegionEnabled.mockRejectedValue({
        response: { status: 409, data: { error: 'Turn off its 2 locations first.' } },
      });
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
      fetchLocations.mockResolvedValue([]);
      await renderLoaded();
      await openPanel(1);

      fireEvent.click(screen.getByTestId('toggle-region-enabled-1'));

      expect(await screen.findByTestId('region-toggle-error')).toHaveTextContent('Turn off its 2 locations first.');
      // Reverted: still on, still offering Turn off, still in the list, not in a re-opened Off group.
      await waitFor(() => expect(screen.getByTestId('region-total')).toHaveTextContent('2 on · 1 off'));
      expect(screen.getByTestId('region-panel-subtitle')).toHaveTextContent('On ·');
      expect(screen.getByTestId('toggle-region-enabled-1')).toHaveTextContent('Turn off');
      expect(screen.getByTestId('toggle-region-enabled-1')).toBeEnabled();
      consoleError.mockRestore();
    });

    it('does not show one region\'s refusal under another', async () => {
      setRegionEnabled.mockRejectedValue({ response: { status: 409, data: { error: 'Nope.' } } });
      const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
      fetchLocations.mockResolvedValue([]);
      await renderLoaded();
      await openPanel(1);
      fireEvent.click(screen.getByTestId('toggle-region-enabled-1'));
      await screen.findByTestId('region-toggle-error');

      fireEvent.click(screen.getByTestId('regions-row-2'));

      await waitFor(() => expect(screen.getByTestId('region-panel-title')).toHaveTextContent('Lake District'));
      expect(screen.queryByTestId('region-toggle-error')).not.toBeInTheDocument();
      consoleError.mockRestore();
    });
  });

  describe('Add region', () => {
    it('opens a one-field form in the panel with Cancel and Save', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('add-region-btn'));
      expect(await screen.findByTestId('region-form-add')).toBeInTheDocument();
      expect(screen.getByTestId('region-panel-title')).toHaveTextContent('Add region');
      expect(screen.getByTestId('region-name-input')).toBeInTheDocument();
      expect(screen.queryByTestId('region-base-fields')).not.toBeInTheDocument();
      expect(within(screen.getByTestId('region-panel-footer')).getByText('Cancel')).toBeInTheDocument();
    });

    it('calls addRegion and selects the new region', async () => {
      addRegion.mockResolvedValue({ id: 9, name: 'Yorkshire' });
      fetchRegions
        .mockResolvedValueOnce(MOCK_REGIONS)
        .mockResolvedValue([...MOCK_REGIONS, { id: 9, name: 'Yorkshire', enabled: true, createdAt: '2026-10-31T10:00:00' }]);
      await renderLoaded();
      fireEvent.click(screen.getByTestId('add-region-btn'));
      fireEvent.change(await screen.findByTestId('region-name-input'), { target: { value: '  Yorkshire ' } });
      fireEvent.click(screen.getByTestId('save-region-btn'));

      await waitFor(() => expect(addRegion).toHaveBeenCalledWith({ name: 'Yorkshire' }));
      expect(await screen.findByTestId('region-reader-panel')).toBeInTheDocument();
      expect(screen.getByTestId('region-panel-title')).toHaveTextContent('Yorkshire');
      expect(window.location.hash).toBe('#manage/regions/9');
      expect(screen.getByTestId('regions-row-9')).toHaveAttribute('aria-current', 'true');
    });

    it('disables Save while the name is blank', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('add-region-btn'));
      fireEvent.change(await screen.findByTestId('region-name-input'), { target: { value: '   ' } });
      expect(screen.getByTestId('save-region-btn')).toBeDisabled();
    });

    it('keeps the form open and shows the server\'s sentence when the add fails', async () => {
      addRegion.mockRejectedValue({ response: { data: { error: 'A region with that name already exists' } } });
      await renderLoaded();
      fireEvent.click(screen.getByTestId('add-region-btn'));
      fireEvent.change(await screen.findByTestId('region-name-input'), { target: { value: 'North East' } });
      fireEvent.click(screen.getByTestId('save-region-btn'));

      expect(await screen.findByText('A region with that name already exists')).toBeInTheDocument();
      expect(screen.getByTestId('region-form-add')).toBeInTheDocument();
    });

    it('Cancel closes the panel and leaves the list', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('add-region-btn'));
      fireEvent.click(await screen.findByTestId('region-form-cancel'));
      expect(screen.queryByTestId('region-form-add')).not.toBeInTheDocument();
      expect(screen.getByTestId('regions')).toBeInTheDocument();
    });

    it('Escape closes Add region', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('add-region-btn'));
      await screen.findByTestId('region-form-add');
      fireEvent.keyDown(screen.getByTestId('region-name-input'), { key: 'Escape' });
      expect(screen.queryByTestId('region-form-add')).not.toBeInTheDocument();
    });
  });

  describe('Rename', () => {
    async function openRename(id = 1) {
      await renderLoaded();
      await openPanel(id);
      fireEvent.click(screen.getByTestId(`rename-region-${id}`));
      return screen.findByTestId('region-form-rename');
    }

    it('opens the single Name field with the current name', async () => {
      await openRename();
      expect(screen.getByTestId('region-name-input')).toHaveValue('North East');
      expect(screen.queryByTestId('region-base-fields')).not.toBeInTheDocument();
    });

    it('calls updateRegion with the new name and returns to the panel showing it', async () => {
      updateRegion.mockResolvedValue({});
      fetchRegions
        .mockResolvedValueOnce(MOCK_REGIONS)
        .mockResolvedValue(MOCK_REGIONS.map((r) => (r.id === 1 ? { ...r, name: 'North East England' } : r)));
      await openRename();
      fireEvent.change(screen.getByTestId('region-name-input'), { target: { value: 'North East England' } });
      fireEvent.click(screen.getByTestId('save-region-btn'));

      await waitFor(() => expect(updateRegion).toHaveBeenCalledWith(1, { name: 'North East England' }));
      expect(await screen.findByTestId('region-reader-panel')).toBeInTheDocument();
      expect(screen.getByTestId('region-panel-title')).toHaveTextContent('North East England');
      // ⚠️ A rename never touches the base: moving it discards the drive-time matrix.
      expect(setRegionBase).not.toHaveBeenCalled();
    });

    it('Cancel returns to the panel without calling the API', async () => {
      await openRename();
      fireEvent.click(screen.getByTestId('region-form-cancel'));
      expect(screen.getByTestId('region-reader-panel')).toBeInTheDocument();
      expect(updateRegion).not.toHaveBeenCalled();
    });

    it('Escape steps back to the panel first, then closes it', async () => {
      await openRename();
      fireEvent.keyDown(screen.getByTestId('region-name-input'), { key: 'Escape' });
      expect(screen.getByTestId('region-reader-panel')).toBeInTheDocument();
      expect(screen.queryByTestId('region-form-rename')).not.toBeInTheDocument();
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByTestId('region-reader-panel')).not.toBeInTheDocument();
    });

    it('re-reads the list when the save fails, and keeps the form open with the error', async () => {
      updateRegion.mockRejectedValue({ response: { data: { error: 'Name taken' } } });
      await openRename();
      fireEvent.change(screen.getByTestId('region-name-input'), { target: { value: 'Lake District' } });
      fireEvent.click(screen.getByTestId('save-region-btn'));

      expect(await screen.findByText('Name taken')).toBeInTheDocument();
      // Twice: the mount, and the re-read after the failure.
      await waitFor(() => expect(fetchRegions).toHaveBeenCalledTimes(2));
      expect(screen.getByTestId('region-name-input')).toHaveValue('Lake District');
    });
  });

  describe('Edit base — the base town', () => {
    // The base is the origin the Plan tab can plan from (heat-field plan §4.8). A half-saved base cannot be routed
    // from, and a base save on an unchanged form throws away that region's whole shared drive-time matrix.
    const BASED = [
      {
        id: 1, name: 'Lake District', enabled: true, createdAt: '2026-01-15T10:00:00',
        baseName: 'Keswick', baseLat: 54.6013, baseLon: -3.1347,
      },
      {
        id: 2, name: 'North East', enabled: true, createdAt: '2026-02-01T10:00:00',
        baseName: null, baseLat: null, baseLon: null,
      },
    ];

    beforeEach(() => {
      fetchRegions.mockResolvedValue(BASED);
      fetchLocations.mockResolvedValue([]);
      fetchAreas.mockResolvedValue([]);
      updateRegion.mockResolvedValue({});
      setRegionBase.mockResolvedValue({});
    });

    async function openBase(id) {
      await renderLoaded();
      await openPanel(id);
      fireEvent.click(screen.getByTestId(`edit-region-base-${id}`));
      return screen.findByTestId('region-form-base');
    }

    it('shows each region\'s base in its panel', async () => {
      await renderLoaded();
      await openPanel(1);
      expect(screen.getByTestId('region-base-1')).toHaveTextContent('Keswick');
    });

    it('loads the stored base into the form', async () => {
      await openBase(1);
      expect(screen.getByTestId('region-base-name-input')).toHaveValue('Keswick');
      expect(screen.getByTestId('region-base-lat-input')).toHaveValue('54.6013');
      expect(screen.getByTestId('region-base-lon-input')).toHaveValue('-3.1347');
      expect(screen.queryByTestId('region-name-input')).not.toBeInTheDocument();
    });

    it('opens empty for a region with no base', async () => {
      await openBase(2);
      expect(screen.getByTestId('region-base-name-input')).toHaveValue('');
      expect(screen.getByTestId('region-base-lat-input')).toHaveValue('');
    });

    it('keeps the copy that moving the base clears the drive times', async () => {
      await openBase(1);
      expect(screen.getByTestId('region-base-fields')).toHaveTextContent(/discards this region.s stored drive times/);
    });

    it('saves a new base through its own endpoint, with the coordinates as numbers', async () => {
      await openBase(2);
      fireEvent.change(screen.getByTestId('region-base-name-input'), { target: { value: 'Alnwick' } });
      fireEvent.change(screen.getByTestId('region-base-lat-input'), { target: { value: '55.4137' } });
      fireEvent.change(screen.getByTestId('region-base-lon-input'), { target: { value: '-1.7060' } });
      fireEvent.click(screen.getByTestId('save-region-btn'));

      await waitFor(() => expect(setRegionBase).toHaveBeenCalledWith(2, {
        baseName: 'Alnwick', baseLat: 55.4137, baseLon: -1.706,
      }));
      expect(updateRegion).not.toHaveBeenCalled();
      expect(await screen.findByTestId('region-reader-panel')).toBeInTheDocument();
    });

    it('⚠️ does NOT call the base endpoint when the form is saved unchanged', async () => {
      await openBase(1);
      fireEvent.click(screen.getByTestId('save-region-btn'));
      expect(await screen.findByTestId('region-reader-panel')).toBeInTheDocument();
      expect(setRegionBase).not.toHaveBeenCalled();
    });

    it('clears the base when all three fields are emptied', async () => {
      await openBase(1);
      fireEvent.change(screen.getByTestId('region-base-name-input'), { target: { value: '' } });
      fireEvent.change(screen.getByTestId('region-base-lat-input'), { target: { value: '' } });
      fireEvent.change(screen.getByTestId('region-base-lon-input'), { target: { value: '' } });
      fireEvent.click(screen.getByTestId('save-region-btn'));

      await waitFor(() => expect(setRegionBase).toHaveBeenCalledWith(1, {
        baseName: null, baseLat: null, baseLon: null,
      }));
    });

    it('does not send a clear for a region that had no base to start with', async () => {
      await openBase(2);
      fireEvent.click(screen.getByTestId('save-region-btn'));
      expect(await screen.findByTestId('region-reader-panel')).toBeInTheDocument();
      expect(setRegionBase).not.toHaveBeenCalled();
    });

    it('⚠️ refuses a partial base beside the fields, rather than as a 400 after a round trip', async () => {
      await openBase(2);
      fireEvent.change(screen.getByTestId('region-base-name-input'), { target: { value: 'Alnwick' } });
      fireEvent.click(screen.getByTestId('save-region-btn'));

      expect(await screen.findByText(
        'A base town needs a name, a latitude and a longitude — or leave all three blank.',
      )).toBeInTheDocument();
      expect(setRegionBase).not.toHaveBeenCalled();
    });

    it('refuses a coordinate that is not a number', async () => {
      await openBase(2);
      fireEvent.change(screen.getByTestId('region-base-name-input'), { target: { value: 'Alnwick' } });
      fireEvent.change(screen.getByTestId('region-base-lat-input'), { target: { value: 'north' } });
      fireEvent.change(screen.getByTestId('region-base-lon-input'), { target: { value: '-1.7' } });
      fireEvent.click(screen.getByTestId('save-region-btn'));

      expect(await screen.findByText(/needs a name, a latitude and a longitude/i)).toBeInTheDocument();
      expect(setRegionBase).not.toHaveBeenCalled();
    });

    it('⚠️ re-reads the list when the save fails, and keeps the form on the field that failed', async () => {
      setRegionBase.mockRejectedValue({ response: { data: { error: 'Base coordinates are out of range' } } });
      await openBase(2);
      fireEvent.change(screen.getByTestId('region-base-name-input'), { target: { value: 'Alnwick' } });
      fireEvent.change(screen.getByTestId('region-base-lat-input'), { target: { value: '545.4' } });
      fireEvent.change(screen.getByTestId('region-base-lon-input'), { target: { value: '-1.7' } });
      fireEvent.click(screen.getByTestId('save-region-btn'));

      expect(await screen.findByText(/out of range/i)).toBeInTheDocument();
      await waitFor(() => expect(fetchRegions).toHaveBeenCalledTimes(2));
      expect(screen.getByTestId('region-base-lat-input')).toHaveValue('545.4');
    });

    it('keeps a base at zero longitude — Greenwich is a place, and `||` would blank it', async () => {
      fetchRegions.mockResolvedValue([{
        id: 1, name: 'Home Counties', enabled: true, createdAt: '2026-01-15T10:00:00',
        baseName: 'Greenwich', baseLat: 51.4826, baseLon: 0,
      }]);
      await openBase(1);
      expect(screen.getByTestId('region-base-lon-input')).toHaveValue('0');
    });
  });
});
