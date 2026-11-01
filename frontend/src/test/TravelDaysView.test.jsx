import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import TravelDaysView from '../components/TravelDaysView.jsx';
import { setRewind } from '../utils/rewind.js';

vi.mock('../api/travelDayApi', () => ({
  fetchTravelDays: vi.fn(),
  addTravelDay: vi.fn(),
  updateTravelDay: vi.fn(),
  deleteTravelDay: vi.fn(),
}));
// jsdom has no layout: the split breakpoint (1000px) is decided here, so the list stays reachable beside the panel.
vi.mock('../hooks/useIsSplitLayout.js', () => ({ useIsSplitLayout: vi.fn() }));

import {
  fetchTravelDays, addTravelDay, updateTravelDay, deleteTravelDay,
} from '../api/travelDayApi';
import { useIsSplitLayout } from '../hooks/useIsSplitLayout.js';

// The Travel days tab end to end: the admin list (past included) on the list-and-panel shell, the calendar, the hash,
// and Edit, Remove and Add. The real "now" is pinned to Mon 2 Nov 2026 (Date only, so timers still run).

const LONDON = { id: 1, startDate: '2026-11-04', endDate: '2026-11-06', note: 'London — work' };
const SINGLE = { id: 2, startDate: '2026-11-10', endDate: '2026-11-10', note: null };
const LEEDS = { id: 3, startDate: '2026-10-26', endDate: '2026-10-28', note: 'Leeds' };
const NOW_RANGE = { id: 4, startDate: '2026-11-02', endDate: '2026-11-02', note: 'Today only' };
const RANGES = [SINGLE, LONDON, NOW_RANGE, LEEDS];

function mount(ranges = RANGES) {
  fetchTravelDays.mockResolvedValue(ranges);
  return render(<TravelDaysView />);
}

/** Opens a range's panel by pressing its row. */
async function openPanel(id) {
  fireEvent.click(await screen.findByTestId(`travel-days-row-${id}`));
  return screen.findByTestId('travel-panel');
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-02T10:00:00Z'));
  window.location.hash = '#manage/traveldays';
  setRewind(null);
  useIsSplitLayout.mockReturnValue(true);
  addTravelDay.mockResolvedValue({ id: 9 });
  updateTravelDay.mockResolvedValue({});
  deleteTravelDay.mockResolvedValue();
});

afterEach(() => {
  setRewind(null);
  vi.useRealTimers();
});

