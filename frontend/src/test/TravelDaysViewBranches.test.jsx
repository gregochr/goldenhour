import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import TravelDaysView from '../components/TravelDaysView.jsx';

vi.mock('../api/travelDayApi', () => ({
  fetchTravelDays: vi.fn(),
  addTravelDay: vi.fn(),
  updateTravelDay: vi.fn(),
  deleteTravelDay: vi.fn(),
}));
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));
vi.mock('../hooks/useIsMobile.js', () => ({ useIsMobile: vi.fn() }));

import { fetchTravelDays, addTravelDay } from '../api/travelDayApi';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';
import { useIsMobile } from '../hooks/useIsMobile.js';

// Real "now" is Mon 2 Nov 2026.
const LONDON = { id: 1, startDate: '2026-11-04', endDate: '2026-11-06', note: 'London' };
const SINGLE = { id: 2, startDate: '2026-11-10', endDate: '2026-11-10', note: null };

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
}

function mount(ranges = [LONDON, SINGLE]) {
  fetchTravelDays.mockResolvedValue(ranges);
  return render(<TravelDaysView />);
}

/** A touch tap on a day (down, up, click). */
function tap(iso) {
  const cell = screen.getByTestId(`travel-cell-${iso}`);
  fireEvent.pointerDown(cell, { button: 0, pointerType: 'touch' });
  fireEvent.pointerUp(cell, { button: 0, pointerType: 'touch' });
  fireEvent.click(cell);
}

async function draftRange(start = '2026-11-18', end = '2026-11-20') {
  await screen.findByTestId('travel-days-row-1');
  tap(start);
  tap(end);
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T10:00:00Z'));
  window.location.hash = '#manage/traveldays';
  useIsSplitLayout.mockReturnValue(true);
  useIsMobile.mockReturnValue(false);
});

afterEach(() => {
  vi.useRealTimers();
});

describe('opening a range from a calendar day', () => {
  it('keeps the panel and the address when the day belongs to the range already open', async () => {
    mount();
    fireEvent.click(await screen.findByTestId('travel-days-row-1'));
    await screen.findByTestId('travel-panel');

    tap('2026-11-05');

    expect(screen.getByTestId('travel-panel-title')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
    expect(window.location.hash).toBe('#manage/traveldays/1');
  });

  it('replaces an open Add panel with the pressed range', async () => {
    mount();
    fireEvent.click(await screen.findByTestId('travel-day-add-open'));
    await screen.findByTestId('travel-add-panel');

    tap('2026-11-05');

    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();
  });
});

describe('the draft bar posting', () => {
  it('ignores calendar changes while its own save is posting', async () => {
    mount();
    await draftRange();
    const posting = deferred();
    addTravelDay.mockReturnValue(posting.promise);

    fireEvent.click(screen.getByTestId('travel-draft-add'));
    tap('2026-11-25');

    expect(screen.getByTestId('travel-draft-text')).toHaveTextContent('Wed 18 Nov → Fri 20 Nov · 3 days');
    await act(async () => { posting.resolve({ id: 9, startDate: '2026-11-18', endDate: '2026-11-20', note: null }); });
    await waitFor(() => expect(screen.queryByTestId('travel-draft-bar')).not.toBeInTheDocument());
  });

  it('merges the new range in when the reload fails, and still opens it', async () => {
    mount();
    await draftRange();
    addTravelDay.mockResolvedValue({ id: 9, startDate: '2026-11-18', endDate: '2026-11-20', note: null });
    fetchTravelDays.mockRejectedValue(new Error('down'));

    fireEvent.click(screen.getByTestId('travel-draft-add'));

    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 18 Nov → Fri 20 Nov');
    expect(window.location.hash).toBe('#manage/traveldays/9');
    expect(screen.getByTestId('travel-error')).toHaveTextContent('Failed to load travel days');
  });

  it('does not list a range twice when the failed reload already had it', async () => {
    mount();
    await draftRange();
    addTravelDay.mockResolvedValue({ id: 1, startDate: '2026-11-04', endDate: '2026-11-06', note: 'London' });
    fetchTravelDays.mockRejectedValue(new Error('down'));

    fireEvent.click(screen.getByTestId('travel-draft-add'));

    await waitFor(() => expect(screen.getByTestId('travel-error')).toBeInTheDocument());
    expect(screen.getAllByTestId('travel-days-row-1')).toHaveLength(1);
  });

  it('opens the created range from the draft dates when the server omits its start', async () => {
    mount();
    await draftRange();
    addTravelDay.mockResolvedValue({ id: 9 });
    fetchTravelDays.mockResolvedValue([
      LONDON, SINGLE, { id: 9, startDate: '2026-11-18', endDate: '2026-11-20', note: null },
    ]);

    fireEvent.click(screen.getByTestId('travel-draft-add'));

    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 18 Nov → Fri 20 Nov');
    expect(screen.getByTestId('travel-calendar-month')).toHaveTextContent('November 2026');
  });

  it('does not pull the reader away when they opened another range meanwhile', async () => {
    mount();
    await draftRange();
    const posting = deferred();
    addTravelDay.mockReturnValue(posting.promise);

    fireEvent.click(screen.getByTestId('travel-draft-add'));
    fireEvent.click(screen.getByTestId('travel-days-row-2'));
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Tue 10 Nov');

    await act(async () => { posting.resolve({ id: 9, startDate: '2026-11-18', endDate: '2026-11-20', note: null }); });
    await waitFor(() => expect(fetchTravelDays).toHaveBeenCalledTimes(2));

    expect(window.location.hash).toBe('#manage/traveldays/2');
  });
});

describe('the Add panel saving', () => {
  it('does not take a calendar draft into From and To while its save is in flight', async () => {
    mount();
    fireEvent.click(await screen.findByTestId('travel-day-add-open'));
    await screen.findByTestId('travel-add-panel');
    fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: '2026-11-18' } });
    fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: '2026-11-19' } });
    const posting = deferred();
    addTravelDay.mockReturnValue(posting.promise);
    fireEvent.click(screen.getByTestId('travel-day-add'));
    await waitFor(() => expect(addTravelDay).toHaveBeenCalled());

    tap('2026-11-24');
    tap('2026-11-26');

    expect(screen.getByTestId('travel-day-start')).toHaveValue('2026-11-18');
    expect(screen.getByTestId('travel-day-end')).toHaveValue('2026-11-19');
    await act(async () => { posting.reject(new Error('stop')); });
  });
});
