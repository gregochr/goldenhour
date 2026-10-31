import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within, cleanup } from '@testing-library/react';
import TideManagementView from '../components/TideManagementView.jsx';
import { fetchAllTideStats } from '../api/tideApi.js';
import { fetchLocations } from '../api/forecastApi.js';

vi.mock('../api/tideApi.js', () => ({
  fetchAllTideStats: vi.fn(),
  fetchTideStats: vi.fn(),
}));
vi.mock('../api/forecastApi.js', () => ({ fetchLocations: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn(() => false) }));

function stats(overrides = {}) {
  return {
    avgHighMetres: 1.4, maxHighMetres: 1.8, avgLowMetres: -1.2, minLowMetres: -1.5,
    dataPoints: 5000, avgRangeMetres: 2.6, p75HighMetres: 1.6, p90HighMetres: 1.72,
    p95HighMetres: 1.78, springTideCount: 5, springTideFrequency: 0.05, springTideThreshold: 1.75,
    kingTideThreshold: 1.78, kingTideCount: 2,
    ...overrides,
  };
}

// Berwick 5000 readings, Cocklawburn 1500 (thin), Seahouses 9000, Mystery Bay 800 (thin, matches no location),
// Holy Island with the percentile and range figures missing.
const MOCK_STATS = {
  'Berwick-Upon-Tweed': stats(),
  'Cocklawburn Beach': stats({ dataPoints: 1500, avgRangeMetres: 2.4, p95HighMetres: 1.68, springTideFrequency: 0.033 }),
  Seahouses: stats({ dataPoints: 9000, avgRangeMetres: 3.1, p95HighMetres: 2.05, springTideFrequency: 0.08 }),
  'Mystery Bay': stats({ dataPoints: 800, avgRangeMetres: 1.9, p95HighMetres: 1.5 }),
  'Holy Island': stats({
    dataPoints: 4000, avgRangeMetres: null, p95HighMetres: null, springTideFrequency: null, p75HighMetres: null,
  }),
};

const LOCATIONS = [
  { id: 11, name: 'Berwick-Upon-Tweed', enabled: true, region: { id: 1, name: 'Northumberland' }, tideType: ['HIGH', 'MID'], subRegionName: 'Berwickshire coast' },
  { id: 12, name: 'Cocklawburn Beach', enabled: true, region: { id: 1, name: 'Northumberland' }, tideType: ['HIGH', 'MID', 'LOW'], subRegionName: 'Berwickshire coast' },
  { id: 13, name: 'Seahouses', enabled: false, region: { id: 2, name: 'The Lakes' }, tideType: [] },
  { id: 14, name: 'Holy Island', enabled: true, region: { id: 1, name: 'Northumberland' }, tideType: ['LOW'] },
];

async function renderLoaded(data = MOCK_STATS) {
  fetchAllTideStats.mockResolvedValue(data);
  render(<TideManagementView />);
  await screen.findByTestId('tides');
}

/** The location names in the grid, in row order. */
function listedNames() {
  return screen.getAllByTestId(/^tides-row-/).map((row) => row.querySelector('[data-testid^="tides-cell-location-"] span').textContent);
}