describe('TravelDaysView: the list', () => {
  it('asks the admin endpoint for past ranges too', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    expect(fetchTravelDays).toHaveBeenCalledWith({ includePast: true });
  });

  it('heads the list with the title, the upcoming count and Add range, then the intro', async () => {
    mount();
    expect(await screen.findByTestId('travel-heading')).toHaveTextContent('Travel days');
    expect(screen.getByTestId('travel-meta')).toHaveTextContent('3 upcoming');
    expect(screen.getByTestId('travel-day-add-open')).toHaveTextContent('Add range');
    expect(screen.getByTestId('travel-intro'))
      .toHaveTextContent("The overnight batch skips forecasts for days you're away. Start and end dates are both included.");
  });

  it('groups Upcoming open and Past collapsed, and expands Past on a press', async () => {
    mount();
    expect(await screen.findByTestId('travel-days-group-upcoming')).toHaveTextContent('Upcoming · 3');
    expect(screen.getByTestId('travel-days-group-upcoming')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('travel-days-group-past')).toHaveTextContent('Past · 1');
    expect(screen.getByTestId('travel-days-group-past')).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByTestId('travel-days-row-3')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('travel-days-group-past'));
    expect(screen.getByTestId('travel-days-row-3')).toBeInTheDocument();
    expect(screen.getByTestId('travel-when-3')).toHaveTextContent('Ended 28 Oct');
  });

  it('orders Upcoming soonest first', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    const order = screen.getAllByTestId(/^travel-days-row-/).map((row) => row.dataset.testid);
    expect(order).toEqual(['travel-days-row-4', 'travel-days-row-1', 'travel-days-row-2']);
  });

  it('writes each row\'s Range, Length and When', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    expect(screen.getByTestId('travel-range-1')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
    expect(screen.getByTestId('travel-days-row-1')).toHaveTextContent('London — work');
    expect(screen.getByTestId('travel-length-1')).toHaveTextContent('3 days');
    expect(screen.getByTestId('travel-when-1')).toHaveTextContent('In 2 days');
    expect(screen.getByTestId('travel-range-2')).toHaveTextContent('Tue 10 Nov');
    expect(screen.getByTestId('travel-range-2')).not.toHaveTextContent('→');
    expect(screen.getByTestId('travel-length-2')).toHaveTextContent('1 day');
    expect(screen.getByTestId('travel-when-4')).toHaveTextContent('Now');
  });

  it('reads the whole row as the row\'s name', async () => {
    mount();
    expect(await screen.findByRole('button', {
      name: 'Wed 4 Nov → Fri 6 Nov, 3 days, In 2 days, London — work',
    })).toBeInTheDocument();
  });

  it('says so when no range is set', async () => {
    mount([]);
    expect(await screen.findByTestId('travel-day-empty')).toHaveTextContent('No travel days set. Every date is forecast.');
    expect(screen.getByTestId('travel-meta')).toHaveTextContent('0 upcoming');
  });

  it('shows a banner, and not the empty state, when the first load fails', async () => {
    fetchTravelDays.mockRejectedValue(new Error('down'));
    render(<TravelDaysView />);
    expect(await screen.findByTestId('travel-error')).toHaveTextContent('Failed to load travel days');
    expect(screen.queryByTestId('travel-day-empty')).not.toBeInTheDocument();
  });
});

describe('TravelDaysView: the calendar', () => {
  it('draws five weeks from this week\'s Monday with labelled cells', async () => {
    mount();
    await screen.findByTestId('travel-days-row-1');
    expect(screen.getAllByTestId(/^travel-cell-/)).toHaveLength(35);
    expect(screen.getByTestId('travel-cell-2026-11-02')).toHaveAttribute('aria-label', 'Mon 2 Nov, today, away (Today only)');
    expect(screen.getByTestId('travel-cell-2026-11-02')).toHaveAttribute('aria-current', 'date');
    expect(screen.getByTestId('travel-cell-2026-11-05')).toHaveAttribute('aria-label', 'Thu 5 Nov, away (London — work)');
    expect(screen.getByTestId('travel-cell-2026-11-05')).toHaveAttribute('data-away', 'true');
    expect(screen.getByTestId('travel-cell-2026-11-03')).toHaveAttribute('aria-label', 'Tue 3 Nov');
    expect(screen.getByTestId('travel-cell-2026-12-01')).toHaveTextContent('1 Dec');
  });

  it('marks away days across a month boundary', async () => {
    mount([{ id: 7, startDate: '2026-11-29', endDate: '2026-12-02', note: null }]);
    await screen.findByTestId('travel-days-row-7');
    const away = screen.getAllByTestId(/^travel-cell-/)
      .filter((cell) => cell.dataset.away === 'true').map((cell) => cell.dataset.testid);
    expect(away).toEqual([
      'travel-cell-2026-11-29', 'travel-cell-2026-11-30', 'travel-cell-2026-12-01', 'travel-cell-2026-12-02',
    ]);
  });

  it('opens a range from an away day (also one in the past) and sets the hash', async () => {
    mount();
    fireEvent.click(await screen.findByTestId('travel-cell-2026-11-05'));
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
    expect(window.location.hash).toBe('#manage/traveldays/1');
  });

  it('opens a past range from its away day, though its row is collapsed', async () => {
    // Thu 5 Nov: the grid begins on Mon 2 Nov, so Mon to Wed are past days.
    vi.setSystemTime(new Date('2026-11-05T10:00:00Z'));
    mount([{ id: 3, startDate: '2026-11-02', endDate: '2026-11-03', note: 'Leeds' }]);
    const cell = await screen.findByTestId('travel-cell-2026-11-02');
    expect(cell).toBeEnabled();
    expect(cell).toHaveAttribute('aria-label', 'Mon 2 Nov, away (Leeds)');
    fireEvent.click(cell);
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Mon 2 Nov → Tue 3 Nov');
  });

  it('opens Add with From set when an empty day from today on is pressed', async () => {
    mount();
    fireEvent.click(await screen.findByTestId('travel-cell-2026-11-12'));
    expect(await screen.findByTestId('travel-add-panel')).toBeInTheDocument();
    expect(screen.getByTestId('travel-day-start')).toHaveValue('2026-11-12');
    expect(screen.getByTestId('travel-day-end')).toHaveValue('');
  });

  it('leaves a past empty day inert', async () => {
    vi.setSystemTime(new Date('2026-11-05T10:00:00Z'));
    mount();
    const cell = await screen.findByTestId('travel-cell-2026-11-03');
    expect(cell).toBeDisabled();
    fireEvent.click(cell);
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();
  });

  it('keeps the real day when a rewind is set (R-D7)', async () => {
    setRewind('2026-01-15T09:00:00Z');
    mount();
    await screen.findByTestId('travel-days-row-1');
    expect(screen.getByTestId('travel-cell-2026-11-02')).toHaveAttribute('aria-current', 'date');
    expect(screen.getByTestId('travel-cell-2026-11-02')).toHaveTextContent('2');
    expect(screen.getByTestId('travel-when-1')).toHaveTextContent('In 2 days');
    expect(screen.queryByTestId('travel-cell-2026-01-15')).not.toBeInTheDocument();
  });
});

