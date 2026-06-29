import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import TravelDaysView from '../components/TravelDaysView.jsx';

vi.mock('../api/travelDayApi', () => ({
  fetchTravelDays: vi.fn(),
  addTravelDay: vi.fn(),
  deleteTravelDay: vi.fn(),
}));

import {
  fetchTravelDays,
  addTravelDay,
  deleteTravelDay,
} from '../api/travelDayApi';

const MOCK_RANGES = [
  { id: 1, startDate: '2026-07-01', endDate: '2026-07-03', note: 'London — work' },
  { id: 2, startDate: '2026-07-10', endDate: '2026-07-10', note: null },
];

describe('TravelDaysView', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    fetchTravelDays.mockResolvedValue(MOCK_RANGES);
    addTravelDay.mockResolvedValue({});
    deleteTravelDay.mockResolvedValue();
  });

  it('renders existing travel ranges', async () => {
    render(<TravelDaysView />);
    expect(await screen.findByTestId('travel-day-1')).toBeInTheDocument();
    expect(screen.getByTestId('travel-day-2')).toBeInTheDocument();
    expect(screen.getByText('London — work')).toBeInTheDocument();
  });

  it('shows an empty state when there are no ranges', async () => {
    fetchTravelDays.mockResolvedValue([]);
    render(<TravelDaysView />);
    expect(await screen.findByTestId('travel-day-empty')).toBeInTheDocument();
  });

  it('adds a new range and reloads', async () => {
    render(<TravelDaysView />);
    await screen.findByTestId('travel-day-1');

    fireEvent.change(screen.getByTestId('travel-day-start'), {
      target: { value: '2026-08-01' },
    });
    fireEvent.change(screen.getByTestId('travel-day-end'), {
      target: { value: '2026-08-02' },
    });
    fireEvent.change(screen.getByTestId('travel-day-note'), {
      target: { value: 'Conference' },
    });
    fireEvent.click(screen.getByTestId('travel-day-add'));

    await waitFor(() =>
      expect(addTravelDay).toHaveBeenCalledWith({
        startDate: '2026-08-01',
        endDate: '2026-08-02',
        note: 'Conference',
      }),
    );
    expect(fetchTravelDays).toHaveBeenCalledTimes(2);
  });

  it('rejects an inverted range without calling the API', async () => {
    render(<TravelDaysView />);
    await screen.findByTestId('travel-day-1');

    fireEvent.change(screen.getByTestId('travel-day-start'), {
      target: { value: '2026-08-05' },
    });
    fireEvent.change(screen.getByTestId('travel-day-end'), {
      target: { value: '2026-08-01' },
    });
    fireEvent.click(screen.getByTestId('travel-day-add'));

    await screen.findByText('End date must not be before start date');
    expect(addTravelDay).not.toHaveBeenCalled();
  });

  it('deletes a range', async () => {
    render(<TravelDaysView />);
    await screen.findByTestId('travel-day-1');

    fireEvent.click(screen.getByTestId('travel-day-delete-1'));

    await waitFor(() => expect(deleteTravelDay).toHaveBeenCalledWith(1));
  });
});
