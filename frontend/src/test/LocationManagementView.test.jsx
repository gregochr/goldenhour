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
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn(() => false) }));

import { fetchLocations, resetLocationFailures } from '../api/forecastApi';
import { fetchRegions } from '../api/regionApi';
import { fetchAreas, fetchUncoveredLocations, fetchLocationAliases } from '../api/areaApi';
import { fetchTideStats } from '../api/tideApi';
import { useIsMobile } from '../hooks/useIsMobile.js';

const REASON = 'Auto-disabled after 3 consecutive failed scheduled runs (last 2026-10-02: weather data could not be fetched).';
const NORTH = { id: 1, name: 'Northumberland', enabled: true };
const LAKES = { id: 2, name: 'Lake District', enabled: true };
const BASE = { enabled: true, solarEventType: ['SUNRISE', 'SUNSET'], tideType: [], consecutiveFailures: 0 };

const ALNMOUTH = {
  ...BASE, id: 1, name: 'Alnmouth', lat: 55.39, lon: -1.613, locationType: ['SEASCAPE'], tideType: ['HIGH', 'MID', 'LOW'],
  region: NORTH, subRegionName: 'Alnwick Coast', bortleClass: 3,
};
const AIRA = {
  ...BASE, id: 2, name: 'Aira Force', lat: 54.57, lon: -2.92, locationType: ['WATERFALL', 'WOODLAND'], region: LAKES,
  subRegionName: 'Ullswater', mistProne: true, bortleClass: null,
};
const AMBLE = {
  ...BASE, id: 3, name: 'Amble', lat: 55.33, lon: -1.58, locationType: ['SEASCAPE'], tideType: ['HIGH'], region: NORTH,
  subRegionName: 'Alnwick Coast', consecutiveFailures: 2, lastFailureAt: '2026-10-02T03:00:00', bortleClass: 4,
};
const BLACKHALL = {
  ...BASE, id: 4, name: 'Blackhall Rocks', lat: 54.7, lon: -1.3, locationType: ['SEASCAPE'], tideType: ['LOW'], enabled: false,
  region: NORTH, consecutiveFailures: 3, lastFailureAt: '2026-10-02T03:00:00', disabledReason: REASON,
};
const BLEA = {
  ...BASE, id: 5, name: 'Blea Tarn', lat: 54.4, lon: -3.0, locationType: ['LANDSCAPE'], enabled: false, region: LAKES,
  subRegionName: 'The Langdales',
};
const DURHAM = {
  ...BASE, id: 6, name: 'Durham Cathedral', lat: 54.77, lon: -1.58, locationType: ['LANDSCAPE'], region: NORTH,
  subRegionName: null,
};
const ALL = [ALNMOUTH, AIRA, AMBLE, BLACKHALL, BLEA, DURHAM];

function makeMany(count) {
  return Array.from({ length: count }, (_, i) => ({
    ...BASE, id: i + 1, name: `Location ${i + 1}`, lat: 54 + i * 0.1, lon: -1, locationType: ['LANDSCAPE'],
  }));
}

/** The location names in the grid, in row order. */
function listedNames() {
  return screen.getAllByTestId(/^location-name-/).map((name) => name.textContent);
}

async function renderLoaded(props = {}) {
  render(<LocationManagementView onLocationsChanged={() => {}} {...props} />);
  await screen.findByTestId('locations');
}