describe('TravelDaysView: the hash', () => {
  it('opens the range named by #manage/traveldays/{id}', async () => {
    window.location.hash = '#manage/traveldays/1';
    mount();
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
  });

  it('writes the id when a row opens, and the bare tab when the panel closes', async () => {
    mount();
    await openPanel(2);
    expect(window.location.hash).toBe('#manage/traveldays/2');
    fireEvent.click(screen.getByTestId('travel-panel-close'));
    expect(window.location.hash).toBe('#manage/traveldays');
    expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument();
  });

  it('leaves the bare address for an id the server does not have, replacing the entry', async () => {
    window.location.hash = '#manage/traveldays/999';
    mount();
    await screen.findByTestId('travel-days-row-1');
    await waitFor(() => expect(window.location.hash).toBe('#manage/traveldays'));
    expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument();
  });
});

describe('TravelDaysView: the range panel', () => {
  it('shows the range, its meta and the effect', async () => {
    mount();
    await openPanel(1);
    expect(screen.getByTestId('travel-panel-title')).toHaveTextContent('Wed 4 Nov → Fri 6 Nov');
    expect(screen.getByTestId('travel-panel-meta')).toHaveTextContent('3 days · In 2 days');
    expect(screen.getByTestId('travel-panel-from')).toHaveTextContent('Wed 4 Nov');
    expect(screen.getByTestId('travel-panel-to')).toHaveTextContent('Fri 6 Nov');
    expect(screen.getByTestId('travel-panel-note')).toHaveTextContent('London — work');
    expect(screen.getByTestId('travel-panel-effect'))
      .toHaveTextContent('The Plan tab shows these as Away. No forecast is generated for them, so nothing is rated.');
    expect(screen.getByTestId('travel-panel-remove-section'))
      .toHaveTextContent('These days are forecast again from the next run.');
  });

  it('shows a dash for a range with no note', async () => {
    mount();
    await openPanel(2);
    expect(screen.getByTestId('travel-panel-note')).toHaveTextContent('—');
  });

  it('is labelled for assistive technology and is never a dialog', async () => {
    mount();
    const panel = await openPanel(1);
    expect(panel.closest('aside')).toHaveAttribute('aria-label', 'Wed 4 Nov → Fri 6 Nov range');
    expect(panel.closest('[role="dialog"]')).toBeNull();
  });

  it('edits with the PUT, then reloads and returns to the view', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    expect(screen.getByTestId('travel-edit-start')).toHaveValue('2026-11-04');
    expect(screen.getByTestId('travel-edit-end')).toHaveValue('2026-11-06');
    expect(screen.getByTestId('travel-edit-note')).toHaveValue('London — work');

    fireEvent.change(screen.getByTestId('travel-edit-end'), { target: { value: '2026-11-07' } });
    fireEvent.change(screen.getByTestId('travel-edit-note'), { target: { value: '  Leeds  ' } });
    fetchTravelDays.mockResolvedValue([{ ...LONDON, endDate: '2026-11-07', note: 'Leeds' }, SINGLE]);
    fireEvent.click(screen.getByTestId('travel-edit-save'));

    await waitFor(() => expect(updateTravelDay).toHaveBeenCalledWith(1, {
      startDate: '2026-11-04', endDate: '2026-11-07', note: 'Leeds',
    }));
    expect(await screen.findByTestId('travel-panel-note')).toHaveTextContent('Leeds');
    expect(screen.getByTestId('travel-panel-meta')).toHaveTextContent('4 days');
    expect(screen.queryByTestId('travel-edit-form')).not.toBeInTheDocument();
    expect(fetchTravelDays).toHaveBeenCalledTimes(2);
  });

  it('sends a cleared note as null', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.change(screen.getByTestId('travel-edit-note'), { target: { value: '   ' } });
    fireEvent.click(screen.getByTestId('travel-edit-save'));
    await waitFor(() => expect(updateTravelDay).toHaveBeenCalledWith(1, expect.objectContaining({ note: null })));
  });

  it('refuses an end before the start under To, without calling the API', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.change(screen.getByTestId('travel-edit-end'), { target: { value: '2026-11-01' } });
    fireEvent.click(screen.getByTestId('travel-edit-save'));
    expect(await screen.findByTestId('travel-edit-end-error')).toHaveTextContent('End date must not be before start date');
    expect(updateTravelDay).not.toHaveBeenCalled();
  });

  it('refuses a cleared date under that field', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.change(screen.getByTestId('travel-edit-start'), { target: { value: '' } });
    fireEvent.click(screen.getByTestId('travel-edit-save'));
    expect(await screen.findByTestId('travel-edit-start-error'))
      .toHaveTextContent('Both a start and end date are required');
    expect(updateTravelDay).not.toHaveBeenCalled();
  });

  it('puts a server 400 about the note under the note field and stays in the editor', async () => {
    updateTravelDay.mockRejectedValue({ response: { status: 400, data: { error: 'note must be at most 200 characters' } } });
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.click(screen.getByTestId('travel-edit-save'));
    expect(await screen.findByTestId('travel-edit-note-error')).toHaveTextContent('note must be at most 200 characters');
    expect(screen.getByTestId('travel-edit-form')).toBeInTheDocument();
  });

  it('says "Failed to save travel range" under the form for a failure naming no field', async () => {
    updateTravelDay.mockRejectedValue(new Error('Network Error'));
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.click(screen.getByTestId('travel-edit-save'));
    expect(await screen.findByTestId('travel-edit-error')).toHaveTextContent('Failed to save travel range');
  });

  it('keeps the note to 200 characters in the field itself', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    expect(screen.getByTestId('travel-edit-note')).toHaveAttribute('maxlength', '200');
  });

  it('cancels the editor with Cancel and with Esc, and closes the panel on the next Esc', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.click(screen.getByTestId('travel-edit-cancel'));
    expect(screen.queryByTestId('travel-edit-form')).not.toBeInTheDocument();
    expect(updateTravelDay).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('travel-panel-edit'));
    fireEvent.keyDown(screen.getByTestId('travel-edit-note'), { key: 'Escape' });
    expect(screen.queryByTestId('travel-edit-form')).not.toBeInTheDocument();
    expect(screen.getByTestId('travel-panel')).toBeInTheDocument();

    fireEvent.keyDown(document.body, { key: 'Escape' });
    expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument();
  });

  it('removes with no confirmation, closes the panel and reloads', async () => {
    mount();
    await openPanel(1);
    fetchTravelDays.mockResolvedValue([SINGLE]);
    fireEvent.click(screen.getByTestId('travel-day-delete-1'));

    await waitFor(() => expect(deleteTravelDay).toHaveBeenCalledWith(1));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.queryByTestId('travel-days-row-1')).not.toBeInTheDocument());
    expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/traveldays');
    expect(fetchTravelDays).toHaveBeenCalledTimes(2);
  });

  it('sends one delete for a double press', async () => {
    let finish;
    deleteTravelDay.mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    mount();
    await openPanel(1);
    const remove = screen.getByTestId('travel-day-delete-1');
    fireEvent.click(remove);
    fireEvent.click(remove);
    expect(deleteTravelDay).toHaveBeenCalledTimes(1);
    expect(remove).toHaveAttribute('aria-disabled', 'true');
    finish();
    await waitFor(() => expect(screen.queryByTestId('travel-panel')).not.toBeInTheDocument());
  });

  it('keeps the panel and says so when the remove fails', async () => {
    deleteTravelDay.mockRejectedValue(new Error('down'));
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-day-delete-1'));
    expect(await screen.findByTestId('travel-remove-error')).toHaveTextContent('Failed to remove travel range');
    expect(screen.getByTestId('travel-panel')).toBeInTheDocument();
  });
});