describe('TideManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.location.hash = '';
    fetchLocations.mockResolvedValue(LOCATIONS);
  });

  describe('loading and failure', () => {
    it('renders loading state initially', () => {
      fetchAllTideStats.mockReturnValue(new Promise(() => {}));
      render(<TideManagementView />);
      expect(screen.getByText('Loading tide statistics...')).toBeInTheDocument();
    });

    it('renders error state on fetch failure', async () => {
      fetchAllTideStats.mockRejectedValue(new Error('Network error'));
      render(<TideManagementView />);
      expect(await screen.findByText('Failed to load tide statistics.')).toBeInTheDocument();
    });

    it('displays the empty message when there are no statistics', async () => {
      fetchAllTideStats.mockResolvedValue({});
      render(<TideManagementView />);
      expect(await screen.findByText('No coastal locations with tide data found.')).toBeInTheDocument();
      expect(screen.queryByTestId('tides-search')).not.toBeInTheDocument();
    });

    it('still lists the statistics when the locations fail to load, with a notice', async () => {
      fetchLocations.mockRejectedValue(new Error('down'));
      await renderLoaded();
      expect(listedNames()).toHaveLength(5);
      expect(screen.getByTestId('tides-locations-failed')).toHaveTextContent("Couldn't load locations, so rows can't open.");
      // With no locations nothing joins, so no row opens.
      expect(screen.queryAllByRole('button', { name: /Berwick/ })).toHaveLength(0);
    });
  });

  describe('the list', () => {
    it('shows no notice when the locations load', async () => {
      await renderLoaded();
      expect(screen.queryByTestId('tides-locations-failed')).not.toBeInTheDocument();
    });

    it('keeps a deep link in the address when the locations failed', async () => {
      fetchLocations.mockRejectedValue(new Error('down'));
      window.location.hash = '#manage/tides/11';
      await renderLoaded();
      await new Promise((resolve) => setTimeout(resolve, 20));
      expect(window.location.hash).toBe('#manage/tides/11');
      expect(screen.queryByTestId('tide-panel-shell')).not.toBeInTheDocument();
    });

    it('hides the Region chip when no location has a region', async () => {
      fetchLocations.mockResolvedValue(LOCATIONS.map((l) => ({ ...l, region: null })));
      await renderLoaded();
      expect(screen.queryByTestId('tides-region')).not.toBeInTheDocument();
      expect(screen.getByTestId('tides-chip-thin')).toBeInTheDocument();
    });

    it('captions the Range column with the scale', async () => {
      await renderLoaded();
      expect(screen.getByTestId('tides-header')).toHaveTextContent('Range −3.5 · 0 · +3.5');
    });

    it('shows the header, the subtitle and the key', async () => {
      await renderLoaded();
      expect(screen.getByRole('heading', { name: 'Tides' })).toBeInTheDocument();
      expect(screen.getByTestId('tides-total')).toHaveTextContent('5 coastal locations · heights in metres');
      const key = screen.getByTestId('tides-key');
      expect(key).toHaveTextContent('Lowest low to highest high');
      expect(key).toHaveTextContent('Average low to average high');
      expect(key).toHaveTextContent('P95 high');
    });

    it('says "1 coastal location" in the singular', async () => {
      await renderLoaded({ Seahouses: stats() });
      expect(screen.getByTestId('tides-total')).toHaveTextContent('1 coastal location · heights in metres');
    });

    it('lists by name ascending by default, with the figures and the sub-region', async () => {
      await renderLoaded();
      expect(listedNames()).toEqual(['Berwick-Upon-Tweed', 'Cocklawburn Beach', 'Holy Island', 'Mystery Bay', 'Seahouses']);
      expect(screen.getByTestId('tides-sort-name')).toHaveTextContent('Location ▲');
      expect(screen.getByTestId('tides-cell-location-11')).toHaveTextContent('Berwickshire coast');
      expect(screen.getByTestId('tide-avg-range-11')).toHaveTextContent(/^2\.60$/);
      expect(screen.getByTestId('tides-cell-p95High-11')).toHaveTextContent(/^1\.78$/);
      expect(screen.getByTestId('tides-cell-p95High-11').innerHTML).not.toContain('text-verdict-marginal');
      expect(screen.getByTestId('tides-cell-spring-11')).toHaveTextContent('5.0%');
      expect(screen.getByTestId('tide-readings-11')).toHaveTextContent('5,000');
    });

    it('draws a range bar on every row', async () => {
      await renderLoaded();
      expect(screen.getByTestId('tide-bar-11')).toHaveAttribute('aria-label', expect.stringContaining('lowest low -1.50 m to highest high 1.80 m'));
      expect(screen.getAllByTestId(/^tide-bar-[^-]+$/)).toHaveLength(5);
    });

    it('renders null figures as an em dash and drops that bar tick', async () => {
      await renderLoaded();
      expect(screen.getByTestId('tide-avg-range-14')).toHaveTextContent('—');
      expect(screen.getByTestId('tides-cell-p95High-14')).toHaveTextContent('—');
      expect(screen.getByTestId('tides-cell-spring-14')).toHaveTextContent('—');
      expect(screen.queryByTestId('tide-bar-14-p95')).not.toBeInTheDocument();
      expect(screen.getByTestId('tide-bar-14-full')).toBeInTheDocument();
    });

    it('marks thin readings amber and leaves the rest alone', async () => {
      await renderLoaded();
      expect(screen.getByTestId('tide-readings-12').className).toContain('text-verdict-marginal');
      expect(screen.getByTestId('tide-readings-11').className).not.toContain('text-verdict-marginal');
    });

    it('marks the Spring and Readings columns secondary and puts the average range on the card line 1', async () => {
      await renderLoaded();
      expect(screen.getByTestId('tides-cell-spring-11').className).toContain('@max-[820px]:hidden');
      expect(screen.getByTestId('tides-cell-readings-11').className).toContain('@max-[820px]:hidden');
      expect(screen.getByTestId('tides-cell-card-status-11')).toHaveTextContent('2.60 m');
      expect(screen.getByTestId('tides-cell-range-11').className).toContain('@max-[540px]:col-span-2');
    });
  });

  describe('search, chips and region', () => {
    it('filters by location name', async () => {
      await renderLoaded();
      fireEvent.change(screen.getByTestId('tides-search'), { target: { value: 'tweed' } });
      expect(listedNames()).toEqual(['Berwick-Upon-Tweed']);
    });

    it('searches the sub-region too', async () => {
      await renderLoaded();
      fireEvent.change(screen.getByTestId('tides-search'), { target: { value: 'berwickshire' } });
      expect(listedNames()).toEqual(['Berwick-Upon-Tweed', 'Cocklawburn Beach']);
    });

    it('counts All and Thin data, and the Thin chip keeps only the rows under 2000 readings', async () => {
      await renderLoaded();
      expect(screen.getByTestId('tides-chip-all-count')).toHaveTextContent('5');
      expect(screen.getByTestId('tides-chip-thin-count')).toHaveTextContent('2');
      fireEvent.click(screen.getByTestId('tides-chip-thin'));
      expect(listedNames()).toEqual(['Cocklawburn Beach', 'Mystery Bay']);
      expect(screen.getByTestId('tides-chip-thin')).toHaveAttribute('aria-pressed', 'true');
      fireEvent.click(screen.getByTestId('tides-chip-all'));
      expect(listedNames()).toHaveLength(5);
    });

    it('treats exactly 2000 readings as not thin', async () => {
      await renderLoaded({ Seahouses: stats({ dataPoints: 2000 }), 'Mystery Bay': stats({ dataPoints: 1999 }) });
      expect(screen.getByTestId('tides-chip-thin-count')).toHaveTextContent('1');
    });

    it('offers the regions that exist and filters by one, narrowing the chip counts', async () => {
      await renderLoaded();
      const select = screen.getByTestId('tides-region');
      expect(within(select).getAllByRole('option').map((o) => o.textContent)).toEqual(['All regions', 'Northumberland', 'The Lakes']);
      fireEvent.change(select, { target: { value: 'Northumberland' } });
      // Mystery Bay has no location and so no region: it leaves a region-filtered list.
      expect(listedNames()).toEqual(['Berwick-Upon-Tweed', 'Cocklawburn Beach', 'Holy Island']);
      expect(screen.getByTestId('tides-chip-all-count')).toHaveTextContent('3');
      expect(screen.getByTestId('tides-chip-thin-count')).toHaveTextContent('1');
    });

    it('says nothing matches, and Clear filters resets every filter', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-chip-thin'));
      fireEvent.change(screen.getByTestId('tides-search'), { target: { value: 'zzz' } });
      expect(screen.getByTestId('tides-no-match')).toHaveTextContent('No locations match.');
      fireEvent.click(screen.getByTestId('tides-clear-filters'));
      expect(listedNames()).toHaveLength(5);
      expect(screen.getByTestId('tides-search')).toHaveValue('');
      expect(screen.getByTestId('tides-chip-thin')).toHaveAttribute('aria-pressed', 'false');
    });
  });

  describe('sorting', () => {
    it('sorts by readings, ascending then descending', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-sort-dataPoints'));
      expect(listedNames()).toEqual(['Mystery Bay', 'Cocklawburn Beach', 'Holy Island', 'Berwick-Upon-Tweed', 'Seahouses']);
      fireEvent.click(screen.getByTestId('tides-sort-dataPoints'));
      expect(listedNames()[0]).toBe('Seahouses');
    });

    it('sorts by average range with the missing one last in either direction', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-sort-avgRange'));
      expect(listedNames()).toEqual(['Mystery Bay', 'Cocklawburn Beach', 'Berwick-Upon-Tweed', 'Seahouses', 'Holy Island']);
      fireEvent.click(screen.getByTestId('tides-sort-avgRange'));
      expect(listedNames()).toEqual(['Seahouses', 'Berwick-Upon-Tweed', 'Cocklawburn Beach', 'Mystery Bay', 'Holy Island']);
    });

    it('sorts by P95 high and by spring share', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-sort-p95High'));
      expect(listedNames()[0]).toBe('Mystery Bay');
      expect(listedNames()[4]).toBe('Holy Island');
      fireEvent.click(screen.getByTestId('tides-sort-spring'));
      expect(listedNames()[0]).toBe('Cocklawburn Beach');
      expect(listedNames()[4]).toBe('Holy Island');
    });

    it('has no sort button on the Range column', async () => {
      await renderLoaded();
      expect(screen.queryByTestId('tides-sort-range')).not.toBeInTheDocument();
    });

    it('sorts from the sort chip a card list shows', async () => {
      await renderLoaded();
      fireEvent.change(screen.getByTestId('tides-sort-chip'), { target: { value: 'dataPoints:desc' } });
      expect(listedNames()[0]).toBe('Seahouses');
    });
  });

  describe('pagination', () => {
    it('pages a long list through the shared Pagination', async () => {
      const many = Object.fromEntries(Array.from({ length: 13 }, (_, i) => [`Beach ${String(i).padStart(2, '0')}`, stats()]));
      await renderLoaded(many);
      expect(listedNames()).toHaveLength(10);
      expect(screen.getByTestId('pagination-summary')).toHaveTextContent('1–10 of 13');
      fireEvent.click(screen.getByTestId('pagination-next'));
      expect(listedNames()).toHaveLength(3);
    });
  });

  describe('the name join', () => {
    it('lists a statistics entry with no matching location, but its row opens nothing', async () => {
      await renderLoaded();
      const orphan = screen.getByTestId('tides-row-stats:Mystery Bay');
      expect(orphan).not.toHaveAttribute('role');
      expect(orphan).not.toHaveAttribute('tabindex');
      fireEvent.click(orphan);
      fireEvent.keyDown(orphan, { key: 'Enter' });
      expect(screen.queryByTestId('tide-panel-shell')).not.toBeInTheDocument();
      expect(window.location.hash).toBe('');
      expect(within(orphan).queryByText('Berwickshire coast')).not.toBeInTheDocument();
    });

    it('gives a matched row a button name that reads the row', async () => {
      await renderLoaded();
      const row = screen.getByRole('button', { name: /^Cocklawburn Beach, Berwickshire coast, average range 2.40 m/ });
      expect(row).toHaveAccessibleName(expect.stringContaining('1,500 readings, thin data'));
    });

    it('takes the sub-region from SUB_REGION areas only', async () => {
      await renderLoaded();
      expect(screen.getByTestId('tides-cell-location-11')).not.toHaveTextContent('Not a sub-region');
    });
  });

  describe('the panel', () => {
    it('opens on a row click, writes the hash and fills the sections', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-row-11'));
      expect(window.location.hash).toBe('#manage/tides/11');
      expect(screen.getByTestId('tide-panel-shell-title')).toHaveTextContent('Berwick-Upon-Tweed');
      expect(screen.getByTestId('tide-panel-shell-subtitle')).toHaveTextContent('Berwickshire coast · 5,000 readings');
      expect(screen.getByTestId('tide-panel-bar-labels')).toHaveTextContent('−1.50 m0+1.80 m');
      expect(screen.getByTestId('tide-panel-bar')).toBeInTheDocument();
      expect(screen.getByTestId('tide-panel-max-high')).toHaveTextContent('1.80 m');
      expect(screen.getByTestId('tide-panel-avg-high')).toHaveTextContent('1.40 m');
      expect(screen.getByTestId('tide-panel-avg-range')).toHaveTextContent('2.60 m');
      expect(screen.getByTestId('tide-panel-min-low')).toHaveTextContent('-1.50 m');
      expect(screen.getByTestId('tide-panel-avg-low')).toHaveTextContent('-1.20 m');
      expect(screen.getByTestId('tide-panel-readings')).toHaveTextContent('5,000');
      expect(screen.getByTestId('tide-panel-p75')).toHaveTextContent('1.60 m');
      expect(screen.getByTestId('tide-panel-p90')).toHaveTextContent('1.72 m');
      expect(screen.getByTestId('tide-panel-p95')).toHaveTextContent('1.78 m');
      expect(screen.getByTestId('tide-panel-spring')).toHaveTextContent('above 1.75 m · 5.0% of highs · 5');
      expect(screen.getByTestId('tide-panel-king')).toHaveTextContent('above 1.78 m · 2 highs');
      expect(screen.getByText('Spring is more than 125% of the average high. King is above P95.')).toBeInTheDocument();
      expect(screen.getByTestId('tide-panel-tide-types')).toHaveTextContent('High, Mid');
      expect(screen.getByTestId('tide-panel-status')).toHaveTextContent('Active');
    });

    it('opens on Enter and on Space', async () => {
      await renderLoaded();
      const row = screen.getByTestId('tides-row-12');
      fireEvent.keyDown(row, { key: 'Enter' });
      expect(screen.getByTestId('tide-panel-shell-title')).toHaveTextContent('Cocklawburn Beach');
      fireEvent.keyDown(screen.getByTestId('tides-row-14'), { key: ' ' });
      expect(screen.getByTestId('tide-panel-shell-title')).toHaveTextContent('Holy Island');
    });

    it('is an aside, never a dialog', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-row-11'));
      expect(screen.getByRole('complementary', { name: 'Tide statistics for Berwick-Upon-Tweed' })).toBeInTheDocument();
      expect(document.querySelector('[role="dialog"], [aria-modal="true"]')).toBeNull();
    });

    it('opens on mount for a hash naming a location', async () => {
      window.location.hash = '#manage/tides/13';
      await renderLoaded();
      expect(screen.getByTestId('tide-panel-shell-title')).toHaveTextContent('Seahouses');
      expect(screen.getByTestId('tide-panel-status')).toHaveTextContent('Disabled');
      expect(screen.getByTestId('tide-panel-tide-types')).toHaveTextContent('—');
    });

    it('leaves the bare tab address for a hash naming no listed location', async () => {
      window.location.hash = '#manage/tides/9999';
      await renderLoaded();
      await waitFor(() => expect(window.location.hash).toBe('#manage/tides'));
      expect(screen.queryByTestId('tide-panel-shell')).not.toBeInTheDocument();
    });

    it('follows the hash moving to another location and to none', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-row-11'));
      window.location.hash = '#manage/tides/12';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
      await waitFor(() => expect(screen.getByTestId('tide-panel-shell-title')).toHaveTextContent('Cocklawburn Beach'));
      window.location.hash = '#manage/tides';
      window.dispatchEvent(new HashChangeEvent('hashchange'));
      await waitFor(() => expect(screen.queryByTestId('tide-panel-shell')).not.toBeInTheDocument());
    });

    it('closes from the ×, clearing the hash, and on Escape', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-row-11'));
      fireEvent.click(screen.getByTestId('tide-panel-shell-close'));
      expect(screen.queryByTestId('tide-panel-shell')).not.toBeInTheDocument();
      expect(window.location.hash).toBe('#manage/tides');
      fireEvent.click(screen.getByTestId('tides-row-11'));
      fireEvent.keyDown(document, { key: 'Escape' });
      expect(screen.queryByTestId('tide-panel-shell')).not.toBeInTheDocument();
    });

    it('closes when a filter takes the open location out of the list, but not when a sort moves it', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-row-11'));
      fireEvent.click(screen.getByTestId('tides-sort-dataPoints'));
      expect(screen.getByTestId('tide-panel-shell')).toBeInTheDocument();
      fireEvent.click(screen.getByTestId('tides-chip-thin'));
      expect(screen.queryByTestId('tide-panel-shell')).not.toBeInTheDocument();
    });

    it('links to the location in Locations by setting the hash', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-row-11'));
      expect(screen.getByTestId('tide-open-in-locations')).toHaveAttribute('href', '#manage/locations/11');
    });

    it('shows the thin-data warning with the median reading count', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-row-12'));
      // The others read 5000, 9000, 800 and 4000: their median is 4500 (its own 1500 is left out).
      expect(screen.getByTestId('tide-thin-warning')).toHaveTextContent('Thin data');
      expect(screen.getByTestId('tide-thin-warning')).toHaveTextContent(
        '1,500 readings, against about 4,500 for its neighbours. Percentiles here are less reliable.',
      );
      expect(screen.getByTestId('tide-panel-readings').className).toContain('text-verdict-marginal');
    });

    it('drops the comparison when fewer than three other locations have counts', async () => {
      await renderLoaded({ A: stats({ dataPoints: 500 }), B: stats({ dataPoints: 6000 }), C: stats({ dataPoints: 7000 }) });
      fetchLocations.mockResolvedValue([{ id: 1, name: 'A', enabled: true }]);
      cleanup();
      render(<TideManagementView />);
      await screen.findByTestId('tides');
      fireEvent.click(screen.getByTestId('tides-row-1'));
      const warning = screen.getByTestId('tide-thin-warning');
      expect(warning).toHaveTextContent('500 readings. Percentiles here are less reliable.');
      expect(warning).not.toHaveTextContent('against about');
    });

    it('reads a null threshold as a dash for the whole value, and labels the P95 stat "P95 · king"', async () => {
      await renderLoaded({
        ...MOCK_STATS,
        'Holy Island': stats({ springTideThreshold: null, kingTideThreshold: null, dataPoints: null }),
      });
      fireEvent.click(screen.getByTestId('tides-row-14'));
      expect(screen.getByTestId('tide-panel-spring')).toHaveTextContent(/^—$/);
      expect(screen.getByTestId('tide-panel-king')).toHaveTextContent(/^—$/);
      expect(screen.getByText('P95 · king')).toBeInTheDocument();
      expect(screen.queryByTestId('tide-panel-shell-subtitle')).not.toBeInTheDocument();
      expect(screen.getByTestId('tide-panel-readings')).toHaveTextContent('—');
    });

    it('omits the readings part of a row name when the count is missing', async () => {
      await renderLoaded({ ...MOCK_STATS, 'Holy Island': stats({ dataPoints: null }) });
      const row = screen.getByTestId('tides-row-14');
      expect(row.getAttribute('aria-label')).not.toContain('readings');
    });

    it('shows no warning for a location with enough readings', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-row-11'));
      expect(screen.queryByTestId('tide-thin-warning')).not.toBeInTheDocument();
    });

    it('reads missing figures as an em dash and omits the missing tick', async () => {
      await renderLoaded();
      fireEvent.click(screen.getByTestId('tides-row-14'));
      expect(screen.getByTestId('tide-panel-avg-range')).toHaveTextContent('—');
      expect(screen.getByTestId('tide-panel-p95')).toHaveTextContent('—');
      expect(screen.getByTestId('tide-panel-p75')).toHaveTextContent('—');
      expect(screen.queryByTestId('tide-panel-bar-p95')).not.toBeInTheDocument();
    });

    it('stays open and unchanged when the page changes while its row is still in the filtered set', async () => {
      const many = Object.fromEntries(Array.from({ length: 13 }, (_, i) => [`Beach ${String(i).padStart(2, '0')}`, stats()]));
      fetchLocations.mockResolvedValue(Array.from({ length: 13 }, (_, i) => ({ id: 100 + i, name: `Beach ${String(i).padStart(2, '0')}`, enabled: true })));
      await renderLoaded(many);
      fireEvent.click(screen.getByTestId('tides-row-100'));
      fireEvent.click(screen.getByTestId('pagination-next'));
      expect(screen.getByTestId('tide-panel-shell')).toBeInTheDocument();
    });
  });
});