describe('LocationManagementView — the list', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    useIsMobile.mockReturnValue(false);
    fetchLocations.mockResolvedValue(ALL);
    fetchRegions.mockResolvedValue([NORTH, LAKES]);
    fetchAreas.mockResolvedValue([]);
    fetchUncoveredLocations.mockResolvedValue([]);
    fetchLocationAliases.mockResolvedValue({});
    fetchTideStats.mockResolvedValue(null);
  });

  it('lists each location with its name, sub-region, type words and Bortle', async () => {
    await renderLoaded();

    expect(screen.getByRole('heading', { name: /^Locations/ })).toBeInTheDocument();
    ['Location', 'Type', 'Light', 'Tides', 'Bortle', 'Status'].forEach((header) => {
      expect(screen.getByTestId('locations-header')).toHaveTextContent(header);
    });
    expect(screen.getByTestId('locations-total')).toHaveTextContent('6');
    expect(listedNames()).toEqual(['Aira Force', 'Alnmouth', 'Amble', 'Blackhall Rocks', 'Blea Tarn', 'Durham Cathedral']);
    const alnmouth = screen.getByTestId('locations-row-1');
    expect(alnmouth).toHaveTextContent('Alnwick Coast');
    expect(screen.getByTestId('locations-cell-type-1')).toHaveTextContent('Seascape');
    expect(screen.getByTestId('locations-cell-type-2')).toHaveTextContent('Woodland, Waterfall');
    expect(screen.getByTestId('bortle-1')).toHaveTextContent('3');
    expect(screen.getByTestId('bortle-2')).toHaveTextContent('—');
  });

  it('tags a mist-prone place with Mist, and no other', async () => {
    await renderLoaded();

    expect(screen.getByTestId('mist-prone-badge-2')).toHaveTextContent('Mist');
    expect(screen.queryByTestId('mist-prone-badge-1')).not.toBeInTheDocument();
  });

  it('draws light and tide tokens with the unheld ones in the faintest ink, and a dash for a non-seascape', async () => {
    fetchLocations.mockResolvedValue([{ ...AMBLE, solarEventType: ['SUNSET'] }, DURHAM]);
    await renderLoaded();

    expect(screen.getByTestId('location-tides-3-HIGH')).toHaveClass('text-plex-text');
    expect(screen.getByTestId('location-tides-3-MID')).toHaveClass('text-plex-text-faint');
    expect(screen.getByTestId('location-tides-3-LOW')).toHaveClass('text-plex-text-faint');
    expect(screen.getByTestId('location-light-3-SUNSET')).toHaveClass('text-plex-text');
    expect(screen.getByTestId('location-light-3-SUNRISE')).toHaveClass('text-plex-text-faint');
    expect(screen.getByTestId('location-light-3-ALLDAY')).toHaveClass('text-plex-text-faint');
    expect(screen.getByTestId('location-tides-none-6')).toHaveTextContent('—');
  });

  it('reads each status: Active, failed runs, Disabled, Disabled after failures', async () => {
    await renderLoaded();

    expect(screen.getByTestId('location-status-1')).toHaveTextContent('Active');
    expect(screen.getByTestId('location-status-3')).toHaveTextContent('2 failed runs');
    expect(screen.getByTestId('location-status-4')).toHaveTextContent('Disabled after failures');
    expect(screen.getByTestId('location-status-5')).toHaveTextContent('Disabled');
    expect(screen.getByTestId('location-status-5')).not.toHaveTextContent('after failures');
  });

  it('holds no button, link or field in any row, and the status is not a toggle', async () => {
    await renderLoaded();

    screen.getAllByTestId(/^locations-row-/).forEach((row) => {
      ['button', 'link', 'textbox', 'combobox', 'checkbox', 'searchbox'].forEach((role) => {
        expect(within(row).queryAllByRole(role)).toHaveLength(0);
      });
    });
    expect(screen.queryByTestId('toggle-enabled-1')).not.toBeInTheDocument();
    expect(screen.queryByTestId('edit-location-1')).not.toBeInTheDocument();
  });

  it('says so when there are no locations at all', async () => {
    fetchLocations.mockResolvedValue([]);
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    expect(await screen.findByTestId('locations-empty')).toHaveTextContent('No locations configured. Add one to get started.');
    expect(screen.queryByTestId('locations-search')).not.toBeInTheDocument();
  });

  it('says it could not load the locations, rather than reading as empty', async () => {
    fetchLocations.mockRejectedValue(new Error('boom'));
    render(<LocationManagementView onLocationsChanged={() => {}} />);

    expect(await screen.findByTestId('locations-load-error')).toHaveTextContent("Couldn't load locations.");
    expect(screen.queryByTestId('locations-empty')).not.toBeInTheDocument();
  });

  it('paginates when there are more locations than a page, keeping the Areas section below', async () => {
    fetchLocations.mockResolvedValue(makeMany(15));
    await renderLoaded();

    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('1–10 of 15');
    expect(screen.getByText('Location 1')).toBeInTheDocument();
    expect(screen.queryByText('Location 5')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId('pagination-next'));
    expect(await screen.findByText('Location 5')).toBeInTheDocument();
    expect(screen.getByTestId('pagination-summary')).toHaveTextContent('11–15 of 15');
    expect(await screen.findByTestId('areas-section')).toBeInTheDocument();
  });

  it('hides pagination when everything fits on one page', async () => {
    await renderLoaded();

    expect(screen.queryByTestId('pagination')).not.toBeInTheDocument();
  });

  describe('opening a row', () => {
    it('opens the panel on a click, and writes the selection to the hash', async () => {
      await renderLoaded();

      fireEvent.click(screen.getByTestId('locations-row-1'));

      expect(await screen.findByTestId('location-view-panel')).toBeInTheDocument();
      expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Alnmouth');
      expect(screen.getByTestId('location-panel-subtitle')).toHaveTextContent('Seascape · Alnwick Coast · Active');
      expect(window.location.hash).toBe('#manage/locations/1');
      expect(screen.getByTestId('locations-row-1')).toHaveAttribute('aria-current', 'true');
    });

    it('opens on Enter and on Space', async () => {
      await renderLoaded();

      fireEvent.keyDown(screen.getByTestId('locations-row-2'), { key: 'Enter' });
      expect(await screen.findByTestId('location-panel-title')).toHaveTextContent('Aira Force');

      fireEvent.keyDown(screen.getByTestId('locations-row-6'), { key: ' ' });
      await waitFor(() => expect(screen.getByTestId('location-panel-title')).toHaveTextContent('Durham Cathedral'));
    });

    it('opens the location a pasted hash names, and drops a hash that names none', async () => {
      window.location.hash = '#manage/locations/4';
      await renderLoaded();
      expect(await screen.findByTestId('location-panel-title')).toHaveTextContent('Blackhall Rocks');

      window.location.hash = '#manage/locations/999';
      await waitFor(() => expect(screen.queryByTestId('location-view-panel')).not.toBeInTheDocument());
      await waitFor(() => expect(window.location.hash).toBe('#manage/locations'));
    });

    it('closes on the × and on Escape, and clears the hash', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('locations-row-1'));
      await screen.findByTestId('location-view-panel');

      fireEvent.click(screen.getByTestId('location-panel-close'));
      expect(screen.queryByTestId('location-view-panel')).not.toBeInTheDocument();
      expect(window.location.hash).toBe('#manage/locations');

      fireEvent.click(screen.getByTestId('locations-row-1'));
      await screen.findByTestId('location-view-panel');
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByTestId('location-view-panel')).not.toBeInTheDocument();
    });

    it('never renders the panel as a dialog', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('locations-row-1'));
      await screen.findByTestId('location-view-panel');

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.getByRole('complementary', { name: 'Location Alnmouth' })).toBeInTheDocument();
    });

    it('keeps the panel open while paging, because the selection is still in the set', async () => {
      fetchLocations.mockResolvedValue(makeMany(15));
      await renderLoaded();
      fireEvent.click(screen.getByTestId('locations-row-1'));
      await screen.findByTestId('location-view-panel');

      fireEvent.click(screen.getByTestId('pagination-next'));

      await screen.findByText('Location 5');
      expect(screen.getByTestId('location-view-panel')).toBeInTheDocument();
    });
  });

  describe('search and chips', () => {
    it('searches by name or by sub-region', async () => {
      await renderLoaded();

      fireEvent.change(screen.getByTestId('locations-search'), { target: { value: 'alnwick' } });
      expect(listedNames()).toEqual(['Alnmouth', 'Amble']);

      fireEvent.change(screen.getByTestId('locations-search'), { target: { value: 'durham' } });
      expect(listedNames()).toEqual(['Durham Cathedral']);
    });

    it('counts a location under each type it holds, and filters by it', async () => {
      await renderLoaded();

      expect(screen.getByTestId('locations-chip-all-count')).toHaveTextContent('6');
      expect(screen.getByTestId('locations-chip-SEASCAPE-count')).toHaveTextContent('3');
      expect(screen.getByTestId('locations-chip-WATERFALL-count')).toHaveTextContent('1');
      expect(screen.getByTestId('locations-chip-WOODLAND-count')).toHaveTextContent('1');
      expect(screen.getByTestId('locations-chip-LANDSCAPE-count')).toHaveTextContent('2');
      expect(screen.getByTestId('locations-chip-WILDLIFE-count')).toHaveTextContent('0');

      fireEvent.click(screen.getByTestId('locations-chip-WOODLAND'));
      expect(listedNames()).toEqual(['Aira Force']);
      expect(screen.getByTestId('locations-chip-WOODLAND')).toHaveAttribute('aria-pressed', 'true');

      fireEvent.click(screen.getByTestId('locations-chip-all'));
      expect(listedNames()).toHaveLength(6);
    });

    it('filters to Needs attention (failing or turned off by the failures) and to Disabled', async () => {
      await renderLoaded();

      expect(screen.getByTestId('locations-chip-attention-count')).toHaveTextContent('2');
      expect(screen.getByTestId('locations-chip-disabled-count')).toHaveTextContent('2');

      fireEvent.click(screen.getByTestId('locations-chip-attention'));
      expect(listedNames()).toEqual(['Amble', 'Blackhall Rocks']);

      fireEvent.click(screen.getByTestId('locations-chip-disabled'));
      expect(listedNames()).toEqual(['Blackhall Rocks', 'Blea Tarn']);

      // Pressing the chip that is on turns it off.
      fireEvent.click(screen.getByTestId('locations-chip-disabled'));
      expect(listedNames()).toHaveLength(6);
    });

    it('lets the chip counts follow the search, the region and the other chip group', async () => {
      await renderLoaded();

      fireEvent.change(screen.getByTestId('locations-search'), { target: { value: 'a' } });
      fireEvent.click(screen.getByTestId('locations-chip-SEASCAPE'));
      // Seascapes with an "a" in the name: Alnmouth, Amble, Blackhall Rocks; of which Amble and Blackhall need attention.
      expect(screen.getByTestId('locations-chip-attention-count')).toHaveTextContent('2');
      expect(screen.getByTestId('locations-chip-disabled-count')).toHaveTextContent('1');
    });

    it('filters by region', async () => {
      await renderLoaded();

      fireEvent.change(screen.getByTestId('locations-region'), { target: { value: '2' } });

      expect(listedNames()).toEqual(['Aira Force', 'Blea Tarn']);
    });

    it('says No locations match, and Clear filters brings them all back', async () => {
      await renderLoaded();
      fireEvent.change(screen.getByTestId('locations-search'), { target: { value: 'zzz' } });
      fireEvent.click(screen.getByTestId('locations-chip-attention'));

      expect(screen.getByTestId('locations-no-match')).toHaveTextContent('No locations match.');
      fireEvent.click(screen.getByTestId('locations-clear-filters'));

      expect(listedNames()).toHaveLength(6);
      expect(screen.getByTestId('locations-search')).toHaveValue('');
      expect(screen.getByTestId('locations-chip-attention')).toHaveAttribute('aria-pressed', 'false');
    });

    it('closes an open panel when a filter moves its location out of the list, and not otherwise', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('locations-row-6'));
      await screen.findByTestId('location-view-panel');

      fireEvent.click(screen.getByTestId('locations-chip-LANDSCAPE'));
      expect(screen.getByTestId('location-view-panel')).toBeInTheDocument();

      fireEvent.click(screen.getByTestId('locations-chip-SEASCAPE'));
      await waitFor(() => expect(screen.queryByTestId('location-view-panel')).not.toBeInTheDocument());
    });

    it('sorts Status by severity: Disabled after failures, failed runs by count (10 before 2), Disabled, Active', async () => {
      fetchLocations.mockResolvedValue([
        { ...AMBLE, id: 11, name: 'Two', consecutiveFailures: 2 },
        { ...AMBLE, id: 12, name: 'Ten', consecutiveFailures: 10 },
        BLACKHALL, BLEA, DURHAM,
      ]);
      await renderLoaded();

      fireEvent.click(screen.getByTestId('locations-sort-status'));
      expect(listedNames()).toEqual(['Durham Cathedral', 'Blea Tarn', 'Two', 'Ten', 'Blackhall Rocks']);

      fireEvent.click(screen.getByTestId('locations-sort-status'));
      expect(listedNames()).toEqual(['Blackhall Rocks', 'Ten', 'Two', 'Blea Tarn', 'Durham Cathedral']);
    });

    it('sorts by a header, and by the sort chip', async () => {
      await renderLoaded();

      fireEvent.click(screen.getByTestId('locations-sort-name'));
      expect(listedNames()[0]).toBe('Durham Cathedral');

      fireEvent.click(screen.getByTestId('locations-sort-bortle'));
      expect(listedNames()[0]).toBe('Alnmouth');
    });
  });

  describe('a Regions link (#manage/locations?region={id})', () => {
    it('opens filtered to that region, and leaves the address as it was', async () => {
      window.location.hash = '#manage/locations?region=2';
      await renderLoaded();

      expect(listedNames()).toEqual(['Aira Force', 'Blea Tarn']);
      expect(screen.getByTestId('locations-region')).toHaveValue('2');
      expect(window.location.hash).toBe('#manage/locations?region=2');
    });

    it('follows the link while the tab is already open', async () => {
      await renderLoaded();
      expect(listedNames()).toHaveLength(6);

      window.location.hash = '#manage/locations?region=1';

      await waitFor(() => expect(listedNames()).toEqual(['Alnmouth', 'Amble', 'Blackhall Rocks', 'Durham Cathedral']));
    });

    it('leaves the address alone, so Back restores the filter after a row was opened', async () => {
      window.location.hash = '#manage/locations?region=2';
      await renderLoaded();
      expect(listedNames()).toEqual(['Aira Force', 'Blea Tarn']);
      expect(window.location.hash).toBe('#manage/locations?region=2');

      fireEvent.change(screen.getByTestId('locations-region'), { target: { value: 'ALL' } });
      fireEvent.click(screen.getByTestId('locations-row-1'));
      await waitFor(() => expect(window.location.hash).toBe('#manage/locations/1'));
      expect(listedNames()).toHaveLength(6);

      window.history.back();

      await waitFor(() => expect(listedNames()).toEqual(['Aira Force', 'Blea Tarn']));
    });

    it('applies the filter and selects the location when both ride the hash', async () => {
      window.location.hash = '#manage/locations/2?region=2';
      await renderLoaded();

      expect(listedNames()).toEqual(['Aira Force', 'Blea Tarn']);
      expect(await screen.findByTestId('location-panel-title')).toHaveTextContent('Aira Force');
      expect(window.location.hash).toBe('#manage/locations/2?region=2');
    });

    it('ignores a region that does not exist', async () => {
      window.location.hash = '#manage/locations?region=77';
      await renderLoaded();

      expect(listedNames()).toHaveLength(6);
      expect(screen.getByTestId('locations-region')).toHaveValue('ALL');
    });
  });

  describe('the attention alert', () => {
    it('counts the places that need attention and names one failing and one turned-off place', async () => {
      await renderLoaded();

      const alert = screen.getByTestId('locations-attention-alert');
      expect(within(alert).getByTestId('locations-attention-count')).toHaveTextContent('2 locations need attention.');
      expect(alert).toHaveTextContent('Amble has failed 2 runs in a row.');
      expect(alert).toHaveTextContent('Blackhall Rocks was turned off after 3 failed runs.');
    });

    it('names at most one of each, and says needs for a single place', async () => {
      fetchLocations.mockResolvedValue([
        { ...AMBLE, id: 11, name: 'Zed', consecutiveFailures: 1 }, AMBLE, ALNMOUTH,
      ]);
      await renderLoaded();

      const alert = screen.getByTestId('locations-attention-alert');
      expect(alert).toHaveTextContent('2 locations need attention.');
      expect(alert).toHaveTextContent('Amble has failed 2 runs in a row.');
      expect(alert).not.toHaveTextContent('Zed');
      expect(screen.queryByTestId('locations-attention-turned-off')).not.toBeInTheDocument();
    });

    it('uses the singular for one place with one failed run', async () => {
      fetchLocations.mockResolvedValue([{ ...AMBLE, consecutiveFailures: 1 }, DURHAM]);
      await renderLoaded();

      expect(screen.getByTestId('locations-attention-alert')).toHaveTextContent('1 location needs attention. Amble has failed 1 run in a row.');
    });

    it('lists a place turned off by the failures even though its counter was reset, but not one switched off by hand', async () => {
      fetchLocations.mockResolvedValue([{ ...BLACKHALL, consecutiveFailures: 0 }, BLEA, DURHAM]);
      await renderLoaded();

      const alert = screen.getByTestId('locations-attention-alert');
      expect(alert).toHaveTextContent('1 location needs attention.');
      expect(alert).toHaveTextContent('Blackhall Rocks was turned off after failed runs.');
      expect(screen.getByTestId('locations-chip-attention-count')).toHaveTextContent('1');
    });

    it('is not shown when nothing needs attention', async () => {
      fetchLocations.mockResolvedValue([ALNMOUTH, BLEA, DURHAM]);
      await renderLoaded();

      expect(screen.queryByTestId('locations-attention-alert')).not.toBeInTheDocument();
    });

    it('gives Show them a 44px touch target on a phone', async () => {
      await renderLoaded();

      expect(screen.getByTestId('locations-show-attention')).toHaveClass('max-[639px]:min-h-11');
    });

    it('does not count a place that is on but carries a stale reason', async () => {
      fetchLocations.mockResolvedValue([{ ...DURHAM, disabledReason: 'old reason' }, ALNMOUTH]);
      await renderLoaded();

      expect(screen.queryByTestId('locations-attention-alert')).not.toBeInTheDocument();
      expect(screen.getByTestId('locations-chip-attention-count')).toHaveTextContent('0');
    });

    it('Show them applies the Needs attention chip over everything else', async () => {
      await renderLoaded();
      fireEvent.change(screen.getByTestId('locations-search'), { target: { value: 'durham' } });

      fireEvent.click(screen.getByTestId('locations-show-attention'));

      expect(screen.getByTestId('locations-chip-attention')).toHaveAttribute('aria-pressed', 'true');
      expect(screen.getByTestId('locations-search')).toHaveValue('');
      expect(listedNames()).toEqual(['Amble', 'Blackhall Rocks']);
    });
  });

  describe('telling ManageView how many need attention', () => {
    it('reports the count once the locations have loaded, and again after a change', async () => {
      const onCount = vi.fn();
      await renderLoaded({ onAttentionCountChange: onCount });

      await waitFor(() => expect(onCount).toHaveBeenLastCalledWith(2));
      expect(onCount).not.toHaveBeenCalledWith(0);

      resetLocationFailures.mockResolvedValue(undefined);
      fetchLocations.mockResolvedValue(ALL.map((loc) => (loc.id === 3 ? { ...loc, consecutiveFailures: 0 } : loc)));
      fireEvent.click(screen.getByTestId('locations-row-3'));
      fireEvent.click(await screen.findByTestId('location-reset-failures'));

      await waitFor(() => expect(onCount).toHaveBeenLastCalledWith(1));
    });
  });

  describe('on a phone', () => {
    beforeEach(() => useIsMobile.mockReturnValue(true));

    it('shows the panel as a page with a back bar named for the tab, and steps back from the edit form to the location', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('locations-row-6'));
      await screen.findByTestId('location-view-panel');
      expect(screen.getByTestId('location-back')).toHaveAccessibleName('Back to Locations');

      fireEvent.click(screen.getByTestId('edit-location-6'));
      await screen.findByTestId('location-edit-panel');
      expect(screen.getByTestId('location-back')).toHaveAccessibleName('Back to Durham Cathedral');
      fireEvent.click(screen.getByTestId('location-back'));
      expect(await screen.findByTestId('location-view-panel')).toBeInTheDocument();

      fireEvent.click(screen.getByTestId('location-back'));
      expect(screen.queryByTestId('location-view-panel')).not.toBeInTheDocument();
    });

    it('returns from Add location to the list', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('add-location-btn'));
      await screen.findByTestId('location-add-panel');
      expect(screen.getByTestId('location-back')).toHaveAccessibleName('Back to Locations');

      fireEvent.click(screen.getByTestId('location-back'));

      expect(screen.queryByTestId('location-add-panel')).not.toBeInTheDocument();
    });
  });
});