describe('TravelDaysView: Add range', () => {
  async function openAdd() {
    fireEvent.click(await screen.findByTestId('travel-day-add-open'));
    return screen.findByTestId('travel-add-panel');
  }

  it('opens the Add panel with its title, subtitle and an optional note', async () => {
    mount();
    await openAdd();
    expect(screen.getByTestId('travel-panel-title')).toHaveTextContent('Add travel days');
    expect(screen.getByTestId('travel-panel-subtitle')).toHaveTextContent('No forecasts are generated for these dates.');
    expect(screen.getByTestId('travel-day-note')).toHaveAttribute('placeholder', 'London — work');
    expect(screen.getByTestId('travel-day-note')).toHaveAttribute('maxlength', '200');
    expect(screen.getByTestId('travel-day-add')).toHaveTextContent('Add range');
    expect(screen.queryByTestId('travel-add-summary')).not.toBeInTheDocument();
  });

  it('asks for both dates without calling the API', async () => {
    mount();
    await openAdd();
    fireEvent.click(screen.getByTestId('travel-day-add'));
    expect(await screen.findByTestId('travel-day-start-error')).toHaveTextContent('Both a start and end date are required');
    expect(screen.getByTestId('travel-day-end-error')).toHaveTextContent('Both a start and end date are required');
    expect(addTravelDay).not.toHaveBeenCalled();
  });

  it('rejects an inverted range without calling the API', async () => {
    mount();
    await openAdd();
    fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: '2026-11-20' } });
    fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: '2026-11-18' } });
    fireEvent.click(screen.getByTestId('travel-day-add'));
    expect(await screen.findByText('End date must not be before start date')).toBeInTheDocument();
    expect(addTravelDay).not.toHaveBeenCalled();
    expect(screen.queryByTestId('travel-add-summary')).not.toBeInTheDocument();
  });

  it('summarises the range, adds it with a trimmed note, reloads and opens the new range', async () => {
    mount();
    await openAdd();
    fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: '2026-11-18' } });
    fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: '2026-11-20' } });
    fireEvent.change(screen.getByTestId('travel-day-note'), { target: { value: ' Conference ' } });
    expect(screen.getByTestId('travel-add-summary')).toHaveTextContent('3 days');
    expect(screen.getByTestId('travel-add-summary')).toHaveTextContent('Wed 18 Nov to Fri 20 Nov, both included.');

    fetchTravelDays.mockResolvedValue([...RANGES, { id: 9, startDate: '2026-11-18', endDate: '2026-11-20', note: 'Conference' }]);
    fireEvent.click(screen.getByTestId('travel-day-add'));

    await waitFor(() => expect(addTravelDay).toHaveBeenCalledWith({
      startDate: '2026-11-18', endDate: '2026-11-20', note: 'Conference',
    }));
    expect(await screen.findByTestId('travel-panel-title')).toHaveTextContent('Wed 18 Nov → Fri 20 Nov');
    expect(window.location.hash).toBe('#manage/traveldays/9');
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();
    expect(fetchTravelDays).toHaveBeenCalledTimes(2);
  });

  it('sends an empty note as null', async () => {
    mount();
    await openAdd();
    fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: '2026-11-18' } });
    fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: '2026-11-18' } });
    expect(screen.getByTestId('travel-add-summary')).toHaveTextContent('1 day');
    fireEvent.click(screen.getByTestId('travel-day-add'));
    await waitFor(() => expect(addTravelDay).toHaveBeenCalledWith({
      startDate: '2026-11-18', endDate: '2026-11-18', note: null,
    }));
  });

  it('puts a server 400 about the note under the note field', async () => {
    addTravelDay.mockRejectedValue({ response: { status: 400, data: { error: 'note must be at most 200 characters' } } });
    mount();
    await openAdd();
    fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: '2026-11-18' } });
    fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: '2026-11-19' } });
    fireEvent.click(screen.getByTestId('travel-day-add'));
    expect(await screen.findByTestId('travel-day-note-error')).toHaveTextContent('note must be at most 200 characters');
    expect(screen.getByTestId('travel-add-panel')).toBeInTheDocument();
  });

  it('says "Failed to add travel range" for a failure naming no field', async () => {
    addTravelDay.mockRejectedValue(new Error('Network Error'));
    mount();
    await openAdd();
    fireEvent.change(screen.getByTestId('travel-day-start'), { target: { value: '2026-11-18' } });
    fireEvent.change(screen.getByTestId('travel-day-end'), { target: { value: '2026-11-19' } });
    fireEvent.click(screen.getByTestId('travel-day-add'));
    expect(await screen.findByTestId('travel-add-error')).toHaveTextContent('Failed to add travel range');
  });

  it('closes with Cancel and with Esc', async () => {
    mount();
    await openAdd();
    fireEvent.click(screen.getByTestId('travel-add-cancel'));
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();

    await openAdd();
    fireEvent.keyDown(screen.getByTestId('travel-day-note'), { key: 'Escape' });
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();
  });

  it('swaps an open range for Add, and Add for a range', async () => {
    mount();
    await openPanel(1);
    fireEvent.click(screen.getByTestId('travel-day-add-open'));
    expect(await screen.findByTestId('travel-add-panel')).toBeInTheDocument();
    expect(screen.queryByTestId('travel-panel-edit')).not.toBeInTheDocument();
    expect(window.location.hash).toBe('#manage/traveldays');

    fireEvent.click(screen.getByTestId('travel-days-row-2'));
    expect(await screen.findByTestId('travel-panel-edit')).toBeInTheDocument();
    expect(screen.queryByTestId('travel-add-panel')).not.toBeInTheDocument();
  });

  it('keeps the list beside the panel (the row stays selected)', async () => {
    mount();
    await openPanel(2);
    expect(within(screen.getByTestId('travel-split-list')).getByTestId('travel-days-row-2'))
      .toHaveAttribute('aria-current', 'true');
  });
});
