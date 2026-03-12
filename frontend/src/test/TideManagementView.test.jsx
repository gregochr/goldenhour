import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import TideManagementView from '../components/TideManagementView.jsx';

vi.mock('../api/tideApi', () => ({
  fetchAllTideStats: vi.fn(),
  fetchTideStats: vi.fn(),
  fetchTidesForDate: vi.fn(),
}));

import { fetchAllTideStats } from '../api/tideApi';

const MOCK_STATS = {
  'Berwick-Upon-Tweed': {
    avgHighMetres: 1.4, maxHighMetres: 1.8, avgLowMetres: -1.2, minLowMetres: -1.5,
    dataPoints: 200, avgRangeMetres: 2.6, p75HighMetres: 1.6, p90HighMetres: 1.72,
    p95HighMetres: 1.78, springTideCount: 5, springTideFrequency: 0.05,
  },
  'Cocklawburn Beach': {
    avgHighMetres: 1.3, maxHighMetres: 1.7, avgLowMetres: -1.1, minLowMetres: -1.4,
    dataPoints: 180, avgRangeMetres: 2.4, p75HighMetres: 1.5, p90HighMetres: 1.62,
    p95HighMetres: 1.68, springTideCount: 3, springTideFrequency: 0.033,
  },
};

describe('TideManagementView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders loading state initially', () => {
    fetchAllTideStats.mockReturnValue(new Promise(() => {}));
    render(<TideManagementView />);
    expect(screen.getByText('Loading tide statistics...')).toBeInTheDocument();
  });

  it('renders tide data after loading', async () => {
    fetchAllTideStats.mockResolvedValue(MOCK_STATS);
    render(<TideManagementView />);

    await waitFor(() => {
      expect(screen.getByText('Berwick-Upon-Tweed')).toBeInTheDocument();
      expect(screen.getByText('Cocklawburn Beach')).toBeInTheDocument();
    });

    expect(screen.getByText('2 coastal locations with tide data')).toBeInTheDocument();
  });

  it('renders error state on fetch failure', async () => {
    fetchAllTideStats.mockRejectedValue(new Error('Network error'));
    render(<TideManagementView />);

    await waitFor(() => {
      expect(screen.getByText('Failed to load tide statistics.')).toBeInTheDocument();
    });
  });

  it('filters by location name', async () => {
    fetchAllTideStats.mockResolvedValue(MOCK_STATS);
    render(<TideManagementView />);

    await waitFor(() => {
      expect(screen.getByText('Berwick-Upon-Tweed')).toBeInTheDocument();
    });

    const filterInput = screen.getByTestId('filter-name');
    fireEvent.change(filterInput, { target: { value: 'berwick' } });

    expect(screen.getByText('Berwick-Upon-Tweed')).toBeInTheDocument();
    expect(screen.queryByText('Cocklawburn Beach')).not.toBeInTheDocument();
  });

  it('sorts by data points when header clicked', async () => {
    fetchAllTideStats.mockResolvedValue(MOCK_STATS);
    render(<TideManagementView />);

    await waitFor(() => {
      expect(screen.getByText('Berwick-Upon-Tweed')).toBeInTheDocument();
    });

    // Click "Data Pts" to sort ascending — Cocklawburn (180) before Berwick (200)
    fireEvent.click(screen.getByText('Data Pts'));

    const rows = screen.getAllByRole('row').filter((r) => r.querySelector('td'));
    const firstRowText = rows[0].textContent;
    expect(firstRowText).toContain('Cocklawburn Beach');
  });

  it('displays no data message when empty', async () => {
    fetchAllTideStats.mockResolvedValue({});
    render(<TideManagementView />);

    await waitFor(() => {
      expect(screen.getByText('No coastal locations with tide data found.')).toBeInTheDocument();
    });
  });

  it('shows table with correct data-testid', async () => {
    fetchAllTideStats.mockResolvedValue(MOCK_STATS);
    render(<TideManagementView />);

    await waitFor(() => {
      expect(screen.getByTestId('tides-table')).toBeInTheDocument();
    });
  });
});
